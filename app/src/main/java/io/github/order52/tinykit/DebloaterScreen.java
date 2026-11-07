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
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class DebloaterScreen {
    private final MainActivity tk;

    DebloaterScreen(MainActivity tk) {
        this.tk = tk;
    }

    static final int[] LEVEL_COLORS = {0xFF66BB6A, 0xFFFFCA28, 0xFFFF8A65, 0xFFEF5350};

    static final int[] LEVEL_COLORS_LIGHT = {0xFF1B5E20, 0xFFB25E00, 0xFFC2410C, 0xFFB71C1C};

    /** Apps in use right now, with why: removing one would break the phone. Call off the main thread. */
    Map<String, String> inUse() {
        Map<String, String> m = new HashMap<>();
        PackageManager pm = tk.getPackageManager();
        m.put(tk.getPackageName(), "TinyKit, the app you are using");
        m.put(MainActivity.SHIZUKU_PKG, "Shizuku, which TinyKit needs");
        ResolveInfo home = pm.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null) m.put(home.activityInfo.packageName, "Your home screen");
        String ime = Settings.Secure.getString(tk.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.contains("/")) m.put(ime.substring(0, ime.indexOf('/')), "Your keyboard");
        if (Build.VERSION.SDK_INT >= 26) {
            android.content.pm.PackageInfo web = android.webkit.WebView.getCurrentWebViewPackage();
            if (web != null) m.put(web.packageName, "Shows web pages inside apps");
        }
        if (Build.VERSION.SDK_INT >= 29 && Shell.ready()) {
            // Whoever holds these roles is in use even if it isn't the default for something else.
            String[][] wanted = {{"HOME", "Your home screen"}, {"DIALER", "Your phone app"}, {"SMS", "Your messages app"}};
            for (String[] role : wanted) {
                for (String holder : tk.packages(Shell.run("cmd role get-role-holders --user 0 android.app.role."
                        + role[0]).out)) {
                    m.put(holder, role[1]);
                }
            }
        }
        return m;
    }

    static MainActivity.Pkg describe(String pkg, String label, boolean system, Map<String, String> inUse) {
        MainActivity.Pkg p = new MainActivity.Pkg();
        p.pkg = pkg;
        p.label = label == null ? pkg : label;
        p.system = system;
        Bloat.Info info = Bloat.info(pkg, system);
        String why = inUse.get(pkg);
        p.level = why != null ? Bloat.UNSAFE : info.level;
        p.note = why != null ? why + ". In use right now" : info.note;
        p.locked = p.level == Bloat.UNSAFE;
        return p;
    }

    /** Installed apps, or (removed) the system apps taken off this phone and still restorable. */
    List<MainActivity.Pkg> debloatList(boolean removed) {
        PackageManager pm = tk.getPackageManager();
        Map<String, String> inUse = inUse();
        Map<String, String> names = new HashMap<>();
        for (MainActivity.App a : tk.installedApps()) names.put(a.pkg, a.label);
        List<MainActivity.Pkg> out = new ArrayList<>();
        Set<String> installed = new HashSet<>();
        for (ApplicationInfo i : pm.getInstalledApplications(0)) {
            installed.add(i.packageName);
            if (!removed) {
                out.add(describe(i.packageName, names.get(i.packageName),
                        (i.flags & ApplicationInfo.FLAG_SYSTEM) != 0, inUse));
            }
        }
        if (removed) {
            // "-u" also lists apps that are gone for this user but still on the system partition.
            for (String line : Shell.run("pm list packages -u").out.split("\n")) {
                String pkg = line.trim().replaceFirst("^package:", "");
                if (pkg.isEmpty() || installed.contains(pkg)) continue;
                try {
                    ApplicationInfo i = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                    // An app the user installed and removed has no files left to bring back.
                    if ((i.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                    MainActivity.Pkg p = describe(pkg, String.valueOf(i.loadLabel(pm)), true, Collections.emptyMap());
                    p.locked = false;
                    out.add(p);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        return out;
    }

    void openDebloat() {
        // Android 7 has no "pm install-existing", so a removed app could not be brought back.
        if (Build.VERSION.SDK_INT < 26) {
            tk.toast(Tr.t("The Debloater needs Android 8 or newer, so removed apps can be restored."));
            return;
        }
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView help = tk.text("Remove apps your phone came with, for you only. Nothing is deleted from the "
                + "system: bring any app back from Removed, and a factory reset restores all of them. "
                + "Hold an app to read about it.", 13, false);
        tk.setDim(help, 0.8f);
        page.addView(help);
        EditText search = tk.searchField("Search apps");
        page.addView(search, tk.fieldMargin());
        CheckBox mine = new CheckBox(tk);
        mine.setText(Tr.t("Also show apps I installed"));
        page.addView(mine);
        TextView loading = tk.text("Reading apps…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button go = tk.button("", null);
        go.setVisibility(View.GONE);
        page.addView(go);
        tk.push("Debloater", page);

        final boolean[] removedView = {false};
        final int[] levelFilter = {-1};
        List<MainActivity.Pkg> all = new ArrayList<>();
        MainActivity.Adapter<MainActivity.Pkg> adapter = tk.new Adapter<>(true, (p, row) -> {
            row.first.setText(Tr.t(p.label));
            row.second.setText(Tr.t(p.pkg));
            if (tk.lightTheme) {
                String levelStr = Tr.t(Bloat.LEVELS[p.level]);
                String noteStr = Tr.t(p.note);
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
                sb.append(levelStr);
                sb.setSpan(new android.text.style.ForegroundColorSpan(LEVEL_COLORS_LIGHT[p.level]),
                        0, levelStr.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(" · ").append(noteStr);
                row.third.setTextColor(0xFF222222);
                row.third.setText(sb);
            } else {
                row.third.setText(Tr.t(Bloat.LEVELS[p.level]) + " · " + Tr.t(p.note));
                row.third.setTextColor(LEVEL_COLORS[p.level]);
            }
            row.check.setChecked(p.checked);
            row.check.setVisibility(p.locked ? View.INVISIBLE : View.VISIBLE);
            tk.showIcon(row, p.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<MainActivity.Pkg> shown = new ArrayList<>();
            int picked = 0;
            for (MainActivity.Pkg p : all) {
                if (p.checked) picked++;
                if (!removedView[0] && !p.system && !mine.isChecked() && !p.checked) continue;
                if (levelFilter[0] >= 0 && p.level != levelFilter[0]) continue;
                if (p.label.toLowerCase(Locale.ROOT).contains(q) || p.pkg.toLowerCase(Locale.ROOT).contains(q)) shown.add(p);
            }
            Collections.sort(shown, (x, y) -> x.checked != y.checked ? (x.checked ? -1 : 1)
                    : x.label.compareToIgnoreCase(y.label));
            adapter.set(shown);
            go.setVisibility(picked > 0 ? View.VISIBLE : View.GONE);
            go.setText(Tr.t((removedView[0] ? "Restore " : "Remove ") + picked + (picked == 1 ? " app" : " apps")));
        };
        Runnable load = () -> {
            boolean wanted = removedView[0];
            all.clear();
            filter.run();
            loading.setText(Tr.t("Reading apps…"));
            loading.setVisibility(View.VISIBLE);
            tk.worker.execute(() -> {
                List<MainActivity.Pkg> list = debloatList(wanted);
                tk.main.post(() -> {
                    if (wanted != removedView[0]) return;
                    all.clear();
                    all.addAll(list);
                    loading.setText(Tr.t("No apps here."));
                    loading.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                    filter.run();
                });
            });
        };
        LinearLayout chips = new LinearLayout(tk);
        chips.setPadding(tk.dp(0), tk.dp(8), tk.dp(0), tk.dp(4));
        chips.addView(tk.chip("", "View", new String[]{"Installed apps", "Removed apps"}, i -> {
            removedView[0] = i == 1;
            mine.setVisibility(i == 1 ? View.GONE : View.VISIBLE);
            load.run();
        }));
        chips.addView(tk.chip("", "Safety", new String[]{"All", "Recommended", "Advanced", "Expert", "Unsafe"}, i -> {
            levelFilter[0] = i - 1;
            filter.run();
        }));
        page.addView(chips, 3);
        search.addTextChangedListener(tk.watcher(filter));
        mine.setOnCheckedChangeListener((v, checked) -> filter.run());
        listView.setOnItemClickListener((parent, view, position, id) -> {
            MainActivity.Pkg p = adapter.getItem(position);
            if (p.locked) {
                tk.toast(Tr.t(p.label + ": " + p.note + ". It can't be removed."));
                return;
            }
            p.checked = !p.checked;
            filter.run();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            MainActivity.Pkg p = adapter.getItem(position);
            new AlertDialog.Builder(tk)
                    .setTitle(Tr.t(p.label))
                    .setMessage(Tr.t(Bloat.LEVELS[p.level]) + ": " + Tr.t(Bloat.MEANING[p.level]) + "\n\n" + Tr.t(p.note)
                            + "\n\n" + p.pkg + (tk.versionName(p.pkg).isEmpty() ? "" : "\n" + Tr.t("Version ") + tk.versionName(p.pkg)))
                    .setPositiveButton(Tr.t("OK"), null)
                    .show();
            return true;
        });
        go.setOnClickListener(v -> {
            List<MainActivity.Pkg> picked = new ArrayList<>();
            int worst = 0;
            for (MainActivity.Pkg p : all) {
                if (!p.checked) continue;
                picked.add(p);
                worst = Math.max(worst, p.level);
            }
            boolean restore = removedView[0];
            List<String> yours = new ArrayList<>();
            for (MainActivity.Pkg p : picked) if (!p.system) yours.add(p.label);
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < picked.size() && i < 12; i++) names.append("• ").append(picked.get(i).label).append('\n');
            if (picked.size() > 12) names.append("and ").append(picked.size() - 12).append(" more\n");
            new AlertDialog.Builder(tk)
                    .setTitle(Tr.t((restore ? "Restore " : "Remove ") + picked.size() + (picked.size() == 1 ? " app?" : " apps?")))
                    .setMessage(Tr.t(names + (restore
                            ? "\nThey come back as they were."
                            : (picked.size() == 1 ? "\nIt disappears" : "\nThey disappear")
                                    + " for you only and can be restored from Removed."
                                    + (yours.isEmpty() ? "" : "\n\nYou installed " + TextUtils.join(", ", yours)
                                    + " yourself: " + (yours.size() == 1 ? "it is" : "they are")
                                    + " uninstalled completely, with " + (yours.size() == 1 ? "its" : "their")
                                    + " data, and can't be restored here.")
                                    + (worst >= Bloat.EXPERT ? "\n\nSome of these are marked Expert: other "
                                    + "apps or features may depend on them." : ""))))
                    .setPositiveButton(Tr.t(restore ? "Restore" : "Remove"), (d, w) -> runDebloat(picked, restore, load))
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        load.run();
    }

    void runDebloat(List<MainActivity.Pkg> picked, boolean restore, Runnable done) {
        TextView message = tk.text((restore ? "Restoring " : "Removing ") + picked.size() + "…", 15, false);
        message.setPadding(tk.dp(24), tk.dp(16), tk.dp(24), tk.dp(8));
        AlertDialog progress = new AlertDialog.Builder(tk).setTitle(Tr.t("Working"))
                .setView(message).setCancelable(false).show();
        tk.worker.execute(() -> {
            StringBuilder script = new StringBuilder();
            for (MainActivity.Pkg p : picked) {
                String q = Shell.quote(p.pkg);
                if (restore) {
                    script.append("echo ").append(q).append("\"|$(pm install-existing --user 0 ").append(q)
                            .append(" 2>&1)\"; ");
                } else {
                    // -k keeps the app's data so a restore brings it back as it was. An updated system
                    // app first loses its update, so a second pass removes the app itself.
                    String k = p.system ? "-k " : "";
                    script.append("r=$(pm uninstall ").append(k).append("--user 0 ").append(q).append(" 2>&1); ")
                            .append("[ -n \"$(pm path ").append(q).append(" 2>/dev/null)\" ] && r=$(pm uninstall ")
                            .append(k).append("--user 0 ").append(q).append(" 2>&1); echo ").append(q)
                            .append("\"|$r\"; ");
                }
            }
            Shell.Result r = Shell.run(script.toString());
            Map<String, String> labels = new HashMap<>();
            for (MainActivity.Pkg p : picked) labels.put(p.pkg, p.label);
            int ok = 0;
            List<String> failed = new ArrayList<>();
            for (String line : r.out.split("\n")) {
                int bar = line.indexOf('|');
                if (bar < 0) continue;
                String pkg = line.substring(0, bar);
                String result = line.substring(bar + 1);
                if (result.contains("Success") || result.contains("installed for user")) {
                    ok++;
                } else if (labels.containsKey(pkg)) {
                    failed.add(labels.get(pkg) + ": " + debloatReason(result));
                }
            }
            tk.reloadApps();
            int count = ok;
            tk.main.post(() -> {
                if (!tk.alive()) return;
                tk.dismiss(progress);
                done.run();
                String summary = (restore ? "Restored " : "Removed ") + count + (count == 1 ? " app." : " apps.");
                if (failed.isEmpty() && count > 0) {
                    tk.toast(Tr.t(summary));
                } else {
                    new AlertDialog.Builder(tk).setTitle(Tr.t(summary))
                            .setMessage(Tr.t(failed.isEmpty() ? "Nothing changed." : TextUtils.join("\n", failed)))
                            .setPositiveButton(Tr.t("OK"), null).show();
                }
            });
        });
    }

    static String debloatReason(String result) {
        if (result.contains("DEVICE_POLICY")) return "your device policy blocks this";
        if (result.contains("USER_RESTRICTED")) return "this phone doesn't allow it";
        if (result.contains("NOT_INSTALLED") || result.contains("Unknown package")) return "not installed";
        return result.isEmpty() ? "Android refused" : result.replaceAll("\\s+", " ");
    }
}
