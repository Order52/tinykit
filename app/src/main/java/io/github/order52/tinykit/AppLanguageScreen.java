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
import android.os.Build;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AppLanguageScreen {
    private final MainActivity tk;

    AppLanguageScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openAppLanguage() {
        if (Build.VERSION.SDK_INT < 33) {
            tk.toast(Tr.t("A language for each app needs Android 13 or newer."));
            return;
        }
        tk.openAppList("App language", "Pick an app to give it its own language. It only takes effect for "
                + "languages the app has.", this::chooseAppLanguage);
    }

    void chooseAppLanguage(String pkg) {
        tk.worker.execute(() -> {
            String out = Shell.run("cmd locale get-app-locales " + Shell.quote(pkg) + " --user 0").out;
            Matcher m = Pattern.compile("\\[(.*?)\\]").matcher(out);
            String current = m.find() ? m.group(1).split(",")[0].trim() : "";
            // The languages Android itself ships translations for.
            Set<String> tags = new HashSet<>();
            for (String tag : tk.getResources().getAssets().getLocales()) {
                // The pseudo-locales (en-XA, ar-XB) are only for testing layouts.
                if (!tag.isEmpty() && !tag.matches(".*-X[A-C]")) tags.add(tag);
            }
            List<String[]> options = new ArrayList<>();
            for (String tag : tags) {
                Locale l = Locale.forLanguageTag(tag);
                options.add(new String[]{tag, l.getDisplayName() + "  (" + tag + ")"});
            }
            Collections.sort(options, (x, y) -> x[1].compareToIgnoreCase(y[1]));
            options.add(0, new String[]{"", "System default"});
            String[] names = new String[options.size()];
            for (int i = 0; i < names.length; i++) {
                names[i] = (options.get(i)[0].equals(current) ? "●  " : "") + options.get(i)[1];
            }
            tk.main.post(() -> new AlertDialog.Builder(tk)
                    .setTitle(Tr.t(tk.label(pkg)))
                    .setItems(Tr.all(names), (d, which) -> tk.worker.execute(() -> {
                        String tag = options.get(which)[0];
                        String error = tk.error(Shell.run("cmd locale set-app-locales " + Shell.quote(pkg)
                                + " --user 0" + (tag.isEmpty() ? "" : " --locales " + Shell.quote(tag))));
                        tk.main.post(() -> tk.toast(Tr.t(error != null ? error : tk.label(pkg) + ": " + options.get(which)[1])));
                    }))
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show());
        });
    }
}
