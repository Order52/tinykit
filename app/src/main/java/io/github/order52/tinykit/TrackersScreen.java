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
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

final class TrackersScreen {
    private final MainActivity tk;

    TrackersScreen(MainActivity tk) {
        this.tk = tk;
    }

    /**
     * Which ad and analytics libraries each app carries, read from the parts it declares. Android
     * doesn't let the shell turn individual parts off ("Shell cannot change component state"), so
     * this finds them and offers what does work: cutting the app off the internet.
     */
    void openTrackers() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView help = tk.text("Ad and analytics libraries found inside your apps, most first. This is what each "
                + "app declares, not proof it uses them. Tap an app to see them" + (FirewallPolicy.supported()
                ? " or cut it off the internet." : "."), 13, false);
        tk.setDim(help, 0.8f);
        page.addView(help);
        CheckBox system = new CheckBox(tk);
        system.setText(Tr.t("Show system apps"));
        page.addView(system);
        TextView loading = tk.text("Scanning apps…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push("Trackers in apps", page);

        // {package, label, "A, B, C", count, system}
        List<String[]> all = new ArrayList<>();
        MainActivity.Adapter<String[]> adapter = tk.new Adapter<>(false, (t, row) -> {
            row.first.setText(Tr.t(t[1]));
            row.second.setText(Tr.t(t[3] + (t[3].equals("1") ? " tracker" : " trackers")));
            row.third.setText(Tr.t(t[2]));
            tk.showIcon(row, t[0]);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            List<String[]> shown = new ArrayList<>();
            for (String[] t : all) if (system.isChecked() || t[4].equals("0")) shown.add(t);
            adapter.set(shown);
            loading.setText(Tr.t("No trackers found."));
            loading.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        };
        system.setOnCheckedChangeListener((v, on) -> filter.run());
        tk.worker.execute(() -> {
            PackageManager pm = tk.getPackageManager();
            List<String[]> found = new ArrayList<>();
            for (MainActivity.App a : tk.installedApps()) {
                Set<String> libs = new TreeSet<>();
                try {
                    PackageInfo info = pm.getPackageInfo(a.pkg, PackageManager.GET_ACTIVITIES
                            | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
                    for (ComponentInfo[] group : new ComponentInfo[][]{info.activities, info.services,
                            info.receivers, info.providers}) {
                        if (group == null) continue;
                        for (ComponentInfo ci : group) {
                            String lib = Trackers.find(ci.name);
                            if (lib != null) libs.add(lib);
                        }
                    }
                } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                    continue;
                }
                if (libs.isEmpty()) continue;
                found.add(new String[]{a.pkg, a.label, TextUtils.join(", ", libs), String.valueOf(libs.size()),
                        a.system ? "1" : "0"});
            }
            Collections.sort(found, (x, y) -> Integer.parseInt(y[3]) - Integer.parseInt(x[3]));
            tk.main.post(() -> {
                all.clear();
                all.addAll(found);
                filter.run();
            });
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String[] t = adapter.getItem(position);
            AlertDialog.Builder d = new AlertDialog.Builder(tk)
                    .setTitle(Tr.t(t[1]))
                    .setMessage(Tr.t(t[2] + "\n\nThese are declared inside " + t[0] + ". TinyKit can't switch them off "
                            + "one by one (Android doesn't allow it from the shell)."))
                    .setNegativeButton(Tr.t("Close"), null);
            if (FirewallPolicy.supported() && Shell.ready()) {
                d.setPositiveButton(Tr.t("Block internet"), (x, w) -> tk.worker.execute(() -> {
                    String error = FirewallPolicy.block(tk, tk.prefs, t[0]);
                    tk.main.post(() -> tk.toast(Tr.t(error != null ? error : FirewallPolicy.paused(tk.prefs)
                            ? t[1] + " will be blocked from the internet when you resume blocking."
                            : t[1] + " is blocked from the internet.")));
                }));
            }
            d.show();
        });
    }
}
