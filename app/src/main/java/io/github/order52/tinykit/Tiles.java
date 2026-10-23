package io.github.order52.tinykit;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.text.format.Formatter;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Quick Settings tiles. Android binds a tile only while the quick panel is open, and none of
 * them asks to stay active, so they cost nothing the rest of the time.
 *
 * Starting and stopping Shizuku itself has its own tile in {@link ShizukuTile}, and the screenshot
 * tile is {@link ScreenshotTile}; this holds the rest.
 */
public abstract class Tiles extends TileService {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());

    /** Runs on the worker thread; returns {active, subtitle}. */
    abstract Object[] state();

    /** Runs on the worker thread when the tile is tapped. */
    abstract void tap(boolean active);

    @Override
    public void onStartListening() {
        refresh();
    }

    @Override
    public void onClick() {
        Tile tile = getQsTile();
        boolean active = tile != null && tile.getState() == Tile.STATE_ACTIVE;
        WORKER.execute(() -> {
            tap(active);
            refresh();
        });
    }

    void refresh() {
        WORKER.execute(() -> {
            Object[] state = state();
            main.post(() -> {
                Tile tile = getQsTile();
                if (tile == null) return;
                tile.setState((Boolean) state[0] ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
                if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle((String) state[1]);
                tile.updateTile();
            });
        });
    }

    void toast(String message) {
        main.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    boolean shizuku() {
        if (Shell.await()) return true;
        toast("Start Shizuku first.");
        return false;
    }

    SharedPreferences prefs() {
        return getSharedPreferences("tinykit", MODE_PRIVATE);
    }

    static Object[] state(boolean active, String subtitle) {
        return new Object[]{active, subtitle};
    }

    // ---- Shared with the app ---------------------------------------------------------------

    /** Clears every app's cache and says how much space came back. Call off the main thread. */
    static String clearCaches() {
        String free = "df -k /data | tail -1 | awk '{print $4}'";
        Shell.Result r = Shell.run("a=$(" + free + "); pm trim-caches 999G; b=$(" + free + "); "
                + "echo $(( (b - a) / 1024 ))");
        if (!r.ok()) return "Failed: " + r.out;
        long mb = 0;
        try {
            mb = Math.max(0, Long.parseLong(r.out.trim()));
        } catch (NumberFormatException ignored) {
        }
        return "Freed " + mb + " MB";
    }

    // ---- Settings toggles ----------------------------------------------------------------------

    /** A tile that flips one {@link Tweak}. */
    abstract static class Toggle extends Tiles {
        abstract Tweak tweak();

        @Override
        Object[] state() {
            if (tweak() == Tweak.MIC_CAM_OFF) Shell.await();
            return state(tweak().isOn(this), null);
        }

        @Override
        void tap(boolean active) {
            if (!Tweak.canWrite(this) || tweak() == Tweak.MIC_CAM_OFF) Shell.await();
            String error = tweak().set(this, !active);
            if (error != null) toast(error);
        }
    }

    public static final class HideDev extends Toggle {
        @Override
        Tweak tweak() {
            return Tweak.HIDE_DEV;
        }
    }

    public static final class DevOptions extends Toggle {
        @Override
        Tweak tweak() {
            return Tweak.DEV_OPTIONS;
        }
    }

    public static final class UsbDebug extends Toggle {
        @Override
        Tweak tweak() {
            return Tweak.USB_DEBUG;
        }
    }

    public static final class AdBlock extends Toggle {
        @Override
        Tweak tweak() {
            return Tweak.AD_DNS;
        }
    }

    public static final class Grayscale extends Toggle {
        @Override
        Tweak tweak() {
            return Tweak.GRAYSCALE;
        }
    }

    public static final class ExtraDim extends Toggle {
        @Override
        Tweak tweak() {
            return Tweak.EXTRA_DIM;
        }
    }

    public static final class MicCam extends Toggle {
        @Override
        Tweak tweak() {
            return Tweak.MIC_CAM_OFF;
        }
    }

    /** Cycles 30 s, 1 min, 5 min, 30 min and never. Sets the phone's normal screen timeout. */
    public static final class Timeout extends Tiles {
        @Override
        Object[] state() {
            return state(true, Tweak.timeoutLabel(Tweak.timeout(this)));
        }

        @Override
        void tap(boolean active) {
            int now = Tweak.timeout(this);
            int next = Tweak.TIMEOUTS[0];
            for (int ms : Tweak.TIMEOUTS) {
                if (ms > now) {
                    next = ms;
                    break;
                }
            }
            if (!Tweak.canWrite(this)) Shell.await();
            if (!Tweak.setTimeout(this, next)) toast("Start Shizuku once so TinyKit can change this.");
        }
    }

    /**
     * Keeps the screen on by raising the screen-off timeout, cycling 5 min, 15 min, 30 min and
     * never. Turning it off restores the timeout the phone had before. Android still turns the
     * screen off on its own, so nothing has to run in the background.
     */
    public static final class Caffeine extends Tiles {
        private static final int[] STEPS = {300_000, 900_000, 1_800_000, Integer.MAX_VALUE};

        @Override
        Object[] state() {
            boolean on = prefs().contains("caffeine_prev");
            return state(on, on ? Tweak.timeoutLabel(Tweak.timeout(this)) : "Off");
        }

        @Override
        void tap(boolean active) {
            if (!Tweak.canWrite(this)) Shell.await();
            SharedPreferences prefs = prefs();
            if (!active) {
                prefs.edit().putInt("caffeine_prev", Tweak.timeout(this)).apply();
                if (!Tweak.setTimeout(this, STEPS[0])) {
                    prefs.edit().remove("caffeine_prev").apply();
                    toast("Start Shizuku once so TinyKit can change this.");
                }
                return;
            }
            int now = Tweak.timeout(this);
            int next = 0;
            for (int ms : STEPS) {
                if (ms > now) {
                    next = ms;
                    break;
                }
            }
            if (next == 0) {
                // Past the last step: back to the phone's original timeout.
                Tweak.setTimeout(this, prefs.getInt("caffeine_prev", 30_000));
                prefs.edit().remove("caffeine_prev").apply();
            } else {
                Tweak.setTimeout(this, next);
            }
        }
    }

    public static final class Screenshot extends Tiles {
        @Override
        Object[] state() {
            return state(false, null);
        }

        @Override
        void tap(boolean active) {
            if (!shizuku()) return;
            String dir = "/sdcard/Pictures/Screenshots";
            Shell.Result r = Shell.run("cmd statusbar collapse; sleep 0.7; mkdir -p " + dir
                    + "; f=" + dir + "/Screenshot_$(date +%Y%m%d_%H%M%S)_TinyKit.png; "
                    + "screencap -p \"$f\" && [ -s \"$f\" ] && echo \"$f\" || { rm -f \"$f\"; exit 3; }");
            if (r.ok()) {
                MediaScannerConnection.scanFile(this, new String[]{r.out}, new String[]{"image/png"}, null);
                toast("Screenshot saved to Pictures/Screenshots");
            } else if (r.code == 3) {
                toast("This app blocks screenshots. Android doesn't let any app get past that without root.");
            } else {
                toast("Couldn't take a screenshot. " + r.out);
            }
        }
    }

    /** Records the screen to Movies; tap to start, tap again to stop and save. */
    public static final class ScreenRecord extends Tiles {
        @Override
        Object[] state() {
            boolean on = Shell.ready() && !Shell.run("pgrep -f '[s]creenrecord'").out.trim().isEmpty();
            return state(on, on ? "Recording…" : null);
        }

        @Override
        void tap(boolean active) {
            if (!shizuku()) return;
            if (active) {
                // SIGINT lets screenrecord finish writing a playable file.
                Shell.run("pkill -INT -f '[s]creenrecord'");
                SystemClock.sleep(1200);
                String path = prefs().getString("screenrecord_file", "");
                if (!path.isEmpty()) {
                    MediaScannerConnection.scanFile(this, new String[]{path}, new String[]{"video/mp4"}, null);
                }
                toast("Recording saved to Movies");
                return;
            }
            String dir = "/sdcard/Movies";
            String path = dir + "/Screen_" + stamp() + "_TinyKit.mp4";
            prefs().edit().putString("screenrecord_file", path).apply();
            Shell.Result r = Shell.run("command -v screenrecord >/dev/null 2>&1 || exit 3; mkdir -p " + dir
                    + "; (screenrecord " + Shell.quote(path) + " >/dev/null 2>&1 &); "
                    + "sleep 0.6; pgrep -f '[s]creenrecord' >/dev/null");
            if (r.ok()) {
                toast("Recording. Tap the tile again to stop.");
            } else if (r.code == 3) {
                toast("This phone has no screenrecord command. Use its built-in screen recorder.");
            } else {
                toast("Couldn't start recording. " + r.out);
            }
        }

        private static String stamp() {
            return new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT)
                    .format(new java.util.Date());
        }
    }

    public static final class ClearCaches extends Tiles {
        private String last;

        @Override
        Object[] state() {
            return state(false, last);
        }

        @Override
        void tap(boolean active) {
            if (!shizuku()) return;
            last = clearCaches();
        }
    }

    /** Shows free memory; tapping stops background apps. */
    public static final class Memory extends Tiles {
        @Override
        Object[] state() {
            return state(false, Formatter.formatShortFileSize(this, available()) + " free");
        }

        @Override
        void tap(boolean active) {
            if (!shizuku()) return;
            long before = available();
            Shell.run("am kill-all");
            SystemClock.sleep(800);
            long freed = Math.max(0, available() - before);
            toast("Stopped background apps. Freed " + Formatter.formatShortFileSize(this, freed) + ".");
        }

        private long available() {
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            getSystemService(ActivityManager.class).getMemoryInfo(mem);
            return mem.availMem;
        }
    }

    /** Turns every sensor off (motion, mic, camera) — the hidden "Sensors off" developer tile. */
    public static final class SensorsOff extends Tiles {
        @Override
        Object[] state() {
            Shell.await();
            boolean on = "true".equals(Shell.run(MainActivity.sensorsCmd(this, "state")).out.trim());
            return state(on, on ? "Blocked" : null);
        }

        @Override
        void tap(boolean active) {
            if (!shizuku()) return;
            Shell.Result r = Shell.run(MainActivity.sensorsCmd(this, active ? "off" : "on"));
            if (!r.ok()) toast("Couldn't toggle sensors. " + r.out);
        }
    }

    /** Closes whatever app is on screen. */
    public static final class KillForeground extends Tiles {
        @Override
        Object[] state() {
            return state(false, null);
        }

        @Override
        void tap(boolean active) {
            if (!shizuku()) return;
            Shell.Result r = Shell.run("cmd statusbar collapse; sleep 0.6; "
                    + "f=$(dumpsys activity activities 2>/dev/null | grep -m1 'ResumedActivity'); "
                    + "[ -z \"$f\" ] && f=$(dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus'); "
                    + "pkg=$(echo \"$f\" | grep -oE '[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+' "
                    + "| grep -ivE 'systemui|launcher|trebuchet|tinykit' | head -1); "
                    + "[ -n \"$pkg\" ] && am force-stop \"$pkg\" && echo \"$pkg\"");
            if (r.ok() && !r.out.isEmpty()) {
                toast("Closed " + r.out);
            } else {
                toast("Couldn't tell which app is in front.");
            }
        }
    }
}
