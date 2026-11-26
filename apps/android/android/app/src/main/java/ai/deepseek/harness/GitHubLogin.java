package ai.deepseek.harness;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub sign-in for the full engine through GitHub's own CLI: `gh auth login --web`
 * prints a one-time device code, which the user enters at github.com/login/device;
 * `gh auth setup-git` then lets git push with the same sign-in. No token passes
 * through this app.
 */
final class GitHubLogin {
    /** Page where the user enters the device code. */
    static final String DEVICE_PAGE = "https://github.com/login/device";
    private static final Pattern CODE = Pattern.compile("one-time code: ([A-Z0-9]{4}-[A-Z0-9]{4})");
    private static final String SIGNED_IN_MARK = "::signed-in:: ";
    private static final String COMMAND = "export BROWSER=true GH_PROMPT_DISABLED=1; "
            + "gh auth login --hostname github.com --git-protocol https --web --scopes workflow "
            + "&& gh auth setup-git && echo \"" + SIGNED_IN_MARK + "$(gh api user --jq .login)\"";
    private static final String STATUS = "gh api user --jq .login 2>/dev/null | sed 's/^/" + SIGNED_IN_MARK + "/'";

    /** Callbacks on the worker thread. */
    interface Listener {
        void code(String code);

        void done(String user, String error);
    }

    private GitHubLogin() {
    }

    /**
     * Start the device-code sign-in on a worker thread.
     *
     * @param linux the installed engine
     * @param listener receives the code, then the signed-in user name or an error
     */
    static void start(LinuxEngine linux, Listener listener) {
        new Thread(() -> run(linux, COMMAND, listener), "dsh-github-login").start();
    }

    /**
     * Look up the signed-in user on a worker thread.
     *
     * @param linux the installed engine
     * @param listener receives the user name, or null for none
     */
    static void status(LinuxEngine linux, Listener listener) {
        new Thread(() -> run(linux, STATUS, listener), "dsh-github-status").start();
    }

    private static void run(LinuxEngine linux, String commandLine, Listener listener) {
        String user = null;
        StringBuilder tail = new StringBuilder();
        try {
            Process process = linux.command(commandLine);
            try (OutputStream stdin = process.getOutputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    Matcher code = CODE.matcher(line);
                    if (code.find()) {
                        listener.code(code.group(1));
                        // gh waits for Enter before opening a browser; the app opens the page itself.
                        stdin.write('\n');
                        stdin.flush();
                    } else if (line.startsWith(SIGNED_IN_MARK)) {
                        String name = line.substring(SIGNED_IN_MARK.length()).trim();
                        if (!name.isEmpty()) user = name;
                    } else {
                        tail.setLength(0);
                        tail.append(line);
                    }
                }
            }
            int exit = process.waitFor();
            listener.done(user, exit == 0 || user != null ? null : tail.toString());
        } catch (IOException failed) {
            listener.done(null, failed.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            listener.done(null, "interrupted");
        }
    }
}
