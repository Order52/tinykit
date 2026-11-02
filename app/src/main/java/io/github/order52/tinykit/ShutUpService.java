package io.github.order52.tinykit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs Shut Up (see {@link ShutUp}): checks which app is in front a couple of times a second and
 * hides Developer options and the debugging switches while one of the chosen apps is. It is OFF
 * unless the user turns Shut Up on and has chosen at least one app, and it puts everything back when
 * it stops.
 */
public final class ShutUpService extends Service {
    private static final String CHANNEL = "shutup";
    private static final int NOTIFICATION = 8;
    private static final long FAST_MS = 600, IDLE_MS = 3000;

    private static volatile boolean running;

    private ScheduledExecutorService loop;
    private SharedPreferences prefs;
    private ShutUp.Watcher watcher;

    static boolean isRunning() {
        return running;
    }

    /** Starts or stops the service to match the saved choice. Safe to call from anywhere, any time. */
    static void sync(Context c) {
        Context app = c.getApplicationContext();
        Intent service = new Intent(app, ShutUpService.class);
        try {
            if (ShutUp.needed(app.getSharedPreferences("tinykit", Context.MODE_PRIVATE)) && ShutUp.ready(app)) {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(service);
                else app.startService(service);
            } else {
                app.stopService(service);
            }
        } catch (RuntimeException e) {
            // Android refuses to start a foreground service from the background; the next start
            // from the screen, a restart or an update tries again.
        }
    }

    /**
     * Turns Shut Up on or off. Returns null when it worked, else what is missing. On, TinyKit is
     * also asked not to be put to sleep. Call off the main thread.
     */
    static String setEnabled(Context c, SharedPreferences p, boolean on) {
        if (on) {
            Tweak.grantSelf(c);
            if (!ShutUp.ready(c)) {
                return "Start Shizuku once and allow TinyKit, so it can change these settings and see "
                        + "which app is open without Shizuku.";
            }
            if (Shell.ready()) Shell.run("dumpsys deviceidle whitelist +" + c.getPackageName());
        }
        p.edit().putBoolean(ShutUp.PREF_ON, on).apply();
        if (!on) ShutUp.restore(c, p);
        sync(c);
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        prefs = getSharedPreferences("tinykit", MODE_PRIVATE);
        if (!ShutUp.needed(prefs) || !ShutUp.ready(this)) {
            ShutUp.restore(this, prefs);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, notification());
        }
        if (loop == null) {
            watcher = new ShutUp.Watcher(this);
            loop = Executors.newSingleThreadScheduledExecutor();
            loop.schedule(this::tick, 0, TimeUnit.MILLISECONDS);
        }
        return START_STICKY;
    }

    private void tick() {
        long next = FAST_MS;
        try {
            Set<String> chosen = ShutUp.apps(prefs);
            String front = watcher.poll();
            boolean want = front != null && chosen.contains(front);
            if (want && (!ShutUp.hidden(prefs) || stillOn())) {
                boolean ok = ShutUp.hide(this, prefs);
                Log.i("TinyKit", "Shut Up: hid for " + front + " ok=" + ok + " saved=" + prefs.getString(ShutUp.PREF_SAVED, ""));
            } else if (!want && ShutUp.hidden(prefs)) {
                boolean ok = ShutUp.restore(this, prefs);
                Log.i("TinyKit", "Shut Up: restored (front=" + front + ") ok=" + ok);
            }
            if (!watcher.interactive() && !ShutUp.hidden(prefs)) next = IDLE_MS;
        } catch (RuntimeException e) {
            Log.w("TinyKit", "Shut Up: " + e);
            next = IDLE_MS;
        }
        if (loop != null && !loop.isShutdown()) loop.schedule(this::tick, next, TimeUnit.MILLISECONDS);
    }

    /** True when something turned a hidden setting back on while the chosen app is still open. */
    private boolean stillOn() {
        for (String key : ShutUp.KEYS) {
            if ("1".equals(Tweak.readGlobal(this, key))) return true;
        }
        return false;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, Tr.t("Shut Up"),
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        return b.setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(Tr.t("Shut Up is on"))
                .setContentText(Tr.t("Hiding developer options and debugging from the apps you chose."))
                .setContentIntent(tap).setOngoing(true).build();
    }

    @Override
    public void onDestroy() {
        ScheduledExecutorService l = loop;
        loop = null;
        if (l != null) l.shutdownNow();
        // Whatever was hidden is put back, so stopping Shut Up never leaves the phone changed.
        if (prefs != null) {
            SharedPreferences p = prefs;
            new Thread(() -> ShutUp.restore(getApplicationContext(), p)).start();
        }
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
