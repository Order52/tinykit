package io.github.order52.tinykit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Starts Shizuku with TinyKit's own adb client ({@link Adb}), with nothing else installed: finds the
 * phone's Wireless debugging, turns it on when TinyKit may, pairs with it once, connects and runs
 * Shizuku's start command. It shows what it is doing in a notification and asks for the one thing it
 * can't do itself, the pairing code, right there, so the person can stay in Settings.
 *
 * Runs only while a start is under way, and ends by itself.
 */
@android.annotation.SuppressLint("NewApi") // only started on Android 11 and newer
public final class AdbService extends Service {
    static final String ACTION_CODE = "io.github.order52.tinykit.ADB_CODE";
    private static final String CHANNEL = "shizuku_start";
    private static final int NOTIFICATION = 7;
    private static final String CONNECT = "_adb-tls-connect._tcp.", PAIRING = "_adb-tls-pairing._tcp.";
    private static final String PREF_PAIRED = "adb_paired";
    private static final String NEEDS_WIRELESS = "wireless", NEEDS_PAIRING = "pairing";
    /** Runs Shizuku's own starter the way its start script does, as the adb user. */
    private static final String START = "d=$(pm path moe.shizuku.privileged.api | head -1 | sed 's/package://;s#/base.apk##'); "
            + "$d/lib/$(ls $d/lib | head -1)/libshizuku.so";

    private final LinkedBlockingQueue<String> codes = new LinkedBlockingQueue<>();
    private volatile boolean running;
    private SharedPreferences prefs;

    /** Wireless debugging exists from Android 11. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    /** Whether TinyKit has paired already, so a start needs nobody. */
    static boolean paired(Context c) {
        return supported() && c.getSharedPreferences("tinykit", MODE_PRIVATE).getBoolean(PREF_PAIRED, false);
    }

