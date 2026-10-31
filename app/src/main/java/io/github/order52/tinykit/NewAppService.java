package io.github.order52.tinykit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hears about installs so a new app can be blocked the moment it appears (see {@link NewApp}).
 * Android only tells a running process about installs, so this small foreground service keeps
 * TinyKit alive with a quiet notification. It is OFF unless the user turns on "Block new apps at
 * once" in the internet block settings. It does nothing else and idles at no CPU: the block
 * itself is enforced by Android.
 */
public final class NewAppService extends Service {
    /** The user's choice: keep TinyKit running so new apps are blocked at once. Off by default. */
    static final String PREF = "watch_new_apps";
    private static final String CHANNEL = "watch";
    private static final int NOTIFICATION = 7;

    private static volatile boolean running;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private BroadcastReceiver receiver;

    /** The service runs only when the user turned it on. */
    static boolean needed(SharedPreferences p) {
        return FirewallPolicy.supported() && p.getBoolean(PREF, false);
    }

    /** Whether the service is alive right now (it dies with the process, so this can't go stale). */
    static boolean isRunning() {
        return running;
    }

    /**
     * Turns the watcher on or off. On, it also asks Android not to put TinyKit to sleep or kill it
     * to save battery (the same battery exemption "Keep alive" uses). Call off the main thread.
     */
    static void setEnabled(Context c, SharedPreferences p, boolean on) {
        p.edit().putBoolean(PREF, on).apply();
        if (on && Shell.ready()) {
            Shell.run("dumpsys deviceidle whitelist +" + c.getPackageName());
        }
        sync(c);
    }

    /** Starts or stops the service to match the saved choice. Safe to call from anywhere, any time. */
    static void sync(Context c) {
        Context app = c.getApplicationContext();
        if (!FirewallPolicy.supported()) return;
        Intent service = new Intent(app, NewAppService.class);
        try {
            if (needed(app.getSharedPreferences("tinykit", Context.MODE_PRIVATE))) {
                app.startForegroundService(service);
            } else {
                app.stopService(service);
            }
        } catch (RuntimeException e) {
            // Android refuses to start a foreground service from the background; the next start
            // from the screen, a restart or an update tries again.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = getSharedPreferences("tinykit", MODE_PRIVATE);
        if (!needed(p)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        if (receiver == null) {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (!Intent.ACTION_PACKAGE_ADDED.equals(i.getAction())) return;
                    Context app = context.getApplicationContext();
                    SharedPreferences prefs = app.getSharedPreferences("tinykit", MODE_PRIVATE);
                    if (!NewApp.relevant(prefs, i)) return;
                    worker.execute(() -> NewApp.handle(app, prefs, i));
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        return START_STICKY;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "New app blocking",
                NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Blocking the internet of new apps")
                .setContentText("You can turn this off in TinyKit: Block internet per app.")
                .setContentIntent(tap).setOngoing(true).build();
    }

    @Override
    public void onDestroy() {
        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }
        worker.shutdown();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
