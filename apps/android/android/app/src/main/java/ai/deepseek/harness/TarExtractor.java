package ai.deepseek.harness;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Extracts a tar stream (ustar with GNU long names and PAX paths) into a
 * directory: regular files, directories, symbolic links, and hard links, with
 * permission bits. Device nodes and FIFOs are skipped. Entries whose path leaves the target, or whose parent directory is a
 * symbolic link, are skipped so the archive cannot write outside the target.
 */
final class TarExtractor {
    /** File operations Java's file API lacks; Android implements them with {@code android.system.Os}. */
    interface Links {
        void symlink(String target, File link) throws IOException;

        void hardlink(File existing, File link) throws IOException;

        void chmod(File file, int mode) throws IOException;

        boolean isSymlink(File file);

        String readlink(File file) throws IOException;
    }

    /** Maps an archive path to the path to write, or null to skip the entry. */
    interface PathMap {
        String map(String archivePath);
    }

    /** Writes every entry under its own name. */
    static final PathMap IDENTITY = path -> path;

    /** Progress and skipped-entry reports. */
    interface Listener {
        void entry(String path);

        void skipped(String path, String reason);
    }

    private static final int BLOCK = 512;

    private final File root;
    private final Links links;
    private final Listener listener;
    private final String rootPath;
    private final PathMap pathMap;

    TarExtractor(File root, Links links, Listener listener) throws IOException {
        this(root, links, listener, IDENTITY);
    }

    TarExtractor(File root, Links links, Listener listener, PathMap pathMap) throws IOException {
        this.pathMap = pathMap;
        this.root = root;
        this.links = links;
        this.listener = listener;
        this.rootPath = root.getCanonicalPath() + File.separator;
    }

    /**
     * Extract every entry of an uncompressed tar stream.
     *
     * @param in tar bytes
     * @return the number of entries written
     * @throws IOException on a read or write failure, or a malformed header
     */
    int extract(InputStream in) throws IOException {
        byte[] header = new byte[BLOCK];
        String longName = null;
        String longLink = null;
        Map<String, String> pax = new HashMap<>();
        int written = 0;
        while (true) {
            if (!readFully(in, header)) return written;
            if (isZero(header)) return written;
            verifyChecksum(header);
            char type = (char) header[156];
            long size = parseNumber(header, 124, 12);
            String name = longName != null ? longName : pax.getOrDefault("path", headerName(header));
            String link = longLink != null ? longLink : pax.getOrDefault("linkpath", cString(header, 157, 100));
            int mode = (int) parseNumber(header, 100, 8) & 07777;
            switch (type) {
                case 'L':
                    longName = trimNul(new String(readBody(in, size), StandardCharsets.UTF_8));
                    continue;
                case 'K':
                    longLink = trimNul(new String(readBody(in, size), StandardCharsets.UTF_8));
                    continue;
                case 'x':
                    pax = parsePax(readBody(in, size));
                    continue;
                case 'g':
                    skip(in, size);
                    continue;
                default:
                    break;
            }
            longName = null;
            longLink = null;
            pax = new HashMap<>();
            String mapped = pathMap.map(name);
            if (mapped == null) {
                skip(in, size);
                continue;
            }
            name = mapped;
            if (type == '1') {
                String mappedLink = pathMap.map(link);
                if (mappedLink != null) link = mappedLink;
            }
            File target = resolve(name);
            if (target == null) {
                listener.skipped(name, "path leaves the target directory");
                skip(in, size);
                continue;
            }
            if (parentIsSymlink(target)) {
                listener.skipped(name, "parent directory is a symbolic link");
                skip(in, size);
                continue;
            }
            switch (type) {
                case '5':
                    if (!target.isDirectory() && !target.mkdirs()) throw new IOException("cannot create " + target);
                    links.chmod(target, mode);
                    break;
                case '2':
                    makeParent(target);
                    deleteExisting(target);
                    links.symlink(link, target);
                    break;
                case '1': {
                    File existing = resolve(link);
                    if (existing == null || !existing.exists()) {
                        listener.skipped(name, "hard link target is missing");
                        break;
                    }
                    makeParent(target);
                    deleteExisting(target);
                    links.hardlink(existing, target);
                    break;
                }
                case '0':
                case '\0':
                case '7':
                    makeParent(target);
                    deleteExisting(target);
                    try (OutputStream out = new FileOutputStream(target)) {
                        copy(in, out, size);
                    }
                    skipPadding(in, size);
                    links.chmod(target, mode);
                    break;
                default:
                    listener.skipped(name, "unsupported entry type '" + type + "'");
                    skip(in, size);
                    continue;
            }
            listener.entry(name);
            written++;
        }
    }

