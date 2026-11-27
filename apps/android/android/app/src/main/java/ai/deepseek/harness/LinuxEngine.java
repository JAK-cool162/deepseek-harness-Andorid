package ai.deepseek.harness;

import android.content.Context;
import android.os.Build;
import android.os.StatFs;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The full engine: the published DeepSeek Harness running natively on Termux's
 * Android builds of Node.js and the usual tools, installed by
 * {@link NativeInstaller} into this app's prefix ({@code files/usr}), with the
 * harness's Android addon from {@code assets/native-addons/}. Programs run
 * directly: the app targets SDK 28, where Android still lets an app execute
 * files in its own storage.
 */
final class LinuxEngine {
    /** Free space the install needs: Termux packages, Node.js, and the harness's packages. */
    static final long REQUIRED_BYTES = 900L * 1024 * 1024;
    static final int PORT = 47400;
    private static final String DSH_PACKAGE = "@deepseek-ai/dsh@alpha";
    /** Install scripts the harness needs; koffi and node-pty have no Android prebuilds and stay unbuilt. */
    private static final String ALLOWED_SCRIPTS = "@deepseek-ai/dsh-subprocess-local,protobufjs";
    /** Asset directory of the engine shipped in the APK; see {@code scripts/bundle-engine.sh}. */
    private static final String BUNDLE = "engine-bundle";
    /** App-owned skills written into the engine's dsh home before every start, so app updates replace them. */
    private static final String[] APP_SKILLS = { "github-actions", "android-phone" };

    /** Install and run output. */
    interface Output {
        void line(String text);

        void progress(String step, int percent);
    }

    private final Context context;
    private final File prefix;
    private final File home;

    LinuxEngine(Context context) {
        this.context = context.getApplicationContext();
        this.prefix = new File(this.context.getFilesDir(), "usr");
        this.home = new File(this.context.getFilesDir(), "home");
    }

    /** @return the agent's working directory */
    File workDir() {
        return new File(home, "work");
    }

    /** @return whether the install finished successfully */
    boolean installed() {
        return new File(home, ".dsh-native-installed").isFile() && dshEntry().isFile();
    }

    /**
     * Install or update the Termux packages, then the harness and its Android addon.
     *
     * @param output receives progress and command output
     * @throws IOException on download, extraction, or command failure
     * @throws InterruptedException when interrupted while npm runs
     */
    void install(Output output) throws IOException, InterruptedException {
        long free = new StatFs(context.getFilesDir().getPath()).getAvailableBytes();
        if (!installed() && free < REQUIRED_BYTES) {
            throw new IOException("Not enough free storage: the engine needs about " + (REQUIRED_BYTES >> 20) + " MB, " + (free >> 20) + " MB free");
        }
        removeLegacyDebian(output);
        new NativeInstaller(termuxArch(), prefix, prefix.getPath(), new File(context.getCacheDir(), "packages"), ANDROID_LINKS,
                new NativeInstaller.Output() {
                    @Override
                    public void line(String text) {
                        output.line(text);
                    }

                    @Override
                    public void progress(String step, int percent) {
                        output.progress(step, percent);
                    }
                }, this::openBundled).install();
        mkdirs(new File(prefix, "tmp"));
        mkdirs(workDir());
        output.progress("Installing DeepSeek Harness", -1);
        String version = bundledVersion();
        if (version != null) {
            unpackBundledHarness(output);
        } else {
            int exit = run(output, List.of(node(), new File(prefix, "lib/node_modules/npm/bin/npm-cli.js").getPath(),
                    "install", "-g", "--no-fund", "--no-audit", "--allow-scripts=" + ALLOWED_SCRIPTS, DSH_PACKAGE));
            if (exit != 0) throw new IOException("npm install stopped with exit code " + exit);
            version = DSH_PACKAGE;
        }
        installAddon();
        write(new File(home, ".dsh-native-installed"), version + "\n");
        output.progress("Done", -1);
    }

    /** Delete the whole engine. */
    void remove() throws IOException {
        deleteTree(prefix);
        deleteTree(home);
        deleteTree(new File(context.getFilesDir(), "linux"));
    }

    /**
     * Start `dsh web` on loopback.
     *
     * @return the running process; stdout and stderr are merged
     * @throws IOException when Node cannot start
     */
    Process start() throws IOException {
        configure();
        ProcessBuilder builder = new ProcessBuilder(node(), dshEntry().getPath(), "web", "--port", String.valueOf(PORT), "--no-open");
        return prepare(builder).start();
    }

