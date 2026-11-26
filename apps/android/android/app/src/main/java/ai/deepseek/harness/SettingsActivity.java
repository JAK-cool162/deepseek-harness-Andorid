package ai.deepseek.harness;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * App settings: the built-in style preset, and where the harness runs
 * (on-device, or the Termux engine with its one-time setup).
 */
public final class SettingsActivity extends Activity {
    /** One-time Termux setup; the last line starts the engine by hand. */
    static final String SETUP_COMMANDS =
            "pkg update -y && pkg install -y nodejs-lts git python clang make binutils\n"
            + "termux-setup-storage\n"
            + "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings\n"
            + "npm install -g --allow-scripts=@deepseek-ai/dsh-subprocess-local,koffi,node-pty,protobufjs @deepseek-ai/dsh@alpha\n"
            + Engine.START_COMMAND;
    private static final String TERMUX_DOWNLOAD = "https://f-droid.org/packages/com.termux/";
    private static final int RUN_COMMAND_REQUEST = 1;

    private TextView status;
    private EditText link;
    private Button defaultStyle;
    private Button glassStyle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(20);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        column.addView(text(getString(R.string.settings_title), 24, true));

        column.addView(text(getString(R.string.style_heading), 18, true));
        column.addView(text(getString(R.string.style_intro), 14, false));
        defaultStyle = button(getString(R.string.style_default), () -> chooseStyle(Style.DEFAULT));
        glassStyle = button(getString(R.string.style_liquid_glass), () -> chooseStyle(Style.LIQUID_GLASS));
        column.addView(defaultStyle);
        column.addView(glassStyle);
        markStyle();

        column.addView(text(getString(R.string.engine_heading), 18, true));
        column.addView(text(getString(R.string.engine_intro), 14, false));
        column.addView(text(getString(R.string.engine_step_install), 14, false));
        column.addView(button(getString(R.string.engine_get_termux), () -> open(Uri.parse(TERMUX_DOWNLOAD))));
        column.addView(text(getString(R.string.engine_step_commands), 14, false));
        TextView commands = text(SETUP_COMMANDS, 12, false);
        commands.setTypeface(Typeface.MONOSPACE);
        commands.setTextIsSelectable(true);
        commands.setBackgroundColor(0x22FFFFFF);
        commands.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        column.addView(commands);
        column.addView(button(getString(R.string.engine_copy), () -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("dsh setup", SETUP_COMMANDS));
            Toast.makeText(this, R.string.engine_copied, Toast.LENGTH_SHORT).show();
        }));
        column.addView(button(getString(R.string.engine_open_termux), this::openTermux));
        column.addView(text(getString(R.string.engine_step_permission), 14, false));
        column.addView(button(getString(R.string.engine_allow_run), this::requestRunCommand));
        column.addView(text(getString(R.string.engine_step_link), 14, false));

        link = new EditText(this);
        link.setHint("http://127.0.0.1:" + Engine.DEFAULT_PORT + "/?token=…");
        link.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        link.setSingleLine(true);
        String saved = Engine.savedUrl(this);
        if (saved != null) link.setText(saved);
        column.addView(link);
        column.addView(button(getString(R.string.engine_connect), this::connect));
        column.addView(button(getString(R.string.engine_use_device), () -> {
            Engine.save(this, null);
            restart();
        }));
        status = text(saved == null ? getString(R.string.engine_status_device) : getString(R.string.engine_status_termux), 14, false);
        column.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(column);
        setContentView(scroll);
    }

    private void chooseStyle(String preset) {
        Style.setPreset(this, preset);
        markStyle();
        restart();
    }

    private void markStyle() {
        boolean glass = Style.LIQUID_GLASS.equals(Style.preset(this));
        defaultStyle.setText(getString(R.string.style_default) + (glass ? "" : "  ✓"));
        glassStyle.setText(getString(R.string.style_liquid_glass) + (glass ? "  ✓" : ""));
    }

    private void requestRunCommand() {
        if (!Engine.termuxInstalled(this)) {
            status.setText(R.string.engine_no_termux);
            return;
        }
        requestPermissions(new String[] { Engine.RUN_COMMAND_PERMISSION }, RUN_COMMAND_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != RUN_COMMAND_REQUEST) return;
        status.setText(Engine.canRunInTermux(this) ? R.string.engine_run_allowed : R.string.engine_run_denied);
    }

    private void openTermux() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(Engine.TERMUX_PACKAGE);
        if (launch == null) {
            status.setText(R.string.engine_no_termux);
            return;
        }
        startActivity(launch);
    }

    private void connect() {
        String url;
        try {
            url = Engine.validate(link.getText().toString());
        } catch (IllegalArgumentException invalid) {
            status.setText(invalid.getMessage());
            return;
        }
        status.setText(R.string.engine_checking);
        String chosen = url;
        new Thread(() -> {
            boolean up = Engine.reachable(chosen);
            runOnUiThread(() -> {
                if (!up) {
                    status.setText(R.string.engine_unreachable);
                    return;
                }
                Engine.save(this, chosen);
                restart();
            });
        }, "dsh-engine-probe").start();
    }

    private void open(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException none) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
        }
    }

    private void restart() {
        startActivity(new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_RELOAD)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(bold ? 20 : 10);
        view.setLayoutParams(params);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setOnClickListener(clicked -> action.run());
        return view;
    }
}
