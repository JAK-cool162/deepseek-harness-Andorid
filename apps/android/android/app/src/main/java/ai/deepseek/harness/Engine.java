package ai.deepseek.harness;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The Termux engine: the full `dsh web` server running in Termux on the same
 * phone, shown instead of the on-device Worker harness. Termux shares the
 * phone's network, so the server is reachable on 127.0.0.1 directly. Only
 * loopback links are accepted, so the app never shows a remote server as its
 * own UI.
 *
 * <p>The first load uses the sign-in link `dsh web` printed, whose token signs
 * a cookie; later loads use the origin alone, because each `dsh web` start
 * prints a new token while the signed cookie stays valid.
 */
final class Engine {
    private static final String TAG = "DshAndroid";
    private static final String PREFS = "engine";
    private static final String KEY_URL = "url";
    private static final String KEY_PAIRED = "paired";
    /** Port the setup commands start `dsh web` on. */
    static final int DEFAULT_PORT = 47400;
    /** Termux's permission for running commands on behalf of other apps. */
    static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    static final String TERMUX_PACKAGE = "com.termux";
    /** The command the app runs in Termux to start the engine. */
    static final String START_COMMAND = "mkdir -p ~/work && cd ~/work && exec dsh web --port " + DEFAULT_PORT + " --no-open";
    private static final int PROBE_TIMEOUT_MS = 1500;

    private Engine() {
    }

    /**
     * @param context any context of this app
     * @return the saved sign-in link, or null when on-device mode is selected
     */
    static String savedUrl(Context context) {
        return prefs(context).getString(KEY_URL, null);
    }

    /**
     * Select the engine, or on-device mode with null. A new link needs a new sign-in.
     *
     * @param context any context of this app
     * @param url sign-in link, or null
     */
    static void save(Context context, String url) {
        SharedPreferences.Editor editor = prefs(context).edit().putBoolean(KEY_PAIRED, false);
        if (url == null) editor.remove(KEY_URL);
        else editor.putString(KEY_URL, url);
        editor.apply();
    }

    /**
     * Record whether the WebView holds a signed cookie for the engine.
     *
     * @param context any context of this app
     * @param paired true after a successful sign-in, false after the engine refuses the cookie
     */
    static void setPaired(Context context, boolean paired) {
        prefs(context).edit().putBoolean(KEY_PAIRED, paired).apply();
    }

    /**
     * @param context any context of this app
     * @param saved the saved sign-in link
     * @return the link before the first sign-in, then the engine's origin
     */
    static String loadUrl(Context context, String saved) {
        return prefs(context).getBoolean(KEY_PAIRED, false) ? origin(saved) : saved;
    }

    /**
     * @param url any engine URL
     * @return its scheme and authority with a trailing slash
     */
    static String origin(String url) {
        Uri uri = Uri.parse(url);
        return uri.getScheme() + "://" + uri.getAuthority() + "/";
    }

    /**
     * Validate a pasted `dsh web` link.
     *
     * @param text user input
     * @return the trimmed link
     * @throws IllegalArgumentException when it is not an http(s) URL on 127.0.0.1 or localhost
     */
    static String validate(String text) {
        String trimmed = text.trim();
        Uri uri = Uri.parse(trimmed);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("The link must start with http://");
        }
        if (!isLoopback(uri.getHost())) {
            throw new IllegalArgumentException("The link must point at 127.0.0.1 or localhost on this phone");
        }
        return trimmed;
    }

    /**
     * @param host URL host
     * @return whether it names this device's loopback interface
     */
    static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host);
    }

    /**
     * Whether a server answers at the link's origin. Blocks; call off the main thread.
     *
     * @param url engine link
     * @return true when any HTTP response arrives within the timeout
     */
    static boolean reachable(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(origin(url)).openConnection();
            connection.setConnectTimeout(PROBE_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.getResponseCode();
            return true;
        } catch (IOException unreachable) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * @param context any context of this app
     * @return whether Termux is installed
     */
    static boolean termuxInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TERMUX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    /**
     * @param context any context of this app
     * @return whether the user granted this app Termux's run-command permission
     */
    static boolean canRunInTermux(Context context) {
        return termuxInstalled(context)
                && context.checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Ask Termux to start `dsh web` as a background command. Termux refuses unless
     * `allow-external-apps = true` is set in `~/.termux/termux.properties`.
     *
     * @param context any context of this app
     * @return whether the request reached Termux
     */
    static boolean startInTermux(Context context) {
        Intent intent = new Intent("com.termux.RUN_COMMAND")
                .setComponent(new ComponentName(TERMUX_PACKAGE, "com.termux.app.RunCommandService"))
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[] { "-lc", START_COMMAND })
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        try {
            context.startService(intent);
            return true;
        } catch (RuntimeException refused) {
            Log.w(TAG, "Termux refused the start command: " + refused);
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
