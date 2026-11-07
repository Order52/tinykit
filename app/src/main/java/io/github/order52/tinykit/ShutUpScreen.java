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

final class ShutUpScreen {
    private final MainActivity tk;

    ShutUpScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openShutUp() {
        LinearLayout list = tk.vertical();
        list.addView(tk.note("Some apps refuse to work, or nag you, when Developer options or USB or Wireless "
                + "debugging are on. Choose those apps here. While one of them is on screen, TinyKit turns the "
                + "three settings off, and it turns back on whatever was on the moment you leave the app. "
                + "Android can't hide a setting from one app only, so every app sees it off during that time. "
                + "An app that checks in its first moments, before TinyKit notices it, or that remembers an "
                + "earlier check, can still see it."));
        Switch onSwitch = new Switch(tk);
        onSwitch.setClickable(false);
        onSwitch.setFocusable(false);
        TextView status = tk.text("", 13, false);
        status.setPadding(tk.dp(16), tk.dp(0), tk.dp(16), tk.dp(8));
        View[] choose = new View[1];
        Runnable refresh = () -> {
            boolean on = tk.prefs.getBoolean(ShutUp.PREF_ON, false);
            boolean background = BackgroundService.on(tk.prefs);
            onSwitch.setChecked(on);
            int n = ShutUp.apps(tk.prefs).size();
            ((TextView) choose[0].getTag()).setText(Tr.t(n == 0 ? "No app chosen yet."
                    : n + (n == 1 ? " app chosen." : " apps chosen.")));
            boolean paused = on && !background;
            status.setTextColor(paused ? tk.attrColor(android.R.attr.colorAccent) : (tk.lightTheme ? 0xFF000000 : 0xB3FFFFFF));
            status.setText(Tr.t(BackgroundService.shutUpRunning()
                    ? (ShutUp.hidden(tk.prefs) ? "Running. Hidden right now, because a chosen app is open."
                    : "Running. Nothing is hidden until you open a chosen app.")
                    : paused ? "Paused, because Run in the background is off. Tap here to turn it back on."
                    : on ? (n == 0 ? "On. Choose at least one app above."
                    : !ShutUp.ready(tk) ? "Waiting: start Shizuku once so TinyKit can give itself the access Shut Up needs."
                    : "Starting…")
                    : "Off. TinyKit changes nothing and runs nothing."));
        };
        status.setOnClickListener(v -> {
            if (!tk.prefs.getBoolean(ShutUp.PREF_ON, false) || BackgroundService.on(tk.prefs)) return;
            tk.worker.execute(() -> {
                BackgroundService.setEnabled(tk, tk.prefs, true);
                tk.afterServiceChange(refresh);
            });
        });
        choose[0] = tk.row(list, "Choose apps", "", tk.arrow(), () -> tk.openPicker("Shut Up apps",
                "Checked apps see Developer options, USB debugging and Wireless debugging as off while they "
                        + "are open.", true,
                () -> ShutUp.apps(tk.prefs),
                (pkg, on) -> ShutUp.choose(tk, tk.prefs, pkg, on)), false);
        tk.row(list, "Shut Up", "Watches which app is open (a couple of times a second, only while the screen is "
                + "on) with a quiet notification. It uses very little battery. Turning it on also turns on Run "
                + "in the background (Info tab), which it needs.", onSwitch, () -> {
                    boolean on = !tk.prefs.getBoolean(ShutUp.PREF_ON, false);
                    onSwitch.setChecked(on);
                    tk.worker.execute(() -> {
                        String error = ShutUp.setEnabled(tk, tk.prefs, on);
                        tk.afterServiceChange(() -> {
                            refresh.run();
                            if (error != null) tk.toast(Tr.t(error));
                        });
                    });
                }, false);
        list.addView(status);
        Switch stopSwitch = new Switch(tk);
        stopSwitch.setClickable(false);
        stopSwitch.setFocusable(false);
        TextView stopStatus = tk.text("", 13, false);
        stopStatus.setPadding(tk.dp(16), tk.dp(0), tk.dp(16), tk.dp(8));
        Runnable showStop = () -> {
            boolean can = ShizukuControl.canRestart(tk, tk.prefs);
            stopSwitch.setChecked(tk.prefs.getBoolean(ShutUp.PREF_STOP_SHIZUKU, true) && can);
            stopStatus.setText(Tr.t(can ? "TinyKit can start Shizuku again by itself."
                    : "Not available: TinyKit has no way to start Shizuku by itself. Save Shizuku's auth token "
                    + "(Shizuku tab, for a Shizuku fork with start and stop intents), or tap Start once so "
                    + "TinyKit pairs with Wireless debugging (Android 11+)."));
        };
        tk.row(list, "Stop Shizuku while they're open", "Some apps notice Shizuku running. With this on, TinyKit "
                + "stops Shizuku while a chosen app is open and starts it again about 15 seconds after you "
                + "leave. Turning USB or Wireless debugging off can end Shizuku too, so TinyKit also starts it "
                + "again after that. It only does this when it can start Shizuku by itself.", stopSwitch, () -> {
                    if (!ShizukuControl.canRestart(tk, tk.prefs)) {
                        tk.toast(Tr.t("TinyKit has no way to start Shizuku by itself yet. See the note below."));
                        showStop.run();
                        return;
                    }
                    tk.prefs.edit().putBoolean(ShutUp.PREF_STOP_SHIZUKU,
                            !tk.prefs.getBoolean(ShutUp.PREF_STOP_SHIZUKU, true)).apply();
                    showStop.run();
                }, false);
        list.addView(stopStatus);
        showStop.run();
        View page = tk.scroll(list);
        page.setTag((Runnable) () -> {
            refresh.run();
            showStop.run();
        });
        tk.push("Shut Up", page);
        refresh.run();
    }
}
