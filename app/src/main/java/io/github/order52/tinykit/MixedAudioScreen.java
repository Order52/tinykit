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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MixedAudioScreen {
    private final MainActivity tk;

    MixedAudioScreen(MainActivity tk) {
        this.tk = tk;
    }

    /**
     * Lets apps play audio at the same time, or mutes an app. Both are app-op changes
     * (TAKE_AUDIO_FOCUS and PLAY_AUDIO) that the audio system enforces — nothing runs in the
     * background, uses no memory or battery once set.
     */
    void openMixedAudio() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView help = tk.text("Let apps play audio together, or mute an app. These are app-op changes; "
                + "nothing runs in the background.", 13, false);
        tk.setDim(help, 0.7f);
        page.addView(help);
        EditText search = tk.searchField("Search apps");
        page.addView(search, tk.fieldMargin());
        CheckBox system = new CheckBox(tk);
        system.setText(Tr.t("Show system apps"));
        page.addView(system);
        final int[] sort = {0};
        ListView listView = new ListView(tk);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push("Mixed audio", page);

        tk.audioState.clear();
        List<MainActivity.App> all = new ArrayList<>();
        MainActivity.Adapter<MainActivity.App> adapter = tk.new Adapter<>(false, (app, row) -> {
            row.first.setText(Tr.t(app.label));
            row.second.setText(Tr.t(audioStateText(app.pkg)));
            tk.showIcon(row, app.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<MainActivity.App> shown = new ArrayList<>();
            for (MainActivity.App a : all) {
                boolean set = tk.audioState.containsKey(a.pkg);
                if ((system.isChecked() || !a.system || set)
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
        listView.setOnItemClickListener((parent, view, position, id) ->
                showAudioDialog(adapter.getItem(position), filter));
        Runnable show = () -> {
            all.clear();
            for (MainActivity.App a : tk.installedApps()) all.add(a.copy());
            filter.run();
        };
        if (tk.appsLoaded()) show.run();
        else tk.worker.execute(() -> {
            tk.installedApps();
            tk.main.post(show);
        });
        loadAudioState(filter);
    }

    String audioStateText(String pkg) {
        int[] st = tk.audioState.get(pkg);
        boolean muted = st != null && st[0] == 1;
        boolean mixed = st != null && st[1] == 1;
        if (muted && mixed) return "Muted  ·  plays with others";
        if (muted) return "Muted";
        if (mixed) return "Plays with others";
        return pkg;
    }

    /** Reads which apps are muted or set to mix, then runs {@code after} on the main thread. */
    void loadAudioState(Runnable after) {
        tk.worker.execute(() -> {
            String out = Shell.run("echo '@M'; appops query-op PLAY_AUDIO deny; "
                    + "echo '@F'; appops query-op TAKE_AUDIO_FOCUS ignore; "
                    + "appops query-op TAKE_AUDIO_FOCUS deny").out;
            Map<String, int[]> state = new HashMap<>();
            int section = 0;
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.equals("@M")) {
                    section = 1;
                    continue;
                }
                if (line.equals("@F")) {
                    section = 2;
                    continue;
                }
                if (!MainActivity.PACKAGE.matcher(line).matches()) continue;
                int[] st = state.get(line);
                if (st == null) state.put(line, st = new int[2]);
                if (section == 1) st[0] = 1;
                else if (section == 2) st[1] = 1;
            }
            tk.main.post(() -> {
                tk.audioState.clear();
                tk.audioState.putAll(state);
                after.run();
            });
        });
    }

    void showAudioDialog(MainActivity.App app, Runnable refresh) {
        int[] st = tk.audioState.get(app.pkg);
        boolean muted = st != null && st[0] == 1;
        boolean mixed = st != null && st[1] == 1;
        String q = Shell.quote(app.pkg);
        LinkedHashMap<String, String> actions = new LinkedHashMap<>();
        actions.put(muted ? "Unmute audio" : "Mute audio",
                "appops set " + q + " PLAY_AUDIO " + (muted ? "allow" : "deny"));
        actions.put(mixed ? "Play normally (take focus)" : "Let it play with others",
                "appops set " + q + " TAKE_AUDIO_FOCUS " + (mixed ? "allow" : "ignore"));
        String[] labels = actions.keySet().toArray(new String[0]);
        String[] cmds = actions.values().toArray(new String[0]);
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t(app.label))
                .setItems(Tr.all(labels), (d, which) -> tk.worker.execute(() -> {
                    String err = tk.error(Shell.run(cmds[which]));
                    if (err != null) tk.main.post(() -> tk.toast(Tr.t(err)));
                    loadAudioState(refresh);
                }))
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }
}
