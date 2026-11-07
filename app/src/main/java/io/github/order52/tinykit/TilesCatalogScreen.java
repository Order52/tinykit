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

import android.widget.LinearLayout;
import android.widget.TextView;

final class TilesCatalogScreen {
    private final MainActivity tk;

    TilesCatalogScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openTiles() {
        Object[][] catalog = {
                {ShizukuTile.class, "Shizuku", "Turn Shizuku on and off.", R.drawable.ic_tile_shizuku},
                {ScreenshotTile.class, "Screenshot", "Save a screenshot.", R.drawable.ic_tile_screenshot},
                {Tiles.Caffeine.class, "Caffeine", "Keep the screen on for a while.", R.drawable.ic_tile_caffeine},
                {Tiles.DevOptions.class, "Developer options", "Turn developer options on or off.", R.drawable.ic_tile_dev},
                {Tiles.UsbDebug.class, "USB debugging", "Turn USB debugging on or off.", R.drawable.ic_tile_usb},
                {Tiles.KillForeground.class, "Close app", "Close the app on screen.", R.drawable.ic_tile_kill},
                {Tiles.SensorsOff.class, "Sensors off", "Block every sensor (motion, mic, camera).", R.drawable.ic_tile_sensors},
                {Tiles.Timeout.class, "Screen timeout", "Cycle 30 s to never.", R.drawable.ic_tile_timeout},
                {Tiles.Grayscale.class, "Grayscale", "Grey out the whole screen.", R.drawable.ic_tile_grayscale},
                {Tiles.AdBlock.class, "Ad-block DNS", "Block ads with Private DNS.", R.drawable.ic_tile_adblock},
                {Tiles.BankingMode.class, "Banking mode", "Banking apps work: developer options and debugging off, back as they were when you turn it off.", R.drawable.ic_tile_banking},
                {Tiles.HideDev.class, "Hide dev options", "Hide developer options from apps.", R.drawable.ic_tile_hidedev},
                {Tiles.ExtraDim.class, "Extra dim", "Dim below the usual minimum.", R.drawable.ic_tile_dim},
                {Tiles.ClearCaches.class, "Clear caches", "Free every app's cached space.", R.drawable.ic_tile_cache},
                {Tiles.MicCam.class, "Mic & camera off", "Block the mic and camera for every app.", R.drawable.ic_tile_miccam},
                {Tiles.Memory.class, "Free RAM", "Stop background apps.", R.drawable.ic_tile_ram},
                {Tiles.ScreenRecord.class, "Screen record", "Record the screen to Movies.", R.drawable.ic_tile_record},
                {Tiles.Firewall.class, "Internet block", "Pause and resume blocking apps' internet.", R.drawable.ic_tile_firewall},
                {Tiles.HwOverlays.class, "HW overlays off", "Turn off hardware overlays (developer option).", R.drawable.ic_tile_overlays},
                {Tiles.ForceDark.class, "Force dark", "Dark-theme apps that have no dark mode.", R.drawable.ic_tile_forcedark},
                {Tiles.WorkProfile.class, "Work profile", "Pause and resume your work profile.", R.drawable.ic_tile_work}};
        LinearLayout list = tk.vertical();
        list.addView(tk.note("Tap a tile to add it to your quick panel. On Android 13+ that's one tap; "
                + "on older phones TinyKit shows where to drag it."));
        for (Object[] t : catalog) {
            Class<?> service = (Class<?>) t[0];
            String name = (String) t[1];
            String about = (String) t[2];
            int icon = (Integer) t[3];
            TextView add = tk.text("Add", 14, true);
            add.setTextColor(tk.attrColor(android.R.attr.colorAccent));
            add.setPadding(tk.dp(12), 0, tk.dp(8), 0);
            tk.row(list, name, about, add, () -> tk.addTile(service, name, icon, about), false);
        }
        tk.push("TinyKit tiles", tk.scroll(list));
    }
}
