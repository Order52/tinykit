package io.github.order52.tinykit;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * The one way TinyKit starts and stops Shizuku: the Shizuku tab's buttons, the quick panel tile and
 * Shut Up all go through here.
 *
 * Stop, best way first:
 * 1. Shizuku's own stop intent, with the auth token saved in TinyKit, on the Shizuku forks that have one.
 * 2. Through Shizuku itself: Shizuku's app is force-stopped first, so a Watchdog can't start it straight
 *    back up, then its server is asked to end, and made to if it hasn't within 3 seconds.
 * 3. Termux with shizuku-termux.
 *
 * Start: the start intent with the token, then Termux. The standard Shizuku app has neither, so
 * Shut Up never stops Shizuku unless {@link #canRestart} says it can bring it back.
 */
final class ShizukuControl {
    static final String SHIZUKU = "moe.shizuku.privileged.api";
    private static final String TERMUX = "com.termux";
    private static final String TERMUX_BIN = "/data/data/com.termux/files/usr/bin/";
    private static final String RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    /**
     * Ends Shizuku's server. pidof matches the process name only, so unlike pkill -f it can never
     * match the shell running this very command. The shell keeps going after the server is gone.
     */
    private static final String KILL = "am force-stop " + SHIZUKU + "; p=$(pidof shizuku_server) || exit 0; kill $p; "
            + "i=0; while [ $i -lt 30 ] && kill -0 $p 2>/dev/null; do sleep 0.1; i=$((i+1)); done; "
            + "kill -0 $p 2>/dev/null && kill -9 $p; exit 0";

    private ShizukuControl() {
    }

    /** Whether this Shizuku has start and stop intents, and the token they need is saved. */
    static boolean intents(Context c, SharedPreferences p) {
        return !p.getString("auth", "").isEmpty() && !c.getPackageManager().queryBroadcastReceivers(
                new Intent(SHIZUKU + ".START").setPackage(SHIZUKU), 0).isEmpty();
    }

    static boolean termux(Context c) {
        try {
            c.getPackageManager().getPackageInfo(TERMUX, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return Build.VERSION.SDK_INT < 23
                || c.checkSelfPermission(RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
    }

    /** Whether TinyKit can start Shizuku again by itself once it has been stopped. */
    static boolean canRestart(Context c, SharedPreferences p) {
        return intents(c, p) || termux(c);
    }

    /** Whether {@link #stop} has a way to stop Shizuku right now. */
    static boolean canStop(Context c, SharedPreferences p) {
        return intents(c, p) || Shell.ready() || termux(c);
    }

    /**
     * Stops Shizuku. Returns null once a stop is under way, or what to do so TinyKit can stop it.
     * Can block for a few seconds; call off the main thread.
     */
    static String stop(Context c, SharedPreferences p, PendingIntent termuxResult) {
        android.util.Log.i("TinyKit", "Shizuku: stop, " + (intents(c, p) ? "intent" : Shell.ready() ? "kill" : "termux"));
        try {
            if (intents(c, p)) {
                c.sendBroadcast(new Intent(SHIZUKU + ".STOP").setPackage(SHIZUKU).putExtra("auth", p.getString("auth", "")));
            } else if (Shell.ready()) {
                Shell.run(KILL);
            } else if (termux(c)) {
                termux(c, "stop", termuxResult);
            } else {
                return "Allow TinyKit in Shizuku first, or set the auth token.";
            }
            return null;
        } catch (RuntimeException e) {
            return "Couldn't change Shizuku.";
        }
    }

    /** Starts Shizuku. True when a start was requested. */
    static boolean start(Context c, SharedPreferences p, PendingIntent termuxResult) {
        try {
            if (intents(c, p)) {
                c.sendBroadcast(new Intent(SHIZUKU + ".START").setPackage(SHIZUKU).putExtra("auth", p.getString("auth", "")));
                return true;
            }
            if (!termux(c)) return false;
            termux(c, "start", termuxResult);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Runs "shizuku start" or "shizuku stop" in Termux, without opening it. */
    private static void termux(Context c, String command, PendingIntent result) {
        Intent run = new Intent("com.termux.RUN_COMMAND")
                .setClassName(TERMUX, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BIN + "bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c",
                        "[ -x " + TERMUX_BIN + "shizuku ] || exit 127; exec " + TERMUX_BIN + "shizuku " + command + " </dev/null"})
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        if (result != null) run.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(run); else c.startService(run);
    }
}
