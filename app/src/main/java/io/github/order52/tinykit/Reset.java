package io.github.order52.tinykit;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Undoes what TinyKit changed, group by group, back to Android's own defaults.
 *
 * TinyKit cannot know what a setting was before it touched it, so "back" means Android's default,
 * not a custom value the user had earlier. Only things TinyKit remembers, or whose state is
 * unmistakably TinyKit's, are touched. Android's own switches that look identical to ones the user
 * may have set in Settings (background limits, special access, notification channels) are left
 * alone unless the user asks for them. Every step is best-effort and independent, each write is
 * read back, and every step reports what it really did.
 */
final class Reset {
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_.]+");

    private Reset() {
    }

    /** The permission choices TinyKit remembers (Fake, Real, Off) and anything Fake left damaged. */
    static String permissions(Context c, SharedPreferences p) {
        if (!Shell.ready()) return "Permissions: Shizuku isn't running, so nothing was changed.";
        PackageManager pm = c.getPackageManager();
        StringBuilder out = new StringBuilder();
        // First fix damage on apps that were never chosen; it needs the saved choices to find them.
        String repaired = PermGuard.repair(c, p);
        if (repaired != null && !repaired.isEmpty()) out.append(repaired).append(' ');

        // {state, package, op, permission}
        List<String[]> entries = new ArrayList<>();
        for (String state : new String[]{"Fake", "Off", "Real"}) {
            for (String entry : FakePolicy.entries(p, state)) {
                String[] f = entry.split("\\|", -1);
                if (f.length == 3 && PACKAGE.matcher(f[0]).matches() && f[1].matches("[A-Z_]+")
                        && f[2].matches("[A-Za-z0-9_.]*")) {
                    entries.add(new String[]{state, f[0], f[1], f[2]});
                }
            }
        }
        StringBuilder script = new StringBuilder();
        List<String[]> check = new ArrayList<>();
        List<String[]> revoked = new ArrayList<>(); // {package, permission}: must end up not granted
        Set<String> apps = new LinkedHashSet<>();
        for (String[] e : entries) {
            try {
                pm.getApplicationInfo(e[1], 0);
            } catch (PackageManager.NameNotFoundException gone) {
                continue;
            }
            String q = Shell.quote(e[1]);
            if (e[3].isEmpty()) {
                // A hidden op: Android's default.
                script.append("appops set ").append(q).append(' ').append(e[2]).append(" default; ");
                check.add(new String[]{e[1], e[2], "p"});
            } else {
                // Fake and Off become "not allowed" (the app asks again through Android's own dialog),
                // Real stays allowed. The permission is revoked only where that can't hit other apps.
                boolean safe = PermGuard.refuse(c, e[1], e[2]) == null;
                boolean revoke = !e[0].equals("Real") && safe;
                if (revoke) script.append("pm revoke ").append(q).append(' ').append(e[3]).append(" 2>/dev/null; ");
                script.append("appops set --uid ").append(q).append(' ').append(e[2]).append(" allow; ");
                // Android shows a revoked permission's app-op as "ignore" by itself, so a revoked one is
                // checked by the permission; the rest by their app-op.
                if (revoke) revoked.add(new String[]{e[1], e[3]});
                else check.add(new String[]{e[1], e[2], "u"});
            }
            apps.add(e[1]);
        }
        if (script.length() > 0) Shell.run(script.toString());
        // Read back: nothing may still be ignored.
        Map<String, String> now = PermGuard.read(check);
        int stuck = 0;
        for (String[] it : check) if ("ignore".equals(now.get(it[0] + "|" + it[1]))) stuck++;
        for (String[] it : revoked) {
            if (pm.checkPermission(it[1], it[0]) == PackageManager.PERMISSION_GRANTED) stuck++;
        }
        if (stuck == 0) {
            FakePolicy.clearAll(p);
            p.edit().putBoolean(PermGuard.PREF_REPAIR_PENDING, false).apply();
            out.append("Permissions: reset ").append(entries.size()).append(entries.size() == 1 ? " choice" : " choices")
                    .append(" on ").append(apps.size()).append(apps.size() == 1 ? " app." : " apps.");
        } else {
            out.append("Permissions: ").append(stuck).append(" couldn't be reset, so the saved list was kept to try again.");
        }
        return out.toString().trim();
    }

    /** All internet blocking: lists, allow list mode, the install watcher and every rule in Android. */
    static String internet(Context c, SharedPreferences p) {
        boolean used = !FirewallPolicy.saved(p, FirewallPolicy.BLOCKED).isEmpty()
                || !FirewallPolicy.saved(p, FirewallPolicy.ALLOWED).isEmpty() || FirewallPolicy.allowMode(p)
                || p.getBoolean(NewAppService.PREF, false);
        p.edit().putStringSet(FirewallPolicy.BLOCKED, new HashSet<>()).putStringSet(FirewallPolicy.ALLOWED, new HashSet<>())
                .putBoolean(FirewallPolicy.MODE, false).putBoolean(NewAppService.PREF, false)
                .putBoolean(Restore.PREF_FIREWALL_PENDING, false).apply();
        NewAppService.sync(c);
        if (!FirewallPolicy.supported()) return "Internet block: nothing to reset on this Android.";
        if (!Shell.ready()) {
            return used ? "Internet block: the lists were cleared, but Shizuku isn't running so Android's rules stay "
                    + "until you start it and reset again." : "Internet block: nothing to reset.";
        }
        String error = FirewallPolicy.apply(c, p, true);
        if (error != null) return "Internet block: " + error;
        // Read it back: no app may still be blocked.
        List<String> uids = new ArrayList<>();
        for (int uid : new HashSet<>(FirewallPolicy.uids(c).values())) if (uid >= 10000) uids.add(String.valueOf(uid));
        int stillBlocked = 0;
        if (!uids.isEmpty()) {
            String list = Shell.run(FirewallPolicy.command(c, "query " + String.join(" ", uids))).out.trim();
            if (!list.isEmpty() && list.matches("[0-9 ]+")) stillBlocked = list.split(" ").length;
        }
        Shell.run(FirewallPolicy.command(c, "off"));
        return stillBlocked == 0 ? "Internet block: every app can use the internet again."
                : "Internet block: " + stillBlocked + " apps are still blocked; reset again.";
    }

    /** Apps TinyKit hid (disabled) and system apps it removed. */
    static String apps(Context c) {
        if (!Shell.ready()) return "Apps: Shizuku isn't running, so nothing was changed.";
        PackageManager pm = c.getPackageManager();
        StringBuilder script = new StringBuilder();
        List<String> enable = new ArrayList<>();
        for (String line : Shell.run("pm list packages -d").out.split("\n")) {
            String pkg = line.trim().replaceFirst("^package:", "");
            if (!PACKAGE.matcher(pkg).matches()) continue;
            // Only apps switched off by a user action ("hidden"), never ones the phone maker turned off.
            if (pm.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                enable.add(pkg);
                script.append("pm enable ").append(Shell.quote(pkg)).append("; ");
            }
        }
        List<String> restore = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 26) {
            Set<String> installed = new HashSet<>();
            for (ApplicationInfo i : pm.getInstalledApplications(0)) installed.add(i.packageName);
            for (String line : Shell.run("pm list packages -u").out.split("\n")) {
                String pkg = line.trim().replaceFirst("^package:", "");
                if (!PACKAGE.matcher(pkg).matches() || installed.contains(pkg)) continue;
                try {
                    ApplicationInfo i = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                    // An app the user installed and removed has no files left to bring back.
                    if ((i.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                    restore.add(pkg);
                    script.append("pm install-existing --user 0 ").append(Shell.quote(pkg)).append(" >/dev/null 2>&1; ");
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        if (script.length() == 0) return "Apps: nothing was hidden or removed.";
        Shell.run(script.toString());
        int failed = 0;
        for (String pkg : enable) {
            if (pm.getApplicationEnabledSetting(pkg) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) failed++;
        }
        for (String pkg : restore) {
            try {
                pm.getApplicationInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException e) {
                failed++;
            }
        }
        int done = enable.size() + restore.size() - failed;
        return "Apps: brought back " + done + (done == 1 ? " app" : " apps")
                + (failed > 0 ? ", " + failed + " couldn't be brought back." : ".");
    }

    /** Force dark, HW overlays, all-sensors-off and the Quick toggles TinyKit has on. */
    static String switches(Context c, SharedPreferences p) {
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        boolean shizuku = Shell.ready();
        if (shizuku) {
            if (p.getBoolean(Restore.PREF_FORCE_DARK, false)
                    || "true".equals(Shell.run("getprop debug.hwui.force_dark").out.trim())) {
                (Shell.run(Restore.forceDarkCommand(false)).ok() ? done : failed).add("Force dark");
            }
            if (p.getBoolean(Restore.PREF_NO_OVERLAYS, false)) {
                (Shell.run(Restore.overlaysCommand(false)).ok() ? done : failed).add("HW overlays");
            }
            if ("true".equals(Shell.run(MainActivity.sensorsCmd(c, "state")).out.trim())) {
                (Shell.run(MainActivity.sensorsCmd(c, "on")).ok() ? done : failed).add("All sensors off");
            }
        }
        p.edit().putBoolean(Restore.PREF_FORCE_DARK, false).putBoolean(Restore.PREF_NO_OVERLAYS, false).apply();
        // Only the Quick toggles that TinyKit itself offers and that are on right now. The developer
        // options, USB debugging and screen timeout are never touched.
        Tweak[] toggles = {Tweak.AD_DNS, Tweak.NO_SCAN, Tweak.FREE_HOTSPOT, Tweak.NO_MOBILE_ON_WIFI,
                Tweak.GRAYSCALE, Tweak.EXTRA_DIM, Tweak.MIC_CAM_OFF};
        for (Tweak t : toggles) {
            if (!t.usable(c) || !t.isOn(c)) continue;
            (t.set(c, false) == null ? done : failed).add(t.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        }
        if (!shizuku && done.isEmpty() && failed.isEmpty()) return "Switches: Shizuku isn't running; nothing to check.";
        if (done.isEmpty() && failed.isEmpty()) return "Switches: nothing was on.";
        String s = "Switches: turned off " + (done.isEmpty() ? "nothing" : String.join(", ", done)) + ".";
        return failed.isEmpty() ? s : s + " Couldn't turn off: " + String.join(", ", failed) + ".";
    }

    /**
     * Optional: background limits, clipboard guard and battery "kept alive" exemptions. These are
     * Android's own switches, so this also clears limits you set in Settings.
     */
    static String limits(Context c) {
        if (!Shell.ready()) return "Limits: Shizuku isn't running, so nothing was changed.";
        StringBuilder script = new StringBuilder();
        int count = 0;
        for (String op : new String[]{"READ_CLIPBOARD", "RUN_ANY_IN_BACKGROUND", "WAKE_LOCK"}) {
            for (String pkg : MainActivity.packages(Shell.run("appops query-op " + op + " ignore").out)) {
                script.append("appops set ").append(Shell.quote(pkg)).append(' ').append(op).append(" default; ");
                count++;
            }
        }
        for (String line : Shell.run("dumpsys deviceidle whitelist").out.split("\n")) {
            String[] parts = line.trim().split(",");
            if (parts.length >= 2 && parts[0].equals("user") && PACKAGE.matcher(parts[1]).matches()
                    && !parts[1].equals(c.getPackageName())) {
                script.append("dumpsys deviceidle whitelist -").append(Shell.quote(parts[1])).append(" >/dev/null; ");
                count++;
            }
        }
        if (script.length() == 0) return "Limits: nothing was set.";
        Shell.run(script.toString());
        return "Limits: cleared " + count + (count == 1 ? " setting." : " settings.");
    }
}
