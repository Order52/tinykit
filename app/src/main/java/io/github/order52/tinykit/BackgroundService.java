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
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one thing TinyKit may keep running, behind the "Run in the background" switch (Info tab). It is
 * off unless the user turns that on, and even then it only runs while a tool needs it:
 *
 * - New apps: Android only tells a running process about installs, so this listens for them and
 *   blocks a new app's internet at once (allow list mode), or puts Fake permissions back after an
 *   update (see {@link NewApp}).
 * - Shut Up: checks which app is in front a couple of times a second while the screen is on, and
 *   hides Developer options and the debugging switches while a chosen app is (see {@link ShutUp}).
 *   With the screen off it puts everything back and sleeps until the screen comes on: no timer, no
 *   wake-ups.
 *
 * Nothing else runs: no thread while there's no install to handle, no network, no wake locks. One
 * quiet notification says which of them are running. Whatever Shut Up hid is put back when it stops.
 */
public final class BackgroundService extends Service {
    /** The "Run in the background" switch. Off by default. (The key is older than the name.) */
    static final String PREF = "watch_new_apps";
    private static final String CHANNEL = "background";
    private static final int NOTIFICATION = 7;
    private static final long FAST_MS = 600, IDLE_MS = 3000, GRACE_MS = 15_000;

    private static volatile boolean running, shutUpRunning, installsRunning;

