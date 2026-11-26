package ai.deepseek.harness;

import android.content.Context;
import android.os.StatFs;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The built-in Linux engine: a headless Debian (the official `debian:bookworm-slim`
 * arm64 image layer, pinned by digest) unpacked into app storage and run under
 * the bundled proot, which needs no root. The installer script adds Node.js and
 * the published DeepSeek Harness; the start script serves `dsh web` on loopback.
 */
final class LinuxEngine {
    /** Digest of the single layer of `debian:bookworm-slim` for linux/arm64/v8. */
    static final String LAYER_DIGEST = "sha256:7f1aa0b5c351d0542b2066749ed47608e5873bcdd1fd14f4df6f0a05cac02f8e";
    static final long LAYER_SIZE = 28_144_305L;
    /** Free space the install needs: Debian, build tools, Node.js, and the harness's packages. */
    static final long REQUIRED_BYTES = 1_500L * 1024 * 1024;
    /** Registries serving the official Debian image, tried in order: token URL, blob URL prefix. */
    private static final String[][] REGISTRIES = {
        { "https://public.ecr.aws/token/?scope=repository:docker/library/debian:pull",
          "https://public.ecr.aws/v2/docker/library/debian/blobs/" },
        { "https://auth.docker.io/token?service=registry.docker.io&scope=repository:library/debian:pull",
          "https://registry-1.docker.io/v2/library/debian/blobs/" },
    };
    /** Files Android hides from apps under /proc, replaced with static stand-ins when unreadable. */
    private static final String[][] PROC_STAND_INS = {
        { "loadavg", "0.12 0.07 0.02 2/165 765\n" },
        { "stat", "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\n"
                + "intr 63361 0 0 0\nctxt 38014093\nbtime 1694292441\nprocesses 26442\nprocs_running 1\nprocs_blocked 0\n" },
        { "uptime", "124.08 932.80\n" },
        { "version", "Linux version 6.1.0 (dsh-android) #1 SMP PREEMPT\n" },
        { "vmstat", "nr_free_pages 1743136\nnr_inactive_anon 179281\nnr_active_anon 7183\n" },
    };

    /** App-owned skills written into the engine's dsh home before every start, so app updates replace them. */
    private static final String[] APP_SKILLS = { "github-actions" };

    /** Install and run output. */
    interface Output {
        void line(String text);

        void progress(String step, int percent);
    }

    private final Context context;
    private final File base;
    private final File rootfs;

    LinuxEngine(Context context) {
        this.context = context.getApplicationContext();
        this.base = new File(this.context.getFilesDir(), "linux");
        this.rootfs = new File(base, "rootfs");
    }

    /** @return whether the install script finished successfully */
    boolean installed() {
        return new File(rootfs, "root/.dsh-android-installed").isFile();
    }

    /**
     * Download Debian when needed, then run the idempotent install script.
     *
     * @param output receives progress and script output
     * @throws IOException on download, extraction, or script failure
     * @throws InterruptedException when interrupted while the script runs
     */
    void install(Output output) throws IOException, InterruptedException {
        if (!new File(rootfs, "etc/debian_version").isFile()) {
            long free = new StatFs(context.getFilesDir().getPath()).getAvailableBytes();
            if (free < REQUIRED_BYTES) {
                throw new IOException("Not enough free storage: the engine needs about "
                        + (REQUIRED_BYTES >> 20) + " MB, " + (free >> 20) + " MB free");
            }
            File layer = new File(base, "debian.tar.gz");
            download(layer, output);
            extract(layer, output);
            if (!layer.delete()) output.line("could not delete " + layer);
        }
        configure();
        output.progress("Installing Linux tools, Node.js, and DeepSeek Harness", -1);
        int exit = run(output, "/root/install.sh");
        if (exit != 0) throw new IOException("The install script stopped with exit code " + exit);
    }

