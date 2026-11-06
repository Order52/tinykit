package io.github.order52.tinykit;

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
            try {
                tap(active);
            } catch (RuntimeException e) {
                android.util.Log.w("TinyKit", "tile tap failed: " + e);
                toast(Tr.t("Couldn't do that. Check that Shizuku is running and allowed."));
            }
            refresh();
        });
    }

    void refresh() {
        WORKER.execute(() -> {
            Object[] state;
            try {
                state = state();
            } catch (RuntimeException e) {
                // A tile that can't read its state shows as off instead of taking the app down.
                android.util.Log.w("TinyKit", "tile state failed: " + e);
                state = state(false, "Unavailable");
            }
            final Object[] shown = state;
            main.post(() -> {
                Tile tile = getQsTile();
                if (tile == null) return;
                tile.setState((Boolean) shown[0] ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
                if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(Tr.t((String) shown[1]));
                tile.updateTile();
            });
        });
    }

    void toast(String message) {
        main.post(() -> Toast.makeText(this, Tr.t(message), Toast.LENGTH_LONG).show());
    }

    boolean shizuku() {
        if (Shell.await()) return true;
        toast(Tr.t("Start Shizuku first."));
        return false;
    }

    SharedPreferences prefs() {
        return prefs(this);
    }

    static SharedPreferences prefs(android.content.Context c) {
        return c.getSharedPreferences("tinykit", android.content.Context.MODE_PRIVATE);
    }

    static Object[] state(boolean active, String subtitle) {
        return new Object[]{active, subtitle};
    }

    // ---- Shared with the app ---------------------------------------------------------------

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
            if (error != null) toast(Tr.t(error));
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

    /**
     * A tile for a setting Android forgets at a restart. It remembers the choice in TinyKit's
     * preferences, which is also what {@link Restore} puts back once Shizuku is running.
     */
    abstract static class Remembered extends Tiles {
        abstract String pref();

        abstract String command(boolean on);

        @Override
        Object[] state() {
            return state(prefs().getBoolean(pref(), false), null);
        }

        @Override
        void tap(boolean active) {
            if (!shizuku()) return;
            Shell.Result r = Shell.run(command(!active));
            if (r.ok()) {
                prefs().edit().putBoolean(pref(), !active).apply();
            } else {
                toast(Tr.t("Couldn't change this. " + r.out));
            }
        }
    }

    /** Pauses and resumes the work profile (Android's own pause), if there is one. */
    public static final class WorkProfile extends Tiles {
        @Override
        Object[] state() {
            if (!Shell.await()) return state(false, "Start Shizuku");
            int id = Work.id(this);
            if (id < 0) return state(false, "Not set up");
            int state = Work.state(this, id);
            return state(state == Work.RUNNING, state == Work.PAUSED ? "Paused" : state == Work.LOCKED ? "Locked" : "On");
        }

        @Override
        void tap(boolean active) {
            if (!shizuku()) return;
            int id = Work.id(this);
            if (id < 0) {
                toast(Tr.t("Create the work profile in TinyKit first (Apps tab)."));
                return;
            }
            if (Work.state(this, id) == Work.LOCKED) {
                toast(Tr.t("The work profile is locked. Open TinyKit > Apps > Work profile and unlock it with its PIN."));
                return;
            }
            // Active means running, so a tap pauses (locks); otherwise it resumes.
            String error = Work.pause(this, id, active);
            if (error != null) toast(Tr.t(error));
        }
    }

    /** Cycles deep sleep: Default, Light, Moderate, High, Extreme. */
    public static final class DeepSleep extends Tiles {
        @Override
        Object[] state() {
            if (!Shell.await()) return state(false, "Start Shizuku");
            int p = prefs().getInt(Doze.PREF, 0);
            return state(p > 0, Doze.NAMES[p]);
        }

        @Override
        void tap(boolean active) {
            if (!shizuku()) return;
            int next = (prefs().getInt(Doze.PREF, 0) + 1) % Doze.NAMES.length;
            String error = Doze.apply(this, next);
            if (error != null) toast(error); else prefs().edit().putInt(Doze.PREF, next).apply();
        }
    }

    /** Opens the audio mixer: a tap closes the panel and shows the mixer page. */
    public static final class Mixer extends Tiles {
        @Override
        Object[] state() {
            return state(false, "Volume per app");
        }

        @Override
        void tap(boolean active) {
        }

        @Override
        public void onClick() {
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_MIXER, true);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, open,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            } else {
                startActivityAndCollapse(open);
            }
        }
    }

    /** Pauses and resumes the internet block without losing the lists (Android 14+). */
    public static final class Firewall extends Tiles {
        @Override
        Object[] state() {
            SharedPreferences p = prefs();
            if (!FirewallPolicy.supported()) return state(false, "Android 14+");
            if (!FirewallPolicy.configured(p)) return state(false, "Not set up");
            boolean paused = FirewallPolicy.paused(p);
            return state(!paused, paused ? "Paused" : "Blocking");
        }

        @Override
        void tap(boolean active) {
            SharedPreferences p = prefs();
            if (!FirewallPolicy.supported() || !FirewallPolicy.configured(p)) {
                toast(Tr.t(FirewallPolicy.supported() ? "Choose apps to block in TinyKit first." : "Needs Android 14 or newer."));
                return;
            }
            if (!shizuku()) return;
            // Active means blocking now, so a tap pauses; otherwise it resumes.
            p.edit().putBoolean(FirewallPolicy.PAUSED, active).apply();
            String error = FirewallPolicy.apply(this, p, true);
            if (error != null) {
                p.edit().putBoolean(FirewallPolicy.PAUSED, !active).apply();
                toast(Tr.t("Couldn't change this. " + error));
            }
        }
    }

    /** The "Disable HW overlays" developer option. */
    public static final class HwOverlays extends Remembered {
        @Override
        String pref() {
            return Restore.PREF_NO_OVERLAYS;
        }

        @Override
        String command(boolean on) {
            return Restore.overlaysCommand(on);
        }
    }

    public static final class ForceDark extends Remembered {
        @Override
        String pref() {
            return Restore.PREF_FORCE_DARK;
        }

        @Override
        String command(boolean on) {
            return Restore.forceDarkCommand(on);
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
            if (!Tweak.setTimeout(this, next)) toast(Tr.t("Start Shizuku once so TinyKit can change this."));
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
                    toast(Tr.t("Start Shizuku once so TinyKit can change this."));
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
                // Past the last step: back to the phone's original timeout. Kept until that worked.
                if (Tweak.setTimeout(this, prefs.getInt("caffeine_prev", 30_000))) {
                    prefs.edit().remove("caffeine_prev").apply();
                } else {
                    toast(Tr.t("Start Shizuku once so TinyKit can change this."));
                }
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
                toast(Tr.t("Screenshot saved to Pictures/Screenshots"));
            } else if (r.code == 3) {
                toast(Tr.t("This app blocks screenshots. Android doesn't let any app get past that without root."));
            } else {
                toast(Tr.t("Couldn't take a screenshot. " + r.out));
            }
        }
    }

    /**
     * Records the screen to Movies; tap to start, tap again to stop and save. Android's screenrecord
     * stops by itself after 3 minutes. The recording is followed by its process id, so another app's
     * recorder (or TinyKit's own shell) is never mistaken for it.
     */
    public static final class ScreenRecord extends Tiles {
        @Override
        Object[] state() {
            boolean on = Shell.ready() && recording(this);
            if (!on) saveFinished(this);
            return state(on, on ? "Recording…" : null);
        }

        private static boolean recording(android.content.Context c) {
            int pid = prefs(c).getInt("screenrecord_pid", 0);
            return pid > 0 && Shell.run("grep -q screenrecord /proc/" + pid + "/cmdline 2>/dev/null").ok();
        }

        /** Adds a finished recording (stopped by a tap or by the time limit) to the gallery, once. */
        private static void saveFinished(android.content.Context c) {
            SharedPreferences p = prefs(c);
            String path = p.getString("screenrecord_file", "");
            if (path.isEmpty() || recording(c)) return;
            p.edit().remove("screenrecord_file").remove("screenrecord_pid").apply();
            MediaScannerConnection.scanFile(c, new String[]{path}, new String[]{"video/mp4"}, null);
        }

        @Override
        void tap(boolean active) {
            if (!shizuku()) return;
            int pid = prefs().getInt("screenrecord_pid", 0);
            if (active && pid > 0) {
                // SIGINT lets screenrecord finish writing a playable file.
                Shell.run("kill -INT " + pid + "; for i in 1 2 3 4 5 6 7 8 9 10; do "
                        + "grep -q screenrecord /proc/" + pid + "/cmdline 2>/dev/null || break; sleep 0.3; done");
                saveFinished(this);
                toast(Tr.t("Recording saved to Movies"));
                return;
            }
            String dir = "/sdcard/Movies";
            String path = dir + "/Screen_" + stamp() + "_TinyKit.mp4";
            Shell.Result r = Shell.run("command -v screenrecord >/dev/null 2>&1 || exit 3; mkdir -p " + dir
                    + "; screenrecord " + Shell.quote(path) + " </dev/null >/dev/null 2>&1 & p=$!; "
                    + "sleep 0.8; grep -q screenrecord /proc/$p/cmdline 2>/dev/null && echo $p || exit 4");
            int started = 0;
            try {
                started = Integer.parseInt(r.out.trim());
            } catch (NumberFormatException ignored) {
            }
            if (r.ok() && started > 0) {
                prefs().edit().putString("screenrecord_file", path).putInt("screenrecord_pid", started).apply();
                toast(Tr.t("Recording, for up to 3 minutes. Tap the tile again to stop."));
            } else if (r.code == 3) {
                toast(Tr.t("This phone has no screenrecord command. Use its built-in screen recorder."));
            } else {
                toast(Tr.t("Couldn't start recording. This screen may be protected, or another recording is running."));
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
            last = Ram.clearCaches(this);
        }
    }

    /** Shows free memory; tapping stops background apps. */
    public static final class Memory extends Tiles {
        @Override
        Object[] state() {
            return state(false, Formatter.formatShortFileSize(this, Ram.available(this)) + " free");
        }

        @Override
        void tap(boolean active) {
            if (Ram.needsShizuku() && !shizuku()) return;
            if (!Ram.needsShizuku()) Shell.await();
            toast(Tr.t(Ram.free(this)));
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
            if (!r.ok()) toast(Tr.t("Couldn't toggle sensors. " + r.out));
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
                toast(Tr.t("Closed " + r.out));
            } else {
                toast(Tr.t("Couldn't tell which app is in front."));
            }
        }
    }
}
