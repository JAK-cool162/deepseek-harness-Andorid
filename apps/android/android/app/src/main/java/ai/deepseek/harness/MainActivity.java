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
     * Load the Linux engine when one is saved and answering, else the on-device harness.
     *
     * @param safeMode whether the on-device page starts without the custom style
     */
    private void loadHarness(boolean safeMode) {
        String engine = Engine.savedUrl(this);
        if (engine == null) {
            webView.loadUrl(server.bootUrl(safeMode));
            return;
        }
        new Thread(() -> {
            boolean up = Engine.reachable(engine);
            main.post(() -> {
                if (webView == null) return;
                if (up) {
                    webView.loadUrl(engine);
                } else {
                    Toast.makeText(this, R.string.engine_fallback, Toast.LENGTH_LONG).show();
                    webView.loadUrl(server.bootUrl(safeMode));
                }
            });
        }, "dsh-engine-probe").start();
    }

    /** Inject the mobile base style and the Enter shim into a Linux engine page. */
    private void decorateEnginePage(WebView view) {
        String css;
        try (java.io.InputStream in = getAssets().open("www/android/mobile-base.css")) {
            css = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException missing) {
            Log.w(TAG, "mobile base style missing from assets: " + missing);
            css = "";
        }
        String quoted = org.json.JSONObject.quote(css);
        view.evaluateJavascript("(()=>{if(document.querySelector('style[data-dsh-android-base]'))return;"
                + "const s=document.createElement('style');s.dataset.dshAndroidBase='';s.textContent=" + quoted
                + ";document.head.append(s)})();" + ENGINE_ENTER_SHIM, null);
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
                if (Engine.isLoopback(uri.getHost())) return false;
                openExternally(uri);
                return true;
            }

            @Override
            public void onPageFinished(WebView webView, String url) {
                Uri uri = Uri.parse(url);
                if (Engine.isLoopback(uri.getHost()) && uri.getPort() != server.port()) decorateEnginePage(webView);
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
