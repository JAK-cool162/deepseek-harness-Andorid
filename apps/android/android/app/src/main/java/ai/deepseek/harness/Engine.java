package ai.deepseek.harness;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * The Linux engine: a full `dsh web` server the user runs in a Linux
 * environment on the phone (the Android 16 Linux Terminal, or Termux) and the
 * app shows instead of the on-device Worker harness. Only loopback addresses
 * are accepted, so the app never shows a remote server as its own UI.
 */
final class Engine {
    private static final String PREFS = "engine";
    private static final String KEY_URL = "url";
    /** Port the setup commands start `dsh web` on. */
    static final int DEFAULT_PORT = 47400;
    private static final int PROBE_TIMEOUT_MS = 1500;

    private Engine() {
    }

    /**
     * @param context any context of this app
     * @return the saved sign-in URL, or null when on-device mode is selected
     */
    static String savedUrl(Context context) {
        return prefs(context).getString(KEY_URL, null);
    }

    /**
     * @param context any context of this app
     * @param url sign-in URL to use from now on, or null for on-device mode
     */
    static void save(Context context, String url) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (url == null) editor.remove(KEY_URL);
        else editor.putString(KEY_URL, url);
        editor.apply();
    }

    /**
     * Validate a pasted `dsh web` link.
     *
     * @param text user input
     * @return the trimmed URL
     * @throws IllegalArgumentException when it is not an http(s) URL on 127.0.0.1 or localhost
     */
    static String validate(String text) {
        String trimmed = text.trim();
        Uri uri = Uri.parse(trimmed);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("The link must start with http://");
        }
        if (!isLoopback(host)) {
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
     * Whether a server answers at the URL's origin. Blocks; call off the main thread.
     *
     * @param url saved sign-in URL
     * @return true when any HTTP response arrives within the timeout
     */
    static boolean reachable(String url) {
        HttpURLConnection connection = null;
        try {
            Uri uri = Uri.parse(url);
            connection = (HttpURLConnection) new URL(uri.getScheme() + "://" + uri.getAuthority() + "/").openConnection();
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

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
