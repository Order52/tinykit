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

import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AnalyticsScreen {
    private final MainActivity tk;

    AnalyticsScreen(MainActivity tk) {
        this.tk = tk;
    }

    /** Huawei's background diagnostics and telemetry packages (matched by name). Never com.huawei.hwid. */
    static final String[] HUAWEI_DIAG = {
            "com.huawei.hiview", "hiviewtunnel", "huawei.android.chr", "hwdetectrepair", "hwdiagnosis",
            "recsys", "huawei.magazine"};

    static final String[] HICLOUD = {"hicloud"};

    void openAnalytics() {
        LinearLayout list = tk.vertical();
        list.addView(tk.note("\"Blocked\" stops the service; turn it back on here any time. "
                + "TinyKit never touches your Huawei ID sign-in."));
        tk.section(list, "Huawei");
        privacySwitch(list, "User Experience Programme",
                "Stops Huawei usage analytics and app analytics.",
                () -> {
                    String involved = sget("secure", "user_experience_involved");
                    String analytics = sget("secure", "hw_app_analytics_state");
                    // Neither key exists away from Huawei phones.
                    if (!Shell.ready() || ("null".equals(involved) && "null".equals(analytics))) return null;
                    return "0".equals(involved) && "0".equals(analytics);
                },
                block -> tk.error(Shell.run("settings put secure user_experience_involved " + (block ? 0 : 1)
                        + "; settings put secure hw_app_analytics_state " + (block ? 0 : 1))));
        privacySwitch(list, "Diagnostics & telemetry",
                "Disables Huawei's background logging and reporting apps.",
                pkgChecker(HUAWEI_DIAG), pkgApplier(HUAWEI_DIAG));
        privacySwitch(list, "HiCloud",
                "Disables HiCloud sync services. Your Huawei ID sign-in stays.",
                pkgChecker(HICLOUD), pkgApplier(HICLOUD));
        tk.section(list, "Google");
        privacySwitch(list, "Limit ad personalisation",
                "Asks apps not to use the Google advertising ID for targeting.",
                () -> "1".equals(sget("secure", "limit_ad_tracking")),
                block -> tk.error(Shell.run("settings put secure limit_ad_tracking " + (block ? 1 : 0))));
        tk.push("Analytics & telemetry", tk.scroll(list));
    }

    /** A switch backed by a worker-thread check and change, used for the analytics toggles. */
    void privacySwitch(LinearLayout list, String title, String summary, MainActivity.Checker checker, MainActivity.Applier applier) {
        Switch view = new Switch(tk);
        view.setClickable(false);
        view.setFocusable(false);
        // A read can take a second (some switches start a Java process), so reads run on their own thread,
        // never queue behind each other for the same switch, and never overwrite a newer toggle.
        java.util.concurrent.atomic.AtomicBoolean reading = new java.util.concurrent.atomic.AtomicBoolean();
        int[] toggled = {0};
        View row = tk.row(list, title, summary, view, () -> {
            boolean block = !view.isChecked();
            view.setChecked(block);
            view.setEnabled(false);
            toggled[0]++;
            tk.worker.execute(() -> {
                String err = applier.apply(block);
                Boolean now = checker.check();
                tk.main.post(() -> {
                    view.setEnabled(true);
                    if (now != null) view.setChecked(now);
                    if (err != null) tk.toast(Tr.t(err));
                });
            });
        }, true);
        TextView sub = (TextView) row.getTag();
        Runnable read = () -> {
            if (!reading.compareAndSet(false, true)) return;
            int version = toggled[0];
            tk.reads.execute(() -> {
                try {
                    if (!Shell.ready()) return; // read again once Shizuku is usable
                    Boolean state = checker.check();
                    tk.main.post(() -> {
                        if (version != toggled[0]) return;
                        showPrivacyState(row, view, sub, summary, state);
                    });
                } finally {
                    reading.set(false);
                }
            });
        };
        // Tab switches are read again whenever the settings are (Shizuku connecting, a tab opening).
        if (tk.indexingTab >= 0) tk.liveSwitches.add(read);
        read.run();
    }

    void showPrivacyState(View row, Switch view, TextView sub, String summary, Boolean state) {
        if (state == null) {
            row.setEnabled(false);
            view.setEnabled(false);
            tk.shizukuRows.remove(row);
            row.setAlpha(tk.lightTheme ? 0.45f : 0.4f);
            sub.setText(Tr.t(summary + "  ·  Not on this phone"));
        } else {
            view.setChecked(state);
        }
    }

    MainActivity.Checker pkgChecker(String[] patterns) {
        return () -> {
            List<String> pkgs = matchingPackages(patterns);
            if (pkgs.isEmpty()) return null;
            Set<String> disabled = disabledPackages();
            for (String pkg : pkgs) {
                if (!disabled.contains(pkg)) return false;
            }
            return true;
        };
    }

    MainActivity.Applier pkgApplier(String[] patterns) {
        return block -> {
            StringBuilder cmd = new StringBuilder();
            for (String pkg : matchingPackages(patterns)) {
                cmd.append(block ? "pm disable-user --user 0 " : "pm enable ").append(Shell.quote(pkg)).append("; ");
            }
            if (cmd.length() == 0) return null;
            return tk.error(Shell.run(cmd.toString()));
        };
    }

    /** Installed packages whose name contains one of the patterns, never the Huawei ID package. */
    List<String> matchingPackages(String[] patterns) {
        List<String> result = new ArrayList<>();
        for (MainActivity.App a : tk.installedApps()) {
            if (a.pkg.contains("hwid")) continue;
            for (String pattern : patterns) {
                if (a.pkg.contains(pattern)) {
                    result.add(a.pkg);
                    break;
                }
            }
        }
        return result;
    }

    Set<String> disabledPackages() {
        Set<String> set = new HashSet<>();
        for (String line : Shell.run("pm list packages -d | sed 's/^package://'").out.split("\n")) {
            String pkg = line.trim();
            if (!pkg.isEmpty()) set.add(pkg);
        }
        return set;
    }

    String sget(String table, String key) {
        return Shell.run("settings get " + table + " " + key).out.trim();
    }
}
