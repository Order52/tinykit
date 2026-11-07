package io.github.order52.tinykit;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.usage.StorageStats;
import android.appwidget.AppWidgetManager;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

import android.app.AlertDialog;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ResetScreen {
    private final MainActivity tk;

    ResetScreen(MainActivity tk) {
        this.tk = tk;
    }

    /** Asks what to reset, saves a backup, resets the chosen groups and shows exactly what happened. */
    void confirmResetEverything() {
        if (!Shell.ready()) {
            tk.toast(Tr.t("Start Shizuku and allow TinyKit first."));
            return;
        }
        String[] names = {
                "Internet block (allow list mode, blocked and allowed apps, pause)",
                "Permission choices (Fake, Real, Off) and any damage they caused",
                "Hidden apps and removed system apps (brought back, including apps you switched off in Settings)",
                "Force dark, HW overlays, sensors off, Shut Up, Run in the background, and the Quick toggles that are on",
                "Background limits, clipboard guard and kept-alive apps (these are Android's own settings, "
                        + "so this also clears limits you set in Settings)"};
        boolean[] on = {true, true, true, true, false};
        LinearLayout box = tk.vertical();
        box.setPadding(tk.dp(20), tk.dp(8), tk.dp(20), tk.dp(0));
        TextView info = tk.text("Puts what TinyKit changed back to Android's defaults. TinyKit can't know what a "
                + "setting was before it changed it, so this is Android's default, not a custom value you "
                + "had earlier. A backup is saved to Download/TinyKit first.\n\nNot touched: developer "
                + "options, USB debugging, screen timeout, notification channels and special access.", 14, false);
        info.setPadding(0, tk.dp(8), 0, tk.dp(8));
        box.addView(info);
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            CheckBox cb = new CheckBox(tk);
            cb.setText(Tr.t(names[i]));
            cb.setChecked(on[i]);
            cb.setOnCheckedChangeListener((v, checked) -> on[index] = checked);
            box.addView(cb);
        }
        ScrollView scroll = new ScrollView(tk);
        scroll.addView(box);
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t("Reset everything TinyKit changed?"))
                .setView(scroll)
                .setPositiveButton(Tr.t("Reset"), (d, w) -> runReset(on[0], on[1], on[2], on[3], on[4]))
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    /** The small version for the permissions: Real, Fake and Off choices, and what they broke. */
    void confirmResetPermissions() {
        if (!Shell.ready()) {
            tk.toast(Tr.t("Start Shizuku and allow TinyKit first."));
            return;
        }
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t("Reset permission changes?"))
                .setMessage(Tr.t("Fake and Off permissions become not allowed, so Android asks the app again. Real "
                        + "ones stay allowed. Anything Fake changed on apps you never picked, like the camera, "
                        + "is repaired. A backup is saved to Download/TinyKit first."))
                .setPositiveButton(Tr.t("Reset"), (d, w) -> runReset(false, true, false, false, false))
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    void runReset(boolean internet, boolean permissions, boolean apps, boolean switches, boolean limits) {
        if (!internet && !permissions && !apps && !switches && !limits) {
            tk.toast(Tr.t("Nothing was picked."));
            return;
        }
        TextView message = tk.text("Saving a backup, then resetting…", 15, false);
        message.setPadding(tk.dp(24), tk.dp(16), tk.dp(24), tk.dp(8));
        AlertDialog progress = new AlertDialog.Builder(tk).setTitle(Tr.t("Working")).setView(message)
                .setCancelable(false).show();
        tk.worker.execute(() -> {
            // The safety net first: if it can't be saved, nothing is changed.
            String backup = backupSetup();
            List<String> lines = new ArrayList<>();
            if (backup.startsWith("Failed")) {
                lines.add("Nothing was reset because the backup couldn't be saved. " + backup);
            } else {
                lines.add(backup);
                if (permissions) lines.add(Reset.permissions(tk, tk.prefs));
                if (internet) lines.add(Reset.internet(tk, tk.prefs));
                if (apps) lines.add(Reset.apps(tk));
                if (switches) lines.add(Reset.switches(tk, tk.prefs));
                if (limits) lines.add(Reset.limits(tk));
            }
            String result = String.join("\n\n", lines);
            tk.main.post(() -> {
                if (!tk.alive()) return;
                tk.dismiss(progress);
                new AlertDialog.Builder(tk).setTitle(Tr.t("Reset")).setMessage(Tr.t(result))
                        .setPositiveButton(Tr.t("OK"), null).show();
            });
        });
    }

    void openBackupRestore() {
        LinearLayout list = tk.vertical();
        list.addView(tk.note("Backs up global, secure and system settings to Download/TinyKit. Restoring "
                + "puts them back; some keys are read-only and are skipped."));
        tk.action(list, "Back up settings now", "Save all settings to a file.", () -> {
            Shell.Result r = Shell.run("d=" + MainActivity.BACKUP_DIR + "; mkdir -p $d; f=$d/settings-$(date +%Y%m%d_%H%M%S).txt; "
                    + "{ for t in global secure system; do settings list $t 2>/dev/null | sed \"s/^/$t /\"; done; } "
                    + "> $f && wc -l < $f");
            if (!r.ok()) return "Failed: " + (r.out.isEmpty() ? "couldn't write the file." : r.out);
            return "Saved " + r.out.trim() + " settings to Download/TinyKit.";
        });
        tk.link(list, "Restore settings", "Pick a backup file and apply it.", this::openRestore);
        tk.section(list, "TinyKit setup");
        list.addView(tk.note("Saves your choices in TinyKit: blocked and allowed apps, the clipboard guard, "
                + "stopped background apps, kept-alive and hidden apps, special access, removed system "
                + "apps. Restoring puts all of it back, for example after a factory reset."));
        tk.action(list, "Back up TinyKit setup", "Save these choices to Download/TinyKit.", this::backupSetup);
        tk.link(list, "Restore TinyKit setup", "Pick a setup file and apply it.", this::openRestoreSetup);
        tk.push("Settings backup & restore", tk.scroll(list));
    }

    /** App-ops TinyKit's pickers set, as {op, mode}: the ones whose apps a setup backup lists. */
    static final String[][] SETUP_OPS = {
            {"READ_CLIPBOARD", "ignore"}, {"RUN_ANY_IN_BACKGROUND", "ignore"}, {"WAKE_LOCK", "ignore"},
            {"REQUEST_INSTALL_PACKAGES", "allow"}, {"SYSTEM_ALERT_WINDOW", "allow"},
            {"WRITE_SETTINGS", "allow"}, {"MANAGE_EXTERNAL_STORAGE", "allow"}, {"GET_USAGE_STATS", "allow"}};

    String backupSetup() {
        StringBuilder out = new StringBuilder("# TinyKit setup\n");
        out.append("mode\t").append(FirewallPolicy.allowMode(tk.prefs) ? "allow" : "block").append('\n');
        for (String pkg : FirewallPolicy.saved(tk.prefs, FirewallPolicy.BLOCKED)) out.append("blocked\t").append(pkg).append('\n');
        for (String pkg : FirewallPolicy.saved(tk.prefs, FirewallPolicy.ALLOWED)) out.append("allowed\t").append(pkg).append('\n');
        if (tk.prefs.getBoolean(Restore.PREF_FORCE_DARK, false)) out.append("dark\t1\n");
        if (tk.prefs.getBoolean(Restore.PREF_NO_OVERLAYS, false)) out.append("overlays\t1\n");
        for (String[] op : SETUP_OPS) {
            for (String pkg : tk.packages(Shell.run("appops query-op " + op[0] + " " + op[1]).out)) {
                out.append("op\t").append(op[0]).append('\t').append(op[1]).append('\t').append(pkg).append('\n');
            }
        }
        for (String line : Shell.run("dumpsys deviceidle whitelist").out.split("\n")) {
            String[] parts = line.trim().split(",");
            if (parts.length >= 2 && parts[0].equals("user")) out.append("alive\t").append(parts[1]).append('\n');
        }
        for (String pkg : tk.disabledPackages()) out.append("hidden\t").append(pkg).append('\n');
        for (String entry : FakePolicy.all(tk.prefs)) {
            String[] f = entry.split("\\|", -1);
            if (f.length == 3) out.append("fake\t").append(f[1]).append('\t').append(f[2].isEmpty() ? "-" : f[2])
                    .append('\t').append(f[0]).append('\n');
        }
        for (String state : new String[]{"Real", "Off"}) {
            for (String entry : FakePolicy.entries(tk.prefs, state)) {
                String[] f = entry.split("\\|", -1);
                if (f.length == 3) out.append(state.toLowerCase(Locale.ROOT)).append('\t').append(f[1]).append('\t')
                        .append(f[2].isEmpty() ? "-" : f[2]).append('\t').append(f[0]).append('\n');
            }
        }
        for (MainActivity.Pkg p : tk.debloatList(true)) out.append("removed\t").append(p.pkg).append('\n');
        String file = MainActivity.BACKUP_DIR + "/setup-" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                .format(new java.util.Date()) + ".txt";
        Shell.Result r = Shell.run("mkdir -p " + MainActivity.BACKUP_DIR + "; cat > " + Shell.quote(file),
                new java.io.ByteArrayInputStream(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (!r.ok()) return "Failed: " + (r.out.isEmpty() ? "couldn't write the file." : r.out);
        return "Saved " + (out.toString().split("\n").length - 1) + " choices to Download/TinyKit.";
    }

    void openRestoreSetup() {
        tk.worker.execute(() -> {
            List<String> files = new ArrayList<>();
            for (String line : Shell.run("ls -1t " + MainActivity.BACKUP_DIR + "/setup-*.txt 2>/dev/null").out.split("\n")) {
                if (!line.trim().isEmpty()) files.add(line.trim());
            }
            tk.main.post(() -> {
                if (files.isEmpty()) {
                    tk.toast(Tr.t("No setup backups yet. Back up first."));
                    return;
                }
                String[] names = new String[files.size()];
                for (int i = 0; i < names.length; i++) names[i] = files.get(i).substring(files.get(i).lastIndexOf('/') + 1);
                new AlertDialog.Builder(tk)
                        .setTitle(Tr.t("Restore which setup?"))
                        .setItems(Tr.all(names), (d, which) -> new AlertDialog.Builder(tk)
                                .setTitle(Tr.t("Restore this setup?"))
                                .setMessage(Tr.t("Puts back the internet block, permission choices, background limits, hidden and removed "
                                        + "apps saved in " + names[which] + ". Apps that aren't installed any more "
                                        + "are skipped."))
                                .setPositiveButton(Tr.t("Restore"), (x, y) -> tk.withProgress("Restore",
                                        () -> restoreSetup(files.get(which))))
                                .setNegativeButton(Tr.t("Cancel"), null)
                                .show())
                        .setNegativeButton(Tr.t("Cancel"), null)
                        .show();
            });
        });
    }

    /** Applies a setup file. Apps that aren't installed any more are skipped. */
    String restoreSetup(String file) {
        Set<String> blocked = new HashSet<>(), allowed = new HashSet<>(), fakes = new HashSet<>();
        List<String[]> choices = new ArrayList<>(); // Real and Off: {package, op, permission, state}
        boolean allowMode = false, dark = false, overlays = false;
        StringBuilder script = new StringBuilder();
        int count = 0;
        for (String line : Shell.run("cat " + Shell.quote(file)).out.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 2) continue;
            // Package names are checked before they reach the shell.
            if (f.length > 1 && !f[0].equals("mode") && !f[0].equals("dark") && !f[0].equals("overlays") && !MainActivity.PACKAGE.matcher(f[f.length - 1]).matches()) continue;
            String q = Shell.quote(f[f.length - 1]);
            switch (f[0]) {
                case "mode": allowMode = f[1].equals("allow"); break;
                case "dark": dark = true; break;
                case "overlays": overlays = true; break;
                case "blocked": blocked.add(f[1]); break;
                case "allowed": allowed.add(f[1]); break;
                case "op":
                    if (f.length == 4 && f[1].matches("[A-Z_]+") && f[2].matches("[a-z]+")) {
                        script.append("appops set ").append(q).append(' ').append(f[1]).append(' ').append(f[2]).append("; ");
                        if (f[1].equals("RUN_ANY_IN_BACKGROUND")) {
                            script.append("am set-standby-bucket ").append(q).append(" restricted >/dev/null 2>&1; ");
                        }
                    }
                    break;
                case "fake":
                    if (f.length == 4 && f[1].matches("[A-Z_]+") && f[2].matches("-|[A-Za-z0-9_.]+")) {
                        fakes.add(FakePolicy.key(f[3], f[1], f[2].equals("-") ? "" : f[2]));
                    }
                    break;
                case "real":
                case "off":
                    if (f.length == 4 && f[1].matches("[A-Z_]+") && f[2].matches("-|[A-Za-z0-9_.]+")) {
                        choices.add(new String[]{f[3], f[1], f[2].equals("-") ? "" : f[2], f[0].equals("real") ? "Real" : "Off"});
                    }
                    break;
                case "alive": script.append("dumpsys deviceidle whitelist +").append(q).append(" >/dev/null; "); break;
                case "hidden": script.append("pm disable-user --user 0 ").append(q).append(" >/dev/null 2>&1; "); break;
                case "removed": script.append("pm uninstall -k --user 0 ").append(q).append(" >/dev/null 2>&1; "); break;
                default: continue;
            }
            count++;
        }
        if (count == 0) return "That file has nothing TinyKit can restore.";
        tk.prefs.edit().putStringSet(FirewallPolicy.BLOCKED, blocked).putStringSet(FirewallPolicy.ALLOWED, allowed)
                .putBoolean(FirewallPolicy.MODE, allowMode).putBoolean(Restore.PREF_FORCE_DARK, dark)
                .putBoolean(Restore.PREF_NO_OVERLAYS, overlays).apply();
        if (script.length() > 0) Shell.run(script.toString());
        for (String[] ch : choices) FakePolicy.choose(tk.prefs, ch[0], ch[1], ch[2], ch[3]);
        if (!fakes.isEmpty() || !choices.isEmpty()) {
            FakePolicy.addFakes(tk.prefs, fakes);
            FakePolicy.enforce(tk, tk.prefs);
        }
        if (dark) Shell.run("setprop debug.hwui.force_dark true");
        if (overlays) Shell.run(Restore.overlaysCommand(true));
        String firewall = FirewallPolicy.supported() ? FirewallPolicy.apply(tk, tk.prefs, true) : null;
        tk.reloadApps();
        return "Restored " + count + " choices." + (firewall != null ? "\nThe internet block wasn't applied: " + firewall : "");
    }

    void openRestore() {
        tk.worker.execute(() -> {
            String out = Shell.run("ls -1t " + MainActivity.BACKUP_DIR + "/settings-*.txt 2>/dev/null").out;
            List<String> files = new ArrayList<>();
            for (String line : out.split("\n")) if (!line.trim().isEmpty()) files.add(line.trim());
            tk.main.post(() -> {
                if (files.isEmpty()) {
                    tk.toast(Tr.t("No backups yet. Back up first."));
                    return;
                }
                String[] names = new String[files.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = files.get(i).substring(files.get(i).lastIndexOf('/') + 1);
                }
                new AlertDialog.Builder(tk)
                        .setTitle(Tr.t("Restore which backup?"))
                        .setItems(Tr.all(names), (d, which) -> confirmRestore(files.get(which)))
                        .setNegativeButton(Tr.t("Cancel"), null)
                        .show();
            });
        });
    }

    void confirmRestore(String file) {
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t("Restore settings?"))
                .setMessage(Tr.t("This writes the saved settings back. Read-only keys are skipped. "
                        + "Some changes may need a restart."))
                .setPositiveButton(Tr.t("Restore"), (d, w) -> tk.withProgress("Restore", () -> {
                    // Keys that had no value were saved as "null" and are left alone.
                    Shell.Result r = Shell.run("ok=0; bad=0; while IFS= read -r line; do t=${line%% *}; "
                            + "rest=${line#* }; key=${rest%%=*}; val=${rest#*=}; "
                            + "case $t in global|secure|system) ;; *) continue;; esac; "
                            + "[ -z \"$key\" ] || [ \"$val\" = null ] && continue; "
                            + "if settings put $t \"$key\" \"$val\" >/dev/null 2>&1; then ok=$((ok+1)); "
                            + "else bad=$((bad+1)); fi; done < " + Shell.quote(file) + "; echo \"$ok $bad\"");
                    String[] n = r.out.trim().split(" ");
                    if (!r.ok() || n.length != 2) return "Failed: " + r.out;
                    return "Restored " + n[0] + " settings." + ("0".equals(n[1]) ? ""
                            : " " + n[1] + " couldn't be written (read-only on this phone) and were skipped.")
                            + " Some changes need a restart.";
                }))
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }
}
