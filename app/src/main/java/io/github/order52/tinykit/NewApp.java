package io.github.order52.tinykit;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * What to do when Android announces an app was installed or updated. {@link NewAppService} calls
 * {@link #handle} for every install, off the main thread:
 *
 * - A new app: keeps the internet firewall true. In allow list mode it starts blocked, and a
 *   reinstalled app that was blocked gets its block back (it can come back with a new user id).
 * - An updated app that has Fake permissions: Android turns those back into Real when it
 *   re-syncs permissions after an update, so they are put back at once, and checked again a few
 *   seconds later in case Android syncs again.
 *
 * If Shizuku is off at that moment it leaves a note for {@link Restore}, which finishes the job
 * as soon as Shizuku is running. Updates never touch the firewall: the app keeps its id and rule.
 *
 * Why a service and not a manifest receiver: Android does not deliver PACKAGE_ADDED to receivers
 * declared in the manifest of an app that targets Android 8 or newer, so only a receiver
 * registered by a running process ever hears about an install.
 */
final class NewApp {
    private NewApp() {
    }

    /** True when an install has something to do for this intent. */
    static boolean relevant(SharedPreferences p, Intent intent) {
        return firewall(p, intent) || fake(p, intent);
    }

    private static boolean firewall(SharedPreferences p, Intent intent) {
        boolean update = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        return !update && FirewallPolicy.supported()
                && (FirewallPolicy.allowMode(p) || !FirewallPolicy.saved(p, FirewallPolicy.BLOCKED).isEmpty());
    }

    private static boolean fake(SharedPreferences p, Intent intent) {
        boolean update = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
        String pkg = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;
        return update && pkg != null && FakePolicy.has(p, pkg);
    }

    /** Blocks the new app (or puts a Fake permission back). Blocks for up to a few seconds; call off the main thread. */
    static void handle(Context app, SharedPreferences p, Intent intent) {
        boolean fake = fake(p, intent);
        boolean firewall = firewall(p, intent);
        if (!fake && !firewall) return;
        // Shizuku's binder may still be on its way if this process was just started.
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
    }
}
