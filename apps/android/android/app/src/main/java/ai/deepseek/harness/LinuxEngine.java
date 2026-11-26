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
                }).install();
        mkdirs(new File(prefix, "tmp"));
        mkdirs(workDir());
        output.progress("Installing DeepSeek Harness", -1);
        int exit = run(output, List.of(node(), new File(prefix, "lib/node_modules/npm/bin/npm-cli.js").getPath(),
                "install", "-g", "--no-fund", "--no-audit", "--allow-scripts=" + ALLOWED_SCRIPTS, DSH_PACKAGE));
        if (exit != 0) throw new IOException("npm install stopped with exit code " + exit);
        installAddon();
        write(new File(home, ".dsh-native-installed"), DSH_PACKAGE + "\n");
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

    /** Copy the harness's Android addon, which the published packages lack, beside its loader. */
    private void installAddon() throws IOException {
        String abi = termuxArch().equals("aarch64") ? "arm64-v8a" : "x86_64";
        String arch = abi.equals("arm64-v8a") ? "arm64" : "x64";
        String name = "node-addon-require-builtin-android-" + arch;
        File target = new File(prefix, "lib/node_modules/@deepseek-ai/dsh/node_modules/" + name);
        mkdirs(target);
        for (String file : new String[] { "index.js", "package.json", "require_builtin.node" }) {
            try (InputStream in = context.getAssets().open("native-addons/" + abi + "/" + name + "/" + file);
                    OutputStream out = new FileOutputStream(new File(target, file))) {
                Io.copy(in, out);
            }
        }
    }

    /** Write the app's skills into the engine's dsh home; run before every start. */
    private void configure() throws IOException {
        mkdirs(new File(prefix, "tmp"));
        mkdirs(workDir());
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
