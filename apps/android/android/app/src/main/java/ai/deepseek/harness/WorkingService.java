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

/**
 * Foreground service that runs while the agent works: it shows an ongoing
 * notification with elapsed time, which Android 16 may promote to a live update
 * in the status bar, and holds a partial wake lock so the turn continues with
 * the screen off. When work ends while the app is not visible, it posts a
 * "finished" notification.
 */
public final class WorkingService extends Service {
    private static final String TAG = "DshAndroid";
    private static final String CHANNEL_WORKING = "working";
    private static final String CHANNEL_FINISHED = "finished";
    private static final int ID_WORKING = 1;
    private static final int ID_FINISHED = 2;
    /** Upper bound on one wake lock hold; a longer turn re-acquires it on the next start. */
    private static final long WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L;
    /** Android 16 extra asking the system to promote an ongoing notification to a live update. */
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";

    private PowerManager.WakeLock wakeLock;

    /**
     * Start showing the working notification.
     *
     * @param context any context of this app
     */
    static void start(Context context) {
        try {
            context.startForegroundService(new Intent(context, WorkingService.class));
        } catch (RuntimeException refused) {
            // Android refuses foreground services started from the background; the turn still runs.
            Log.w(TAG, "working notification refused: " + refused);
        }
    }

    /**
     * Stop the working notification.
     *
     * @param context any context of this app
     * @param announce whether to post a "finished" notification, for when the app is not visible
     */
    static void stop(Context context, boolean announce) {
        context.stopService(new Intent(context, WorkingService.class));
        if (announce) postFinished(context);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = workingNotification(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID_WORKING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_WORKING, notification);
        }
        if (wakeLock == null) {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DeepSeekHarness:working");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static PendingIntent openApp(Context context) {
        Intent open = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void ensureChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_WORKING,
                context.getString(R.string.channel_working), NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_FINISHED,
                context.getString(R.string.channel_finished), NotificationManager.IMPORTANCE_DEFAULT));
    }

    private static Notification workingNotification(Context context) {
        ensureChannels(context);
        Notification.Builder builder = new Notification.Builder(context, CHANNEL_WORKING)
                .setSmallIcon(R.drawable.ic_stat_working)
                .setContentTitle(context.getString(R.string.working_title))
                .setContentText(context.getString(R.string.working_text))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setUsesChronometer(true)
                .setProgress(0, 0, true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(openApp(context));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        builder.getExtras().putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
        return builder.build();
    }

    private static void postFinished(Context context) {
        ensureChannels(context);
        Notification notification = new Notification.Builder(context, CHANNEL_FINISHED)
                .setSmallIcon(R.drawable.ic_stat_working)
                .setContentTitle(context.getString(R.string.finished_title))
                .setContentText(context.getString(R.string.finished_text))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build();
        try {
            context.getSystemService(NotificationManager.class).notify(ID_FINISHED, notification);
        } catch (SecurityException denied) {
            // Notification permission is denied; the result waits in the app.
            Log.i(TAG, "finished notification not posted: " + denied);
        }
    }
}
