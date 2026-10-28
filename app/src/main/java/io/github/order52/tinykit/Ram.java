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
 * Keeps the phone smooth the way Android itself would: asks apps to release memory, compacts what
 * cached apps hold, and closes cached apps only when memory is really low (never the app on
 * screen, music, navigation or anything running a service). Also clears app caches.
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
    // skipped there. KILL is "kill-all" (cached apps only) and runs only when memory is really low:
    // closing apps Android could have kept makes them cold-start later, which is slower.
    private static String script(boolean kill) {
        return APPS
            + "A=; cmd activity help 2>/dev/null | grep -q send-trim-memory && A='cmd activity'; "
            + "a=$(L | wc -l); " + (kill ? "${A:-am} kill-all; sleep 1; " : "")
            + "[ -n \"$A\" ] && L | { while read p n; do "
            // SystemUI is left alone so the status bar and quick panel never redraw. Background
            // apps take MODERATE (release what they can rebuild); apps in use only accept
            // RUNNING_LOW; Android refuses a level below what it already sent, so try both.
            + "[ \"$n\" = com.android.systemui ] || { $A send-trim-memory \"$p\" MODERATE || "
            + "$A send-trim-memory \"$p\" RUNNING_LOW; } >/dev/null 2>&1 & "
            + "done; wait; }; "
            // Android 11+: compress what cached apps still hold instead of closing them.
            + "[ -n \"$A\" ] && $A help 2>/dev/null | grep -q 'compact system' && $A compact system >/dev/null 2>&1; "
            + "echo $((a - $(L | wc -l)))";
    }

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

    /**
     * One tap to make the phone smoother, using only what helps:
     * 1. asks apps to release memory they can rebuild and compacts what cached apps still hold;
     * 2. closes cached apps only when free memory is under a quarter of the total;
     * 3. clears just enough app caches to bring storage back to 20% free, only if it is under 15%;
     * 4. starts Android's own idle maintenance (TRIM and compiling), at most once a day.
     * Returns a short result for the user. Runs off the main thread.
     */
    static String free(Context c) {
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        c.getSystemService(ActivityManager.class).getMemoryInfo(mem);
        boolean tight = mem.availMem < mem.totalMem / 4;
        long before = mem.availMem;
        int closed = 0;
        java.util.List<String> did = new java.util.ArrayList<>();
        if (Shell.ready()) {
            Shell.Result r = Shell.run(script(tight));
            String[] lines = r.out.split("\n");
            try {
                closed = Math.max(0, Integer.parseInt(lines[lines.length - 1].trim()));
            } catch (NumberFormatException e) {
                if (!r.ok()) return "Failed: " + r.out;
            }
            did.add(closed > 0 ? "closed " + closed + " cached app" + (closed == 1 ? "" : "s")
                    : "trimmed app memory");
            String storage = storage(c);
            if (storage != null) did.add(storage);
            if (maintenance(c)) did.add("started Android's storage maintenance");
        } else if (!needsShizuku()) {
            if (tight) {
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
                did.add("closed cached apps");
            }
        } else {
            return "Start Shizuku first.";
        }
        long freed = Math.max(0, available(c) - before);
        String free = Formatter.formatShortFileSize(c, available(c)) + " memory free now.";
        if (did.isEmpty()) return "Memory is fine, so no apps were closed. " + free;
        return (freed >= 1024 * 1024 ? "Freed " + Formatter.formatShortFileSize(c, freed) + ": " : "Done: ")
                + String.join(", ", did) + ". " + free;
    }

    /** Clears app caches only if storage is nearly full, and only as much as needed. */
    private static String storage(Context c) {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
        if (free >= total * 15 / 100) return null;
        Shell.Result r = Shell.run("pm trim-caches " + (total / 5));
        long gained = Math.max(0, freeStorage() - free);
        return r.ok() && gained > 0 ? "cleared " + Formatter.formatShortFileSize(c, gained)
                + " of caches (storage was nearly full)" : null;
    }

    /** Starts Android's idle maintenance (TRIM, compiling) in the system, at most once a day. */
    private static boolean maintenance(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("tinykit", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - p.getLong("maintenance", 0) < 24L * 3600 * 1000) return false;
        if (!Shell.run("sm idle-maint run").ok()) return false;
        p.edit().putLong("maintenance", now).apply();
        return true;
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
