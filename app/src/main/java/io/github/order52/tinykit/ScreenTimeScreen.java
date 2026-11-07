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

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ScreenTimeScreen {
    private final MainActivity tk;

    ScreenTimeScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openScreenTime() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView total = tk.text("", 16, true);
        page.addView(total);
        TextView help = tk.text("How long each app was on your screen, and how often you opened it. Read from "
                + "Android's usage history, so TinyKit measures nothing itself.", 13, false);
        tk.setDim(help, 0.7f);
        page.addView(help);
        TextView loading = tk.text("Reading usage…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        MainActivity.Adapter<String[]> adapter = tk.new Adapter<>(false, (a, row) -> {
            row.first.setText(Tr.t(a[1]));
            row.second.setText(Tr.t(a[2]));
            tk.showIcon(row, a[0]);
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, v, position, id) -> tk.openAppInfo(adapter.getItem(position)[0]));
        final int[] range = {0};
        Runnable load = () -> {
            loading.setText(Tr.t("Reading usage…"));
            loading.setVisibility(View.VISIBLE);
            tk.worker.execute(() -> {
                if (!Tweak.usageAllowed(tk)) Tweak.grantSelf(tk);
                if (!Tweak.usageAllowed(tk)) {
                    tk.main.post(() -> loading.setText(Tr.t("TinyKit needs usage access. Start Shizuku once and "
                            + "reopen this page, or allow it in Settings > Apps > Special access.")));
                    return;
                }
                Calendar midnight = Calendar.getInstance();
                midnight.set(Calendar.HOUR_OF_DAY, 0);
                midnight.set(Calendar.MINUTE, 0);
                midnight.set(Calendar.SECOND, 0);
                midnight.set(Calendar.MILLISECOND, 0);
                long now = System.currentTimeMillis();
                long today = midnight.getTimeInMillis();
                // Whole days, because Android keeps its usage totals per day.
                long from = range[0] == 2 ? today - 6 * DAY : range[0] == 1 ? today - DAY : today;
                long to = range[0] == 1 ? today - 1 : now;
                long[] sum = {0};
                List<String[]> rows = screenTime(from, to, sum);
                tk.main.post(() -> {
                    total.setText(Tr.t(sum[0] == 0 ? "" : "Total on screen: " + duration(sum[0])));
                    loading.setText(Tr.t("No app use recorded in this time."));
                    loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                    adapter.set(rows);
                });
            });
        };
        page.addView(tk.sortBarLike(tk.chip("", "Show", new String[]{"Today", "Yesterday", "Last 7 days"}, i -> {
            range[0] = i;
            load.run();
        })), 1);
        tk.push("Screen time", page);
        load.run();
    }

    static final long DAY = 86_400_000L;

    static String duration(long ms) {
        long minutes = Math.round(ms / 60000.0);
        if (minutes < 1) return ms < 1000 ? "0 min" : "under a minute";
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    /** Rows {package, label, "2 h 5 min · 12 opens"}, longest first; sum[0] gets the total. Off the main thread. */
    List<String[]> screenTime(long from, long to, long[] sum) {
        UsageStatsManager usm = (UsageStatsManager) tk.getSystemService(Context.USAGE_STATS_SERVICE);
        // Android adds up the foreground time itself, which copes with apps that were killed while
        // open. Only whole-day totals inside the range are used, so a week-long total can't leak in.
        Map<String, Long> stats = new HashMap<>();
        List<UsageStats> days = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to);
        if (days != null) {
            for (UsageStats d : days) {
                if (d.getFirstTimeStamp() < from - 3_600_000L || d.getLastTimeStamp() > to + DAY) continue;
                Long before = stats.get(d.getPackageName());
                stats.put(d.getPackageName(), (before == null ? 0 : before) + d.getTotalTimeInForeground());
            }
        }
        // Opens aren't kept as a number, so count how often each app came to the front.
        Map<String, Integer> opens = new HashMap<>();
        android.app.usage.UsageEvents events = usm.queryEvents(from, to);
        android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
        String last = "";
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            // 1 is "activity resumed", the long-standing value of the foreground event.
            if (e.getEventType() != 1 || e.getPackageName().equals(last)) continue;
            last = e.getPackageName();
            opens.put(last, (opens.containsKey(last) ? opens.get(last) : 0) + 1);
        }
        List<String[]> rows = new ArrayList<>();
        List<long[]> order = new ArrayList<>();
        for (Map.Entry<String, Long> s : stats.entrySet()) {
            long spent = s.getValue();
            if (spent < 1000) continue;
            int n = opens.containsKey(s.getKey()) ? opens.get(s.getKey()) : 0;
            rows.add(new String[]{s.getKey(), tk.label(s.getKey()),
                    duration(spent) + (n == 0 ? "" : " · " + n + (n == 1 ? " open" : " opens"))});
            order.add(new long[]{rows.size() - 1, spent});
            sum[0] += spent;
        }
        Collections.sort(order, (x, y) -> Long.compare(y[1], x[1]));
        List<String[]> sorted = new ArrayList<>();
        for (long[] o : order) sorted.add(rows.get((int) o[0]));
        return sorted;
    }
}