    /** @return the file for an archive path inside the target, or null when it leaves the target */
    private File resolve(String name) throws IOException {
        String path = name;
        while (path.startsWith("./")) path = path.substring(2);
        while (path.startsWith("/")) path = path.substring(1);
        if (path.isEmpty() || path.equals(".")) return root;
        for (String segment : path.split("/")) {
            if (segment.equals("..")) return null;
        }
        File file = new File(root, path);
        return (file.getParentFile().getCanonicalPath() + File.separator).startsWith(rootPath) ? file : null;
    }

    private boolean parentIsSymlink(File target) {
        for (File parent = target.getParentFile(); parent != null && !parent.equals(root); parent = parent.getParentFile()) {
            if (links.isSymlink(parent)) return true;
        }
        return false;
    }

    private void deleteExisting(File target) throws IOException {
        if ((target.exists() || links.isSymlink(target)) && !target.isDirectory() && !target.delete()) {
            throw new IOException("cannot replace " + target);
        }
    }

    private static void makeParent(File target) throws IOException {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
    }

    private static String headerName(byte[] header) {
        String name = cString(header, 0, 100);
        boolean ustar = new String(header, 257, 5, StandardCharsets.US_ASCII).equals("ustar");
        String prefix = ustar ? cString(header, 345, 155) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String cString(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String trimNul(String value) {
        int nul = value.indexOf('\0');
        return nul < 0 ? value : value.substring(0, nul);
    }

    /** Octal field, or GNU base-256 when the high bit of the first byte is set. */
    static long parseNumber(byte[] header, int offset, int length) {
        if ((header[offset] & 0x80) != 0) {
            long value = header[offset] & 0x7f;
            for (int index = offset + 1; index < offset + length; index++) value = (value << 8) | (header[index] & 0xff);
            return value;
        }
        long value = 0;
        for (int index = offset; index < offset + length; index++) {
            byte b = header[index];
            if (b == 0 || b == ' ') {
                if (value != 0) break;
                continue;
            }
            if (b < '0' || b > '7') throw new NumberFormatException("bad octal digit in tar header");
            value = value * 8 + (b - '0');
        }
        return value;
    }

    private static void verifyChecksum(byte[] header) throws IOException {
        long expected = parseNumber(header, 148, 8);
        long sum = 0;
        for (int index = 0; index < BLOCK; index++) sum += (index >= 148 && index < 156) ? ' ' : (header[index] & 0xff);
        if (sum != expected) throw new IOException("tar header checksum mismatch");
    }

    private static Map<String, String> parsePax(byte[] body) {
        Map<String, String> records = new HashMap<>();
        int index = 0;
        while (index < body.length) {
            int space = index;
            while (space < body.length && body[space] != ' ') space++;
            if (space >= body.length) break;
            int length = Integer.parseInt(new String(body, index, space - index, StandardCharsets.US_ASCII));
            if (length <= 0 || index + length > body.length) break;
            String record = new String(body, space + 1, index + length - space - 2, StandardCharsets.UTF_8);
            int equals = record.indexOf('=');
            if (equals > 0) records.put(record.substring(0, equals), record.substring(equals + 1));
            index += length;
        }
        return records;
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static boolean readFully(InputStream in, byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int count = in.read(buffer, read, buffer.length - read);
            if (count < 0) {
                if (read == 0) return false;
                throw new IOException("truncated tar stream");
            }
            read += count;
        }
        return true;
    }

    private static byte[] readBody(InputStream in, long size) throws IOException {
        if (size > 16 * 1024 * 1024) throw new IOException("tar metadata entry too large");
        byte[] body = new byte[(int) size];
        if (size > 0 && !readFully(in, body)) throw new IOException("truncated tar stream");
        skipPadding(in, size);
        return body;
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long left = size;
        while (left > 0) {
            int count = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (count < 0) throw new IOException("truncated tar stream");
            out.write(buffer, 0, count);
            left -= count;
        }
    }

    private static void skip(InputStream in, long size) throws IOException {
        discard(in, size);
        skipPadding(in, size);
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long padding = (BLOCK - (size % BLOCK)) % BLOCK;
        discard(in, padding);
    }

    private static void discard(InputStream in, long count) throws IOException {
        byte[] buffer = new byte[8192];
        long left = count;
        while (left > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (read < 0) throw new IOException("truncated tar stream");
            left -= read;
        }
    }
}
