package io.github.order52.tinykit;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.SystemClock;
import android.text.format.Formatter;

/**
 * Frees memory the way Android itself does when it runs low, so nothing the user is using breaks:
 * it closes only the apps Android has already set aside as cached (never the app on screen,
 * music, navigation or anything running a service), then asks the apps still running to release
 * their own caches.
 *
 * With Shizuku this uses the shell's "am kill-all" and "am send-trim-memory". Without it,
 * Android 13 and older let any app close other apps' background processes, so TinyKit can still
 * do the first half; Android 14 removed that, so there it needs Shizuku.
 */
final class Ram {
    // Running app processes (app uids only, so no system processes), one name per line.
    private static final String APPS = "dumpsys activity processes | grep -oE 'ProcessRecord\\{[^}]*\\}' "
            + "| grep -E '/u[0-9]+a[0-9]+\\}' | sed -E 's/.* [0-9]+:([^/]+)\\/.*/\\1/' | sort -u";

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
            // SystemUI is left alone so the status bar and quick panel never redraw.
            Shell.Result r = Shell.run("L() { " + APPS + "; }; a=$(L | wc -l); am kill-all; sleep 1; "
                    + "for p in $(L | grep -v '^com.android.systemui$'); do "
                    + "am send-trim-memory \"$p\" RUNNING_LOW >/dev/null 2>&1; done; "
                    + "echo $((a - $(L | wc -l)))");
            if (!r.ok()) return "Failed: " + r.out;
            try {
                String[] lines = r.out.split("\n");
                closed = Math.max(0, Integer.parseInt(lines[lines.length - 1].trim()));
            } catch (NumberFormatException ignored) {
            }
        } else if (!needsShizuku()) {
            ActivityManager am = c.getSystemService(ActivityManager.class);
            for (ApplicationInfo app : c.getPackageManager().getInstalledApplications(0)) {
                if (app.packageName.equals(c.getPackageName())) continue;
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
}
