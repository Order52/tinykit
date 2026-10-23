package io.github.order52.tinykit;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private static final String SHIZUKU_TERMUX = "https://github.com/Order52/shizuku-termux";
    private static final String REPO = "https://github.com/Order52/tinykit";
    private static final String TERMUX = "com.termux";
    private static final String TERMUX_BIN = "/data/data/com.termux/files/usr/bin/";
    private static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final String ACTION_TERMUX_RESULT = "io.github.order52.tinykit.TERMUX_RESULT";
    private static final int REQUEST_RUN_COMMAND = 2;
    private static final String[] TAB_NAMES = {"Shizuku", "Privacy", "Apps", "System", "Info"};
    private static final String DNS_HOST = "dns.adguard-dns.com";
    private static final String BACKUP_DIR = "/sdcard/Download/TinyKit";
    private static final String READ_SETTINGS = "for k in development_settings_enabled "
            + "private_dns_mode private_dns_specifier wifi_scan_always_enabled "
            + "ble_scan_always_enabled tether_dun_required mobile_data_always_on; do "
            + "echo \"$k=$(settings get global $k)\"; done";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern MEMINFO = Pattern.compile("^\\s*([\\d,]+)K: (\\S+) \\(pid \\d+");
    private static final Pattern APPOP = Pattern.compile("^(CAMERA|RECORD_AUDIO): ([a-z]+)", Pattern.MULTILINE);

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
    private final List<Toggle> toggles = new ArrayList<>();
    private final List<View> shizukuRows = new ArrayList<>();
    private final List<View> pages = new ArrayList<>();
    private final List<CharSequence> titles = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();
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
            String message = termuxMessage(intent.getBundleExtra("result"));
            // Shizuku's binder can arrive a moment after the command returns.
            main.postDelayed(() -> startFinished(message), 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tinykit", MODE_PRIVATE);
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizuku();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        main.removeCallbacks(startTimeout);
        unregisterReceiver(termuxResult);
        worker.shutdown();
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
        frame.addView(pages.get(pages.size() - 1));
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
        if (ready) loadSettings();
    }

    /**
     * Starts Shizuku without a computer by asking Termux to run the shizuku-termux command,
     * the same way the Shizuku Termux app does. The result comes back to {@link #termuxResult}.
     */
    private void startShizuku() {
        if (starting) return;
        if (!token().isEmpty()) {
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
            toast("Starting from the phone needs Termux with shizuku-termux installed.");
            openUrl(SHIZUKU_TERMUX);
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
            startService(intent);
        } catch (RuntimeException e) {
            toast("Could not reach Termux. Open Termux once, then try again.");
            return;
        }
        starting = true;
        refreshShizuku();
        main.postDelayed(startTimeout, 60_000L);
    }

    /**
     * With the auth token this uses Shizuku's own stop intent, which its Watchdog respects.
     * Without it the server process is ended, and the Watchdog may start it again.
     */
    private void stopShizuku() {
        if (!token().isEmpty()) {
            sendBroadcast(new Intent(SHIZUKU_PKG + ".STOP").setPackage(SHIZUKU_PKG)
                    .putExtra("auth", token()));
            toast("Stopping Shizuku…");
            main.postDelayed(this::refreshShizuku, 1500);
            return;
        }
        if (!Shell.ready()) {
            toast("Set the auth token first (below), or allow TinyKit in Shizuku.");
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
                + "shizuku-termux. Set the token if you use Shizuku's Watchdog, so Stop really stops it.", 13, false);
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
        return scroll(list);
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
                        "shizuku-termux: the one-line setup for Termux",
                        "Original Shizuku"}, (d, which) -> openUrl(new String[]{
                        SHIZUKU_DOWNLOAD, TERMUX_DOWNLOAD, SHIZUKU_TERMUX, SHIZUKU_ORIGINAL}[which]))
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
        link(list, "Block camera & mic",
                "Pick apps that can never use the camera or microphone.", this::openCameraMic);

        return scroll(list);
    }

    private View buildApps() {
        LinearLayout list = vertical();
        link(list, "Running apps", "Memory use of everything running. Stop any app.", this::openRunning);
        link(list, "Stop background activity",
                "Pick apps that can't run or wake the phone in the background.", this::openBackground);
        link(list, "Keep apps alive",
                "Pick apps Android must never kill or delay, like Termux or messengers.",
                this::openKeepAlive);
        link(list, "Back up app as APK", "Save any app's APK to Download/TinyKit.", this::openBackup);

        return scroll(list);
    }

    private View buildSystem() {
        LinearLayout list = vertical();
        section(list, "Quick panel");
        link(list, "Edit quick panel", "Reorder, add and remove tiles with one tap each.", this::openQuickPanel);

        section(list, "Clean & speed");
        action(list, "Clear all app caches", "Frees the space used by every app's cache.", () -> {
            String free = "df -k /data | tail -1 | awk '{print $4}'";
            Shell.Result r = Shell.run("a=$(" + free + "); pm trim-caches 999G; b=$(" + free + "); "
                    + "echo $(( (b - a) / 1024 ))");
            if (!r.ok()) return "Failed: " + r.out;
            long mb = 0;
            try {
                mb = Math.max(0, Long.parseLong(r.out.trim()));
            } catch (NumberFormatException ignored) {
            }
            return "Freed " + mb + " MB.";
        });
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
        row(list, "Phone info", "Battery, memory, storage and system details.", arrow(), this::openInfo, false);
        link(list, "Crash log", "See why an app crashed or closed by itself.", this::openCrashes);
        View[] update = new View[1];
        update[0] = row(list, "Check for updates", "You have version " + versionName(getPackageName()) + ".",
                null, () -> checkForUpdate(update[0]), false);
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

    private void openCameraMic() {
        openPicker("Block camera & mic",
                "Checked apps can never use the camera or microphone, even with permission.", true,
                () -> packages(Shell.run("appops query-op CAMERA ignore").out),
                (pkg, on) -> {
                    String q = Shell.quote(pkg);
                    if (on) {
                        Matcher m = APPOP.matcher(Shell.run(
                                "appops get " + q + " CAMERA; appops get " + q + " RECORD_AUDIO").out);
                        SharedPreferences.Editor edit = prefs.edit();
                        while (m.find()) {
                            if (!m.group(2).equals("ignore")) edit.putString(pkg + "/" + m.group(1), m.group(2));
                        }
                        edit.apply();
                        return error(Shell.run("appops set " + q + " CAMERA ignore; "
                                + "appops set " + q + " RECORD_AUDIO ignore"));
                    }
                    String camera = prefs.getString(pkg + "/CAMERA", "default");
                    String mic = prefs.getString(pkg + "/RECORD_AUDIO", "default");
                    prefs.edit().remove(pkg + "/CAMERA").remove(pkg + "/RECORD_AUDIO").apply();
                    return error(Shell.run("appops set " + q + " CAMERA " + camera + "; "
                            + "appops set " + q + " RECORD_AUDIO " + mic));
                });
    }

    private void openBackground() {
        openPicker("Stop background activity",
                "Checked apps can't run or keep the phone awake in the background. Their "
                        + "notifications may arrive late, so leave messengers unchecked.", true,
                () -> packages(Shell.run("appops query-op RUN_ANY_IN_BACKGROUND ignore").out),
                (pkg, on) -> {
                    String q = Shell.quote(pkg);
                    String mode = on ? "ignore" : "allow";
                    return error(Shell.run("appops set " + q + " RUN_ANY_IN_BACKGROUND " + mode
                            + "; appops set " + q + " WAKE_LOCK " + mode));
                });
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
        TextView loading = text("Loading apps…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push(title, page);

        List<App> all = new ArrayList<>();
        Adapter<App> adapter = new Adapter<>(checkable, (app, row) -> {
            row.first.setText(app.label);
            row.second.setText(app.pkg);
            row.check.setChecked(app.checked);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<App> shown = new ArrayList<>();
            for (App a : all) {
                if ((system.isChecked() || !a.system || a.checked)
                        && (a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.contains(q))) {
                    shown.add(a);
                }
            }
            adapter.set(shown);
        };
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

        worker.execute(() -> {
            List<App> list = new ArrayList<>();
            Set<String> checked = loader == null ? Collections.emptySet() : loader.load();
            for (App a : installedApps()) {
                App copy = a.copy();
                copy.checked = checked.contains(a.pkg);
                list.add(copy);
            }
            // Checked apps first so it's easy to see and undo what is on.
            Collections.sort(list, (x, y) -> x.checked == y.checked
                    ? x.label.compareToIgnoreCase(y.label) : (x.checked ? -1 : 1));
            main.post(() -> {
                all.addAll(list);
                loading.setVisibility(View.GONE);
                filter.run();
            });
        });
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
        });
        listView.setAdapter(adapter);
        Runnable[] load = new Runnable[1];
        load[0] = () -> worker.execute(() -> {
            List<Proc> procs = runningProcesses();
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
            main.post(() -> {
                summary.setText("Memory: " + Formatter.formatShortFileSize(this, mem.totalMem - mem.availMem)
                        + " used of " + Formatter.formatShortFileSize(this, mem.totalMem));
                adapter.set(procs);
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
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), dp(24));
        page.addView(text("Loading…", 15, false));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        push("Phone info", scroll);
        worker.execute(() -> {
            List<String[]> info = phoneInfo();
            main.post(() -> {
                page.removeAllViews();
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

    /** Asks GitHub for the latest release. Runs only when tapped; nothing checks in the background. */
    private void checkForUpdate(View row) {
        TextView status = (TextView) row.getTag();
        if (!row.isEnabled()) return;
        row.setEnabled(false);
        status.setText("Checking…");
        worker.execute(() -> {
            String latest = null;
            String apk = null;
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
                if (tag.find()) latest = tag.group(1);
                Matcher url = Pattern.compile("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").matcher(body);
                if (url.find()) apk = url.group(1);
            } catch (Exception ignored) {
            }
            String current = versionName(getPackageName());
            String found = latest;
            String download = apk != null ? apk : REPO + "/releases/latest";
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
        }, true);
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
        final TextView first = text("", 16, false);
        final TextView second = text("", 13, false);
        final TextView third = text("", 14, false);
        final CheckBox check = new CheckBox(MainActivity.this);

        Row(boolean checkable) {
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(dp(16), dp(10), dp(8), dp(10));
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
