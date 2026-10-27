package io.github.order52.tinykit;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.format.Formatter;

/**
 * Frees memory the way Android itself does when it runs low, so nothing the user is using breaks:
 * it closes only the apps Android has already set aside as cached (never the app on screen,
 * music, navigation or anything running a service), then asks the apps still running to release
 * their own caches. Also clears app caches for the speed-up widget and the System tab.
 *
 * With Shizuku this uses the shell's "kill-all" and "send-trim-memory". Without it, Android 13
 * and older let any app close other apps' background processes, so TinyKit can still do the
 * first half; Android 14 removed that, so there it needs Shizuku.
 *
 * The shell script only uses what every Android version has (sh, ps, grep, wc): no awk, sed or
 * sort, and no dumpsys output whose format changes between versions and manufacturers.
 */
final class Ram {
    // App processes (uids like u0_a123, any user) as "pid name", one per line. Android 8+ needs
    // "ps -A" to list everything; Android 7's ps lists everything already and treats -A as a
    // name filter, printing only the header, so fall back to plain ps when that happens.
    private static final String APPS = "L() { o=$(ps -A 2>/dev/null); "
            + "[ $(echo \"$o\" | wc -l) -gt 1 ] || o=$(ps); "
            + "echo \"$o\" | while read u p r; do case \"$u\" in u[0-9]*_a[0-9]*) echo \"$p ${r##* }\";; esac; done; }; ";

    // "cmd activity" talks to the system directly. Older versions only have "am", which starts a
    // Java VM per call: fine for one kill-all, far too slow for one trim per app, so the trims are
    // skipped there (kill-all does most of the work anyway).
    private static final String SCRIPT = APPS
            + "A=; cmd activity help 2>/dev/null | grep -q send-trim-memory && A='cmd activity'; "
            + "a=$(L | wc -l); ${A:-am} kill-all; sleep 1; "
            + "[ -n \"$A\" ] && L | { while read p n; do "
            // SystemUI is left alone so the status bar and quick panel never redraw. Background
            // apps take MODERATE (release what they can rebuild); apps in use only accept
            // RUNNING_LOW; Android refuses a level below what it already sent, so try both.
            + "[ \"$n\" = com.android.systemui ] || { $A send-trim-memory \"$p\" MODERATE || "
            + "$A send-trim-memory \"$p\" RUNNING_LOW; } >/dev/null 2>&1 & "
            + "done; wait; }; "
            + "echo $((a - $(L | wc -l)))";

    private Ram() {
    }

    static boolean needsShizuku() {
        return Build.VERSION.SDK_INT >= 34;
    }

    static long available(Context c) {
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        c.getSystemService(ActivityManager.class).getMemoryInfo(mem);
        return mem.availMem;
    }

    /** Frees memory and returns a short result for the user. Runs off the main thread. */
    static String free(Context c) {
        long before = available(c);
        int closed = -1;
        if (Shell.ready()) {
            Shell.Result r = Shell.run(SCRIPT);
            String[] lines = r.out.split("\n");
            try {
                closed = Math.max(0, Integer.parseInt(lines[lines.length - 1].trim()));
            } catch (NumberFormatException e) {
                if (!r.ok()) return "Failed: " + r.out;
            }
        } else if (!needsShizuku()) {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            for (ApplicationInfo app : c.getPackageManager().getInstalledApplications(0)) {
                // Stopped apps have no processes to close.
                if ((app.flags & ApplicationInfo.FLAG_STOPPED) != 0
                        || app.packageName.equals(c.getPackageName())) continue;
                try {
                    am.killBackgroundProcesses(app.packageName);
                } catch (SecurityException ignored) {
                }
            }
            SystemClock.sleep(1000);
        } else {
            return "Start Shizuku first.";
        }
        long freed = Math.max(0, available(c) - before);
        String free = Formatter.formatShortFileSize(c, available(c)) + " free now.";
        if (freed < 1024 * 1024 && closed <= 0) return "Memory was already clear. " + free;
        return "Freed " + Formatter.formatShortFileSize(c, freed)
                + (closed > 0 ? " and closed " + closed + " cached app" + (closed == 1 ? "" : "s") : "")
                + ". " + free;
    }

    /**
     * Clears every app's cache with Android's own "pm trim-caches" (what it does itself when
     * storage runs low) and returns "Freed 12 MB" or "Failed: …". Needs Shizuku.
     * Free space is measured with StatFs rather than df, whose output differs between versions.
     */
    static String clearCaches(Context c) {
        long before = freeStorage();
        // Asking for far more free space than any phone has makes Android clear every cache.
        Shell.Result r = Shell.run("pm trim-caches 999G");
        if (!r.ok()) return "Failed: " + r.out;
        return "Freed " + Formatter.formatShortFileSize(c, Math.max(0, freeStorage() - before));
    }

    private static long freeStorage() {
        return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
    }
}
