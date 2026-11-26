package ai.deepseek.harness;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loopback HTTP/1.1 server behind the WebView. It serves the bundled web assets,
 * stores the harness state archive, and relays cross-origin requests from the
 * page so hosts without CORS headers stay reachable.
 *
 * <p>Only 127.0.0.1 is bound. Every request except the boot redirect must carry
 * the per-launch token cookie, so other apps on the device cannot read state or
 * use the relay.
 */
public final class LocalServer {
    /** Bundled web files, addressed by relative path. */
    public interface AssetSource {
        /**
         * Open one asset.
         *
         * @param path relative path without a leading slash
         * @return the asset bytes, or null when no such asset exists
         * @throws IOException when the asset exists but cannot be read
         */
        InputStream open(String path) throws IOException;
    }

    /** Native capabilities the server calls on the app's behalf. */
    public interface Host {
        /**
         * Called whenever the number of relayed requests in flight changes.
         *
         * @param inFlight relayed requests currently open
         */
        void onRelayActivity(int inFlight);

        /**
         * Save a file the page offers to the phone's Downloads folder.
         *
         * @param name file name without directories
         * @param bytes file contents
         * @throws IOException when the file cannot be written
         */
        void saveDownload(String name, byte[] bytes) throws IOException;
    }

    /** Host that ignores activity and refuses saves, for launchers without a device. */
    public static final Host NO_HOST = new Host() {
        @Override
        public void onRelayActivity(int inFlight) {
        }

        @Override
        public void saveDownload(String name, byte[] bytes) throws IOException {
            throw new IOException("saving is unavailable without a device");
        }
    };

    /** Path the WebView loads first; it sets the token cookie and redirects to the page. */
    public static final String BOOT_PATH = "/__dsh_boot";
    /** Saved state archive endpoint. */
    public static final String STATE_PATH = "/__dsh_state";
    /** Endpoint that saves a POST body to Downloads under the `name` query. */
    public static final String SAVE_PATH = "/__dsh_save";
    /** Prefix followed by one percent-encoded absolute URL to relay. */
    public static final String RELAY_PREFIX = "/__dsh_relay/";
    /** Page served for the root path. */
    public static final String ENTRY_PAGE = "android.html";
    /** Suffix the asset assembly appends to gzip archives, which the Android asset pipeline would otherwise inflate. */
    public static final String RAW_SUFFIX = ".raw";

    /**
     * @param path requested asset path
     * @return the packaged asset name holding that path's bytes
     */
    public static String storedAssetName(String path) {
        return path.endsWith(".gz") ? path + RAW_SUFFIX : path;
    }

    private static final String COOKIE = "dsh_token";
    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int MAX_BODY_BYTES = 256 * 1024 * 1024;
    private static final Set<String> REQUEST_HEADERS_DROPPED = Set.of(
            "host", "cookie", "origin", "referer", "connection", "content-length", "accept-encoding",
            "transfer-encoding", "keep-alive", "x-requested-with", "upgrade", "te", "proxy-connection");
    private static final Set<String> RESPONSE_HEADERS_DROPPED = Set.of(
            "connection", "content-length", "content-encoding", "transfer-encoding", "keep-alive",
            "set-cookie", "set-cookie2", "strict-transport-security", "alt-svc");

