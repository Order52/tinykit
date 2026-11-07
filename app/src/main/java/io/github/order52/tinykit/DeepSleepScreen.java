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
import android.widget.TextView;
import java.util.Set;

final class DeepSleepScreen {
    private final MainActivity tk;

    DeepSleepScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openDoze() {
        LinearLayout list = tk.vertical();
        TextView status = tk.note("Reading…");
        list.addView(status);
        tk.section(list, "Profile");
        final int[] chosen = {tk.prefs.getInt(Doze.PREF, 0)};
        final TextView[] marks = new TextView[Doze.NAMES.length];
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> tk.worker.execute(() -> {
            Doze.Status now = Doze.read();
            tk.main.post(() -> {
                String text = Tr.t("Profile") + ": " + Tr.t(Doze.NAMES[chosen[0]]);
                java.util.Set<String> refused = Doze.refused(tk.prefs);
                if (chosen[0] > 0) text += " · " + Tr.t(now.matches(chosen[0], refused) ? "Android is using it." : "Android isn't using it yet.");
                if (chosen[0] > 0 && !refused.isEmpty()) text += "\n" + Tr.t("Your phone's Android refused some of the timings, so it uses its own for those.");
                status.setText(text + (now.state.isEmpty() ? "" : "\n" + now.state));
            });
        });
        for (int i = 0; i < Doze.NAMES.length; i++) {
            final int profile = i;
            marks[i] = tk.text("✓", 18, true);
            marks[i].setTextColor(tk.attrColor(android.R.attr.colorAccent));
            marks[i].setVisibility(i == chosen[0] ? View.VISIBLE : View.INVISIBLE);
            tk.row(list, Doze.NAMES[i], Doze.SUMMARY[i], marks[i], () -> tk.worker.execute(() -> {
                String error = Doze.apply(tk, profile);
                tk.main.post(() -> {
                    if (error != null) {
                        tk.toast(Tr.t(error));
                        return;
                    }
                    chosen[0] = profile;
                    tk.prefs.edit().putInt(Doze.PREF, profile).apply();
                    for (int k = 0; k < marks.length; k++) marks[k].setVisibility(k == profile ? View.VISIBLE : View.INVISIBLE);
                    tk.main.postDelayed(refresh[0], 800);
                });
            }), true);
        }
        tk.section(list, "Now");
        tk.action(list, "Deep sleep now", "Puts the phone into Doze right now, until you use it again. Turn the screen off "
                + "to see it work.", () -> {
            String error = Doze.force(true);
            tk.main.post(refresh[0]);
            return error == null ? "Done. Doze is on." : error;
        });
        tk.action(list, "Wake up", "Takes the phone out of deep sleep.", () -> {
            String error = Doze.force(false);
            tk.main.post(refresh[0]);
            return error == null ? "Done." : error;
        });
        tk.link(list, "Apps that ignore deep sleep", "Apps you keep alive (and the system's own) don't doze.",
                tk::openKeepAlive);
        tk.push("Deep sleep", tk.scroll(list));
        refresh[0].run();
    }
}
