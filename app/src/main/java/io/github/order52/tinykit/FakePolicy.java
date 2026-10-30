package io.github.order52.tinykit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps Fake permissions Fake.
 *
 * Fake is "permission granted, app-op ignore". Android keeps that only until it next syncs its
 * permissions with its app-ops: a restart, an update of the app, or the app asking through the
 * system dialog all turn it back into Real. So TinyKit remembers what you made Fake and, whenever
 * Shizuku is running and TinyKit gets to run, checks them and puts back any that changed.
 *
 * If one can't be put back, it's switched Off, never left Real.
 */
final class FakePolicy {
    static final String KEY = "fake_ops";
    private static final Pattern OP = Pattern.compile("[A-Z_]+");
    private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9_.]*");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_.]+");

    private FakePolicy() {
    }

    /** One saved choice: an app, an app-op and, for a permission, the permission ("" for a hidden op). */
    static String key(String pkg, String op, String permission) {
        return pkg + "|" + op + "|" + permission;
    }

    static Set<String> all(SharedPreferences p) {
        return new HashSet<>(p.getStringSet(KEY, new HashSet<>()));
    }

    /** Whether any permission of {@code pkg} is saved as Fake. */
    static boolean has(SharedPreferences p, String pkg) {
        for (String entry : all(p)) if (entry.startsWith(pkg + "|")) return true;
        return false;
    }

    /** Remembers (or forgets) that {@code op} is Fake for {@code pkg}. */
    static void set(SharedPreferences p, String pkg, String op, String permission, boolean fake) {
        Set<String> set = all(p);
        if (fake) set.add(key(pkg, op, permission)); else set.remove(key(pkg, op, permission));
        p.edit().putStringSet(KEY, set).apply();
    }

    /**
     * Checks every saved Fake and fixes the ones Android changed. Returns a message to show when
     * something had to be fixed, else null. Call off the main thread.
     */
    static synchronized String enforce(Context c, SharedPreferences p) {
        if (!Shell.ready()) return null;
        PackageManager pm = c.getPackageManager();
        Set<String> saved = all(p);
        List<String[]> list = new ArrayList<>(); // {pkg, op, permission}
        for (String entry : saved) {
            String[] f = entry.split("\\|", -1);
            boolean valid = f.length == 3 && PACKAGE.matcher(f[0]).matches() && OP.matcher(f[1]).matches()
                    && PERMISSION.matcher(f[2]).matches();
            try {
                if (!valid) throw new PackageManager.NameNotFoundException();
                pm.getApplicationInfo(f[0], 0);
                list.add(f);
            } catch (PackageManager.NameNotFoundException gone) {
                saved.remove(entry); // uninstalled
            }
        }
        if (saved.size() != all(p).size()) p.edit().putStringSet(KEY, saved).apply();
        List<String[]> broken = broken(pm, list);
        if (broken.isEmpty()) return null;

        StringBuilder fix = new StringBuilder();
        for (String[] f : broken) {
            String q = Shell.quote(f[0]);
            if (f[2].isEmpty()) {
                fix.append("appops set ").append(q).append(' ').append(f[1]).append(" ignore; ");
            } else {
                fix.append("pm grant ").append(q).append(' ').append(f[2]).append(" 2>/dev/null; ")
                        .append("appops set --uid ").append(q).append(' ').append(f[1]).append(" ignore; ");
            }
        }
        Shell.run(fix.toString());

        // Anything still not Fake is switched Off rather than left Real.
        List<String[]> failed = broken(pm, broken);
        if (!failed.isEmpty()) {
            StringBuilder close = new StringBuilder();
            Set<String> keep = all(p);
            for (String[] f : failed) {
                String q = Shell.quote(f[0]);
                if (f[2].isEmpty()) {
                    close.append("appops set ").append(q).append(' ').append(f[1]).append(" deny; ");
                } else {
                    close.append("pm revoke ").append(q).append(' ').append(f[2]).append(" 2>/dev/null; ")
                            .append("appops set --uid ").append(q).append(' ').append(f[1]).append(" ignore; ");
                }
                keep.remove(key(f[0], f[1], f[2]));
            }
            Shell.run(close.toString());
            p.edit().putStringSet(KEY, keep).apply();
        }
        int fixed = broken.size() - failed.size();
        String message = fixed > 0 ? "Android had reset " + fixed + " Fake permission" + (fixed == 1 ? "" : "s")
                + ". TinyKit put " + (fixed == 1 ? "it" : "them") + " back." : "";
        if (!failed.isEmpty()) {
            message += (message.isEmpty() ? "" : " ") + failed.size() + " couldn't be kept Fake and "
                    + (failed.size() == 1 ? "was" : "were") + " switched Off.";
        }
        return message;
    }

    /** The entries that are not Fake right now: the app-op isn't ignore, or the permission isn't granted. */
    private static List<String[]> broken(PackageManager pm, List<String[]> list) {
        List<String[]> out = new ArrayList<>();
        if (list.isEmpty()) return out;
        StringBuilder read = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            String[] f = list.get(i);
            read.append("echo \"").append(i).append("=$(appops get ").append(f[2].isEmpty() ? "" : "--uid ")
                    .append(Shell.quote(f[0])).append(' ').append(f[1]).append(" 2>/dev/null | head -1)\"; ");
        }
        Map<Integer, String> modes = new HashMap<>();
        for (String line : Shell.run(read.toString()).out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                try {
                    modes.put(Integer.parseInt(line.substring(0, eq)), line.substring(eq + 1));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (int i = 0; i < list.size(); i++) {
            String[] f = list.get(i);
            boolean ignoring = modes.getOrDefault(i, "").contains("ignore");
            boolean granted = f[2].isEmpty() || pm.checkPermission(f[2], f[0]) == PackageManager.PERMISSION_GRANTED;
            if (!ignoring || !granted) out.add(f);
        }
        return out;
    }
}
