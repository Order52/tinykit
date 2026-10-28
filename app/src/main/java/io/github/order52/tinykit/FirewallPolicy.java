package io.github.order52.tinykit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the internet firewall should look like, kept in TinyKit's preferences, and the code that
 * pushes it into Android. The kernel forgets the rules at every restart, so {@link Restore} calls
 * {@link #apply} again as soon as Shizuku is back.
 *
 * Two modes: block the apps in {@code fw_blocked}, or (allow mode) block every app except those in
 * {@code fw_allowed}.
 */
final class FirewallPolicy {
    static final String BLOCKED = "fw_blocked";
    static final String ALLOWED = "fw_allowed";
    static final String MODE = "fw_allow_mode";
    // Lower ids are Android itself, which can't be blocked on its own.
    private static final int FIRST_APP_UID = 10000;

    private FirewallPolicy() {
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 34;
    }

    static boolean allowMode(SharedPreferences p) {
        return p.getBoolean(MODE, false);
    }

    static Set<String> saved(SharedPreferences p, String key) {
        return new HashSet<>(p.getStringSet(key, Collections.emptySet()));
    }

    /** Package name to user id for every installed app, straight from Android with no shell call. */
    static Map<String, Integer> uids(Context c) {
        Map<String, Integer> ids = new HashMap<>();
        for (ApplicationInfo info : c.getPackageManager().getInstalledApplications(0)) {
            ids.put(info.packageName, info.uid);
        }
        return ids;
    }

    static String command(Context c, String args) {
        return "CLASSPATH=" + c.getPackageCodePath()
                + " app_process /system/bin io.github.order52.tinykit.Firewall " + args;
    }

    /** How many apps the current choice stops from using the internet. */
    static int blockedCount(Context c, SharedPreferences p) {
        Map<String, Integer> ids = uids(c);
        Set<Integer> blocked = new HashSet<>();
        for (int uid : desired(c, ids, p)[0]) blocked.add(uid);
        int n = 0;
        for (Map.Entry<String, Integer> e : ids.entrySet()) if (blocked.contains(e.getValue())) n++;
        return n;
    }

    /** {uids to block, uids to leave open}: the second list is what a full reset sets back to allowed. */
    private static List<Integer>[] desired(Context c, Map<String, Integer> ids, SharedPreferences p) {
        boolean allow = allowMode(p);
        Set<String> chosen = saved(p, allow ? ALLOWED : BLOCKED);
        // TinyKit stays reachable in allow mode, so its update check keeps working.
        if (allow) chosen.add(c.getPackageName());
        Set<Integer> chosenUids = new HashSet<>();
        for (String pkg : chosen) {
            Integer uid = ids.get(pkg);
            if (uid != null) chosenUids.add(uid);
        }
        List<Integer> block = new ArrayList<>();
        List<Integer> open = new ArrayList<>();
        for (int uid : new HashSet<>(ids.values())) {
            if (uid < FIRST_APP_UID) continue;
            // Normal mode blocks the chosen ids; allow mode blocks every id that isn't chosen, so an
            // app sharing an id with an allowed one stays allowed.
            if (allow != chosenUids.contains(uid)) block.add(uid); else open.add(uid);
        }
        @SuppressWarnings("unchecked")
        List<Integer>[] result = new List[]{block, open};
        return result;
    }

    /**
     * Makes Android match the saved choice in one run of {@link Firewall}. With {@code reset} every
     * other app is also set back to allowed (needed after the choice changed); after a restart the
     * rules are already empty, so that part is skipped. Returns null on success, else the error.
     * Call off the main thread.
     */
    static String apply(Context c, SharedPreferences p, boolean reset) {
        if (!supported()) return "Blocking an app's internet needs Android 14 or newer.";
        List<Integer>[] sets = desired(c, uids(c), p);
        if (sets[0].isEmpty() && !reset) return null;
        StringBuilder args = new StringBuilder("sync");
        for (int uid : sets[0]) args.append(' ').append(uid);
        if (reset) {
            args.append(" --");
            for (int uid : sets[1]) args.append(' ').append(uid);
        }
        Shell.Result r = Shell.run(command(c, args.toString()));
        return r.ok() ? null : (r.out.isEmpty() ? "Failed." : r.out);
    }

    /** Remembers a per-app choice and applies it. Returns null on success, else the error. */
    static String choose(Context c, SharedPreferences p, String pkg, boolean checked) {
        Integer uid = uids(c).get(pkg);
        if (uid == null) return "Couldn't find " + pkg + ".";
        if (uid < FIRST_APP_UID) return "This is part of Android itself, so it can't be blocked on its own.";
        boolean allow = allowMode(p);
        // Checked means blocked in the normal mode and allowed in allow mode.
        String error = error(Shell.run(command(c, ((checked != allow) ? "block " : "allow ") + uid)));
        if (error != null) return error;
        String key = allow ? ALLOWED : BLOCKED;
        Set<String> set = saved(p, key);
        if (checked) set.add(pkg); else set.remove(pkg);
        p.edit().putStringSet(key, set).apply();
        return null;
    }

    /** Apps that share {@code pkg}'s user id: Android's rule covers all of them at once. */
    static List<String> sharing(Context c, String pkg) {
        Map<String, Integer> ids = uids(c);
        List<String> same = new ArrayList<>();
        Integer uid = ids.get(pkg);
        if (uid == null) return same;
        for (Map.Entry<String, Integer> e : ids.entrySet()) {
            if (e.getValue().equals(uid) && !e.getKey().equals(pkg)) same.add(e.getKey());
        }
        return same;
    }

    private static String error(Shell.Result r) {
        return r.ok() ? null : (r.out.isEmpty() ? "Failed." : r.out);
    }
}
