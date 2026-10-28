package io.github.order52.tinykit;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Puts back the few things Android forgets when the phone restarts, so nothing is lost: the internet
 * firewall rules and Force dark, and puts Fake permissions back (Android resets those to Real at
 * a restart). Everything else TinyKit changes (hidden and removed apps, settings) Android keeps.
 *
 * Runs once per boot, the moment Shizuku is running again: Shizuku starts TinyKit's process when
 * it comes up, {@link TinyKitApp} sees that, and this does its work in a second or two and ends.
 * Nothing stays resident and nothing polls.
 */
final class Restore {
    static final String PREF_FORCE_DARK = "force_dark";
    private static final String PREF_BOOT = "restored_boot";
    private static final String PREF_FAKE_CHECKED = "fake_checked";
    private static final String PREF_FAKE_BOOT = "fake_boot";

    private Restore() {
    }

    static void start(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> run(app), "restore").start();
    }

    private static synchronized void run(Context c) {
        if (!Shell.ready()) return;
        SharedPreferences p = c.getSharedPreferences("tinykit", Context.MODE_PRIVATE);
        // Android turns Fake permissions back into Real at a restart or an app update. Check them
        // every time Shizuku comes up (not more than every ten minutes: tiles start this process).
        long now = System.currentTimeMillis();
        int boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot != p.getInt(PREF_FAKE_BOOT, -2) || Math.abs(now - p.getLong(PREF_FAKE_CHECKED, 0)) > 10 * 60 * 1000) {
            FakePolicy.enforce(c, p);
            p.edit().putLong(PREF_FAKE_CHECKED, now).putInt(PREF_FAKE_BOOT, boot).apply();
        }
        // Already done since the last restart (tiles and the widget start this process often).
        if (boot != -1 && boot == p.getInt(PREF_BOOT, -2)) return;
        boolean ok = true;
        if (FirewallPolicy.supported()) ok = FirewallPolicy.apply(c, p, false) == null;
        if (p.getBoolean(PREF_FORCE_DARK, false)) {
            ok &= Shell.run("setprop debug.hwui.force_dark true").ok();
        }
        if (ok) p.edit().putInt(PREF_BOOT, boot).apply();
    }
}
