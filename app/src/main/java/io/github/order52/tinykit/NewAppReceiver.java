package io.github.order52.tinykit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Keeps the internet firewall true for apps installed later: in allow list mode a new app starts
 * blocked, and a reinstalled app that was blocked gets its block back (it can come back with a new
 * user id). Runs only when Android announces an install, applies the saved choice and ends. If
 * Shizuku is off at that moment it leaves a note for {@link Restore}, which finishes the job as
 * soon as Shizuku is running. Updates are ignored: the app keeps its id and its rule.
 */
public final class NewAppReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                || intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                || !FirewallPolicy.supported()) {
            return;
        }
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences("tinykit", Context.MODE_PRIVATE);
        if (!FirewallPolicy.allowMode(p) && FirewallPolicy.saved(p, FirewallPolicy.BLOCKED).isEmpty()) return;
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                // The process was just started for this broadcast, so Shizuku's binder may still be on its way.
                for (int i = 0; i < 50 && !Shell.ready(); i++) SystemClock.sleep(100);
                if (!Shell.ready() || FirewallPolicy.apply(app, p, false) != null) {
                    p.edit().putBoolean(Restore.PREF_FIREWALL_PENDING, true).apply();
                }
            } finally {
                result.finish();
            }
        }, "new-app").start();
    }
}
