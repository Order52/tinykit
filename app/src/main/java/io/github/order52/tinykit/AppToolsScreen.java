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

import android.content.Intent;
import android.content.pm.ResolveInfo;
import java.util.HashSet;
import java.util.Set;

final class AppToolsScreen {
    private final MainActivity tk;

    AppToolsScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openBackground() {
        tk.openPicker("Stop background activity",
                "Checked apps are restricted like Settings > Battery > Restricted: no background work, "
                        + "alarms or wake-locks until you open them. Their notifications may arrive late, "
                        + "so leave messengers unchecked.", true,
                () -> tk.packages(Shell.run("appops query-op RUN_ANY_IN_BACKGROUND ignore").out),
                (pkg, on) -> {
                    String q = Shell.quote(pkg);
                    String mode = on ? "ignore" : "allow";
                    // The standby bucket is Android 9+; where it's missing the command just fails, so
                    // it goes first and the app-ops below decide the result.
                    return tk.error(Shell.run("am set-standby-bucket " + q + (on ? " restricted" : " active")
                            + " >/dev/null 2>&1; appops set " + q + " RUN_ANY_IN_BACKGROUND " + mode
                            + "; appops set " + q + " WAKE_LOCK " + mode));
                });
    }

    /** Remembers Force dark so {@link Restore} can switch it back on after a restart. */
    String forceDark(boolean on, Shell.Result r) {
        if (r.ok()) tk.prefs.edit().putBoolean(Restore.PREF_FORCE_DARK, on).apply();
        return tk.error(r);
    }

    /** Remembers Disable HW overlays so {@link Restore} can switch it back on after a restart. */
    String noOverlays(boolean on, Shell.Result r) {
        if (r.ok()) tk.prefs.edit().putBoolean(Restore.PREF_NO_OVERLAYS, on).apply();
        return tk.error(r);
    }

    void openKeepAlive() {
        tk.openPicker("Keep apps alive",
                "Checked apps are exempt from battery optimization and background limits, so "
                        + "Android doesn't kill them or delay their notifications.", true,
                () -> {
                    Set<String> set = new HashSet<>();
                    for (String line : Shell.run("dumpsys deviceidle whitelist").out.split("\n")) {
                        String[] parts = line.trim().split(",");
                        if (parts.length >= 2 && parts[0].equals("user")) set.add(parts[1]);
                    }
                    return set;
                },
                (pkg, on) -> {
                    String q = Shell.quote(pkg);
                    return tk.error(Shell.run(on
                            ? "dumpsys deviceidle whitelist +" + q
                                    + "; appops set " + q + " RUN_ANY_IN_BACKGROUND allow"
                                    + "; appops set " + q + " WAKE_LOCK allow"
                            : "dumpsys deviceidle whitelist -" + q));
                });
    }

    void openSharePins() {
        tk.openPicker("Share menu pins",
                "The first three apps you check show in the top row of the share menu. Up to "
                        + ShareTo.MAX + ": the rest are in the Pinned apps entry. Tapping one hands what you share "
                        + "straight to that app.", true,
                () -> new HashSet<>(ShareTo.pins(tk.prefs)),
                (pkg, on) -> ShareTo.choose(tk, tk.prefs, pkg, on),
                () -> {
                    Set<String> found = new HashSet<>();
                    for (String type : new String[]{"text/plain", "image/png", "video/mp4", "application/pdf",
                            "audio/mpeg", "*/*"}) {
                        Intent send = new Intent(Intent.ACTION_SEND).setType(type);
                        for (android.content.pm.ResolveInfo r : tk.getPackageManager().queryIntentActivities(send, 0)) {
                            found.add(r.activityInfo.packageName);
                        }
                    }
                    return found;
                });
    }
}