    /**
     * Run one bash command line in the engine's environment.
     *
     * @param commandLine bash source passed to {@code bash -c}; not a login shell, whose profile is Termux's
     * @return the running process; stdout and stderr are merged, stdin is open
     * @throws IOException when bash cannot start
     */
    Process command(String commandLine) throws IOException {
        configure();
        return prepare(new ProcessBuilder(new File(prefix, "bin/bash").getPath(), "-c", commandLine)).start();
    }

    /** Termux architecture name for this device. */
    static String termuxArch() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if (abi.equals("arm64-v8a")) return "aarch64";
            if (abi.equals("x86_64")) return "x86_64";
        }
        throw new IllegalStateException("the full engine needs a 64-bit ARM or x86 device");
    }

    private File dshEntry() {
        return new File(prefix, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js");
    }

    private String node() {
        return new File(prefix, "bin/node").getPath();
    }

    /**
     * Add the Android builds of the harness's two native addons, which the published packages lack:
     * require-builtin beside its loader, and this repository's flock addon, whose loader is patched
     * to accept Android because the published one refuses every platform but Linux and macOS.
     */
    private void installAddon() throws IOException {
        String abi = termuxArch().equals("aarch64") ? "arm64-v8a" : "x86_64";
        String arch = abi.equals("arm64-v8a") ? "arm64" : "x64";
        File modules = new File(prefix, "lib/node_modules/@deepseek-ai/dsh/node_modules");
        copyAssetTree("native-addons/" + abi + "/node-addon-require-builtin-android-" + arch,
                new File(modules, "node-addon-require-builtin-android-" + arch));
        copyAssetTree("native-addons/" + abi + "/node-addon-system-android-" + arch,
                new File(modules, "@deepseek-ai/node-addon-system-android-" + arch));
        File flock = new File(modules, "@deepseek-ai/node-addon-system/lib/flock.js");
        String source;
        try (InputStream in = new FileInputStream(flock)) {
            source = new String(Io.readAll(in), StandardCharsets.UTF_8);
        }
        String gate = "platform !== 'linux' && platform !== 'darwin'";
        if (!source.contains(gate + " && platform !== 'android'")) {
            if (!source.contains(gate)) throw new IOException("the flock addon's platform check changed; the Android patch needs updating");
            write(flock, source.replace(gate, gate + " && platform !== 'android'"));
        }
    }

    private void copyAssetTree(String asset, File target) throws IOException {
        String[] children = context.getAssets().list(asset);
        if (children == null || children.length == 0) {
            mkdirs(target.getParentFile());
            try (InputStream in = context.getAssets().open(asset); OutputStream out = new FileOutputStream(target)) {
                Io.copy(in, out);
            }
            return;
        }
        mkdirs(target);
        for (String child : children) copyAssetTree(asset + "/" + child, new File(target, child));
    }

    /**
     * @param name bundle-relative path
     * @return the bundled file, or null when this APK does not ship it
     */
    private InputStream openBundled(String name) throws IOException {
        try {
            return context.getAssets().open(BUNDLE + "/" + termuxArch() + "/" + name);
        } catch (java.io.FileNotFoundException absent) {
            return null;
        }
    }

    /** @return the bundled harness's version line, or null when this APK has no bundled harness */
    private String bundledVersion() throws IOException {
        try (InputStream in = context.getAssets().open(BUNDLE + "/VERSION")) {
            return new String(Io.readAll(in), StandardCharsets.UTF_8).trim();
        } catch (java.io.FileNotFoundException absent) {
            return null;
        }
    }

    /** Replace the installed harness with the pre-installed tree from the APK: no npm, no network. */
    private void unpackBundledHarness(Output output) throws IOException {
        output.progress("Unpacking DeepSeek Harness", -1);
        deleteTree(new File(prefix, "lib/node_modules/@deepseek-ai/dsh"));
        File bin = new File(prefix, "bin/dsh");
        if ((bin.exists() || ANDROID_LINKS.isSymlink(bin)) && !bin.delete()) throw new IOException("cannot replace " + bin);
        TarExtractor extractor = new TarExtractor(prefix, ANDROID_LINKS, new TarExtractor.Listener() {
            @Override
            public void entry(String path) {
            }

            @Override
            public void skipped(String path, String reason) {
                output.line("skipped " + path + ": " + reason);
            }
        });
        try (InputStream in = new java.util.zip.GZIPInputStream(new java.io.BufferedInputStream(
                context.getAssets().open(BUNDLE + "/dsh.tgz"), 256 * 1024), 256 * 1024)) {
            extractor.extract(in);
        }
    }

    /** Write the app's skills and re-apply the Android addons, which app updates may change; run before every start. */
    private void configure() throws IOException {
        mkdirs(new File(prefix, "tmp"));
        mkdirs(workDir());
        // An app update that ships a newer bundled harness replaces the installed one before it starts.
        String version = bundledVersion();
        File marker = new File(home, ".dsh-native-installed");
        if (version != null && marker.isFile()) {
            String installed;
            try (InputStream in = new FileInputStream(marker)) {
                installed = new String(Io.readAll(in), StandardCharsets.UTF_8).trim();
            }
            if (!version.equals(installed)) {
                unpackBundledHarness(new Output() {
                    @Override
                    public void line(String text) {
                    }

                    @Override
                    public void progress(String step, int percent) {
                    }
                });
                write(marker, version + "\n");
            }
        }
        if (dshEntry().isFile()) installAddon();
        for (String skill : APP_SKILLS) {
            try (InputStream in = context.getAssets().open("engine/skills/" + skill + "/SKILL.md")) {
                write(new File(home, ".dsh/skills/" + skill + "/SKILL.md"), new String(Io.readAll(in), StandardCharsets.UTF_8));
            }
        }
    }

    /** Apply the engine's environment and working directory; Termux binaries find libraries and certificates through it. */
    private ProcessBuilder prepare(ProcessBuilder builder) {
        String p = prefix.getPath();
        Map<String, String> env = builder.environment();
        env.clear();
        env.put("PREFIX", p);
        env.put("HOME", home.getPath());
        env.put("TMPDIR", p + "/tmp");
        env.put("PATH", p + "/bin:/system/bin");
        env.put("LD_LIBRARY_PATH", p + "/lib");
        env.put("LANG", "en_US.UTF-8");
        env.put("TERM", "xterm-256color");
        env.put("SHELL", p + "/bin/bash");
        env.put("ANDROID_ROOT", "/system");
        env.put("ANDROID_DATA", "/data");
        env.put("EXTERNAL_STORAGE", "/sdcard");
        env.put("SSL_CERT_FILE", p + "/etc/tls/cert.pem");
        env.put("CURL_CA_BUNDLE", p + "/etc/tls/cert.pem");
        env.put("GIT_EXEC_PATH", p + "/libexec/git-core");
        env.put("GIT_TEMPLATE_DIR", p + "/share/git-core/templates");
        // Termux binaries have Termux's own prefix compiled in for these files; on a phone that
        // directory belongs to another app or is missing, so each path is pointed at this prefix.
        env.put("OPENSSL_CONF", p + "/etc/tls/openssl.cnf");
        env.put("TERMINFO", p + "/share/terminfo");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_ATTR_NOSYSTEM", "1");
        env.put("GIT_SSL_CAINFO", p + "/etc/tls/cert.pem");
        env.put("NODE_EXTRA_CA_CERTS", p + "/etc/tls/cert.pem");
        env.put("XDG_CONFIG_HOME", home.getPath() + "/.config");
        env.put("XDG_CACHE_HOME", home.getPath() + "/.cache");
        // V8 keeps compiled code for the harness's modules here, so starts after the first skip recompiling them.
        env.put("NODE_COMPILE_CACHE", home.getPath() + "/.cache/node-compile");
        env.put("XDG_DATA_HOME", home.getPath() + "/.local/share");
        env.put("PIP_CACHE_DIR", home.getPath() + "/.cache/pip");
        env.put("npm_config_cache", home.getPath() + "/.npm");
        env.put("npm_config_update_notifier", "false");
        env.put("npm_config_prefix", p);
        env.put("npm_config_script_shell", p + "/bin/sh");
        builder.directory(workDir());
        builder.redirectErrorStream(true);
        return builder;
    }

    private int run(Output output, List<String> command) throws IOException, InterruptedException {
        Process process = prepare(new ProcessBuilder(command)).start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) output.line(line);
        }
        return process.waitFor();
    }

    /** The earlier Debian-under-proot engine is replaced; free its space. */
    private void removeLegacyDebian(Output output) throws IOException {
        File legacy = new File(context.getFilesDir(), "linux");
        if (!legacy.exists()) return;
        output.progress("Removing the old Debian engine", -1);
        deleteTree(legacy);
    }

    private static void mkdirs(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("cannot create " + directory);
    }

    private static void write(File file, String text) throws IOException {
        mkdirs(file.getParentFile());
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists() && !ANDROID_LINKS.isSymlink(file)) return;
        if (file.isDirectory() && !ANDROID_LINKS.isSymlink(file)) {
            // Directories unpacked read-only must become writable before their entries can go.
            ANDROID_LINKS.chmod(file, 0700);
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
                    Io.copy(in, out);
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

        @Override
        public String readlink(File file) throws IOException {
            try {
                return Os.readlink(file.getPath());
            } catch (ErrnoException failed) {
                throw new IOException("readlink " + file + ": " + failed.getMessage(), failed);
            }
        }
    };
}
