package io.github.order52.tinykit;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts Shizuku through Termux, with everything Termux needs set up by TinyKit: the permission to run
 * commands there, and the one Termux setting that lets other apps do so (one command, copied for the person to
 * paste in Termux once, which also installs adb and brings TinyKit back), and a start script. The script runs
 * in the background, no Termux window opens.
 */
final class Termux {
    static final String PACKAGE = "com.termux";
    static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String BIN = "/data/data/com.termux/files/usr/bin/";

    /** Exit codes of {@link #SCRIPT}. */
    static final int STARTED = 0, NOT_CONNECTED = 3, PAIR_FAILED = 4, NO_ADB = 5;

    /**
     * Start Shizuku, the way shizuku-termux does: root? adb over the network (port 5555 or 50000)? Wireless debugging (a port the
     * network lists, or the ones TinyKit found)? Arguments: the start command, then "start" and the extra ports, or
     * "pair" with a port and the pairing code. Whether it worked is read from what the start command says (or
     * from ps, run the same way the command was): Termux can't see other users' processes, so its own ps would
     * always say Shizuku isn't running.
     */
    private static final String SCRIPT = "export PATH=" + BIN + ":$PATH; START=\"$1\"; MODE=\"$2\"; F=\"${TMPDIR:-$HOME}/tinykit.out\"; "
            + "need_adb() { adb version >/dev/null 2>&1 && return 0; pkg install -y android-tools >/dev/null 2>&1; "
            + "adb version >/dev/null 2>&1; }; "
            + "if [ \"$MODE\" = pair ]; then need_adb || exit 5; adb start-server >/dev/null 2>&1; "
            + "adb pair \"localhost:$3\" \"$4\" 2>&1 | grep -qi 'success' && exit 0; exit 4; fi; "
            + "if command -v su >/dev/null 2>&1 && [ \"$(su -c id -u 2>/dev/null)\" = 0 ]; then "
            + "su -c \"$START\" >\"$F\" 2>&1; grep -q 'exit with 0' \"$F\" && exit 0; sleep 1; "
            + "su -c \"ps -A\" 2>/dev/null | grep -q '[s]hizuku_server' && exit 0; fi; "
            + "need_adb || exit 5; adb start-server >/dev/null 2>&1; "
            + "try() { adb connect \"$1\" 2>&1 | grep -qiE 'connected to|already connected' || return 1; "
            + "adb -s \"$1\" shell \"$START\" >\"$F\" 2>&1; grep -q 'exit with 0' \"$F\" && return 0; sleep 1; "
            + "adb -s \"$1\" shell \"ps -A\" 2>/dev/null | grep -q '[s]hizuku_server'; }; "
            + "try localhost:5555 && exit 0; try localhost:50000 && exit 0; "
            + "for p in $(adb mdns services 2>/dev/null | grep _adb-tls-connect | grep -oE '[0-9]+$'); do "
            + "try \"localhost:$p\" && exit 0; done; "
            + "for p in $3; do try \"localhost:$p\" && exit 0; done; exit 3";

    /** What a run in Termux gave back. */
    static final class Result {
        int exitCode = -1;
        String out = "", error = "";
        boolean allowMissing;

        /** Termux ran it, whatever it answered. */
        boolean ran() {
            return exitCode >= 0 && error.isEmpty();
        }
    }

    private static final ConcurrentHashMap<Integer, Result> RESULTS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, CountDownLatch> WAITING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger(100);

    private Termux() {
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean permitted(Context c) {
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Runs {@code script} (with its arguments) in Termux, in the background, and waits for the answer. Off the main thread. */
    static Result run(Context c, String script, long timeoutMs, String... args) {
        int id = NEXT.incrementAndGet();
        CountDownLatch done = new CountDownLatch(1);
        WAITING.put(id, done);
        String[] arguments = new String[args.length + 3];
        arguments[0] = "-c";
        arguments[1] = script;
        arguments[2] = "tinykit";
        System.arraycopy(args, 0, arguments, 3, args.length);
        int flags = PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent reply = PendingIntent.getBroadcast(c, id, new Intent(c, Reply.class).putExtra("id", id), flags);
        Intent run = new Intent("com.termux.RUN_COMMAND").setClassName(PACKAGE, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", BIN + "bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", reply);
        Result result = new Result();
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(run); else c.startService(run);
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                result.error = "Termux didn't answer in time.";
                return result;
            }
        } catch (RuntimeException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            result.error = "Couldn't reach Termux: " + e.getMessage();
            return result;
        } finally {
            WAITING.remove(id);
        }
        Result answer = RESULTS.remove(id);
        return answer == null ? result : answer;
    }

    /** The start script, once: false is not answered or not allowed. */
    static Result start(Context c, String startCommand, String ports) {
        return run(c, SCRIPT, 240_000, startCommand, "start", ports);
    }

    static Result pair(Context c, String startCommand, int port, String code) {
        return run(c, SCRIPT, 120_000, startCommand, "pair", String.valueOf(port), code);
    }

    /** Whether Termux lets TinyKit run commands now. */
    static Result probe(Context c) {
        return run(c, "true", 15_000);
    }

    /** Termux's answer to a run. */
    public static final class Reply extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int id = intent.getIntExtra("id", -1);
            Bundle bundle = intent.getBundleExtra("result");
            Result r = new Result();
            if (bundle != null) {
                r.exitCode = bundle.getInt("exitCode", -1);
                r.out = bundle.getString("stdout", "");
                String errmsg = bundle.getString("errmsg", "");
                if (bundle.getInt("err", 0) > 0 && r.exitCode == -1) {
                    r.error = errmsg.isEmpty() ? "Termux refused." : errmsg;
                    r.allowMissing = errmsg.contains("allow-external-apps");
                }
            } else {
                r.error = "Termux returned no result.";
            }
            RESULTS.put(id, r);
            CountDownLatch done = WAITING.get(id);
            if (done != null) done.countDown();
        }
    }

    // ---- The one setting Termux needs ------------------------------------------------------------

    /**
     * The one command to paste in Termux, which does everything there: switches on allow-external-apps (keeping the
     * rest of the settings), makes Termux read them, installs adb if it isn't working, and brings TinyKit back
     * to the front so it carries on by itself.
     */
    static String setupCommand() {
        return "mkdir -p ~/.termux && { grep -v '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null; "
                + "echo 'allow-external-apps = true'; } > ~/.termux/.tk && mv ~/.termux/.tk ~/.termux/termux.properties "
                + "&& termux-reload-settings; (adb version >/dev/null 2>&1 || pkg install -y android-tools); "
                + "am start -n io.github.order52.tinykit/.MainActivity --ez termux_done true >/dev/null 2>&1; echo; "
                + "echo 'TinyKit is set up. You can go back to it.'";
    }

    /** Copies {@link #setupCommand} and opens Termux: pasting it and pressing Enter is all that is left. False if Termux can't be opened. */
    static boolean setUp(Context c) {
        ((android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE))
                .setPrimaryClip(android.content.ClipData.newPlainText("TinyKit setup for Termux", setupCommand()));
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (launch == null) return false;
        c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return true;
    }
}
