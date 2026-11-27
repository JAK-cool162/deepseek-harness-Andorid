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
    private TextView builtinStatus;
    private TextView builtinLog;
    private TextView githubStatus;
    private TextView importStatus;
    private TextView storageStatus;
    private static final int STORAGE_REQUEST = 3;
    private static final int PICK_FOLDER_REQUEST = 2;
    private EditText link;
    private final android.os.Handler refresh = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshBuiltin = new Runnable() {
        @Override
        public void run() {
            showBuiltin();
            refresh.postDelayed(this, 1000);
        }
    };
    private Button defaultStyle;
    private Button glassStyle;

    /** Indexes into {@link #colors}: the harness's own settings palette (ui-theme design-platform.css). */
    private static final int BG = 0, SURFACE = 1, FG = 2, SUB = 3, LINE = 4;
    private int[] colors;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean dark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        colors = dark ? new int[] {0xFF151517, 0xFF2C2C2E, 0xFFF9FAFB, 0xFFADB2B8, 0xFF353638}
                : new int[] {0xFFFFFFFF, 0xFFF5F6F7, 0xFF0F1115, 0xFF61666B, 0xFFE9ECF2};
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
        column.addView(text(getString(R.string.style_accent), 15, true));
        column.addView(choiceRow(Style.ACCENTS, Style.accent(this), "accent_", choice -> {
            Style.setAccent(this, choice);
            restart();
        }));
        column.addView(text(getString(R.string.style_corners), 15, true));
        column.addView(choiceRow(Style.CORNERS, Style.corners(this), "corners_", choice -> {
            Style.setCorners(this, choice);
            restart();
        }));

        column.addView(text(getString(R.string.builtin_heading), 18, true));
        column.addView(text(getString(R.string.builtin_intro), 14, false));
        builtinStatus = text("", 14, true);
        column.addView(builtinStatus);
        column.addView(button(getString(R.string.builtin_install), () -> EngineService.send(this, EngineService.ACTION_INSTALL)));
        column.addView(button(getString(R.string.builtin_use), () -> {
            if (!new LinuxEngine(this).installed()) {
                builtinStatus.setText(R.string.engine_not_installed);
                return;
            }
            Engine.setBuiltin(this, true);
            restart();
        }));
        column.addView(button(getString(R.string.builtin_stop), () -> EngineService.send(this, EngineService.ACTION_STOP)));
        column.addView(button(getString(R.string.builtin_remove), this::removeBuiltin));
        column.addView(text(getString(R.string.storage_heading), 15, true));
        storageStatus = text(getString(storageGranted() ? R.string.storage_allowed : R.string.storage_intro), 14, false);
        column.addView(storageStatus);
        column.addView(button(getString(R.string.storage_allow), () -> requestPermissions(new String[] {
            android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE }, STORAGE_REQUEST)));
        column.addView(text(getString(R.string.import_heading), 15, true));
        importStatus = text(getString(R.string.import_intro), 14, false);
        column.addView(importStatus);
        column.addView(button(getString(R.string.import_folder), this::pickFolder));
        column.addView(text(getString(R.string.github_heading), 15, true));
        githubStatus = text(getString(R.string.github_checking), 14, false);
        column.addView(githubStatus);
        column.addView(button(getString(R.string.github_sign_in), this::signInToGitHub));
        builtinLog = text("", 11, false);
        builtinLog.setTypeface(Typeface.MONOSPACE);
        builtinLog.setTextIsSelectable(true);
        column.addView(builtinLog);

        column.addView(text(getString(R.string.engine_heading), 18, true));
        column.addView(text(getString(R.string.engine_intro), 14, false));
        column.addView(text(getString(R.string.engine_step_install), 14, false));
        column.addView(button(getString(R.string.engine_get_termux), () -> open(Uri.parse(TERMUX_DOWNLOAD))));
        column.addView(text(getString(R.string.engine_step_commands), 14, false));
        TextView commands = text(SETUP_COMMANDS, 12, false);
        commands.setTypeface(Typeface.MONOSPACE);
        commands.setTextIsSelectable(true);
        commands.setBackground(rounded(colors[SURFACE], 0));
        commands.setPadding(dp(12), dp(10), dp(12), dp(10));
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
            Engine.setBuiltin(this, false);
            Engine.save(this, null);
            restart();
        }));
        status = text(saved == null ? getString(R.string.engine_status_device) : getString(R.string.engine_status_termux), 14, false);
        column.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(colors[BG]);
        scroll.addView(column);
        setContentView(scroll);
        getWindow().setStatusBarColor(colors[BG]);
        getWindow().setNavigationBarColor(colors[BG]);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh.post(refreshBuiltin);
        LinuxEngine linux = new LinuxEngine(this);
        if (!linux.installed()) {
            githubStatus.setText(R.string.github_needs_engine);
            return;
        }
        GitHubLogin.status(linux, githubListener());
    }

    private boolean storageGranted() {
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void pickFolder() {
        if (!new LinuxEngine(this).installed()) {
            importStatus.setText(R.string.github_needs_engine);
            return;
        }
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER_REQUEST);
        } catch (ActivityNotFoundException none) {
            importStatus.setText(R.string.no_app_for_link);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        importStatus.setText(R.string.import_running);
        FolderImport.start(this, data.getData(), new FolderImport.Listener() {
            @Override
            public void progress(int files, String current) {
                runOnUiThread(() -> importStatus.setText(getString(R.string.import_progress, files, current)));
            }

            @Override
            public void done(java.io.File target, int files, String error) {
                String where = target == null ? "" : "~/work/imports/" + target.getName();
                runOnUiThread(() -> importStatus.setText(error == null
                        ? getString(R.string.import_done, files, where)
                        : getString(R.string.import_failed, error)));
            }
        });
    }

    private void signInToGitHub() {
        LinuxEngine linux = new LinuxEngine(this);
        if (!linux.installed()) {
            githubStatus.setText(R.string.github_needs_engine);
            return;
        }
        githubStatus.setText(R.string.github_starting);
        GitHubLogin.start(linux, githubListener());
    }

    private GitHubLogin.Listener githubListener() {
        return new GitHubLogin.Listener() {
            @Override
            public void code(String code) {
                runOnUiThread(() -> {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("GitHub code", code));
                    githubStatus.setText(getString(R.string.github_enter_code, code));
                    open(Uri.parse(GitHubLogin.DEVICE_PAGE));
                });
            }

            @Override
            public void done(String user, String error) {
                runOnUiThread(() -> {
                    if (user != null) githubStatus.setText(getString(R.string.github_signed_in, user));
                    else if (error != null && !error.isEmpty()) githubStatus.setText(getString(R.string.github_failed, error));
                    else githubStatus.setText(R.string.github_signed_out);
                });
            }
        };
    }

    @Override
    protected void onPause() {
        refresh.removeCallbacks(refreshBuiltin);
        super.onPause();
    }

    private void showBuiltin() {
        boolean installed = new LinuxEngine(this).installed();
        EngineService.State state = EngineService.state();
        String line;
        switch (state) {
            case INSTALLING:
            case STARTING:
                line = EngineService.step();
                break;
            case RUNNING:
                line = getString(R.string.engine_running);
                break;
            case INSTALL_FAILED:
                line = getString(R.string.engine_install_failed);
                break;
            default:
                line = getString(installed ? R.string.builtin_installed : R.string.engine_not_installed);
        }
        if (Engine.builtinSelected(this)) line += "\n" + getString(R.string.builtin_selected);
        builtinStatus.setText(line);
        java.util.List<String> log = EngineService.log();
        builtinLog.setText(String.join("\n", log.subList(Math.max(0, log.size() - 30), log.size())));
    }

    private void removeBuiltin() {
        EngineService.send(this, EngineService.ACTION_STOP);
        Engine.setBuiltin(this, false);
        builtinStatus.setText(R.string.builtin_removing);
        new Thread(() -> {
            String result;
            try {
                new LinuxEngine(this).remove();
                result = getString(R.string.builtin_removed);
            } catch (java.io.IOException failed) {
                result = failed.getMessage();
            }
            String shown = result;
            runOnUiThread(() -> builtinStatus.setText(shown));
        }, "dsh-engine-remove").start();
    }

    /** A wrapping row of buttons, one per choice, with the current one checked. */
    private android.view.View choiceRow(String[][] choices, String current, String labelPrefix, java.util.function.Consumer<String> choose) {
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] choice : choices) {
            int label = getResources().getIdentifier(labelPrefix + choice[0], "string", getPackageName());
            Button button = button(getString(label) + (choice[0].equals(current) ? "  ✓" : ""), () -> choose.accept(choice[0]));
            button.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((LinearLayout.LayoutParams) button.getLayoutParams()).rightMargin = dp(8);
            ((LinearLayout.LayoutParams) button.getLayoutParams()).topMargin = dp(8);
            if (choice[0].equals(current)) button.setBackground(rounded(colors[SURFACE], colors[FG]));
            if (!choice[1].isEmpty() && choice[1].startsWith("#")) button.setTextColor(android.graphics.Color.parseColor(choice[1]));
            row.addView(button);
        }
        scroll.addView(row);
        return scroll;
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
        if (requestCode == STORAGE_REQUEST) {
            storageStatus.setText(storageGranted() ? R.string.storage_allowed : R.string.storage_denied);
            return;
        }
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
                Engine.setBuiltin(this, false);
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
        view.setTextSize(bold ? Math.min(sizeSp, 20) : Math.min(sizeSp, 13));
        view.setTextColor(colors[bold ? FG : SUB]);
        view.setLineSpacing(0, 1.15f);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(bold ? (sizeSp >= 18 ? 28 : 18) : 6);
        view.setLayoutParams(params);
        return view;
    }

    /** A flat rounded row button in the harness settings style. */
    private Button button(String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextSize(15);
        view.setTextColor(colors[FG]);
        view.setGravity(android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.START);
        view.setStateListAnimator(null);
        view.setMinHeight(dp(48));
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(colors[LINE]), rounded(colors[SURFACE], 0), null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        params.rightMargin = dp(8);
        view.setLayoutParams(params);
        view.setOnClickListener(clicked -> action.run());
        return view;
    }

    private android.graphics.drawable.GradientDrawable rounded(int fill, int stroke) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(12));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }
}
