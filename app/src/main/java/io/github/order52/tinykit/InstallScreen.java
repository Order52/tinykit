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

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class InstallScreen {
    private final MainActivity tk;

    InstallScreen(MainActivity tk) {
        this.tk = tk;
    }

    void pickApks(int user) {
        tk.pickUser = user;
        if (!Shell.ready()) {
            tk.toast(Tr.t("Start Shizuku and allow TinyKit first."));
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            tk.startActivityForResult(pick, MainActivity.REQUEST_PICK_APK);
        } catch (ActivityNotFoundException e) {
            tk.toast(Tr.t("No file picker found on this phone."));
        }
    }

    void openBackup() {
        openPicker("Back up app as APK", "Tap an app to save its APK to Download/TinyKit.", false, null,
                (pkg, on) -> {
                    String name = tk.fileName(tk.label(pkg) + "-" + tk.versionName(pkg));
                    String dir = MainActivity.BACKUP_DIR;
                    Shell.Result r = Shell.run("set -e; p=$(pm path " + Shell.quote(pkg)
                            + " | sed 's/^package://'); mkdir -p " + dir + "; "
                            + "if [ $(echo \"$p\" | wc -l) -eq 1 ]; then "
                            + "cp \"$p\" " + Shell.quote(dir + "/" + name + ".apk") + "; echo apk; "
                            + "else mkdir -p " + Shell.quote(dir + "/" + name) + "; "
                            + "for f in $p; do cp \"$f\" " + Shell.quote(dir + "/" + name) + "/; done; echo split; fi");
                    if (!r.ok()) return r.out.isEmpty() ? "Could not copy the APK." : r.out;
                    String where = "Download/TinyKit/" + name + (r.out.endsWith("split")
                            ? "/ (split APK: install all files together)" : ".apk");
                    tk.main.post(() -> tk.toast(Tr.t("Saved to " + where)));
                    return null;
                });
    }

    void openPicker(String title, String help, boolean checkable, MainActivity.Loader loader, MainActivity.AppChange change) {
        openPicker(title, help, checkable, loader, change, null);
    }

    /** As above; {@code eligible}, when given, limits the list to the apps it returns. */
    void openPicker(String title, String help, boolean checkable, MainActivity.Loader loader, MainActivity.AppChange change,
                            MainActivity.Loader eligible) {
        openPicker(title, help, checkable, loader, change, eligible, false);
    }

    /** As above; {@code internetFilter} adds the "Only apps with internet permission" checkbox. */
    void openPicker(String title, String help, boolean checkable, MainActivity.Loader loader, MainActivity.AppChange change,
                            MainActivity.Loader eligible, boolean internetFilter) {
        openPicker(title, help, checkable, loader, change, eligible, internetFilter, false);
    }

    /** As above; {@code bulk} adds a bar that allows or takes away the access for every app the list shows. */
    void openPicker(String title, String help, boolean checkable, MainActivity.Loader loader, MainActivity.AppChange change,
                            MainActivity.Loader eligible, boolean internetFilter, boolean bulk) {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView helpView = tk.text(help, 14, false);
        tk.setDim(helpView, 0.8f);
        page.addView(helpView);
        EditText search = tk.searchField("Search apps");
        page.addView(search, tk.fieldMargin());
        CheckBox system = new CheckBox(tk);
        system.setText(Tr.t("Show system apps"));
        page.addView(system);
        CheckBox net = null;
        final Set<String> withInternet = Collections.synchronizedSet(new HashSet<>());
        final boolean[] netReady = {false};
        if (internetFilter) {
            net = new CheckBox(tk);
            net.setText(Tr.t("Only apps with internet permission"));
            net.setChecked(tk.prefs.getBoolean(PREF_NET_ONLY, true));
            page.addView(net);
        }
        final CheckBox netBox = net;
        final int[] sort = {0};
        TextView loading = tk.text("Loading apps…", 14, false);
        page.addView(loading);
        final List<MainActivity.App> shownNow = new ArrayList<>();
        final TextView bulkTitle = tk.text("", 15, true);
        final TextView[] bulkPills = new TextView[2];
        final String[] bulkLabels = {"Allow", "Take away"};
        if (checkable && bulk) {
            LinearLayout bar = tk.vertical();
            bar.setPadding(0, tk.dp(8), 0, tk.dp(8));
            bar.addView(bulkTitle);
            LinearLayout seg = new LinearLayout(tk);
            seg.setPadding(0, tk.dp(8), 0, 0);
            for (int i = 0; i < 2; i++) {
                TextView pill = tk.text(bulkLabels[i], 13, true);
                pill.setGravity(Gravity.CENTER);
                pill.setPadding(0, tk.dp(9), 0, tk.dp(9));
                bulkPills[i] = pill;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(i == 0 ? 0 : tk.dp(4), 0, i == 1 ? 0 : tk.dp(4), 0);
                seg.addView(pill, lp);
            }
            bar.addView(seg);
            page.addView(bar);
        }
        ListView listView = new ListView(tk);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push(title, page);

        List<MainActivity.App> all = new ArrayList<>();
        final Set<String> only = Collections.synchronizedSet(new HashSet<>());
        final boolean[] limited = {eligible != null};
        MainActivity.Adapter<MainActivity.App> adapter = tk.new Adapter<>(checkable, (app, row) -> {
            row.first.setText(Tr.t(app.label));
            row.second.setText(Tr.t(app.pkg));
            row.check.setChecked(app.checked);
            tk.showIcon(row, app.pkg);
        });
        listView.setAdapter(adapter);
        final Runnable[] adapterRefresh = new Runnable[1];
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<MainActivity.App> shown = new ArrayList<>();
            for (MainActivity.App a : all) {
                if (limited[0] && !only.contains(a.pkg)) continue;
                // Apps already in the list stay visible so a saved choice never disappears.
                if (netBox != null && netBox.isChecked() && netReady[0] && !a.checked
                        && !withInternet.contains(a.pkg)) continue;
                if ((system.isChecked() || !a.system || a.checked)
                        && (a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.toLowerCase(Locale.ROOT).contains(q))) {
                    shown.add(a);
                }
            }
            // Checked apps stay on top; the chip picks the order within each group.
            Collections.sort(shown, (x, y) -> x.checked != y.checked ? (x.checked ? -1 : 1)
                    : (sort[0] == 1 ? x.pkg.compareToIgnoreCase(y.pkg) : x.label.compareToIgnoreCase(y.label)));
            adapter.set(shown);
            if (checkable && bulk) {
                shownNow.clear();
                shownNow.addAll(shown);
                bulkTitle.setText(Tr.t("Set the apps shown") + " (" + shown.size() + ")");
                for (int i = 0; i < 2; i++) {
                    boolean lit = !shown.isEmpty();
                    for (MainActivity.App a : shown) if (a.checked == (i == 1)) lit = false;
                    bulkPills[i].setAlpha(shown.isEmpty() ? 0.4f : 1f);
                    tk.permissionsScreen.stylePill(bulkPills[i], i == 0 ? "Real" : "Off", lit);
                }
            }
        };
        if (checkable && bulk) {
            for (int i = 0; i < 2; i++) {
                final boolean on = i == 0;
                tk.permissionsScreen.stylePill(bulkPills[i], on ? "Real" : "Off", false);
                bulkPills[i].setOnClickListener(v -> confirmAll(title, new ArrayList<>(shownNow), on, change,
                        () -> adapterRefresh[0].run()));
            }
        }
        adapterRefresh[0] = filter;
        if (checkable) {
            page.addView(tk.sortBar(new String[]{"Name", "Package"}, i -> {
                sort[0] = i;
                filter.run();
            }), 3);
        }
        search.addTextChangedListener(tk.watcher(filter));
        system.setOnCheckedChangeListener((v, checked) -> filter.run());
        if (netBox != null) {
            netBox.setOnCheckedChangeListener((v, checked) -> {
                tk.prefs.edit().putBoolean(PREF_NET_ONLY, checked).apply();
                filter.run();
            });
            tk.worker.execute(() -> {
                Set<String> found = internetApps();
                tk.main.post(() -> {
                    withInternet.addAll(found);
                    netReady[0] = true;
                    filter.run();
                });
            });
        }
        listView.setOnItemClickListener((parent, view, position, id) -> {
            MainActivity.App app = adapter.getItem(position);
            boolean on = !app.checked;
            if (checkable) {
                app.checked = on;
                adapter.notifyDataSetChanged();
            } else {
                tk.toast(Tr.t("Saving " + app.label + "…"));
            }
            tk.worker.execute(() -> {
                String error = change.apply(app.pkg, on);
                tk.main.post(() -> {
                    if (error != null) {
                        if (checkable) app.checked = !on;
                        adapter.notifyDataSetChanged();
                        tk.toast(Tr.t(error));
                    }
                });
            });
        });

        Runnable show = () -> {
            all.clear();
            for (MainActivity.App a : installedApps()) all.add(a.copy());
            loading.setVisibility(View.GONE);
            filter.run();
        };
        // Show the apps instantly (cache is preloaded at launch); the checkmarks fill in once the
        // current state comes back from Shizuku, so there's no blank "loading" wait.
        if (appsLoaded()) {
            show.run();
        } else {
            tk.worker.execute(() -> {
                installedApps();
                tk.main.post(show);
            });
        }
        if (eligible != null) {
            tk.worker.execute(() -> {
                Set<String> ok = eligible.load();
                tk.main.post(() -> {
                    only.addAll(ok);
                    limited[0] = true;
                    filter.run();
                });
            });
        }
        if (loader != null) {
            loading.setText(Tr.t("Reading current state…"));
            loading.setVisibility(View.VISIBLE);
            tk.worker.execute(() -> {
                Set<String> checked = loader.load();
                tk.main.post(() -> {
                    for (MainActivity.App a : all) a.checked = checked.contains(a.pkg);
                    loading.setVisibility(View.GONE);
                    filter.run();
                });
            });
        }
    }

    /** Asks, then allows or takes away the access for every app in {@code targets} that doesn't have that already. */
    private void confirmAll(String title, List<MainActivity.App> targets, boolean on, MainActivity.AppChange change,
                            Runnable refresh) {
        if (targets.isEmpty()) return;
        if (!Shell.ready()) {
            tk.toast(Tr.t("Start Shizuku and allow TinyKit first."));
            return;
        }
        String action = on ? "Allow" : "Take away";
        String about = on ? "Every app shown gets this access. Only do this for apps you trust."
                : "Every app shown loses this access.";
        new AlertDialog.Builder(tk).setTitle(Tr.t("Set every app shown?"))
                .setMessage(Tr.t(title) + " → " + Tr.t(action) + " · " + targets.size() + " "
                        + Tr.t(targets.size() == 1 ? "app" : "apps") + "\n\n" + Tr.t(about))
                .setNegativeButton(Tr.t("Cancel"), null)
                .setPositiveButton(Tr.t("Apply"), (d, w) -> tk.withProgress(title, () -> {
                    int done = 0, same = 0;
                    List<String> skipped = new ArrayList<>();
                    for (MainActivity.App a : targets) {
                        if (a.checked == on) {
                            same++;
                            continue;
                        }
                        String err = change.apply(a.pkg, on);
                        if (err != null) {
                            skipped.add(a.label + ": " + Tr.t(err));
                            continue;
                        }
                        a.checked = on;
                        done++;
                    }
                    tk.main.post(refresh);
                    StringBuilder out = new StringBuilder();
                    out.append(Tr.t(title)).append(" → ").append(Tr.t(action)).append(" · ").append(done).append(' ')
                            .append(Tr.t(done == 1 ? "app" : "apps"));
                    if (same > 0) out.append('\n').append(same).append(' ').append(Tr.t(same == 1 ? "app" : "apps"))
                            .append(' ').append(Tr.t("already")).append(' ').append(Tr.t(action));
                    if (!skipped.isEmpty()) {
                        out.append("\n\n").append(skipped.size()).append(' ').append(Tr.t("skipped")).append(":");
                        for (int i = 0; i < Math.min(5, skipped.size()); i++) out.append("\n• ").append(skipped.get(i));
                        if (skipped.size() > 5) out.append("\n… ").append(skipped.size() - 5).append(' ').append(Tr.t("more"));
                    }
                    return out.toString();
                }))
                .show();
    }

    static final String PREF_NET_ONLY = "fw_net_only";

    /**
     * Apps that can reach the network: those that ask for INTERNET, plus any app sharing a user id
     * with one (the firewall works per user id). Call off the main thread.
     */
    Set<String> internetApps() {
        PackageManager pm = tk.getPackageManager();
        Set<Integer> uids = new HashSet<>();
        for (android.content.pm.PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            if (info.requestedPermissions == null || info.applicationInfo == null) continue;
            for (String r : info.requestedPermissions) {
                if ("android.permission.INTERNET".equals(r)) {
                    uids.add(info.applicationInfo.uid);
                    break;
                }
            }
        }
        Set<String> out = new HashSet<>();
        for (ApplicationInfo i : pm.getInstalledApplications(0)) if (uids.contains(i.uid)) out.add(i.packageName);
        return out;
    }

    /** Reloads the cached app list, e.g. after installing an app. Call off the main thread. */
    void reloadApps() {
        // Built aside and swapped in, so lists opening meanwhile use the old one instead of waiting.
        List<MainActivity.App> fresh = loadApps();
        tk.apps = fresh;
    }

    boolean appsLoaded() {
        return tk.apps != null;
    }

    /** The cached app list; loads it first if needed, so off the main thread unless {@link #appsLoaded}. */
    List<MainActivity.App> installedApps() {
        List<MainActivity.App> list = tk.apps;
        if (list != null) return list;
        synchronized (tk.appsLock) {
            if (tk.apps == null) tk.apps = loadApps();
            return tk.apps;
        }
    }

    List<MainActivity.App> loadApps() {
        PackageManager pm = tk.getPackageManager();
        List<MainActivity.App> list = new ArrayList<>();
        for (ApplicationInfo info : pm.getInstalledApplications(0)) {
            if (info.packageName.equals(tk.getPackageName())) continue;
            MainActivity.App a = new MainActivity.App();
            a.pkg = info.packageName;
            a.label = String.valueOf(info.loadLabel(pm));
            a.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            list.add(a);
        }
        return list;
    }
}
