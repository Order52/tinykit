package io.github.order52.tinykit;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safety net for Real / Fake / Off on permissions.
 *
 * A permission's app-op is set for the whole user id ({@code appops set --uid}), so on an app that
 * shares its user id with others the change lands on all of them, and on a system app it can stop
 * the camera from opening at all. This class
 *
 * - refuses Fake and Off where that can happen ({@link #refuse}),
 * - checks after every change that no app TinyKit did not touch was changed too, and undoes the
 *   change if one was ({@link #setPermission}),
 * - repairs damage that is already there: a permission that is granted but ignored is exactly what
 *   Fake produces and Android never does by itself, so on an app the user did not make Fake it is
 *   put back to normal ({@link #repair}).
 *
 * Every write is read back. Nothing is reported as done unless Android's state really changed.
 */
final class PermGuard {
    /** {app-op, runtime permission} of every permission TinyKit can make Fake. */
    static final String[][] OPS = {
            {"COARSE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION"},
            {"FINE_LOCATION", "android.permission.ACCESS_FINE_LOCATION"},
            {"CAMERA", "android.permission.CAMERA"},
            {"RECORD_AUDIO", "android.permission.RECORD_AUDIO"},
            {"READ_CONTACTS", "android.permission.READ_CONTACTS"},
            {"WRITE_CONTACTS", "android.permission.WRITE_CONTACTS"},
            {"READ_CALENDAR", "android.permission.READ_CALENDAR"},
            {"READ_CALL_LOG", "android.permission.READ_CALL_LOG"},
            {"READ_PHONE_STATE", "android.permission.READ_PHONE_STATE"},
            {"READ_SMS", "android.permission.READ_SMS"},
            {"READ_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE"},
            {"WRITE_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE"},
            {"BODY_SENSORS", "android.permission.BODY_SENSORS"},
            {"ACTIVITY_RECOGNITION", "android.permission.ACTIVITY_RECOGNITION"},
            {"POST_NOTIFICATION", "android.permission.POST_NOTIFICATIONS"}};

    /** Set when a repair could not finish, so the next start tries again. */
    static final String PREF_REPAIR_PENDING = "perm_repair_pending";

    private static final int FIRST_APP_UID = 10000;
    private static final Pattern MODE = Pattern.compile("\\b(allow|ignore|deny|foreground|default)\\b");
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.]+");

    private PermGuard() {
    }

    /** The phone's own camera apps (system apps that handle taking a photo or video). */
    static Set<String> cameraApps(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> found = new LinkedHashSet<>();
        String[] actions = {MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.ACTION_VIDEO_CAPTURE,
                MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.INTENT_ACTION_VIDEO_CAMERA};
        for (String action : actions) {
            List<ResolveInfo> list = pm.queryIntentActivities(new Intent(action), PackageManager.MATCH_DEFAULT_ONLY);
            for (ResolveInfo r : list) {
                if (r.activityInfo == null) continue;
                ApplicationInfo info = r.activityInfo.applicationInfo;
                if ((info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                        && SAFE_NAME.matcher(info.packageName).matches()) {
                    found.add(info.packageName);
                }
            }
        }
        return found;
    }

    /** Other apps with the same user id as {@code pkg}. */
    static Set<String> sharing(Context c, String pkg) {
        Set<String> same = new LinkedHashSet<>();
        try {
            int uid = c.getPackageManager().getApplicationInfo(pkg, 0).uid;
            String[] all = c.getPackageManager().getPackagesForUid(uid);
            if (all != null) for (String other : all) if (!other.equals(pkg)) same.add(other);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return same;
    }

    /** Why Fake or Off must not be applied to {@code pkg}'s {@code op}, in plain words; null when it is safe. */
    static String refuse(Context c, String pkg, String op) {
        int uid;
        try {
            uid = c.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return "Couldn't find " + pkg + ".";
        }
        if (uid < FIRST_APP_UID) {
            return "This is part of Android itself. Changing its permissions can break the phone, so TinyKit won't.";
        }
        Set<String> same = sharing(c, pkg);
        if (!same.isEmpty()) {
            String first = same.iterator().next();
            return "This app shares its permissions with " + first
                    + (same.size() > 1 ? " and " + (same.size() - 1) + " more" : "")
                    + ". Changing it would change them too, so TinyKit won't.";
        }
        if ((op.equals("CAMERA") || op.equals("RECORD_AUDIO")) && cameraApps(c).contains(pkg)) {
            return "This is your phone's camera app. Making its camera or microphone Fake or Off stops it from opening, so TinyKit won't.";
        }
        return null;
    }

    private static String modeOf(String text) {
        Matcher m = MODE.matcher(text);
        return m.find() ? m.group(1) : "default";
    }

    /** The mode to write to put something back to normal. ("default" is accepted by appops but changes nothing.) */
    static String restoreMode(String was) {
        return "default".equals(was) ? "allow" : was;
    }

    /**
     * Reads app-op modes in ONE shell round-trip. Each item is {package, op, "u"} for the user-id
     * level or {package, op, "p"} for the package level. Result keys are {@code package|op}.
     */
    static Map<String, String> read(List<String[]> items) {
        Map<String, String> out = new HashMap<>();
        if (items.isEmpty()) return out;
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            String[] it = items.get(i);
            cmd.append("echo \"").append(i).append("=$(appops get ").append(it[2].equals("u") ? "--uid " : "")
                    .append(Shell.quote(it[0])).append(' ').append(it[1]).append(" 2>/dev/null | head -1)\"; ");
        }
        for (String line : Shell.run(cmd.toString()).out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try {
                String[] it = items.get(Integer.parseInt(line.substring(0, eq)));
                out.put(it[0] + "|" + it[1], modeOf(line.substring(eq + 1)));
            } catch (NumberFormatException | IndexOutOfBoundsException ignored) {
            }
        }
        return out;
    }

    private static List<String[]> uidItems(Set<String> pkgs, String op) {
        List<String[]> items = new ArrayList<>();
        for (String pkg : pkgs) items.add(new String[]{pkg, op, "u"});
        return items;
    }

    private static boolean asks(PackageManager pm, String pkg, String permission) {
        try {
            String[] asked = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (asked != null) for (String a : asked) if (a.equals(permission)) return true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    /**
     * Applies Real, Fake or Off to a permission, then checks that no other app was changed with it.
     * Returns null on success, else a message (and nothing is left changed). Call off the main thread.
     */
    static String setPermission(Context c, String pkg, String op, String perm, String target) {
        if (!target.equals("Real")) {
            String why = refuse(c, pkg, op);
            if (why != null) return why;
        }
        PackageManager pm = c.getPackageManager();
        String q = Shell.quote(pkg);
        // Everything that must not change: the camera apps and anything sharing this app's user id.
        Set<String> watch = new LinkedHashSet<>(sharing(c, pkg));
        if (op.equals("CAMERA") || op.equals("RECORD_AUDIO")) watch.addAll(cameraApps(c));
        watch.remove(pkg);
        Map<String, String> before = read(uidItems(watch, op));
        // This app's own state, to go back to if something else turns out to be hit.
        String selfWas = read(uidItems(new HashSet<>(java.util.Collections.singleton(pkg)), op))
                .getOrDefault(pkg + "|" + op, "default");
        boolean grantedWas = pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED;

        String command;
        switch (target) {
            case "Real":
                command = "pm grant " + q + " " + perm + " 2>/dev/null; appops set --uid " + q + " " + op + " allow";
                break;
            case "Fake":
                command = "pm grant " + q + " " + perm + " 2>/dev/null; appops set --uid " + q + " " + op + " ignore";
                break;
            default:
                command = "pm revoke " + q + " " + perm + " 2>/dev/null; appops set --uid " + q + " " + op + " ignore";
        }
        Shell.Result r = Shell.run(command);
        if (!r.ok()) return r.out.isEmpty() ? "Failed." : r.out;

        Map<String, String> after = read(uidItems(watch, op));
        List<String> hurt = new ArrayList<>();
        StringBuilder undo = new StringBuilder();
        for (String other : watch) {
            String was = before.getOrDefault(other + "|" + op, "default");
            String now = after.getOrDefault(other + "|" + op, "default");
            // Only a change to ignore or deny counts as damage; "allow" does not stop anything.
            if (!was.equals(now) && ("ignore".equals(now) || "deny".equals(now))) {
                hurt.add(other);
                undo.append("appops set --uid ").append(Shell.quote(other)).append(' ').append(op).append(' ')
                        .append(restoreMode(was)).append("; ");
            }
        }
        if (hurt.isEmpty()) return null;
        // Put the others back and take this change back too.
        undo.append("appops set --uid ").append(q).append(' ').append(op).append(' ').append(restoreMode(selfWas)).append("; ")
                .append(grantedWas ? "pm grant " : "pm revoke ").append(q).append(' ').append(perm).append(" 2>/dev/null; ");
        Shell.run(undo.toString());
        return "Not applied: it also changed " + hurt.get(0) + (hurt.size() > 1 ? " and " + (hurt.size() - 1) + " more" : "")
                + ", which you didn't pick. Everything was put back.";
    }

    /**
     * Fixes damage from Fake on apps the user did not choose. A permission that is granted yet
     * ignored is what Fake looks like, and Android never produces it on its own, so on the phone's
     * camera apps, on apps sharing a user id with an app TinyKit manages, and on saved choices that
     * can't be safe, it is put back to normal. Apps the user chose Fake, and that are safe, are never
     * touched. Returns a message when something was repaired (or could not be), else null.
     * Call off the main thread, with Shizuku running.
     */
    static String repair(Context c, SharedPreferences p) {
        if (!Shell.ready()) return null;
        // Nothing to repair if TinyKit never changed a permission (keeps every start cheap).
        if (!FakePolicy.everUsed(p) && !p.getBoolean(PREF_REPAIR_PENDING, false)) return null;
        PackageManager pm = c.getPackageManager();
        Set<String> fakeChosen = FakePolicy.all(p);
        Set<String> cameras = cameraApps(c);
        Set<String> candidates = new LinkedHashSet<>(cameras);
        for (String entry : FakePolicy.everyEntry(p)) {
            String[] f = entry.split("\\|", -1);
            if (f.length != 3 || !SAFE_NAME.matcher(f[0]).matches()) continue;
            Set<String> same = sharing(c, f[0]);
            candidates.addAll(same);
            if (!same.isEmpty()) candidates.add(f[0]);
        }
        // One read for every candidate and every op.
        List<String[]> items = new ArrayList<>();
        List<String[]> pairs = new ArrayList<>(); // {pkg, opIndex}
        for (String pkg : candidates) {
            if (!SAFE_NAME.matcher(pkg).matches()) continue;
            for (int i = 0; i < OPS.length; i++) {
                if (!asks(pm, pkg, OPS[i][1])) continue;
                if (pm.checkPermission(OPS[i][1], pkg) != PackageManager.PERMISSION_GRANTED) continue;
                items.add(new String[]{pkg, OPS[i][0], "u"});
                pairs.add(new String[]{pkg, String.valueOf(i)});
            }
        }
        Map<String, String> now = read(items);
        android.util.Log.i("TinyKit", "permissions: repair checked " + items.size() + " of " + candidates.size()
                + " apps, entries=" + FakePolicy.everyEntry(p).size());
        StringBuilder fix = new StringBuilder();
        List<String[]> toFix = new ArrayList<>();
        for (String[] pair : pairs) {
            String[] op = OPS[Integer.parseInt(pair[1])];
            if (!"ignore".equals(now.get(pair[0] + "|" + op[0]))) continue;
            boolean chosen = fakeChosen.contains(FakePolicy.key(pair[0], op[0], op[1]));
            // A Fake the user chose stays, unless it can't be safe (shared id, system, camera app).
            if (chosen && refuse(c, pair[0], op[0]) == null) continue;
            fix.append("appops set --uid ").append(Shell.quote(pair[0])).append(' ').append(op[0]).append(" allow; ");
            toFix.add(new String[]{pair[0], op[0], op[1]});
        }
        android.util.Log.i("TinyKit", "permissions: repair needs " + toFix.size() + " fixes");
        if (toFix.isEmpty()) {
            p.edit().putBoolean(PREF_REPAIR_PENDING, false).apply();
            return null;
        }
        Shell.run(fix.toString());
        // Read everything back: only what really changed counts as repaired.
        List<String[]> check = new ArrayList<>();
        for (String[] f : toFix) check.add(new String[]{f[0], f[1], "u"});
        Map<String, String> after = read(check);
        Set<String> okApps = new LinkedHashSet<>();
        Set<String> badApps = new LinkedHashSet<>();
        Set<String> keep = FakePolicy.all(p);
        boolean changedList = false;
        for (String[] f : toFix) {
            if ("ignore".equals(after.get(f[0] + "|" + f[1]))) {
                badApps.add(f[0]);
            } else {
                okApps.add(f[0]);
                changedList |= keep.remove(FakePolicy.key(f[0], f[1], f[2]));
            }
        }
        if (changedList) p.edit().putStringSet(FakePolicy.KEY, keep).apply();
        p.edit().putBoolean(PREF_REPAIR_PENDING, !badApps.isEmpty()).apply();
        StringBuilder message = new StringBuilder();
        if (!okApps.isEmpty()) {
            message.append("Repaired permissions that Fake had left changed on ").append(okApps.size())
                    .append(okApps.size() == 1 ? " app" : " apps").append(" (").append(okApps.iterator().next())
                    .append(okApps.size() > 1 ? " and others" : "").append(").");
        }
        if (!badApps.isEmpty()) {
            if (message.length() > 0) message.append(' ');
            message.append("Couldn't repair ").append(badApps.iterator().next())
                    .append(badApps.size() > 1 ? " and others" : "").append("; TinyKit will try again.");
        }
        return message.toString();
    }
}
