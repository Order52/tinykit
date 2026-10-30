package io.github.order52.tinykit;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Puts back the few things Android forgets when the phone restarts, so nothing is lost: the internet
 * firewall rules, Force dark and Disable HW overlays, and puts Fake permissions back (Android resets those to Real at
 * a restart). Everything else TinyKit changes (hidden and removed apps, settings) Android keeps.
 *
 * Runs once per boot, the moment Shizuku is running again: Shizuku starts TinyKit's process when
 * it comes up, {@link TinyKitApp} sees that, and this does its work in a second or two and ends.
 * Nothing stays resident and nothing polls.
 */
final class Restore {
    static final String PREF_FORCE_DARK = "force_dark";
    static final String PREF_NO_OVERLAYS = "no_overlays";
    // Set when an app was installed while Shizuku was off, so the firewall still has to catch up.
    static final String PREF_FIREWALL_PENDING = "fw_pending";
    private static final String PREF_BOOT = "restored_boot";
    // Set when an app was updated while Shizuku was off: its Fake permissions need another look.
    static final String PREF_FAKE_PENDING = "fake_pending";
    private static final String PREF_FAKE_CHECKED = "fake_checked";
    private static final String PREF_FAKE_BOOT = "fake_boot";

    private Restore() {
    }

    /** SurfaceFlinger's "disable overlays" call, the one behind the developer option. */
    static String overlaysCommand(boolean off) {
        return "service call SurfaceFlinger 1008 i32 " + (off ? 1 : 0);
    }

    static void start(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> run(app), "restore").start();
    }

    private static synchronized void run(Context c) {
        if (!Shell.ready()) return;
        SharedPreferences p = c.getSharedPreferences("tinykit", Context.MODE_PRIVATE);
        // Android turns Fake permissions back into Real at a restart or an app update. Check them
        // every time Shizuku comes up (not more than once a minute: tiles start this process).
        long now = System.currentTimeMillis();
        int boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot != p.getInt(PREF_FAKE_BOOT, -2) || p.getBoolean(PREF_FAKE_PENDING, false)
                || Math.abs(now - p.getLong(PREF_FAKE_CHECKED, 0)) > 60 * 1000) {
            FakePolicy.enforce(c, p);
            p.edit().putLong(PREF_FAKE_CHECKED, now).putInt(PREF_FAKE_BOOT, boot)
                    .putBoolean(PREF_FAKE_PENDING, false).apply();
        }
        // An app was installed while Shizuku was off: block it now if the firewall is set to.
        if (p.getBoolean(PREF_FIREWALL_PENDING, false) && FirewallPolicy.supported()
                && FirewallPolicy.apply(c, p, false) == null) {
            p.edit().putBoolean(PREF_FIREWALL_PENDING, false).apply();
        }
        // Already done since the last restart (tiles and the widget start this process often).
        if (boot != -1 && boot == p.getInt(PREF_BOOT, -2)) return;
        boolean ok = true;
        if (FirewallPolicy.supported()) ok = FirewallPolicy.apply(c, p, false) == null;
        if (p.getBoolean(PREF_FORCE_DARK, false)) {
            ok &= Shell.run("setprop debug.hwui.force_dark true").ok();
        }
        if (p.getBoolean(PREF_NO_OVERLAYS, false)) {
            ok &= Shell.run(overlaysCommand(true)).ok();
        }
        if (ok) p.edit().putInt(PREF_BOOT, boot).apply();
    }
}
