package io.github.order52.tinykit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Brings the camera and microphone back when something TinyKit (or Android's own privacy switches)
 * left them blocked for every app. Three things can do that, and none of them is undone by a
 * restart:
 *
 * 1. Android's sensor privacy: "Camera access" / "Microphone access" turned off, or the TinyKit
 *    "Sensors off" and "Mic and camera off" switches. Phone calls still use the microphone, every
 *    other app gets silence and "Can't connect to the camera".
 * 2. The app-op of the phone's own camera app, or of Android itself, set to ignore or deny.
 * 3. The camera app switched off or removed for your user, so it is missing from app lists.
 *
 * Everything here only puts things back to Android's normal; it never blocks anything.
 */
final class CameraFix {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_.]+");
    private static final Pattern PRIVACY_ON = Pattern.compile("state_type=1|is_enabled=true");
    private static final String[][] OPS = {
            {"CAMERA", "android.permission.CAMERA"}, {"RECORD_AUDIO", "android.permission.RECORD_AUDIO"}};

    private CameraFix() {
    }

    /** Full repair, started by the user. Call off the main thread. Returns what was done, or null when nothing was wrong. */
    static synchronized String run(Context c, SharedPreferences p) {
        if (!Shell.ready()) return null;
        List<String> lines = new ArrayList<>();
        String s = sensors(c);
        if (s != null) lines.add(s);
        String apps = cameraPackages(c);
        if (apps != null) lines.add(apps);
        String ops = appOps(c, true);
        if (ops != null) lines.add(ops);
        p.edit().putBoolean(PermGuard.PREF_REPAIR_PENDING, false).apply();
        return lines.isEmpty() ? null : String.join(" ", lines);
    }

    /** Quiet check used by the safety net: only the app-op part, only camera apps and Android itself. */
    static String heal(Context c) {
        if (!Shell.ready()) return null;
        return appOps(c, false);
    }

    /**
     * Turns sensor privacy off for camera and microphone and the global "all sensors" switch (the one
     * in the Privacy tab). It always tries all of them, whatever the phone reports, then checks again.
     * Returns null when nothing was on, else what happened.
     */
    static String sensors(Context c) {
        boolean was = blocked(c);
        StringBuilder cmd = new StringBuilder("u=$(am get-current-user 2>/dev/null); ");
        for (String sensor : new String[]{"camera", "microphone"}) {
            cmd.append("cmd sensor_privacy disable 0 ").append(sensor).append(" >/dev/null 2>&1; ")
                    .append("[ -n \"$u\" ] && [ \"$u\" != 0 ] && cmd sensor_privacy disable \"$u\" ")
                    .append(sensor).append(" >/dev/null 2>&1; ");
        }
        Shell.run(cmd.toString());
        // "off" means blocking off (the Privacy switch and the tile send "off" to unblock).
        Shell.run(MainActivity.sensorsCmd(c, "off"));
        boolean still = blocked(c);
        if (still) {
            Shell.run(MainActivity.sensorsCmd(c, "off")); // once more: Android can apply it a moment late
            try {
                Thread.sleep(700);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            still = blocked(c);
        }
        if (still) {
            return "Couldn't turn off the camera/microphone privacy block. Open Settings > Privacy and turn "
                    + "Camera access and Microphone access on.";
        }
        return was ? "Turned off the sensor block that was cutting the camera and microphone for every app." : null;
    }

    /** Whether any sensor privacy block (camera, microphone or all sensors) is on right now. */
    private static boolean blocked(Context c) {
        return PRIVACY_ON.matcher(Shell.run("dumpsys sensor_privacy").out).find()
                || "true".equals(Shell.run(MainActivity.sensorsCmd(c, "state")).out.trim());
    }

    /** Re-enables or restores camera packages that are switched off or removed (they vanish from app lists). */
    private static String cameraPackages(Context c) {
        PackageManager pm = c.getPackageManager();
        StringBuilder script = new StringBuilder();
        List<String> fixed = new ArrayList<>();
        Pattern cam = Pattern.compile("(?i)camera");
        for (String line : Shell.run("pm list packages -d").out.split("\n")) {
            String pkg = line.trim().replaceFirst("^package:", "");
            if (!PACKAGE.matcher(pkg).matches() || !cam.matcher(pkg).find()) continue;
            script.append("pm enable ").append(Shell.quote(pkg)).append(" >/dev/null 2>&1; ");
            fixed.add(pkg);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            Set<String> installed = new LinkedHashSet<>();
            for (ApplicationInfo i : pm.getInstalledApplications(0)) installed.add(i.packageName);
            for (String line : Shell.run("pm list packages -u").out.split("\n")) {
                String pkg = line.trim().replaceFirst("^package:", "");
                if (!PACKAGE.matcher(pkg).matches() || installed.contains(pkg) || !cam.matcher(pkg).find()) continue;
                try {
                    ApplicationInfo i = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                    if ((i.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                    script.append("pm install-existing --user 0 ").append(Shell.quote(pkg)).append(" >/dev/null 2>&1; ");
                    fixed.add(pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        if (fixed.isEmpty()) return null;
        Shell.run(script.toString());
        return "Switched the camera app back on: " + String.join(", ", fixed) + ".";
    }

    /**
     * Camera and microphone app-ops that are ignored or denied on the phone's camera apps and on
     * Android's own apps, while their permission is granted. Android never produces that on its own.
     * {@code wide} also checks every system app that asks for the camera or microphone.
     */
    private static String appOps(Context c, boolean wide) {
        PackageManager pm = c.getPackageManager();
        Set<String> cameras = PermGuard.cameraApps(c);
        Set<String> candidates = new LinkedHashSet<>(cameras);
        for (PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            ApplicationInfo a = info.applicationInfo;
            if (a == null || !PACKAGE.matcher(info.packageName).matches() || info.requestedPermissions == null) continue;
            boolean system = (a.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if (!system || (!wide && a.uid >= 10000)) continue;
            for (String r : info.requestedPermissions) {
                if (r.equals(OPS[0][1]) || r.equals(OPS[1][1])) {
                    candidates.add(info.packageName);
                    break;
                }
            }
        }
        List<String[]> items = new ArrayList<>();
        for (String pkg : candidates) for (String[] op : OPS) items.add(new String[]{pkg, op[0], "u"});
        Map<String, String> now = PermGuard.read(items);
        StringBuilder fix = new StringBuilder();
        List<String[]> toFix = new ArrayList<>();
        for (String pkg : candidates) {
            for (String[] op : OPS) {
                String mode = now.get(pkg + "|" + op[0]);
                if (!"ignore".equals(mode) && !"deny".equals(mode)) continue;
                boolean granted = pm.checkPermission(op[1], pkg) == PackageManager.PERMISSION_GRANTED;
                // Normal: the user never allowed it, or took it back. Only the repair the user starts
                // gives a camera app its permission back; the automatic check never does.
                if (!granted && (!wide || !cameras.contains(pkg))) continue;
                if (!granted) fix.append("pm grant ").append(Shell.quote(pkg)).append(' ').append(op[1]).append(" 2>/dev/null; ");
                fix.append("appops set --uid ").append(Shell.quote(pkg)).append(' ').append(op[0]).append(" allow; ")
                        .append("appops set ").append(Shell.quote(pkg)).append(' ').append(op[0]).append(" default; ");
                toFix.add(new String[]{pkg, op[0]});
            }
        }
        android.util.Log.i("TinyKit", "camera: checked " + candidates.size() + " apps, fixing " + toFix.size());
        if (toFix.isEmpty()) return null;
        Shell.run(fix.toString());
        List<String[]> check = new ArrayList<>();
        for (String[] f : toFix) check.add(new String[]{f[0], f[1], "u"});
        Map<String, String> after = PermGuard.read(check);
        Set<String> good = new LinkedHashSet<>();
        Set<String> bad = new LinkedHashSet<>();
        for (String[] f : toFix) {
            String m = after.get(f[0] + "|" + f[1]);
            (("ignore".equals(m) || "deny".equals(m)) ? bad : good).add(f[0]);
        }
        StringBuilder out = new StringBuilder();
        if (!good.isEmpty()) {
            out.append("Put the camera/microphone permission back to normal on ").append(good.size())
                    .append(good.size() == 1 ? " system app (" : " system apps (").append(good.iterator().next())
                    .append(good.size() > 1 ? " and others)." : ").");
        }
        if (!bad.isEmpty()) {
            if (out.length() > 0) out.append(' ');
            out.append("Couldn't fix ").append(bad.iterator().next()).append(bad.size() > 1 ? " and others." : ".");
        }
        String s = out.toString();
        return bad.isEmpty() ? s : "Couldn't fully fix: " + s;
    }
}