    private final AssetSource assets;
    private final File stateFile;
    private final String token;
    private final Host host;
    private final java.util.concurrent.atomic.AtomicInteger relaysInFlight = new java.util.concurrent.atomic.AtomicInteger();
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "dsh-local-server");
        thread.setDaemon(true);
        return thread;
    });
    private ServerSocket server;

    /**
     * @param assets bundled web files
     * @param stateFile where the state archive is stored; its parent must exist
     * @param token secret the boot URL carries and the cookie must echo
     * @param host native capabilities; {@link #NO_HOST} when none
     */
    public LocalServer(AssetSource assets, File stateFile, String token, Host host) {
        this.assets = assets;
        this.stateFile = stateFile;
        this.token = token;
        this.host = host;
    }

    /**
     * Bind and start accepting. The preferred port keeps the page origin, and so
     * the WebView's per-origin storage, stable across launches; when it is taken
     * an ephemeral port is used.
     *
     * @param preferredPort port tried first, or 0 for any
     * @return the bound port
     * @throws IOException when no loopback port can be bound
     */
    public synchronized int start(int preferredPort) throws IOException {
        if (server != null) return server.getLocalPort();
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        ServerSocket bound;
        try {
            bound = new ServerSocket(preferredPort, 64, loopback);
        } catch (IOException taken) {
            bound = new ServerSocket(0, 64, loopback);
        }
        server = bound;
        ServerSocket accepting = bound;
        pool.execute(() -> acceptLoop(accepting));
        return bound.getLocalPort();
    }

    /** @return the bound port, or -1 when stopped */
    public synchronized int port() {
        return server == null ? -1 : server.getLocalPort();
    }

    /**
     * @param safeMode whether the page starts without the custom style
     * @return the URL the WebView loads to start the page
     */
    public String bootUrl(boolean safeMode) {
        return "http://127.0.0.1:" + server.getLocalPort() + BOOT_PATH + "?token=" + token + (safeMode ? "&safe=1" : "");
    }

    /** Stop accepting and close the listening socket. */
    public synchronized void stop() {
        if (server == null) return;
        try {
            server.close();
        } catch (IOException ignored) {
            // Closing a listening socket only fails when it is already closed.
        }
        server = null;
        pool.shutdownNow();
    }

    private void acceptLoop(ServerSocket listening) {
        while (!listening.isClosed()) {
            try {
                Socket socket = listening.accept();
                pool.execute(() -> serveConnection(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serveConnection(Socket socket) {
        try (Socket owned = socket) {
            InputStream in = new BufferedInputStream(owned.getInputStream());
            OutputStream out = owned.getOutputStream();
            while (true) {
                Request request = Request.read(in);
                if (request == null) return;
                boolean keepAlive = handle(request, out);
                out.flush();
                if (!keepAlive || "close".equalsIgnoreCase(request.header("connection"))) return;
            }
        } catch (IOException disconnected) {
            // The WebView closed the connection, possibly mid-response after an aborted fetch.
        }
    }

    /** One parsed request with its complete body. */
    static final class Request {
        final String method;
        final String target;
        final Map<String, String> headers;
        final byte[] body;

        Request(String method, String target, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.target = target;
            this.headers = headers;
            this.body = body;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        String path() {
            int query = target.indexOf('?');
            return query < 0 ? target : target.substring(0, query);
        }

        String query(String name) {
            int start = target.indexOf('?');
            if (start < 0) return null;
            for (String pair : target.substring(start + 1).split("&")) {
                int eq = pair.indexOf('=');
                String key = eq < 0 ? pair : pair.substring(0, eq);
                if (key.equals(name)) return eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
            return null;
        }

        static Request read(InputStream in) throws IOException {
            String requestLine = readLine(in);
            if (requestLine == null) return null;
            if (requestLine.isEmpty()) requestLine = readLine(in);
            if (requestLine == null) return null;
            String[] parts = requestLine.split(" ");
            if (parts.length != 3) throw new IOException("malformed request line");
            Map<String, String> headers = new LinkedHashMap<>();
            int total = 0;
            for (String line = readLine(in); line != null && !line.isEmpty(); line = readLine(in)) {
                total += line.length();
                if (total > MAX_HEADER_BYTES) throw new IOException("request headers too large");
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            }
            byte[] body = new byte[0];
            String length = headers.get("content-length");
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                body = readChunked(in);
            } else if (length != null) {
                long size = Long.parseLong(length);
                if (size < 0 || size > MAX_BODY_BYTES) throw new IOException("request body too large");
                body = Io.readN(in, (int) size);
                if (body.length != size) throw new IOException("truncated request body");
            }
            return new Request(parts[0], parts[1], headers, body);
        }

        private static byte[] readChunked(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null) throw new IOException("truncated chunked body");
                int semicolon = sizeLine.indexOf(';');
                int size = Integer.parseInt((semicolon < 0 ? sizeLine : sizeLine.substring(0, semicolon)).trim(), 16);
                if (size == 0) {
                    for (String trailer = readLine(in); trailer != null && !trailer.isEmpty(); trailer = readLine(in)) {
                        // Trailers carry nothing the server uses.
                    }
                    return buffer.toByteArray();
                }
                if (buffer.size() + size > MAX_BODY_BYTES) throw new IOException("request body too large");
                buffer.write(Io.readN(in, size));
                readLine(in);
            }
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int previous = -1;
            while (true) {
                int next = in.read();
                if (next == -1) return line.size() == 0 && previous == -1 ? null : line.toString(StandardCharsets.ISO_8859_1);
                if (next == '\n') {
                    byte[] bytes = line.toByteArray();
                    int end = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
                    return new String(bytes, 0, end, StandardCharsets.ISO_8859_1);
                }
                line.write(next);
                previous = next;
                if (line.size() > MAX_HEADER_BYTES) throw new IOException("request line too long");
            }
        }
    }

    /** @return whether the connection may serve another request */
    private boolean handle(Request request, OutputStream out) throws IOException {
        String path = request.path();
        if (path.equals(BOOT_PATH)) {
            if (!constantTimeEquals(token, request.query("token"))) {
                return sendText(request, out, 403, "Forbidden", "invalid token");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Strict");
            headers.put("Location", "/" + ENTRY_PAGE + (request.query("safe") != null ? "?safe=1" : ""));
            headers.put("Cache-Control", "no-store");
            writeHead(out, 302, "Found", headers, 0L);
            return true;
        }
        if (!constantTimeEquals(token, cookie(request.header("cookie"), COOKIE))) {
            return sendText(request, out, 403, "Forbidden", "missing or invalid session cookie");
        }
        if (path.equals(STATE_PATH)) return handleState(request, out);
        if (path.equals(SAVE_PATH)) return handleSave(request, out);
        if (path.startsWith(RELAY_PREFIX)) {
            host.onRelayActivity(relaysInFlight.incrementAndGet());
            try {
                return relay(request, path.substring(RELAY_PREFIX.length()), out);
            } finally {
                host.onRelayActivity(relaysInFlight.decrementAndGet());
            }
        }
        if (!request.method.equals("GET") && !request.method.equals("HEAD")) {
            return sendText(request, out, 405, "Method Not Allowed", "static assets are read-only");
        }
        return serveAsset(request, path, out);
    }

    private boolean serveAsset(Request request, String path, OutputStream out) throws IOException {
        String relative = URLDecoder.decode(path, StandardCharsets.UTF_8);
        while (relative.startsWith("/")) relative = relative.substring(1);
        if (relative.isEmpty()) relative = ENTRY_PAGE;
        for (String segment : relative.split("/")) {
            if (segment.equals("..") || segment.equals(".") || segment.isEmpty()) {
                return sendText(request, out, 400, "Bad Request", "invalid path");
            }
        }
        InputStream asset;
        try {
            asset = assets.open(relative);
        } catch (IOException missing) {
            asset = null;
        }
        if (asset == null) return sendText(request, out, 404, "Not Found", "no such asset: " + relative);
        try (InputStream owned = asset) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", contentType(relative));
            headers.put("Cache-Control", relative.endsWith(".html") ? "no-cache" : "max-age=31536000, immutable");
            if (request.method.equals("HEAD")) {
                writeHead(out, 200, "OK", headers, 0L);
                return true;
            }
            writeChunked(out, 200, "OK", headers, owned);
            return true;
        }
    }

    private boolean handleState(Request request, OutputStream out) throws IOException {
        switch (request.method) {
            case "HEAD":
            case "GET": {
                if (!stateFile.isFile()) return sendText(request, out, 404, "Not Found", "no saved state");
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", "application/gzip");
                headers.put("Cache-Control", "no-store");
                if (request.method.equals("HEAD")) {
                    writeHead(out, 200, "OK", headers, 0L);
                    return true;
                }
                try (InputStream in = new FileInputStream(stateFile)) {
                    writeChunked(out, 200, "OK", headers, in);
                }
                return true;
            }
            case "PUT": {
                if (request.body.length < 2 || (request.body[0] & 0xff) != 0x1f || (request.body[1] & 0xff) != 0x8b) {
                    return sendText(request, out, 400, "Bad Request", "state must be a gzip member");
                }
                File temp = new File(stateFile.getPath() + ".tmp");
                try (FileOutputStream file = new FileOutputStream(temp)) {
                    file.write(request.body);
                    file.getFD().sync();
                }
                File previous = new File(stateFile.getPath() + ".prev");
                if (stateFile.isFile() && !stateFile.renameTo(previous)) {
                    return sendText(request, out, 500, "Internal Server Error", "could not keep the previous state");
                }
                if (!temp.renameTo(stateFile)) return sendText(request, out, 500, "Internal Server Error", "could not store state");
                return sendText(request, out, 204, "No Content", null);
            }
            case "DELETE": {
                new File(stateFile.getPath() + ".prev").delete();
                if (stateFile.isFile() && !stateFile.delete()) {
                    return sendText(request, out, 500, "Internal Server Error", "could not delete state");
                }
                return sendText(request, out, 204, "No Content", null);
            }
            default:
                return sendText(request, out, 405, "Method Not Allowed", "unsupported method");
        }
    }

    private boolean handleSave(Request request, OutputStream out) throws IOException {
        if (!request.method.equals("POST")) return sendText(request, out, 405, "Method Not Allowed", "use POST");
        String name = request.query("name");
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            return sendText(request, out, 400, "Bad Request", "invalid file name");
        }
        try {
            host.saveDownload(name, request.body);
        } catch (IOException failed) {
            return sendText(request, out, 500, "Internal Server Error", "could not save: " + failed.getMessage());
        }
        return sendText(request, out, 204, "No Content", null);
    }

    private boolean relay(Request request, String encoded, OutputStream out) throws IOException {
        URL target;
        try {
            target = new URL(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException invalid) {
            return sendText(request, out, 400, "Bad Request", "invalid relay target");
        }
        String protocol = target.getProtocol();
        if (!protocol.equals("https") && !protocol.equals("http")) {
            return sendText(request, out, 400, "Bad Request", "relay targets must be http or https");
        }
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        try {
            try {
                connection.setRequestMethod(request.method);
            } catch (java.net.ProtocolException unsupported) {
                return sendText(request, out, 405, "Method Not Allowed", "the relay cannot send " + request.method);
            }
            connection.setConnectTimeout(30_000);
            // Model responses stream for minutes; the page owns cancellation.
            connection.setReadTimeout(0);
            connection.setInstanceFollowRedirects(true);
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : request.headers.entrySet()) {
                String name = header.getKey();
                if (REQUEST_HEADERS_DROPPED.contains(name) || name.startsWith("sec-")) continue;
                connection.setRequestProperty(name, header.getValue());
            }
            boolean hasBody = request.body.length > 0 && !request.method.equals("GET") && !request.method.equals("HEAD");
            if (hasBody) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(request.body.length);
                try (OutputStream upstream = connection.getOutputStream()) {
                    upstream.write(request.body);
                }
            }
            int status;
            try {
                status = connection.getResponseCode();
            } catch (IOException unreachable) {
                return sendText(request, out, 502, "Bad Gateway", "relay could not reach " + target.getHost() + ": " + unreachable.getMessage());
            }
            Map<String, String> headers = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> field : connection.getHeaderFields().entrySet()) {
                String name = field.getKey();
                if (name == null || RESPONSE_HEADERS_DROPPED.contains(name.toLowerCase(Locale.ROOT))) continue;
                headers.put(name, String.join(", ", field.getValue()));
            }
            headers.put("Cache-Control", "no-store");
            String reason = connection.getResponseMessage();
            InputStream body = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (request.method.equals("HEAD") || body == null) {
                writeHead(out, status, reason == null ? "" : reason, headers, 0L);
                return true;
            }
            try (InputStream owned = body) {
                writeChunked(out, status, reason == null ? "" : reason, headers, owned);
            }
            return true;
        } finally {
            connection.disconnect();
        }
    }

    private static String cookie(String header, String name) {
        if (header == null) return null;
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) return trimmed.substring(name.length() + 1);
        }
        return null;
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    static String contentType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json") || lower.endsWith(".map")) return "application/json; charset=utf-8";
        if (lower.endsWith(".webmanifest")) return "application/manifest+json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".gz")) return "application/gzip";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    /** Send a short text response; a HEAD response carries the head only. */
    private static boolean sendText(Request request, OutputStream out, int status, String reason, String text)
            throws IOException {
        byte[] bytes = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        if (text != null) headers.put("Content-Type", "text/plain; charset=utf-8");
        headers.put("Cache-Control", "no-store");
        writeHead(out, status, reason, headers, (long) bytes.length);
        if (!request.method.equals("HEAD")) out.write(bytes);
        return true;
    }

    private static void writeHead(OutputStream out, int status, String reason, Map<String, String> headers, Long length)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        if (length == null) head.append("Transfer-Encoding: chunked\r\n");
        else if (status != 204 && status != 304) head.append("Content-Length: ").append(length).append("\r\n");
        head.append("Connection: keep-alive\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Stream a body in chunks, flushing each so server-sent events reach the page as they arrive. */
    private static void writeChunked(OutputStream out, int status, String reason, Map<String, String> headers,
            InputStream body) throws IOException {
        writeHead(out, status, reason, headers, null);
        byte[] buffer = new byte[64 * 1024];
        while (true) {
            // An upstream failure propagates and closes the connection without the
            // terminating chunk, so the page sees a failed body rather than a complete one.
            int read = body.read(buffer);
            if (read == -1) break;
            if (read == 0) continue;
            out.write((Integer.toHexString(read) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(buffer, 0, read);
            out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
    }
}
