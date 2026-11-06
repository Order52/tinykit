package io.github.order52.tinykit;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The one way TinyKit starts and stops Shizuku: the Shizuku tab's buttons, the quick panel tile and
 * Shut Up all go through here.
 *
 * Start, best way first:
 * 1. Shizuku's own start intent with the auth token saved in TinyKit, on the Shizuku forks that have one.
 * 2. TinyKit's own adb client ({@link AdbService}): over Wireless debugging on Android 11 and newer (it pairs
 *    once and after that needs nobody), or on older Android through root or adb over the network.
 *
 * Stop: the stop intent with the token, else through Shizuku itself: Shizuku's app is force-stopped first,
 * so a Watchdog can't start it straight back up, then its server is asked to end, and made to if it
 * hasn't within 3 seconds.
 */
final class ShizukuControl {
    static final String SHIZUKU = "moe.shizuku.privileged.api";

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

    /** Whether TinyKit can start Shizuku again by itself, with nobody there, once it has been stopped. */
    static boolean canRestart(Context c, SharedPreferences p) {
        return intents(c, p) || AdbService.paired(c);
    }

    /** Whether {@link #start} can try: always (it explains what is missing if nothing works). */
    static boolean canStart(Context c, SharedPreferences p) {
        return true;
    }

    /** Whether {@link #stop} has a way to stop Shizuku right now. */
    static boolean canStop(Context c, SharedPreferences p) {
        return intents(c, p) || Shell.ready();
    }

    /**
     * Stops Shizuku. Returns null once a stop is under way, or what to do so TinyKit can stop it.
     * Can block for a few seconds; call off the main thread.
     */
    static String stop(Context c, SharedPreferences p) {
        android.util.Log.i("TinyKit", "Shizuku: stop, " + (intents(c, p) ? "intent" : "kill"));
        try {
            if (intents(c, p)) {
                c.sendBroadcast(new Intent(SHIZUKU + ".STOP").setPackage(SHIZUKU).putExtra("auth", p.getString("auth", "")));
            } else if (Shell.ready()) {
                Shell.run(KILL);
            } else {
                return "Allow TinyKit in Shizuku first, or set the auth token.";
            }
            return null;
        } catch (RuntimeException e) {
            return "Couldn't change Shizuku.";
        }
    }

    /** Starts Shizuku. True when a start was requested; false when only the Shizuku app can. */
    static boolean start(Context c, SharedPreferences p) {
        try {
            if (intents(c, p)) {
                c.sendBroadcast(new Intent(SHIZUKU + ".START").setPackage(SHIZUKU).putExtra("auth", p.getString("auth", "")));
                return true;
            }
            AdbService.begin(c);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
