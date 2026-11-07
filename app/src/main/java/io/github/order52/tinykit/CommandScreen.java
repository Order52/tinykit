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

import android.graphics.Typeface;
import android.os.Process;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class CommandScreen {
    private final MainActivity tk;

    CommandScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openRunCommand() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        page.addView(tk.note("Runs as the shell (adb) user through Shizuku. Output and exit code show below."));
        EditText input = new EditText(tk);
        input.setHint(Tr.t("e.g. getprop ro.product.model"));
        input.setSingleLine(false);
        input.setTypeface(Typeface.MONOSPACE);
        page.addView(input);
        Button run = tk.button("Run", null);
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(-1, -2);
        full.setMargins(tk.dp(4), tk.dp(4), tk.dp(4), tk.dp(4));
        page.addView(run, full);
        TextView output = tk.text("", 12, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setPadding(tk.dp(16), tk.dp(8), tk.dp(16), tk.dp(24));
        ScrollView scroll = new ScrollView(tk);
        scroll.addView(output);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push("Run a command", page);
        java.lang.Process[] runningCmd = {null};
        run.setOnClickListener(v -> {
            if (runningCmd[0] != null) {
                runningCmd[0].destroy();
                return;
            }
            String cmd = input.getText().toString().trim();
            if (cmd.isEmpty()) return;
            if (!Shell.ready()) {
                tk.toast(Tr.t("Start Shizuku and allow TinyKit first."));
                return;
            }
            output.setText(Tr.t("Running…"));
            run.setText(Tr.t("Stop"));
            // Its own thread, so a long command never holds up the rest of TinyKit.
            new Thread(() -> {
                Shell.Result r = Shell.runLimited(cmd, 60_000L, 256 * 1024, p -> runningCmd[0] = p);
                tk.main.post(() -> {
                    runningCmd[0] = null;
                    run.setText(Tr.t("Run"));
                    output.setText((r.out.isEmpty() ? Tr.t("(no output)") : r.out) + "\n\n[exit " + r.code + "]");
                });
            }, "command").start();
        });
    }
}
