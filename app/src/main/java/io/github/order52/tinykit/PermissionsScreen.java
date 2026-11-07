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

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PermissionsScreen {
    private final MainActivity tk;

    PermissionsScreen(MainActivity tk) {
        this.tk = tk;
    }

    /** Sensitive ops the per-app page offers, as {op, name, matching runtime permission}. */
    static final String[][] PERMISSION_OPS = {
            {"COARSE_LOCATION", "Approximate location", "android.permission.ACCESS_COARSE_LOCATION"},
            {"FINE_LOCATION", "Precise location", "android.permission.ACCESS_FINE_LOCATION"},
            {"CAMERA", "Camera", "android.permission.CAMERA"},
            {"RECORD_AUDIO", "Microphone", "android.permission.RECORD_AUDIO"},
            {"READ_CONTACTS", "Read contacts", "android.permission.READ_CONTACTS"},
            {"WRITE_CONTACTS", "Modify contacts", "android.permission.WRITE_CONTACTS"},
            {"READ_CALENDAR", "Calendar", "android.permission.READ_CALENDAR"},
            {"READ_CALL_LOG", "Call log", "android.permission.READ_CALL_LOG"},
            {"READ_PHONE_STATE", "Phone", "android.permission.READ_PHONE_STATE"},
            {"READ_SMS", "SMS", "android.permission.READ_SMS"},
            {"READ_EXTERNAL_STORAGE", "Read storage", "android.permission.READ_EXTERNAL_STORAGE"},
            {"WRITE_EXTERNAL_STORAGE", "Write storage", "android.permission.WRITE_EXTERNAL_STORAGE"},
            {"BODY_SENSORS", "Body sensors", "android.permission.BODY_SENSORS"},
            {"ACTIVITY_RECOGNITION", "Physical activity", "android.permission.ACTIVITY_RECOGNITION"},
            {"POST_NOTIFICATION", "Notifications", "android.permission.POST_NOTIFICATIONS"}};

    /** Quieter ops most settings screens hide, as {op, name}. */
    static final String[][] HIDDEN_OPS = {
            {"READ_CLIPBOARD", "Read clipboard"},
            {"WRITE_CLIPBOARD", "Write clipboard"},
            {"RUN_ANY_IN_BACKGROUND", "Run in background"},
            {"WAKE_LOCK", "Keep device awake"},
            {"SYSTEM_ALERT_WINDOW", "Draw over other apps"},
            {"GET_USAGE_STATS", "Read app usage"},
            {"MANAGE_EXTERNAL_STORAGE", "All files access"},
            {"START_FOREGROUND", "Foreground service"},
            {"VIBRATE", "Vibrate"}};

    /** Lists installed apps and calls {@code onPick} with the one tapped. */
    /** Every sensitive permission with how many apps hold it; a tap lists those apps. */
    void openPermissionTypes() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView help = tk.text("Pick a permission to see which apps ask for it and which have it.", 14, false);
        tk.setDim(help, 0.8f);
        page.addView(help);
        TextView loading = tk.text("Reading permissions…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push("Permissions by type", page);
        final Map<String, List<MainActivity.App>> byPermission = new HashMap<>();
        MainActivity.Adapter<String[]> adapter = tk.new Adapter<>(false, (op, row) -> {
            List<MainActivity.App> apps = byPermission.get(op[2]);
            int has = 0;
            if (apps != null) for (MainActivity.App a : apps) if (a.checked) has++;
            row.first.setText(Tr.t(op[1]));
            row.second.setText(apps == null ? "" : has + " " + Tr.t("of") + " " + apps.size() + " " + Tr.t("apps have it"));
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String[] op = adapter.getItem(position);
            List<MainActivity.App> apps = byPermission.get(op[2]);
            if (apps != null) openPermissionApps(op[1], apps);
        });
        tk.worker.execute(() -> {
            PackageManager pm = tk.getPackageManager();
            for (PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                if (info.requestedPermissions == null || info.packageName.equals(tk.getPackageName())) continue;
                for (int i = 0; i < info.requestedPermissions.length; i++) {
                    for (String[] op : PERMISSION_OPS) {
                        if (!op[2].equals(info.requestedPermissions[i])) continue;
                        MainActivity.App a = new MainActivity.App();
                        a.pkg = info.packageName;
                        a.label = info.applicationInfo == null ? info.packageName
                                : String.valueOf(info.applicationInfo.loadLabel(pm));
                        a.checked = (info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                        byPermission.computeIfAbsent(op[2], k -> new ArrayList<>()).add(a);
                    }
                }
            }
            List<String[]> shown = new ArrayList<>();
            for (String[] op : PERMISSION_OPS) if (byPermission.containsKey(op[2])) shown.add(op);
            tk.main.post(() -> {
                loading.setVisibility(View.GONE);
                adapter.set(shown);
            });
        });
    }

    /** The apps that ask for one permission, the ones that have it first. A tap opens that app's permissions. */
    void openPermissionApps(String title, List<MainActivity.App> apps) {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        EditText search = tk.searchField("Search apps");
        page.addView(search, tk.fieldMargin());
        ListView listView = new ListView(tk);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push(title, page);
        MainActivity.Adapter<MainActivity.App> adapter = tk.new Adapter<>(false, (a, row) -> {
            row.first.setText(a.label);
            row.second.setText(a.pkg);
            row.third.setText(Tr.t(a.checked ? "Allowed" : "Not allowed"));
            row.third.setTextColor(a.checked ? stateColor("Real") : stateColor("Off"));
            tk.showIcon(row, a.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<MainActivity.App> shown = new ArrayList<>();
            for (MainActivity.App a : apps) {
                if (a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.toLowerCase(Locale.ROOT).contains(q)) shown.add(a);
            }
            Collections.sort(shown, (x, y) -> x.checked != y.checked ? (x.checked ? -1 : 1)
                    : x.label.compareToIgnoreCase(y.label));
            adapter.set(shown);
        };
        search.addTextChangedListener(tk.watcher(filter));
        listView.setOnItemClickListener((parent, view, position, id) -> openAppPage(adapter.getItem(position).pkg));
        filter.run();
    }

    void openAppList(String title, String help, MainActivity.OnText onPick) {
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
        final int[] sort = {0};
        TextView loading = tk.text("Loading apps…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push(title, page);

        List<MainActivity.App> all = new ArrayList<>();
        MainActivity.Adapter<MainActivity.App> adapter = tk.new Adapter<>(false, (app, row) -> {
            row.first.setText(Tr.t(app.label));
            row.second.setText(Tr.t(app.pkg));
            tk.showIcon(row, app.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<MainActivity.App> shown = new ArrayList<>();
            for (MainActivity.App a : all) {
                if ((system.isChecked() || !a.system)
                        && (a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.toLowerCase(Locale.ROOT).contains(q))) {
                    shown.add(a);
                }
            }
            Collections.sort(shown, (x, y) -> sort[0] == 1
                    ? x.pkg.compareToIgnoreCase(y.pkg) : x.label.compareToIgnoreCase(y.label));
            adapter.set(shown);
        };
        page.addView(tk.sortBar(new String[]{"Name", "Package"}, i -> {
            sort[0] = i;
            filter.run();
        }), 3);
        search.addTextChangedListener(tk.watcher(filter));
        system.setOnCheckedChangeListener((v, checked) -> filter.run());
        listView.setOnItemClickListener((parent, view, position, id) -> onPick.run(adapter.getItem(position).pkg));
        Runnable show = () -> {
            all.clear();
            for (MainActivity.App a : tk.installedApps()) all.add(a.copy());
            loading.setVisibility(View.GONE);
            filter.run();
        };
        // Instant when the cache is warm (it's preloaded at launch); otherwise load once off-thread.
        if (tk.appsLoaded()) {
            show.run();
        } else {
            tk.worker.execute(() -> {
                tk.installedApps();
                tk.main.post(show);
            });
        }
    }

    /**
     * One app's permissions and app-ops, each settable to Real, Fake or Off.
     *
     * Camera, microphone and location are controlled at the UID level on Android 12+, so they use
     * {@code appops set --uid} and a real "Off" revokes the permission; the quieter ops are plain
     * package-level app-ops.
     */
    void openAppPage(String pkg) {
        LinearLayout list = tk.vertical();
        LinearLayout head = new LinearLayout(tk);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(tk.dp(16), tk.dp(16), tk.dp(16), tk.dp(4));
        ImageView appIc = new ImageView(tk);
        appIc.setImageResource(android.R.drawable.sym_def_app_icon);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(tk.dp(52), tk.dp(52));
        hp.setMargins(0, 0, tk.dp(14), 0);
        head.addView(appIc, hp);
        LinearLayout htext = tk.vertical();
        htext.addView(tk.text(tk.label(pkg), 20, true));
        TextView sub = tk.text(pkg, 12, false);
        tk.setDim(sub, 0.6f);
        htext.addView(sub);
        head.addView(htext);
        list.addView(head);
        tk.thumbs.execute(() -> {
            Bitmap b = tk.iconBitmap(pkg, tk.dp(52));
            if (b != null) tk.main.post(() -> appIc.setImageBitmap(b));
        });
        list.addView(tk.note("Tap Real, Fake or Off for each permission. Fake shows the app the "
                + "permission as granted while it actually gets nothing."));
        TextView loading = tk.text("Reading permissions…", 14, false);
        loading.setPadding(tk.dp(16), tk.dp(8), tk.dp(16), 0);
        list.addView(loading);
        tk.push(tk.label(pkg), tk.scroll(list));

        tk.worker.execute(() -> {
            Set<String> granted = grantedPermissions(pkg);
            Set<String> requested = requestedPermissions(pkg);
            Map<String, String> modes = opModes(pkg);
            tk.main.post(() -> {
                list.removeView(loading);
                tk.section(list, "Permissions");
                List<java.util.function.Consumer<String>> setters = new ArrayList<>();
                int allAt = list.getChildCount();
                for (String[] op : PERMISSION_OPS) {
                    if (requested.contains(op[2]) || granted.contains(op[2])) {
                        setters.add(opRow(list, pkg, op, true, stateOf(true, granted.contains(op[2]), modes.get(op[0]))));
                    }
                }
                if (setters.isEmpty()) list.addView(tk.note("This app requests no sensitive permissions."));
                else list.addView(allRow(setters), allAt); // one tap for every permission shown below
                tk.section(list, "Hidden app-ops");
                for (String[] op : HIDDEN_OPS) {
                    opRow(list, pkg, op, false, stateOf(false, false, modes.get(op[0])));
                }
            });
        });
    }

    /** A permission with an inline Real / Fake / Off segmented control — one tap to change. */
    java.util.function.Consumer<String> opRow(LinearLayout list, String pkg, String[] op, boolean permission,
                                                       String initial) {
        final String[] state = {initial};
        LinearLayout block = tk.vertical();
        block.setPadding(tk.dp(16), tk.dp(10), tk.dp(16), tk.dp(6));
        block.addView(tk.text(op[1], 16, false));
        LinearLayout seg = new LinearLayout(tk);
        seg.setPadding(0, tk.dp(8), 0, 0);
        final String[] keys = {"Real", "Fake", "Off"};
        final TextView[] pills = new TextView[3];
        final Runnable render = () -> {
            for (int i = 0; i < 3; i++) stylePill(pills[i], keys[i], keys[i].equals(state[0]));
        };
        final java.util.function.Consumer<String> choose = target -> {
            if (!Shell.ready()) {
                tk.toast(Tr.t("Start Shizuku and allow TinyKit first."));
                return;
            }
            if (target.equals(state[0])) return;
            state[0] = target;
            render.run();
            tk.worker.execute(() -> {
                String err = setOp(pkg, op, permission, target);
                // Remembered (Real, Fake or Off) so Android can't quietly change it back.
                if (err == null) FakePolicy.choose(tk.prefs, pkg, op[0], permission ? op[2] : "", target);
                String now = readState(pkg, op, permission);
                tk.main.post(() -> {
                    state[0] = now;
                    render.run();
                    if (err != null) tk.toast(Tr.t(err));
                });
            });
        };
        for (int i = 0; i < 3; i++) {
            final String target = keys[i];
            TextView pill = tk.text(target, 13, true);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(0, tk.dp(9), 0, tk.dp(9));
            pill.setOnClickListener(v -> choose.accept(target));
            pills[i] = pill;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(i == 0 ? 0 : tk.dp(4), 0, i == 2 ? 0 : tk.dp(4), 0);
            seg.addView(pill, lp);
        }
        block.addView(seg);
        render.run();
        list.addView(block);
        return choose;
    }

    /** "All permissions": Real, Fake or Off applied to every permission row of the page in one tap. */
    View allRow(List<java.util.function.Consumer<String>> setters) {
        LinearLayout block = tk.vertical();
        block.setPadding(tk.dp(16), tk.dp(10), tk.dp(16), tk.dp(6));
        block.addView(tk.text("All permissions", 16, true));
        LinearLayout seg = new LinearLayout(tk);
        seg.setPadding(0, tk.dp(8), 0, 0);
        String[] keys = {"Real", "Fake", "Off"};
        for (int i = 0; i < 3; i++) {
            String target = keys[i];
            TextView pill = tk.text(target, 13, true);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(0, tk.dp(9), 0, tk.dp(9));
            stylePill(pill, target, false);
            pill.setOnClickListener(v -> {
                for (java.util.function.Consumer<String> set : setters) set.accept(target);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(i == 0 ? 0 : tk.dp(4), 0, i == 2 ? 0 : tk.dp(4), 0);
            seg.addView(pill, lp);
        }
        block.addView(seg);
        return block;
    }

    void stylePill(TextView pill, String key, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(tk.dp(8));
        shape.setColor(selected ? stateColor(key) : (tk.lightTheme ? 0xFFE0E0E0 : 0xFF1C1C1C));
        pill.setBackground(shape);
        pill.setTextColor(selected ? 0xFF000000 : (tk.lightTheme ? 0xFF000000 : 0xFFBDBDBD));
    }

    int stateColor(String state) {
        switch (state) {
            case "Fake": return tk.attrColor(android.R.attr.colorAccent);
            case "Off": return 0xFFE57373;
            default: return 0xFF66BB6A;
        }
    }

    static String stateOf(boolean permission, boolean granted, String mode) {
        if (permission) {
            if (!granted) return "Off";
            return "ignore".equals(mode) ? "Fake" : "Real";
        }
        if ("ignore".equals(mode)) return "Fake";
        if ("deny".equals(mode)) return "Off";
        return "Real";
    }

    String setOp(String pkg, String[] op, boolean permission, String target) {
        String q = Shell.quote(pkg);
        if (permission) {
            // Guarded: refuses Fake/Off where other apps or the camera would be hit, and undoes a
            // change that turns out to have touched an app that wasn't picked.
            return PermGuard.setPermission(tk, pkg, op[0], op[2], target);
        }
        String mode = target.equals("Real") ? "allow" : target.equals("Fake") ? "ignore" : "deny";
        return tk.error(Shell.run("appops set " + q + " " + op[0] + " " + mode));
    }

    /** Reads one op's live state after a change. Call off the main thread. */
    String readState(String pkg, String[] op, boolean permission) {
        if (permission) {
            boolean granted = tk.getPackageManager().checkPermission(op[2], pkg) == PackageManager.PERMISSION_GRANTED;
            return stateOf(true, granted, opMode(pkg, op[0], true));
        }
        return stateOf(false, false, opMode(pkg, op[0], false));
    }

    /**
     * Current mode of every op the page shows, in one shell round-trip. Permission ops are read at
     * the UID level, the hidden ops at the package level; their names never overlap.
     */
    Map<String, String> opModes(String pkg) {
        String q = Shell.quote(pkg);
        StringBuilder cmd = new StringBuilder();
        for (String[] op : PERMISSION_OPS) {
            cmd.append("echo \"").append(op[0]).append("=$(appops get --uid ").append(q).append(' ')
                    .append(op[0]).append(" 2>/dev/null)\"; ");
        }
        for (String[] op : HIDDEN_OPS) {
            cmd.append("echo \"").append(op[0]).append("=$(appops get ").append(q).append(' ')
                    .append(op[0]).append(" 2>/dev/null)\"; ");
        }
        Map<String, String> modes = new HashMap<>();
        for (String line : Shell.run(cmd.toString()).out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            modes.put(line.substring(0, eq), extractMode(line.substring(eq + 1)));
        }
        return modes;
    }

    String opMode(String pkg, String op, boolean uid) {
        return extractMode(Shell.run("appops get " + (uid ? "--uid " : "")
                + Shell.quote(pkg) + " " + op + " 2>/dev/null").out);
    }

    static String extractMode(String text) {
        Matcher m = Pattern.compile("\\b(allow|ignore|deny|foreground|default)\\b").matcher(text);
        return m.find() ? m.group(1) : "default";
    }

    Set<String> requestedPermissions(String pkg) {
        Set<String> set = new HashSet<>();
        try {
            PackageInfo info = tk.getPackageManager().getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) Collections.addAll(set, info.requestedPermissions);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return set;
    }

    Set<String> grantedPermissions(String pkg) {
        Set<String> set = new HashSet<>();
        try {
            PackageInfo info = tk.getPackageManager().getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) {
                for (int i = 0; i < info.requestedPermissions.length; i++) {
                    if ((info.requestedPermissionsFlags[i]
                            & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                        set.add(info.requestedPermissions[i]);
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return set;
    }
}
