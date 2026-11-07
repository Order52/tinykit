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
import android.view.View;
import android.widget.Button;
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

final class ServicesScreen {
    private final MainActivity tk;

    ServicesScreen(MainActivity tk) {
        this.tk = tk;
    }

    static final String PREF_BLOCKED = "svc_blocked";

    void openServices() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView summary = tk.text("Reading…", 15, true);
        page.addView(summary);
        TextView help = tk.text("Tap a service to stop it, stop its app, or block it. Stopping can be undone by the app; "
                + "blocking can't, until you unblock it.", 13, false);
        tk.setDim(help, 0.7f);
        page.addView(help);
        EditText search = tk.searchField("Search services");
        page.addView(search, tk.fieldMargin());
        CheckBox system = new CheckBox(tk);
        system.setText(Tr.t("Show system services"));
        page.addView(system);
        Button blockedButton = tk.button("", v -> showBlocked());
        page.addView(blockedButton);
        ListView listView = new ListView(tk);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push("Running services", page);

        List<Services.Service> all = new ArrayList<>();
        Set<String> systemApps = new HashSet<>();
        MainActivity.Adapter<Services.Service> adapter = tk.new Adapter<>(false, (sv, row) -> {
            row.first.setText(tk.label(sv.pkg) + (sv.user != 0 ? " (" + Tr.t("Work profile") + ")" : ""));
            row.second.setText(sv.shortName());
            row.third.setText((sv.foreground ? Tr.t("Foreground") + " · " : "") + (sv.started ? Tr.t("Started") + " · " : "")
                    + (sv.bound ? Tr.t("Bound") + " · " : "") + sv.age);
            row.third.setTextColor(sv.foreground ? tk.attrColor(android.R.attr.colorAccent) : row.second.getCurrentTextColor());
            tk.showIcon(row, sv.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<Services.Service> shown = new ArrayList<>();
            for (Services.Service sv : all) {
                if (!system.isChecked() && systemApps.contains(sv.pkg)) continue;
                if (q.isEmpty() || sv.cls.toLowerCase(Locale.ROOT).contains(q) || sv.pkg.toLowerCase(Locale.ROOT).contains(q)
                        || tk.label(sv.pkg).toLowerCase(Locale.ROOT).contains(q)) shown.add(sv);
            }
            adapter.set(shown);
            String pattern = Tr.t(shown.size() == 1 ? "%d service running" : "%d services running");
            summary.setText(pattern.contains("%d") ? String.format(Locale.getDefault(), pattern, shown.size())
                    : shown.size() + " " + pattern);
        };
        search.addTextChangedListener(tk.watcher(filter));
        system.setOnCheckedChangeListener((v, on) -> filter.run());
        Runnable[] load = new Runnable[1];
        load[0] = () -> tk.worker.execute(() -> {
            List<Services.Service> found = Services.running();
            for (MainActivity.App a : tk.installedApps()) if (a.system) systemApps.add(a.pkg);
            tk.main.post(() -> {
                all.clear();
                all.addAll(found);
                int blocked = tk.prefs.getStringSet(PREF_BLOCKED, new HashSet<>()).size();
                blockedButton.setVisibility(blocked == 0 ? View.GONE : View.VISIBLE);
                blockedButton.setText(Tr.t("Blocked services") + " (" + blocked + ")");
                filter.run();
            });
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Services.Service sv = adapter.getItem(position);
            new AlertDialog.Builder(tk)
                    .setTitle(tk.label(sv.pkg))
                    .setMessage(sv.cls)
                    .setItems(Tr.all(new String[]{"Stop service", "Stop app", "Block service"}), (d, which) -> {
                        if (which == 2) {
                            new AlertDialog.Builder(tk)
                                    .setTitle(Tr.t("Block this service?"))
                                    .setMessage(Tr.t("The app can't start it any more, and may stop working properly. "
                                            + "You can unblock it from this page."))
                                    .setPositiveButton(Tr.t("Block"), (x, y) -> tk.worker.execute(() -> {
                                        boolean ok = Services.block(sv, true);
                                        if (ok) {
                                            Set<String> set = new HashSet<>(tk.prefs.getStringSet(PREF_BLOCKED, new HashSet<>()));
                                            set.add(sv.user + "|" + sv.component());
                                            tk.prefs.edit().putStringSet(PREF_BLOCKED, set).apply();
                                            Services.stop(sv);
                                        }
                                        tk.main.post(() -> {
                                            tk.toast(Tr.t(ok ? "Blocked." : "Couldn't block it."));
                                            load[0].run();
                                        });
                                    }))
                                    .setNegativeButton(Tr.t("Cancel"), null)
                                    .show();
                            return;
                        }
                        tk.worker.execute(() -> {
                            String error = which == 0 ? Services.stop(sv)
                                    : tk.error(Shell.run("am force-stop --user " + sv.user + " " + Shell.quote(sv.pkg)));
                            tk.main.post(() -> {
                                tk.toast(Tr.t(error == null ? "Done." : error));
                                load[0].run();
                            });
                        });
                    })
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        load[0].run();
    }

    /** The services blocked here, each one to be switched back on with a tap. */
    void showBlocked() {
        List<String> items = new ArrayList<>(tk.prefs.getStringSet(PREF_BLOCKED, new HashSet<>()));
        Collections.sort(items);
        String[] names = new String[items.size()];
        for (int i = 0; i < names.length; i++) names[i] = items.get(i).substring(items.get(i).indexOf('|') + 1);
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t("Tap one to unblock it"))
                .setItems(names, (d, which) -> tk.worker.execute(() -> {
                    String item = items.get(which);
                    Services.Service sv = new Services.Service();
                    sv.user = Integer.parseInt(item.substring(0, item.indexOf('|')));
                    String component = item.substring(item.indexOf('|') + 1);
                    sv.pkg = component.substring(0, component.indexOf('/'));
                    sv.cls = component.substring(component.indexOf('/') + 1);
                    boolean ok = Services.block(sv, false);
                    if (ok) {
                        Set<String> set = new HashSet<>(tk.prefs.getStringSet(PREF_BLOCKED, new HashSet<>()));
                        set.remove(item);
                        tk.prefs.edit().putStringSet(PREF_BLOCKED, set).apply();
                    }
                    tk.main.post(() -> tk.toast(Tr.t(ok ? "Unblocked." : "Couldn't unblock it.")));
                }))
                .setNegativeButton(Tr.t("Close"), null)
                .show();
    }
}
