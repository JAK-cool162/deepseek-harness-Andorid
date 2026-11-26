package ai.deepseek.harness;

import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Installs Termux's prebuilt Android packages (bash, coreutils, Node.js, git,
 * gh, Python) into this app's own prefix, so the full harness runs natively
 * with no proot. Packages are resolved from the Termux repository index,
 * verified by SHA-256, and unpacked from each `.deb`'s `data.tar.xz` with the
 * Termux prefix mapped to this app's prefix. Termux paths baked into symbolic
 * links, script shebangs, and git's shell path are then rewritten.
 *
 * <p>Plain Java with no Android APIs, so the same code installs into a test
 * prefix off-device.
 */
final class NativeInstaller {
    /** Where Termux packages expect to live. */
    static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    static final String REPOSITORY = "https://packages.termux.dev/apt/termux-main";
    /** Tools the harness and its agent use. */
    static final String[] ROOTS = {
        "bash", "coreutils", "findutils", "grep", "sed", "gawk", "diffutils", "tar", "gzip", "xz-utils", "less", "procps",
        "dash", "curl", "ca-certificates", "nodejs-lts", "npm", "git", "gh", "python",
    };
    /** Termux app integration this app does not use. */
    private static final Set<String> SKIP = Set.of("termux-tools", "termux-am", "termux-am-socket", "termux-exec", "dialog", "termux-core");
    private static final String ARCHIVE_PREFIX = TERMUX_PREFIX.substring(1) + "/";

    /** Progress and log output. */
    interface Output {
        void line(String text);

        void progress(String step, int percent);
    }

    /** One package from the index. */
    static final class Package {
        final String name;
        final String version;
        final String filename;
        final String sha256;
        final long size;
        /** Each dependency as its alternatives, in index order. */
        final List<List<String>> depends;

        Package(String name, String version, String filename, String sha256, long size, List<List<String>> depends) {
            this.name = name;
            this.version = version;
            this.filename = filename;
            this.sha256 = sha256;
            this.size = size;
            this.depends = depends;
        }
    }

    private final String arch;
    private final File prefixDir;
    private final String prefixPath;
    private final File cache;
    private final TarExtractor.Links links;
    private final Output output;

    /**
     * @param arch Termux architecture name, such as {@code aarch64}
     * @param prefixDir directory to install into
     * @param prefixPath the prefix's path as programs see it at run time; equals {@code prefixDir} on the device
     * @param cache directory for downloaded packages
     * @param links symbolic link and mode operations
     * @param output progress and log output
     */
    NativeInstaller(String arch, File prefixDir, String prefixPath, File cache, TarExtractor.Links links, Output output) {
        this.arch = arch;
        this.prefixDir = prefixDir;
        this.prefixPath = prefixPath;
        this.cache = cache;
        this.links = links;
        this.output = output;
    }

    /**
     * Install every package the roots need that is not installed at its indexed version.
     *
     * @throws IOException on a download, verification, or extraction failure
     */
    void install() throws IOException {
        output.progress("Reading the package list", -1);
        Map<String, Package> index = parseIndex(fetchText(REPOSITORY + "/dists/stable/main/binary-" + arch + "/Packages"));
        List<Package> plan = resolve(index, ROOTS);
        File installed = new File(prefixDir, "var/lib/dsh-android/installed");
        Map<String, String> versions = readInstalled(installed);
        long total = 0;
        for (Package pkg : plan) if (!pkg.version.equals(versions.get(pkg.name))) total += pkg.size;
        long done = 0;
        for (Package pkg : plan) {
            if (pkg.version.equals(versions.get(pkg.name))) continue;
            File deb = download(pkg, done, total);
            output.progress("Unpacking " + pkg.name, -1);
            extractDeb(deb);
            if (!deb.delete()) output.line("could not delete " + deb);
            versions.put(pkg.name, pkg.version);
            writeInstalled(installed, versions);
            done += pkg.size;
        }
        output.progress("Fixing paths", -1);
        relocate();
        output.line("Installed " + plan.size() + " packages into " + prefixPath);
    }

