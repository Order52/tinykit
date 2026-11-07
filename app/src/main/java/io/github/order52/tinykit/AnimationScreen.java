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
import android.widget.TextView;

final class AnimationScreen {
    private final MainActivity tk;

    AnimationScreen(MainActivity tk) {
        this.tk = tk;
    }

    static final float[] ANIM_SCALES = {0f, 0.5f, 1f, 1.5f, 2f};

    static final String[] ANIM_LABELS = {"Off", "0.5× (faster)", "Normal (1×)", "1.5×", "2× (slower)"};

    void refreshAnimationSpeed(View row) {
        TextView sub = (TextView) row.getTag();
        tk.worker.execute(() -> {
            String label = animLabel(animScale());
            tk.main.post(() -> sub.setText(Tr.t(label)));
        });
    }

    float animScale() {
        try {
            return Float.parseFloat(Shell.run("settings get global window_animation_scale").out.trim());
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    static String animLabel(float scale) {
        for (int i = 0; i < ANIM_SCALES.length; i++) {
            if (Math.abs(ANIM_SCALES[i] - scale) < 0.01f) return ANIM_LABELS[i];
        }
        return scale + "×";
    }

    void chooseAnimationSpeed(View row) {
        tk.worker.execute(() -> {
            float cur = animScale();
            int selected = 2;
            for (int i = 0; i < ANIM_SCALES.length; i++) {
                if (Math.abs(ANIM_SCALES[i] - cur) < 0.01f) selected = i;
            }
            int finalSel = selected;
            tk.main.post(() -> new AlertDialog.Builder(tk)
                    .setTitle(Tr.t("Animation speed"))
                    .setSingleChoiceItems(Tr.all(ANIM_LABELS), finalSel, (d, which) -> {
                        d.dismiss();
                        float s = ANIM_SCALES[which];
                        tk.worker.execute(() -> {
                            String err = tk.error(Shell.run("settings put global window_animation_scale " + s
                                    + "; settings put global transition_animation_scale " + s
                                    + "; settings put global animator_duration_scale " + s));
                            tk.main.post(() -> {
                                if (err != null) tk.toast(Tr.t(err));
                                refreshAnimationSpeed(row);
                            });
                        });
                    })
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show());
        });
    }
}
