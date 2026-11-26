package ai.deepseek.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Foreground service that owns the built-in Linux engine: it runs the install
 * and keeps `dsh web` alive while the app is in the background. State and the
 * recent log are process-wide so the settings screen and the main activity
 * can read them.
 */
public final class EngineService extends Service {
    private static final String TAG = "DshAndroid";
    static final String ACTION_INSTALL = "ai.deepseek.harness.engine.INSTALL";
    static final String ACTION_START = "ai.deepseek.harness.engine.START";
    static final String ACTION_STOP = "ai.deepseek.harness.engine.STOP";
    private static final String CHANNEL = "engine";
    private static final int NOTIFICATION_ID = 3;
    private static final int LOG_LINES = 400;
    private static final Pattern SIGN_IN = Pattern.compile("(http://127\\.0\\.0\\.1:\\d+/\\?token=\\S+)");

    /** Engine lifecycle as the UI shows it. */
    enum State { IDLE, INSTALLING, INSTALL_FAILED, STARTING, RUNNING, STOPPED }

    private static final Object LOCK = new Object();
    private static State state = State.IDLE;
    private static String step = "";
    private static int percent = -1;
    private static String signInUrl;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();

    private Thread worker;
    private Process engine;
    private PowerManager.WakeLock wakeLock;

    /** @return the current state */
    static State state() {
        synchronized (LOCK) {
            return state;
        }
    }

    /** @return the sign-in link of the running engine, or null */
    static String signInUrl() {
        synchronized (LOCK) {
            return signInUrl;
        }
    }

    /** @return a one-line progress description */
    static String step() {
        synchronized (LOCK) {
            return percent >= 0 ? step + " — " + percent + "%" : step;
        }
    }

    /** @return the recent log, oldest first */
    static List<String> log() {
        synchronized (LOCK) {
            return new ArrayList<>(LOG);
        }
    }

    /**
     * Send an action to the service.
     *
     * @param context any context of this app
     * @param action {@link #ACTION_INSTALL}, {@link #ACTION_START}, or {@link #ACTION_STOP}
     */
    static void send(Context context, String action) {
        Intent intent = new Intent(context, EngineService.class).setAction(action);
        try {
            if (ACTION_STOP.equals(action)) context.startService(intent);
            else context.startForegroundService(intent);
        } catch (RuntimeException refused) {
            append("Android refused to start the engine service: " + refused.getMessage());
        }
    }

    private static void set(State next, String nextStep, int nextPercent) {
        synchronized (LOCK) {
            state = next;
            step = nextStep;
            percent = nextPercent;
        }
    }

    private static void append(String line) {
        synchronized (LOCK) {
            LOG.addLast(line);
            while (LOG.size() > LOG_LINES) LOG.removeFirst();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEngine();
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground(ACTION_INSTALL.equals(action) ? getString(R.string.engine_installing) : getString(R.string.engine_starting_builtin));
        if (worker != null && worker.isAlive()) return START_NOT_STICKY;
        LinuxEngine linux = new LinuxEngine(this);
        if (ACTION_INSTALL.equals(action)) {
            worker = new Thread(() -> install(linux), "dsh-engine-install");
        } else {
            worker = new Thread(() -> run(linux), "dsh-engine-run");
        }
        worker.start();
        return START_NOT_STICKY;
    }

    private void install(LinuxEngine linux) {
        set(State.INSTALLING, getString(R.string.engine_installing), -1);
        acquireWakeLock();
        try {
            linux.install(new LinuxEngine.Output() {
                @Override
                public void line(String text) {
                    append(text);
                }

                @Override
                public void progress(String nextStep, int nextPercent) {
                    set(State.INSTALLING, nextStep, nextPercent);
                    if (nextPercent < 0) append("== " + nextStep);
                    updateNotification(nextPercent >= 0 ? nextStep + " — " + nextPercent + "%" : nextStep);
                }
            });
            append("== Installed");
            Engine.setBuiltin(this, true);
        } catch (IOException | InterruptedException failed) {
            append("Install failed: " + failed.getMessage());
            set(State.INSTALL_FAILED, getString(R.string.engine_install_failed), -1);
            releaseWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        releaseWakeLock();
        run(linux);
    }

    private void run(LinuxEngine linux) {
        if (!linux.installed()) {
            set(State.IDLE, getString(R.string.engine_not_installed), -1);
            stopSelf();
            return;
        }
        set(State.STARTING, getString(R.string.engine_starting_builtin), -1);
        synchronized (LOCK) {
            signInUrl = null;
        }
        try {
            engine = linux.start();
        } catch (IOException failed) {
            append("Engine start failed: " + failed.getMessage());
            set(State.STOPPED, getString(R.string.engine_start_failed), -1);
            stopSelf();
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(engine.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                append(line);
                Matcher match = SIGN_IN.matcher(line);
                if (match.find()) {
                    synchronized (LOCK) {
                        signInUrl = match.group(1);
                    }
                    set(State.RUNNING, getString(R.string.engine_running), -1);
                    updateNotification(getString(R.string.engine_running));
                }
            }
        } catch (IOException closed) {
            Log.i(TAG, "engine output closed: " + closed);
        }
        int exit;
        try {
            exit = engine.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exit = -1;
        }
        append("== Engine stopped (exit " + exit + ")");
        synchronized (LOCK) {
            signInUrl = null;
        }
        set(State.STOPPED, getString(R.string.engine_stopped), -1);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopEngine() {
        if (engine != null) engine.destroy();
    }

    @Override
    public void onDestroy() {
        stopEngine();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeepSeekHarness:engine-install");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(60 * 60 * 1000L);
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void goForeground(String text) {
        Notification notification = notification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        try {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
        } catch (SecurityException denied) {
            Log.i(TAG, "engine notification not updated: " + denied);
        }
    }

    private Notification notification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel_engine), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, SettingsActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_working)
                .setContentTitle(getString(R.string.channel_engine))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build();
    }
}