    /** Parse a Debian Packages index. */
    static Map<String, Package> parseIndex(String text) {
        Map<String, Package> index = new HashMap<>();
        for (String block : text.split("\n\n")) {
            Map<String, String> fields = new HashMap<>();
            for (String line : block.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && !line.startsWith(" ")) fields.put(line.substring(0, colon), line.substring(colon + 1).trim());
            }
            String name = fields.get("Package");
            if (name == null || fields.get("Filename") == null) continue;
            List<List<String>> depends = new ArrayList<>();
            for (String part : fields.getOrDefault("Depends", "").split(",")) {
                List<String> alternatives = new ArrayList<>();
                for (String alternative : part.split("\\|")) {
                    String dependency = alternative.trim().split("[ (]")[0].trim();
                    if (!dependency.isEmpty()) alternatives.add(dependency);
                }
                if (!alternatives.isEmpty()) depends.add(alternatives);
            }
            long size = Long.parseLong(fields.getOrDefault("Size", "0"));
            index.put(name, new Package(name, fields.getOrDefault("Version", ""), fields.get("Filename"),
                    fields.getOrDefault("SHA256", ""), size, depends));
        }
        return index;
    }

    /** Dependencies before dependents, skipping Termux app integration. */
    static List<Package> resolve(Map<String, Package> index, String[] roots) throws IOException {
        Set<String> wanted = Set.of(roots);
        Set<String> seen = new LinkedHashSet<>();
        List<Package> order = new ArrayList<>();
        for (String root : roots) {
            if (!index.containsKey(root)) throw new IOException("package " + root + " is missing from the Termux index");
            visit(index, root, wanted, seen, order);
        }
        return order;
    }

    private static void visit(Map<String, Package> index, String name, Set<String> wanted, Set<String> seen, List<Package> order) {
        if (SKIP.contains(name) || !seen.add(name)) return;
        Package pkg = index.get(name);
        if (pkg == null) return;
        for (List<String> alternatives : pkg.depends) visit(index, choose(alternatives, wanted, seen, index), wanted, seen, order);
        order.add(pkg);
    }

    /** Prefer an alternative already chosen or requested, so `nodejs | nodejs-lts` keeps the requested Node. */
    private static String choose(List<String> alternatives, Set<String> wanted, Set<String> seen, Map<String, Package> index) {
        for (String alternative : alternatives) if (seen.contains(alternative) || wanted.contains(alternative)) return alternative;
        for (String alternative : alternatives) if (index.containsKey(alternative)) return alternative;
        return alternatives.get(0);
    }

    private File download(Package pkg, long done, long total) throws IOException {
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("cannot create " + cache);
        File target = new File(cache, pkg.name + ".deb");
        HttpURLConnection connection = (HttpURLConnection) new URL(REPOSITORY + "/" + pkg.filename).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        MessageDigest sha256 = sha256();
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long received = 0;
            int lastPercent = -1;
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
                sha256.update(buffer, 0, read);
                received += read;
                int percent = total == 0 ? 100 : (int) ((done + received) * 100 / total);
                if (percent != lastPercent) {
                    output.progress("Downloading " + pkg.name + " (" + (total >> 20) + " MB in all)", Math.min(percent, 100));
                    lastPercent = percent;
                }
            }
        } finally {
            connection.disconnect();
        }
        if (!hex(sha256.digest()).equals(pkg.sha256)) throw new IOException(pkg.name + " does not match its SHA-256");
        return target;
    }

    /** Unpack a `.deb`'s data member, mapping the Termux prefix to this one. */
    void extractDeb(File deb) throws IOException {
        TarExtractor extractor = new TarExtractor(prefixDir, links, new TarExtractor.Listener() {
            @Override
            public void entry(String path) {
            }

            @Override
            public void skipped(String path, String reason) {
                output.line("skipped " + path + ": " + reason);
            }
        }, path -> {
            String trimmed = path.startsWith("./") ? path.substring(2) : path;
            if (trimmed.startsWith(ARCHIVE_PREFIX)) return trimmed.substring(ARCHIVE_PREFIX.length());
            // Hard-link targets and the prefix's own parent directories.
            if (trimmed.startsWith(TERMUX_PREFIX + "/")) return trimmed.substring(TERMUX_PREFIX.length() + 1);
            return null;
        });
        try (InputStream in = new BufferedInputStream(new FileInputStream(deb), 64 * 1024)) {
            long size = seekArMember(in, "data.tar.xz");
            InputStream member = new BoundedInputStream(in, size);
            extractor.extract(new XZInputStream(member));
        }
    }

    /**
     * Advance an `ar` archive stream to the start of one member.
     *
     * @return the member's size
     */
    static long seekArMember(InputStream in, String wanted) throws IOException {
        byte[] magic = Io.readN(in, 8);
        if (!new String(magic, StandardCharsets.US_ASCII).equals("!<arch>\n")) throw new IOException("not a .deb archive");
        while (true) {
            byte[] header = Io.readN(in, 60);
            if (header.length < 60) throw new IOException(".deb has no " + wanted);
            String name = new String(header, 0, 16, StandardCharsets.US_ASCII).trim();
            if (name.endsWith("/")) name = name.substring(0, name.length() - 1);
            long size = Long.parseLong(new String(header, 48, 10, StandardCharsets.US_ASCII).trim());
            if (name.equals(wanted)) return size;
            Io.skipN(in, size + (size % 2));
        }
    }

    /** Rewrite Termux paths in symbolic links, script shebangs, and git's shell path. */
    void relocate() throws IOException {
        walk(prefixDir);
        String termuxShell = TERMUX_PREFIX + "/bin/sh";
        for (File git : gitBinaries()) patchBinaryString(git, termuxShell, "/system/bin/sh");
    }

    private void walk(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (links.isSymlink(child)) {
                String target = links.readlink(child);
                if (target.startsWith(TERMUX_PREFIX + "/")) {
                    if (!child.delete()) throw new IOException("cannot replace " + child);
                    links.symlink(prefixPath + target.substring(TERMUX_PREFIX.length()), child);
                }
            } else if (child.isDirectory()) {
                walk(child);
            } else if (child.isFile()) {
                rewriteShebang(child);
            }
        }
    }

    private void rewriteShebang(File file) throws IOException {
        byte[] head = new byte[2];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(head) != 2 || head[0] != '#' || head[1] != '!') return;
        }
        byte[] bytes;
        try (InputStream in = new FileInputStream(file)) {
            bytes = Io.readAll(in);
        }
        int newline = 0;
        while (newline < bytes.length && bytes[newline] != '\n') newline++;
        String first = new String(bytes, 0, newline, StandardCharsets.UTF_8);
        if (!first.contains(TERMUX_PREFIX)) return;
        byte[] replaced = first.replace(TERMUX_PREFIX, prefixPath).getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(replaced);
            out.write(bytes, newline, bytes.length - newline);
        }
    }

    private List<File> gitBinaries() {
        List<File> files = new ArrayList<>();
        File git = new File(prefixDir, "bin/git");
        if (git.isFile()) files.add(git);
        File[] core = new File(prefixDir, "libexec/git-core").listFiles();
        if (core != null) for (File file : core) if (file.isFile() && !links.isSymlink(file)) files.add(file);
        return files;
    }

    /** Replace a NUL-terminated string in a binary with a shorter one, padding with NULs. */
    static int patchBinaryString(File file, String from, String to) throws IOException {
        byte[] needle = (from + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] replacement = new byte[needle.length];
        byte[] value = to.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(value, 0, replacement, 0, value.length);
        byte[] bytes;
        try (InputStream in = new FileInputStream(file)) {
            bytes = Io.readAll(in);
        }
        if (bytes.length < 4 || bytes[0] != 0x7f || bytes[1] != 'E' || bytes[2] != 'L' || bytes[3] != 'F') return 0;
        int count = 0;
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            outer:
            for (int index = 0; index <= bytes.length - needle.length; index++) {
                for (int offset = 0; offset < needle.length; offset++) if (bytes[index + offset] != needle[offset]) continue outer;
                out.seek(index);
                out.write(replacement);
                count++;
            }
        }
        return count;
    }

    private static Map<String, String> readInstalled(File file) throws IOException {
        Map<String, String> versions = new HashMap<>();
        if (!file.isFile()) return versions;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                int space = line.indexOf(' ');
                if (space > 0) versions.put(line.substring(0, space), line.substring(space + 1));
            }
        }
        return versions;
    }

    private static void writeInstalled(File file, Map<String, String> versions) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : versions.entrySet()) text.append(entry.getKey()).append(' ').append(entry.getValue()).append('\n');
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String fetchText(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        try (InputStream in = connection.getInputStream()) {
            return new String(Io.readAll(in), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IOException(missing);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format("%02x", b));
        return text.toString();
    }

    /** Reads at most a fixed number of bytes from an underlying stream, leaving the rest unread. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private long left;

        BoundedInputStream(InputStream in, long size) {
            this.in = in;
            this.left = size;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) return -1;
            int value = in.read();
            if (value >= 0) left--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) return -1;
            int count = in.read(buffer, offset, (int) Math.min(length, left));
            if (count > 0) left -= count;
            return count;
        }
    }
}
