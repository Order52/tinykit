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

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.LinearLayout;
import java.util.HashSet;
import java.util.Set;

final class SpecialAccessScreen {
    private final MainActivity tk;

    SpecialAccessScreen(MainActivity tk) {
        this.tk = tk;
    }

    /** Access Android keeps on screens of its own, as {app-op, name, what it allows, permission}. */
    static final String[][] SPECIAL = {
            {"REQUEST_INSTALL_PACKAGES", "Install unknown apps",
                    "Apps that can install other apps. Allow only your browser, file manager or app store.",
                    "android.permission.REQUEST_INSTALL_PACKAGES"},
            {"SYSTEM_ALERT_WINDOW", "Draw over other apps",
                    "Apps that can show things on top of other apps. Scammers abuse this, so keep it short.",
                    "android.permission.SYSTEM_ALERT_WINDOW"},
            {"WRITE_SETTINGS", "Modify system settings",
                    "Apps that can change brightness, ringtones and other settings.",
                    "android.permission.WRITE_SETTINGS"},
            {"MANAGE_EXTERNAL_STORAGE", "All files access",
                    "Apps that can read and change every file on your phone.",
                    "android.permission.MANAGE_EXTERNAL_STORAGE"},
            {"GET_USAGE_STATS", "Usage access",
                    "Apps that can see which apps you use and for how long.",
                    "android.permission.PACKAGE_USAGE_STATS"}};

    void openSpecialAccess() {
        LinearLayout list = tk.vertical();
        list.addView(tk.note("These are the permissions Android hides on separate screens. Pick one to see "
                + "only the apps that ask for it, and allow or take it away."));
        for (String[] s : SPECIAL) {
            if (s[0].equals("MANAGE_EXTERNAL_STORAGE") && Build.VERSION.SDK_INT < 30) continue;
            tk.row(list, s[1], s[2], tk.arrow(), () -> tk.openPicker(s[1], s[2] + " Checked apps have it.", true,
                    () -> tk.packages(Shell.run("appops query-op " + s[0] + " allow").out),
                    (pkg, on) -> tk.error(Shell.run("appops set " + Shell.quote(pkg) + " " + s[0]
                            + (on ? " allow" : " default"))),
                    () -> requesting(s[3])), true);
        }
        tk.push("Special access", tk.scroll(list));
    }

    /** Apps that list {@code permission} in their manifest. Call off the main thread. */
    Set<String> requesting(String permission) {
        Set<String> set = new HashSet<>();
        for (PackageInfo info : tk.getPackageManager().getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            if (info.requestedPermissions == null) continue;
            for (String p : info.requestedPermissions) {
                if (p.equals(permission)) {
                    set.add(info.packageName);
                    break;
                }
            }
        }
        return set;
    }
}