    // Its thread exists only while an install is being handled.
    private final ExecutorService worker = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());
    private SharedPreferences prefs;
    private BroadcastReceiver receiver, screenOn;
    private ScheduledExecutorService loop;
    private ShutUp.Watcher watcher;
    private long leftAt;
    private boolean stopTried;
    /** True while Shut Up waits for the screen to come on, doing nothing at all. */
    private final AtomicBoolean asleep = new AtomicBoolean();

    static boolean on(SharedPreferences p) {
        return p.getBoolean(PREF, false);
    }

    /** Whether installs need watching: an internet block is set up, or an app has saved permission choices. */
    static boolean watchesInstalls(SharedPreferences p) {
        return (FirewallPolicy.supported() && FirewallPolicy.configured(p)) || !FakePolicy.everyEntry(p).isEmpty();
    }

    /** Whether Shut Up has work: turned on, with apps chosen, and able to run without Shizuku. */
    static boolean watchesApps(Context c, SharedPreferences p) {
        return ShutUp.wanted(p) && ShutUp.ready(c);
    }

    /** The service runs only while the switch is on and at least one tool needs it. */
    static boolean needed(Context c, SharedPreferences p) {
        return on(p) && (watchesInstalls(p) || watchesApps(c, p));
    }

    static boolean isRunning() {
        return running;
    }

    static boolean shutUpRunning() {
        return running && shutUpRunning;
    }

    static boolean installsRunning() {
        return running && installsRunning;
    }

    /**
     * Turns "Run in the background" on or off. On, TinyKit is also asked not to be put to sleep or
     * closed to save battery, and gets the access Shut Up needs. Call off the main thread.
     */
    static void setEnabled(Context c, SharedPreferences p, boolean on) {
        p.edit().putBoolean(PREF, on).commit();
        if (on && Shell.ready()) {
            Shell.run("dumpsys deviceidle whitelist +" + c.getPackageName());
            Tweak.grantSelf(c);
        }
        sync(c);
    }

    /** Starts, updates or stops the service to match the saved choices. Safe to call from anywhere, any time. */
    static void sync(Context c) {
        Context app = c.getApplicationContext();
        Intent service = new Intent(app, BackgroundService.class);
        try {
            if (needed(app, app.getSharedPreferences("tinykit", Context.MODE_PRIVATE))) {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(service);
                else app.startService(service);
            } else if (running) {
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
        prefs = getSharedPreferences("tinykit", MODE_PRIVATE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean installs = on(prefs) && watchesInstalls(prefs);
        boolean apps = on(prefs) && watchesApps(this, prefs);
        // Android expects startForeground soon after startForegroundService, even when stopping at once.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, notification(installs, apps), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, notification(installs, apps));
        }
        if (!installs && !apps) {
            stopSelf();
            return START_NOT_STICKY;
        }
        watchInstalls(installs);
        watchApps(apps);
        return START_STICKY;
    }

    // ---- New apps ---------------------------------------------------------------------------------

    private void watchInstalls(boolean on) {
        installsRunning = on;
        if (on && receiver == null) {
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    if (!Intent.ACTION_PACKAGE_ADDED.equals(i.getAction())) return;
                    Context app = context.getApplicationContext();
                    if (!NewApp.relevant(prefs, i)) return;
                    worker.execute(() -> NewApp.handle(app, prefs, i));
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }
        } else if (!on && receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }
    }

    // ---- Shut Up ----------------------------------------------------------------------------------

    private synchronized void watchApps(boolean on) {
        shutUpRunning = on;
        if (on && loop == null) {
            watcher = new ShutUp.Watcher(this);
            loop = Executors.newSingleThreadScheduledExecutor();
            asleep.set(false);
            loop.schedule(this::tick, 0, TimeUnit.MILLISECONDS);
            // With the screen off Shut Up sleeps; this wakes it.
            screenOn = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent i) {
                    wake();
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenOn, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenOn, filter);
            }
        } else if (!on && loop != null) {
            stopLoop();
        }
    }

    private synchronized void wake() {
        ScheduledExecutorService l = loop;
        if (l == null || !asleep.compareAndSet(true, false)) return;
        try {
            l.schedule(this::tick, 0, TimeUnit.MILLISECONDS);
        } catch (RuntimeException stopped) {
            // Shut down meanwhile.
        }
    }

    /** Stops watching apps and puts back whatever was hidden (and Shizuku, if Shut Up stopped it). */
    private synchronized void stopLoop() {
        ScheduledExecutorService l = loop;
        loop = null;
        if (l != null) l.shutdownNow();
        if (screenOn != null) {
            unregisterReceiver(screenOn);
            screenOn = null;
        }
        Context app = getApplicationContext();
        SharedPreferences p = prefs;
        new Thread(() -> {
            ShutUp.restore(app, p);
            ShutUp.startShizuku(app, p);
        }).start();
    }

    private void tick() {
        long next = FAST_MS;
        try {
            boolean awake = watcher.interactive();
            Set<String> chosen = ShutUp.apps(prefs);
            String front = watcher.poll();
            boolean want = front != null && chosen.contains(front);
            if (want) {
                leftAt = 0;
                if (!ShutUp.hidden(prefs) || stillOn()) {
                    boolean ok = ShutUp.hide(this, prefs);
                    Log.i("TinyKit", "Shut Up: hid for " + front + " ok=" + ok);
                }
                if (!stopTried) {
                    stopTried = true; // once per visit, so a Shizuku that won't stop isn't hammered
                    ShutUp.stopShizuku(this, prefs);
                }
            } else {
                stopTried = false;
                if (ShutUp.hidden(prefs)) {
                    boolean ok = ShutUp.restore(this, prefs);
                    Log.i("TinyKit", "Shut Up: restored (front=" + front + ") ok=" + ok);
                }
                if (prefs.getBoolean(ShutUp.PREF_SHIZUKU_STOPPED, false)) {
                    // Not started the moment you leave, so going back and forth between apps doesn't
                    // restart it each time. Screen off counts as having left for good.
                    long now = System.currentTimeMillis();
                    if (!awake || (leftAt != 0 && now - leftAt >= GRACE_MS)) {
                        leftAt = 0;
                        ShutUp.startShizuku(this, prefs);
                    } else if (leftAt == 0) {
                        leftAt = now;
                    }
                } else {
                    leftAt = 0;
                }
            }
            if (!awake) {
                if (!ShutUp.hidden(prefs)) {
                    // Everything is put back: sleep until the screen comes on, with no timer at all.
                    asleep.set(true);
                    Log.i("TinyKit", "Shut Up: asleep until the screen is on");
                    if (watcher.interactive()) wake(); // it came on just now
                    return;
                }
                next = IDLE_MS; // putting the settings back failed; try again shortly
            }
        } catch (RuntimeException e) {
            Log.w("TinyKit", "Shut Up: " + e);
            next = IDLE_MS;
        }
        ScheduledExecutorService l = loop;
        if (l != null && !l.isShutdown()) {
            try {
                l.schedule(this::tick, next, TimeUnit.MILLISECONDS);
            } catch (RuntimeException stopped) {
                // Shut down between the check and the schedule.
            }
        }
    }

    /** True when something turned a hidden setting back on while the chosen app is still open. */
    private boolean stillOn() {
        for (String key : ShutUp.KEYS) {
            if ("1".equals(Tweak.readGlobal(this, key))) return true;
        }
        return false;
    }

    // ---- Notification -----------------------------------------------------------------------------

    private Notification notification(boolean installs, boolean apps) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, Tr.t("Run in the background"),
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            nm.createNotificationChannel(channel);
            // The channels of the two services this one replaced.
            nm.deleteNotificationChannel("watch");
            nm.deleteNotificationChannel("shutup");
        }
        List<String> jobs = new ArrayList<>();
        if (apps) jobs.add(Tr.t("Shut Up"));
        if (installs) jobs.add(Tr.t("New apps"));
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_TAB, 4);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_MIN);
        return b.setSmallIcon(R.drawable.ic_tile_shizuku)
                .setContentTitle(Tr.t("TinyKit is running in the background"))
                .setContentText(jobs.isEmpty() ? Tr.t("Stopping…") : String.join(" · ", jobs))
                .setContentIntent(tap).setOngoing(true).setShowWhen(false).build();
    }

    @Override
    public void onDestroy() {
        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }
        installsRunning = false;
        if (loop != null) stopLoop();
        shutUpRunning = false;
        worker.shutdown();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
