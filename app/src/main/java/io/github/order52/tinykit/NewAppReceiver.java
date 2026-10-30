package io.github.order52.tinykit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * Runs when Android announces an app was installed or updated, does one small job and ends:
 *
 * - A new app: keeps the internet firewall true. In allow list mode it starts blocked, and a
 *   reinstalled app that was blocked gets its block back (it can come back with a new user id).
 * - An updated app that has Fake permissions: Android turns those back into Real when it
 *   re-syncs permissions after an update, so they are put back at once, and checked again a few
 *   seconds later in case Android syncs again.
 *
 * If Shizuku is off at that moment it leaves a note for {@link Restore}, which finishes the job
 * as soon as Shizuku is running. Updates never touch the firewall: the app keeps its id and rule.
 */
public final class NewAppReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences("tinykit", Context.MODE_PRIVATE);
        boolean update = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
        boolean fake = update && pkg != null && FakePolicy.has(p, pkg);
        boolean firewall = !update && FirewallPolicy.supported()
                && (FirewallPolicy.allowMode(p) || !FirewallPolicy.saved(p, FirewallPolicy.BLOCKED).isEmpty());
        if (!fake && !firewall) return;
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                // The process was just started for this broadcast, so Shizuku's binder may still be on its way.
                for (int i = 0; i < 50 && !Shell.ready(); i++) SystemClock.sleep(100);
                boolean ready = Shell.ready();
                if (firewall && (!ready || FirewallPolicy.apply(app, p, false) != null)) {
                    p.edit().putBoolean(Restore.PREF_FIREWALL_PENDING, true).apply();
                }
                if (fake) {
                    if (!ready) {
                        p.edit().putBoolean(Restore.PREF_FAKE_PENDING, true).apply();
                    } else {
                        FakePolicy.enforce(app, p);
                        // Android can sync an app's permissions again a moment after the update.
                        SystemClock.sleep(4000);
                        FakePolicy.enforce(app, p);
                    }
                }
            } finally {
                result.finish();
            }
        }, "new-app").start();
    }
}
