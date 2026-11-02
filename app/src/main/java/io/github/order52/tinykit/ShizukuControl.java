package io.github.order52.tinykit;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Stops and starts Shizuku with no screen open, for Shut Up. It only ever stops Shizuku when it also
 * has a way to start it again by itself: the start and stop intents of Shizuku forks (with the auth
 * token saved in TinyKit), or Termux with shizuku-termux. The standard Shizuku app has neither, so
 * Shut Up never stops it, because nothing could bring it back.
 */
final class ShizukuControl {
    private static final String SHIZUKU = "moe.shizuku.privileged.api";
    private static final String TERMUX = "com.termux";
    private static final String TERMUX_BIN = "/data/data/com.termux/files/usr/bin/";
    private static final String RUN_COMMAND = "com.termux.permission.RUN_COMMAND";

    private ShizukuControl() {
    }

    private static boolean intents(Context c, SharedPreferences p) {
        return !p.getString("auth", "").isEmpty() && !c.getPackageManager().queryBroadcastReceivers(
                new Intent(SHIZUKU + ".START").setPackage(SHIZUKU), 0).isEmpty();
    }

    private static boolean termux(Context c) {
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

    /** Stops Shizuku. Call only when {@link #canRestart} is true. */
    static void stop(Context c, SharedPreferences p) {
        if (intents(c, p)) {
            c.sendBroadcast(new Intent(SHIZUKU + ".STOP").setPackage(SHIZUKU).putExtra("auth", p.getString("auth", "")));
        } else {
            Shell.run("pkill -f '[s]hizuku_server' || killall shizuku_server");
        }
    }

    /** Starts Shizuku. True when a start was requested. */
    static boolean start(Context c, SharedPreferences p) {
        try {
            if (intents(c, p)) {
                c.sendBroadcast(new Intent(SHIZUKU + ".START").setPackage(SHIZUKU).putExtra("auth", p.getString("auth", "")));
                return true;
            }
            if (!termux(c)) return false;
            Intent run = new Intent("com.termux.RUN_COMMAND")
                    .setClassName(TERMUX, "com.termux.app.RunCommandService")
                    .putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BIN + "bash")
                    .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c",
                            "[ -x " + TERMUX_BIN + "shizuku ] || exit 127; exec " + TERMUX_BIN + "shizuku start </dev/null"})
                    .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(run); else c.startService(run);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
