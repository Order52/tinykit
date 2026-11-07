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

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PhoneInfoScreen {
    private final MainActivity tk;

    PhoneInfoScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openInfo() {
        infoPage("Phone info", this::phoneInfo);
    }

    /** A page of key/value rows; an item with a null value starts a new section. */
    void infoPage(String title, MainActivity.InfoJob job) {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), tk.dp(24));
        page.addView(tk.text("Loading…", 15, false));
        ScrollView scroll = new ScrollView(tk);
        scroll.addView(page);
        tk.push(title, scroll);
        tk.worker.execute(() -> {
            List<String[]> info = job.run();
            tk.main.post(() -> {
                page.removeAllViews();
                if (info.isEmpty()) {
                    page.addView(tk.note("Nothing to show."));
                    return;
                }
                for (String[] item : info) {
                    if (item[1] == null) {
                        tk.section(page, item[0]);
                        continue;
                    }
                    TextView key = tk.text(item[0], 13, false);
                    tk.setDim(key, 0.7f);
                    key.setPadding(0, tk.dp(8), 0, 0);
                    page.addView(key);
                    TextView value = tk.text(item[1], 16, false);
                    value.setTextIsSelectable(true);
                    page.addView(value);
                }
            });
        });
    }

    /** A read-only, scrollable, monospace view of a shell command's output, with Refresh. */
    void openLog(String title, String command, String empty) {
        LinearLayout page = tk.vertical();
        Button refresh = tk.button("Refresh", null);
        refresh.setPadding(tk.dp(12), tk.dp(4), tk.dp(12), 0);
        page.addView(refresh);
        TextView log = tk.text("Loading…", 12, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(tk.dp(16), tk.dp(8), tk.dp(16), tk.dp(24));
        ScrollView scroll = new ScrollView(tk);
        scroll.addView(log);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push(title, page);
        Runnable load = () -> tk.worker.execute(() -> {
            String out = Shell.run(command).out;
            tk.main.post(() -> log.setText(Tr.t(out.trim().isEmpty() ? empty : out)));
        });
        refresh.setOnClickListener(v -> load.run());
        load.run();
    }

    List<String[]> phoneInfo() {
        List<String[]> info = new ArrayList<>();
        Intent battery = tk.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryManager bm = (BatteryManager) tk.getSystemService(Context.BATTERY_SERVICE);
        info.add(new String[]{"Battery", null});
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            info.add(new String[]{"Charge", level * 100 / Math.max(scale, 1) + "%  ·  "
                    + tk.batteryStatus(battery.getIntExtra(BatteryManager.EXTRA_STATUS, 1))});
            info.add(new String[]{"Health", tk.batteryHealth(battery.getIntExtra(BatteryManager.EXTRA_HEALTH, 1))});
            info.add(new String[]{"Temperature", String.format(Locale.ROOT, "%.1f °C",
                    battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f)});
            info.add(new String[]{"Voltage", battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) + " mV"});
            String tech = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (!TextUtils.isEmpty(tech)) info.add(new String[]{"Type", tech});
        }
        int current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (current != 0 && current != Integer.MIN_VALUE) {
            // Phones report either µA or mA; values above 20 A must be µA.
            int ma = Math.abs(current) > 20_000 ? current / 1000 : current;
            info.add(new String[]{"Current", ma + " mA"});
        }
        String cycles = null;
        if (battery != null && battery.getIntExtra("android.os.extra.CYCLE_COUNT", -1) >= 0) {
            cycles = String.valueOf(battery.getIntExtra("android.os.extra.CYCLE_COUNT", -1));
        }
        if (Shell.ready()) {
            if (cycles == null) {
                String out = Shell.run("cat /sys/class/power_supply/*/cycle_count 2>/dev/null | head -1").out;
                if (out.matches("\\d+")) cycles = out;
            }
            String stats = Shell.run("dumpsys batterystats 2>/dev/null | grep -m3 -iE "
                    + "'Estimated battery capacity|learned battery capacity'").out;
            for (String line : stats.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.toLowerCase(Locale.ROOT).contains("battery capacity")) {
                    info.add(new String[]{line.substring(0, colon).trim(), line.substring(colon + 1).trim()});
                }
            }
        }
        info.add(new String[]{"Charge cycles", cycles != null ? cycles : "Not reported by this phone"});

        info.add(new String[]{"Memory & storage", null});
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        ((ActivityManager) tk.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mem);
        info.add(new String[]{"RAM", Formatter.formatShortFileSize(tk, mem.availMem) + " free of "
                + Formatter.formatShortFileSize(tk, mem.totalMem)});
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        info.add(new String[]{"Storage", Formatter.formatShortFileSize(tk, fs.getAvailableBytes())
                + " free of " + Formatter.formatShortFileSize(tk, fs.getTotalBytes())});

        info.add(new String[]{"System", null});
        info.add(new String[]{"Model", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")"});
        info.add(new String[]{"Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"});
        info.add(new String[]{"Security patch", Build.VERSION.SECURITY_PATCH});
        info.add(new String[]{"Build", Build.DISPLAY});
        info.add(new String[]{"Kernel", System.getProperty("os.version")});
        info.add(new String[]{"Processor", Build.HARDWARE + "  ·  " + TextUtils.join(", ", Build.SUPPORTED_ABIS)});
        info.add(new String[]{"Uptime", DateUtils.formatElapsedTime(SystemClock.elapsedRealtime() / 1000)});
        return info;
    }

    /**
     * Asks GitHub for the latest release: {version, APK download link}, or null if GitHub can't be
     * reached. Called when "Check for updates" is tapped, or when TinyKit opens with "Check for
     * updates on start" on; nothing checks in the background.
     */
    String[] latestRelease() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://api.github.com/repos/Order52/tinykit/releases/latest").openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            String body;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                body = out.toString("UTF-8");
            }
            Matcher tag = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").matcher(body);
            if (!tag.find()) return null;
            Matcher url = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").matcher(body);
            return new String[]{tag.group(1), url.find() ? url.group(1) : MainActivity.REPO + "/releases/latest"};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * With "Check for updates on start" on, checks when TinyKit opens (at most once an hour) and
     * shows one popup per new version, so it never nags about the same update twice.
     */
    void autoCheckForUpdate() {
        long now = System.currentTimeMillis();
        if (!tk.prefs.getBoolean("update_on_start", false)
                || Math.abs(now - tk.prefs.getLong("update_checked", 0)) < 3_600_000) return;
        tk.prefs.edit().putLong("update_checked", now).apply();
        tk.thumbs.execute(() -> {
            String[] latest = latestRelease();
            if (latest == null || compareVersions(latest[0], tk.versionName(tk.getPackageName())) <= 0
                    || latest[0].equals(tk.prefs.getString("update_notified", ""))) return;
            tk.prefs.edit().putString("update_notified", latest[0]).apply();
            tk.main.post(() -> {
                if (!tk.isFinishing() && !tk.isDestroyed()) showUpdate(latest[0], latest[1]);
            });
        });
    }

    void showUpdate(String version, String download) {
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t("Update available"))
                .setMessage("TinyKit " + version + Tr.t(" is out (you have ") + tk.versionName(tk.getPackageName()) + ").")
                .setPositiveButton(Tr.t("Download"), (d, w) -> tk.openUrl(download))
                .setNegativeButton(Tr.t("Later"), null)
                .show();
    }

    /** Asks the launcher to place the speed-up widget (Android 8+); otherwise explains how to add it. */
    void addBoostWidget() {
        AppWidgetManager manager = tk.getSystemService(AppWidgetManager.class);
        if (Build.VERSION.SDK_INT >= 26 && manager != null && manager.isRequestPinAppWidgetSupported()
                && manager.requestPinAppWidget(new ComponentName(tk, BoostWidget.class), null, null)) {
            return;
        }
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t("Add the widget"))
                .setMessage(Tr.t("Touch and hold an empty spot on your home screen, tap Widgets, then drag "
                        + "TinyKit's \"Speed up\" widget onto the screen."))
                .setPositiveButton(Tr.t("OK"), null)
                .show();
    }

    void checkForUpdate(View row) {
        TextView status = (TextView) row.getTag();
        if (!row.isEnabled()) return;
        row.setEnabled(false);
        status.setText(Tr.t("Checking…"));
        tk.worker.execute(() -> {
            String[] latest = latestRelease();
            String current = tk.versionName(tk.getPackageName());
            String found = latest == null ? null : latest[0];
            String download = latest == null ? null : latest[1];
            tk.main.post(() -> {
                row.setEnabled(true);
                if (found == null) {
                    status.setText(Tr.t("Couldn't reach GitHub. Check your internet and try again."));
                } else if (compareVersions(found, current) > 0) {
                    status.setText(Tr.t("Version " + found + " is available."));
                    new AlertDialog.Builder(tk)
                            .setTitle(Tr.t("Update available"))
                            .setMessage("TinyKit " + found + Tr.t(" is out (you have ") + current + ").")
                            .setPositiveButton(Tr.t("Download"), (d, w) -> tk.openUrl(download))
                            .setNegativeButton(Tr.t("Later"), null)
                            .show();
                } else {
                    status.setText(Tr.t("You have the latest version (" + current + ")."));
                }
            });
        });
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parseInt(x[i]) : 0;
            int q = i < y.length ? parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    static int parseInt(String value) {
        try {
            return Integer.parseInt(value.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    void showAbout() {
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t(tk.getString(R.string.app_name) + " " + tk.versionName(tk.getPackageName())))
                .setMessage(Tr.t("Made by Ali Alakbar\n\nSmall Android tweaks powered by Shizuku. "
                        + "Free and open source (MIT).\n\n" + MainActivity.REPO))
                .setPositiveButton(Tr.t("GitHub"), (d, w) -> tk.openUrl(MainActivity.REPO))
                .setNegativeButton(Tr.t("Close"), null)
                .show();
    }
}
