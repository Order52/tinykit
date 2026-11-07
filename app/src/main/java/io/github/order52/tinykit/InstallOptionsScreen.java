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
import android.widget.TextView;

final class InstallOptionsScreen {
    private final MainActivity tk;

    InstallOptionsScreen(MainActivity tk) {
        this.tk = tk;
    }

    static final String[] INSTALL_OPTION_NAMES = {
            "Allow downgrade (install an older version over a newer one)",
            "Grant all permissions the app asks for",
            "Allow test-only apps"};

    static final int[] INSTALL_OPTION_BITS = {ApkInstaller.DOWNGRADE, ApkInstaller.GRANT_ALL, ApkInstaller.TEST_ONLY};

    String installOptionsSummary() {
        int options = tk.prefs.getInt(ApkInstaller.PREF, 0);
        StringBuilder on = new StringBuilder();
        String[] short_ = {"Allow downgrade", "Grant all permissions", "Test-only apps"};
        for (int i = 0; i < INSTALL_OPTION_BITS.length; i++) {
            if ((options & INSTALL_OPTION_BITS[i]) != 0) on.append(on.length() > 0 ? ", " : "").append(Tr.t(short_[i]));
        }
        return on.length() == 0 ? Tr.t("Normal install. Tap to allow downgrades or grant permissions.")
                : Tr.t("On: ") + on + ".";
    }

    void chooseInstallOptions(TextView summary) {
        boolean[] checked = new boolean[INSTALL_OPTION_BITS.length];
        int options = tk.prefs.getInt(ApkInstaller.PREF, 0);
        for (int i = 0; i < checked.length; i++) checked[i] = (options & INSTALL_OPTION_BITS[i]) != 0;
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t("Install options"))
                .setMultiChoiceItems(Tr.all(INSTALL_OPTION_NAMES), checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton(Tr.t("Save"), (d, w) -> {
                    int bits = 0;
                    for (int i = 0; i < checked.length; i++) if (checked[i]) bits |= INSTALL_OPTION_BITS[i];
                    tk.prefs.edit().putInt(ApkInstaller.PREF, bits).apply();
                    if (summary != null) summary.setText(Tr.t(installOptionsSummary()));
                })
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }
}
