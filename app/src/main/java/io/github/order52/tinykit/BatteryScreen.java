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

import android.content.pm.PackageManager;
import android.os.Process;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BatteryScreen {
    private final MainActivity tk;

    BatteryScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openBatteryUsage() {
        rowsPage("Battery usage", "Estimated drain since the last full charge, biggest first.",
                "Couldn't read battery stats on this phone.", this::batteryUsage);
    }

    List<String[]> batteryUsage() {
        forgetProfiles();
        List<String[]> rows = new ArrayList<>();
        String out = Shell.run("dumpsys batterystats --charged | sed -n '/Estimated power use/,/^$/p'").out;
        Pattern line = Pattern.compile("^(.+?): ([0-9][0-9.]*)");
        Set<String> skip = new HashSet<>(Arrays.asList("Capacity", "Computed drain", "actual drain"));
        List<float[]> order = new ArrayList<>();
        for (String raw : out.split("\n")) {
            Matcher m = line.matcher(raw.trim());
            if (!m.find()) continue;
            String who = m.group(1).trim();
            if (skip.contains(who)) continue;
            float mah;
            try {
                mah = Float.parseFloat(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (mah < 0.01f) continue;
            String pkg = "";
            if (who.regionMatches(true, 0, "uid ", 0, 4)) {
                String token = who.substring(4).trim();
                String p = uidPackage(token);
                pkg = p != null && installedHere(p) ? p : "";
                who = p != null ? uidTitle(p) : ("0".equals(token) ? "Root / Kernel" : token);
            } else {
                who = who.replace('_', ' ');
                who = who.substring(0, 1).toUpperCase(Locale.ROOT) + who.substring(1);
            }
            rows.add(new String[]{who, String.format(Locale.ROOT, "%.2f mAh", mah), pkg});
            order.add(new float[]{rows.size() - 1, mah});
        }
        Collections.sort(order, (x, y) -> Float.compare(y[1], x[1]));
        List<String[]> sorted = new ArrayList<>();
        for (float[] o : order) sorted.add(rows.get((int) o[0]));
        return sorted;
    }

    String uidLabel(String token) {
        String pkg = uidPackage(token);
        return pkg != null ? tk.label(pkg) : token;
    }

    /** User ids are {@code user * PER_USER_RANGE + app id}. */
    static final int PER_USER_RANGE = 100000;

    /** First package sharing a batterystats uid token like "u0a162" or "1000", or null. */
    String uidPackage(String token) {
        int uid = -1;
        Matcher app = Pattern.compile("u(\\d+)a(\\d+)").matcher(token);
        if (app.find()) {
            uid = Integer.parseInt(app.group(1)) * 100000 + 10000 + Integer.parseInt(app.group(2));
        } else if (token.matches("\\d+")) {
            uid = Integer.parseInt(token);
        }
        if (uid < 0) return null;
        // batterystats lists every profile (Secure Folder, Dual Apps); Android only answers apps
        // for our own, and throws a SecurityException for the others, so those are asked of the shell.
        if (uid / PER_USER_RANGE != Process.myUid() / PER_USER_RANGE) return profilePackage(uid);
        try {
            String[] pkgs = tk.getPackageManager().getPackagesForUid(uid);
            if (pkgs != null && pkgs.length > 0) return pkgs[0];
        } catch (SecurityException ignored) {
        }
        return null;
    }

    /** Package lists of the other profiles, as user id to (uid to package), filled on first use. */
    final Map<Integer, Map<Integer, String>> profileApps = new HashMap<>();

    synchronized String profilePackage(int uid) {
        int user = uid / PER_USER_RANGE;
        Map<Integer, String> apps = profileApps.get(user);
        if (apps == null) {
            apps = new HashMap<>();
            Matcher m = Pattern.compile("package:(\\S+)\\s+uid:(\\d+)").matcher(
                    Shell.run("pm list packages -U --user " + user).out);
            while (m.find()) apps.putIfAbsent(Integer.parseInt(m.group(2)), m.group(1));
            profileApps.put(user, apps);
        }
        return apps.get(uid);
    }

    /** A row title for a batterystats uid: the app's name, or its package name when it only exists in another profile. */
    String uidTitle(String pkg) {
        return installedHere(pkg) ? tk.label(pkg) : pkg + " (other profile)";
    }

    boolean installedHere(String pkg) {
        try {
            tk.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    synchronized void forgetProfiles() {
        profileApps.clear();
    }

    void openWakeups() {
        rowsPage("Wake-ups", "What has held the phone awake since the last charge, longest first.",
                "Nothing recorded since the last charge, or this phone hides it.", this::wakeups);
    }

    static String formatTimes(String countStr) {
        int count = 0;
        try {
            count = Integer.parseInt(countStr);
        } catch (NumberFormatException ignored) {
            return countStr + " " + Tr.t("times");
        }
        String lang = Locale.getDefault().getLanguage();
        if ("ru".equals(lang)) {
            return count + " " + (Tr.ruPlural(count) == 1 ? "раза" : "раз");
        }
        if ("es".equals(lang)) {
            return count + " " + (count == 1 ? "vez" : "veces");
        }
        return count + " " + (count == 1 ? "time" : "times");
    }

    List<String[]> wakeups() {
        forgetProfiles();
        List<String[]> rows = new ArrayList<>();
        String out = Shell.run("dumpsys batterystats --charged | sed -n '/All partial wake locks/,/^$/p'").out;
        Pattern p = Pattern.compile("Wake lock (\\S+) (.+?): ([0-9hms ]+?) \\((\\d+) times?\\)");
        for (String line : out.split("\n")) {
            Matcher m = p.matcher(line.trim());
            if (!m.find()) continue;
            String pkg = uidPackage(m.group(1));
            String who = pkg != null ? uidTitle(pkg) : ("0".equals(m.group(1)) ? "Root / Kernel" : m.group(1));
            String detail = Tr.t("Held ") + m.group(3).trim() + "  ·  "
                    + formatTimes(m.group(4)) + "  ·  " + m.group(2);
            rows.add(new String[]{who, detail, pkg != null && installedHere(pkg) ? pkg : ""});
        }
        return rows;
    }

    void openVibration() {
        rowsPage("Vibration log", "Recent vibrations, most recent first.",
                "No vibrations recorded since the phone started.", this::vibrations);
    }

    List<String[]> vibrations() {
        String out = Shell.run("dumpsys vibrator_manager 2>/dev/null | sed -n '/Previous vibrations for usage/,$p'").out;
        if (out.trim().isEmpty()) {
            out = Shell.run("dumpsys vibrator 2>/dev/null | sed -n '/Previous vibrations/,$p'").out;
        }
        Pattern usageP = Pattern.compile("Previous vibrations for usage (\\w+)");
        Pattern timeP = Pattern.compile("(?:start|create)Time: ([\\d-]+ [\\d:.]+)");
        Pattern pkgP = Pattern.compile("opPkg[=:] ?([a-zA-Z0-9_.]+)");
        Pattern effP = Pattern.compile("effect=([A-Z_]+)");
        List<String[]> rows = new ArrayList<>();
        String usage = "";
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            Matcher um = usageP.matcher(line);
            if (um.find()) {
                usage = capitalize(um.group(1));
                continue;
            }
            if (!line.contains("Time:")) continue;
            Matcher tm = timeP.matcher(line);
            Matcher pm = pkgP.matcher(line);
            Matcher em = effP.matcher(line);
            String when = tm.find() ? tm.group(1) : "";
            String pkg = pm.find() ? pm.group(1) : "";
            String who = pkg.isEmpty() ? "System" : tk.label(pkg);
            String effect = em.find() ? capitalize(em.group(1).replace('_', ' ')) : "Vibration";
            String detail = (usage.isEmpty() ? "" : usage + "  ·  ") + effect + (when.isEmpty() ? "" : "  ·  " + when);
            rows.add(new String[]{who, detail, when, pkg});
        }
        Collections.sort(rows, (a, b) -> b[2].compareTo(a[2]));
        List<String[]> out2 = new ArrayList<>();
        for (String[] r : rows) out2.add(new String[]{r[0], r[1], r[3]});
        return out2;
    }

    static String capitalize(String value) {
        value = value.toLowerCase(Locale.ROOT);
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** A simple two-line list page backed by a worker job that returns {line1, line2} rows. */
    void rowsPage(String title, String help, String empty, MainActivity.InfoJob job) {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView helpView = tk.text(help, 13, false);
        tk.setDim(helpView, 0.7f);
        page.addView(helpView);
        TextView loading = tk.text("Loading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push(title, page);
        MainActivity.Adapter<String[]> adapter = tk.new Adapter<>(false, (a, row) -> {
            row.first.setText(Tr.t(a[0]));
            row.second.setText(Tr.t(a[1]));
            if (a.length > 2 && a[2] != null && a[2].contains(".")) tk.showIcon(row, a[2]);
        });
        listView.setAdapter(adapter);
        final List<String[]> original = new ArrayList<>();
        final int[] sort = {0};
        Runnable apply = () -> {
            List<String[]> shown = new ArrayList<>(original);
            if (sort[0] == 1) Collections.sort(shown, (x, y) -> x[0].compareToIgnoreCase(y[0]));
            adapter.set(shown);
        };
        page.addView(tk.sortBar(new String[]{"Default", "Name"}, i -> {
            sort[0] = i;
            apply.run();
        }), 1);
        tk.worker.execute(() -> {
            // Whatever goes wrong reading the phone's logs (another profile, an odd dump format), the
            // page shows its empty message instead of taking the whole app down.
            List<String[]> rows;
            try {
                rows = job.run();
            } catch (RuntimeException e) {
                android.util.Log.w("TinyKit", title + ": " + e);
                rows = new ArrayList<>();
            }
            final List<String[]> shownRows = rows;
            tk.main.post(() -> {
                loading.setVisibility(shownRows.isEmpty() ? View.VISIBLE : View.GONE);
                if (shownRows.isEmpty()) loading.setText(Tr.t(empty));
                original.clear();
                original.addAll(shownRows);
                apply.run();
            });
        });
    }

    void openAppLog() {
        tk.openAppList("App log", "Pick an app to see its recent log output.", pkg -> tk.openLog(tk.label(pkg),
                "pid=$(pidof -s " + Shell.quote(pkg) + " 2>/dev/null); "
                        + "if [ -n \"$pid\" ]; then logcat -d -v time --pid=$pid -t 500; "
                        + "else logcat -d -v time -t 3000 | grep -F " + Shell.quote(pkg) + " | tail -500; fi",
                "No log output. The app may not be running, or this phone hides app logs."));
    }
}
