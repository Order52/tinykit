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
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class FirewallScreen {
    private final MainActivity tk;

    FirewallScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openFirewall() {
        if (!FirewallPolicy.supported()) {
            tk.toast(Tr.t("Blocking an app's internet needs Android 14 or newer."));
            return;
        }
        LinearLayout list = tk.vertical();
        TextView status = tk.text("", 14, true);
        status.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), tk.dp(4));
        list.addView(status);
        list.addView(tk.note("Blocked apps can't use Wi-Fi or mobile data. Android itself enforces it, so "
                + "TinyKit runs nothing in the background unless you turn on \"Run in the background\" below, "
                + "and the block comes back by itself after a restart as soon as Shizuku is running again."));
        Switch modeSwitch = new Switch(tk);
        modeSwitch.setClickable(false);
        modeSwitch.setFocusable(false);
        Switch pauseSwitch = new Switch(tk);
        pauseSwitch.setClickable(false);
        pauseSwitch.setFocusable(false);
        Switch watchSwitch = new Switch(tk);
        watchSwitch.setClickable(false);
        watchSwitch.setFocusable(false);
        TextView watchStatus = tk.text("", 13, false);
        watchStatus.setPadding(tk.dp(16), tk.dp(0), tk.dp(16), tk.dp(8));
        View[] choose = new View[1];
        Runnable refresh = () -> {
            boolean allow = FirewallPolicy.allowMode(tk.prefs);
            modeSwitch.setChecked(allow);
            boolean watch = BackgroundService.on(tk.prefs);
            watchSwitch.setChecked(watch);
            pauseSwitch.setChecked(FirewallPolicy.paused(tk.prefs));
            watchStatus.setText(Tr.t(BackgroundService.installsRunning()
                    ? "Running. New apps are blocked the moment they are installed."
                    : watch ? (FirewallPolicy.configured(tk.prefs) ? "Starting…"
                    : "On. Nothing to watch until you block an app or turn on allow list mode.")
                    : "Off. Apps you install are blocked the next time you open TinyKit."));
            ((TextView) choose[0].getTag()).setText(Tr.t(allow
                    ? "Pick the apps that may use the internet. Everything else is blocked."
                    : "Pick the apps to cut off."));
            status.setText(Tr.t("…"));
            tk.worker.execute(() -> {
                int n = FirewallPolicy.blockedCount(tk, tk.prefs);
                tk.main.post(() -> status.setText(Tr.t(FirewallPolicy.paused(tk.prefs)
                        ? "Paused. No app is blocked until you resume." : n == 0 ? "No app is blocked."
                        : n + (n == 1 ? " app is" : " apps are") + " blocked.")));
            });
        };
        choose[0] = tk.row(list, "Choose apps", "", tk.arrow(), this::openFirewallApps, true);
        tk.row(list, "Allow list mode", "Block every app except the ones you allow. Apps you install later are "
                + "blocked when you next open TinyKit, or at once with \"Run in the background\" below. Allow Google Play "
                + "services if you want push notifications.", modeSwitch, () -> {
                    boolean on = !FirewallPolicy.allowMode(tk.prefs);
                    if (!on) {
                        setFirewallMode(false, refresh);
                        return;
                    }
                    new AlertDialog.Builder(tk)
                            .setTitle(Tr.t("Block everything except allowed apps?"))
                            .setMessage(Tr.t("Every app that isn't on your allow list loses Wi-Fi and mobile data "
                                    + "right away, including system apps like Google Play services. "
                                    + "Pick the apps to allow next."))
                            .setPositiveButton(Tr.t("Turn on"), (d, w) -> setFirewallMode(true, refresh))
                            .setNegativeButton(Tr.t("Cancel"), null)
                            .show();
                }, true);
        tk.row(list, "Pause blocking", "Let every app use the internet for now and keep your lists. Also on the "
                + "Internet block quick tile.", pauseSwitch, () -> {
                    boolean on = !FirewallPolicy.paused(tk.prefs);
                    tk.prefs.edit().putBoolean(FirewallPolicy.PAUSED, on).apply();
                    tk.worker.execute(() -> {
                        String error = FirewallPolicy.apply(tk, tk.prefs, true);
                        tk.main.post(() -> {
                            refresh.run();
                            if (error != null) tk.toast(Tr.t(error));
                        });
                    });
                }, true);
        tk.row(list, "Run in the background", "Blocks a new app the moment you install it, with a quiet "
                + "notification. This is the same switch as in the Info tab, shared with Shut Up. It uses almost "
                + "no battery or memory.", watchSwitch, () -> {
                    boolean on = !BackgroundService.on(tk.prefs);
                    watchSwitch.setChecked(on);
                    tk.worker.execute(() -> {
                        BackgroundService.setEnabled(tk, tk.prefs, on);
                        tk.afterServiceChange(refresh);
                    });
                }, true);
        list.addView(watchStatus);
        tk.action(list, "Unblock all apps", "Let every app use the internet again and turn allow list mode off.", () -> {
            tk.prefs.edit().putStringSet(FirewallPolicy.BLOCKED, new HashSet<>())
                    .putBoolean(FirewallPolicy.MODE, false).apply();
            String error = FirewallPolicy.apply(tk, tk.prefs, true);
            tk.main.post(refresh);
            return error == null ? "Every app can use the internet." : error;
        });
        View page = tk.scroll(list);
        page.setTag(refresh);
        tk.push("Block internet per app", page);
        refresh.run();
    }

    void setFirewallMode(boolean allow, Runnable refresh) {
        tk.prefs.edit().putBoolean(FirewallPolicy.MODE, allow).apply();
        tk.worker.execute(() -> {
            String error = FirewallPolicy.apply(tk, tk.prefs, true);
            tk.main.post(() -> {
                refresh.run();
                if (error != null) tk.toast(Tr.t(error));
            });
        });
    }

    void openFirewallApps() {
        boolean allow = FirewallPolicy.allowMode(tk.prefs);
        tk.openPicker(allow ? "Allowed apps" : "Blocked apps",
                allow ? "Checked apps may use Wi-Fi and mobile data. Every other app is blocked."
                        : "Checked apps can't use Wi-Fi or mobile data. It takes effect at once.",
                true,
                () -> {
                    Set<String> installed = FirewallPolicy.uids(tk).keySet();
                    Set<String> checked = FirewallPolicy.saved(tk.prefs, allow ? FirewallPolicy.ALLOWED
                            : FirewallPolicy.BLOCKED);
                    checked.retainAll(installed);
                    return checked;
                },
                (pkg, on) -> {
                    String error = FirewallPolicy.choose(tk, tk.prefs, pkg, on);
                    if (error != null) return error;
                    // Apps from one developer can share an id, and the rule covers all of them.
                    List<String> same = new ArrayList<>();
                    for (String other : FirewallPolicy.sharing(tk, pkg)) same.add(tk.label(other));
                    if (!same.isEmpty()) {
                        tk.main.post(() -> tk.toast(Tr.t("Also " + (on != allow ? "blocked: " : "allowed: ")
                                + TextUtils.join(", ", same))));
                    }
                    return null;
                }, null, true);
    }
}
