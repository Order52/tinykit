package io.github.order52.tinykit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps every choice made in Real / Fake / Off exactly as the user left it.
 *
 * - Real: permission granted, app-op allow.
 * - Fake: permission granted, app-op ignore.
 * - Off:  permission revoked (or app-op deny for the ones without a permission).
 *
 * Android keeps these only until it next syncs its permissions with its app-ops: a restart, an
 * update of the app, or the app asking through the system dialog can all turn them back. So TinyKit
 * remembers what the user chose and, whenever Shizuku is running and TinyKit gets to run, checks
 * each choice and puts back any that changed, until the user changes it themselves.
 *
 * If a Fake can't be put back it is switched Off, never left Real. A choice that could change other
 * apps (see {@link PermGuard#refuse}) is dropped rather than applied.
 */
final class FakePolicy {
    /** Saved Fake choices. Entries look like {@code package|OP|permission}. */
    static final String KEY = "fake_ops";
    static final String REAL_KEY = "real_ops";
    static final String OFF_KEY = "off_ops";
    /** Set once the user has ever made a choice, so the safety net knows there is something to guard. */
    private static final String USED = "perm_used";

    private static final String FAKE = "Fake";
    private static final String REAL = "Real";
    private static final String OFF = "Off";

    private static final Pattern OP = Pattern.compile("[A-Z_]+");
    private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_.]*");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_.]+");

    private FakePolicy() {
    }

    /** One saved choice: an app, an app-op and, for a permission, the permission ("" for a hidden op). */
    static String key(String pkg, String op, String permission) {
        return pkg + "|" + op + "|" + permission;
    }

    private static String prefKey(String state) {
        return state.equals(FAKE) ? KEY : state.equals(REAL) ? REAL_KEY : OFF_KEY;
    }

    private static Set<String> set(SharedPreferences p, String state) {
        return new HashSet<>(p.getStringSet(prefKey(state), new HashSet<>()));
    }

    /** The saved Fake choices. */
    static Set<String> all(SharedPreferences p) {
        return set(p, FAKE);
    }

    /** Every saved choice, whatever its state. */
    static Set<String> everyEntry(SharedPreferences p) {
        Set<String> every = set(p, FAKE);
        every.addAll(set(p, REAL));
        every.addAll(set(p, OFF));
        return every;
    }

    /** The saved choices of one state ("Real", "Fake" or "Off"). */
    static Set<String> entries(SharedPreferences p, String state) {
        return set(p, state);
    }

    /** Forgets every saved choice. */
    static void clearAll(SharedPreferences p) {
        p.edit().putStringSet(KEY, new HashSet<>()).putStringSet(REAL_KEY, new HashSet<>())
                .putStringSet(OFF_KEY, new HashSet<>()).putBoolean(USED, false).apply();
    }

    static boolean everUsed(SharedPreferences p) {
        return p.getBoolean(USED, false) || !everyEntry(p).isEmpty();
    }

    /** Whether any permission of {@code pkg} has a saved choice (Android resets those on an update). */
    static boolean has(SharedPreferences p, String pkg) {
        for (String entry : everyEntry(p)) if (entry.startsWith(pkg + "|")) return true;
        return false;
    }

    /** Remembers the user's choice of {@code state} (Real, Fake or Off) for {@code op} of {@code pkg}. */
    static void choose(SharedPreferences p, String pkg, String op, String permission, String state) {
        String entry = key(pkg, op, permission);
        SharedPreferences.Editor edit = p.edit().putBoolean(USED, true);
        for (String s : new String[]{FAKE, REAL, OFF}) {
            Set<String> set = set(p, s);
            if (s.equals(state)) set.add(entry); else set.remove(entry);
            edit.putStringSet(prefKey(s), set);
        }
        edit.apply();
    }

    /** Adds Fake choices from a backup. */
    static void addFakes(SharedPreferences p, Set<String> fakes) {
        for (String entry : fakes) {
            String[] f = entry.split("\\|", -1);
            if (f.length == 3) choose(p, f[0], f[1], f[2], FAKE);
        }
    }

    private static final class Entry {
        final String pkg, op, permission, state;

        Entry(String[] f, String state) {
            this.pkg = f[0];
            this.op = f[1];
            this.permission = f[2];
            this.state = state;
        }

        String key() {
            return FakePolicy.key(pkg, op, permission);
        }

        /** True when the app-op lives at the user-id level (permissions) rather than per package. */
        boolean uidLevel() {
            return !permission.isEmpty();
        }
    }

    /**
     * Checks every saved choice and fixes the ones Android changed, then lets {@link PermGuard} repair
     * damage on apps the user never chose. Returns a message to show when something was fixed, else
     * null. Call off the main thread.
     */
    static synchronized String enforce(Context c, SharedPreferences p) {
        if (!Shell.ready()) {
            android.util.Log.i("TinyKit", "permissions: skipped, Shizuku not ready");
            return null;
        }
        // Repair first: it needs the unsafe saved choices (shared user id, camera app) to find the
        // apps they hit, and enforceSaved then drops those choices.
        String repaired = PermGuard.repair(c, p);
        String fixed = enforceSaved(c, p);
        if (repaired == null) return fixed;
        return fixed == null || fixed.isEmpty() ? repaired : repaired + " " + fixed;
    }

    private static String enforceSaved(Context c, SharedPreferences p) {
        PackageManager pm = c.getPackageManager();
        List<Entry> list = new ArrayList<>();
        Set<String> dropped = new HashSet<>();
        for (String state : new String[]{FAKE, REAL, OFF}) {
            for (String entry : set(p, state)) {
                String[] f = entry.split("\\|", -1);
                boolean valid = f.length == 3 && PACKAGE.matcher(f[0]).matches() && OP.matcher(f[1]).matches()
                        && PERMISSION.matcher(f[2]).matches();
                if (!valid) {
                    dropped.add(state + "|" + entry);
                    continue;
                }
                try {
                    pm.getApplicationInfo(f[0], 0);
                } catch (PackageManager.NameNotFoundException gone) {
                    dropped.add(state + "|" + entry); // uninstalled
                    continue;
                }
                // Never keep a Fake or Off that could change other apps or stop the camera;
                // PermGuard.repair puts anything it already changed back to normal.
                if (!state.equals(REAL) && !f[2].isEmpty() && PermGuard.refuse(c, f[0], f[1]) != null) {
                    dropped.add(state + "|" + entry);
                    continue;
                }
                list.add(new Entry(f, state));
            }
        }
        if (!dropped.isEmpty()) forget(p, dropped);
        List<Entry> broken = broken(pm, list);
        if (broken.isEmpty()) return null;

        StringBuilder fix = new StringBuilder();
        for (Entry e : broken) fix.append(command(e, e.state));
        Shell.run(fix.toString());

        // A Fake that still isn't Fake is switched Off rather than left Real. (A Real or Off that
        // won't stick has nothing safer to fall back to, so it is kept and tried again next time.)
        List<Entry> failed = broken(pm, broken);
        List<Entry> failedFakes = new ArrayList<>();
        for (Entry e : failed) if (e.state.equals(FAKE)) failedFakes.add(e);
        if (!failedFakes.isEmpty()) {
            StringBuilder close = new StringBuilder();
            for (Entry e : failedFakes) {
                close.append(command(e, OFF));
                choose(p, e.pkg, e.op, e.permission, OFF);
            }
            Shell.run(close.toString());
        }
        int fixed = broken.size() - failed.size();
        String message = fixed > 0 ? "Android had reset " + fixed + " permission choice" + (fixed == 1 ? "" : "s")
                + ". TinyKit put " + (fixed == 1 ? "it" : "them") + " back." : "";
        if (!failedFakes.isEmpty()) {
            message += (message.isEmpty() ? "" : " ") + failedFakes.size() + " couldn't be kept Fake and "
                    + (failedFakes.size() == 1 ? "was" : "were") + " switched Off.";
        }
        return message;
    }

    private static void forget(SharedPreferences p, Set<String> stateAndEntry) {
        SharedPreferences.Editor edit = p.edit();
        for (String s : new String[]{FAKE, REAL, OFF}) {
            Set<String> set = set(p, s);
            for (String x : stateAndEntry) if (x.startsWith(s + "|")) set.remove(x.substring(s.length() + 1));
            edit.putStringSet(prefKey(s), set);
        }
        edit.apply();
    }

    /** The shell commands that make {@code e} be in {@code state}. */
    private static String command(Entry e, String state) {
        String q = Shell.quote(e.pkg);
        if (e.uidLevel()) {
            String grant = state.equals(OFF) ? "pm revoke " : "pm grant ";
            String mode = state.equals(REAL) ? "allow" : "ignore";
            return grant + q + ' ' + e.permission + " 2>/dev/null; appops set --uid " + q + ' ' + e.op + ' ' + mode + "; ";
        }
        String mode = state.equals(REAL) ? "allow" : state.equals(FAKE) ? "ignore" : "deny";
        return "appops set " + q + ' ' + e.op + ' ' + mode + "; ";
    }

    /** The entries that are not in their saved state right now. One shell read for all of them. */
    private static List<Entry> broken(PackageManager pm, List<Entry> list) {
        List<Entry> out = new ArrayList<>();
        if (list.isEmpty()) return out;
        List<String[]> items = new ArrayList<>();
        for (Entry e : list) items.add(new String[]{e.pkg, e.op, e.uidLevel() ? "u" : "p"});
        Map<String, String> modes = PermGuard.read(items);
        for (Entry e : list) {
            String mode = modes.getOrDefault(e.pkg + "|" + e.op, "default");
            boolean ignoring = mode.equals("ignore");
            boolean blocked = ignoring || mode.equals("deny");
            boolean granted = !e.uidLevel() || pm.checkPermission(e.permission, e.pkg) == PackageManager.PERMISSION_GRANTED;
            // An app that never asks for the permission can't be granted it: nothing to keep.
            if (e.uidLevel() && !e.state.equals(OFF) && !asks(pm, e.pkg, e.permission)) continue;
            boolean ok;
            switch (e.state) {
                case FAKE:
                    ok = ignoring && granted;
                    break;
                case REAL:
                    ok = granted && !blocked;
                    break;
                default:
                    ok = e.uidLevel() ? !granted : mode.equals("deny");
            }
            if (!ok) out.add(e);
        }
        return out;
    }

    private static boolean asks(PackageManager pm, String pkg, String permission) {
        try {
            String[] asked = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions;
            if (asked != null) for (String a : asked) if (a.equals(permission)) return true;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }
}