    /** Delete the whole engine. */
    void remove() throws IOException {
        deleteTree(base);
    }

    /**
     * Start `dsh web` inside Debian.
     *
     * @return the running process; stdout and stderr are merged
     * @throws IOException when proot cannot start
     */
    Process start() throws IOException {
        configure();
        return proot("/root/start.sh").start();
    }

    private void download(File target, Output output) throws IOException {
        IOException last = null;
        for (String[] registry : REGISTRIES) {
            try {
                downloadFrom(registry[0], registry[1] + LAYER_DIGEST, target, output);
                return;
            } catch (IOException failed) {
                output.line("Download from " + new URL(registry[1]).getHost() + " failed: " + failed.getMessage());
                last = failed;
            }
        }
        throw last;
    }

    private void downloadFrom(String tokenUrl, String blobUrl, File target, Output output) throws IOException {
        String token;
        try {
            token = new JSONObject(readAll(open(tokenUrl, null))).getString("token");
        } catch (JSONException malformed) {
            throw new IOException("registry token response was not JSON", malformed);
        }
        HttpURLConnection connection = open(blobUrl, token);
        // Blob URLs redirect to a CDN, which must not receive the registry token.
        for (int hop = 0; hop < 5 && connection.getResponseCode() / 100 == 3; hop++) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            connection = open(new URL(new URL(blobUrl), location).toString(), null);
        }
        if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
        MessageDigest sha256;
        try {
            sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IOException(missing);
        }
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("cannot create " + base);
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int lastPercent = -1;
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
                sha256.update(buffer, 0, read);
                total += read;
                int percent = (int) (total * 100 / LAYER_SIZE);
                if (percent != lastPercent) {
                    output.progress("Downloading Debian (" + (LAYER_SIZE >> 20) + " MB)", Math.min(percent, 100));
                    lastPercent = percent;
                }
            }
        } finally {
            connection.disconnect();
        }
        StringBuilder hex = new StringBuilder("sha256:");
        for (byte b : sha256.digest()) hex.append(String.format("%02x", b));
        if (!hex.toString().equals(LAYER_DIGEST)) throw new IOException("downloaded Debian does not match its pinned digest");
    }

    private void extract(File layer, Output output) throws IOException {
        output.progress("Unpacking Debian", -1);
        deleteTree(rootfs);
        if (!rootfs.mkdirs()) throw new IOException("cannot create " + rootfs);
        TarExtractor extractor = new TarExtractor(rootfs, ANDROID_LINKS, new TarExtractor.Listener() {
            @Override
            public void entry(String path) {
            }

            @Override
            public void skipped(String path, String reason) {
                output.line("skipped " + path + ": " + reason);
            }
        });
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(layer), 64 * 1024), 64 * 1024)) {
            output.line("Unpacked " + extractor.extract(in) + " files");
        }
    }

    /** Write the files Debian needs on Android and the app's scripts; run before every proot start. */
    private void configure() throws IOException {
        write(new File(rootfs, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        write(new File(rootfs, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost ip6-localhost\n");
        for (String script : new String[] { "install.sh", "start.sh" }) {
            File target = new File(rootfs, "root/" + script);
            try (InputStream in = context.getAssets().open("linux/" + script)) {
                write(target, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            chmod(target, 0755);
        }
        for (String skill : APP_SKILLS) {
            try (InputStream in = context.getAssets().open("linux/skills/" + skill + "/SKILL.md")) {
                write(new File(rootfs, "root/.dsh/skills/" + skill + "/SKILL.md"), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        File proc = new File(base, "proc");
        for (String[] standIn : PROC_STAND_INS) write(new File(proc, standIn[0]), standIn[1]);
        File tmp = new File(context.getCacheDir(), "proot");
        if (!tmp.isDirectory() && !tmp.mkdirs()) throw new IOException("cannot create " + tmp);
    }

    private int run(Output output, String script) throws IOException, InterruptedException {
        Process process = proot(script).start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.startsWith("::step:: ")) output.progress(line.substring(9), -1);
                else output.line(line);
            }
        }
        return process.waitFor();
    }

    /**
     * Run one bash command line in Debian.
     *
     * @param commandLine bash source passed to {@code bash -lc}
     * @return the running process; stdout and stderr are merged, stdin is open
     * @throws IOException when proot cannot start
     */
    Process command(String commandLine) throws IOException {
        configure();
        return proot("-lc", commandLine).start();
    }

    /** A proot command running bash with the given arguments in Debian as root, with a clean environment. */
    private ProcessBuilder proot(String... bashArguments) {
        String libs = context.getApplicationInfo().nativeLibraryDir;
        List<String> command = new ArrayList<>(List.of(
                libs + "/libproot.so", "--kill-on-exit", "--link2symlink", "-0",
                "-r", rootfs.getPath(),
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-b", "/proc/self/fd:/dev/fd",
                "-b", new File(rootfs, "tmp").getPath() + ":/dev/shm"));
        File proc = new File(base, "proc");
        for (String[] standIn : PROC_STAND_INS) {
            if (!new File("/proc/" + standIn[0]).canRead()) {
                command.add("-b");
                command.add(new File(proc, standIn[0]).getPath() + ":/proc/" + standIn[0]);
            }
        }
        command.addAll(List.of("-w", "/root", "/usr/bin/env", "-i",
                "HOME=/root", "USER=root", "LANG=C.UTF-8", "TERM=xterm-256color", "TMPDIR=/tmp",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "/bin/bash"));
        command.addAll(List.of(bashArguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("PROOT_LOADER", libs + "/libproot-loader.so");
        env.put("PROOT_TMP_DIR", new File(context.getCacheDir(), "proot").getPath());
        env.put("LD_LIBRARY_PATH", libs);
        return builder;
    }

    private static HttpURLConnection open(String url, String bearer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        return connection;
    }

    private static String readAll(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        if (ANDROID_LINKS.isSymlink(file) && !file.delete()) throw new IOException("cannot replace " + file);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void chmod(File file, int mode) throws IOException {
        ANDROID_LINKS.chmod(file, mode);
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists() && !ANDROID_LINKS.isSymlink(file)) return;
        if (file.isDirectory() && !ANDROID_LINKS.isSymlink(file)) {
            // Directories unpacked read-only must become writable before their entries can go.
            chmod(file, 0700);
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("cannot delete " + file);
    }

    /** Link and mode operations through {@link Os}, which Java's file API lacks on Android. */
    static final TarExtractor.Links ANDROID_LINKS = new TarExtractor.Links() {
        @Override
        public void symlink(String target, File link) throws IOException {
            try {
                Os.symlink(target, link.getPath());
            } catch (ErrnoException failed) {
                throw new IOException("symlink " + link + ": " + failed.getMessage(), failed);
            }
        }

        @Override
        public void hardlink(File existing, File link) throws IOException {
            try {
                Os.link(existing.getPath(), link.getPath());
            } catch (ErrnoException failed) {
                // Some app storage refuses hard links; a copy keeps the file usable.
                try (InputStream in = new FileInputStream(existing); OutputStream out = new FileOutputStream(link)) {
                    in.transferTo(out);
                }
            }
        }

        @Override
        public void chmod(File file, int mode) throws IOException {
            try {
                // The owner keeps read and write so the app can update and delete the tree.
                Os.chmod(file.getPath(), (mode & 07777) | 0600);
            } catch (ErrnoException failed) {
                throw new IOException("chmod " + file + ": " + failed.getMessage(), failed);
            }
        }

        @Override
        public boolean isSymlink(File file) {
            try {
                return OsConstants.S_ISLNK(Os.lstat(file.getPath()).st_mode);
            } catch (ErrnoException missing) {
                return false;
            }
        }
    };
}
