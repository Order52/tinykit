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

import java.net.Socket;
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
    private static final String PREF_PAIRED = "adb_paired", PREF_PORT = "adb_port";
    /** Set once Termux lets TinyKit run commands: then Start goes through it. */
    static final String PREF_TERMUX = "termux_ready";
    private static final String TERMUX_SKIP = "skip";
    private static final String NEEDS_WIRELESS = "wireless", NEEDS_PAIRING = "pairing", NEEDS_NOTHING = "nothing";
    private static final String NOTHING_AVAILABLE = "This Android is older than 11, so it has no Wireless debugging, and "
            + "this phone has neither root nor adb over the network switched on. On Android 10 and older one of those "
            + "has to be on: root, or adb tcpip 5555 from a computer once (it lasts until the phone restarts).";
    /**
     * Runs Shizuku's own starter as the adb or root user, the way Shizuku's start script does, after lifting the
     * limit Android 12+ puts on background processes (which kills a Shizuku started this way within minutes).
     * It ignores the hang-up sent when the session it was started in closes, which would end the server at once.
     */
    static final String START = "trap '' HUP; if [ \"$(getprop ro.build.version.sdk)\" -ge 31 ]; then "
            + "(device_config set_sync_disabled_for_tests persistent; "
            + "device_config put activity_manager max_phantom_processes 2147483647; "
            + "settings put global settings_enable_monitor_phantom_procs false) >/dev/null 2>&1; fi; "
            + "d=$(pm path moe.shizuku.privileged.api | head -1 | sed 's/package://;s#/base.apk##'); "
            + "l=$(ls $d/lib/*/libshizuku.so 2>/dev/null | head -1); "
            + "if [ -n \"$l\" ]; then $l; else sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh; fi";

    private final LinkedBlockingQueue<String> codes = new LinkedBlockingQueue<>();
    private volatile boolean running, silent;
    private SharedPreferences prefs;

    /** Wireless debugging exists from Android 11. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    /** Whether a start has worked before (paired with Wireless debugging, or root, or adb over the network), so it needs nobody. */
    static boolean paired(Context c) {
        return c.getSharedPreferences("tinykit", MODE_PRIVATE).getBoolean(PREF_PAIRED, false);
    }

    /** Starts the start. Safe to call again while one is running. */
    static void begin(Context c) {
        begin(c, false);
    }

    /**
     * {@code auto}: started by TinyKit itself (after a restart, or when it opens), not by a tap. It then never asks the
     * person for anything and keeps quiet if it can't: no pairing code, no "turn on" notifications, no failure notice.
     */
    static void begin(Context c, boolean auto) {
        Intent start = new Intent(c, AdbService.class).putExtra("auto", auto);
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
            silent = intent != null && intent.getBooleanExtra("auto", false);
            new Thread(this::flow, "adb-start").start();
        }
        return START_NOT_STICKY;
    }

    private void flow() {
        String result;
        try {
            if (silent && !paired(this) && !prefs.getBoolean(PREF_TERMUX, false)) { // nothing known to work: stay out of the way
                finish(null);
                return;
            }
            if (Termux.installed(this) && Termux.permitted(this) && prefs.getBoolean(PREF_TERMUX, false)) {
                String viaTermux = termuxFlow();
                if (!TERMUX_SKIP.equals(viaTermux)) {
                    finish(viaTermux);
                    return;
                }
            }
            Adb adb = new Adb(getFilesDir());
            // As shizuku-termux does, first what needs no pairing: root, then adb over the network if it is on.
            String quick = quickStart(adb);
            android.util.Log.i("TinyKit", "adb: quick start gave " + quick);
            if (quick == null || !NEEDS_NOTHING.equals(quick) || !supported()) {
                finish(quick == null ? null : NEEDS_NOTHING.equals(quick) ? NOTHING_AVAILABLE : quick);
                return;
            }
            result = connectAndStart(adb, 4000);
            if (NEEDS_WIRELESS.equals(result) && enableWireless()) result = connectAndStart(adb, 12_000);
            long end = System.currentTimeMillis() + (silent ? 90_000L : 5 * 60_000L); // Wi-Fi can be late after a restart
            while ((NEEDS_WIRELESS.equals(result) || NEEDS_PAIRING.equals(result)) && System.currentTimeMillis() < end) {
                if (silent && NEEDS_PAIRING.equals(result)) break; // never asks for a code on its own
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
        android.util.Log.i("TinyKit", "adb: flow ended with " + result);
        finish(result == null ? null : NEEDS_WIRELESS.equals(result) || NEEDS_PAIRING.equals(result)
                ? "Wireless debugging wasn't set up in time. Tap Start to try again." : result);
    }

    /**
     * Starts Shizuku through Termux, in the background. Null when it is on its way, TERMUX_SKIP when Termux can't do it
     * (then TinyKit's own adb client takes over), else why not. The same steps as the other way: root, adb over the
     * network, then Wireless debugging, asking for the pairing code in a notification the first time.
     */
    private String termuxFlow() throws InterruptedException {
        say("Starting Shizuku…", "Through Termux, in the background.", false, null);
        Termux.Result r = Termux.start(this, START, "");
        android.util.Log.i("TinyKit", "termux start: exit=" + r.exitCode + " error=" + r.error + " out=" + r.out);
        if (!r.ran()) return TERMUX_SKIP;
        long end = System.currentTimeMillis() + 5 * 60_000L;
        while (r.exitCode == Termux.NOT_CONNECTED && System.currentTimeMillis() < end) {
            if (!supported()) return NOTHING_AVAILABLE;
            if (Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 0) {
                if (enableWireless()) {
                    Thread.sleep(5000); // the network needs a moment to announce it
                    r = Termux.start(this, START, join(discover(CONNECT, 4000)));
                } else {
                    boolean developer = Settings.Global.getInt(getContentResolver(),
                            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
                    say(developer ? "Turn on Wireless debugging" : "Turn on Developer options",
                            developer ? "Tap here, then switch on Wireless debugging. It needs Wi-Fi. TinyKit carries on by itself."
                                    : "Tap here, then tap Build number 7 times. TinyKit carries on by itself.",
                            false, developer ? Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS : Settings.ACTION_DEVICE_INFO_SETTINGS);
                    Thread.sleep(3000);
                    if (Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 1) {
                        Thread.sleep(4000);
                        r = Termux.start(this, START, join(discover(CONNECT, 4000)));
                    }
                }
                continue;
            }
            List<Integer> connect = discover(CONNECT, 3000);
            if (!connect.isEmpty() && !tried.containsAll(connect)) { // the ports TinyKit sees, that Termux's own lookup missed
                tried.addAll(connect);
                r = Termux.start(this, START, join(connect));
                continue;
            }
            if (silent) return TERMUX_SKIP;
            say("Enter the pairing code", "In Wireless debugging tap Pair device with pairing code, then type "
                    + "the six digits here.", true, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            String code = codes.poll(2, TimeUnit.SECONDS);
            if (code == null) continue;
            List<Integer> ports = discover(PAIRING, 8000);
            if (ports.isEmpty()) {
                say("Enter the pairing code", "Open Pair device with pairing code first, then type the digits again.",
                        true, null);
                continue;
            }
            boolean paired = false;
            for (int port : ports) {
                if (Termux.pair(this, START, port, code).exitCode == 0) {
                    paired = true;
                    break;
                }
            }
            if (paired) {
                say("Paired", "Starting Shizuku…", false, null);
                Thread.sleep(3000);
                tried.clear();
                r = Termux.start(this, START, join(discover(CONNECT, 6000)));
            } else {
                say("Enter the pairing code", "That didn't pair. Type the digits again.", true, null);
            }
        }
        if (r.exitCode == 0) return null;
        if (r.exitCode == Termux.NOT_CONNECTED) return "Wireless debugging wasn't set up in time. Tap Start to try again.";
        return TERMUX_SKIP;
    }

    private final Set<Integer> tried = new java.util.HashSet<>();

    private static String join(List<Integer> ports) {
        StringBuilder out = new StringBuilder();
        for (int p : ports) out.append(out.length() > 0 ? " " : "").append(p);
        return out.toString();
    }

    /** Null when Shizuku is on its way, NEEDS_NOTHING when neither root nor adb over the network is there, else why not. */
    private String quickStart(Adb adb) {
        String rooted = root();
        if (rooted != null && rooted.contains("exit with 0")) {
            prefs.edit().putBoolean(PREF_PAIRED, true).apply();
            return null;
        }
        try {
            say("Starting Shizuku…", "If the phone asks to allow USB debugging, choose Allow (Always allow).", false, null);
            String out = adb.shell("127.0.0.1", 5555, START, true);
            if (out.contains("exit with 0")) {
                prefs.edit().putBoolean(PREF_PAIRED, true).apply();
                return null;
            }
            String[] lines = out.split("\n");
            return "Shizuku didn't start: " + (lines.length == 0 ? "" : lines[lines.length - 1]);
        } catch (Adb.Failure f) {
            return f.closed ? NEEDS_NOTHING : f.getMessage();
        } catch (Exception e) {
            return "Couldn't start Shizuku: " + e.getMessage();
        }
    }

    /** Runs the start command through su, if the phone has root and allows TinyKit. Null when there is no su. */
    private String root() {
        try {
            Process p = new ProcessBuilder("su", "-c", START).redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (java.io.InputStream in = p.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                } catch (java.io.IOException ignored) {
                }
            });
            reader.start();
            reader.join(20_000);
            p.destroy();
            return out.toString();
        } catch (java.io.IOException | InterruptedException e) {
            return null;
        }
    }

    /** Null when Shizuku is on its way, else what is missing (see the two constants) or why it failed. */
    private String connectAndStart(Adb adb, long wait) {
        // Off means off, even if the network still remembers the service.
        if (Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) == 0) return NEEDS_WIRELESS;
        long end = System.currentTimeMillis() + wait;
        int last = prefs.getInt(PREF_PORT, 0);
        if (last > 0 && prefs.getBoolean(PREF_PAIRED, false)) { // the port from the last time: usually still the one
            try {
                String out = adb.shell("127.0.0.1", last, START);
                if (out.contains("exit with 0")) return null;
            } catch (Exception e) {
                // gone: look for the new one
            }
        }
        do {
            // The port changes each time Wireless debugging is switched on, and the network can still list the old
            // one: the ones that refuse are skipped, and the new one turns up in a moment.
            for (int port : discover(CONNECT, 2500)) {
                android.util.Log.i("TinyKit", "adb: connect port " + port);
                if (!prefs.getBoolean(PREF_PAIRED, false)) return NEEDS_PAIRING;
                try {
                    String out = adb.shell("127.0.0.1", port, START);
                    if (out.contains("exit with 0")) {
                        prefs.edit().putInt(PREF_PORT, port).apply();
                        return null;
                    }
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
        if (ports.isEmpty() && waitMs >= 2500) ports.addAll(scan());
        return new ArrayList<>(ports);
    }

    /**
     * Wireless debugging's ports are random, in 30000 to 49999. When the network's service discovery shows nothing
     * (some phones block it), the ports that answer on this phone are found by asking each one.
     */
    private static Set<Integer> scan() {
        Set<Integer> open = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(48);
        for (int p = 30000; p < 50000; p++) {
            final int port = p;
            pool.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 250);
                    open.add(port);
                } catch (java.io.IOException ignored) {
                }
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return open;
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
        if (silent) return;
        getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(title, text, input, settings));
    }

    private Notification notification(String title, String text, boolean input, String settings) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, Tr.t("Starting Shizuku"),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
        Intent open = settings != null ? new Intent(settings) : new Intent(this, MainActivity.class);
        if (Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS.equals(settings)) { // lands on the Wireless debugging switch
            Bundle args = new Bundle();
            args.putString(":settings:fragment_args_key", "toggle_adb_wireless");
            open.putExtra(":settings:fragment_args_key", "toggle_adb_wireless").putExtra(":settings:show_fragment_args", args);
        }
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
    /** Told when a start ends, so the Shizuku tab stops saying "Starting…" if Shizuku didn't come up. */
    static volatile java.util.function.Consumer<String> onFinish;

    private void finish(String error) {
        java.util.function.Consumer<String> listener = onFinish;
        if (listener != null) listener.accept(error == null ? "Shizuku didn't start. Tap Start again, or open the Shizuku app." : error);
        stopForeground(STOP_FOREGROUND_REMOVE);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.cancel(NOTIFICATION);
        if (error != null && !silent) {
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
