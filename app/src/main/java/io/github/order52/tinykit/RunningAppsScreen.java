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
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
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

final class RunningAppsScreen {
    private final MainActivity tk;

    RunningAppsScreen(MainActivity tk) {
        this.tk = tk;
    }

    static final String PREF_STOP_ASK = "stop_ask";

    void openRunning() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView summary = tk.text("Loading…", 15, true);
        page.addView(summary);
        TextView help = tk.text("Tap an app to stop it. Hold one to pick several. System processes can't be stopped.", 13, false);
        tk.setDim(help, 0.7f);
        page.addView(help);
        CheckBox ask = new CheckBox(tk);
        ask.setText(Tr.t("Ask before stopping"));
        ask.setChecked(tk.prefs.getBoolean(PREF_STOP_ASK, true));
        ask.setOnCheckedChangeListener((v, on) -> tk.prefs.edit().putBoolean(PREF_STOP_ASK, on).apply());
        page.addView(ask);
        ListView listView = new ListView(tk);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button stopSelected = tk.primary(tk.button("", null));
        stopSelected.setVisibility(View.GONE);
        LinearLayout.LayoutParams stopMargin = new LinearLayout.LayoutParams(-1, -2);
        stopMargin.setMargins(0, tk.dp(8), 0, tk.dp(8));
        page.addView(stopSelected, stopMargin);
        tk.push("Running apps", page);

        final List<MainActivity.Proc> procs = new ArrayList<>();
        final Set<String> picked = new HashSet<>(); // packages chosen with a long press
        MainActivity.Adapter<MainActivity.Proc> adapter = tk.new Adapter<>(true, (p, row) -> {
            row.first.setText(Tr.t(p.label));
            row.second.setText(Tr.t(Formatter.formatShortFileSize(tk, p.kb * 1024)
                    + String.format(Locale.US, "  ·  %.1f%% CPU", p.cpu)
                    + (p.label.equals(p.name) ? "" : "  ·  " + p.name)));
            row.check.setVisibility(picked.isEmpty() || p.pkg == null ? View.GONE : View.VISIBLE);
            row.check.setChecked(p.pkg != null && picked.contains(p.pkg));
            tk.showIcon(row, p.name.split(":")[0]);
        });
        listView.setAdapter(adapter);
        final int[] sort = {0};
        Runnable apply = () -> {
            if (sort[0] == 2) Collections.sort(procs, (x, y) -> x.label.compareToIgnoreCase(y.label));
            else if (sort[0] == 1) Collections.sort(procs, (x, y) -> Float.compare(y.cpu, x.cpu));
            else Collections.sort(procs, (x, y) -> Long.compare(y.kb, x.kb));
            adapter.set(procs);
        };
        page.addView(tk.sortBar(new String[]{"Memory", "CPU", "Name"}, i -> {
            sort[0] = i;
            apply.run();
        }), 2);
        Runnable[] load = new Runnable[1];
        load[0] = () -> tk.worker.execute(() -> {
            List<MainActivity.Proc> found = runningProcesses();
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            ((ActivityManager) tk.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mem);
            tk.main.post(() -> {
                summary.setText(Tr.t("Memory: ") + Formatter.formatShortFileSize(tk, mem.totalMem - mem.availMem)
                        + Tr.t(" used of ") + Formatter.formatShortFileSize(tk, mem.totalMem));
                procs.clear();
                procs.addAll(found);
                apply.run();
            });
        });
        // Stops the given packages one after the other, then reads the list again.
        java.util.function.Consumer<List<String>> stop = pkgs -> tk.worker.execute(() -> {
            String error = null;
            for (String pkg : pkgs) {
                String e = tk.error(Shell.run("am force-stop " + Shell.quote(pkg)));
                if (e != null) error = e;
            }
            String failed = error;
            tk.main.post(() -> {
                picked.clear();
                stopSelected.setVisibility(View.GONE);
                if (failed != null) tk.toast(Tr.t(failed));
                load[0].run();
            });
        });
        Runnable showPicked = () -> {
            stopSelected.setVisibility(picked.isEmpty() ? View.GONE : View.VISIBLE);
            stopSelected.setText(Tr.t("Stop selected") + " (" + picked.size() + ")");
            adapter.notifyDataSetChanged();
        };
        stopSelected.setOnClickListener(v -> {
            List<String> pkgs = new ArrayList<>(picked);
            if (!tk.prefs.getBoolean(PREF_STOP_ASK, true)) {
                stop.accept(pkgs);
                return;
            }
            new AlertDialog.Builder(tk)
                    .setTitle(Tr.t("Stop selected") + " (" + pkgs.size() + ")?")
                    .setMessage(Tr.t("They close now and can start again when you open them or they're needed."))
                    .setPositiveButton(Tr.t("Stop"), (d, w) -> stop.accept(pkgs))
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            MainActivity.Proc p = adapter.getItem(position);
            if (p.pkg == null) return true;
            if (!picked.remove(p.pkg)) picked.add(p.pkg);
            showPicked.run();
            return true;
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            MainActivity.Proc p = adapter.getItem(position);
            if (p.pkg == null) {
                tk.toast(Tr.t("This is a system process and can't be stopped."));
                return;
            }
            if (!picked.isEmpty()) { // choosing several: a tap adds or removes
                if (!picked.remove(p.pkg)) picked.add(p.pkg);
                showPicked.run();
                return;
            }
            if (!tk.prefs.getBoolean(PREF_STOP_ASK, true)) {
                stop.accept(java.util.Collections.singletonList(p.pkg));
                return;
            }
            new AlertDialog.Builder(tk)
                    .setTitle(Tr.t("Stop " + p.label + "?"))
                    .setMessage(Tr.t("It closes now and can start again when you open it or it's needed."))
                    .setPositiveButton(Tr.t("Stop"), (d, w) -> stop.accept(java.util.Collections.singletonList(p.pkg)))
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        load[0].run();
    }

    List<MainActivity.Proc> runningProcesses() {
        List<MainActivity.Proc> list = new ArrayList<>();
        // CPU comes from top's second sample (the first only has averages since boot), read while meminfo runs.
        String[] both = Shell.run("f=/data/local/tmp/tinykit-top; top -b -n 2 -d 1 -o PID,%CPU,ARGS >$f 2>/dev/null & "
                + "dumpsys meminfo | sed -n '/Total PSS by process/,/^$/p'; wait; echo @@; cat $f; rm -f $f").out.split("@@", 2);
        String out = both[0];
        Map<String, Float> cpu = new HashMap<>();
        if (both.length > 1) {
            for (String line : both[1].split("\n")) {
                Matcher c = MainActivity.CPUINFO.matcher(line);
                if (c.find()) cpu.put(c.group(2), Float.parseFloat(c.group(1))); // the later sample wins
            }
        }
        PackageManager pm = tk.getPackageManager();
        for (String line : out.split("\n")) {
            Matcher m = MainActivity.MEMINFO.matcher(line);
            if (!m.find()) continue;
            MainActivity.Proc p = new MainActivity.Proc();
            p.kb = Long.parseLong(m.group(1).replace(",", ""));
            p.name = m.group(2);
            if (p.name.equals("top")) continue; // the sampler started just above
            p.cpu = cpu.getOrDefault(p.name, 0f);
            String pkg = p.name.split(":")[0];
            p.label = p.name;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                p.label = String.valueOf(info.loadLabel(pm));
                boolean critical = (info.flags & ApplicationInfo.FLAG_PERSISTENT) != 0
                        || pkg.equals("android") || pkg.equals(tk.getPackageName());
                if (!critical) p.pkg = pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            list.add(p);
        }
        return list;
    }
}
