package io.github.order52.tinykit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After a restart or a TinyKit update, starts {@link BackgroundService} again if it is on and a tool
 * needs it. Also the alarms that freeze the chosen work apps, and lock the work profile, once the screen has
 * been off long enough.
 */
public final class StartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean freeze = Work.ACTION_FREEZE.equals(action);
        if (freeze || Work.ACTION_LOCK.equals(action)) {
            PendingResult result = goAsync();
            Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    if (freeze) Work.autoFreeze(app);
                    else Work.autoLock(app);
                } catch (RuntimeException e) {
                    android.util.Log.w("TinyKit", "work alarm: " + e);
                } finally {
                    result.finish();
                }
            }, "work-alarm").start();
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        BackgroundService.sync(context);
        // After a restart Shizuku is gone: start it again by itself, if it can be done with nobody there.
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            android.content.SharedPreferences prefs = context.getSharedPreferences("tinykit", Context.MODE_PRIVATE);
            if (prefs.getBoolean(ShizukuControl.PREF_AUTO, false) && !Shell.running() && ShizukuControl.canRestart(context, prefs)) {
                if (!ShizukuControl.intents(context, prefs)) {
                    try {
                        AdbService.begin(context, true);
                    } catch (RuntimeException e) {
                        android.util.Log.w("TinyKit", "auto start: " + e);
                    }
                } else {
                    ShizukuControl.start(context, prefs);
                }
            }
        }
    }
}
