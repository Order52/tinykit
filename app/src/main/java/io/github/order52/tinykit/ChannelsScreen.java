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

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ChannelsScreen {
    private final MainActivity tk;

    ChannelsScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openChannelsApp() {
        if (Build.VERSION.SDK_INT < 26) {
            tk.toast(Tr.t("Notification channels need Android 8 or newer."));
            return;
        }
        tk.openAppList("Notification channels", "Pick an app to choose which kinds of notification it may show.",
                this::openChannels);
    }

    static String channelsCmd(Context c, String args) {
        return "CLASSPATH=" + c.getPackageCodePath()
                + " app_process /system/bin io.github.order52.tinykit.Channels " + args;
    }

    static String importanceName(int importance) {
        switch (importance) {
            case 0: return "Blocked";
            case 1: return "Silent, hidden";
            case 2: return "Silent";
            case 3: return "Sound";
            default: return "Sound and pop-up";
        }
    }

    void openChannels(String pkg) {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView help = tk.text("Tick = the app may show this kind of notification. Untick to block just that kind. "
                + "Some system channels can't be blocked.", 13, false);
        tk.setDim(help, 0.8f);
        page.addView(help);
        TextView loading = tk.text("Reading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push(tk.label(pkg), page);

        // {id, name, importance}
        MainActivity.Adapter<String[]> adapter = tk.new Adapter<>(true, (ch, row) -> {
            row.first.setText(Tr.t(ch[1]));
            row.second.setText(Tr.t(importanceName(Integer.parseInt(ch[2]))));
            row.check.setChecked(!ch[2].equals("0"));
        });
        listView.setAdapter(adapter);
        int uid;
        try {
            uid = tk.getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            loading.setText(Tr.t("This app isn't installed any more."));
            return;
        }
        boolean[] busy = {false};
        Runnable load = () -> tk.worker.execute(() -> {
            Shell.Result r = Shell.run(channelsCmd(tk, "list " + Shell.quote(pkg) + " " + uid));
            List<String[]> rows = new ArrayList<>();
            if (r.ok()) {
                for (String line : r.out.split("\n")) {
                    String[] f = line.split("\t");
                    if (f.length == 3) rows.add(f);
                }
            }
            Collections.sort(rows, (x, y) -> x[1].compareToIgnoreCase(y[1]));
            tk.main.post(() -> {
                loading.setText(Tr.t(r.ok() ? "This app hasn't created any notification channels yet."
                        : "Couldn't read them: " + r.out));
                loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.set(rows);
                busy[0] = false;
            });
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            // One change at a time: the row shows the old state until the list is read again.
            if (busy[0]) return;
            busy[0] = true;
            String[] ch = adapter.getItem(position);
            boolean blocked = ch[2].equals("0");
            String key = "chan_" + pkg + "|" + ch[0];
            tk.worker.execute(() -> {
                // Remember how loud it was so turning it back on restores exactly that.
                int target = blocked ? tk.prefs.getInt(key, 3) : 0;
                if (!blocked) tk.prefs.edit().putInt(key, Integer.parseInt(ch[2])).apply();
                Shell.Result r = Shell.run(channelsCmd(tk, "set " + Shell.quote(pkg) + " " + uid + " "
                        + Shell.quote(ch[0]) + " " + target));
                tk.main.post(() -> {
                    if (!r.ok()) tk.toast(Tr.t(r.out));
                    load.run();
                });
            });
        });
        load.run();
    }
}
