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
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
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

/**
 * TinyKit: small system tweaks and tools that run through Shizuku.
 *
 * One home list grouped by section; tools that need more room open as pages on top of it.
 * Settings toggles are plain `settings put global` changes, per-app tools use appops and
 * deviceidle, so everything can be undone from the same screen.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_SHIZUKU = 1;
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    // thedjchi's fork adds a Watchdog that restarts Shizuku when Android kills it.
    private static final String SHIZUKU_DOWNLOAD = "https://github.com/thedjchi/Shizuku/releases/latest";
    private static final String SHIZUKU_ORIGINAL = "https://shizuku.rikka.app/download/";
    private static final String TERMUX_DOWNLOAD = "https://f-droid.org/packages/com.termux/";
    private static final String REPO = "https://github.com/Order52/tinykit";
    private static final String TERMUX = "com.termux";
    private static final String TERMUX_BIN = "/data/data/com.termux/files/usr/bin/";
    private static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final String ACTION_TERMUX_RESULT = "io.github.order52.tinykit.TERMUX_RESULT";
    private static final int REQUEST_RUN_COMMAND = 2;
    private static final int REQUEST_PICK_APK = 4;
    private static final String[] TAB_NAMES = {"Shizuku", "Privacy", "Apps", "System", "Info"};
    private static final String DNS_HOST = "dns.adguard-dns.com";
    private static final String BACKUP_DIR = "/sdcard/Download/TinyKit";
    private static final String READ_SETTINGS = "for k in development_settings_enabled "
            + "private_dns_mode private_dns_specifier wifi_scan_always_enabled "
            + "ble_scan_always_enabled tether_dun_required mobile_data_always_on; do "
            + "echo \"$k=$(settings get global $k)\"; done";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern MEMINFO = Pattern.compile("^\\s*([\\d,]+)K: (\\S+) \\(pid \\d+");

    private interface IsOn {
        boolean test(Map<String, String> settings);
    }

    private interface Command {
        String get(boolean on);
    }

    /** Applies a per-app change on the worker thread. Returns null on success, else the error. */
    private interface AppChange {
        String apply(String pkg, boolean on);
    }

    private interface Loader {
        Set<String> load();
    }

    private interface Job {
        String run();
    }

    /** Reads whether something is currently blocked; null when it isn't available on this phone. */
    private interface Checker {
        Boolean check();
    }

    /** Blocks or unblocks; returns null on success, else the error. Call off the main thread. */
    private interface Applier {
        String apply(boolean block);
    }

    private interface OnText {
        void run(String value);
    }

    private interface InfoJob {
        List<String[]> run();
    }

    private interface Binder<T> {
        void bind(T item, Row row);
    }

    private static final class Toggle {
        Switch view;
        IsOn isOn;
    }

    private static final class App {
        String pkg;
        String label;
        String component;
        boolean system;
        boolean checked;

        App copy() {
            App a = new App();
            a.pkg = pkg;
            a.label = label;
            a.system = system;
            return a;
        }
    }

    private static final class Proc {
        String name;
        String label;
        String pkg;
        long kb;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // Icons and recover-page thumbnails load off the main thread and are cached as small bitmaps,
    // both bounded (so memory stays low) and cleared when the app is sent to the background.
    private final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    private final Map<String, Bitmap> thumbCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 96;
                }
            });
    private final Map<String, Bitmap> iconCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > 128;
                }
            });
    private final List<Toggle> toggles = new ArrayList<>();
    private final List<View> shizukuRows = new ArrayList<>();
    private final List<View> shizukuLabels = new ArrayList<>();
    private Switch updateSwitch;
    private final List<View> pages = new ArrayList<>();
    private final List<CharSequence> titles = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();
    // Mixed audio state per package: {muted 0/1, plays-with-others 0/1}.
    private final Map<String, int[]> audioState = new HashMap<>();
    private Map<String, String> settings = new HashMap<>();
    private List<App> apps;
    private SharedPreferences prefs;
    private FrameLayout frame;
    private final TextView[] tabs = new TextView[TAB_NAMES.length];
    private View[] tabRoots;
    private TextView shizukuText;
    private TextView tokenText;
    private Button shizukuButton;
    private Button startButton;
    private Button stopButton;
    private boolean starting;

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> main.post(this::refreshShizuku);
    private final Shizuku.OnBinderDeadListener binderDead = () -> main.post(this::refreshShizuku);
    private final Shizuku.OnRequestPermissionResultListener permissionResult =
            (code, result) -> main.post(this::refreshShizuku);
    private final Runnable startTimeout = () -> startFinished(
            "Shizuku didn't start. Open Termux or the Shizuku app to see why.");
    private final BroadcastReceiver termuxResult = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle result = intent.getBundleExtra("result");
            if (result != null && result.getInt("exitCode", -1) == 127) {
                // Termux is there but shizuku-termux isn't: use Shizuku's own Start button.
                startFinished(null);
                openShizukuToStart();
                return;
            }
            String message = termuxMessage(result);
            // Shizuku's binder can arrive a moment after the command returns.
            main.postDelayed(() -> startFinished(message), 1500);
        }
    };

    @Override
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // flag exists from Android 13
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tinykit", MODE_PRIVATE);
        // Warm the installed-apps cache (loading labels is the slow part) so every per-app list,
        // picker and log opens instantly. Runs off the worker queue to not delay the first tap.
        thumbs.execute(this::installedApps);
        LinearLayout root = vertical();
        frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(buildTabBar());
        setContentView(root);
        tabRoots = new View[]{buildShizuku(), buildPrivacy(), buildApps(), buildSystem(), buildInfo()};
        selectTab(Math.min(prefs.getInt("tab", 0), TAB_NAMES.length - 1));
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        IntentFilter filter = new IntentFilter(ACTION_TERMUX_RESULT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(termuxResult, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(termuxResult, filter);
        }
        // Not on rotation or other re-creations: only when TinyKit is actually opened.
        if (savedInstanceState == null) {
            if (prefs.contains("update_on_start")) autoCheckForUpdate();
            else askUpdateCheck();
        }
    }

    /** First run: asks once whether to check for updates whenever TinyKit opens. */
    private void askUpdateCheck() {
        new AlertDialog.Builder(this)
                .setTitle("Check for updates?")
                .setMessage("Do you want TinyKit to check for a new version each time you open it? It asks "
                        + "GitHub at most once an hour and shows a popup if there is one. Nothing else is "
                        + "sent. You can change this later in the Info tab.")
                .setCancelable(false)
                .setPositiveButton("Turn on", (d, w) -> setUpdateOnStart(true))
                .setNegativeButton("No thanks", (d, w) -> setUpdateOnStart(false))
                .show();
    }

    private void setUpdateOnStart(boolean on) {
        prefs.edit().putBoolean("update_on_start", on).remove("update_checked").apply();
        if (updateSwitch != null) updateSwitch.setChecked(on);
        if (on) autoCheckForUpdate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizuku();
    }

    /**
     * Frees the icon and thumbnail bitmap caches as soon as the app leaves the screen, so a
     * backgrounded TinyKit keeps almost no memory (and it never runs anything in the background).
     * Settings live in SharedPreferences and the system, so nothing is lost.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            iconCache.clear();
            thumbCache.clear();
        }
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        main.removeCallbacks(startTimeout);
        unregisterReceiver(termuxResult);
        worker.shutdown();
        thumbs.shutdownNow();
        super.onDestroy();
    }

    // ---- Pages ----------------------------------------------------------------------

    private void push(CharSequence title, View page) {
        pages.add(page);
        titles.add(title);
        show();
    }

    private void show() {
        frame.removeAllViews();
        View page = pages.get(pages.size() - 1);
        frame.addView(page);
        // A page that shows live numbers refreshes itself when it comes back into view.
        if (page.getTag() instanceof Runnable) ((Runnable) page.getTag()).run();
        if (getActionBar() != null) {
            getActionBar().setTitle(titles.get(titles.size() - 1));
            getActionBar().setDisplayHomeAsUpEnabled(pages.size() > 1);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (pages.size() > 1) {
            pages.remove(pages.size() - 1);
            titles.remove(titles.size() - 1);
            show();
        } else {
            super.onBackPressed();
        }
    }

    /** Bottom bar: one tab per group, so every tool is at most two taps away. */
    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(0xFF0D0D0D);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            TextView tab = text(TAB_NAMES[i], 13, false);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(16), 0, dp(16));
            tab.setBackgroundResource(attrResource(android.R.attr.selectableItemBackground));
            int index = i;
            tab.setOnClickListener(v -> selectTab(index));
            tabs[i] = tab;
            bar.addView(tab, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        return bar;
    }

    private void selectTab(int index) {
        prefs.edit().putInt("tab", index).apply();
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == index;
            tabs[i].setTextColor(selected ? attrColor(android.R.attr.colorAccent) : 0xFFFFFFFF);
            tabs[i].setAlpha(selected ? 1f : 0.6f);
            tabs[i].setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
        pages.clear();
        titles.clear();
        push(index == 0 ? getString(R.string.app_name) : TAB_NAMES[index], tabRoots[index]);
        loadSettings();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---- Shizuku --------------------------------------------------------------------

    private void refreshShizuku() {
        boolean installed = Shell.installed(getPackageManager());
        boolean running = Shell.running();
        boolean ready = Shell.ready();
        if (!installed) {
            shizukuText.setText("Shizuku is not installed");
            shizukuButton.setText("Get Shizuku");
            shizukuButton.setOnClickListener(v -> showInstall());
        } else if (running && !ready) {
            shizukuText.setText("Shizuku is running. Allow TinyKit to use it.");
            shizukuButton.setText("Allow");
            shizukuButton.setOnClickListener(v -> Shizuku.requestPermission(REQUEST_SHIZUKU));
        } else {
            shizukuText.setText(running ? "Shizuku is running  ✓" : "Shizuku is not running");
            shizukuButton.setText("Open Shizuku");
            shizukuButton.setOnClickListener(v -> openApp(SHIZUKU_PKG));
        }
        if (starting && running) {
            starting = false;
            main.removeCallbacks(startTimeout);
        }
        startButton.setEnabled(installed && !starting && !running);
        startButton.setText(starting ? "Starting…" : "Start");
        stopButton.setEnabled(running);
        tokenText.setText(token().isEmpty()
                ? "Not set. Tap to paste it from Shizuku's settings."
                : "Set. Start and Stop go straight to Shizuku.");
        for (View row : shizukuRows) row.setAlpha(ready ? 1f : 0.4f);
        for (View label : shizukuLabels) label.setVisibility(ready ? View.GONE : View.VISIBLE);
        if (ready) {
            loadSettings();
            // Grant TinyKit the permissions its toggles and tiles need, so they keep working
            // when Shizuku is later turned off. Done once, off the main thread.
            worker.execute(() -> Tweak.grantSelf(this));
            // Puts back what a restart wiped (firewall rules, Force dark); once per boot.
            Restore.start(this);
        }
    }

    /**
     * Starts Shizuku without a computer: through its start intent when an auth token is saved and
     * the installed Shizuku has one, otherwise by asking Termux to run the shizuku-termux command
     * (the result comes back to {@link #termuxResult}). The standard Shizuku app has neither, so
     * TinyKit opens it on its Start button and connects as soon as it's running.
     */
    private void startShizuku() {
        if (starting) return;
        if (!token().isEmpty() && hasStartIntent()) {
            sendBroadcast(new Intent(SHIZUKU_PKG + ".START").setPackage(SHIZUKU_PKG)
                    .putExtra("auth", token()));
            starting = true;
            refreshShizuku();
            main.postDelayed(startTimeout, 60_000L);
            return;
        }
        try {
            getPackageManager().getPackageInfo(TERMUX, 0);
        } catch (PackageManager.NameNotFoundException e) {
            openShizukuToStart();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(PERMISSION_RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{PERMISSION_RUN_COMMAND}, REQUEST_RUN_COMMAND);
            return;
        }
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent result = PendingIntent.getBroadcast(this, 0,
                new Intent(ACTION_TERMUX_RESULT).setPackage(getPackageName()), flags);
        Intent intent = new Intent("com.termux.RUN_COMMAND")
                .setClassName(TERMUX, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BIN + "bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c",
                        "[ -x " + TERMUX_BIN + "shizuku ] || exit 127; exec " + TERMUX_BIN
                                + "shizuku start </dev/null"})
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result);
        try {
            // Termux's own docs ask for this on Android 8+: a plain start is refused whenever
            // TinyKit isn't counted as in front, e.g. right after the permission dialog closes.
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
        } catch (RuntimeException e) {
            toast("Could not reach Termux. Open Termux once, then try again.");
            return;
        }
        starting = true;
        refreshShizuku();
        main.postDelayed(startTimeout, 60_000L);
    }

    /**
     * Whether the installed Shizuku has start and stop intents (thedjchi's fork and others based on
     * it). The standard app from shizuku.rikka.app has none and ignores them.
     */
    private boolean hasStartIntent() {
        return !getPackageManager().queryBroadcastReceivers(
                new Intent(SHIZUKU_PKG + ".START").setPackage(SHIZUKU_PKG), 0).isEmpty();
    }

    /**
     * The standard Shizuku app can only be started from its own screen (or a computer), so open
     * it there. TinyKit's binder listener refreshes this screen as soon as Shizuku is running.
     */
    private void openShizukuToStart() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
        if (launch == null) {
            toast("Install Shizuku first.");
            return;
        }
        toast("Tap Start in Shizuku. TinyKit connects by itself once it's running.");
        startActivity(launch);
    }

    /**
     * With the auth token this uses Shizuku's own stop intent, which its Watchdog respects.
     * Without it the server process is ended, and the Watchdog may start it again.
     */
    private void stopShizuku() {
        if (!token().isEmpty() && hasStartIntent()) {
            sendBroadcast(new Intent(SHIZUKU_PKG + ".STOP").setPackage(SHIZUKU_PKG)
                    .putExtra("auth", token()));
            toast("Stopping Shizuku…");
            main.postDelayed(this::refreshShizuku, 1500);
            return;
        }
        if (!Shell.ready()) {
            toast("Allow TinyKit in Shizuku first, or set the auth token below.");
            return;
        }
        if (!hasStartIntent()) {
            // The standard Shizuku app has no Watchdog, so ending the server stops it for good.
            worker.execute(() -> Shell.run("pkill -f shizuku_server || killall shizuku_server"));
            toast("Stopping Shizuku…");
            main.postDelayed(this::refreshShizuku, 1500);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Stop Shizuku?")
                .setMessage("Without the auth token TinyKit ends Shizuku's process. If Shizuku's "
                        + "Watchdog is on, it will start Shizuku again. Set the auth token to stop it properly.")
                .setPositiveButton("Stop anyway", (d, w) -> worker.execute(() ->
                        Shell.run("pkill -f shizuku_server || killall shizuku_server")))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String token() {
        return prefs.getString("auth", "");
    }

    private void editToken() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(token());
        input.setHint("Auth token");
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Shizuku auth token")
                .setMessage("Copy it from Shizuku: Settings, where the start/stop intent "
                        + "parameters are listed.")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    prefs.edit().putString("auth", input.getText().toString().trim()).apply();
                    refreshShizuku();
                })
                .setNeutralButton("Clear", (d, w) -> {
                    prefs.edit().remove("auth").apply();
                    refreshShizuku();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startFinished(String message) {
        main.removeCallbacks(startTimeout);
        starting = false;
        refreshShizuku();
        if (!Shell.running() && !TextUtils.isEmpty(message)) toast(message);
    }

    private static String termuxMessage(Bundle result) {
        if (result == null) return "Termux returned no result.";
        int exitCode = result.getInt("exitCode", -1);
        String errmsg = result.getString("errmsg", "");
        if (result.getInt("err", 0) > 0 && exitCode == -1) {
            return errmsg.contains("allow-external-apps")
                    ? "Termux doesn't allow other apps to run commands yet. Run the shizuku-termux "
                            + "install command in Termux once."
                    : errmsg;
        }
        if (exitCode == 127) {
            return "shizuku-termux isn't installed in Termux. Get it from github.com/Order52/shizuku-termux.";
        }
        return (result.getString("stdout", "") + "\n" + result.getString("stderr", "")).trim();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQUEST_RUN_COMMAND) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            startShizuku();
        } else {
            toast("Allow \"Run commands in Termux environment\" for TinyKit to start Shizuku.");
        }
    }

    private void loadSettings() {
        if (!Shell.ready()) return;
        worker.execute(() -> {
            Map<String, String> values = readSettings();
            main.post(() -> {
                settings = values;
                for (Toggle t : toggles) t.view.setChecked(t.isOn.test(values));
            });
        });
    }

    private static Map<String, String> readSettings() {
        Map<String, String> values = new HashMap<>();
        for (String line : Shell.run(READ_SETTINGS).out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) values.put(line.substring(0, eq), line.substring(eq + 1).trim());
        }
        return values;
    }

    // ---- Home -----------------------------------------------------------------------

    private View buildShizuku() {
        LinearLayout list = vertical();
        LinearLayout status = vertical();
        status.setPadding(dp(16), dp(20), dp(16), dp(8));
        shizukuText = text("", 20, true);
        status.addView(shizukuText);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(12), 0, 0);
        startButton = button("Start", v -> startShizuku());
        stopButton = button("Stop", v -> stopShizuku());
        buttons.addView(startButton, weight());
        buttons.addView(stopButton, weight());
        status.addView(buttons);
        shizukuButton = button("", null);
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(-1, -2);
        full.setMargins(dp(4), 0, dp(4), 0);
        status.addView(shizukuButton, full);
        list.addView(status);

        row(list, "Auth token", "", arrow(), this::editToken, false);
        tokenText = (TextView) list.getChildAt(list.getChildCount() - 1).getTag();
        TextView help = text("Start uses the auth token when it's set, otherwise Termux with "
                + "shizuku-termux, otherwise it opens Shizuku so you tap its Start button. Set the token "
                + "if you use a Shizuku fork with Watchdog, so Stop really stops it.", 13, false);
        help.setAlpha(0.6f);
        help.setPadding(dp(16), dp(8), dp(16), 0);
        list.addView(help);

        section(list, "Quick Settings");
        row(list, "Shizuku tile", "Turn Shizuku on and off from the quick panel with one tap.",
                arrow(), () -> addTile(ShizukuTile.class, "Shizuku", R.drawable.ic_tile_shizuku,
                        "One tap turns Shizuku on or off. It uses the auth token when it's set, "
                                + "otherwise Termux with shizuku-termux."), false);
        row(list, "Screenshot tile", "Take a screenshot from the quick panel, saved to Pictures/Screenshots.",
                arrow(), () -> addTile(ScreenshotTile.class, "Screenshot", R.drawable.ic_tile_screenshot,
                        "Needs Shizuku running. Apps that block screenshots stay blocked: Android doesn't let "
                                + "any app get past that without root."), false);
        row(list, "Install Shizuku & Termux", "Recommended versions that keep running in the background.",
                arrow(), this::showInstall, false);
        return withSearch(scroll(list));
    }

    /**
     * Wraps the home screen with a search bar over every TinyKit tool and setting. Typing swaps the
     * home content for a results list; clearing it brings the home screen back.
     */
    private View withSearch(View home) {
        LinearLayout outer = vertical();
        EditText search = new EditText(this);
        search.setHint("Search all settings & tools");
        search.setSingleLine(true);
        search.setPadding(dp(16), dp(10), dp(16), dp(10));
        outer.addView(search);
        FrameLayout holder = new FrameLayout(this);
        holder.addView(home);
        ListView results = new ListView(this);
        results.setFastScrollEnabled(true);
        results.setVisibility(View.GONE);
        holder.addView(results);
        outer.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1f));

        List<Feature> all = features();
        Adapter<Feature> adapter = new Adapter<>(false, (f, row) -> {
            row.first.setText(f.title);
            row.second.setText(f.summary);
        });
        results.setAdapter(adapter);
        results.setOnItemClickListener((parent, view, position, id) -> adapter.getItem(position).open.run());
        search.addTextChangedListener(watcher(() -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT).trim();
            if (q.isEmpty()) {
                home.setVisibility(View.VISIBLE);
                results.setVisibility(View.GONE);
                return;
            }
            String[] terms = q.split("\\s+");
            List<Feature> shown = new ArrayList<>();
            for (Feature f : all) {
                String hay = (f.title + " " + f.summary).toLowerCase(Locale.ROOT);
                boolean match = true;
                for (String term : terms) {
                    if (!hay.contains(term)) {
                        match = false;
                        break;
                    }
                }
                if (match) shown.add(f);
            }
            adapter.set(shown);
            home.setVisibility(View.GONE);
            results.setVisibility(View.VISIBLE);
        }));
        return outer;
    }

    private static final class Feature {
        final String title;
        final String summary;
        final Runnable open;

        Feature(String title, String summary, Runnable open) {
            this.title = title;
            this.summary = summary;
            this.open = open;
        }
    }

    /** Everything searchable: pages open directly, inline toggles jump to their tab. */
    private List<Feature> features() {
        List<Feature> f = new ArrayList<>();
        f.add(new Feature("Auth token", "Shizuku start and stop token", this::editToken));
        f.add(new Feature("TinyKit tiles", "Add Quick Settings tiles", this::openTiles));
        f.add(new Feature("Install Shizuku & Termux", "Get the recommended versions", this::showInstall));
        f.add(new Feature("Hide developer options", "Apps see developer options as off", () -> selectTab(1)));
        f.add(new Feature("Ad-blocking DNS", "Block ads with Private DNS", () -> selectTab(1)));
        f.add(new Feature("Stop background scanning", "Wi-Fi and Bluetooth stop scanning", () -> selectTab(1)));
        f.add(new Feature("Analytics & telemetry", "Huawei and Google tracking off", this::openAnalytics));
        f.add(new Feature("Saved Wi-Fi networks", "Passwords and QR codes", this::openWifi));
        f.add(new Feature("All sensors off", "Block motion, mic and camera sensors", () -> selectTab(1)));
        f.add(new Feature("App permissions", "Set Real, Fake or Off per app", () -> openAppList(
                "App permissions", "Pick an app to change its permissions and app-ops.", this::openAppPage)));
        f.add(new Feature("Default apps", "Launcher, browser, phone and more", this::openDefaults));
        f.add(new Feature("Hide apps", "Hide apps from the launcher", this::openHide));
        f.add(new Feature("Unused apps", "Apps you haven't opened in a long time", this::openUnused));
        f.add(new Feature("Storage by app", "Which apps take the most space", this::openStorage));
        f.add(new Feature("Recent access", "Camera, mic, location, contacts, clipboard", this::openRecentAccess));
        f.add(new Feature("Mixed audio", "Play apps together or mute an app", this::openMixedAudio));
        f.add(new Feature("Running apps", "Memory use; stop any app", this::openRunning));
        f.add(new Feature("Stop background activity", "Per-app background block", this::openBackground));
        f.add(new Feature("Keep apps alive", "Exempt apps from battery limits", this::openKeepAlive));
        f.add(new Feature("Back up app as APK", "Save any app's APK", this::openBackup));
        f.add(new Feature("Install APK files", "Install .apk, .apks, .xapk and split apps", this::pickApks));
        f.add(new Feature("Debloater", "Remove apps your phone came with, and bring them back", this::openDebloat));
        f.add(new Feature("Special access", "Install unknown apps, draw over apps, all files and more",
                this::openSpecialAccess));
        f.add(new Feature("App language", "Give an app its own language", this::openAppLanguage));
        f.add(new Feature("Installed with TinyKit", "Apps the installer put on, with a remove button",
                this::openInstallHistory));
        f.add(new Feature("Screen time", "How long each app was on screen", this::openScreenTime));
        f.add(new Feature("Clipboard guard", "Stop apps from reading your clipboard", this::openClipboardGuard));
        f.add(new Feature("Block internet per app", "Cut an app off Wi-Fi and mobile data", this::openFirewall));
        f.add(new Feature("Force dark on all apps", "Dark-theme apps that have no dark mode", () -> selectTab(3)));
        f.add(new Feature("Animation speed", "Off, faster or slower system animations", () -> selectTab(3)));
        f.add(new Feature("Edit quick panel", "Reorder, add and remove tiles", this::openQuickPanel));
        f.add(new Feature("Run a command", "Run a shell command via Shizuku", this::openRunCommand));
        f.add(new Feature("Recover deleted files", "Files in recycle bins and trash", this::openRecover));
        f.add(new Feature("Settings backup & restore", "Save and restore all settings", this::openBackupRestore));
        f.add(new Feature("Advanced reboot", "Reboot, recovery, bootloader, soft reboot", this::openReboot));
        f.add(new Feature("Speed up phone", "Trim memory, free space, one tap", () -> selectTab(3)));
        f.add(new Feature("Speed-up widget", "One-tap RAM and cache cleaner", this::addBoostWidget));
        f.add(new Feature("Clear all app caches", "Free cached space", () -> selectTab(3)));
        f.add(new Feature("Optimize apps", "Recompile apps so they open faster", () -> selectTab(3)));
        f.add(new Feature("Trim storage", "Storage maintenance and TRIM", () -> selectTab(3)));
        f.add(new Feature("Free hotspot", "Bypass carrier tether blocks", () -> selectTab(3)));
        f.add(new Feature("Mobile data off on Wi-Fi", "Save battery on Wi-Fi", () -> selectTab(3)));
        f.add(new Feature("Phone info", "Battery, memory, storage, system", this::openInfo));
        f.add(new Feature("Network info", "IP addresses, Wi-Fi, DNS", this::openNetworkInfo));
        f.add(new Feature("Charging info", "Power, current, voltage, health", this::openChargingInfo));
        f.add(new Feature("Temperatures", "Every thermal sensor", this::openTemperatures));
        f.add(new Feature("Device IDs", "Android ID, serial, MAC, build", this::openDeviceIds));
        f.add(new Feature("Battery usage", "Top battery consumers", this::openBatteryUsage));
        f.add(new Feature("Wake-ups", "What keeps the phone awake", this::openWakeups));
        f.add(new Feature("Vibration log", "Recent vibrations", this::openVibration));
        f.add(new Feature("App log", "An app's recent log output", this::openAppLog));
        f.add(new Feature("Crash log", "Why an app crashed", this::openCrashes));
        f.add(new Feature("Check for updates", "Get the latest version", () -> selectTab(4)));
        f.add(new Feature("About", "About TinyKit", this::showAbout));
        return f;
    }

    /** Android 13+ can add a tile with one tap; older versions need the panel's edit screen. */
    private void addTile(Class<?> service, String label, int icon, String about) {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                    new ComponentName(this, service), label,
                    android.graphics.drawable.Icon.createWithResource(this, icon),
                    getMainExecutor(), result -> {
                    });
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Add the " + label + " tile")
                .setMessage("Open System > Edit quick panel in TinyKit and add \"" + label + "\", or:\n\n"
                        + "1. Pull down the quick panel.\n2. Tap the edit (pencil) button.\n"
                        + "3. Drag \"" + label + "\" into your tiles.\n\n" + about)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showInstall() {
        new AlertDialog.Builder(this)
                .setTitle("Install")
                .setItems(new String[]{
                        "Shizuku (thedjchi's fork, recommended): its Watchdog restarts Shizuku if Android kills it",
                        "Termux from F-Droid: needed to start Shizuku without a computer",
                        "Original Shizuku"}, (d, which) -> openUrl(new String[]{
                        SHIZUKU_DOWNLOAD, TERMUX_DOWNLOAD, SHIZUKU_ORIGINAL}[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private View scroll(LinearLayout list) {
        list.setPadding(0, 0, 0, dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        return scroll;
    }

    private View buildPrivacy() {
        LinearLayout list = vertical();
        section(list, "Quick toggles");
        toggle(list, "Hide developer options",
                "Apps see Developer options as off. adb and Shizuku keep working.",
                s -> "0".equals(s.get("development_settings_enabled")),
                on -> "settings put global development_settings_enabled " + (on ? 0 : 1));
        toggle(list, "Ad-blocking DNS",
                "Blocks ads and trackers in every app and browser (AdGuard Private DNS).",
                s -> "hostname".equals(s.get("private_dns_mode"))
                        && DNS_HOST.equals(s.get("private_dns_specifier")),
                this::dnsCommand);
        toggle(list, "Stop background scanning",
                "Wi-Fi and Bluetooth stop scanning for nearby devices while they're off.",
                s -> "0".equals(s.get("wifi_scan_always_enabled"))
                        && "0".equals(s.get("ble_scan_always_enabled")),
                on -> "settings put global wifi_scan_always_enabled " + (on ? 0 : 1)
                        + "; settings put global ble_scan_always_enabled " + (on ? 0 : 1));
        privacySwitch(list, "All sensors off",
                "Blocks every sensor for all apps — motion, microphone and camera — like the hidden "
                        + "developer \"Sensors off\" tile. Resets on reboot.",
                () -> "true".equals(Shell.run(sensorsCmd(this, "state")).out.trim()),
                block -> error(Shell.run(sensorsCmd(this, block ? "on" : "off"))));

        section(list, "Tracking");
        link(list, "Analytics & telemetry",
                "Turn off Huawei and Google analytics, ad tracking and HiCloud.", this::openAnalytics);

        section(list, "Networks");
        link(list, "Block internet per app",
                Build.VERSION.SDK_INT >= 34
                        ? "Cut chosen apps off Wi-Fi and mobile data. Nothing runs in the background."
                        : "Needs Android 14 or newer.", this::openFirewall);
        link(list, "Saved Wi-Fi networks",
                "See saved passwords and share any network with a QR code.", this::openWifi);

        section(list, "Clipboard");
        link(list, "Clipboard guard",
                "Pick apps that can't read what you copy, like passwords and codes.", this::openClipboardGuard);

        return scroll(list);
    }

    private View buildApps() {
        LinearLayout list = vertical();
        section(list, "Permissions");
        link(list, "App permissions",
                "Set any app's permissions to Real, Fake (looks granted, gets nothing) or Off.",
                () -> openAppList("App permissions", "Pick an app to change its permissions and app-ops.",
                        this::openAppPage));
        link(list, "Default apps", "Set the default launcher, browser, phone and more.", this::openDefaults);

        section(list, "Manage");
        link(list, "Hide apps", "Hide apps from the launcher by disabling them.", this::openHide);
        link(list, "Unused apps", "Apps you haven't opened in a long time.", this::openUnused);
        link(list, "Storage by app", "Which apps take the most space.", this::openStorage);
        link(list, "Recent access",
                "Who used the camera, mic, location, contacts or clipboard lately.", this::openRecentAccess);
        link(list, "Mixed audio",
                "Let apps play audio together, or mute an app.", this::openMixedAudio);

        section(list, "Running & background");
        link(list, "Running apps", "Memory use of everything running. Stop any app.", this::openRunning);
        link(list, "Stop background activity",
                "Pick apps that can't run or wake the phone in the background.", this::openBackground);
        link(list, "Keep apps alive",
                "Pick apps Android must never kill or delay, like Termux or messengers.",
                this::openKeepAlive);
        link(list, "Back up app as APK", "Save any app's APK to Download/TinyKit.", this::openBackup);
        link(list, "Install APK files",
                "Install .apk, .apks, .xapk and .apkm files, including apps split into several parts.",
                this::pickApks);
        View[] installOptions = new View[1];
        installOptions[0] = row(list, "Install options", installOptionsSummary(), arrow(),
                () -> chooseInstallOptions((TextView) installOptions[0].getTag()), false);
        row(list, "Installed with TinyKit", "Apps the installer put on, newest first, with a remove button.",
                arrow(), this::openInstallHistory, false);

        section(list, "Remove & control");
        link(list, "Debloater", "Remove apps your phone came with, with a safety rating for each. "
                + "Bring any of them back later.", this::openDebloat);
        link(list, "Special access", "Install unknown apps, draw over apps, all files access and more, "
                + "per app.", this::openSpecialAccess);
        link(list, "App language", "Give any app its own language (Android 13+).", this::openAppLanguage);
        link(list, "Trackers in apps", "See which ad and analytics libraries each app carries.",
                this::openTrackers);
        link(list, "Notification channels", "Silence one kind of notification from an app, not all of them "
                + "(Android 8+).", this::openChannelsApp);

        return scroll(list);
    }

    private View buildSystem() {
        LinearLayout list = vertical();
        section(list, "Display");
        privacySwitch(list, "Force dark on all apps",
                "Dark-themes apps that have no dark mode (Android's force-dark). Takes effect as each "
                        + "app is re-opened. Android forgets it at a restart; TinyKit puts it back once Shizuku is running.",
                () -> "true".equals(Shell.run("getprop debug.hwui.force_dark").out.trim()),
                block -> forceDark(block, Shell.run("setprop debug.hwui.force_dark " + (block ? "true" : "false")
                        + " && (settings put secure op_force_dark_entire_world " + (block ? 1 : 0)
                        + " 2>/dev/null; settings put secure aosp_force_dark_mode " + (block ? 1 : 0)
                        + " 2>/dev/null; true)")));
        View[] anim = new View[1];
        anim[0] = row(list, "Animation speed", "…", arrow(), () -> chooseAnimationSpeed(anim[0]), true);
        refreshAnimationSpeed(anim[0]);

        section(list, "Quick panel");
        link(list, "Edit quick panel", "Reorder, add and remove tiles with one tap each.", this::openQuickPanel);
        row(list, "TinyKit tiles", "Add any of TinyKit's own Quick Settings tiles.", arrow(),
                this::openTiles, false);

        section(list, "Tools");
        link(list, "Run a command", "Run a shell command as the shell user through Shizuku.",
                this::openRunCommand);
        link(list, "Recover deleted files", "Find files in every recycle bin, with thumbnails, and restore them.",
                this::openRecover);
        link(list, "Settings backup & restore", "Save all system settings to a file and put them back.",
                this::openBackupRestore);
        link(list, "Advanced reboot", "Reboot, recovery, bootloader or a soft (userspace) reboot.",
                this::openReboot);

        section(list, "Clean & speed");
        action(list, "Speed up phone", "Trims and compacts app memory, closes cached apps only if memory is low, "
                + "clears caches only if storage is nearly full, and runs Android's storage maintenance "
                + "once a day. Nothing you're using is closed.", Ram.needsShizuku(),
                () -> Ram.free(this));
        row(list, "Speed-up widget", "Add a home screen widget that does the same speed-up in one tap, "
                + "without opening TinyKit.", arrow(), this::addBoostWidget, false);
        action(list, "Clear all app caches", "Frees the space used by every app's cache.",
                () -> Ram.clearCaches(this) + ".");
        action(list, "Optimize apps",
                "Compiles apps so they open faster. Runs in the background for about 10 minutes.", () -> {
            Shell.Result r = Shell.run("(cmd package bg-dexopt-job >/dev/null 2>&1 &)");
            return r.ok() ? "Started. It keeps running if you close TinyKit." : "Failed: " + r.out;
        });
        action(list, "Trim storage", "Runs storage maintenance and TRIM to keep storage fast.", () -> {
            Shell.Result r = Shell.run("sm fstrim; sm idle-maint run");
            return r.ok() ? "Started. Android finishes it in the background." : "Failed: " + r.out;
        });

        section(list, "Network & battery");
        toggle(list, "Free hotspot",
                "Hotspot uses your normal data connection, so carriers that block it can't (works on many carriers).",
                s -> "0".equals(s.get("tether_dun_required")),
                on -> on ? "settings put global tether_dun_required 0"
                        : "settings delete global tether_dun_required");
        toggle(list, "Mobile data off on Wi-Fi",
                "Don't keep mobile data running while connected to Wi-Fi. Saves battery.",
                s -> "0".equals(s.get("mobile_data_always_on")),
                on -> "settings put global mobile_data_always_on " + (on ? 0 : 1));

        return scroll(list);
    }

    private View buildInfo() {
        LinearLayout list = vertical();
        section(list, "Device");
        row(list, "Phone info", "Battery, memory, storage and system details.", arrow(), this::openInfo, false);
        // These pages show what Android gives any app, and add more details when Shizuku is on.
        row(list, "Network info", "IP addresses, Wi-Fi, DNS and connection type.", arrow(),
                this::openNetworkInfo, false);
        row(list, "Charging info", "Power, current, voltage and battery health.", arrow(),
                this::openChargingInfo, false);
        row(list, "Temperatures", "Every thermal sensor the phone reports.", arrow(), this::openTemperatures, false);
        row(list, "Device IDs", "Android ID, serial, MAC and build fingerprint.", arrow(), this::openDeviceIds, false);

        section(list, "Battery & logs");
        link(list, "Battery usage", "Which apps have drained the most since the last charge.", this::openBatteryUsage);
        row(list, "Screen time", "How long each app was on screen today, yesterday or in the last 7 days.", arrow(),
                this::openScreenTime, false);
        link(list, "Wake-ups", "What has kept the phone awake.", this::openWakeups);
        link(list, "Vibration log", "Recent vibrations and what asked for them.", this::openVibration);
        link(list, "App log", "See an app's log output (Huawei hides most of it).", this::openAppLog);
        link(list, "Crash log", "See why an app crashed or closed by itself.", this::openCrashes);
        View[] update = new View[1];
        update[0] = row(list, "Check for updates", "You have version " + versionName(getPackageName()) + ".",
                null, () -> checkForUpdate(update[0]), false);
        updateSwitch = new Switch(this);
        updateSwitch.setChecked(prefs.getBoolean("update_on_start", false));
        updateSwitch.setClickable(false);
        updateSwitch.setFocusable(false);
        row(list, "Check for updates on start", "When you open TinyKit, ask GitHub for a new version and "
                + "show a popup if there is one.", updateSwitch, () -> {
                    setUpdateOnStart(!updateSwitch.isChecked());
                }, false);
        row(list, "About", "Made by Ali Alakbar", arrow(), this::showAbout, false);
        return scroll(list);
    }

    /** Turning on remembers the previous Private DNS setting so turning off restores it. */
    private String dnsCommand(boolean on) {
        if (on) {
            prefs.edit()
                    .putString("dns_mode", settings.get("private_dns_mode"))
                    .putString("dns_host", settings.get("private_dns_specifier"))
                    .apply();
            return "settings put global private_dns_mode hostname; "
                    + "settings put global private_dns_specifier " + DNS_HOST;
        }
        return restore("private_dns_mode", prefs.getString("dns_mode", "off"))
                + "; " + restore("private_dns_specifier", prefs.getString("dns_host", null));
    }

    private static String restore(String key, String value) {
        if (value == null || value.equals("null") || value.equals(DNS_HOST)) {
            return "settings delete global " + key;
        }
        return "settings put global " + key + " " + Shell.quote(value);
    }

    // ---- Per-app tools ----------------------------------------------------------------

    private void openBackground() {
        openPicker("Stop background activity",
                "Checked apps are restricted like Settings > Battery > Restricted: no background work, "
                        + "alarms or wake-locks until you open them. Their notifications may arrive late, "
                        + "so leave messengers unchecked.", true,
                () -> packages(Shell.run("appops query-op RUN_ANY_IN_BACKGROUND ignore").out),
                (pkg, on) -> {
                    String q = Shell.quote(pkg);
                    String mode = on ? "ignore" : "allow";
                    // The standby bucket is Android 9+; where it's missing the command just fails, so
                    // it goes first and the app-ops below decide the result.
                    return error(Shell.run("am set-standby-bucket " + q + (on ? " restricted" : " active")
                            + " >/dev/null 2>&1; appops set " + q + " RUN_ANY_IN_BACKGROUND " + mode
                            + "; appops set " + q + " WAKE_LOCK " + mode));
                });
    }

    /** Remembers Force dark so {@link Restore} can switch it back on after a restart. */
    private String forceDark(boolean on, Shell.Result r) {
        if (r.ok()) prefs.edit().putBoolean(Restore.PREF_FORCE_DARK, on).apply();
        return error(r);
    }

    private void openKeepAlive() {
        openPicker("Keep apps alive",
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
                    return error(Shell.run(on
                            ? "dumpsys deviceidle whitelist +" + q
                                    + "; appops set " + q + " RUN_ANY_IN_BACKGROUND allow"
                                    + "; appops set " + q + " WAKE_LOCK allow"
                            : "dumpsys deviceidle whitelist -" + q));
                });
    }

    // ---- Clipboard guard ------------------------------------------------------------------

    private void openClipboardGuard() {
        openPicker("Clipboard guard",
                "Checked apps can't read what you copy, even when they're open. Android already stops "
                        + "apps from reading it in the background. Don't check your keyboard, or pasting "
                        + "suggestions stop working.", true,
                () -> packages(Shell.run("appops query-op READ_CLIPBOARD ignore").out),
                (pkg, on) -> error(Shell.run("appops set " + Shell.quote(pkg) + " READ_CLIPBOARD "
                        + (on ? "ignore" : "default"))));
    }

    // ---- Block internet per app ---------------------------------------------------------------

    private void openFirewall() {
        if (!FirewallPolicy.supported()) {
            toast("Blocking an app's internet needs Android 14 or newer.");
            return;
        }
        LinearLayout list = vertical();
        TextView status = text("", 14, true);
        status.setPadding(dp(16), dp(12), dp(16), dp(4));
        list.addView(status);
        list.addView(note("Blocked apps can't use Wi-Fi or mobile data. Android itself enforces it, so "
                + "TinyKit runs nothing in the background, and it comes back by itself after a restart "
                + "as soon as Shizuku is running again."));
        Switch modeSwitch = new Switch(this);
        modeSwitch.setClickable(false);
        modeSwitch.setFocusable(false);
        View[] choose = new View[1];
        Runnable refresh = () -> {
            boolean allow = FirewallPolicy.allowMode(prefs);
            modeSwitch.setChecked(allow);
            ((TextView) choose[0].getTag()).setText(allow
                    ? "Pick the apps that may use the internet. Everything else is blocked."
                    : "Pick the apps to cut off.");
            status.setText("…");
            worker.execute(() -> {
                int n = FirewallPolicy.blockedCount(this, prefs);
                main.post(() -> status.setText(n == 0 ? "No app is blocked."
                        : n + (n == 1 ? " app is" : " apps are") + " blocked."));
            });
        };
        choose[0] = row(list, "Choose apps", "", arrow(), this::openFirewallApps, true);
        row(list, "Allow list mode", "Block every app except the ones you allow. Apps you install later are "
                + "blocked the next time TinyKit runs, so open it after installing. Allow Google Play "
                + "services if you want push notifications.", modeSwitch, () -> {
                    boolean on = !FirewallPolicy.allowMode(prefs);
                    if (!on) {
                        setFirewallMode(false, refresh);
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Block everything except allowed apps?")
                            .setMessage("Every app that isn't on your allow list loses Wi-Fi and mobile data "
                                    + "right away, including system apps like Google Play services. "
                                    + "Pick the apps to allow next.")
                            .setPositiveButton("Turn on", (d, w) -> setFirewallMode(true, refresh))
                            .setNegativeButton("Cancel", null)
                            .show();
                }, true);
        action(list, "Unblock all apps", "Let every app use the internet again and turn allow list mode off.", () -> {
            prefs.edit().putStringSet(FirewallPolicy.BLOCKED, new HashSet<>())
                    .putBoolean(FirewallPolicy.MODE, false).apply();
            String error = FirewallPolicy.apply(this, prefs, true);
            main.post(refresh);
            return error == null ? "Every app can use the internet." : error;
        });
        View page = scroll(list);
        page.setTag(refresh);
        push("Block internet per app", page);
        refresh.run();
    }

    private void setFirewallMode(boolean allow, Runnable refresh) {
        prefs.edit().putBoolean(FirewallPolicy.MODE, allow).apply();
        worker.execute(() -> {
            String error = FirewallPolicy.apply(this, prefs, true);
            main.post(() -> {
                refresh.run();
                if (error != null) toast(error);
            });
        });
    }

    private void openFirewallApps() {
        boolean allow = FirewallPolicy.allowMode(prefs);
        openPicker(allow ? "Allowed apps" : "Blocked apps",
                allow ? "Checked apps may use Wi-Fi and mobile data. Every other app is blocked."
                        : "Checked apps can't use Wi-Fi or mobile data. It takes effect at once.",
                true,
                () -> {
                    Set<String> installed = FirewallPolicy.uids(this).keySet();
                    Set<String> checked = FirewallPolicy.saved(prefs, allow ? FirewallPolicy.ALLOWED
                            : FirewallPolicy.BLOCKED);
                    checked.retainAll(installed);
                    return checked;
                },
                (pkg, on) -> {
                    String error = FirewallPolicy.choose(this, prefs, pkg, on);
                    if (error != null) return error;
                    // Apps from one developer can share an id, and the rule covers all of them.
                    List<String> same = new ArrayList<>();
                    for (String other : FirewallPolicy.sharing(this, pkg)) same.add(label(other));
                    if (!same.isEmpty()) {
                        main.post(() -> toast("Also " + (on != allow ? "blocked: " : "allowed: ")
                                + TextUtils.join(", ", same)));
                    }
                    return null;
                });
    }

    // ---- Debloater --------------------------------------------------------------------------------

    private static final class Pkg {
        String pkg;
        String label;
        String note;
        boolean system;
        boolean checked;
        boolean locked;
        int level;
    }

    private static final int[] LEVEL_COLORS = {0xFF66BB6A, 0xFFFFCA28, 0xFFFF8A65, 0xFFEF5350};

    /** Apps in use right now, with why: removing one would break the phone. Call off the main thread. */
    private Map<String, String> inUse() {
        Map<String, String> m = new HashMap<>();
        PackageManager pm = getPackageManager();
        m.put(getPackageName(), "TinyKit, the app you are using");
        m.put(SHIZUKU_PKG, "Shizuku, which TinyKit needs");
        ResolveInfo home = pm.resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        if (home != null && home.activityInfo != null) m.put(home.activityInfo.packageName, "Your home screen");
        String ime = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.contains("/")) m.put(ime.substring(0, ime.indexOf('/')), "Your keyboard");
        if (Build.VERSION.SDK_INT >= 26) {
            android.content.pm.PackageInfo web = android.webkit.WebView.getCurrentWebViewPackage();
            if (web != null) m.put(web.packageName, "Shows web pages inside apps");
        }
        if (Build.VERSION.SDK_INT >= 29 && Shell.ready()) {
            // Whoever holds these roles is in use even if it isn't the default for something else.
            String[][] wanted = {{"HOME", "Your home screen"}, {"DIALER", "Your phone app"}, {"SMS", "Your messages app"}};
            for (String[] role : wanted) {
                for (String holder : packages(Shell.run("cmd role get-role-holders --user 0 android.app.role."
                        + role[0]).out)) {
                    m.put(holder, role[1]);
                }
            }
        }
        return m;
    }

    private static Pkg describe(String pkg, String label, boolean system, Map<String, String> inUse) {
        Pkg p = new Pkg();
        p.pkg = pkg;
        p.label = label == null ? pkg : label;
        p.system = system;
        Bloat.Info info = Bloat.info(pkg, system);
        String why = inUse.get(pkg);
        p.level = why != null ? Bloat.UNSAFE : info.level;
        p.note = why != null ? why + ". In use right now" : info.note;
        p.locked = p.level == Bloat.UNSAFE;
        return p;
    }

    /** Installed apps, or (removed) the system apps taken off this phone and still restorable. */
    private List<Pkg> debloatList(boolean removed) {
        PackageManager pm = getPackageManager();
        Map<String, String> inUse = inUse();
        Map<String, String> names = new HashMap<>();
        for (App a : installedApps()) names.put(a.pkg, a.label);
        List<Pkg> out = new ArrayList<>();
        Set<String> installed = new HashSet<>();
        for (ApplicationInfo i : pm.getInstalledApplications(0)) {
            installed.add(i.packageName);
            if (!removed) {
                out.add(describe(i.packageName, names.get(i.packageName),
                        (i.flags & ApplicationInfo.FLAG_SYSTEM) != 0, inUse));
            }
        }
        if (removed) {
            // "-u" also lists apps that are gone for this user but still on the system partition.
            for (String line : Shell.run("pm list packages -u").out.split("\n")) {
                String pkg = line.trim().replaceFirst("^package:", "");
                if (pkg.isEmpty() || installed.contains(pkg)) continue;
                try {
                    ApplicationInfo i = pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES);
                    // An app the user installed and removed has no files left to bring back.
                    if ((i.flags & ApplicationInfo.FLAG_SYSTEM) == 0) continue;
                    Pkg p = describe(pkg, String.valueOf(i.loadLabel(pm)), true, Collections.emptyMap());
                    p.locked = false;
                    out.add(p);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }
        return out;
    }

    private void openDebloat() {
        // Android 7 has no "pm install-existing", so a removed app could not be brought back.
        if (Build.VERSION.SDK_INT < 26) {
            toast("The Debloater needs Android 8 or newer, so removed apps can be restored.");
            return;
        }
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Remove apps your phone came with, for you only. Nothing is deleted from the "
                + "system: bring any app back from Removed, and a factory reset restores all of them. "
                + "Hold an app to read about it.", 13, false);
        help.setAlpha(0.8f);
        page.addView(help);
        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        page.addView(search);
        CheckBox mine = new CheckBox(this);
        mine.setText("Also show apps I installed");
        page.addView(mine);
        TextView loading = text("Reading apps…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button go = button("", null);
        go.setVisibility(View.GONE);
        page.addView(go);
        push("Debloater", page);

        final boolean[] removedView = {false};
        final int[] levelFilter = {-1};
        List<Pkg> all = new ArrayList<>();
        Adapter<Pkg> adapter = new Adapter<>(true, (p, row) -> {
            row.first.setText(p.label);
            row.second.setText(p.pkg);
            row.third.setText(Bloat.LEVELS[p.level] + " · " + p.note);
            row.third.setTextColor(LEVEL_COLORS[p.level]);
            row.check.setChecked(p.checked);
            row.check.setVisibility(p.locked ? View.INVISIBLE : View.VISIBLE);
            showIcon(row, p.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<Pkg> shown = new ArrayList<>();
            int picked = 0;
            for (Pkg p : all) {
                if (p.checked) picked++;
                if (!removedView[0] && !p.system && !mine.isChecked() && !p.checked) continue;
                if (levelFilter[0] >= 0 && p.level != levelFilter[0]) continue;
                if (p.label.toLowerCase(Locale.ROOT).contains(q) || p.pkg.contains(q)) shown.add(p);
            }
            Collections.sort(shown, (x, y) -> x.checked != y.checked ? (x.checked ? -1 : 1)
                    : x.label.compareToIgnoreCase(y.label));
            adapter.set(shown);
            go.setVisibility(picked > 0 ? View.VISIBLE : View.GONE);
            go.setText((removedView[0] ? "Restore " : "Remove ") + picked + (picked == 1 ? " app" : " apps"));
        };
        Runnable load = () -> {
            boolean wanted = removedView[0];
            all.clear();
            filter.run();
            loading.setText("Reading apps…");
            loading.setVisibility(View.VISIBLE);
            worker.execute(() -> {
                List<Pkg> list = debloatList(wanted);
                main.post(() -> {
                    if (wanted != removedView[0]) return;
                    all.clear();
                    all.addAll(list);
                    loading.setText("No apps here.");
                    loading.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                    filter.run();
                });
            });
        };
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(dp(0), dp(8), dp(0), dp(4));
        chips.addView(chip("", "View", new String[]{"Installed apps", "Removed apps"}, i -> {
            removedView[0] = i == 1;
            mine.setVisibility(i == 1 ? View.GONE : View.VISIBLE);
            load.run();
        }));
        chips.addView(chip("", "Safety", new String[]{"All", "Recommended", "Advanced", "Expert", "Unsafe"}, i -> {
            levelFilter[0] = i - 1;
            filter.run();
        }));
        page.addView(chips, 3);
        search.addTextChangedListener(watcher(filter));
        mine.setOnCheckedChangeListener((v, checked) -> filter.run());
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Pkg p = adapter.getItem(position);
            if (p.locked) {
                toast(p.label + ": " + p.note + ". It can't be removed.");
                return;
            }
            p.checked = !p.checked;
            filter.run();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            Pkg p = adapter.getItem(position);
            new AlertDialog.Builder(this)
                    .setTitle(p.label)
                    .setMessage(Bloat.LEVELS[p.level] + ": " + Bloat.MEANING[p.level] + "\n\n" + p.note
                            + "\n\n" + p.pkg + (versionName(p.pkg).isEmpty() ? "" : "\nVersion " + versionName(p.pkg)))
                    .setPositiveButton("OK", null)
                    .show();
            return true;
        });
        go.setOnClickListener(v -> {
            List<Pkg> picked = new ArrayList<>();
            int worst = 0;
            for (Pkg p : all) {
                if (!p.checked) continue;
                picked.add(p);
                worst = Math.max(worst, p.level);
            }
            boolean restore = removedView[0];
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < picked.size() && i < 12; i++) names.append("• ").append(picked.get(i).label).append('\n');
            if (picked.size() > 12) names.append("and ").append(picked.size() - 12).append(" more\n");
            new AlertDialog.Builder(this)
                    .setTitle((restore ? "Restore " : "Remove ") + picked.size() + (picked.size() == 1 ? " app?" : " apps?"))
                    .setMessage(names + (restore
                            ? "\nThey come back as they were."
                            : (picked.size() == 1 ? "\nIt disappears" : "\nThey disappear")
                                    + " for you only and can be restored from Removed."
                                    + (worst >= Bloat.EXPERT ? "\n\nSome of these are marked Expert: other "
                                    + "apps or features may depend on them." : "")))
                    .setPositiveButton(restore ? "Restore" : "Remove", (d, w) -> runDebloat(picked, restore, load))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        load.run();
    }

    private void runDebloat(List<Pkg> picked, boolean restore, Runnable done) {
        TextView message = text((restore ? "Restoring " : "Removing ") + picked.size() + "…", 15, false);
        message.setPadding(dp(24), dp(16), dp(24), dp(8));
        AlertDialog progress = new AlertDialog.Builder(this).setTitle("Working")
                .setView(message).setCancelable(false).show();
        worker.execute(() -> {
            StringBuilder script = new StringBuilder();
            for (Pkg p : picked) {
                String q = Shell.quote(p.pkg);
                if (restore) {
                    script.append("echo ").append(q).append("\"|$(pm install-existing --user 0 ").append(q)
                            .append(" 2>&1)\"; ");
                } else {
                    // -k keeps the app's data so a restore brings it back as it was. An updated system
                    // app first loses its update, so a second pass removes the app itself.
                    String k = p.system ? "-k " : "";
                    script.append("r=$(pm uninstall ").append(k).append("--user 0 ").append(q).append(" 2>&1); ")
                            .append("[ -n \"$(pm path ").append(q).append(" 2>/dev/null)\" ] && r=$(pm uninstall ")
                            .append(k).append("--user 0 ").append(q).append(" 2>&1); echo ").append(q)
                            .append("\"|$r\"; ");
                }
            }
            Shell.Result r = Shell.run(script.toString());
            Map<String, String> labels = new HashMap<>();
            for (Pkg p : picked) labels.put(p.pkg, p.label);
            int ok = 0;
            List<String> failed = new ArrayList<>();
            for (String line : r.out.split("\n")) {
                int bar = line.indexOf('|');
                if (bar < 0) continue;
                String pkg = line.substring(0, bar);
                String result = line.substring(bar + 1);
                if (result.contains("Success") || result.contains("installed for user")) {
                    ok++;
                } else if (labels.containsKey(pkg)) {
                    failed.add(labels.get(pkg) + ": " + debloatReason(result));
                }
            }
            reloadApps();
            int count = ok;
            main.post(() -> {
                progress.dismiss();
                done.run();
                String summary = (restore ? "Restored " : "Removed ") + count + (count == 1 ? " app." : " apps.");
                if (failed.isEmpty() && count > 0) {
                    toast(summary);
                } else {
                    new AlertDialog.Builder(this).setTitle(summary)
                            .setMessage(failed.isEmpty() ? "Nothing changed." : TextUtils.join("\n", failed))
                            .setPositiveButton("OK", null).show();
                }
            });
        });
    }

    private static String debloatReason(String result) {
        if (result.contains("DEVICE_POLICY")) return "your device policy blocks this";
        if (result.contains("USER_RESTRICTED")) return "this phone doesn't allow it";
        if (result.contains("NOT_INSTALLED") || result.contains("Unknown package")) return "not installed";
        return result.isEmpty() ? "Android refused" : result.replaceAll("\\s+", " ");
    }

    // ---- Special access ---------------------------------------------------------------------------

    /** Access Android keeps on screens of its own, as {app-op, name, what it allows, permission}. */
    private static final String[][] SPECIAL = {
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

    private void openSpecialAccess() {
        LinearLayout list = vertical();
        list.addView(note("These are the permissions Android hides on separate screens. Pick one to see "
                + "only the apps that ask for it, and allow or take it away."));
        for (String[] s : SPECIAL) {
            if (s[0].equals("MANAGE_EXTERNAL_STORAGE") && Build.VERSION.SDK_INT < 30) continue;
            row(list, s[1], s[2], arrow(), () -> openPicker(s[1], s[2] + " Checked apps have it.", true,
                    () -> packages(Shell.run("appops query-op " + s[0] + " allow").out),
                    (pkg, on) -> error(Shell.run("appops set " + Shell.quote(pkg) + " " + s[0]
                            + (on ? " allow" : " default"))),
                    () -> requesting(s[3])), true);
        }
        push("Special access", scroll(list));
    }

    /** Apps that list {@code permission} in their manifest. Call off the main thread. */
    private Set<String> requesting(String permission) {
        Set<String> set = new HashSet<>();
        for (PackageInfo info : getPackageManager().getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
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

    // ---- App language ---------------------------------------------------------------------------

    private void openAppLanguage() {
        if (Build.VERSION.SDK_INT < 33) {
            toast("A language for each app needs Android 13 or newer.");
            return;
        }
        openAppList("App language", "Pick an app to give it its own language. It only takes effect for "
                + "languages the app has.", this::chooseAppLanguage);
    }

    private void chooseAppLanguage(String pkg) {
        worker.execute(() -> {
            String out = Shell.run("cmd locale get-app-locales " + Shell.quote(pkg) + " --user 0").out;
            Matcher m = Pattern.compile("\\[(.*?)\\]").matcher(out);
            String current = m.find() ? m.group(1).split(",")[0].trim() : "";
            // The languages Android itself ships translations for.
            Set<String> tags = new HashSet<>();
            for (String tag : getResources().getAssets().getLocales()) {
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
            main.post(() -> new AlertDialog.Builder(this)
                    .setTitle(label(pkg))
                    .setItems(names, (d, which) -> worker.execute(() -> {
                        String tag = options.get(which)[0];
                        String error = error(Shell.run("cmd locale set-app-locales " + Shell.quote(pkg)
                                + " --user 0" + (tag.isEmpty() ? "" : " --locales " + Shell.quote(tag))));
                        main.post(() -> toast(error != null ? error : label(pkg) + ": " + options.get(which)[1]));
                    }))
                    .setNegativeButton("Cancel", null)
                    .show());
        });
    }

    // ---- Installed with TinyKit ---------------------------------------------------------------

    /**
     * Apps installed through TinyKit's installer, which marks itself as their installer (see
     * {@link ApkInstaller}). The list is read from Android's own records, so TinyKit stores nothing.
     */
    private List<String[]> installedByTinyKit() {
        PackageManager pm = getPackageManager();
        List<long[]> order = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        for (ApplicationInfo i : pm.getInstalledApplications(0)) {
            String installer;
            try {
                installer = Build.VERSION.SDK_INT >= 30
                        ? pm.getInstallSourceInfo(i.packageName).getInstallingPackageName()
                        : pm.getInstallerPackageName(i.packageName);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                continue;
            }
            if (!getPackageName().equals(installer) || i.packageName.equals(getPackageName())) continue;
            try {
                PackageInfo info = pm.getPackageInfo(i.packageName, 0);
                long size = new java.io.File(i.sourceDir).length();
                long age = System.currentTimeMillis() - info.lastUpdateTime;
                String when = age < 60_000 ? "just now" : DateUtils.getRelativeTimeSpanString(info.lastUpdateTime,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString();
                rows.add(new String[]{i.packageName, String.valueOf(i.loadLabel(pm)) + "  " + info.versionName,
                        (info.firstInstallTime == info.lastUpdateTime ? "Installed " : "Updated ") + when
                                + " · " + Formatter.formatShortFileSize(this, size)});
                order.add(new long[]{rows.size() - 1, info.lastUpdateTime});
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        Collections.sort(order, (x, y) -> Long.compare(y[1], x[1]));
        List<String[]> sorted = new ArrayList<>();
        for (long[] o : order) sorted.add(rows.get((int) o[0]));
        return sorted;
    }

    private void openInstallHistory() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Apps installed with TinyKit's installer, newest first. Tap one to open or "
                + "remove it. This list is read from Android's own records, so TinyKit stores nothing.", 13, false);
        help.setAlpha(0.7f);
        page.addView(help);
        TextView loading = text("Reading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        View view = page;
        Adapter<String[]> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(a[1]);
            row.second.setText(a[0]);
            row.third.setText(a[2]);
            showIcon(row, a[0]);
        });
        listView.setAdapter(adapter);
        Runnable load = () -> worker.execute(() -> {
            reloadApps();
            List<String[]> rows = installedByTinyKit();
            main.post(() -> {
                loading.setText("Nothing yet. Apps you install with TinyKit from now on show up here.");
                loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.set(rows);
            });
        });
        listView.setOnItemClickListener((parent, v, position, id) -> {
            String[] a = adapter.getItem(position);
            new AlertDialog.Builder(this)
                    .setTitle(a[1])
                    .setMessage(a[2] + "\n\nRemoving it deletes the app and its data. TinyKit keeps no copy "
                            + "of the file, so an older version can't be put back.")
                    .setPositiveButton("Open", (d, w) -> openApp(a[0]))
                    .setNegativeButton("Remove", (d, w) -> new AlertDialog.Builder(this)
                            .setTitle("Remove " + a[1] + "?")
                            .setMessage("The app and its data are deleted.")
                            .setPositiveButton("Remove", (d2, w2) -> worker.execute(() -> {
                                String error = error(Shell.run("pm uninstall " + Shell.quote(a[0])));
                                main.post(() -> {
                                    toast(error != null ? error : "Removed.");
                                    load.run();
                                });
                            }))
                            .setNegativeButton("Cancel", null)
                            .show())
                    .setNeutralButton("Close", null)
                    .show();
        });
        push("Installed with TinyKit", view);
        load.run();
    }

    // ---- Install options ------------------------------------------------------------------------

    private static final String[] INSTALL_OPTION_NAMES = {
            "Allow downgrade (install an older version over a newer one)",
            "Grant all permissions the app asks for",
            "Allow test-only apps"};
    private static final int[] INSTALL_OPTION_BITS = {ApkInstaller.DOWNGRADE, ApkInstaller.GRANT_ALL, ApkInstaller.TEST_ONLY};

    private String installOptionsSummary() {
        int options = prefs.getInt(ApkInstaller.PREF, 0);
        StringBuilder on = new StringBuilder();
        String[] short_ = {"Allow downgrade", "Grant all permissions", "Test-only apps"};
        for (int i = 0; i < INSTALL_OPTION_BITS.length; i++) {
            if ((options & INSTALL_OPTION_BITS[i]) != 0) on.append(on.length() > 0 ? ", " : "").append(short_[i]);
        }
        return on.length() == 0 ? "Normal install. Tap to allow downgrades or grant permissions."
                : "On: " + on + ".";
    }

    private void chooseInstallOptions(TextView summary) {
        boolean[] checked = new boolean[INSTALL_OPTION_BITS.length];
        int options = prefs.getInt(ApkInstaller.PREF, 0);
        for (int i = 0; i < checked.length; i++) checked[i] = (options & INSTALL_OPTION_BITS[i]) != 0;
        new AlertDialog.Builder(this)
                .setTitle("Install options")
                .setMultiChoiceItems(INSTALL_OPTION_NAMES, checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton("Save", (d, w) -> {
                    int bits = 0;
                    for (int i = 0; i < checked.length; i++) if (checked[i]) bits |= INSTALL_OPTION_BITS[i];
                    prefs.edit().putInt(ApkInstaller.PREF, bits).apply();
                    if (summary != null) summary.setText(installOptionsSummary());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Trackers in apps -----------------------------------------------------------------------

    /**
     * Which ad and analytics libraries each app carries, read from the parts it declares. Android
     * doesn't let the shell turn individual parts off ("Shell cannot change component state"), so
     * this finds them and offers what does work: cutting the app off the internet.
     */
    private void openTrackers() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Ad and analytics libraries found inside your apps, most first. This is what each "
                + "app declares, not proof it uses them. Tap an app to see them" + (FirewallPolicy.supported()
                ? " or cut it off the internet." : "."), 13, false);
        help.setAlpha(0.8f);
        page.addView(help);
        CheckBox system = new CheckBox(this);
        system.setText("Show system apps");
        page.addView(system);
        TextView loading = text("Scanning apps…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Trackers in apps", page);

        // {package, label, "A, B, C", count, system}
        List<String[]> all = new ArrayList<>();
        Adapter<String[]> adapter = new Adapter<>(false, (t, row) -> {
            row.first.setText(t[1]);
            row.second.setText(t[3] + (t[3].equals("1") ? " tracker" : " trackers"));
            row.third.setText(t[2]);
            showIcon(row, t[0]);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            List<String[]> shown = new ArrayList<>();
            for (String[] t : all) if (system.isChecked() || t[4].equals("0")) shown.add(t);
            adapter.set(shown);
            loading.setText("No trackers found.");
            loading.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        };
        system.setOnCheckedChangeListener((v, on) -> filter.run());
        worker.execute(() -> {
            PackageManager pm = getPackageManager();
            List<String[]> found = new ArrayList<>();
            for (App a : installedApps()) {
                Set<String> libs = new TreeSet<>();
                try {
                    PackageInfo info = pm.getPackageInfo(a.pkg, PackageManager.GET_ACTIVITIES
                            | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
                    for (ComponentInfo[] group : new ComponentInfo[][]{info.activities, info.services,
                            info.receivers, info.providers}) {
                        if (group == null) continue;
                        for (ComponentInfo ci : group) {
                            String lib = Trackers.find(ci.name);
                            if (lib != null) libs.add(lib);
                        }
                    }
                } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                    continue;
                }
                if (libs.isEmpty()) continue;
                found.add(new String[]{a.pkg, a.label, TextUtils.join(", ", libs), String.valueOf(libs.size()),
                        a.system ? "1" : "0"});
            }
            Collections.sort(found, (x, y) -> Integer.parseInt(y[3]) - Integer.parseInt(x[3]));
            main.post(() -> {
                all.clear();
                all.addAll(found);
                filter.run();
            });
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String[] t = adapter.getItem(position);
            AlertDialog.Builder d = new AlertDialog.Builder(this)
                    .setTitle(t[1])
                    .setMessage(t[2] + "\n\nThese are declared inside " + t[0] + ". TinyKit can't switch them off "
                            + "one by one (Android doesn't allow it from the shell).")
                    .setNegativeButton("Close", null);
            if (FirewallPolicy.supported() && Shell.ready()) {
                d.setPositiveButton("Block internet", (x, w) -> worker.execute(() -> {
                    String error = FirewallPolicy.choose(this, prefs, t[0], true);
                    main.post(() -> toast(error != null ? error : t[1] + " is blocked from the internet."));
                }));
            }
            d.show();
        });
    }

    // ---- Notification channels ------------------------------------------------------------------

    private void openChannelsApp() {
        if (Build.VERSION.SDK_INT < 26) {
            toast("Notification channels need Android 8 or newer.");
            return;
        }
        openAppList("Notification channels", "Pick an app to choose which kinds of notification it may show.",
                this::openChannels);
    }

    private static String channelsCmd(Context c, String args) {
        return "CLASSPATH=" + c.getPackageCodePath()
                + " app_process /system/bin io.github.order52.tinykit.Channels " + args;
    }

    private static String importanceName(int importance) {
        switch (importance) {
            case 0: return "Blocked";
            case 1: return "Silent, hidden";
            case 2: return "Silent";
            case 3: return "Sound";
            default: return "Sound and pop-up";
        }
    }

    private void openChannels(String pkg) {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Tick = the app may show this kind of notification. Untick to block just that kind. "
                + "Some system channels can't be blocked.", 13, false);
        help.setAlpha(0.8f);
        page.addView(help);
        TextView loading = text("Reading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push(label(pkg), page);

        // {id, name, importance}
        Adapter<String[]> adapter = new Adapter<>(true, (ch, row) -> {
            row.first.setText(ch[1]);
            row.second.setText(importanceName(Integer.parseInt(ch[2])));
            row.check.setChecked(!ch[2].equals("0"));
        });
        listView.setAdapter(adapter);
        int uid;
        try {
            uid = getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        Runnable load = () -> worker.execute(() -> {
            Shell.Result r = Shell.run(channelsCmd(this, "list " + Shell.quote(pkg) + " " + uid));
            List<String[]> rows = new ArrayList<>();
            if (r.ok()) {
                for (String line : r.out.split("\n")) {
                    String[] f = line.split("\t");
                    if (f.length == 3) rows.add(f);
                }
            }
            Collections.sort(rows, (x, y) -> x[1].compareToIgnoreCase(y[1]));
            main.post(() -> {
                loading.setText(r.ok() ? "This app hasn't created any notification channels yet."
                        : "Couldn't read them: " + r.out);
                loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.set(rows);
            });
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String[] ch = adapter.getItem(position);
            boolean blocked = ch[2].equals("0");
            String key = "chan_" + pkg + "|" + ch[0];
            worker.execute(() -> {
                // Remember how loud it was so turning it back on restores exactly that.
                int target = blocked ? prefs.getInt(key, 3) : 0;
                if (!blocked) prefs.edit().putInt(key, Integer.parseInt(ch[2])).apply();
                Shell.Result r = Shell.run(channelsCmd(this, "set " + Shell.quote(pkg) + " " + uid + " "
                        + Shell.quote(ch[0]) + " " + target));
                main.post(() -> {
                    if (!r.ok()) toast(r.out);
                    load.run();
                });
            });
        });
        load.run();
    }

    // ---- Screen time ------------------------------------------------------------------------------

    private void openScreenTime() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView total = text("", 16, true);
        page.addView(total);
        TextView help = text("How long each app was on your screen, and how often you opened it. Read from "
                + "Android's usage history, so TinyKit measures nothing itself.", 13, false);
        help.setAlpha(0.7f);
        page.addView(help);
        TextView loading = text("Reading usage…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        Adapter<String[]> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(a[1]);
            row.second.setText(a[2]);
            showIcon(row, a[0]);
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, v, position, id) -> openAppInfo(adapter.getItem(position)[0]));
        final int[] range = {0};
        Runnable load = () -> {
            loading.setText("Reading usage…");
            loading.setVisibility(View.VISIBLE);
            worker.execute(() -> {
                if (!Tweak.usageAllowed(this)) Tweak.grantSelf(this);
                if (!Tweak.usageAllowed(this)) {
                    main.post(() -> loading.setText("TinyKit needs usage access. Start Shizuku once and "
                            + "reopen this page, or allow it in Settings > Apps > Special access."));
                    return;
                }
                Calendar midnight = Calendar.getInstance();
                midnight.set(Calendar.HOUR_OF_DAY, 0);
                midnight.set(Calendar.MINUTE, 0);
                midnight.set(Calendar.SECOND, 0);
                midnight.set(Calendar.MILLISECOND, 0);
                long now = System.currentTimeMillis();
                long today = midnight.getTimeInMillis();
                // Whole days, because Android keeps its usage totals per day.
                long from = range[0] == 2 ? today - 6 * DAY : range[0] == 1 ? today - DAY : today;
                long to = range[0] == 1 ? today - 1 : now;
                long[] sum = {0};
                List<String[]> rows = screenTime(from, to, sum);
                main.post(() -> {
                    total.setText(sum[0] == 0 ? "" : "Total on screen: " + duration(sum[0]));
                    loading.setText("No app use recorded in this time.");
                    loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                    adapter.set(rows);
                });
            });
        };
        page.addView(sortBarLike(chip("", "Show", new String[]{"Today", "Yesterday", "Last 7 days"}, i -> {
            range[0] = i;
            load.run();
        })), 1);
        push("Screen time", page);
        load.run();
    }

    private static final long DAY = 86_400_000L;

    private static String duration(long ms) {
        long minutes = Math.round(ms / 60000.0);
        if (minutes < 1) return ms < 1000 ? "0 min" : "under a minute";
        return minutes >= 60 ? (minutes / 60) + " h " + (minutes % 60) + " min" : minutes + " min";
    }

    /** Rows {package, label, "2 h 5 min · 12 opens"}, longest first; sum[0] gets the total. Off the main thread. */
    private List<String[]> screenTime(long from, long to, long[] sum) {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        // Android adds up the foreground time itself, which copes with apps that were killed while
        // open. Only whole-day totals inside the range are used, so a week-long total can't leak in.
        Map<String, Long> stats = new HashMap<>();
        List<UsageStats> days = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to);
        if (days != null) {
            for (UsageStats d : days) {
                if (d.getFirstTimeStamp() < from - 3_600_000L || d.getLastTimeStamp() > to + DAY) continue;
                Long before = stats.get(d.getPackageName());
                stats.put(d.getPackageName(), (before == null ? 0 : before) + d.getTotalTimeInForeground());
            }
        }
        // Opens aren't kept as a number, so count how often each app came to the front.
        Map<String, Integer> opens = new HashMap<>();
        android.app.usage.UsageEvents events = usm.queryEvents(from, to);
        android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
        String last = "";
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            // 1 is "activity resumed", the long-standing value of the foreground event.
            if (e.getEventType() != 1 || e.getPackageName().equals(last)) continue;
            last = e.getPackageName();
            opens.put(last, (opens.containsKey(last) ? opens.get(last) : 0) + 1);
        }
        List<String[]> rows = new ArrayList<>();
        List<long[]> order = new ArrayList<>();
        for (Map.Entry<String, Long> s : stats.entrySet()) {
            long spent = s.getValue();
            if (spent < 1000) continue;
            int n = opens.containsKey(s.getKey()) ? opens.get(s.getKey()) : 0;
            rows.add(new String[]{s.getKey(), label(s.getKey()),
                    duration(spent) + (n == 0 ? "" : " · " + n + (n == 1 ? " open" : " opens"))});
            order.add(new long[]{rows.size() - 1, spent});
            sum[0] += spent;
        }
        Collections.sort(order, (x, y) -> Long.compare(y[1], x[1]));
        List<String[]> sorted = new ArrayList<>();
        for (long[] o : order) sorted.add(rows.get((int) o[0]));
        return sorted;
    }

    // ---- Install APK files ----------------------------------------------------------------

    private void pickApks() {
        if (!Shell.ready()) {
            toast("Start Shizuku and allow TinyKit first.");
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(pick, REQUEST_PICK_APK);
        } catch (ActivityNotFoundException e) {
            toast("No file picker found on this phone.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_APK || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        TextView message = text("Reading files…", 15, false);
        message.setPadding(dp(24), dp(16), dp(24), dp(8));
        AlertDialog progress = new AlertDialog.Builder(this).setTitle("Installing")
                .setView(message).setCancelable(false).show();
        // Its own thread: an install can take a while and shouldn't hold up the other commands.
        new Thread(() -> {
            String result = ApkInstaller.install(this, uris, text -> main.post(() -> message.setText(text)));
            main.post(() -> {
                progress.dismiss();
                new AlertDialog.Builder(this).setTitle("Install").setMessage(result)
                        .setPositiveButton("OK", null).show();
            });
            reloadApps();
        }, "install").start();
    }

    private void openBackup() {
        openPicker("Back up app as APK", "Tap an app to save its APK to Download/TinyKit.", false, null,
                (pkg, on) -> {
                    String name = fileName(label(pkg) + "-" + versionName(pkg));
                    String dir = BACKUP_DIR;
                    Shell.Result r = Shell.run("set -e; p=$(pm path " + Shell.quote(pkg)
                            + " | sed 's/^package://'); mkdir -p " + dir + "; "
                            + "if [ $(echo \"$p\" | wc -l) -eq 1 ]; then "
                            + "cp \"$p\" " + Shell.quote(dir + "/" + name + ".apk") + "; echo apk; "
                            + "else mkdir -p " + Shell.quote(dir + "/" + name) + "; "
                            + "for f in $p; do cp \"$f\" " + Shell.quote(dir + "/" + name) + "/; done; echo split; fi");
                    if (!r.ok()) return r.out.isEmpty() ? "Could not copy the APK." : r.out;
                    String where = "Download/TinyKit/" + name + (r.out.endsWith("split")
                            ? "/ (split APK: install all files together)" : ".apk");
                    main.post(() -> toast("Saved to " + where));
                    return null;
                });
    }

    private void openPicker(String title, String help, boolean checkable, Loader loader, AppChange change) {
        openPicker(title, help, checkable, loader, change, null);
    }

    /** As above; {@code eligible}, when given, limits the list to the apps it returns. */
    private void openPicker(String title, String help, boolean checkable, Loader loader, AppChange change,
                            Loader eligible) {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView helpView = text(help, 14, false);
        helpView.setAlpha(0.8f);
        page.addView(helpView);
        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        page.addView(search);
        CheckBox system = new CheckBox(this);
        system.setText("Show system apps");
        page.addView(system);
        final int[] sort = {0};
        TextView loading = text("Loading apps…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push(title, page);

        List<App> all = new ArrayList<>();
        final Set<String> only = Collections.synchronizedSet(new HashSet<>());
        final boolean[] limited = {eligible != null};
        Adapter<App> adapter = new Adapter<>(checkable, (app, row) -> {
            row.first.setText(app.label);
            row.second.setText(app.pkg);
            row.check.setChecked(app.checked);
            showIcon(row, app.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<App> shown = new ArrayList<>();
            for (App a : all) {
                if (limited[0] && !only.contains(a.pkg)) continue;
                if ((system.isChecked() || !a.system || a.checked)
                        && (a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.contains(q))) {
                    shown.add(a);
                }
            }
            // Checked apps stay on top; the chip picks the order within each group.
            Collections.sort(shown, (x, y) -> x.checked != y.checked ? (x.checked ? -1 : 1)
                    : (sort[0] == 1 ? x.pkg.compareToIgnoreCase(y.pkg) : x.label.compareToIgnoreCase(y.label)));
            adapter.set(shown);
        };
        if (checkable) {
            page.addView(sortBar(new String[]{"Name", "Package"}, i -> {
                sort[0] = i;
                filter.run();
            }), 3);
        }
        search.addTextChangedListener(watcher(filter));
        system.setOnCheckedChangeListener((v, checked) -> filter.run());
        listView.setOnItemClickListener((parent, view, position, id) -> {
            App app = adapter.getItem(position);
            boolean on = !app.checked;
            if (checkable) {
                app.checked = on;
                adapter.notifyDataSetChanged();
            } else {
                toast("Saving " + app.label + "…");
            }
            worker.execute(() -> {
                String error = change.apply(app.pkg, on);
                main.post(() -> {
                    if (error != null) {
                        if (checkable) app.checked = !on;
                        adapter.notifyDataSetChanged();
                        toast(error);
                    }
                });
            });
        });

        Runnable show = () -> {
            all.clear();
            for (App a : installedApps()) all.add(a.copy());
            loading.setVisibility(View.GONE);
            filter.run();
        };
        // Show the apps instantly (cache is preloaded at launch); the checkmarks fill in once the
        // current state comes back from Shizuku, so there's no blank "loading" wait.
        if (appsLoaded()) {
            show.run();
        } else {
            worker.execute(() -> {
                installedApps();
                main.post(show);
            });
        }
        if (eligible != null) {
            worker.execute(() -> {
                Set<String> ok = eligible.load();
                main.post(() -> {
                    only.addAll(ok);
                    limited[0] = true;
                    filter.run();
                });
            });
        }
        if (loader != null) {
            loading.setText("Reading current state…");
            loading.setVisibility(View.VISIBLE);
            worker.execute(() -> {
                Set<String> checked = loader.load();
                main.post(() -> {
                    for (App a : all) a.checked = checked.contains(a.pkg);
                    loading.setVisibility(View.GONE);
                    filter.run();
                });
            });
        }
    }

    /** Reloads the cached app list, e.g. after installing an app. Call off the main thread. */
    private synchronized void reloadApps() {
        apps = null;
        installedApps();
    }

    private synchronized boolean appsLoaded() {
        return apps != null;
    }

    private synchronized List<App> installedApps() {
        if (apps != null) return apps;
        PackageManager pm = getPackageManager();
        List<App> list = new ArrayList<>();
        for (ApplicationInfo info : pm.getInstalledApplications(0)) {
            if (info.packageName.equals(getPackageName())) continue;
            App a = new App();
            a.pkg = info.packageName;
            a.label = String.valueOf(info.loadLabel(pm));
            a.system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            list.add(a);
        }
        apps = list;
        return list;
    }

    private static Set<String> packages(String out) {
        Set<String> set = new HashSet<>();
        for (String line : out.split("\n")) {
            if (PACKAGE.matcher(line.trim()).matches()) set.add(line.trim());
        }
        return set;
    }

    private static String error(Shell.Result r) {
        return r.ok() ? null : (r.out.isEmpty() ? "Failed." : r.out);
    }

    // ---- Running apps -------------------------------------------------------------------

    private void openRunning() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView summary = text("Loading…", 15, true);
        page.addView(summary);
        TextView help = text("Tap an app to stop it. System processes can't be stopped.", 13, false);
        help.setAlpha(0.7f);
        page.addView(help);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Running apps", page);

        Adapter<Proc> adapter = new Adapter<>(false, (p, row) -> {
            row.first.setText(p.label);
            row.second.setText(Formatter.formatShortFileSize(this, p.kb * 1024)
                    + (p.label.equals(p.name) ? "" : "  ·  " + p.name));
            showIcon(row, p.name.split(":")[0]);
        });
        listView.setAdapter(adapter);
        final List<Proc> procs = new ArrayList<>();
        final int[] sort = {0};
        Runnable apply = () -> {
            if (sort[0] == 1) Collections.sort(procs, (x, y) -> x.label.compareToIgnoreCase(y.label));
            else Collections.sort(procs, (x, y) -> Long.compare(y.kb, x.kb));
            adapter.set(procs);
        };
        page.addView(sortBar(new String[]{"Memory", "Name"}, i -> {
            sort[0] = i;
            apply.run();
        }), 2);
        Runnable[] load = new Runnable[1];
        load[0] = () -> worker.execute(() -> {
            List<Proc> found = runningProcesses();
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
            main.post(() -> {
                summary.setText("Memory: " + Formatter.formatShortFileSize(this, mem.totalMem - mem.availMem)
                        + " used of " + Formatter.formatShortFileSize(this, mem.totalMem));
                procs.clear();
                procs.addAll(found);
                apply.run();
            });
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Proc p = adapter.getItem(position);
            if (p.pkg == null) {
                toast("This is a system process and can't be stopped.");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Stop " + p.label + "?")
                    .setMessage("It closes now and can start again when you open it or it's needed.")
                    .setPositiveButton("Stop", (d, w) -> worker.execute(() -> {
                        String error = error(Shell.run("am force-stop " + Shell.quote(p.pkg)));
                        main.post(() -> {
                            if (error != null) toast(error);
                            load[0].run();
                        });
                    }))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        load[0].run();
    }

    private List<Proc> runningProcesses() {
        List<Proc> list = new ArrayList<>();
        String out = Shell.run("dumpsys meminfo | sed -n '/Total PSS by process/,/^$/p'").out;
        PackageManager pm = getPackageManager();
        for (String line : out.split("\n")) {
            Matcher m = MEMINFO.matcher(line);
            if (!m.find()) continue;
            Proc p = new Proc();
            p.kb = Long.parseLong(m.group(1).replace(",", ""));
            p.name = m.group(2);
            String pkg = p.name.split(":")[0];
            p.label = p.name;
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                p.label = String.valueOf(info.loadLabel(pm));
                boolean critical = (info.flags & ApplicationInfo.FLAG_PERSISTENT) != 0
                        || pkg.equals("android") || pkg.equals(getPackageName());
                if (!critical) p.pkg = pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            list.add(p);
        }
        return list;
    }

    // ---- Phone info -----------------------------------------------------------------------

    private void openInfo() {
        infoPage("Phone info", this::phoneInfo);
    }

    /** A page of key/value rows; an item with a null value starts a new section. */
    private void infoPage(String title, InfoJob job) {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), dp(24));
        page.addView(text("Loading…", 15, false));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        push(title, scroll);
        worker.execute(() -> {
            List<String[]> info = job.run();
            main.post(() -> {
                page.removeAllViews();
                if (info.isEmpty()) {
                    page.addView(note("Nothing to show."));
                    return;
                }
                for (String[] item : info) {
                    if (item[1] == null) {
                        section(page, item[0]);
                        continue;
                    }
                    TextView key = text(item[0], 13, false);
                    key.setAlpha(0.7f);
                    key.setPadding(0, dp(8), 0, 0);
                    page.addView(key);
                    TextView value = text(item[1], 16, false);
                    value.setTextIsSelectable(true);
                    page.addView(value);
                }
            });
        });
    }

    /** A read-only, scrollable, monospace view of a shell command's output, with Refresh. */
    private void openLog(String title, String command, String empty) {
        LinearLayout page = vertical();
        Button refresh = button("Refresh", null);
        refresh.setPadding(dp(12), dp(4), dp(12), 0);
        page.addView(refresh);
        TextView log = text("Loading…", 12, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(dp(16), dp(8), dp(16), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        push(title, page);
        Runnable load = () -> worker.execute(() -> {
            String out = Shell.run(command).out;
            main.post(() -> log.setText(out.trim().isEmpty() ? empty : out));
        });
        refresh.setOnClickListener(v -> load.run());
        load.run();
    }

    private List<String[]> phoneInfo() {
        List<String[]> info = new ArrayList<>();
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        info.add(new String[]{"Battery", null});
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            info.add(new String[]{"Charge", level * 100 / Math.max(scale, 1) + "%  ·  "
                    + batteryStatus(battery.getIntExtra(BatteryManager.EXTRA_STATUS, 1))});
            info.add(new String[]{"Health", batteryHealth(battery.getIntExtra(BatteryManager.EXTRA_HEALTH, 1))});
            info.add(new String[]{"Temperature", String.format(Locale.ROOT, "%.1f °C",
                    battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f)});
            info.add(new String[]{"Voltage", battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) + " mV"});
            String tech = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            if (!TextUtils.isEmpty(tech)) info.add(new String[]{"Type", tech});
        }
        int current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (current != 0 && current != Integer.MIN_VALUE) {
            // Phones report either µA or mA; values above 20 A must be µA.
            int ma = Math.abs(current) > 20_000 ? current / 1000 : current;
            info.add(new String[]{"Current", ma + " mA"});
        }
        String cycles = null;
        if (battery != null && battery.getIntExtra("android.os.extra.CYCLE_COUNT", -1) >= 0) {
            cycles = String.valueOf(battery.getIntExtra("android.os.extra.CYCLE_COUNT", -1));
        }
        if (Shell.ready()) {
            if (cycles == null) {
                String out = Shell.run("cat /sys/class/power_supply/*/cycle_count 2>/dev/null | head -1").out;
                if (out.matches("\\d+")) cycles = out;
            }
            String stats = Shell.run("dumpsys batterystats | grep -m3 -iE "
                    + "'Estimated battery capacity|learned battery capacity'").out;
            for (String line : stats.split("\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) info.add(new String[]{line.substring(0, colon).trim(), line.substring(colon + 1).trim()});
            }
        }
        info.add(new String[]{"Charge cycles", cycles != null ? cycles : "Not reported by this phone"});

        info.add(new String[]{"Memory & storage", null});
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
        info.add(new String[]{"RAM", Formatter.formatShortFileSize(this, mem.availMem) + " free of "
                + Formatter.formatShortFileSize(this, mem.totalMem)});
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        info.add(new String[]{"Storage", Formatter.formatShortFileSize(this, fs.getAvailableBytes())
                + " free of " + Formatter.formatShortFileSize(this, fs.getTotalBytes())});

        info.add(new String[]{"System", null});
        info.add(new String[]{"Model", Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")"});
        info.add(new String[]{"Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"});
        info.add(new String[]{"Security patch", Build.VERSION.SECURITY_PATCH});
        info.add(new String[]{"Build", Build.DISPLAY});
        info.add(new String[]{"Kernel", System.getProperty("os.version")});
        info.add(new String[]{"Processor", Build.HARDWARE + "  ·  " + TextUtils.join(", ", Build.SUPPORTED_ABIS)});
        info.add(new String[]{"Uptime", DateUtils.formatElapsedTime(SystemClock.elapsedRealtime() / 1000)});
        return info;
    }

    private static String batteryStatus(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            default: return "Unknown";
        }
    }

    private static String batteryHealth(int health) {
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheating";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "Failure";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            default: return "Unknown";
        }
    }

    /**
     * Asks GitHub for the latest release: {version, APK download link}, or null if GitHub can't be
     * reached. Called when "Check for updates" is tapped, or when TinyKit opens with "Check for
     * updates on start" on; nothing checks in the background.
     */
    private String[] latestRelease() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://api.github.com/repos/Order52/tinykit/releases/latest").openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            String body;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                body = out.toString("UTF-8");
            }
            Matcher tag = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([^\"]+)\"").matcher(body);
            if (!tag.find()) return null;
            Matcher url = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").matcher(body);
            return new String[]{tag.group(1), url.find() ? url.group(1) : REPO + "/releases/latest"};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * With "Check for updates on start" on, checks when TinyKit opens (at most once an hour) and
     * shows one popup per new version, so it never nags about the same update twice.
     */
    private void autoCheckForUpdate() {
        long now = System.currentTimeMillis();
        if (!prefs.getBoolean("update_on_start", false)
                || Math.abs(now - prefs.getLong("update_checked", 0)) < 3_600_000) return;
        prefs.edit().putLong("update_checked", now).apply();
        thumbs.execute(() -> {
            String[] latest = latestRelease();
            if (latest == null || compareVersions(latest[0], versionName(getPackageName())) <= 0
                    || latest[0].equals(prefs.getString("update_notified", ""))) return;
            prefs.edit().putString("update_notified", latest[0]).apply();
            main.post(() -> {
                if (!isFinishing() && !isDestroyed()) showUpdate(latest[0], latest[1]);
            });
        });
    }

    private void showUpdate(String version, String download) {
        new AlertDialog.Builder(this)
                .setTitle("Update available")
                .setMessage("TinyKit " + version + " is out (you have " + versionName(getPackageName()) + ").")
                .setPositiveButton("Download", (d, w) -> openUrl(download))
                .setNegativeButton("Later", null)
                .show();
    }

    /** Asks the launcher to place the speed-up widget (Android 8+); otherwise explains how to add it. */
    private void addBoostWidget() {
        AppWidgetManager manager = getSystemService(AppWidgetManager.class);
        if (Build.VERSION.SDK_INT >= 26 && manager != null && manager.isRequestPinAppWidgetSupported()
                && manager.requestPinAppWidget(new ComponentName(this, BoostWidget.class), null, null)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Add the widget")
                .setMessage("Touch and hold an empty spot on your home screen, tap Widgets, then drag "
                        + "TinyKit's \"Speed up\" widget onto the screen.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void checkForUpdate(View row) {
        TextView status = (TextView) row.getTag();
        if (!row.isEnabled()) return;
        row.setEnabled(false);
        status.setText("Checking…");
        worker.execute(() -> {
            String[] latest = latestRelease();
            String current = versionName(getPackageName());
            String found = latest == null ? null : latest[0];
            String download = latest == null ? null : latest[1];
            main.post(() -> {
                row.setEnabled(true);
                if (found == null) {
                    status.setText("Couldn't reach GitHub. Check your internet and try again.");
                } else if (compareVersions(found, current) > 0) {
                    status.setText("Version " + found + " is available.");
                    new AlertDialog.Builder(this)
                            .setTitle("Update available")
                            .setMessage("TinyKit " + found + " is out (you have " + current + ").")
                            .setPositiveButton("Download", (d, w) -> openUrl(download))
                            .setNegativeButton("Later", null)
                            .show();
                } else {
                    status.setText("You have the latest version (" + current + ").");
                }
            });
        });
    }

    private static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parseInt(x[i]) : 0;
            int q = i < y.length ? parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.replaceAll("\\D.*", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name) + " " + versionName(getPackageName()))
                .setMessage("Made by Ali Alakbar\n\nSmall Android tweaks powered by Shizuku. "
                        + "Free and open source (MIT).\n\n" + REPO)
                .setPositiveButton("GitHub", (d, w) -> openUrl(REPO))
                .setNegativeButton("Close", null)
                .show();
    }

    // ---- Crash log ------------------------------------------------------------------------------

    private void openCrashes() {
        LinearLayout page = vertical();
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(dp(12), dp(4), dp(12), 0);
        Button refresh = button("Refresh", null);
        Button clear = button("Clear", null);
        buttons.addView(refresh, weight());
        buttons.addView(clear, weight());
        page.addView(buttons);
        TextView log = text("Loading…", 12, false);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(dp(16), dp(8), dp(16), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Crash log", page);

        Runnable load = () -> worker.execute(() -> {
            String out = Shell.run("logcat -b crash -d -v time -t 500 | grep -v '^--------- beginning'").out;
            main.post(() -> {
                log.setText(out.isEmpty()
                        ? "No crashes recorded since the phone started.\n\nAndroid clears this log on restart."
                        : out);
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            });
        });
        refresh.setOnClickListener(v -> load.run());
        clear.setOnClickListener(v -> worker.execute(() -> {
            Shell.run("logcat -b crash -c");
            load.run();
        }));
        load.run();
    }

    // ---- Tiles catalog --------------------------------------------------------------------------

    private void openTiles() {
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
                {Tiles.HideDev.class, "Hide dev options", "Hide developer options from apps.", R.drawable.ic_tile_hidedev},
                {Tiles.ExtraDim.class, "Extra dim", "Dim below the usual minimum.", R.drawable.ic_tile_dim},
                {Tiles.ClearCaches.class, "Clear caches", "Free every app's cached space.", R.drawable.ic_tile_cache},
                {Tiles.MicCam.class, "Mic & camera off", "Block the mic and camera for every app.", R.drawable.ic_tile_miccam},
                {Tiles.Memory.class, "Free RAM", "Stop background apps.", R.drawable.ic_tile_ram},
                {Tiles.ScreenRecord.class, "Screen record", "Record the screen to Movies.", R.drawable.ic_tile_record}};
        LinearLayout list = vertical();
        list.addView(note("Tap a tile to add it to your quick panel. On Android 13+ that's one tap; "
                + "on older phones TinyKit shows where to drag it."));
        for (Object[] t : catalog) {
            Class<?> service = (Class<?>) t[0];
            String name = (String) t[1];
            String about = (String) t[2];
            int icon = (Integer) t[3];
            TextView add = text("Add", 14, true);
            add.setTextColor(attrColor(android.R.attr.colorAccent));
            add.setPadding(dp(12), 0, dp(8), 0);
            row(list, name, about, add, () -> addTile(service, name, icon, about), false);
        }
        push("TinyKit tiles", scroll(list));
    }

    // ---- Run a command --------------------------------------------------------------------------

    private void openRunCommand() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        page.addView(note("Runs as the shell (adb) user through Shizuku. Output and exit code show below."));
        EditText input = new EditText(this);
        input.setHint("e.g. getprop ro.product.model");
        input.setSingleLine(false);
        input.setTypeface(Typeface.MONOSPACE);
        page.addView(input);
        Button run = button("Run", null);
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(-1, -2);
        full.setMargins(dp(4), dp(4), dp(4), dp(4));
        page.addView(run, full);
        TextView output = text("", 12, false);
        output.setTypeface(Typeface.MONOSPACE);
        output.setTextIsSelectable(true);
        output.setPadding(dp(16), dp(8), dp(16), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Run a command", page);
        run.setOnClickListener(v -> {
            String cmd = input.getText().toString().trim();
            if (cmd.isEmpty()) return;
            if (!Shell.ready()) {
                toast("Start Shizuku and allow TinyKit first.");
                return;
            }
            output.setText("Running…");
            worker.execute(() -> {
                Shell.Result r = Shell.run(cmd);
                main.post(() -> output.setText((r.out.isEmpty() ? "(no output)" : r.out)
                        + "\n\n[exit " + r.code + "]"));
            });
        });
    }

    // ---- Recover deleted files ------------------------------------------------------------------

    private static final String RECOVER_DIR = "/sdcard/Download/TinyKit/Recovered";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long THUMB_MAX = 10L * 1024 * 1024;
    private static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "dng"));

    private static final class Deleted {
        String path;
        String name;
        String source;
        String sizeText;
        String date;
        long bytes;
        long mtime;
        boolean image;
    }

    private void openRecover() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Files found in every recycle bin and trash, with thumbnails. "
                + "Tap one to restore it to Download/TinyKit/Recovered. Largest first.", 13, false);
        help.setAlpha(0.7f);
        page.addView(help);
        TextView loading = text("Searching…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Recover files", page);

        RecoverAdapter adapter = new RecoverAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Deleted item = adapter.getItem(position);
            new AlertDialog.Builder(this)
                    .setTitle("Restore this file?")
                    .setMessage(item.name + "\n\nCopied to Download/TinyKit/Recovered. The original stays in the bin.")
                    .setPositiveButton("Restore", (d, w) -> restoreFile(item))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        final List<Deleted> items = new ArrayList<>();
        final int[] sort = {0};
        Runnable apply = () -> {
            if (sort[0] == 1) Collections.sort(items, (x, y) -> Long.compare(y.mtime, x.mtime));
            else if (sort[0] == 2) Collections.sort(items, (x, y) -> x.name.compareToIgnoreCase(y.name));
            else Collections.sort(items, (x, y) -> Long.compare(y.bytes, x.bytes));
            adapter.set(items);
        };
        page.addView(sortBar(new String[]{"Size", "Date", "Name"}, i -> {
            sort[0] = i;
            apply.run();
        }), 1);
        worker.execute(() -> {
            List<Deleted> found = scanRecoverable();
            main.post(() -> {
                loading.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
                if (found.isEmpty()) loading.setText("Nothing found in any recycle bin or trash.");
                items.clear();
                items.addAll(found);
                apply.run();
            });
        });
    }

    /** One fast pass with {@code find -printf} over every trash location. */
    private List<Deleted> scanRecoverable() {
        String find = "find /sdcard/ -type f \\( -path '*/.Gallery2/recycle/*' "
                + "-o -path '*/.File_Recycle/*' -o -path '*/.fvTrash/*' -o -path '*/.thumbnails/*' "
                + "-o -path '*/LOST.DIR/*' -o -name '.trashed-*' \\) -printf '%s|%T@|%p\\n' 2>/dev/null";
        long now = System.currentTimeMillis();
        List<Deleted> items = new ArrayList<>();
        for (String line : Shell.run(find).out.split("\n")) {
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) continue;
            long bytes;
            try {
                bytes = Long.parseLong(parts[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (bytes <= 0) continue;
            long mtime = 0;
            try {
                mtime = (long) (Double.parseDouble(parts[1].trim()) * 1000);
            } catch (NumberFormatException ignored) {
            }
            String path = parts[2];
            String base = path.substring(path.lastIndexOf('/') + 1);
            Deleted d = new Deleted();
            d.path = path;
            d.bytes = bytes;
            d.mtime = mtime;
            d.sizeText = Formatter.formatShortFileSize(this, bytes);
            d.date = mtime > 0 ? DateUtils.getRelativeTimeSpanString(mtime, now, DateUtils.DAY_IN_MILLIS).toString() : "";
            if (path.contains("/.Gallery2/recycle/")) {
                d.source = "Gallery recycle bin";
                String decoded = decodeGalleryName(base);
                d.name = decoded != null ? decoded : base;
                d.image = true;
            } else if (base.startsWith(".trashed-")) {
                d.source = "Trash";
                d.name = base.replaceFirst("^\\.trashed-\\d+-", "");
            } else if (path.contains("/.File_Recycle/")) {
                d.source = "File recycle bin";
                d.name = base;
            } else if (path.contains("/.fvTrash/")) {
                d.source = "Trash";
                d.name = base;
            } else if (path.contains("/LOST.DIR/")) {
                d.source = "Recovered fragments";
                d.name = base;
            } else {
                d.source = "Thumbnail cache";
                d.name = base.isEmpty() ? "thumbnail" : base;
            }
            if (!d.image) d.image = IMAGE_EXT.contains(extensionOf(d.name));
            items.add(d);
            if (items.size() >= 6000) break;
        }
        Collections.sort(items, (x, y) -> Long.compare(y.bytes, x.bytes));
        return items;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Rows with a lazily-loaded thumbnail for images and a file icon otherwise. */
    private final class RecoverAdapter extends BaseAdapter {
        private List<Deleted> items = new ArrayList<>();

        void set(List<Deleted> list) {
            items = list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Deleted getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView icon;
            TextView name, sub;
            View row;
            if (convertView == null) {
                LinearLayout line = new LinearLayout(MainActivity.this);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(dp(16), dp(8), dp(16), dp(8));
                icon = new ImageView(MainActivity.this);
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(48), dp(48));
                ip.setMargins(0, 0, dp(14), 0);
                line.addView(icon, ip);
                LinearLayout texts = vertical();
                name = text("", 15, false);
                name.setMaxLines(1);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                sub = text("", 12, false);
                sub.setAlpha(0.65f);
                texts.addView(name);
                texts.addView(sub);
                line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
                line.setTag(new Object[]{icon, name, sub});
                row = line;
            } else {
                row = convertView;
                Object[] tag = (Object[]) row.getTag();
                icon = (ImageView) tag[0];
                name = (TextView) tag[1];
                sub = (TextView) tag[2];
            }
            Deleted d = items.get(position);
            name.setText(d.name);
            sub.setText(d.source + "  ·  " + d.sizeText + (d.date.isEmpty() ? "" : "  ·  " + d.date));
            icon.setTag(d.path);
            Bitmap cached = thumbCache.get(d.path);
            if (cached != null) {
                icon.setImageBitmap(cached);
            } else {
                icon.setImageResource(d.image ? R.drawable.ic_image : R.drawable.ic_file);
                if (d.image && d.bytes <= THUMB_MAX) loadThumb(d, icon);
            }
            return row;
        }
    }

    private void loadThumb(Deleted d, ImageView icon) {
        thumbs.execute(() -> {
            if (!d.path.equals(icon.getTag()) || thumbCache.containsKey(d.path)) return;
            Bitmap bitmap = makeThumb(d.path);
            if (bitmap == null) return;
            thumbCache.put(d.path, bitmap);
            main.post(() -> {
                if (d.path.equals(icon.getTag())) icon.setImageBitmap(bitmap);
            });
        });
    }

    /**
     * Reads a file's bytes as the shell user through Shizuku (base64, so it survives the pipe) and
     * decodes a small thumbnail. This works for files in trash that the app can't open directly.
     */
    private Bitmap makeThumb(String path) {
        Shell.Result r = Shell.run("base64 " + Shell.quote(path) + " 2>/dev/null");
        if (!r.ok() || r.out.isEmpty()) return null;
        try {
            byte[] bytes = android.util.Base64.decode(r.out.replaceAll("\\s", ""), android.util.Base64.NO_WRAP);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outHeight / sample > 160 || bounds.outWidth / sample > 160) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Gallery2 names files as Base32 of "…|name|.ext|mime|…"; decode and rebuild the real name. */
    private static String decodeGalleryName(String base32) {
        try {
            String cleaned = base32.toUpperCase(Locale.ROOT).replaceAll("[^A-Z2-7]", "");
            if (cleaned.length() < 8) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int buffer = 0, bits = 0;
            for (char c : cleaned.toCharArray()) {
                buffer = (buffer << 5) | BASE32.indexOf(c);
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((buffer >> bits) & 0xFF);
                }
            }
            String decoded = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            if (!decoded.contains("|")) return null;
            String[] fields = decoded.split("\\|");
            String name = null, ext = "";
            for (String f : fields) {
                if (f.contains("/") || f.equals("null") || f.isEmpty()) continue;
                if (f.startsWith(".") && f.length() <= 6) {
                    ext = f;
                } else if (f.matches(".*[A-Za-z].*") && !f.matches("\\d+")) {
                    // Prefer the longest field with letters: the real base name, not an id.
                    if (name == null || f.length() > name.length()) name = f.trim();
                }
            }
            if (name == null) return null;
            return name.contains(".") ? name : name + ext;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void restoreFile(Deleted item) {
        toast("Restoring " + item.name + "…");
        worker.execute(() -> {
            String dest = RECOVER_DIR + "/" + fileName(item.name);
            Shell.Result r = Shell.run("mkdir -p " + RECOVER_DIR + "; cp " + Shell.quote(item.path)
                    + " " + Shell.quote(dest) + " && echo ok");
            main.post(() -> {
                if (r.ok()) {
                    MediaScannerConnection.scanFile(this, new String[]{dest}, null, null);
                    toast("Restored to Download/TinyKit/Recovered");
                } else {
                    toast(r.out.isEmpty() ? "Couldn't restore it." : r.out);
                }
            });
        });
    }

    // ---- Animation speed ------------------------------------------------------------------------

    private static final float[] ANIM_SCALES = {0f, 0.5f, 1f, 1.5f, 2f};
    private static final String[] ANIM_LABELS = {"Off", "0.5× (faster)", "Normal (1×)", "1.5×", "2× (slower)"};

    private void refreshAnimationSpeed(View row) {
        TextView sub = (TextView) row.getTag();
        worker.execute(() -> {
            String label = animLabel(animScale());
            main.post(() -> sub.setText(label));
        });
    }

    private float animScale() {
        try {
            return Float.parseFloat(Shell.run("settings get global window_animation_scale").out.trim());
        } catch (NumberFormatException e) {
            return 1f;
        }
    }

    private static String animLabel(float scale) {
        for (int i = 0; i < ANIM_SCALES.length; i++) {
            if (Math.abs(ANIM_SCALES[i] - scale) < 0.01f) return ANIM_LABELS[i];
        }
        return scale + "×";
    }

    private void chooseAnimationSpeed(View row) {
        worker.execute(() -> {
            float cur = animScale();
            int selected = 2;
            for (int i = 0; i < ANIM_SCALES.length; i++) {
                if (Math.abs(ANIM_SCALES[i] - cur) < 0.01f) selected = i;
            }
            int finalSel = selected;
            main.post(() -> new AlertDialog.Builder(this)
                    .setTitle("Animation speed")
                    .setSingleChoiceItems(ANIM_LABELS, finalSel, (d, which) -> {
                        d.dismiss();
                        float s = ANIM_SCALES[which];
                        worker.execute(() -> {
                            String err = error(Shell.run("settings put global window_animation_scale " + s
                                    + "; settings put global transition_animation_scale " + s
                                    + "; settings put global animator_duration_scale " + s));
                            main.post(() -> {
                                if (err != null) toast(err);
                                refreshAnimationSpeed(row);
                            });
                        });
                    })
                    .setNegativeButton("Cancel", null)
                    .show());
        });
    }

    // ---- Advanced reboot ------------------------------------------------------------------------

    private void openReboot() {
        String[] labels = {"Reboot", "Reboot to recovery", "Reboot to bootloader", "Soft reboot"};
        String[] cmds = {"svc power reboot", "svc power reboot recovery",
                "svc power reboot bootloader", "svc power reboot userspace"};
        String[] notes = {"", "", "",
                "Restarts Android without a full reboot. Needs Android 11+; otherwise it reboots normally."};
        new AlertDialog.Builder(this)
                .setTitle("Advanced reboot")
                .setItems(labels, (d, which) -> confirmReboot(labels[which], cmds[which], notes[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmReboot(String label, String cmd, String note) {
        String message = (label.contains("recovery") || label.contains("bootloader")
                ? "The phone will restart into that mode now."
                : "The phone will restart now.") + (note.isEmpty() ? "" : "\n\n" + note);
        new AlertDialog.Builder(this)
                .setTitle(label + "?")
                .setMessage(message)
                .setPositiveButton(label, (d, w) -> {
                    toast(label + "…");
                    worker.execute(() -> {
                        Shell.Result r = Shell.run(cmd);
                        if (!r.ok()) {
                            main.post(() -> toast("Couldn't do that. " + (r.out.isEmpty() ? "" : r.out)));
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Settings backup & restore --------------------------------------------------------------

    private void openBackupRestore() {
        LinearLayout list = vertical();
        list.addView(note("Backs up global, secure and system settings to Download/TinyKit. Restoring "
                + "puts them back; some keys are read-only and are skipped."));
        action(list, "Back up settings now", "Save all settings to a file.", () -> {
            Shell.Result r = Shell.run("d=" + BACKUP_DIR + "; mkdir -p $d; f=$d/settings-$(date +%Y%m%d_%H%M%S).txt; "
                    + "{ for t in global secure system; do settings list $t 2>/dev/null | sed \"s/^/$t /\"; done; } "
                    + "> $f && wc -l < $f");
            if (!r.ok()) return "Failed: " + (r.out.isEmpty() ? "couldn't write the file." : r.out);
            return "Saved " + r.out.trim() + " settings to Download/TinyKit.";
        });
        link(list, "Restore settings", "Pick a backup file and apply it.", this::openRestore);
        section(list, "TinyKit setup");
        list.addView(note("Saves your choices in TinyKit: blocked and allowed apps, the clipboard guard, "
                + "stopped background apps, kept-alive and hidden apps, special access, removed system "
                + "apps. Restoring puts all of it back, for example after a factory reset."));
        action(list, "Back up TinyKit setup", "Save these choices to Download/TinyKit.", this::backupSetup);
        link(list, "Restore TinyKit setup", "Pick a setup file and apply it.", this::openRestoreSetup);
        push("Settings backup & restore", scroll(list));
    }

    /** App-ops TinyKit's pickers set, as {op, mode}: the ones whose apps a setup backup lists. */
    private static final String[][] SETUP_OPS = {
            {"READ_CLIPBOARD", "ignore"}, {"RUN_ANY_IN_BACKGROUND", "ignore"}, {"WAKE_LOCK", "ignore"},
            {"REQUEST_INSTALL_PACKAGES", "allow"}, {"SYSTEM_ALERT_WINDOW", "allow"},
            {"WRITE_SETTINGS", "allow"}, {"MANAGE_EXTERNAL_STORAGE", "allow"}, {"GET_USAGE_STATS", "allow"}};

    private String backupSetup() {
        StringBuilder out = new StringBuilder("# TinyKit setup\n");
        out.append("mode\t").append(FirewallPolicy.allowMode(prefs) ? "allow" : "block").append('\n');
        for (String pkg : FirewallPolicy.saved(prefs, FirewallPolicy.BLOCKED)) out.append("blocked\t").append(pkg).append('\n');
        for (String pkg : FirewallPolicy.saved(prefs, FirewallPolicy.ALLOWED)) out.append("allowed\t").append(pkg).append('\n');
        if (prefs.getBoolean(Restore.PREF_FORCE_DARK, false)) out.append("dark\t1\n");
        for (String[] op : SETUP_OPS) {
            for (String pkg : packages(Shell.run("appops query-op " + op[0] + " " + op[1]).out)) {
                out.append("op\t").append(op[0]).append('\t').append(op[1]).append('\t').append(pkg).append('\n');
            }
        }
        for (String line : Shell.run("dumpsys deviceidle whitelist").out.split("\n")) {
            String[] parts = line.trim().split(",");
            if (parts.length >= 2 && parts[0].equals("user")) out.append("alive\t").append(parts[1]).append('\n');
        }
        for (String pkg : disabledPackages()) out.append("hidden\t").append(pkg).append('\n');
        for (Pkg p : debloatList(true)) out.append("removed\t").append(p.pkg).append('\n');
        String file = BACKUP_DIR + "/setup-" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                .format(new java.util.Date()) + ".txt";
        Shell.Result r = Shell.run("mkdir -p " + BACKUP_DIR + "; cat > " + Shell.quote(file),
                new java.io.ByteArrayInputStream(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (!r.ok()) return "Failed: " + (r.out.isEmpty() ? "couldn't write the file." : r.out);
        return "Saved " + (out.toString().split("\n").length - 1) + " choices to Download/TinyKit.";
    }

    private void openRestoreSetup() {
        worker.execute(() -> {
            List<String> files = new ArrayList<>();
            for (String line : Shell.run("ls -1t " + BACKUP_DIR + "/setup-*.txt 2>/dev/null").out.split("\n")) {
                if (!line.trim().isEmpty()) files.add(line.trim());
            }
            main.post(() -> {
                if (files.isEmpty()) {
                    toast("No setup backups yet. Back up first.");
                    return;
                }
                String[] names = new String[files.size()];
                for (int i = 0; i < names.length; i++) names[i] = files.get(i).substring(files.get(i).lastIndexOf('/') + 1);
                new AlertDialog.Builder(this)
                        .setTitle("Restore which setup?")
                        .setItems(names, (d, which) -> worker.execute(() -> {
                            String result = restoreSetup(files.get(which));
                            main.post(() -> new AlertDialog.Builder(this).setTitle("Restore")
                                    .setMessage(result).setPositiveButton("OK", null).show());
                        }))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    /** Applies a setup file. Apps that aren't installed any more are skipped. */
    private String restoreSetup(String file) {
        Set<String> blocked = new HashSet<>(), allowed = new HashSet<>();
        boolean allowMode = false, dark = false;
        StringBuilder script = new StringBuilder();
        int count = 0;
        for (String line : Shell.run("cat " + Shell.quote(file)).out.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 2) continue;
            // Package names are checked before they reach the shell.
            if (f.length > 1 && !f[0].equals("mode") && !f[0].equals("dark") && !PACKAGE.matcher(f[f.length - 1]).matches()) continue;
            String q = Shell.quote(f[f.length - 1]);
            switch (f[0]) {
                case "mode": allowMode = f[1].equals("allow"); break;
                case "dark": dark = true; break;
                case "blocked": blocked.add(f[1]); break;
                case "allowed": allowed.add(f[1]); break;
                case "op":
                    if (f.length == 4 && f[1].matches("[A-Z_]+") && f[2].matches("[a-z]+")) {
                        script.append("appops set ").append(q).append(' ').append(f[1]).append(' ').append(f[2]).append("; ");
                        if (f[1].equals("RUN_ANY_IN_BACKGROUND")) {
                            script.append("am set-standby-bucket ").append(q).append(" restricted >/dev/null 2>&1; ");
                        }
                    }
                    break;
                case "alive": script.append("dumpsys deviceidle whitelist +").append(q).append(" >/dev/null; "); break;
                case "hidden": script.append("pm disable-user --user 0 ").append(q).append(" >/dev/null 2>&1; "); break;
                case "removed": script.append("pm uninstall -k --user 0 ").append(q).append(" >/dev/null 2>&1; "); break;
                default: continue;
            }
            count++;
        }
        if (count == 0) return "That file has nothing TinyKit can restore.";
        prefs.edit().putStringSet(FirewallPolicy.BLOCKED, blocked).putStringSet(FirewallPolicy.ALLOWED, allowed)
                .putBoolean(FirewallPolicy.MODE, allowMode).putBoolean(Restore.PREF_FORCE_DARK, dark).apply();
        if (script.length() > 0) Shell.run(script.toString());
        if (dark) Shell.run("setprop debug.hwui.force_dark true");
        String firewall = FirewallPolicy.supported() ? FirewallPolicy.apply(this, prefs, true) : null;
        reloadApps();
        return "Restored " + count + " choices." + (firewall != null ? "\nThe internet block wasn't applied: " + firewall : "");
    }

    private void openRestore() {
        worker.execute(() -> {
            String out = Shell.run("ls -1t " + BACKUP_DIR + "/settings-*.txt 2>/dev/null").out;
            List<String> files = new ArrayList<>();
            for (String line : out.split("\n")) if (!line.trim().isEmpty()) files.add(line.trim());
            main.post(() -> {
                if (files.isEmpty()) {
                    toast("No backups yet. Back up first.");
                    return;
                }
                String[] names = new String[files.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = files.get(i).substring(files.get(i).lastIndexOf('/') + 1);
                }
                new AlertDialog.Builder(this)
                        .setTitle("Restore which backup?")
                        .setItems(names, (d, which) -> confirmRestore(files.get(which)))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
    }

    private void confirmRestore(String file) {
        new AlertDialog.Builder(this)
                .setTitle("Restore settings?")
                .setMessage("This writes the saved settings back. Read-only keys are skipped. "
                        + "Some changes may need a restart.")
                .setPositiveButton("Restore", (d, w) -> worker.execute(() -> {
                    Shell.Result r = Shell.run("while read t rest; do key=${rest%%=*}; val=${rest#*=}; "
                            + "[ -n \"$key\" ] && settings put $t \"$key\" \"$val\" 2>/dev/null; done < "
                            + Shell.quote(file) + "; echo done");
                    main.post(() -> toast(r.ok() ? "Settings restored." : "Failed: " + r.out));
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Network, charging, temperatures, device IDs --------------------------------------------

    /** Local network details render instantly; only the public IP is fetched in the background. */
    private void openNetworkInfo() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), dp(24));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        push("Network info", scroll);

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        LinkProperties lp = network == null ? null : cm.getLinkProperties(network);

        section(page, "Connection");
        String type = "Offline";
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) type = "Wi-Fi";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) type = "Mobile data";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) type = "Ethernet";
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) type = "VPN";
            else type = "Other";
        }
        addKv(page, "Type", type);
        if (caps != null && caps.getLinkDownstreamBandwidthKbps() > 0) {
            addKv(page, "Downlink", caps.getLinkDownstreamBandwidthKbps() / 1000 + " Mbps");
            addKv(page, "Uplink", caps.getLinkUpstreamBandwidthKbps() / 1000 + " Mbps");
        }
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            if (wi != null) {
                String ssid = wi.getSSID();
                if (ssid != null && !ssid.contains("unknown")) addKv(page, "SSID", ssid.replace("\"", ""));
                if (wi.getLinkSpeed() > 0) addKv(page, "Link speed", wi.getLinkSpeed() + " Mbps");
                addKv(page, "Signal", wi.getRssi() + " dBm");
                int f = wi.getFrequency();
                if (f > 0) {
                    addKv(page, "Frequency", f + " MHz  ·  "
                            + (f > 5925 ? "6 GHz" : f > 5000 ? "5 GHz" : "2.4 GHz"));
                }
            }
        }

        String gateway = null;
        if (lp != null) {
            section(page, "Addresses");
            if (lp.getInterfaceName() != null) addKv(page, "Interface", lp.getInterfaceName());
            for (LinkAddress la : lp.getLinkAddresses()) addKv(page, "IP", la.getAddress().getHostAddress());
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() != null && !r.getGateway().isAnyLocalAddress()) {
                    gateway = r.getGateway().getHostAddress();
                    addKv(page, "Gateway", gateway);
                }
            }
            for (InetAddress dns : lp.getDnsServers()) addKv(page, "DNS", dns.getHostAddress());
            if (Build.VERSION.SDK_INT >= 28 && lp.getPrivateDnsServerName() != null) {
                addKv(page, "Private DNS", lp.getPrivateDnsServerName());
            }
        }

        if (gateway != null && gateway.indexOf(':') < 0) {
            final String url = "http://" + gateway;
            section(page, "Router");
            row(page, "Open router settings", "Opens " + url + " in your browser.", arrow(),
                    () -> openUrl(url), false);
        }

        section(page, "Internet");
        TextView publicIp = addKv(page, "Public IP", "Checking…");
        boolean online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        if (!online) {
            publicIp.setText("Offline");
        } else {
            worker.execute(() -> {
                String pub = httpGet("https://api.ipify.org", 3000);
                if (pub == null) pub = httpGet("https://ifconfig.me/ip", 3000);
                String result = pub != null && pub.length() <= 45 && pub.matches("[0-9a-fA-F:.]+")
                        ? pub : "Couldn't reach the internet";
                main.post(() -> publicIp.setText(result));
            });
        }
    }

    /** Adds a dim key line and a selectable value line, returning the value view so it can update. */
    private TextView addKv(LinearLayout page, String key, String value) {
        TextView k = text(key, 13, false);
        k.setAlpha(0.7f);
        k.setPadding(0, dp(8), 0, 0);
        page.addView(k);
        TextView v = text(value, 16, false);
        v.setTextIsSelectable(true);
        page.addView(v);
        return v;
    }

    /** A short-timeout HTTP GET returning the trimmed body, or null. Call off the main thread. */
    private static String httpGet(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "TinyKit");
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return out.toString("UTF-8").trim();
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void openChargingInfo() {
        infoPage("Charging info", () -> {
            List<String[]> info = new ArrayList<>();
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            info.add(new String[]{"Now", null});
            if (battery != null) {
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, 1);
                info.add(new String[]{"Status", batteryStatus(status)});
                int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                String source = plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC charger"
                        : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                        : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "Wireless" : "Not plugged in";
                info.add(new String[]{"Power source", source});
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                info.add(new String[]{"Charge", level * 100 / Math.max(scale, 1) + "%"});
                info.add(new String[]{"Health", batteryHealth(battery.getIntExtra(BatteryManager.EXTRA_HEALTH, 1))});
                int volt = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
                info.add(new String[]{"Voltage", volt + " mV"});
                int current = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (current != 0 && current != Integer.MIN_VALUE) {
                    int ma = Math.abs(current) > 20_000 ? current / 1000 : current;
                    info.add(new String[]{"Current", ma + " mA"});
                    if (volt > 0) {
                        info.add(new String[]{"Power", String.format(Locale.ROOT, "%.2f W",
                                Math.abs(ma) * volt / 1_000_000f)});
                    }
                }
                info.add(new String[]{"Temperature", String.format(Locale.ROOT, "%.1f °C",
                        battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f)});
                String tech = battery.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                if (!TextUtils.isEmpty(tech)) info.add(new String[]{"Technology", tech});
            }
            if (Shell.ready()) {
                for (String line : Shell.run("dumpsys batterystats | grep -m3 -iE "
                        + "'Estimated battery capacity|learned battery capacity'").out.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) info.add(new String[]{line.substring(0, colon).trim(),
                            line.substring(colon + 1).trim()});
                }
            }
            return info;
        });
    }

    private void openTemperatures() {
        infoPage("Temperatures", () -> {
            List<String[]> info = new ArrayList<>();
            Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                info.add(new String[]{"Battery", String.format(Locale.ROOT, "%.1f °C",
                        battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f)});
            }
            if (Shell.ready()) {
                String cmd = "for z in /sys/class/thermal/thermal_zone*; do t=$(cat $z/type 2>/dev/null); "
                        + "v=$(cat $z/temp 2>/dev/null); [ -n \"$t\" ] && printf '%s\\t%s\\n' \"$t\" \"$v\"; done";
                for (String line : Shell.run(cmd).out.split("\n")) {
                    int tab = line.indexOf('\t');
                    if (tab <= 0) continue;
                    String zone = line.substring(0, tab).trim();
                    try {
                        float raw = Float.parseFloat(line.substring(tab + 1).trim());
                        float c = Math.abs(raw) > 1000 ? raw / 1000f : raw;
                        if (c > -40 && c < 200) info.add(new String[]{zone, String.format(Locale.ROOT, "%.1f °C", c)});
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (info.size() <= 1) info.add(new String[]{"Sensors", "This phone doesn't expose thermal zones."});
            return info;
        });
    }

    private void openDeviceIds() {
        infoPage("Device IDs", () -> {
            List<String[]> info = new ArrayList<>();
            info.add(new String[]{"Identifiers", null});
            info.add(new String[]{"Android ID",
                    Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID)});
            if (Shell.ready()) {
                String serial = Shell.run("getprop ro.serialno").out.trim();
                if (!serial.isEmpty()) info.add(new String[]{"Serial", serial});
                String mac = Shell.run("cat /sys/class/net/wlan0/address 2>/dev/null").out.trim();
                if (!mac.isEmpty()) info.add(new String[]{"Wi-Fi MAC", mac});
            }
            info.add(new String[]{"IMEI", "Dial *#06# to view"});
            info.add(new String[]{"Build", null});
            info.add(new String[]{"Fingerprint", Build.FINGERPRINT});
            info.add(new String[]{"Bootloader", Build.BOOTLOADER});
            String radio = Build.getRadioVersion();
            if (!TextUtils.isEmpty(radio)) info.add(new String[]{"Baseband", radio});
            info.add(new String[]{"Brand / model", Build.BRAND + " " + Build.MODEL});
            return info;
        });
    }

    // ---- Battery usage, wake-ups, vibration, app log --------------------------------------------

    private void openBatteryUsage() {
        rowsPage("Battery usage", "Estimated drain since the last full charge, biggest first.",
                "Couldn't read battery stats on this phone.", this::batteryUsage);
    }

    private List<String[]> batteryUsage() {
        List<String[]> rows = new ArrayList<>();
        String out = Shell.run("dumpsys batterystats --charged | sed -n '/Estimated power use/,/^$/p'").out;
        Pattern line = Pattern.compile("^(.+?): ([0-9][0-9.]*)");
        Set<String> skip = new HashSet<>(Arrays.asList("Capacity", "Computed drain", "actual drain"));
        List<float[]> order = new ArrayList<>();
        for (String raw : out.split("\n")) {
            Matcher m = line.matcher(raw.trim());
            if (!m.find()) continue;
            String who = m.group(1).trim();
            if (skip.contains(who)) continue;
            float mah;
            try {
                mah = Float.parseFloat(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            if (mah < 0.01f) continue;
            String pkg = "";
            if (who.regionMatches(true, 0, "uid ", 0, 4)) {
                String token = who.substring(4).trim();
                String p = uidPackage(token);
                pkg = p != null ? p : "";
                who = p != null ? label(p) : token;
            } else {
                who = who.replace('_', ' ');
                who = who.substring(0, 1).toUpperCase(Locale.ROOT) + who.substring(1);
            }
            rows.add(new String[]{who, String.format(Locale.ROOT, "%.2f mAh", mah), pkg});
            order.add(new float[]{rows.size() - 1, mah});
        }
        Collections.sort(order, (x, y) -> Float.compare(y[1], x[1]));
        List<String[]> sorted = new ArrayList<>();
        for (float[] o : order) sorted.add(rows.get((int) o[0]));
        return sorted;
    }

    private String uidLabel(String token) {
        String pkg = uidPackage(token);
        return pkg != null ? label(pkg) : token;
    }

    /** First package sharing a batterystats uid token like "u0a162" or "1000", or null. */
    private String uidPackage(String token) {
        int uid = -1;
        Matcher app = Pattern.compile("u(\\d+)a(\\d+)").matcher(token);
        if (app.find()) {
            uid = Integer.parseInt(app.group(1)) * 100000 + 10000 + Integer.parseInt(app.group(2));
        } else if (token.matches("\\d+")) {
            uid = Integer.parseInt(token);
        }
        if (uid >= 0) {
            String[] pkgs = getPackageManager().getPackagesForUid(uid);
            if (pkgs != null && pkgs.length > 0) return pkgs[0];
        }
        return null;
    }

    private void openWakeups() {
        rowsPage("Wake-ups", "What has held the phone awake since the last charge, longest first.",
                "Nothing recorded since the last charge, or this phone hides it.", this::wakeups);
    }

    private List<String[]> wakeups() {
        List<String[]> rows = new ArrayList<>();
        String out = Shell.run("dumpsys batterystats --charged | sed -n '/All partial wake locks/,/^$/p'").out;
        Pattern p = Pattern.compile("Wake lock (\\S+) (.+?): ([0-9hms ]+?) \\((\\d+) times\\)");
        for (String line : out.split("\n")) {
            Matcher m = p.matcher(line.trim());
            if (!m.find()) continue;
            String pkg = uidPackage(m.group(1));
            String who = pkg != null ? label(pkg) : m.group(1);
            String detail = "Held " + m.group(3).trim() + "  ·  " + m.group(4) + " times  ·  " + m.group(2);
            rows.add(new String[]{who, detail, pkg != null ? pkg : ""});
        }
        return rows;
    }

    private void openVibration() {
        rowsPage("Vibration log", "Recent vibrations, most recent first.",
                "No vibrations recorded since the phone started.", this::vibrations);
    }

    private List<String[]> vibrations() {
        String out = Shell.run("dumpsys vibrator_manager 2>/dev/null | sed -n '/Previous vibrations for usage/,$p'").out;
        if (out.trim().isEmpty()) {
            out = Shell.run("dumpsys vibrator 2>/dev/null | sed -n '/Previous vibrations/,$p'").out;
        }
        Pattern usageP = Pattern.compile("Previous vibrations for usage (\\w+)");
        Pattern timeP = Pattern.compile("(?:start|create)Time: ([\\d-]+ [\\d:.]+)");
        Pattern pkgP = Pattern.compile("opPkg[=:] ?([a-zA-Z0-9_.]+)");
        Pattern effP = Pattern.compile("effect=([A-Z_]+)");
        List<String[]> rows = new ArrayList<>();
        String usage = "";
        for (String raw : out.split("\n")) {
            String line = raw.trim();
            Matcher um = usageP.matcher(line);
            if (um.find()) {
                usage = capitalize(um.group(1));
                continue;
            }
            if (!line.contains("Time:")) continue;
            Matcher tm = timeP.matcher(line);
            Matcher pm = pkgP.matcher(line);
            Matcher em = effP.matcher(line);
            String when = tm.find() ? tm.group(1) : "";
            String pkg = pm.find() ? pm.group(1) : "";
            String who = pkg.isEmpty() ? "System" : label(pkg);
            String effect = em.find() ? capitalize(em.group(1).replace('_', ' ')) : "Vibration";
            String detail = (usage.isEmpty() ? "" : usage + "  ·  ") + effect + (when.isEmpty() ? "" : "  ·  " + when);
            rows.add(new String[]{who, detail, when, pkg});
        }
        Collections.sort(rows, (a, b) -> b[2].compareTo(a[2]));
        List<String[]> out2 = new ArrayList<>();
        for (String[] r : rows) out2.add(new String[]{r[0], r[1], r[3]});
        return out2;
    }

    private static String capitalize(String value) {
        value = value.toLowerCase(Locale.ROOT);
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** A simple two-line list page backed by a worker job that returns {line1, line2} rows. */
    private void rowsPage(String title, String help, String empty, InfoJob job) {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView helpView = text(help, 13, false);
        helpView.setAlpha(0.7f);
        page.addView(helpView);
        TextView loading = text("Loading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push(title, page);
        Adapter<String[]> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(a[0]);
            row.second.setText(a[1]);
            if (a.length > 2 && a[2] != null && a[2].contains(".")) showIcon(row, a[2]);
        });
        listView.setAdapter(adapter);
        final List<String[]> original = new ArrayList<>();
        final int[] sort = {0};
        Runnable apply = () -> {
            List<String[]> shown = new ArrayList<>(original);
            if (sort[0] == 1) Collections.sort(shown, (x, y) -> x[0].compareToIgnoreCase(y[0]));
            adapter.set(shown);
        };
        page.addView(sortBar(new String[]{"Default", "Name"}, i -> {
            sort[0] = i;
            apply.run();
        }), 1);
        worker.execute(() -> {
            List<String[]> rows = job.run();
            main.post(() -> {
                loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                if (rows.isEmpty()) loading.setText(empty);
                original.clear();
                original.addAll(rows);
                apply.run();
            });
        });
    }

    private void openAppLog() {
        openAppList("App log", "Pick an app to see its recent log output.", pkg -> openLog(label(pkg),
                "pid=$(pidof -s " + Shell.quote(pkg) + " 2>/dev/null); "
                        + "if [ -n \"$pid\" ]; then logcat -d -v time --pid=$pid -t 500; "
                        + "else logcat -d -v time -t 3000 | grep -F " + Shell.quote(pkg) + " | tail -500; fi",
                "No log output. The app may not be running, or this phone hides app logs."));
    }

    // ---- Apps: permissions & app-ops ------------------------------------------------------------

    /** Sensitive ops the per-app page offers, as {op, name, matching runtime permission}. */
    private static final String[][] PERMISSION_OPS = {
            {"COARSE_LOCATION", "Approximate location", "android.permission.ACCESS_COARSE_LOCATION"},
            {"FINE_LOCATION", "Precise location", "android.permission.ACCESS_FINE_LOCATION"},
            {"CAMERA", "Camera", "android.permission.CAMERA"},
            {"RECORD_AUDIO", "Microphone", "android.permission.RECORD_AUDIO"},
            {"READ_CONTACTS", "Read contacts", "android.permission.READ_CONTACTS"},
            {"WRITE_CONTACTS", "Modify contacts", "android.permission.WRITE_CONTACTS"},
            {"READ_CALENDAR", "Calendar", "android.permission.READ_CALENDAR"},
            {"READ_CALL_LOG", "Call log", "android.permission.READ_CALL_LOG"},
            {"READ_PHONE_STATE", "Phone", "android.permission.READ_PHONE_STATE"},
            {"READ_SMS", "SMS", "android.permission.READ_SMS"},
            {"READ_EXTERNAL_STORAGE", "Read storage", "android.permission.READ_EXTERNAL_STORAGE"},
            {"WRITE_EXTERNAL_STORAGE", "Write storage", "android.permission.WRITE_EXTERNAL_STORAGE"},
            {"BODY_SENSORS", "Body sensors", "android.permission.BODY_SENSORS"},
            {"ACTIVITY_RECOGNITION", "Physical activity", "android.permission.ACTIVITY_RECOGNITION"},
            {"POST_NOTIFICATION", "Notifications", "android.permission.POST_NOTIFICATIONS"}};

    /** Quieter ops most settings screens hide, as {op, name}. */
    private static final String[][] HIDDEN_OPS = {
            {"READ_CLIPBOARD", "Read clipboard"},
            {"WRITE_CLIPBOARD", "Write clipboard"},
            {"RUN_ANY_IN_BACKGROUND", "Run in background"},
            {"WAKE_LOCK", "Keep device awake"},
            {"SYSTEM_ALERT_WINDOW", "Draw over other apps"},
            {"GET_USAGE_STATS", "Read app usage"},
            {"MANAGE_EXTERNAL_STORAGE", "All files access"},
            {"START_FOREGROUND", "Foreground service"},
            {"VIBRATE", "Vibrate"}};

    /** Lists installed apps and calls {@code onPick} with the one tapped. */
    private void openAppList(String title, String help, OnText onPick) {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView helpView = text(help, 14, false);
        helpView.setAlpha(0.8f);
        page.addView(helpView);
        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        page.addView(search);
        CheckBox system = new CheckBox(this);
        system.setText("Show system apps");
        page.addView(system);
        final int[] sort = {0};
        TextView loading = text("Loading apps…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push(title, page);

        List<App> all = new ArrayList<>();
        Adapter<App> adapter = new Adapter<>(false, (app, row) -> {
            row.first.setText(app.label);
            row.second.setText(app.pkg);
            showIcon(row, app.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<App> shown = new ArrayList<>();
            for (App a : all) {
                if ((system.isChecked() || !a.system)
                        && (a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.contains(q))) {
                    shown.add(a);
                }
            }
            Collections.sort(shown, (x, y) -> sort[0] == 1
                    ? x.pkg.compareToIgnoreCase(y.pkg) : x.label.compareToIgnoreCase(y.label));
            adapter.set(shown);
        };
        page.addView(sortBar(new String[]{"Name", "Package"}, i -> {
            sort[0] = i;
            filter.run();
        }), 3);
        search.addTextChangedListener(watcher(filter));
        system.setOnCheckedChangeListener((v, checked) -> filter.run());
        listView.setOnItemClickListener((parent, view, position, id) -> onPick.run(adapter.getItem(position).pkg));
        Runnable show = () -> {
            all.clear();
            for (App a : installedApps()) all.add(a.copy());
            loading.setVisibility(View.GONE);
            filter.run();
        };
        // Instant when the cache is warm (it's preloaded at launch); otherwise load once off-thread.
        if (appsLoaded()) {
            show.run();
        } else {
            worker.execute(() -> {
                installedApps();
                main.post(show);
            });
        }
    }

    /**
     * One app's permissions and app-ops, each settable to Real, Fake or Off.
     *
     * Camera, microphone and location are controlled at the UID level on Android 12+, so they use
     * {@code appops set --uid} and a real "Off" revokes the permission; the quieter ops are plain
     * package-level app-ops.
     */
    private void openAppPage(String pkg) {
        LinearLayout list = vertical();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(16), dp(16), dp(4));
        ImageView appIc = new ImageView(this);
        appIc.setImageResource(android.R.drawable.sym_def_app_icon);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(52), dp(52));
        hp.setMargins(0, 0, dp(14), 0);
        head.addView(appIc, hp);
        LinearLayout htext = vertical();
        htext.addView(text(label(pkg), 20, true));
        TextView sub = text(pkg, 12, false);
        sub.setAlpha(0.6f);
        htext.addView(sub);
        head.addView(htext);
        list.addView(head);
        thumbs.execute(() -> {
            Bitmap b = iconBitmap(pkg, dp(52));
            if (b != null) main.post(() -> appIc.setImageBitmap(b));
        });
        list.addView(note("Tap Real, Fake or Off for each permission. Fake shows the app the "
                + "permission as granted while it actually gets nothing."));
        TextView loading = text("Reading permissions…", 14, false);
        loading.setPadding(dp(16), dp(8), dp(16), 0);
        list.addView(loading);
        push(label(pkg), scroll(list));

        worker.execute(() -> {
            Set<String> granted = grantedPermissions(pkg);
            Set<String> requested = requestedPermissions(pkg);
            Map<String, String> modes = opModes(pkg);
            main.post(() -> {
                list.removeView(loading);
                section(list, "Permissions");
                boolean any = false;
                for (String[] op : PERMISSION_OPS) {
                    if (requested.contains(op[2]) || granted.contains(op[2])) {
                        opRow(list, pkg, op, true, stateOf(true, granted.contains(op[2]), modes.get(op[0])));
                        any = true;
                    }
                }
                if (!any) list.addView(note("This app requests no sensitive permissions."));
                section(list, "Hidden app-ops");
                for (String[] op : HIDDEN_OPS) {
                    opRow(list, pkg, op, false, stateOf(false, false, modes.get(op[0])));
                }
            });
        });
    }

    /** A permission with an inline Real / Fake / Off segmented control — one tap to change. */
    private void opRow(LinearLayout list, String pkg, String[] op, boolean permission, String initial) {
        final String[] state = {initial};
        LinearLayout block = vertical();
        block.setPadding(dp(16), dp(10), dp(16), dp(6));
        block.addView(text(op[1], 16, false));
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(0, dp(8), 0, 0);
        final String[] keys = {"Real", "Fake", "Off"};
        final TextView[] pills = new TextView[3];
        final Runnable render = () -> {
            for (int i = 0; i < 3; i++) stylePill(pills[i], keys[i], keys[i].equals(state[0]));
        };
        for (int i = 0; i < 3; i++) {
            final String target = keys[i];
            TextView pill = text(target, 13, true);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(0, dp(9), 0, dp(9));
            pill.setOnClickListener(v -> {
                if (!Shell.ready()) {
                    toast("Start Shizuku and allow TinyKit first.");
                    return;
                }
                if (target.equals(state[0])) return;
                state[0] = target;
                render.run();
                worker.execute(() -> {
                    String err = setOp(pkg, op, permission, target);
                    String now = readState(pkg, op, permission);
                    main.post(() -> {
                        state[0] = now;
                        render.run();
                        if (err != null) toast(err);
                    });
                });
            });
            pills[i] = pill;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(i == 0 ? 0 : dp(4), 0, i == 2 ? 0 : dp(4), 0);
            seg.addView(pill, lp);
        }
        block.addView(seg);
        render.run();
        list.addView(block);
    }

    private void stylePill(TextView pill, String key, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(8));
        shape.setColor(selected ? stateColor(key) : 0xFF1C1C1C);
        pill.setBackground(shape);
        pill.setTextColor(selected ? 0xFF000000 : 0xFFBDBDBD);
    }

    private int stateColor(String state) {
        switch (state) {
            case "Fake": return attrColor(android.R.attr.colorAccent);
            case "Off": return 0xFFE57373;
            default: return 0xFF66BB6A;
        }
    }

    private static String stateOf(boolean permission, boolean granted, String mode) {
        if (permission) {
            if (!granted) return "Off";
            return "ignore".equals(mode) ? "Fake" : "Real";
        }
        if ("ignore".equals(mode)) return "Fake";
        if ("deny".equals(mode)) return "Off";
        return "Real";
    }

    private String setOp(String pkg, String[] op, boolean permission, String target) {
        String q = Shell.quote(pkg);
        if (permission) {
            String perm = op[2];
            switch (target) {
                case "Real":
                    return error(Shell.run("pm grant " + q + " " + perm + " 2>/dev/null; "
                            + "appops set --uid " + q + " " + op[0] + " allow"));
                case "Fake":
                    return error(Shell.run("pm grant " + q + " " + perm + " 2>/dev/null; "
                            + "appops set --uid " + q + " " + op[0] + " ignore"));
                default:
                    return error(Shell.run("pm revoke " + q + " " + perm + " 2>/dev/null; "
                            + "appops set --uid " + q + " " + op[0] + " ignore"));
            }
        }
        String mode = target.equals("Real") ? "allow" : target.equals("Fake") ? "ignore" : "deny";
        return error(Shell.run("appops set " + q + " " + op[0] + " " + mode));
    }

    /** Reads one op's live state after a change. Call off the main thread. */
    private String readState(String pkg, String[] op, boolean permission) {
        if (permission) {
            boolean granted = getPackageManager().checkPermission(op[2], pkg) == PackageManager.PERMISSION_GRANTED;
            return stateOf(true, granted, opMode(pkg, op[0], true));
        }
        return stateOf(false, false, opMode(pkg, op[0], false));
    }

    /**
     * Current mode of every op the page shows, in one shell round-trip. Permission ops are read at
     * the UID level, the hidden ops at the package level; their names never overlap.
     */
    private Map<String, String> opModes(String pkg) {
        String q = Shell.quote(pkg);
        StringBuilder cmd = new StringBuilder();
        for (String[] op : PERMISSION_OPS) {
            cmd.append("echo \"").append(op[0]).append("=$(appops get --uid ").append(q).append(' ')
                    .append(op[0]).append(" 2>/dev/null)\"; ");
        }
        for (String[] op : HIDDEN_OPS) {
            cmd.append("echo \"").append(op[0]).append("=$(appops get ").append(q).append(' ')
                    .append(op[0]).append(" 2>/dev/null)\"; ");
        }
        Map<String, String> modes = new HashMap<>();
        for (String line : Shell.run(cmd.toString()).out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            modes.put(line.substring(0, eq), extractMode(line.substring(eq + 1)));
        }
        return modes;
    }

    private String opMode(String pkg, String op, boolean uid) {
        return extractMode(Shell.run("appops get " + (uid ? "--uid " : "")
                + Shell.quote(pkg) + " " + op + " 2>/dev/null").out);
    }

    private static String extractMode(String text) {
        Matcher m = Pattern.compile("\\b(allow|ignore|deny|foreground|default)\\b").matcher(text);
        return m.find() ? m.group(1) : "default";
    }

    private Set<String> requestedPermissions(String pkg) {
        Set<String> set = new HashSet<>();
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) Collections.addAll(set, info.requestedPermissions);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return set;
    }

    private Set<String> grantedPermissions(String pkg) {
        Set<String> set = new HashSet<>();
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, PackageManager.GET_PERMISSIONS);
            if (info.requestedPermissions != null) {
                for (int i = 0; i < info.requestedPermissions.length; i++) {
                    if ((info.requestedPermissionsFlags[i]
                            & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                        set.add(info.requestedPermissions[i]);
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return set;
    }

    // ---- Apps: hide, defaults, usage, storage, recent access ------------------------------------

    private void openHide() {
        openPicker("Hide apps",
                "Checked apps are disabled: they leave the launcher and stop running. Uncheck to "
                        + "bring one back. On this phone only disabling works to hide an app.", true,
                this::disabledPackages,
                (pkg, on) -> error(Shell.run(on
                        ? "pm disable-user --user 0 " + Shell.quote(pkg)
                        : "pm enable " + Shell.quote(pkg))));
    }

    /** Roles the "Default apps" page offers, as {role, name}. */
    private static final String[][] ROLES = {
            {"android.app.role.HOME", "Launcher"},
            {"android.app.role.BROWSER", "Browser"},
            {"android.app.role.DIALER", "Phone"},
            {"android.app.role.SMS", "SMS"},
            {"android.app.role.ASSISTANT", "Assistant"}};

    /** Common file types the "File types" section can set a default app for, as {name, mime}. */
    private static final String[][] FILE_TYPES = {
            {"PDF", "application/pdf"},
            {"Images", "image/*"},
            {"Video", "video/*"},
            {"Audio", "audio/*"},
            {"Plain text", "text/plain"},
            {"Web page (HTML)", "text/html"},
            {"ZIP archive", "application/zip"},
            {"eBook (EPUB)", "application/epub+zip"},
            {"Word document", "application/msword"},
            {"Excel sheet", "application/vnd.ms-excel"},
            {"Markdown", "text/markdown"},
            {"CSV", "text/csv"}};

    private void openDefaults() {
        LinearLayout list = vertical();
        list.addView(note("Tap a default to change it. Some phones only allow certain apps for a role."));
        section(list, "Roles");
        for (String[] role : ROLES) {
            View[] holder = new View[1];
            holder[0] = row(list, role[1], "…", arrow(),
                    () -> openAppList("Set " + role[1],
                            "Pick the app to use as the default " + role[1].toLowerCase(Locale.ROOT) + ".",
                            pkg -> {
                                onBackPressed();
                                worker.execute(() -> {
                                    String err = error(Shell.run("cmd role add-role-holder "
                                            + role[0] + " " + Shell.quote(pkg)));
                                    main.post(() -> {
                                        if (err != null) toast(err);
                                        refreshRole(role, holder[0]);
                                    });
                                });
                            }), true);
            refreshRole(role, holder[0]);
        }

        section(list, "File types");
        list.addView(note("The app that opens each kind of file. Tap to see every app that can "
                + "open it (Android needs root to pin a file-type default directly)."));
        for (String[] type : FILE_TYPES) {
            View[] holder = new View[1];
            holder[0] = row(list, type[0], "…", arrow(), () -> pickForType(type, holder[0]), true);
            refreshType(type, holder[0]);
        }
        push("Default apps", scroll(list));
    }

    /** Shows the apps that can open this type and the current default. */
    private void pickForType(String[] type, View row) {
        worker.execute(() -> {
            List<App> handlers = handlersFor(type[1]);
            main.post(() -> {
                StringBuilder msg = new StringBuilder();
                if (handlers.isEmpty()) {
                    msg.append("No installed app handles this type.");
                } else {
                    msg.append("Apps that can open ").append(type[0]).append(':');
                    for (App h : handlers) msg.append("\n•  ").append(h.label);
                    msg.append("\n\nAndroid only lets the system pin a file-type default, so to set "
                            + "one, open a ").append(type[0]).append(" file and choose \"Always\". "
                            + "TinyKit shows the current default above.");
                }
                new AlertDialog.Builder(this)
                        .setTitle(type[0])
                        .setMessage(msg.toString())
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    /** Apps (with their handling component) that can open a content type, as default-launchable. */
    private List<App> handlersFor(String mime) {
        Intent intent = new Intent(Intent.ACTION_VIEW).setType(mime);
        List<App> result = new ArrayList<>();
        PackageManager pm = getPackageManager();
        for (ResolveInfo info : pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (info.activityInfo == null) continue;
            App a = new App();
            a.pkg = info.activityInfo.packageName;
            a.component = info.activityInfo.name;
            a.label = String.valueOf(info.loadLabel(pm));
            result.add(a);
        }
        return result;
    }

    private void refreshType(String[] type, View row) {
        TextView sub = (TextView) row.getTag();
        worker.execute(() -> {
            String out = Shell.run("cmd package resolve-activity --brief -a android.intent.action.VIEW -t "
                    + Shell.quote(type[1]) + " 2>/dev/null").out;
            Matcher m = PACKAGE.matcher(out);
            String pkg = m.find() ? m.group() : null;
            String text;
            if (pkg == null || out.contains("ResolverActivity") || out.toLowerCase(Locale.ROOT).contains("none")) {
                text = "Ask every time";
            } else {
                text = label(pkg);
            }
            main.post(() -> sub.setText(text));
        });
    }

    private void refreshRole(String[] role, View row) {
        TextView sub = (TextView) row.getTag();
        worker.execute(() -> {
            // Stock Android has get-role-holders; EMUI doesn't, so read dumpsys role instead.
            String out = Shell.run("cmd role get-role-holders " + role[0]).out;
            if (out.contains("Unknown command") || out.trim().isEmpty()) {
                out = Shell.run("dumpsys role | grep -A3 'name=" + role[0] + "' | grep -m1 holders=").out;
            }
            Matcher m = PACKAGE.matcher(out);
            String holder = m.find() ? m.group() : null;
            String text = holder == null ? "None set" : label(holder);
            main.post(() -> sub.setText(text));
        });
    }

    private void openUnused() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Longest-unused apps first. Tap one to open its settings.", 13, false);
        help.setAlpha(0.7f);
        page.addView(help);
        TextView loading = text("Reading usage…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Unused apps", page);

        Adapter<String[]> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(a[1]);
            row.second.setText(a[2]);
            showIcon(row, a[0]);
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> openAppInfo(adapter.getItem(position)[0]));
        final List<String[]> rows = new ArrayList<>();
        final int[] sort = {0};
        Runnable apply = () -> {
            if (sort[0] == 1) {
                Collections.sort(rows, (x, y) -> x[1].compareToIgnoreCase(y[1]));
            } else {
                Collections.sort(rows, (x, y) -> Long.compare(Long.parseLong(x[3]), Long.parseLong(y[3])));
            }
            adapter.set(rows);
        };
        page.addView(sortBar(new String[]{"Least used", "Name"}, i -> {
            sort[0] = i;
            apply.run();
        }), 2);
        worker.execute(() -> {
            if (!Tweak.usageAllowed(this)) Tweak.grantSelf(this);
            Map<String, Long> used = lastUsed();
            List<String[]> found = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (App a : launchableApps()) {
                long last = used.containsKey(a.pkg) ? used.get(a.pkg) : 0L;
                String when = last == 0 ? "Never opened"
                        : "Last opened " + DateUtils.getRelativeTimeSpanString(last, now, DateUtils.DAY_IN_MILLIS);
                found.add(new String[]{a.pkg, a.label, when, String.valueOf(last)});
            }
            main.post(() -> {
                loading.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
                if (found.isEmpty()) loading.setText("Couldn't read usage. Start Shizuku so TinyKit can turn it on.");
                rows.clear();
                rows.addAll(found);
                apply.run();
            });
        });
    }

    private Map<String, Long> lastUsed() {
        Map<String, Long> map = new HashMap<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            long now = System.currentTimeMillis();
            List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_YEARLY,
                    now - 365L * 24 * 3600 * 1000, now);
            if (stats != null) {
                for (UsageStats s : stats) {
                    Long prev = map.get(s.getPackageName());
                    if (prev == null || s.getLastTimeUsed() > prev) map.put(s.getPackageName(), s.getLastTimeUsed());
                }
            }
        } catch (RuntimeException ignored) {
        }
        return map;
    }

    private List<App> launchableApps() {
        List<App> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = getPackageManager();
        for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(info.activityInfo.applicationInfo.loadLabel(pm));
            list.add(a);
        }
        return list;
    }

    @android.annotation.TargetApi(26) // guarded below: StorageStatsManager is Android 8+
    private void openStorage() {
        if (Build.VERSION.SDK_INT < 26) {
            toast("Storage by app needs Android 8 or newer.");
            return;
        }
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView summary = text("Measuring…", 15, true);
        page.addView(summary);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Storage by app", page);

        Adapter<String[]> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(a[1]);
            row.second.setText(a[2]);
            showIcon(row, a[0]);
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> openAppInfo(adapter.getItem(position)[0]));
        final List<String[]> rows = new ArrayList<>();
        final int[] sort = {0};
        Runnable apply = () -> {
            if (sort[0] == 1) {
                Collections.sort(rows, (x, y) -> x[1].compareToIgnoreCase(y[1]));
            } else {
                Collections.sort(rows, (x, y) -> Long.compare(Long.parseLong(y[3]), Long.parseLong(x[3])));
            }
            adapter.set(rows);
        };
        page.addView(sortBar(new String[]{"Size", "Name"}, i -> {
            sort[0] = i;
            apply.run();
        }), 1);
        worker.execute(() -> {
            if (!Tweak.usageAllowed(this)) Tweak.grantSelf(this);
            StorageStatsManager ssm = (StorageStatsManager) getSystemService(STORAGE_STATS_SERVICE);
            List<String[]> found = new ArrayList<>();
            long total = 0;
            for (App a : installedApps()) {
                try {
                    StorageStats s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, a.pkg,
                            Process.myUserHandle());
                    long bytes = s.getAppBytes() + s.getDataBytes() + s.getCacheBytes();
                    total += bytes;
                    found.add(new String[]{a.pkg, a.label, Formatter.formatShortFileSize(this, bytes),
                            String.valueOf(bytes)});
                } catch (Exception ignored) {
                }
            }
            long used = total;
            main.post(() -> {
                summary.setText(found.isEmpty()
                        ? "Couldn't measure storage. Start Shizuku so TinyKit can turn usage access on."
                        : "Apps use " + Formatter.formatShortFileSize(this, used) + " in total");
                rows.clear();
                rows.addAll(found);
                apply.run();
            });
        });
    }

    private void openRecentAccess() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Most recent first, from the system's app-ops record.", 13, false);
        help.setAlpha(0.7f);
        page.addView(help);
        TextView loading = text("Reading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Recent access", page);

        Adapter<String[]> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(a[0]);
            row.second.setText(a[1] + "  ·  " + a[2]);
            showIcon(row, a[3]);
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> openAppInfo(adapter.getItem(position)[3]));
        worker.execute(() -> {
            List<String[]> rows = recentAccess();
            main.post(() -> {
                loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                if (rows.isEmpty()) loading.setText("This phone doesn't record recent access, or nothing recent.");
                adapter.set(rows);
            });
        });
    }

    private static final String[][] ACCESS_OPS = {
            {"CAMERA", "Camera"}, {"RECORD_AUDIO", "Microphone"},
            {"FINE_LOCATION", "Location"}, {"COARSE_LOCATION", "Location"},
            {"READ_CONTACTS", "Contacts"}, {"READ_CLIPBOARD", "Clipboard"}};

    /** Parses {@code dumpsys appops} for recent use of sensitive ops. Best-effort across phones. */
    private List<String[]> recentAccess() {
        Map<String, String> friendly = new HashMap<>();
        for (String[] op : ACCESS_OPS) friendly.put(op[0], op[1]);
        Pattern pkgLine = Pattern.compile("Package ([a-zA-Z0-9_.]+):");
        Pattern opLine = Pattern.compile("^([A-Z_]+) \\(");
        Pattern when = Pattern.compile("(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})");
        List<String[]> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String pkg = null, op = null;
        for (String raw : Shell.run("dumpsys appops").out.split("\n")) {
            String line = raw.trim();
            Matcher pm = pkgLine.matcher(line);
            if (pm.find()) {
                pkg = pm.group(1);
                op = null;
                continue;
            }
            Matcher om = opLine.matcher(line);
            if (om.find()) {
                op = om.group(1);
                continue;
            }
            if (pkg != null && op != null && friendly.containsKey(op) && line.startsWith("Access:")) {
                Matcher wm = when.matcher(line);
                if (wm.find() && seen.add(pkg + "/" + op)) {
                    rows.add(new String[]{label(pkg), friendly.get(op), wm.group(1), pkg});
                }
                op = null;
            }
        }
        Collections.sort(rows, (x, y) -> y[2].compareTo(x[2]));
        return rows;
    }

    private void openAppInfo(String pkg) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            toast("Couldn't open app settings.");
        }
    }

    // ---- Apps: mixed audio ----------------------------------------------------------------------

    /**
     * Lets apps play audio at the same time, or mutes an app. Both are app-op changes
     * (TAKE_AUDIO_FOCUS and PLAY_AUDIO) that the audio system enforces — nothing runs in the
     * background, uses no memory or battery once set.
     */
    private void openMixedAudio() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Let apps play audio together, or mute an app. These are app-op changes; "
                + "nothing runs in the background.", 13, false);
        help.setAlpha(0.7f);
        page.addView(help);
        EditText search = new EditText(this);
        search.setHint("Search apps");
        search.setSingleLine(true);
        page.addView(search);
        CheckBox system = new CheckBox(this);
        system.setText("Show system apps");
        page.addView(system);
        final int[] sort = {0};
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Mixed audio", page);

        audioState.clear();
        List<App> all = new ArrayList<>();
        Adapter<App> adapter = new Adapter<>(false, (app, row) -> {
            row.first.setText(app.label);
            row.second.setText(audioStateText(app.pkg));
            showIcon(row, app.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<App> shown = new ArrayList<>();
            for (App a : all) {
                boolean set = audioState.containsKey(a.pkg);
                if ((system.isChecked() || !a.system || set)
                        && (a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.contains(q))) {
                    shown.add(a);
                }
            }
            Collections.sort(shown, (x, y) -> x.label.compareToIgnoreCase(y.label));
            adapter.set(shown);
        };
        page.addView(sortBar(new String[]{"Name", "Package"}, i -> {
            sort[0] = i;
            Collections.sort(all, (x, y) -> i == 1
                    ? x.pkg.compareToIgnoreCase(y.pkg) : x.label.compareToIgnoreCase(y.label));
            filter.run();
        }), 3);
        search.addTextChangedListener(watcher(filter));
        system.setOnCheckedChangeListener((v, checked) -> filter.run());
        listView.setOnItemClickListener((parent, view, position, id) ->
                showAudioDialog(adapter.getItem(position), filter));
        Runnable show = () -> {
            all.clear();
            for (App a : installedApps()) all.add(a.copy());
            filter.run();
        };
        if (appsLoaded()) show.run();
        else worker.execute(() -> {
            installedApps();
            main.post(show);
        });
        loadAudioState(filter);
    }

    private String audioStateText(String pkg) {
        int[] st = audioState.get(pkg);
        boolean muted = st != null && st[0] == 1;
        boolean mixed = st != null && st[1] == 1;
        if (muted && mixed) return "Muted  ·  plays with others";
        if (muted) return "Muted";
        if (mixed) return "Plays with others";
        return pkg;
    }

    /** Reads which apps are muted or set to mix, then runs {@code after} on the main thread. */
    private void loadAudioState(Runnable after) {
        worker.execute(() -> {
            String out = Shell.run("echo '@M'; appops query-op PLAY_AUDIO deny; "
                    + "echo '@F'; appops query-op TAKE_AUDIO_FOCUS ignore; "
                    + "appops query-op TAKE_AUDIO_FOCUS deny").out;
            Map<String, int[]> state = new HashMap<>();
            int section = 0;
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.equals("@M")) {
                    section = 1;
                    continue;
                }
                if (line.equals("@F")) {
                    section = 2;
                    continue;
                }
                if (!PACKAGE.matcher(line).matches()) continue;
                int[] st = state.get(line);
                if (st == null) state.put(line, st = new int[2]);
                if (section == 1) st[0] = 1;
                else if (section == 2) st[1] = 1;
            }
            main.post(() -> {
                audioState.clear();
                audioState.putAll(state);
                after.run();
            });
        });
    }

    private void showAudioDialog(App app, Runnable refresh) {
        int[] st = audioState.get(app.pkg);
        boolean muted = st != null && st[0] == 1;
        boolean mixed = st != null && st[1] == 1;
        String q = Shell.quote(app.pkg);
        LinkedHashMap<String, String> actions = new LinkedHashMap<>();
        actions.put(muted ? "Unmute audio" : "Mute audio",
                "appops set " + q + " PLAY_AUDIO " + (muted ? "allow" : "deny"));
        actions.put(mixed ? "Play normally (take focus)" : "Let it play with others",
                "appops set " + q + " TAKE_AUDIO_FOCUS " + (mixed ? "allow" : "ignore"));
        String[] labels = actions.keySet().toArray(new String[0]);
        String[] cmds = actions.values().toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(labels, (d, which) -> worker.execute(() -> {
                    String err = error(Shell.run(cmds[which]));
                    if (err != null) main.post(() -> toast(err));
                    loadAudioState(refresh);
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Privacy: analytics & tracking ----------------------------------------------------------

    /** Huawei's background diagnostics and telemetry packages (matched by name). Never com.huawei.hwid. */
    private static final String[] HUAWEI_DIAG = {
            "com.huawei.hiview", "hiviewtunnel", "huawei.android.chr", "hwdetectrepair", "hwdiagnosis",
            "recsys", "huawei.magazine"};
    private static final String[] HICLOUD = {"hicloud"};

    private void openAnalytics() {
        LinearLayout list = vertical();
        list.addView(note("\"Blocked\" stops the service; turn it back on here any time. "
                + "TinyKit never touches your Huawei ID sign-in."));
        section(list, "Huawei");
        privacySwitch(list, "User Experience Programme",
                "Stops Huawei usage analytics and app analytics.",
                () -> "0".equals(sget("secure", "user_experience_involved"))
                        && "0".equals(sget("secure", "hw_app_analytics_state")),
                block -> error(Shell.run("settings put secure user_experience_involved " + (block ? 0 : 1)
                        + "; settings put secure hw_app_analytics_state " + (block ? 0 : 1))));
        privacySwitch(list, "Diagnostics & telemetry",
                "Disables Huawei's background logging and reporting apps.",
                pkgChecker(HUAWEI_DIAG), pkgApplier(HUAWEI_DIAG));
        privacySwitch(list, "HiCloud",
                "Disables HiCloud sync services. Your Huawei ID sign-in stays.",
                pkgChecker(HICLOUD), pkgApplier(HICLOUD));
        section(list, "Google");
        privacySwitch(list, "Limit ad personalisation",
                "Asks apps not to use the Google advertising ID for targeting.",
                () -> "1".equals(sget("secure", "limit_ad_tracking")),
                block -> error(Shell.run("settings put secure limit_ad_tracking " + (block ? 1 : 0))));
        push("Analytics & telemetry", scroll(list));
    }

    /** A switch backed by a worker-thread check and change, used for the analytics toggles. */
    private void privacySwitch(LinearLayout list, String title, String summary, Checker checker, Applier applier) {
        Switch view = new Switch(this);
        view.setClickable(false);
        view.setFocusable(false);
        View row = row(list, title, summary, view, () -> {
            boolean block = !view.isChecked();
            view.setChecked(block);
            view.setEnabled(false);
            worker.execute(() -> {
                String err = applier.apply(block);
                Boolean now = checker.check();
                main.post(() -> {
                    view.setEnabled(true);
                    if (now != null) view.setChecked(now);
                    if (err != null) toast(err);
                });
            });
        }, true);
        TextView sub = (TextView) row.getTag();
        worker.execute(() -> {
            Boolean state = checker.check();
            main.post(() -> {
                if (state == null) {
                    row.setEnabled(false);
                    row.setAlpha(0.4f);
                    view.setEnabled(false);
                    sub.setText(summary + "  ·  Not on this phone");
                } else {
                    view.setChecked(state);
                }
            });
        });
    }

    private Checker pkgChecker(String[] patterns) {
        return () -> {
            List<String> pkgs = matchingPackages(patterns);
            if (pkgs.isEmpty()) return null;
            Set<String> disabled = disabledPackages();
            for (String pkg : pkgs) {
                if (!disabled.contains(pkg)) return false;
            }
            return true;
        };
    }

    private Applier pkgApplier(String[] patterns) {
        return block -> {
            StringBuilder cmd = new StringBuilder();
            for (String pkg : matchingPackages(patterns)) {
                cmd.append(block ? "pm disable-user --user 0 " : "pm enable ").append(Shell.quote(pkg)).append("; ");
            }
            if (cmd.length() == 0) return null;
            return error(Shell.run(cmd.toString()));
        };
    }

    /** Installed packages whose name contains one of the patterns, never the Huawei ID package. */
    private List<String> matchingPackages(String[] patterns) {
        List<String> result = new ArrayList<>();
        for (App a : installedApps()) {
            if (a.pkg.contains("hwid")) continue;
            for (String pattern : patterns) {
                if (a.pkg.contains(pattern)) {
                    result.add(a.pkg);
                    break;
                }
            }
        }
        return result;
    }

    private Set<String> disabledPackages() {
        Set<String> set = new HashSet<>();
        for (String line : Shell.run("pm list packages -d | sed 's/^package://'").out.split("\n")) {
            String pkg = line.trim();
            if (!pkg.isEmpty()) set.add(pkg);
        }
        return set;
    }

    private String sget(String table, String key) {
        return Shell.run("settings get " + table + " " + key).out.trim();
    }

    /** The app_process command that toggles or reads all-sensors privacy. arg is on | off | state. */
    static String sensorsCmd(Context c, String arg) {
        return "CLASSPATH=" + c.getPackageCodePath()
                + " app_process /system/bin io.github.order52.tinykit.Sensors " + arg;
    }

    // ---- Privacy: saved Wi-Fi --------------------------------------------------------------------

    private void openWifi() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Tap a network to see its password and a QR code to share it.", 13, false);
        help.setAlpha(0.7f);
        page.addView(help);
        EditText search = new EditText(this);
        search.setHint("Search networks");
        search.setSingleLine(true);
        page.addView(search);
        TextView loading = text("Reading saved networks…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Saved Wi-Fi", page);

        Adapter<String[]> adapter = new Adapter<>(false, (n, row) -> {
            row.first.setText(n[0]);
            row.second.setText("nopass".equals(n[1]) ? "Open network"
                    : n[2].isEmpty() ? n[1] : n[1] + "  ·  " + n[2]);
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> showWifi(adapter.getItem(position)));
        List<String[]> all = new ArrayList<>();
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<String[]> shown = new ArrayList<>();
            for (String[] n : all) if (n[0].toLowerCase(Locale.ROOT).contains(q)) shown.add(n);
            adapter.set(shown);
        };
        search.addTextChangedListener(watcher(filter));
        worker.execute(() -> {
            List<String[]> nets = readWifi();
            main.post(() -> {
                all.addAll(nets);
                filter.run();
                loading.setVisibility(nets.isEmpty() ? View.VISIBLE : View.GONE);
                if (nets.isEmpty()) {
                    loading.setText("No saved networks, or this phone doesn't allow reading them.");
                }
            });
        });
    }

    /** Runs {@link WifiDump} as the shell user through Shizuku and parses its lines. */
    private List<String[]> readWifi() {
        String apk = Shell.run("pm path " + getPackageName() + " | head -1 | sed 's/^package://'").out.trim();
        List<String[]> nets = new ArrayList<>();
        if (apk.isEmpty()) return nets;
        Shell.Result r = Shell.run("CLASSPATH=" + Shell.quote(apk)
                + " app_process /system/bin io.github.order52.tinykit.WifiDump");
        if (!r.ok()) return nets;
        for (String line : r.out.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length >= 3 && !parts[0].trim().isEmpty()) {
                nets.add(new String[]{parts[0], parts[1], parts[2]});
            }
        }
        Collections.sort(nets, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return nets;
    }

    private void showWifi(String[] net) {
        String ssid = net[0], type = net[1], password = net[2];
        LinearLayout box = vertical();
        box.setPadding(dp(24), dp(12), dp(24), 0);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        Bitmap qr = qrBitmap(wifiQr(ssid, type, password), dp(240));
        if (qr != null) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(qr);
            box.addView(image, new LinearLayout.LayoutParams(dp(240), dp(240)));
        }
        TextView detail = text("nopass".equals(type) ? "Open network — no password" : "Password: " + password, 16, false);
        detail.setTextIsSelectable(true);
        detail.setPadding(0, dp(16), 0, 0);
        detail.setGravity(Gravity.CENTER);
        box.addView(detail);
        new AlertDialog.Builder(this)
                .setTitle(ssid)
                .setView(box)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy password", "nopass".equals(type) ? null : (d, w) -> {
                    ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(android.content.ClipData.newPlainText("Wi-Fi password", password));
                    toast("Password copied");
                })
                .show();
    }

    /** The WIFI: payload a phone camera reads to join a network. */
    private static String wifiQr(String ssid, String type, String password) {
        String auth = "nopass".equals(type) ? "nopass" : "WEP".equals(type) ? "WEP" : "WPA";
        return "WIFI:T:" + auth + ";S:" + qrEscape(ssid) + ";P:" + qrEscape(password) + ";;";
    }

    private static String qrEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    /** Renders a QR code to a square bitmap with a white quiet zone, or null if it won't fit. */
    private Bitmap qrBitmap(String text, int sizePx) {
        Qr qr = Qr.encode(text);
        if (qr == null) return null;
        int quiet = 4;
        int modules = qr.size + quiet * 2;
        int scale = Math.max(1, sizePx / modules);
        int dim = modules * scale;
        Bitmap bitmap = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.get(x, y)) {
                    float px = (x + quiet) * scale;
                    float py = (y + quiet) * scale;
                    canvas.drawRect(px, py, px + scale, py + scale, paint);
                }
            }
        }
        return bitmap;
    }

    // ---- Quick panel editor ---------------------------------------------------------------------

    private static final String QS_TILES = "sysui_qs_tiles";

    /** Names for built-in tiles; anything not listed is shown by its system name. */
    private static final String[][] STOCK_TILES = {
            {"wifi", "Wi-Fi"}, {"bt", "Bluetooth"}, {"internet", "Internet"}, {"cell", "Mobile data"},
            {"data", "Mobile data"}, {"flashlight", "Flashlight"}, {"sound", "Sound"},
            {"rotation", "Auto-rotate"}, {"location", "Location"}, {"hotspot", "Hotspot"},
            {"airplane", "Airplane mode"}, {"dnd", "Do Not Disturb"}, {"busy", "Do Not Disturb"},
            {"battery", "Battery Saver"}, {"superpower", "Power saving"}, {"saver", "Data Saver"},
            {"cast", "Cast"}, {"nfc", "NFC"}, {"night", "Night Light"}, {"dark", "Dark theme"},
            {"eyecomfort", "Eye comfort"}, {"inversion", "Color inversion"}, {"work", "Work apps"},
            {"screenrecord", "Screen record"}, {"recorder", "Screen recorder"}, {"screenshot", "Screenshot"},
            {"airsharing", "Huawei Share"}, {"alarm", "Alarm"}, {"controls", "Device controls"},
            {"wallet", "Wallet"}, {"qr_code_scanner", "QR scanner"}, {"mictoggle", "Microphone access"},
            {"cameratoggle", "Camera access"}, {"onehanded", "One-handed mode"},
            {"color_correction", "Color correction"}, {"reduce_brightness", "Extra dim"},
            {"font_scaling", "Font size"}, {"hearing_devices", "Hearing devices"}};

    /** One tile: its spec as stored in the setting, plus how to show it. */
    private static final class QsTile {
        String spec;
        String title;
        String sub;
        Drawable icon;
        // Tile icons are masks that SystemUI tints; app icons are shown as they are.
        boolean tint;
    }

    private interface OnTile {
        void run(String spec);
    }

    private interface OnPage {
        void run(int page);
    }

    private interface OnDrop {
        void run(String spec, int index);
    }

    // Editor state: the page each grid shows and the tile that's picked for moving.
    private int qsPage;
    private int qsAddPage;
    private String qsPicked;
    private final Map<String, Drawable> qsIcons = new HashMap<>();
    // Tiles are looked up once per visit; every later change only rearranges views.
    private final Map<String, QsTile> qsCache = new HashMap<>();
    private final List<String> qsAppTiles = new ArrayList<>();

    /**
     * Shows the quick panel's tiles as pages laid out like the system's own panel. Every change is
     * written straight to the sysui_qs_tiles setting (through Shizuku), which SystemUI applies at once.
     */
    private void openQuickPanel() {
        qsPage = 0;
        qsAddPage = 0;
        qsPicked = null;
        qsCache.clear();
        LinearLayout list = vertical();
        list.addView(note("Loading…"));
        push("Quick panel", scroll(list));
        worker.execute(() -> {
            List<String> tiles = readTiles();
            List<String> apps = new ArrayList<>();
            for (ResolveInfo info : getPackageManager().queryIntentServices(new Intent(TileService.ACTION_QS_TILE), 0)) {
                apps.add("custom(" + new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
                        .flattenToShortString() + ")");
            }
            // Names and icons load here, off the main thread.
            Set<String> all = new LinkedHashSet<>(tiles);
            all.addAll(apps);
            all.addAll(prefs.getStringSet("qs_known", Collections.emptySet()));
            Map<String, QsTile> described = new HashMap<>();
            for (String spec : all) described.put(spec, describeTile(spec));
            main.post(() -> {
                qsAppTiles.clear();
                qsAppTiles.addAll(apps);
                qsCache.putAll(described);
                if (tiles.isEmpty()) {
                    list.removeAllViews();
                    list.addView(note("This phone doesn't let apps change the quick panel."));
                    return;
                }
                String value = TextUtils.join(",", tiles);
                if (!prefs.contains("qs_original")) prefs.edit().putString("qs_original", value).apply();
                rememberTiles(tiles);
                renderQuickPanel(list, tiles);
            });
        });
    }

    /** The current tiles; on phones that never edited the panel, SystemUI's defaults. */
    private List<String> readTiles() {
        String value = Shell.run("settings get secure " + QS_TILES).out.trim();
        if (value.isEmpty() || value.equals("null") || value.equals("default") || value.contains(" ")) {
            value = systemString("quick_settings_tiles_default");
        }
        List<String> tiles = new ArrayList<>();
        for (String spec : value.split(",")) {
            if (!spec.trim().isEmpty() && !tiles.contains(spec.trim())) tiles.add(spec.trim());
        }
        return tiles;
    }

    private Context systemUi() {
        try {
            return createPackageContext("com.android.systemui", 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    private String systemString(String name) {
        Context ui = systemUi();
        if (ui == null) return "";
        int id = ui.getResources().getIdentifier(name, "string", "com.android.systemui");
        return id == 0 ? "" : ui.getString(id);
    }

    private int systemInteger(String name, int fallback) {
        Context ui = systemUi();
        if (ui == null) return fallback;
        int id = ui.getResources().getIdentifier(name, "integer", "com.android.systemui");
        return id == 0 ? fallback : ui.getResources().getInteger(id);
    }

    /**
     * Columns and rows on one page of the system's panel. EMUI's control panel shows four rows
     * of round tiles; stock Android reads both numbers from SystemUI.
     */
    private int[] panelGrid() {
        int columns = systemInteger("hw_quick_settings_num_columns", 0);
        int rows = 4;
        if (columns == 0) {
            columns = systemInteger("quick_settings_num_columns", 4);
            rows = systemInteger("quick_settings_max_rows", 3);
        }
        return new int[]{Math.max(2, Math.min(columns, 6)), Math.max(2, Math.min(rows, 5))};
    }

    /** Built-in tiles have no list to query, so remember every one seen to offer it again later. */
    private void rememberTiles(List<String> tiles) {
        Set<String> known = new LinkedHashSet<>(prefs.getStringSet("qs_known", Collections.emptySet()));
        for (String spec : tiles) if (!spec.startsWith("custom(")) known.add(spec);
        prefs.edit().putStringSet("qs_known", known).apply();
    }

    private void renderQuickPanel(LinearLayout list, List<String> tiles) {
        list.removeAllViews();
        int[] grid = panelGrid();
        int perPage = grid[0] * grid[1];
        if (qsPicked != null && !tiles.contains(qsPicked)) qsPicked = null;

        list.addView(note(qsPicked == null
                ? "Hold and drag a tile to move it, or tap two tiles to swap them. "
                + "Changes show in the quick panel right away."
                : "Tap another tile to swap them, use the buttons below, or tap it again to let go."));
        section(list, "Your panel  ·  " + tiles.size());
        List<QsTile> mine = new ArrayList<>();
        for (String spec : tiles) mine.add(tile(spec));
        qsPage = Math.min(qsPage, pages(mine.size(), perPage) - 1);
        list.addView(tileGrid(mine, grid, qsPage, qsPicked, spec -> {
            if (qsPicked == null || qsPicked.equals(spec)) {
                qsPicked = spec.equals(qsPicked) ? null : spec;
                renderQuickPanel(list, tiles);
                return;
            }
            List<String> next = new ArrayList<>(tiles);
            Collections.swap(next, next.indexOf(qsPicked), next.indexOf(spec));
            qsPicked = null;
            saveTiles(list, tiles, next);
        }, page -> {
            qsPage = page;
            renderQuickPanel(list, tiles);
        }, (spec, to) -> {
            qsPicked = null;
            moveTile(list, tiles, tiles.indexOf(spec), Math.min(to, tiles.size() - 1));
        }));

        if (qsPicked != null) {
            int index = tiles.indexOf(qsPicked);
            LinearLayout bar = new LinearLayout(this);
            bar.setPadding(dp(12), dp(8), dp(12), 0);
            Button earlier = button("‹  Earlier", v -> {
                qsPage = (index - 1) / perPage;
                moveTile(list, tiles, index, index - 1);
            });
            Button later = button("Later  ›", v -> {
                qsPage = (index + 1) / perPage;
                moveTile(list, tiles, index, index + 1);
            });
            Button remove = button("Remove", v -> {
                qsPicked = null;
                List<String> next = new ArrayList<>(tiles);
                next.remove(index);
                saveTiles(list, tiles, next);
            });
            earlier.setEnabled(index > 0);
            later.setEnabled(index < tiles.size() - 1);
            remove.setEnabled(tiles.size() > 1);
            bar.addView(earlier, weight());
            bar.addView(later, weight());
            bar.addView(remove, weight());
            list.addView(bar);
        }

        List<QsTile> available = availableTiles(tiles);
        section(list, "Add  ·  " + available.size());
        if (available.isEmpty()) {
            list.addView(note("Every tile is already in your panel."));
        } else {
            list.addView(note("Tap a tile to add it to the end of your panel."));
            qsAddPage = Math.min(qsAddPage, pages(available.size(), perPage) - 1);
            list.addView(tileGrid(available, grid, qsAddPage, null, spec -> {
                List<String> next = new ArrayList<>(tiles);
                next.add(spec);
                qsPicked = spec;
                qsPage = (next.size() - 1) / perPage;
                saveTiles(list, tiles, next);
            }, page -> {
                qsAddPage = page;
                renderQuickPanel(list, tiles);
            }, null));
        }

        section(list, "Reset");
        String original = prefs.getString("qs_original", "");
        row(list, "Restore original layout", "Put the tiles back the way they were before you used this.",
                null, () -> {
                    qsPicked = null;
                    qsPage = 0;
                    saveTiles(list, tiles, new ArrayList<>(Arrays.asList(original.split(","))));
                }, true);
    }

    private static int pages(int count, int perPage) {
        return Math.max(1, (count + perPage - 1) / perPage);
    }

    /** One page of tiles plus page dots; swiping sideways or tapping the arrows turns the page. */
    private View tileGrid(List<QsTile> tiles, int[] grid, int page, String picked, OnTile onTile, OnPage onPage,
                          OnDrop onDrop) {
        int columns = grid[0];
        int perPage = columns * grid[1];
        int pages = pages(tiles.size(), perPage);
        // A single page only takes the rows it needs; several pages keep one height, like the system.
        int rows = pages > 1 ? grid[1] : Math.max(1, (tiles.size() + columns - 1) / columns);
        boolean wide = columns <= 2;

        LinearLayout body = vertical();
        body.setPadding(dp(12), dp(4), dp(12), 0);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < columns; c++) {
                int i = page * perPage + r * columns + c;
                View cell;
                if (i < tiles.size()) {
                    QsTile tile = tiles.get(i);
                    boolean on = tile.spec.equals(picked);
                    cell = wide ? wideTile(tile, on) : roundTile(tile, on);
                    cell.setOnClickListener(v -> onTile.run(tile.spec));
                    if (onDrop != null) {
                        cell.setOnLongClickListener(v -> {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            v.startDragAndDrop(null, new View.DragShadowBuilder(v), tile.spec, 0);
                            v.setAlpha(0.3f);
                            return true;
                        });
                    }
                } else {
                    cell = new View(this);
                }
                if (onDrop != null) cell.setOnDragListener(dropTarget(i, onDrop));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(wide ? 64 : 100), 1f);
                params.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(cell, params);
            }
            body.addView(row);
        }

        Swipe swipe = new Swipe(this, page, pages, onPage);
        swipe.addView(body);
        if (pages == 1) return swipe;

        LinearLayout wrap = vertical();
        wrap.addView(swipe);
        LinearLayout dots = new LinearLayout(this);
        dots.setGravity(Gravity.CENTER);
        View back = tileArrow("‹", page > 0, () -> onPage.run(page - 1));
        dots.addView(back);
        for (int p = 0; p < pages; p++) {
            View dot = new View(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(p == page ? 0xFFFFFFFF : 0x55FFFFFF);
            dot.setBackground(shape);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(7), dp(7));
            params.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(dot, params);
        }
        View forward = tileArrow("›", page < pages - 1, () -> onPage.run(page + 1));
        dots.addView(forward);
        if (onDrop != null) {
            // Holding a dragged tile over an arrow turns the page.
            back.setOnDragListener(pageTurn(page > 0, () -> onPage.run(page - 1)));
            forward.setOnDragListener(pageTurn(page < pages - 1, () -> onPage.run(page + 1)));
        }
        wrap.addView(dots);
        return wrap;
    }

    /** A grid slot that takes a dragged tile: it grows while one hovers over it. */
    private View.OnDragListener dropTarget(int index, OnDrop onDrop) {
        return (v, e) -> {
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED:
                    v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(80).start();
                    break;
                case DragEvent.ACTION_DRAG_EXITED:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    break;
                case DragEvent.ACTION_DROP:
                    onDrop.run((String) e.getLocalState(), index);
                    break;
                case DragEvent.ACTION_DRAG_ENDED:
                    v.setAlpha(1f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    break;
            }
            return true;
        };
    }

    /** Turns the page once a dragged tile has rested on the arrow for a moment, like the system. */
    private View.OnDragListener pageTurn(boolean enabled, Runnable turn) {
        return (v, e) -> {
            if (!enabled) return false;
            if (e.getAction() == DragEvent.ACTION_DRAG_ENTERED) v.postDelayed(turn, 450);
            else if (e.getAction() != DragEvent.ACTION_DRAG_LOCATION) v.removeCallbacks(turn);
            return true;
        };
    }

    /** EMUI-style tile: a round button with the name under it. */
    private View roundTile(QsTile tile, boolean picked) {
        LinearLayout cell = vertical();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, dp(6), 0, 0);
        FrameLayout circle = new FrameLayout(this);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(picked ? attrColor(android.R.attr.colorAccent) : 0xFF262626);
        circle.setBackground(shape);
        circle.addView(tileIcon(tile), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        cell.addView(circle, new LinearLayout.LayoutParams(dp(52), dp(52)));
        TextView label = text(tile.title, 12, false);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(dp(2), dp(6), dp(2), 0);
        cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
        cell.setBackgroundResource(attrResource(android.R.attr.selectableItemBackgroundBorderless));
        return cell;
    }

    /** Stock Android 12+ tile: a wide rounded button with the name beside the icon. */
    private View wideTile(QsTile tile, boolean picked) {
        LinearLayout cell = new LinearLayout(this);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(dp(16), 0, dp(12), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(24));
        shape.setColor(picked ? attrColor(android.R.attr.colorAccent) : 0xFF262626);
        cell.setBackground(shape);
        cell.setForeground(getDrawable(attrResource(android.R.attr.selectableItemBackground)));
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(20), dp(20));
        iconSize.setMargins(0, 0, dp(12), 0);
        cell.addView(tileIcon(tile), iconSize);
        LinearLayout texts = vertical();
        TextView title = text(tile.title, 14, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView sub = text(tile.sub, 12, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setAlpha(0.6f);
        texts.addView(sub);
        cell.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        return cell;
    }

    /** The tile's icon, or its first letter when there's no icon to show. */
    private View tileIcon(QsTile tile) {
        if (tile.icon == null) {
            TextView letter = text(tile.title.isEmpty() ? "?" : tile.title.substring(0, 1), 16, true);
            letter.setGravity(Gravity.CENTER);
            return letter;
        }
        Drawable d = tile.icon.getConstantState() != null
                ? tile.icon.getConstantState().newDrawable(getResources()).mutate() : tile.icon.mutate();
        if (tile.tint) d.setTint(0xFFFFFFFF);
        ImageView view = new ImageView(this);
        view.setImageDrawable(d);
        return view;
    }

    private TextView tileArrow(String label, boolean enabled, Runnable onClick) {
        TextView view = text(label, 22, false);
        view.setGravity(Gravity.CENTER);
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 0.9f : 0.2f);
        view.setBackgroundResource(attrResource(android.R.attr.selectableItemBackgroundBorderless));
        if (enabled) view.setOnClickListener(v -> onClick.run());
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(40)));
        return view;
    }

    /** Lets a grid be swiped sideways to turn the page, while taps still reach its tiles. */
    private static final class Swipe extends FrameLayout {
        private final int page;
        private final int pages;
        private final OnPage onPage;
        private final int slop;
        private float downX;
        private float downY;
        private boolean dragging;

        Swipe(Context context, int page, int pages, OnPage onPage) {
            super(context);
            this.page = page;
            this.pages = pages;
            this.onPage = onPage;
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            return track(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            track(e);
            return pages > 1;
        }

        private boolean track(MotionEvent e) {
            if (pages < 2) return false;
            // Screen coordinates: the view itself slides with the finger.
            float dx = e.getRawX() - downX;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(e.getRawY() - downY)) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    // Follow the finger, with resistance at the first and last page.
                    if (dragging) setTranslationX((dx < 0 ? page < pages - 1 : page > 0) ? dx : dx / 4);
                    break;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        dragging = false;
                        int next = dx < 0 ? page + 1 : page - 1;
                        if (Math.abs(dx) > getWidth() / 6f && next >= 0 && next < pages) {
                            onPage.run(next);
                        } else {
                            animate().translationX(0).setDuration(150).start();
                        }
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    animate().translationX(0).setDuration(150).start();
                    break;
            }
            return dragging;
        }
    }

    private void moveTile(LinearLayout list, List<String> tiles, int from, int to) {
        if (from == to) return;
        List<String> next = new ArrayList<>(tiles);
        next.add(to, next.remove(from));
        saveTiles(list, tiles, next);
    }

    /** Shows the new order at once, then writes it; goes back to the old order if that fails. */
    private void saveTiles(LinearLayout list, List<String> old, List<String> next) {
        renderQuickPanel(list, next);
        worker.execute(() -> {
            Shell.Result r = Shell.run("settings put secure " + QS_TILES + " "
                    + Shell.quote(TextUtils.join(",", next)));
            if (r.ok()) return;
            main.post(() -> {
                toast(r.out.isEmpty() ? "Couldn't change the quick panel." : r.out);
                renderQuickPanel(list, old);
            });
        });
    }

    private List<QsTile> availableTiles(List<String> tiles) {
        Set<String> used = new HashSet<>();
        for (String spec : tiles) used.add(normalizeTile(spec));
        List<QsTile> result = new ArrayList<>();
        for (String spec : prefs.getStringSet("qs_known", Collections.emptySet())) {
            if (!used.contains(spec)) result.add(tile(spec));
        }
        for (String spec : qsAppTiles) {
            if (!used.contains(normalizeTile(spec))) result.add(tile(spec));
        }
        Collections.sort(result, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return result;
    }

    /** custom(pkg/.Name) and custom(pkg/pkg.Name) are the same tile. */
    private static String normalizeTile(String spec) {
        if (!spec.startsWith("custom(") || !spec.endsWith(")")) return spec;
        ComponentName c = ComponentName.unflattenFromString(spec.substring(7, spec.length() - 1));
        return c == null ? spec : "custom(" + c.flattenToShortString() + ")";
    }

    private QsTile tile(String spec) {
        QsTile tile = qsCache.get(spec);
        if (tile == null) qsCache.put(spec, tile = describeTile(spec));
        return tile;
    }

    private QsTile describeTile(String spec) {
        QsTile tile = new QsTile();
        tile.spec = spec;
        if (spec.startsWith("custom(") && spec.endsWith(")")) {
            ComponentName c = ComponentName.unflattenFromString(spec.substring(7, spec.length() - 1));
            PackageManager pm = getPackageManager();
            try {
                ServiceInfo si = pm.getServiceInfo(c, 0);
                tile.title = String.valueOf(si.loadLabel(pm));
                tile.sub = String.valueOf(si.applicationInfo.loadLabel(pm));
                // A tile without its own icon shows the app's, which is in full color.
                tile.icon = si.icon != 0 ? si.loadIcon(pm) : si.applicationInfo.loadIcon(pm);
                tile.tint = si.icon != 0 && !opaqueImage(tile.icon);
            } catch (PackageManager.NameNotFoundException | NullPointerException e) {
                tile.title = c == null ? spec : c.getPackageName();
                tile.sub = "App not installed";
            }
            return tile;
        }
        tile.title = spec.substring(0, 1).toUpperCase(Locale.ROOT) + spec.substring(1).replace('_', ' ');
        for (String[] stock : STOCK_TILES) {
            if (stock[0].equals(spec)) tile.title = stock[1];
        }
        tile.sub = "Built in";
        tile.icon = systemIcon(spec);
        tile.tint = true;
        return tile;
    }

    /** A picture rather than a mask, which tinting would turn into a solid square. */
    private static boolean opaqueImage(Drawable icon) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888);
        Drawable d = icon.getConstantState() != null ? icon.getConstantState().newDrawable() : icon;
        d.setBounds(0, 0, 16, 16);
        d.draw(new android.graphics.Canvas(b));
        int solid = 0;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) if (Color.alpha(b.getPixel(x, y)) > 200) solid++;
        b.recycle();
        // Masks are mostly see-through; a picture fills most of its square.
        return solid > 16 * 16 * 0.6;
    }

    /** SystemUI's drawable names for each built-in tile, EMUI's first, then stock Android's. */
    private static final String[][] TILE_ICONS = {
            {"wifi", "wifi", "wifi_signal_4"}, {"internet", "wifi", "wifi_signal_4"}, {"bt", "bluetooth"},
            {"cell", "dataswitch", "lte"}, {"data", "dataswitch", "lte"}, {"flashlight", "flashlight"},
            {"sound", "vibration"}, {"rotation", "rotation", "auto_rotate"}, {"location", "gps", "location"},
            {"hotspot", "hotspot"}, {"airplane", "airplanemode", "airplane"}, {"dnd", "dnd", "busymode"},
            {"busy", "busymode", "dnd"}, {"battery", "lowpowermode", "battery_saver"},
            {"superpower", "lowpowermode", "battery_saver"}, {"saver", "data_saver"}, {"cast", "cast"},
            {"nfc", "nfc"}, {"night", "nightlight"}, {"dark", "darkmode", "light_dark_theme"},
            {"eyecomfort", "eyecomfort"}, {"inversion", "invert_colors"},
            {"screenrecord", "screenrecorder", "screen_record"}, {"recorder", "screenrecorder", "screen_record"},
            {"screenshot", "screenshot"}, {"airsharing", "instantshare"}, {"cameratoggle", "camera_access"},
            {"mictoggle", "mic_access"}, {"reduce_brightness", "extra_dim"},
            {"color_correction", "color_correction"}, {"font_scaling", "font_scaling"}};

    private static final String[] ICON_PATTERNS = {"ic_%s_tile_on", "%s_tile_on", "qs_%s_icon_on",
            "ic_qs_%s_on", "ic_qs_%s", "ic_%s_on", "ic_%s_enable", "ic_%s"};

    /** The icon SystemUI draws for a built-in tile, looked up by name; null if there isn't one. */
    private Drawable systemIcon(String spec) {
        if (qsIcons.containsKey(spec)) return qsIcons.get(spec);
        String[] names = {spec};
        for (String[] row : TILE_ICONS) {
            if (row[0].equals(spec)) names = Arrays.copyOfRange(row, 1, row.length);
        }
        Context ui = systemUi();
        Drawable icon = null;
        search:
        for (String name : names) {
            for (String pattern : ICON_PATTERNS) {
                String res = String.format(Locale.ROOT, pattern, name);
                icon = load(ui, res, "com.android.systemui");
                if (icon == null) icon = load(null, res, "android");
                if (icon != null) break search;
            }
        }
        qsIcons.put(spec, icon);
        return icon;
    }

    private Drawable load(Context ui, String name, String pkg) {
        try {
            android.content.res.Resources res = ui != null ? ui.getResources()
                    : android.content.res.Resources.getSystem();
            int id = res.getIdentifier(name, "drawable", pkg);
            return id == 0 ? null : res.getDrawable(id, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private TextView note(String message) {
        TextView view = text(message, 13, false);
        view.setAlpha(0.6f);
        view.setPadding(dp(16), dp(12), dp(16), dp(4));
        return view;
    }

    /**
     * A generic "Sort" control: an outlined chip showing the current choice that opens a menu of
     * options when tapped, calling onSelect with the chosen index. Reused by every sortable list.
     */
    private View sortBar(String[] options, OnPage onSelect) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(8), dp(12), dp(4));
        bar.addView(chip("↕  ", "Sort", options, onSelect));
        return bar;
    }

    private View sortBarLike(View chip) {
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(0, dp(8), 0, dp(4));
        bar.addView(chip);
        return bar;
    }

    /** A rounded button that shows "Title: choice" and opens a list of {@code options} when tapped. */
    private TextView chip(String icon, String title, String[] options, OnPage onSelect) {
        final int[] sel = {0};
        TextView chip = text("", 13, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(16), dp(8), dp(16), dp(8));
        chip.setTextColor(attrColor(android.R.attr.colorAccent));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(22));
        shape.setColor(0xFF161616);
        shape.setStroke(dp(1), 0x40FFFFFF);
        chip.setBackground(shape);
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-2, -2);
        margin.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(margin);
        Runnable render = () -> chip.setText(icon + title + ": " + options[sel[0]]);
        chip.setOnClickListener(v -> {
            String[] items = new String[options.length];
            for (int i = 0; i < options.length; i++) {
                items[i] = (i == sel[0] ? "●  " : "○  ") + options[i];
            }
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setItems(items, (d, which) -> {
                        sel[0] = which;
                        render.run();
                        onSelect.run(which);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        render.run();
        return chip;
    }

    // ---- Rows and widgets ---------------------------------------------------------------------------

    private void section(LinearLayout list, String title) {
        TextView view = text(title.toUpperCase(Locale.ROOT), 13, true);
        view.setTextColor(attrColor(android.R.attr.colorAccent));
        view.setPadding(dp(16), dp(20), dp(16), dp(4));
        list.addView(view);
    }

    private void toggle(LinearLayout list, String title, String summary, IsOn isOn, Command command) {
        Switch view = new Switch(this);
        view.setClickable(false);
        view.setFocusable(false);
        Toggle t = new Toggle();
        t.view = view;
        t.isOn = isOn;
        toggles.add(t);
        row(list, title, summary, view, () -> {
            boolean on = !view.isChecked();
            view.setChecked(on);
            view.setEnabled(false);
            worker.execute(() -> {
                Shell.Result r = Shell.run(command.get(on));
                Map<String, String> values = readSettings();
                main.post(() -> {
                    view.setEnabled(true);
                    settings = values;
                    for (Toggle other : toggles) other.view.setChecked(other.isOn.test(values));
                    if (!r.ok()) toast(r.out.isEmpty() ? "Failed." : r.out);
                });
            });
        }, true);
    }

    private void link(LinearLayout list, String title, String summary, Runnable open) {
        row(list, title, summary, arrow(), open, true);
    }

    /** A row that runs a job and shows its result in place of the description. */
    private void action(LinearLayout list, String title, String summary, Job job) {
        action(list, title, summary, true, job);
    }

    private void action(LinearLayout list, String title, String summary, boolean needsShizuku, Job job) {
        View[] holder = new View[1];
        holder[0] = row(list, title, summary, null, () -> {
            TextView status = (TextView) holder[0].getTag();
            if (!holder[0].isEnabled()) return;
            holder[0].setEnabled(false);
            status.setText("Working…");
            worker.execute(() -> {
                String result = job.run();
                main.post(() -> {
                    holder[0].setEnabled(true);
                    status.setText(result);
                });
            });
        }, needsShizuku);
    }

    private View row(LinearLayout list, String title, String summary, View end, Runnable onClick,
                     boolean needsShizuku) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackgroundResource(attrResource(android.R.attr.selectableItemBackground));
        LinearLayout texts = vertical();
        texts.addView(text(title, 16, false));
        TextView sub = text(summary, 13, false);
        sub.setAlpha(0.7f);
        texts.addView(sub);
        if (needsShizuku) {
            // Shown only while Shizuku isn't usable, so rows that work without it stay plain.
            TextView label = text("Requires Shizuku", 12, true);
            label.setTextColor(attrColor(android.R.attr.colorAccent));
            label.setPadding(0, dp(2), 0, 0);
            label.setVisibility(Shell.ready() ? View.GONE : View.VISIBLE);
            texts.addView(label);
            shizukuLabels.add(label);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (end != null) row.addView(end);
        row.setTag(sub);
        row.setOnClickListener(v -> {
            if (needsShizuku && !Shell.ready()) {
                toast("Start Shizuku and allow TinyKit first.");
                return;
            }
            onClick.run();
        });
        if (needsShizuku) shizukuRows.add(row);
        list.addView(row);
        return row;
    }

    private TextView arrow() {
        TextView view = text("›", 24, false);
        view.setAlpha(0.5f);
        view.setPadding(dp(12), 0, 0, 0);
        return view;
    }

    /** A list row with up to three lines and an optional checkbox. */
    private final class Row {
        final LinearLayout view = new LinearLayout(MainActivity.this);
        final ImageView icon = new ImageView(MainActivity.this);
        final TextView first = text("", 16, false);
        final TextView second = text("", 13, false);
        final TextView third = text("", 14, false);
        final CheckBox check = new CheckBox(MainActivity.this);

        Row(boolean checkable) {
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(16), dp(10), dp(8), dp(10));
            icon.setVisibility(View.GONE);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
            ip.setMargins(0, 0, dp(14), 0);
            view.addView(icon, ip);
            LinearLayout texts = vertical();
            second.setAlpha(0.7f);
            texts.addView(first);
            texts.addView(second);
            texts.addView(third);
            view.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            check.setClickable(false);
            check.setFocusable(false);
            if (checkable) view.addView(check);
            view.setTag(this);
        }
    }

    /** Shows an app's icon in a row, loaded off the main thread and cached as a small bitmap. */
    private void showIcon(Row row, String pkg) {
        row.icon.setVisibility(View.VISIBLE);
        row.icon.setTag(pkg);
        Bitmap cached = iconCache.get(pkg);
        if (cached != null) {
            row.icon.setImageBitmap(cached);
            return;
        }
        row.icon.setImageResource(android.R.drawable.sym_def_app_icon);
        thumbs.execute(() -> {
            // The row may have been recycled to another app while scrolling — skip the render for
            // one that's no longer on screen, so a fast scroll doesn't load hundreds of icons.
            if (!pkg.equals(row.icon.getTag()) || iconCache.containsKey(pkg)) return;
            Bitmap b = iconBitmap(pkg, dp(40));
            if (b == null) return;
            iconCache.put(pkg, b);
            main.post(() -> {
                if (pkg.equals(row.icon.getTag())) row.icon.setImageBitmap(b);
            });
        });
    }

    /** An app's icon rendered once to a small {@code size}px bitmap, so the heavy source drawable
     * is dropped and only the small bitmap is cached. Call off the main thread. */
    private Bitmap iconBitmap(String pkg, int size) {
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(canvas);
            return bmp;
        } catch (PackageManager.NameNotFoundException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private final class Adapter<T> extends BaseAdapter {
        private final boolean checkable;
        private final Binder<T> binder;
        private List<T> items = new ArrayList<>();

        Adapter(boolean checkable, Binder<T> binder) {
            this.checkable = checkable;
            this.binder = binder;
        }

        void set(List<T> list) {
            items = list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public T getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row = convertView == null ? new Row(checkable) : (Row) convertView.getTag();
            row.third.setText("");
            row.icon.setVisibility(View.GONE);
            row.icon.setTag(null);
            binder.bind(getItem(position), row);
            row.second.setVisibility(TextUtils.isEmpty(row.second.getText()) ? View.GONE : View.VISIBLE);
            row.third.setVisibility(TextUtils.isEmpty(row.third.getText()) ? View.GONE : View.VISIBLE);
            return row.view;
        }
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private static TextWatcher watcher(Runnable onChange) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                onChange.run();
            }
        };
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int attrResource(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    private int attrColor(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private String label(String pkg) {
        synchronized (labels) {
            String cached = labels.get(pkg);
            if (cached != null) return cached;
        }
        String label = pkg;
        try {
            PackageManager pm = getPackageManager();
            label = String.valueOf(pm.getApplicationInfo(pkg, 0).loadLabel(pm));
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        synchronized (labels) {
            labels.put(pkg, label);
        }
        return label;
    }

    private String versionName(String pkg) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static String fileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|'\\s]+", "_");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            toast(url);
        }
    }

    private void openApp(String pkg) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) startActivity(launch);
    }
}
