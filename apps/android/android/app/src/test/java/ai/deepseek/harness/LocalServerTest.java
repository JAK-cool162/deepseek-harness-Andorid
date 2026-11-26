package ai.deepseek.harness;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Loopback server routes: token gate, assets, HEAD framing, state storage, and the relay. */
public class LocalServerTest {
    private static final String TOKEN = "test-token";
    private File directory;
    private LocalServer server;
    private String origin;
    private final java.util.List<Integer> activity = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final java.util.Map<String, byte[]> saved = new java.util.concurrent.ConcurrentHashMap<>();

    @Before
    public void start() throws IOException {
        directory = Files.createTempDirectory("dsh-local-server").toFile();
        server = new LocalServer(path -> path.equals("android.html")
                ? new ByteArrayInputStream("<html>page</html>".getBytes(StandardCharsets.UTF_8))
                : null, new File(directory, "state.tar.gz"), TOKEN, new LocalServer.Host() {
                    @Override
                    public void onRelayActivity(int inFlight) {
                        activity.add(inFlight);
                    }

                    @Override
                    public void saveDownload(String name, byte[] bytes) {
                        saved.put(name, bytes);
                    }
                });
        origin = "http://127.0.0.1:" + server.start(0);
    }

    @After
    public void stop() {
        server.stop();
        for (File file : directory.listFiles()) file.delete();
        directory.delete();
    }

    private HttpURLConnection open(String path, String method, boolean withCookie) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(origin + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod(method);
        if (withCookie) connection.setRequestProperty("Cookie", "dsh_token=" + TOKEN);
        return connection;
    }

    private static byte[] body(HttpURLConnection connection) throws IOException {
        InputStream in = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        return in == null ? new byte[0] : in.readAllBytes();
    }

    @Test
    public void bootSetsTheCookieOnlyForTheLaunchToken() throws IOException {
        HttpURLConnection good = open("/__dsh_boot?token=" + TOKEN, "GET", false);
        assertEquals(302, good.getResponseCode());
        assertTrue(good.getHeaderField("Set-Cookie").startsWith("dsh_token=" + TOKEN + ";"));
        assertEquals("/android.html", good.getHeaderField("Location"));
        assertEquals(403, open("/__dsh_boot?token=wrong", "GET", false).getResponseCode());
    }

    @Test
    public void everyOtherRouteRequiresTheCookie() throws IOException {
        assertEquals(403, open("/android.html", "GET", false).getResponseCode());
        assertEquals(403, open("/__dsh_state", "GET", false).getResponseCode());
        HttpURLConnection page = open("/", "GET", true);
        assertEquals(200, page.getResponseCode());
        assertEquals("<html>page</html>", new String(body(page), StandardCharsets.UTF_8));
    }

    @Test
    public void refusesPathTraversal() throws IOException {
        assertEquals(400, open("/assets/../../secret", "GET", true).getResponseCode());
    }

    @Test
    public void headResponsesCarryNoBodySoTheNextResponseStaysFramed() throws IOException {
        // Both requests share one keep-alive connection; a HEAD body would corrupt the GET.
        assertEquals(404, open("/__dsh_state", "HEAD", true).getResponseCode());
        HttpURLConnection page = open("/android.html", "GET", true);
        assertEquals(200, page.getResponseCode());
        assertEquals("<html>page</html>", new String(body(page), StandardCharsets.UTF_8));
    }

    @Test
    public void storesReplacesAndDeletesState() throws IOException {
        byte[] first = { 0x1f, (byte) 0x8b, 1 };
        byte[] second = { 0x1f, (byte) 0x8b, 2 };
        for (byte[] archive : new byte[][] { first, second }) {
            HttpURLConnection put = open("/__dsh_state", "PUT", true);
            put.setDoOutput(true);
            try (OutputStream out = put.getOutputStream()) {
                out.write(archive);
            }
            assertEquals(204, put.getResponseCode());
        }
        assertArrayEquals(second, body(open("/__dsh_state", "GET", true)));
        assertTrue(new File(directory, "state.tar.gz.prev").isFile());
        assertEquals(204, open("/__dsh_state", "DELETE", true).getResponseCode());
        assertFalse(new File(directory, "state.tar.gz").exists());
        assertEquals(404, open("/__dsh_state", "GET", true).getResponseCode());
    }

    @Test
    public void refusesStateThatIsNotGzip() throws IOException {
        HttpURLConnection put = open("/__dsh_state", "PUT", true);
        put.setDoOutput(true);
        try (OutputStream out = put.getOutputStream()) {
            out.write("plain".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(400, put.getResponseCode());
    }

    @Test
    public void relaysMethodHeadersBodyAndStatusWithoutCookies() throws IOException {
        try (ServerSocket upstream = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread echo = new Thread(() -> echoOnce(upstream));
            echo.start();
            String target = "http://127.0.0.1:" + upstream.getLocalPort() + "/v1/messages";
            HttpURLConnection relayed = open("/__dsh_relay/" + URLEncoder.encode(target, StandardCharsets.UTF_8), "POST", true);
            relayed.setRequestProperty("x-api-key", "sk-test");
            relayed.setDoOutput(true);
            try (OutputStream out = relayed.getOutputStream()) {
                out.write("{\"q\":1}".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(201, relayed.getResponseCode());
            assertEquals("text/event-stream", relayed.getHeaderField("Content-Type"));
            assertEquals("POST|sk-test|null|{\"q\":1}", new String(body(relayed), StandardCharsets.UTF_8));
        }
        assertEquals(java.util.List.of(1, 0), activity);
    }

    /** Answer one request with its method, API key, cookie, and body. */
    private static void echoOnce(ServerSocket upstream) {
        try (Socket socket = upstream.accept()) {
            LocalServer.Request request = LocalServer.Request.read(socket.getInputStream());
            String echo = request.method + "|" + request.header("x-api-key") + "|" + request.header("cookie") + "|"
                    + new String(request.body, StandardCharsets.UTF_8);
            byte[] response = echo.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 201 Created\r\nContent-Type: text/event-stream\r\nContent-Length: " + response.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(response);
            out.flush();
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
    }

    @Test
    public void bootKeepsSafeModeOnTheRedirect() throws IOException {
        assertEquals("/android.html?safe=1", open("/__dsh_boot?token=" + TOKEN + "&safe=1", "GET", false).getHeaderField("Location"));
    }

    @Test
    public void savesNamedFilesAndRefusesPathNames() throws IOException {
        HttpURLConnection save = open("/__dsh_save?name=report.html", "POST", true);
        save.setDoOutput(true);
        try (OutputStream out = save.getOutputStream()) {
            out.write("<p>hi</p>".getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(204, save.getResponseCode());
        assertArrayEquals("<p>hi</p>".getBytes(StandardCharsets.UTF_8), saved.get("report.html"));
        HttpURLConnection escape = open("/__dsh_save?name=..%2Fx.html", "POST", true);
        escape.setDoOutput(true);
        try (OutputStream out = escape.getOutputStream()) {
            out.write(1);
        }
        assertEquals(400, escape.getResponseCode());
    }

    @Test
    public void relayRefusesNonHttpTargets() throws IOException {
        String target = URLEncoder.encode("file:///etc/passwd", StandardCharsets.UTF_8);
        assertEquals(400, open("/__dsh_relay/" + target, "GET", true).getResponseCode());
    }
}
