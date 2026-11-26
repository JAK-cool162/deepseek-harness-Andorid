package ai.deepseek.harness;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Typeface;
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
 * Chooses where the harness runs: on-device, or a Linux engine the user starts
 * in the Android 16 Linux Terminal (or Termux) and connects by pasting the
 * sign-in link `dsh web` prints.
 */
public final class EngineActivity extends Activity {
    /** Commands for a fresh Debian arm64 Linux Terminal. */
    static final String SETUP_COMMANDS =
            "sudo apt-get update && sudo apt-get install -y curl build-essential python3\n"
            + "curl -fsSL https://nodejs.org/dist/v24.22.3/node-v24.22.3-linux-arm64.tar.gz | sudo tar -xz -C /usr/local --strip-components=1\n"
            + "sudo npm install -g --allow-scripts=@deepseek-ai/dsh-subprocess-local,koffi,node-pty,protobufjs @deepseek-ai/dsh@alpha\n"
            + "mkdir -p ~/work && cd ~/work && dsh web --port " + Engine.DEFAULT_PORT + " --no-open";

    private TextView status;
    private EditText link;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(pad, pad, pad, pad);

        column.addView(text(getString(R.string.engine_title), 22, true));
        column.addView(text(getString(R.string.engine_intro), 15, false));
        column.addView(text(getString(R.string.engine_step_terminal), 15, false));
        TextView commands = text(SETUP_COMMANDS, 13, false);
        commands.setTypeface(Typeface.MONOSPACE);
        commands.setTextIsSelectable(true);
        commands.setBackgroundColor(0x22FFFFFF);
        commands.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
        column.addView(commands);
        column.addView(button(getString(R.string.engine_copy), () -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("dsh setup", SETUP_COMMANDS));
            Toast.makeText(this, R.string.engine_copied, Toast.LENGTH_SHORT).show();
        }));
        column.addView(text(getString(R.string.engine_step_link), 15, false));

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
        status = text(saved == null ? getString(R.string.engine_status_device) : getString(R.string.engine_status_linux), 14, false);
        column.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(column);
        setContentView(scroll);
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

    private void restart() {
        startActivity(new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_RELOAD)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
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
