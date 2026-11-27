package ai.deepseek.harness;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.SecureRandom;

/**
 * Hosts the harness page in a WebView served by {@link LocalServer}. The
 * harness itself runs in the page's Web Worker; this Activity owns the server,
 * the file picker, external links, and the save-on-pause hook.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "DshAndroid";
    /** Stable loopback port, so the page origin and its WebView storage survive restarts. */
    private static final int PREFERRED_PORT = 47321;
    private static final int FILE_CHOOSER_REQUEST = 1;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 2;
    /** Launcher-shortcut action that starts the page without the custom style. */
    static final String ACTION_SAFE_MODE = "ai.deepseek.harness.SAFE_MODE";
    /** Action the engine screen sends after a change: load the chosen harness again. */
    static final String ACTION_RELOAD = "ai.deepseek.harness.RELOAD";
    /**
     * Engine-page shim: on a touch-first device, plain Enter in the composer breaks the line
     * instead of sending, matching the on-device build. Capture-phase stopping hides the keydown
     * from the editor's submit handler while the browser still inserts the line break.
     */
    /** Scheme the injected settings button navigates to; never loaded, only intercepted. */
    private static final String APP_SCHEME = "dsh-app";
    private static final String ENGINE_ENTER_SHIM = "(()=>{if(window.__dshEnterShim||!matchMedia('(pointer: coarse)').matches)return;"
            + "window.__dshEnterShim=1;addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.ctrlKey&&!e.metaKey&&!e.shiftKey"
            + "&&!e.altKey&&!e.isComposing&&e.target instanceof Element&&e.target.closest('[data-composer-input]'))"
            + "e.stopImmediatePropagation()},true)})()";
    /** Quiet period after the last relayed request before the agent counts as idle; tool steps run in between. */
    private static final long IDLE_AFTER_MS = 20_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LocalServer server;
    private WebView webView;
    private ValueCallback<Uri[]> pendingFileChooser;
    private boolean working;
    private boolean visible;
    private final Runnable becomeIdle = () -> setWorking(false);
    /** Whether the engine answered the current load with 401, so the saved sign-in is stale. */
    private boolean engineSignInRefused;
    /** Longest wait for a Termux-started engine to answer. */
    private static final long ENGINE_START_WAIT_MS = 45_000;
    /** Longest wait for the full engine to print its sign-in link. */
    private static final long BUILTIN_START_WAIT_MS = 90_000;

    private boolean isEnginePage(Uri uri) {
        return Engine.isLoopback(uri.getHost()) && uri.getPort() != server.port();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AssetManager assetManager = getAssets();
        LocalServer.AssetSource assets = path -> {
            try {
                return assetManager.open("www/" + LocalServer.storedAssetName(path), AssetManager.ACCESS_STREAMING);
            } catch (FileNotFoundException missing) {
                return null;
            }
        };
        LocalServer.Host host = new LocalServer.Host() {
            @Override
            public void onRelayActivity(int inFlight) {
                main.post(() -> relayActivity(inFlight));
            }

            @Override
            public void saveDownload(String name, byte[] bytes) throws IOException {
                Downloads.save(MainActivity.this, name, bytes);
            }

            @Override
            public void shareFile(String name, byte[] bytes) throws IOException {
                Uri uri = SharedFiles.put(MainActivity.this, name, bytes);
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType(SharedFiles.mimeType(name))
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_TITLE, name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                send.setClipData(android.content.ClipData.newRawUri(name, uri));
                Intent chooser = Intent.createChooser(send, name).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(chooser));
            }
        };
        server = new LocalServer(assets, new File(getFilesDir(), "harness-state.tar.gz"), newToken(), host);
        try {
            server.start(PREFERRED_PORT);
        } catch (IOException failed) {
            TextView message = new TextView(this);
            message.setText(getString(R.string.server_failed, failed.getMessage()));
            message.setPadding(48, 48, 48, 48);
            setContentView(message);
            return;
        }

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        configure(webView);
        FrameLayout root = new FrameLayout(this);
        root.addView(webView);
        keepClearOfSystemBars(root);
        setContentView(root);
        loadHarness(ACTION_SAFE_MODE.equals(getIntent().getAction()));
        requestNotificationPermission();
    }

    /**
     * Load the Termux engine when one is saved, starting it through Termux when it is
     * not running and the user allowed that; otherwise load the on-device harness.
     *
     * @param safeMode whether the on-device page starts without the custom style
     */
    private void loadHarness(boolean safeMode) {
        if (Engine.builtinSelected(this) && new LinuxEngine(this).installed()) {
            loadBuiltinEngine(safeMode);
            return;
        }
        String engine = Engine.savedUrl(this);
        if (engine == null) {
            webView.loadUrl(server.bootUrl(safeMode));
            return;
        }
        new Thread(() -> {
            boolean up = Engine.reachable(engine);
            if (!up && Engine.canRunInTermux(this) && Engine.startInTermux(this)) {
                main.post(() -> Toast.makeText(this, R.string.engine_starting, Toast.LENGTH_LONG).show());
                long deadline = System.currentTimeMillis() + ENGINE_START_WAIT_MS;
                while (!up && System.currentTimeMillis() < deadline) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    up = Engine.reachable(engine);
                }
            }
            boolean ready = up;
            main.post(() -> {
                if (webView == null) return;
                if (ready) {
                    engineSignInRefused = false;
                    webView.loadUrl(Engine.loadUrl(this, engine));
                } else {
                    Toast.makeText(this, R.string.engine_fallback, Toast.LENGTH_LONG).show();
                    webView.loadUrl(server.bootUrl(safeMode));
                }
            });
        }, "dsh-engine-probe").start();
    }

    /** A light page with the whale while the engine starts, instead of a blank screen. */
    private void showStartingPage() {
        String label = android.text.Html.escapeHtml(getString(R.string.engine_starting_builtin));
        webView.loadDataWithBaseURL(null, "<!doctype html><meta name=viewport content='width=device-width'>"
                + "<style>:root{color-scheme:light dark;--fg:#0F1115;--sub:#61666B;--bg:#fff;--accent:#4176E6}"
                + "@media (prefers-color-scheme:dark){:root{--fg:#F9FAFB;--sub:#ADB2B8;--bg:#151517;--accent:#7AAAFF}}"
                + "body{margin:0;height:100vh;display:grid;place-content:center;justify-items:center;gap:20px;background:var(--bg);"
                + "font:15px system-ui,sans-serif;color:var(--sub)}svg{width:64px;height:64px;fill:var(--accent);"
                + "animation:p 1.6s ease-in-out infinite}@keyframes p{50%{opacity:.45;transform:scale(.94)}}"
                + "@media (prefers-reduced-motion:reduce){svg{animation:none}}</style>"
                + "<svg viewBox='0 0 50 50' aria-hidden='true'><path d='" + WHALE_PATH + "'/></svg><div>" + label + "</div>",
                "text/html", "utf-8", null);
    }

    /** The DeepSeek whale from the launcher icon, in a 50-unit viewBox. */
    private static final String WHALE_PATH = "M48.8354 10.0479C48.3232 9.79199 48.1025 10.2798 47.8032 10.5278C47.7007 10.6079 47.6143 10.7119 47.5273 10.8076C46.7793 11.624 45.9048 12.1597 44.7622 12.0957C43.0923 12 41.666 12.5356 40.4058 13.8398C40.1377 12.2319 39.2476 11.272 37.8926 10.6558C37.1836 10.3359 36.4668 10.0156 35.9702 9.31982C35.6235 8.82373 35.5293 8.27197 35.356 7.72754C35.2456 7.3999 35.1353 7.06396 34.7651 7.00781C34.3633 6.94385 34.2056 7.2876 34.0479 7.57568C33.418 8.75195 33.1733 10.0479 33.1973 11.3599C33.2524 14.312 34.4736 16.6641 36.8999 18.3359C37.1758 18.5278 37.2466 18.7197 37.1597 19C36.9946 19.5757 36.7974 20.1357 36.624 20.7119C36.5137 21.0801 36.3486 21.1597 35.9624 21C34.6309 20.4321 33.481 19.5918 32.4644 18.5757C30.7393 16.8721 29.1792 14.9917 27.2334 13.52C26.7764 13.1758 26.3193 12.856 25.8467 12.5518C23.8618 10.584 26.1069 8.96777 26.627 8.77588C27.1704 8.57568 26.8159 7.8877 25.0591 7.896C23.3022 7.90381 21.6953 8.50391 19.647 9.30371C19.3477 9.42383 19.0322 9.51172 18.7095 9.58398C16.8501 9.22363 14.9199 9.14355 12.9033 9.37598C9.10596 9.80762 6.07275 11.6396 3.84326 14.7681C1.16455 18.5278 0.53418 22.7998 1.30664 27.2559C2.11768 31.9521 4.46582 35.8398 8.07373 38.8799C11.8159 42.0322 16.1255 43.5762 21.041 43.2803C24.0269 43.104 27.3516 42.6963 31.1016 39.4561C32.0469 39.936 33.0396 40.1279 34.686 40.272C35.9546 40.3921 37.1758 40.208 38.1211 40.0078C39.6021 39.688 39.4995 38.2881 38.9639 38.0322C34.623 35.9678 35.5762 36.8081 34.71 36.1279C36.9155 33.4639 40.2402 30.6958 41.54 21.728C41.6426 21.0161 41.5557 20.5679 41.54 19.9917C41.5322 19.6396 41.6108 19.5039 42.0049 19.4639C43.0923 19.3359 44.1479 19.0317 45.1167 18.4878C47.9292 16.9199 49.064 14.3438 49.3315 11.2559C49.3711 10.7837 49.3237 10.2959 48.8354 10.0479ZM24.3262 37.8398C20.1196 34.4639 18.0791 33.3521 17.2358 33.3999C16.4482 33.4482 16.5898 34.3682 16.7632 34.9678C16.9443 35.5601 17.1812 35.9683 17.5117 36.4878C17.7402 36.832 17.8979 37.3442 17.2832 37.728C15.9282 38.584 13.5728 37.4399 13.4624 37.3838C10.7207 35.7358 8.42822 33.5601 6.81348 30.584C5.25342 27.7197 4.34766 24.6479 4.19775 21.3677C4.1582 20.5757 4.38672 20.2959 5.15869 20.1519C6.17529 19.96 7.22314 19.9199 8.23926 20.0718C12.5327 20.7119 16.1885 22.6719 19.2529 25.7759C21.002 27.5439 22.3252 29.6558 23.6885 31.7202C25.1377 33.9121 26.6978 36 28.6831 37.7119C29.3843 38.312 29.9434 38.7681 30.479 39.104C28.8643 39.2881 26.1699 39.3281 24.3262 37.8398ZM26.3433 24.6001C26.3433 24.248 26.6191 23.9678 26.9658 23.9678C27.0444 23.9678 27.1152 23.9839 27.1782 24.0078C27.2651 24.04 27.3438 24.0879 27.4067 24.1602C27.5171 24.272 27.5801 24.4321 27.5801 24.6001C27.5801 24.9521 27.3042 25.2319 26.9575 25.2319C26.6108 25.2319 26.3433 24.9521 26.3433 24.6001ZM32.6064 27.8799C32.2046 28.0479 31.8027 28.1919 31.4165 28.208C30.8179 28.2397 30.1641 27.9922 29.8096 27.688C29.2583 27.2158 28.8643 26.9521 28.6987 26.1279C28.6279 25.7759 28.6675 25.2319 28.7305 24.9199C28.8721 24.248 28.7144 23.8159 28.2495 23.4238C27.8716 23.104 27.3911 23.0161 26.8633 23.0161C26.666 23.0161 26.4849 22.9277 26.3511 22.856C26.1304 22.7441 25.9492 22.4639 26.1226 22.1201C26.1777 22.0078 26.4458 21.7358 26.5088 21.688C27.2256 21.272 28.0527 21.4077 28.8169 21.7197C29.5259 22.0161 30.0615 22.5601 30.834 23.3281C31.6216 24.2559 31.7632 24.5117 32.2124 25.208C32.5669 25.752 32.8901 26.312 33.1104 26.9521C33.2446 27.3521 33.0713 27.6802 32.6064 27.8799Z";

    /**
     * Start the built-in Linux engine when it is not running, wait for the sign-in link it
     * prints, and load it; fall back to the on-device harness when it does not come up.
     */
    private void loadBuiltinEngine(boolean safeMode) {
        String running = EngineService.signInUrl();
        if (running != null) {
            webView.loadUrl(running);
            return;
        }
        EngineService.State state = EngineService.state();
        if (state != EngineService.State.STARTING && state != EngineService.State.INSTALLING) {
            EngineService.send(this, EngineService.ACTION_START);
        }
        showStartingPage();
        long deadline = System.currentTimeMillis() + BUILTIN_START_WAIT_MS;
        Runnable poll = new Runnable() {
            @Override
            public void run() {
                if (webView == null) return;
                String url = EngineService.signInUrl();
                if (url != null) {
                    webView.loadUrl(url);
                } else if (System.currentTimeMillis() > deadline || EngineService.state() == EngineService.State.STOPPED) {
                    Toast.makeText(MainActivity.this, R.string.engine_builtin_fallback, Toast.LENGTH_LONG).show();
                    webView.loadUrl(server.bootUrl(safeMode));
                } else {
                    main.postDelayed(this, 500);
                }
            }
        };
        main.postDelayed(poll, 500);
    }

    /** Inject the mobile base style and the Enter shim into a Termux engine page. */
    private void decorateEnginePage(WebView view) {
        injectStyle(view, "data-dsh-android-base", readAsset("www/android/mobile-base.css"));
        view.evaluateJavascript(ENGINE_ENTER_SHIM, null);
    }

    /** Layer the selected style preset under any custom theme, in either mode. */
    private void applyStylePreset(WebView view) {
        String preset = Style.preset(this);
        if (!Style.DEFAULT.equals(preset)) injectStyle(view, "data-dsh-android-preset", readAsset(Style.assetPath(preset)));
        injectStyle(view, "data-dsh-android-look", Style.lookCss(this));
    }

    private void injectStyle(WebView view, String attribute, String css) {
        if (css.isEmpty()) return;
        String key = org.json.JSONObject.quote(attribute);
        // Before the custom theme when it exists, so the agent's own theme.css still wins.
        view.evaluateJavascript("(()=>{const k=" + key + ";if(document.querySelector('style['+k+']'))return;"
                + "const s=document.createElement('style');s.setAttribute(k,'');s.textContent=" + org.json.JSONObject.quote(css)
                + ";const t=document.querySelector('style[data-dsh-android-theme]');"
                + "t?t.before(s):document.head.append(s)})()", null);
    }

    private String readAsset(String path) {
        try (java.io.InputStream in = getAssets().open(path)) {
            return new String(Io.readAll(in), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException missing) {
            Log.w(TAG, "asset missing: " + path + ": " + missing);
            return "";
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (webView == null || server == null) return;
        if (ACTION_SAFE_MODE.equals(intent.getAction())) webView.loadUrl(server.bootUrl(true));
        else if (ACTION_RELOAD.equals(intent.getAction())) loadHarness(false);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void relayActivity(int inFlight) {
        if (inFlight > 0) {
            main.removeCallbacks(becomeIdle);
            setWorking(true);
        } else {
            main.removeCallbacks(becomeIdle);
            main.postDelayed(becomeIdle, IDLE_AFTER_MS);
        }
    }

    private void setWorking(boolean next) {
        if (working == next) return;
        working = next;
        if (next) WorkingService.start(this);
        else WorkingService.stop(this, !visible);
        if (webView != null) {
            webView.evaluateJavascript("globalThis.__DSH_ANDROID_WORKING__ && globalThis.__DSH_ANDROID_WORKING__(" + next + ")", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
    }

    /**
     * Pad the page out of the status bar, navigation bar, display cutout, and
     * keyboard. Android 15 draws apps edge to edge, which would otherwise put the
     * page's header buttons under the status bar where taps do not reach them.
     */
    @SuppressWarnings("deprecation")
    private static void keepClearOfSystemBars(View root) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            root.setFitsSystemWindows(true);
            return;
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets clear = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.ime());
            view.setPadding(clear.left, clear.top, clear.right, clear.bottom);
            return WindowInsets.CONSUMED;
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setTextZoom(100);
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (APP_SCHEME.equals(uri.getScheme())) {
                    // Only the app's own top-level page opens native screens; sandboxed artifact frames cannot.
                    if (request.isForMainFrame() && "settings".equals(uri.getHost())) {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                    return true;
                }
                if (Engine.isLoopback(uri.getHost())) return false;
                openExternally(uri);
                return true;
            }

            @Override
            public void onReceivedHttpError(WebView webView, WebResourceRequest request, android.webkit.WebResourceResponse response) {
                // The engine refuses an expired or missing sign-in cookie with 401 on the page itself.
                if (request.isForMainFrame() && response.getStatusCode() == 401 && isEnginePage(request.getUrl())) {
                    engineSignInRefused = true;
                    Engine.setPaired(MainActivity.this, false);
                    Toast.makeText(MainActivity.this, R.string.engine_sign_in_again, Toast.LENGTH_LONG).show();
                    startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                }
            }

            @Override
            public void onPageFinished(WebView webView, String url) {
                Uri uri = Uri.parse(url);
                if (!Engine.isLoopback(uri.getHost())) return;
                if (isEnginePage(uri)) {
                    if (!engineSignInRefused) Engine.setPaired(MainActivity.this, true);
                    decorateEnginePage(webView);
                }
                applyStylePreset(webView);
                webView.evaluateJavascript(readAsset("www/android/settings-phone-tab.js"), null);
            }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
                pendingFileChooser = callback;
                Intent intent = params.createIntent();
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException none) {
                    pendingFileChooser = null;
                    callback.onReceiveValue(null);
                    return false;
                }
                return true;
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.println(message.messageLevel() == ConsoleMessage.MessageLevel.ERROR ? Log.ERROR : Log.INFO, TAG,
                        message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
        });
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException none) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || pendingFileChooser == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                result = new Uri[data.getClipData().getItemCount()];
                for (int index = 0; index < result.length; index++) result[index] = data.getClipData().getItemAt(index).getUri();
            } else if (data.getData() != null) {
                result = new Uri[] { data.getData() };
            }
        }
        pendingFileChooser.onReceiveValue(result);
        pendingFileChooser = null;
    }

    @Override
    protected void onPause() {
        visible = false;
        // The page also saves on visibilitychange; this covers WebViews that pause timers first.
        if (webView != null) {
            webView.evaluateJavascript("globalThis.__DSH_ANDROID_SAVE__ && globalThis.__DSH_ANDROID_SAVE__()", null);
        }
        super.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        // Keep the Worker alive: leaving would discard any unsaved turn.
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        if (server != null) server.stop();
        main.removeCallbacks(becomeIdle);
        if (working) WorkingService.stop(this, false);
        super.onDestroy();
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) hex.append(String.format("%02x", value));
        return hex.toString();
    }
}