    /** Starts the start. Safe to call again while one is running. */
    static void begin(Context c) {
        Intent start = new Intent(c, AdbService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(start); else c.startService(start);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        prefs = getSharedPreferences("tinykit", MODE_PRIVATE);
        Notification first = notification("Starting Shizuku…", "", false, null);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, first);
        }
        if (intent != null && ACTION_CODE.equals(intent.getAction())) {
            Bundle reply = RemoteInput.getResultsFromIntent(intent);
            CharSequence code = reply == null ? null : reply.getCharSequence("code");
            if (code != null) codes.offer(code.toString().replaceAll("\\D", ""));
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            new Thread(this::flow, "adb-start").start();
        }
        return START_NOT_STICKY;
    }

    private void flow() {
        String result;
        try {
            Adb adb = new Adb(getFilesDir());
            result = connectAndStart(adb, 4000);
            if (NEEDS_WIRELESS.equals(result) && enableWireless()) result = connectAndStart(adb, 12_000);
            long end = System.currentTimeMillis() + 5 * 60_000L;
            while ((NEEDS_WIRELESS.equals(result) || NEEDS_PAIRING.equals(result)) && System.currentTimeMillis() < end) {
                if (NEEDS_WIRELESS.equals(result)) {
                    boolean developer = Settings.Global.getInt(getContentResolver(),
                            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
                    say(developer ? "Turn on Wireless debugging" : "Turn on Developer options",
                            developer ? "Tap here, then switch on Wireless debugging. It needs Wi-Fi. TinyKit carries on by itself."
                                    : "Tap here, then tap Build number 7 times. TinyKit carries on by itself.",
                            false, developer ? Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS : Settings.ACTION_DEVICE_INFO_SETTINGS);
                    result = connectAndStart(adb, 3000);
                } else {
                    say("Enter the pairing code", "In Wireless debugging tap Pair device with pairing code, then type "
                            + "the six digits here.", true, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                    String code = codes.poll(2, TimeUnit.SECONDS);
                    if (code == null) continue;
                    List<Integer> ports = discover(PAIRING, 8000);
                    if (ports.isEmpty()) {
                        say("Enter the pairing code", "Open Pair device with pairing code first, then type the "
                                + "digits again.", true, null);
                        continue;
                    }
                    Adb.Failure failed = null;
                    boolean paired = false;
                    // A pairing service the network remembers from an earlier time just refuses: try the next one.
                    for (int port : ports) {
                        try {
                            adb.pair("127.0.0.1", port, code);
                            paired = true;
                            break;
                        } catch (Adb.Failure f) {
                            failed = f;
                        }
                    }
                    if (paired) {
                        prefs.edit().putBoolean(PREF_PAIRED, true).apply();
                        say("Paired", "Starting Shizuku…", false, null);
                        result = connectAndStart(adb, 12_000);
                    } else {
                        say("Enter the pairing code", (failed == null ? "" : failed.getMessage() + " ")
                                + "Type the digits again.", true, null);
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("TinyKit", "adb start: " + e);
            result = "Couldn't start Shizuku: " + e.getMessage();
        }
        finish(result == null ? null : NEEDS_WIRELESS.equals(result) || NEEDS_PAIRING.equals(result)
                ? "Wireless debugging wasn't set up in time. Tap Start to try again." : result);
    }

    /** Null when Shizuku is on its way, else what is missing (see the two constants) or why it failed. */
    private String connectAndStart(Adb adb, long wait) {
        // Off means off, even if the network still remembers the service.
        if (Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 0) return NEEDS_WIRELESS;
        long end = System.currentTimeMillis() + wait;
        do {
            // The port changes each time Wireless debugging is switched on, and the network can still list the old
            // one: the ones that refuse are skipped, and the new one turns up in a moment.
            for (int port : discover(CONNECT, 2500)) {
                android.util.Log.i("TinyKit", "adb: connect port " + port);
                if (!prefs.getBoolean(PREF_PAIRED, false)) return NEEDS_PAIRING;
                try {
                    String out = adb.shell("127.0.0.1", port, START);
                    if (out.contains("exit with 0")) return null;
                    String[] lines = out.split("\n");
                    return "Shizuku didn't start: " + (lines.length == 0 ? "" : lines[lines.length - 1]);
                } catch (Adb.Failure f) {
                    android.util.Log.w("TinyKit", "adb: " + f.getMessage() + (f.unpaired ? " (unpaired)" : ""));
                    if (f.unpaired) {
                        // The phone closed the door: it has forgotten TinyKit's key (revoked, or a new phone).
                        prefs.edit().putBoolean(PREF_PAIRED, false).apply();
                        return NEEDS_PAIRING;
                    }
                } catch (Exception e) {
                    return "Couldn't start Shizuku: " + e.getMessage();
                }
            }
        } while (System.currentTimeMillis() < end);
        return NEEDS_WIRELESS;
    }

    /** Turns Wireless debugging on, when Android has let TinyKit write that setting (Shizuku grants it once). */
    private boolean enableWireless() {
        if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return false;
        try {
            Settings.Global.putInt(getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
            return Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The ports of the Wireless debugging services this phone can see, from the network's service discovery. */
    private List<Integer> discover(String type, long waitMs) {
        NsdManager nsd = (NsdManager) getSystemService(NSD_SERVICE);
        Set<Integer> ports = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
        CountDownLatch found = new CountDownLatch(1);
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo info) {
                resolve(nsd, info, ports, found, 0);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                found.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
            }

            @Override
            public void onServiceLost(NsdServiceInfo info) {
            }
        };
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            if (found.await(waitMs, TimeUnit.MILLISECONDS)) Thread.sleep(700); // others may be a moment behind
        } catch (RuntimeException | InterruptedException e) {
            // no discovery: treated as not found
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (RuntimeException ignored) {
            }
        }
        return new ArrayList<>(ports);
    }

    @SuppressWarnings("deprecation")
    private static void resolve(NsdManager nsd, NsdServiceInfo info, Set<Integer> ports, CountDownLatch found, int tries) {
        nsd.resolveService(info, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo i, int errorCode) {
                // Only one lookup runs at a time on older Android: try again shortly.
                if (tries < 5) new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(() -> resolve(nsd, info, ports, found, tries + 1), 400);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo i) {
                ports.add(i.getPort());
                found.countDown();
            }
        });
    }

    // ---- Notification ----------------------------------------------------------------------------------

    /** Shows a step: with a field for the pairing code when {@code input}, opening {@code settings} when tapped. */
    private void say(String title, String text, boolean input, String settings) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(title, text, input, settings));
    }

    private Notification notification(String title, String text, boolean input, String settings) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, Tr.t("Starting Shizuku"),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        Intent open = settings != null ? new Intent(settings) : new Intent(this, MainActivity.class);
        PendingIntent tap = PendingIntent.getActivity(this, 0, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile_shizuku).setContentTitle(Tr.t(title)).setContentText(Tr.t(text))
                .setStyle(new Notification.BigTextStyle().bigText(Tr.t(text)))
                .setContentIntent(tap).setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true);
        if (input) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent reply = PendingIntent.getService(this, 1,
                    new Intent(this, AdbService.class).setAction(ACTION_CODE), flags);
            b.addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile_shizuku),
                    Tr.t("Pairing code"), reply)
                    .addRemoteInput(new RemoteInput.Builder("code").setLabel(Tr.t("Pairing code")).build()).build());
        }
        return b.build();
    }

    /** Ends with a short message that stays, or quietly when Shizuku is on its way. */
    private void finish(String error) {
        stopForeground(STOP_FOREGROUND_REMOVE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(NOTIFICATION);
        if (error != null) {
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            nm.notify(NOTIFICATION + 1, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_tile_shizuku)
                    .setContentTitle(Tr.t("Shizuku didn't start")).setContentText(Tr.t(error))
                    .setStyle(new Notification.BigTextStyle().bigText(Tr.t(error)))
                    .setContentIntent(PendingIntent.getActivity(this, 0, open,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                    .setAutoCancel(true).build());
        }
        running = false;
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
