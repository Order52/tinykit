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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class InstalledScreen {
    private final MainActivity tk;

    InstalledScreen(MainActivity tk) {
        this.tk = tk;
    }

    /**
     * Apps installed through TinyKit's installer, which marks itself as their installer (see
     * {@link ApkInstaller}). The list is read from Android's own records, so TinyKit stores nothing.
     */
    List<String[]> installedByTinyKit() {
        PackageManager pm = tk.getPackageManager();
        List<long[]> order = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        for (ApplicationInfo i : pm.getInstalledApplications(0)) {
            String installer;
            try {
                installer = Build.VERSION.SDK_INT >= 30
                        ? pm.getInstallSourceInfo(i.packageName).getInstallingPackageName()
                        : pm.getInstallerPackageName(i.packageName);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                continue;
            }
            if (!tk.getPackageName().equals(installer) || i.packageName.equals(tk.getPackageName())) continue;
            try {
                PackageInfo info = pm.getPackageInfo(i.packageName, 0);
                long size = new java.io.File(i.sourceDir).length();
                long age = System.currentTimeMillis() - info.lastUpdateTime;
                String when = age < 60_000 ? "just now" : DateUtils.getRelativeTimeSpanString(info.lastUpdateTime,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
                rows.add(new String[]{i.packageName, String.valueOf(i.loadLabel(pm)) + "  " + info.versionName,
                        (info.firstInstallTime == info.lastUpdateTime ? "Installed " : "Updated ") + when
                                + " · " + Formatter.formatShortFileSize(tk, size)});
                order.add(new long[]{rows.size() - 1, info.lastUpdateTime});
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        Collections.sort(order, (x, y) -> Long.compare(y[1], x[1]));
        List<String[]> sorted = new ArrayList<>();
        for (long[] o : order) sorted.add(rows.get((int) o[0]));
        return sorted;
    }

    void openInstallHistory() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView help = tk.text("Apps installed with TinyKit's installer, newest first. Tap one to open or "
                + "remove it. This list is read from Android's own records, so TinyKit stores nothing.", 13, false);
        tk.setDim(help, 0.7f);
        page.addView(help);
        TextView loading = tk.text("Reading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        View view = page;
        MainActivity.Adapter<String[]> adapter = tk.new Adapter<>(false, (a, row) -> {
            row.first.setText(Tr.t(a[1]));
            row.second.setText(Tr.t(a[0]));
            row.third.setText(Tr.t(a[2]));
            tk.showIcon(row, a[0]);
        });
        listView.setAdapter(adapter);
        Runnable load = () -> tk.worker.execute(() -> {
            tk.reloadApps();
            List<String[]> rows = installedByTinyKit();
            tk.main.post(() -> {
                loading.setText(Tr.t("Nothing yet. Apps you install with TinyKit from now on show up here."));
                loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.set(rows);
            });
        });
        listView.setOnItemClickListener((parent, v, position, id) -> {
            String[] a = adapter.getItem(position);
            new AlertDialog.Builder(tk)
                    .setTitle(Tr.t(a[1]))
                    .setMessage(Tr.t(a[2] + "\n\nRemoving it deletes the app and its data. TinyKit keeps no copy "
                            + "of the file, so an older version can't be put back."))
                    .setPositiveButton(Tr.t("Open"), (d, w) -> tk.openApp(a[0]))
                    .setNegativeButton(Tr.t("Remove"), (d, w) -> new AlertDialog.Builder(tk)
                            .setTitle(Tr.t("Remove " + a[1] + "?"))
                            .setMessage(Tr.t("The app and its data are deleted."))
                            .setPositiveButton(Tr.t("Remove"), (d2, w2) -> tk.worker.execute(() -> {
                                String error = tk.error(Shell.run("pm uninstall " + Shell.quote(a[0])));
                                tk.main.post(() -> {
                                    tk.toast(Tr.t(error != null ? error : "Removed."));
                                    load.run();
                                });
                            }))
                            .setNegativeButton(Tr.t("Cancel"), null)
                            .show())
                    .setNeutralButton(Tr.t("Close"), null)
                    .show();
        });
        tk.push("Installed with TinyKit", view);
        load.run();
    }
}
