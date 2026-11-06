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

/**
 * TinyKit: small system tweaks and tools that run through Shizuku.
 *
 * One home list grouped by section; tools that need more room open as pages on top of it.
 * Settings toggles are plain `settings put global` changes, per-app tools use appops and
 * deviceidle, so everything can be undone from the same screen.
 */
public class MainActivity extends Activity {

    /** Opens TinyKit on this tab (the background notification opens the Info tab). */
    static final String EXTRA_TAB = "tab";
    /** Set by the Audio mixer tile: open the mixer page. */
    static final String EXTRA_MIXER = "mixer";
    private static final int REQUEST_SHIZUKU = 1;
    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    // thedjchi's fork adds a Watchdog that restarts Shizuku when Android kills it.
    private static final String SHIZUKU_DOWNLOAD = "https://github.com/thedjchi/Shizuku/releases/latest";
    private static final String SHIZUKU_ORIGINAL = "https://shizuku.rikka.app/download/";
    private static final String REPO = "https://github.com/Order52/tinykit";
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final int REQUEST_RUN_COMMAND = 3;
    private static final int REQUEST_PICK_APK = 4;
    private static final String[] TAB_NAMES = {"Shizuku", "Privacy", "Apps", "System", "Info"};
    private static final String DNS_HOST = "dns.adguard-dns.com";
    private static final String BACKUP_DIR = "/sdcard/Download/TinyKit";
    private static final String READ_SETTINGS = "for k in development_settings_enabled "
            + "private_dns_mode private_dns_specifier wifi_scan_always_enabled "
            + "ble_scan_always_enabled tether_dun_required mobile_data_always_on; do "
            + "echo \"$k=$(settings get global $k)\"; done";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern CPUINFO = Pattern.compile("^\\s*\\d+\\s+([\\d.]+)\\s+(\\S+)");
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
        float cpu; // percent of the CPU in the recent past, from dumpsys cpuinfo
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Reads of switch states, kept off {@link #worker} because some take a second. */
    private final ExecutorService reads = Executors.newSingleThreadExecutor();
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
    private final List<Runnable> liveSwitches = new ArrayList<>();
    // Every row of the five tabs, for the search on the home tab. Filled while the tabs are built.
    private final List<Feature> searchIndex = new ArrayList<>();
    private int indexingTab = -1;
    // Rows that need Shizuku, dimmed while it isn't usable. Weak, so rows of closed pages can go.
    private final Set<View> shizukuRows = Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Set<View> shizukuLabels = Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private Switch updateSwitch;
    private final List<View> pages = new ArrayList<>();
    private final List<CharSequence> titles = new ArrayList<>();
    private final Map<String, String> labels = new HashMap<>();
    // Mixed audio state per package: {muted 0/1, plays-with-others 0/1}.
    private final Map<String, int[]> audioState = new HashMap<>();
    private Map<String, String> settings = new HashMap<>();
    private volatile List<App> apps;
    private final Object appsLock = new Object();
    private SharedPreferences prefs;
    private boolean lightTheme;
    private FrameLayout frame;
    private final TextView[] tabs = new TextView[TAB_NAMES.length];
    private final ImageView[] tabIcons = new ImageView[TAB_NAMES.length];
    private static final int[] TAB_ICONS = {R.drawable.ic_tile_shizuku, R.drawable.ic_nav_privacy,
            R.drawable.ic_nav_apps, R.drawable.ic_nav_system, R.drawable.ic_nav_info};
    private View[] tabRoots;
    private View tabBar;
    private EditText searchBox;
    private TextView shizukuText;
    private TextView tokenText;
    private Button shizukuButton;
    private Button startButton;
    private Button stopButton;
    private boolean starting, stopping, askedNotifications, termuxChecked, askedTermux, askedTermuxSetup, termuxResume;

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> main.post(this::refreshShizuku);
    private final Shizuku.OnBinderDeadListener binderDead = () -> main.post(this::refreshShizuku);
    private final Shizuku.OnRequestPermissionResultListener permissionResult =
            (code, result) -> main.post(this::refreshShizuku);
    private final Runnable startTimeout = () -> startFinished(ShizukuControl.intents(this, prefs)
            ? "Shizuku didn't start. Check the auth token matches the one in Shizuku's settings."
            : "Shizuku didn't start. Tap Start again, or open the Shizuku app.");
    private final Runnable stopTimeout = () -> stopFinished(ShizukuControl.intents(this, prefs)
            ? "Shizuku didn't stop. Check the auth token matches the one in Shizuku's settings."
            : "Shizuku didn't stop. Try again, or stop it in the Shizuku app.");

    private void applySystemBarColors() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (lightTheme) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    @Override
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // flag exists from Android 13
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences("tinykit", MODE_PRIVATE);
        lightTheme = prefs.getBoolean("light_theme", false);
        if (lightTheme) setTheme(R.style.AppTheme_Light);
        super.onCreate(savedInstanceState);
        Tr.init(this);
        applySystemBarColors();
        // Warm the installed-apps cache (loading labels is the slow part) so every per-app list,
        // picker and log opens instantly. Runs off the worker queue to not delay the first tap.
        thumbs.execute(this::installedApps);
        LinearLayout root = vertical();
        // Takes the first focus, so the search box doesn't open the keyboard on start.
        root.setFocusableInTouchMode(true);
        root.requestFocus();
        frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1f));
        tabBar = buildTabBar();
        root.addView(tabBar);
        setContentView(root);
        if (lightTheme && Build.VERSION.SDK_INT >= 29) root.setForceDarkAllowed(false); // see AppTheme_Light
        tabRoots = new View[TAB_NAMES.length];
        int tab = Math.max(0, Math.min(getIntent().getIntExtra(EXTRA_TAB, prefs.getInt("tab", 0)),
                TAB_NAMES.length - 1));
        // Only the tab on screen is built before the first frame; the others follow, one per turn of the
        // main loop, and any of them is built on the spot if it is needed sooner (a tap, the search).
        buildTab(0); // Shizuku's status views are updated whenever its binder comes or goes
        selectTab(tab);
        if (getIntent().getBooleanExtra(EXTRA_MIXER, false)) {
            getIntent().removeExtra(EXTRA_MIXER); // so turning the phone doesn't open it again
            if (Mixer.supported()) main.post(this::openMixer);
        }
        root.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                main.post(MainActivity.this::buildNextTab);
                return true;
            }
        });
        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        // Not on rotation or other re-creations: only when TinyKit is actually opened.
        if (savedInstanceState == null) {
            Runnable startup = () -> {
                if (!showWhatsNew(this::startupUpdateCheck)) startupUpdateCheck();
            };
            if (!offerCrashReport(startup)) startup.run();
        }
    }

    /**
     * If TinyKit crashed last time, offers to send the details: one tap opens a ready-made GitHub
     * issue that the user reads and posts themselves. Returns whether the offer is showing; {@code
     * next} runs when it is closed.
     */
    private boolean offerCrashReport(Runnable next) {
        String report = CrashReport.pending(this);
        if (report == null) return false;
        if (!prefs.getBoolean(CrashReport.PREF, true)) {
            CrashReport.clear(this);
            return false;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(Tr.t("TinyKit stopped last time"))
                .setMessage(Tr.t("Do you want to send the details so it can be fixed? This opens a ready-made GitHub "
                        + "issue with TinyKit's version, your phone model, your Android version and the error. "
                        + "You can read and change it first, and nothing is sent until you post it."))
                .setPositiveButton(Tr.t("Send"), (d, w) -> sendReport(report))
                .setNegativeButton(Tr.t("Not now"), (d, w) -> CrashReport.clear(this))
                .setOnCancelListener(d -> CrashReport.clear(this))
                .setOnDismissListener(d -> next.run());
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            dialog.setNeutralButton(Tr.t("Ask me right away next time"), (d, w) -> {
                CrashReport.clear(this);
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
            });
        }
        dialog.show();
        return true;
    }

    /** The first line of a saved report is the issue title; the rest is the details. */
    private void sendReport(String report) {
        int nl = report.indexOf('\n');
        String title = nl > 0 ? report.substring(0, nl) : report;
        String details = nl > 0 ? report.substring(nl + 1) : "";
        if (title.length() > 120) title = title.substring(0, 120);
        try {
            startActivity(CrashReport.issue(title, details));
            CrashReport.clear(this);
        } catch (RuntimeException noBrowser) {
            toast(Tr.t("No browser found to open GitHub."));
        }
    }

    /** "Report a problem": the same one-tap issue, for when TinyKit misbehaves without crashing. */
    private void reportProblem() {
        toast(Tr.t("Preparing the report…"));
        worker.execute(() -> {
            String crash = CrashReport.pending(this);
            String details = CrashReport.header(this) + "\nRecent log:\n" + CrashReport.recentLog()
                    + (crash == null ? "" : "\nLast crash:\n" + crash.substring(crash.indexOf('\n') + 1));
            main.post(() -> {
                try {
                    startActivity(CrashReport.issue("Problem: ", details));
                } catch (RuntimeException noBrowser) {
                    toast(Tr.t("No browser found to open GitHub."));
                }
            });
        });
    }

    private void startupUpdateCheck() {
        if (prefs.contains("update_on_start")) autoCheckForUpdate();
        else askUpdateCheck();
    }

    private int versionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** The release notes for versions after {@code after} up to this one, newest first, or "". */
    private String releaseNotes(int after) {
        StringBuilder notes = new StringBuilder();
        int shown = 0;
        for (int code = versionCode(); code > after && shown < 5; code--) {
            try (java.io.InputStream in = getAssets().open(code + ".txt")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                for (int n; (n = in.read(chunk)) != -1; ) bytes.write(chunk, 0, n);
                String text = bytes.toString("UTF-8").trim();
                if (text.isEmpty()) continue;
                if (notes.length() > 0) notes.append("\n\n");
                notes.append(text);
                shown++;
            } catch (java.io.IOException noNotes) {
                // No notes for that version.
            }
        }
        return notes.toString();
    }

    /**
     * After an update, shows what is new once (the notes of every version since the one last
     * seen). A first install just records the version. Returns whether a popup is showing; {@code
     * next} runs when it is closed.
     */
    private boolean showWhatsNew(Runnable next) {
        int code = versionCode();
        int seen = prefs.getInt("whatsnew_seen", -1);
        // A first install has nothing new to show; an existing install from before this popup does.
        boolean fresh = seen == -1 && !prefs.contains("update_on_start");
        prefs.edit().putInt("whatsnew_seen", code).apply();
        if (fresh || seen >= code) return false;
        String notes = releaseNotes(seen);
        if (notes.isEmpty()) return false;
        showNotes(notes, next);
        return true;
    }

    private void showNotes(String notes, Runnable next) {
        new AlertDialog.Builder(this)
                .setTitle(Tr.t("What's new in " + versionName(getPackageName())))
                .setMessage(Tr.t(notes))
                .setPositiveButton(Tr.t("OK"), null)
                .setOnDismissListener(d -> {
                    if (next != null) next.run();
                })
                .show();
    }

    /** First run: asks once whether to check for updates whenever TinyKit opens. */
    private void askUpdateCheck() {
        new AlertDialog.Builder(this)
                .setTitle(Tr.t("Check for updates?"))
                .setMessage(Tr.t("Do you want TinyKit to check for a new version each time you open it? It asks "
                        + "GitHub at most once an hour and shows a popup if there is one. Nothing else is "
                        + "sent. You can change this later in the Info tab."))
                .setCancelable(false)
                .setPositiveButton(Tr.t("Turn on"), (d, w) -> setUpdateOnStart(true))
                .setNegativeButton(Tr.t("No thanks"), (d, w) -> setUpdateOnStart(false))
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
        if (termuxResume) { // back from Termux, which has read its settings again
            termuxResume = false;
            termuxChecked = false;
            main.postDelayed(this::startShizuku, 500);
        }
        refreshShizuku();
    }

    /** Opened again from the background notification: go to the tab it asks for. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("termux_done", false)) { // the pasted command finished
            termuxResume = false;
            termuxChecked = false;
            main.postDelayed(this::startShizuku, 500);
            return;
        }
        if (intent.hasExtra(EXTRA_TAB)) {
            selectTab(Math.max(0, Math.min(intent.getIntExtra(EXTRA_TAB, 0), TAB_NAMES.length - 1)));
        }
        if (intent.getBooleanExtra(EXTRA_MIXER, false) && Mixer.supported()) {
            selectTab(2); // the Apps tab, so Back from the mixer lands where the row is
            openMixer();
        }
    }

    /** Choices made on screen (a blocked app, a Fake permission) can start or stop the background service. */
    @Override
    protected void onPause() {
        super.onPause();
        BackgroundService.sync(this);
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
        main.removeCallbacks(stopTimeout);
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
            getActionBar().setTitle(Tr.t(titles.get(titles.size() - 1)));
            getActionBar().setDisplayHomeAsUpEnabled(pages.size() > 1);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (pages.size() == 1 && searchBox != null && searchBox.length() > 0 && pages.get(0) == tabRoots[0]) {
            searchBox.setText(""); // Back leaves the search first, then the app
            return;
        }
        if (pages.size() > 1) {
            pages.remove(pages.size() - 1);
            titles.remove(titles.size() - 1);
            show();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Bottom bar: one tab per group, so every tool is at most two taps away. Sized like Material 3's
     * navigation bar: 80dp tall (the whole height is the touch target), a 24dp icon in a 64x32dp pill
     * when selected, and a 12sp label 4dp below it.
     */
    private View buildTabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(lightTheme ? 0xFFEBEBEF : 0xFF0D0D0D);
        bar.setMinimumHeight(dp(80));
        for (int i = 0; i < TAB_NAMES.length; i++) {
            LinearLayout tab = vertical();
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(0, dp(12), 0, dp(16));
            tab.setContentDescription(Tr.t(TAB_NAMES[i]));
            ImageView icon = new ImageView(this);
            icon.setDuplicateParentStateEnabled(true); // the pill behind the icon is the only thing that lights up
            icon.setImageResource(TAB_ICONS[i]);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            tabIcons[i] = icon;
            tab.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(32)));
            TextView label = text(TAB_NAMES[i], 12, false);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setPadding(dp(1), dp(4), dp(1), 0);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tabs[i] = label;
            tab.addView(label, new LinearLayout.LayoutParams(-1, -2));
            int index = i;
            tab.setOnClickListener(v -> selectTab(index));
            bar.addView(tab, new LinearLayout.LayoutParams(0, -1, 1f));
        }
        // Large text or a narrow screen: every label shrinks to the same size so the longest fits.
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) bar.post(this::fitTabs);
        });
        return bar;
    }

    private void fitTabs() {
        float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics());
        float min = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10.5f, getResources().getDisplayMetrics());
        android.text.TextPaint paint = new android.text.TextPaint(tabs[0].getPaint());
        paint.setTypeface(Typeface.DEFAULT_BOLD); // the selected tab is bold, and widest
        while (px > min && !tabsFit(paint, px)) px -= 0.5f;
        for (TextView tab : tabs) tab.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
    }

    private boolean tabsFit(android.text.TextPaint paint, float px) {
        paint.setTextSize(px);
        for (TextView tab : tabs) {
            int room = tab.getWidth() - tab.getPaddingLeft() - tab.getPaddingRight() - dp(2);
            if (room <= 0) return true; // not laid out yet
            if (paint.measureText(tab.getText().toString()) > room) return false;
        }
        return true;
    }

    /** The rounded pill behind a tab's icon: filled when selected, and the one place a press shows. */
    private android.graphics.drawable.RippleDrawable pill(boolean selected) {
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(dp(16));
        fill.setColor(selected ? (lightTheme ? 0x262962FF : 0x338C9EFF) : 0);
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(16));
        mask.setColor(lightTheme ? 0xFFFFFFFF : 0xFF000000);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(lightTheme ? 0x20000000 : 0x4DFFFFFF), fill, mask);
    }

    private View buildTab(int i) {
        if (tabRoots[i] == null) {
            int before = indexingTab;
            indexingTab = i; // lets rows register for search and for refreshing
            try {
                tabRoots[i] = i == 0 ? buildShizuku() : i == 1 ? buildPrivacy()
                        : i == 2 ? buildApps() : i == 3 ? buildSystem() : buildInfo();
            } finally {
                indexingTab = before;
            }
        }
        return tabRoots[i];
    }

    private void buildNextTab() {
        for (int i = 0; i < tabRoots.length; i++) {
            if (tabRoots[i] == null) {
                buildTab(i);
                main.post(this::buildNextTab);
                return;
            }
        }
    }

    private void selectTab(int index) {
        prefs.edit().putInt("tab", index).apply();
        int accent = attrColor(android.R.attr.colorAccent);
        for (int i = 0; i < tabs.length; i++) {
            boolean selected = i == index;
            tabs[i].setTextColor(selected ? accent : (lightTheme ? 0xFF111111 : 0xFFFFFFFF));
            tabs[i].setAlpha(selected ? 1f : (lightTheme ? 1f : 0.65f));
            tabs[i].setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            tabIcons[i].setColorFilter(selected ? accent : (lightTheme ? 0xFF111111 : 0xA6FFFFFF));
            tabIcons[i].setBackground(pill(selected));
            ((View) tabs[i].getParent()).setSelected(selected);
        }
        pages.clear();
        titles.clear();
        push(index == 0 ? getString(R.string.app_name) : TAB_NAMES[index], buildTab(index));
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
        if (starting && running) {
            starting = false;
            main.removeCallbacks(startTimeout);
        }
        if (stopping && !running) {
            stopping = false;
            main.removeCallbacks(stopTimeout);
        }
        if (!installed) {
            shizukuText.setText(Tr.t("Shizuku is not installed"));
            shizukuButton.setText(Tr.t("Get Shizuku"));
            shizukuButton.setOnClickListener(v -> showInstall());
        } else if (running && !ready) {
            shizukuText.setText(Tr.t("Shizuku is running. Allow TinyKit to use it."));
            shizukuButton.setText(Tr.t("Allow"));
            shizukuButton.setOnClickListener(v -> {
                try {
                    Shizuku.requestPermission(REQUEST_SHIZUKU);
                } catch (RuntimeException e) {
                    toast(Tr.t("Shizuku isn't ready yet. Open Shizuku, make sure it is running, then try again."));
                }
            });
        } else {
            shizukuText.setText(Tr.t(running ? "Shizuku is running" : "Shizuku is not running"));
            shizukuButton.setText(Tr.t("Open Shizuku"));
            shizukuButton.setOnClickListener(v -> openApp(SHIZUKU_PKG));
        }
        if (installed && (starting || stopping)) {
            shizukuText.setText(Tr.t(stopping ? "Stopping Shizuku…" : "Starting Shizuku…"));
        }
        statusDot(stopping || starting ? 0xFFFFB74D : ready ? 0xFF66BB6A : running ? 0xFFFFB74D : 0xFFEF5350);
        startButton.setEnabled(installed && !starting && !stopping && !running);
        startButton.setText(Tr.t(starting ? "Starting…" : "Start"));
        stopButton.setEnabled(running && !stopping);
        stopButton.setText(Tr.t(stopping ? "Stopping…" : "Stop"));
        tokenText.setText(Tr.t(token().isEmpty()
                ? "Not set. Tap to paste it from Shizuku's settings."
                : "Set. Start and Stop go straight to Shizuku."));
        for (View row : shizukuRows) row.setAlpha(ready ? 1f : (lightTheme ? 0.45f : 0.4f));
        for (View label : shizukuLabels) label.setVisibility(ready ? View.GONE : View.VISIBLE);
        if (ready) {
            loadSettings();
            for (Runnable r : liveSwitches) r.run();
            // Grant TinyKit the permissions its toggles and tiles need, so they keep working
            // when Shizuku is later turned off. Done once, off the main thread.
            worker.execute(() -> Tweak.grantSelf(this));
            // Puts back what a restart wiped (firewall rules, Force dark); once per boot.
            Restore.start(this);
            // Fake permissions Android reset to Real in the meantime go back to Fake.
            worker.execute(() -> {
                String fixed = FakePolicy.enforce(this, prefs);
                if (fixed != null && !fixed.isEmpty()) main.post(() -> toast(Tr.t(fixed)));
            });
        }
    }

    /** The coloured dot in front of the Shizuku status: green ready, amber waiting, red off. */
    private void statusDot(int color) {
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(color);
        dot.setSize(dp(12), dp(12));
        shizukuText.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
    }

    /**
     * Starts Shizuku without a computer: through its start intent when an auth token is saved and the
     * installed Shizuku has one, otherwise with TinyKit's own adb client over Wireless debugging
     * ({@link AdbService}): Wireless debugging on Android 11+ (it asks for a pairing code once), or root or adb
     * over the network on older Android. TinyKit's binder listener refreshes this screen as soon as
     * Shizuku is running.
     */
    /**
     * Gets Termux ready to start Shizuku, by itself: the permission to run commands there (Android asks once), and
     * the Termux setting that lets other apps do it, written through Termux's file provider after the person picks
     * Termux's folder once. When Termux can't be used, Start goes on with TinyKit's own adb client.
     */
    private void prepareTermux() {
        if (!Termux.permitted(this)) {
            if (!askedTermux && Build.VERSION.SDK_INT >= 23) {
                askedTermux = true;
                requestPermissions(new String[]{Termux.PERMISSION}, REQUEST_RUN_COMMAND);
                return;
            }
            termuxChecked = true; // not allowed: the other way
            startShizuku();
            return;
        }
        if (prefs.getBoolean(AdbService.PREF_TERMUX, false)) {
            termuxChecked = true;
            startShizuku();
            return;
        }
        starting = true;
        refreshShizuku();
        worker.execute(() -> {
            Termux.Result r = Termux.probe(this);
            main.post(() -> {
                starting = false;
                if (r.ran()) {
                    prefs.edit().putBoolean(AdbService.PREF_TERMUX, true).apply();
                    termuxChecked = true;
                    startShizuku();
                } else if (r.allowMissing && !askedTermuxSetup) {
                    askedTermuxSetup = true;
                    // The one command, copied: pasting it in Termux sets everything up and brings TinyKit back.
                    if (Termux.setUp(this)) {
                        termuxResume = true;
                        toast(Tr.t("Command copied. Paste it in Termux (hold, Paste) and press Enter."));
                    } else {
                        termuxChecked = true;
                        startShizuku();
                    }
                } else {
                    termuxChecked = true; // Termux can't be used right now: the other way
                    startShizuku();
                }
            });
        });
    }

    private void startShizuku() {
        if (starting) return;
        if (!ShizukuControl.intents(this, prefs)) {
            if (Termux.installed(this) && !termuxChecked) {
                prepareTermux();
                return;
            }
            // The notification that asks for the pairing code needs to be allowed (Android 13+); asked once.
            if (Build.VERSION.SDK_INT >= 33 && AdbService.supported() && !AdbService.paired(this) && !askedNotifications
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                askedNotifications = true;
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQUEST_NOTIFICATIONS);
                return;
            }
        }
        if (!ShizukuControl.start(this, prefs)) {
            shizukuProblem("Shizuku didn't start", "TinyKit couldn't start its Shizuku starter. Tap Start again, or "
                    + "open the Shizuku app.");
            return;
        }
        starting = true;
        refreshShizuku();
        AdbService.onFinish = message -> main.postDelayed(() -> {
            if (starting && !Shell.running()) startFinished(message); // it ended and Shizuku isn't there
        }, 4000);
        // Shizuku's own start intent takes a couple of seconds. TinyKit's own start can wait for pairing, which
        // it asks for in a notification, so it is given minutes; it reports a failure in a notification itself.
        main.postDelayed(startTimeout, ShizukuControl.intents(this, prefs) ? 15_000L
                : AdbService.paired(this) && !prefs.getBoolean(AdbService.PREF_TERMUX, false) ? 40_000L : 300_000L);
    }

    /**
     * The standard Shizuku app can only be started from its own screen (or a computer), so open
     * it there. TinyKit's binder listener refreshes this screen as soon as Shizuku is running.
     */
    private void openShizukuToStart() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
        if (launch == null) {
            toast(Tr.t("Install Shizuku first."));
            return;
        }
        toast(Tr.t("Tap Start in Shizuku. TinyKit connects by itself once it's running."));
        startActivity(launch);
    }

    /** Stops Shizuku the best way it can (see {@link ShizukuControl#stop}); the binder listener redraws the card. */
    private void stopShizuku() {
        if (stopping) return;
        stopping = true;
        refreshShizuku();
        // Gives up if Shizuku is still there: a wrong auth token is ignored without a word.
        main.postDelayed(stopTimeout, 6000);
        // Its own thread: the worker may be busy, and Stop should never wait behind other work.
        new Thread(() -> {
            String error = ShizukuControl.stop(getApplicationContext(), prefs);
            if (error != null) main.post(() -> stopFinished(error));
        }).start();
    }

    private void stopFinished(String message) {
        main.removeCallbacks(stopTimeout);
        stopping = false;
        refreshShizuku();
        if (Shell.running() && message != null) shizukuProblem("Shizuku didn't stop", message);
    }

    /** A start or stop that failed: the whole message (a toast cuts it at two lines), and where to fix it. */
    private void shizukuProblem(String title, String message) {
        if (!alive()) return;
        new AlertDialog.Builder(this)
                .setTitle(Tr.t(title))
                .setMessage(Tr.t(message))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(Tr.t("Open Shizuku"), (d, w) -> openApp(SHIZUKU_PKG))
                .show();
    }

    private String token() {
        return prefs.getString("auth", "");
    }

    private void editToken() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(Tr.t(token()));
        input.setHint(Tr.t("Auth token"));
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(Tr.t("Shizuku auth token"))
                .setMessage(Tr.t("Copy it from Shizuku: Settings, where the start/stop intent "
                        + "parameters are listed."))
                .setView(box)
                .setPositiveButton(Tr.t("Save"), (d, w) -> {
                    prefs.edit().putString("auth", input.getText().toString().trim()).apply();
                    refreshShizuku();
                })
                .setNeutralButton(Tr.t("Clear"), (d, w) -> {
                    prefs.edit().remove("auth").apply();
                    refreshShizuku();
                })
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    private void startFinished(String message) {
        main.removeCallbacks(startTimeout);
        starting = false;
        refreshShizuku();
        if (!Shell.running() && !TextUtils.isEmpty(message)) shizukuProblem("Shizuku didn't start", message);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // Allowed or not, go on: without the notification the start still works when TinyKit is paired already.
        if (requestCode == REQUEST_NOTIFICATIONS) startShizuku();
        if (requestCode == REQUEST_RUN_COMMAND) startShizuku(); // allowed or not: it goes on either way
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
        status.setPadding(dp(16), dp(16), dp(16), dp(12));
        GradientDrawable card = new GradientDrawable();
        card.setCornerRadius(dp(16));
        card.setColor(lightTheme ? 0xFFFFFFFF : 0xFF121212);
        if (lightTheme) card.setStroke(dp(1), 0x1A000000);
        status.setBackground(card);
        shizukuText = text("", 18, true);
        shizukuText.setGravity(Gravity.CENTER_VERTICAL);
        shizukuText.setCompoundDrawablePadding(dp(10));
        shizukuText.setPadding(dp(4), 0, 0, 0);
        status.addView(shizukuText);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(12), 0, 0);
        startButton = primary(button("Start", v -> {
            termuxChecked = false; // a tap checks Termux again
            askedTermuxSetup = false;
            startShizuku();
        }));
        stopButton = button("Stop", v -> stopShizuku());
        buttons.addView(startButton, weight());
        buttons.addView(stopButton, weight());
        status.addView(buttons);
        shizukuButton = button("", null);
        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(-1, -2);
        full.setMargins(dp(4), dp(8), dp(4), 0);
        status.addView(shizukuButton, full);
        LinearLayout.LayoutParams cardMargin = new LinearLayout.LayoutParams(-1, -2);
        cardMargin.setMargins(dp(12), dp(8), dp(12), dp(4));
        list.addView(status, cardMargin);

        row(list, "Auth token", "", arrow(), this::editToken, false);
        tokenText = (TextView) list.getChildAt(list.getChildCount() - 1).getTag();
        TextView help = text("Start uses the auth token when it's set, otherwise TinyKit starts Shizuku itself "
                + "through Wireless debugging (Android 11+; the first time it asks for a pairing code), otherwise it "
                + "opens Shizuku so you tap its Start button. Stop closes Shizuku fully, so a Watchdog can't start "
                + "it straight back up.", 13, false);
        setDim(help, 0.6f);
        help.setPadding(dp(16), dp(8), dp(16), 0);
        list.addView(help);

        section(list, "Quick Settings");
        row(list, "Shizuku tile", "Turn Shizuku on and off from the quick panel with one tap.",
                arrow(), () -> addTile(ShizukuTile.class, "Shizuku", R.drawable.ic_tile_shizuku,
                        "One tap turns Shizuku on or off. It uses the auth token when it's set, "
                                + "otherwise TinyKit starts it through Wireless debugging."), false);
        row(list, "Install Shizuku", "The recommended version keeps running in the background.",
                arrow(), this::showInstall, false);
        return withSearch(scroll(list));
    }

    /**
     * Wraps the home screen with a search bar over every row of every tab. Typing swaps the home
     * content for a results list; clearing it brings the home screen back. A result that opens a page
     * opens it; a switch or an action is shown in its tab, so nothing runs by surprise.
     */
    /** A search box in the same rounded style as the one on the first tab. */
    private EditText searchField(String hint) {
        EditText search = new EditText(this);
        search.setHint(Tr.t(hint));
        search.setSingleLine(true);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setPadding(dp(16), dp(10), dp(16), dp(10));
        if (lightTheme) {
            search.setTextColor(0xFF000000);
            search.setHintTextColor(0xFF555555);
        }
        GradientDrawable bg = roundRect(lightTheme ? 0xFFFFFFFF : 0xFF1A1A1A, 24);
        if (lightTheme) bg.setStroke(dp(1), 0x26000000);
        search.setBackground(bg);
        return search;
    }

    private LinearLayout.LayoutParams fieldMargin() {
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2);
        margin.setMargins(0, dp(4), 0, dp(4));
        return margin;
    }

    private View withSearch(View home) {
        LinearLayout outer = vertical();
        EditText search = new EditText(this);
        searchBox = search;
        search.setHint(Tr.t("Search all settings & tools"));
        search.setSingleLine(true);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        search.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (lightTheme) {
            search.setTextColor(0xFF000000);
            search.setHintTextColor(0xFF555555);
        }
        GradientDrawable box = new GradientDrawable();
        box.setCornerRadius(dp(24));
        box.setColor(lightTheme ? 0xFFFFFFFF : 0xFF1A1A1A);
        if (lightTheme) box.setStroke(dp(1), 0x26000000);
        search.setBackground(box);
        LinearLayout.LayoutParams boxMargin = new LinearLayout.LayoutParams(-1, -2);
        boxMargin.setMargins(dp(12), dp(8), dp(12), dp(4));
        outer.addView(search, boxMargin);
        FrameLayout holder = new FrameLayout(this);
        holder.addView(home);
        ListView results = new ListView(this);
        results.setVisibility(View.GONE);
        TextView none = note("Nothing found.");
        none.setVisibility(View.GONE);
        holder.addView(results);
        holder.addView(none);
        outer.addView(holder, new LinearLayout.LayoutParams(-1, 0, 1f));

        Adapter<Feature> adapter = new Adapter<>(false, (f, row) -> {
            row.first.setText(Tr.t(f.title));
            row.second.setText(Tr.t(f.summary));
            row.third.setText(Tr.t(TAB_NAMES[f.tab]));
            row.third.setTextColor(attrColor(android.R.attr.colorAccent));
            row.third.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        });
        results.setAdapter(adapter);
        results.setOnItemClickListener((parent, view, position, id) -> {
            Feature f = adapter.getItem(position);
            search.setText("");
            hideKeyboard(search);
            reveal(f);
        });
        search.addTextChangedListener(watcher(() -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT).trim();
            // The results get the whole screen above the keyboard while you type.
            if (tabBar != null) tabBar.setVisibility(q.isEmpty() ? View.VISIBLE : View.GONE);
            if (q.isEmpty()) {
                home.setVisibility(View.VISIBLE);
                results.setVisibility(View.GONE);
                none.setVisibility(View.GONE);
                return;
            }
            String[] terms = q.split("\\s+");
            for (int i = 0; i < terms.length; i++) terms[i] = squash(terms[i]);
            List<Feature> titleHits = new ArrayList<>();
            List<Feature> otherHits = new ArrayList<>();
            for (int i = 0; i < tabRoots.length; i++) buildTab(i); // search covers every tab
            for (Feature f : searchIndex) {
                String title = squash(f.title + " " + Tr.t(f.title));
                String hay = title + squash(f.summary + " " + Tr.t(f.summary));
                boolean match = true;
                boolean inTitle = true;
                for (String term : terms) {
                    if (!hay.contains(term)) {
                        match = false;
                        break;
                    }
                    if (!title.contains(term)) inTitle = false;
                }
                if (match) (inTitle ? titleHits : otherHits).add(f);
            }
            titleHits.addAll(otherHits);
            adapter.set(titleHits);
            home.setVisibility(View.GONE);
            results.setVisibility(titleHits.isEmpty() ? View.GONE : View.VISIBLE);
            none.setVisibility(titleHits.isEmpty() ? View.VISIBLE : View.GONE);
        }));
        return outer;
    }

    /** Lower case without spaces, punctuation or accents, so "wifi" finds "Wi-Fi" and "aplicacion" "aplicación". */
    private static String squash(String text) {
        return java.text.Normalizer.normalize(text.toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("[^\\p{L}\\p{N}]+|\\p{M}+", "");
    }

    /** One searchable row: where it lives and the row itself. */
    private static final class Feature {
        final String title;
        final String summary;
        final int tab;
        final View row;
        final boolean opens;

        Feature(String title, String summary, int tab, View row, boolean opens) {
            this.title = title;
            this.summary = summary;
            this.tab = tab;
            this.row = row;
            this.opens = opens;
        }
    }

    /** Goes to a search result: opens its page, or scrolls its tab to it and lights it up. */
    private void reveal(Feature f) {
        selectTab(f.tab);
        if (f.opens) {
            f.row.performClick();
            return;
        }
        View scroll = tabRoots[f.tab];
        if (f.tab == 0 && scroll instanceof ViewGroup) scroll = ((ViewGroup) scroll).getChildAt(1);
        while (scroll instanceof ViewGroup && !(scroll instanceof ScrollView)) scroll = ((ViewGroup) scroll).getChildAt(0);
        ScrollView sv = scroll instanceof ScrollView ? (ScrollView) scroll : null;
        f.row.post(() -> {
            if (sv != null) sv.smoothScrollTo(0, Math.max(0, f.row.getTop() - dp(64)));
            Drawable old = f.row.getBackground();
            f.row.setBackgroundColor(lightTheme ? 0x262962FF : 0x408C9EFF);
            main.postDelayed(() -> f.row.setBackground(old), 1400);
        });
    }

    private void hideKeyboard(View view) {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
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
                .setTitle(Tr.t("Add the " + label + " tile"))
                .setMessage(Tr.t("Open System > Edit quick panel in TinyKit and add \"" + label + "\", or:\n\n"
                        + "1. Pull down the quick panel.\n2. Tap the edit (pencil) button.\n"
                        + "3. Drag \"" + label + "\" into your tiles.\n\n" + about))
                .setPositiveButton(Tr.t("OK"), null)
                .show();
    }

    private void showInstall() {
        new AlertDialog.Builder(this)
                .setTitle(Tr.t("Install"))
                .setItems(Tr.all(new String[]{
                        "Shizuku (thedjchi's fork, recommended): its Watchdog restarts Shizuku if Android kills it",
                        "Original Shizuku"}), (d, which) -> openUrl(new String[]{
                        SHIZUKU_DOWNLOAD, SHIZUKU_ORIGINAL}[which]))
                .setNegativeButton(Tr.t("Cancel"), null)
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
        shizukuNote(list);
        section(list, "Quick toggles");
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
        // Each call starts a Java process (about a second), so right after a change its result is trusted.
        long[] sensorsSet = {0};
        boolean[] sensorsNow = {false};
        privacySwitch(list, "All sensors off",
                "Blocks every sensor for all apps — motion, microphone and camera — like the hidden "
                        + "developer \"Sensors off\" tile. Resets on reboot.",
                () -> android.os.SystemClock.elapsedRealtime() - sensorsSet[0] < 3000 ? Boolean.valueOf(sensorsNow[0])
                        : "true".equals(Shell.run(sensorsCmd(this, "state")).out.trim()),
                block -> {
                    String failed = error(Shell.run(sensorsCmd(this, block ? "on" : "off")));
                    if (failed == null) {
                        sensorsNow[0] = block;
                        sensorsSet[0] = android.os.SystemClock.elapsedRealtime();
                    }
                    return failed;
                });

        section(list, "Developer options");
        toggle(list, "Hide developer options",
                "Every app sees Developer options as off. adb and Shizuku keep working.",
                s -> "0".equals(s.get("development_settings_enabled")),
                on -> "settings put global development_settings_enabled " + (on ? 0 : 1));
        // Needs Shizuku only once, to grant itself access, so the row stays usable without it.
        row(list, "Shut Up",
                "Only the apps you choose see developer options, USB debugging and Wireless debugging as off.",
                arrow(), this::openShutUp, false);

        section(list, "Network");
        link(list, "Block internet per app",
                Build.VERSION.SDK_INT >= 34
                        ? "Cut chosen apps off Wi-Fi and mobile data."
                        : "Needs Android 14 or newer.", this::openFirewall);
        link(list, "Saved Wi-Fi networks",
                "See saved passwords, share any network with a QR code, and export or import them.", this::openWifi);

        section(list, "Tracking");
        link(list, "Analytics & telemetry",
                "Turn off Huawei and Google analytics, ad tracking and HiCloud.", this::openAnalytics);
        link(list, "Trackers in apps", "See which ad and analytics libraries each app carries.",
                this::openTrackers);
        link(list, "Recent access",
                "Who used the camera, mic, location, contacts or clipboard lately.", this::openRecentAccess);
        link(list, "Clipboard guard",
                "Pick apps that can't read what you copy, like passwords and codes.", this::openClipboardGuard);

        return scroll(list);
    }

    private View buildApps() {
        LinearLayout list = vertical();
        shizukuNote(list);
        section(list, "Permissions");
        link(list, "App permissions",
                "Set any app's permissions to Real, Fake (looks granted, gets nothing) or Off.",
                () -> openAppList("App permissions", "Pick an app to change its permissions and app-ops.",
                        this::openAppPage));
        link(list, "Special access", "Install unknown apps, draw over apps, all files access and more, "
                + "per app.", this::openSpecialAccess);
        link(list, "Permissions by type", "Pick a permission, like location or camera, and see every app "
                + "that asks for it and which ones have it.", this::openPermissionTypes);
        action(list, "Repair permissions",
                "If the camera or microphone stopped working (\"Can't connect to the camera\", mic only works in "
                        + "calls) or an app you never changed broke, this fixes it.", () -> {
                    if (!Shell.ready()) return "Start Shizuku and allow TinyKit first.";
                    String message = FakePolicy.enforce(this, prefs);
                    String camera = CameraFix.run(this, prefs);
                    if (camera != null) message = message == null || message.isEmpty() ? camera : message + " " + camera;
                    return message == null || message.isEmpty()
                            ? "Nothing needed repair. Everything TinyKit checks is normal." : message;
                });
        link(list, "Reset permission changes",
                "Undo every Real, Fake and Off choice TinyKit made and fix any problems it caused.",
                this::confirmResetPermissions);

        section(list, "Manage");
        link(list, "Work profile", "A separate space for apps, with their own data and accounts. Freeze them "
                + "when you don't need them.", this::openWork);
        if (Mixer.supported()) link(list, "Audio mixer", "Turn one app's volume down so another stands out. "
                + "Works for apps in the work profile too (Android 13+).", this::openMixer);
        if (ShareTo.supported()) row(list, "Share menu pins", "Pin the apps you share to most. They show "
                + "in the top row of the share menu, so you don't scroll to find them (Android 10+).", arrow(),
                this::openSharePins, false);
        link(list, "Default apps", "Set the default launcher, browser, phone and more.", this::openDefaults);
        link(list, "Hide apps", "Hide apps from the launcher by disabling them.", this::openHide);
        link(list, "Unused apps", "Apps you haven't opened in a long time.", this::openUnused);
        link(list, "Storage by app", "Which apps take the most space.", this::openStorage);
        link(list, "App language", "Give any app its own language (Android 13+).", this::openAppLanguage);
        link(list, "Notification channels", "Silence one kind of notification from an app, not all of them "
                + "(Android 8+).", this::openChannelsApp);
        link(list, "Mixed audio",
                "Let apps play audio together, or mute an app.", this::openMixedAudio);

        section(list, "Running & background");
        link(list, "Running apps", "Memory use of everything running. Stop any app.", this::openRunning);
        link(list, "Stop background activity",
                "Pick apps that can't run or wake the phone in the background.", this::openBackground);
        link(list, "Keep apps alive",
                "Pick apps Android must never kill or delay, like Termux or messengers.",
                this::openKeepAlive);

        section(list, "Install & remove");
        link(list, "Install APK files",
                "Install .apk, .apks, .xapk and .apkm files, including apps split into several parts.",
                () -> pickApks(-1));
        View[] installOptions = new View[1];
        installOptions[0] = row(list, "Install options", installOptionsSummary(), arrow(),
                () -> chooseInstallOptions((TextView) installOptions[0].getTag()), false);
        row(list, "Installed with TinyKit", "Apps the installer put on, newest first, with a remove button.",
                arrow(), this::openInstallHistory, false);
        link(list, "Back up app as APK", "Save any app's APK to Download/TinyKit.", this::openBackup);
        link(list, "Debloater", "Remove apps your phone came with, with a safety rating for each. "
                + "Bring any of them back later.", this::openDebloat);

        return scroll(list);
    }

    private View buildSystem() {
        LinearLayout list = vertical();
        shizukuNote(list);
        section(list, "Display");
        privacySwitch(list, "Force dark on all apps",
                "Dark-themes apps that have no dark mode (Android's force-dark). Takes effect as each "
                        + "app is re-opened. Android forgets it at a restart; TinyKit puts it back once Shizuku is running.",
                () -> "true".equals(Shell.run("getprop debug.hwui.force_dark").out.trim()),
                block -> forceDark(block, Shell.run(Restore.forceDarkCommand(block))));
        privacySwitch(list, "Disable HW overlays",
                "Draws everything with the GPU instead of the display's overlay layers (the same as the "
                        + "developer option). Android forgets it at a restart; TinyKit puts it back once Shizuku is running.",
                () -> prefs.getBoolean(Restore.PREF_NO_OVERLAYS, false),
                block -> noOverlays(block, Shell.run(Restore.overlaysCommand(block))));
        View[] anim = new View[1];
        anim[0] = row(list, "Animation speed", "…", arrow(), () -> chooseAnimationSpeed(anim[0]), true);
        refreshAnimationSpeed(anim[0]);

        section(list, "Quick panel");
        link(list, "Edit quick panel", "Reorder, add and remove tiles with one tap each.", this::openQuickPanel);
        row(list, "TinyKit tiles", "Add any of TinyKit's own Quick Settings tiles.", arrow(),
                this::openTiles, false);

        section(list, "Clean & speed");
        action(list, "Speed up phone", "Deletes every app's cache, closes cached apps, frees and compacts memory "
                + "and trims storage. Apps you're using stay open.", Ram.needsShizuku(),
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

        section(list, "Tools");
        link(list, "Run a command", "Run a shell command as the shell user through Shizuku.",
                this::openRunCommand);
        link(list, "Recover deleted files", "Find files in every recycle bin, with thumbnails, and restore them.",
                this::openRecover);
        link(list, "Settings backup & restore", "Save all system settings to a file and put them back.",
                this::openBackupRestore);
        link(list, "Advanced reboot", "Reboot, recovery, bootloader or a soft (userspace) reboot.",
                this::openReboot);

        section(list, "Undo");
        link(list, "Reset everything TinyKit changed",
                "Undo TinyKit's changes and go back to Android's defaults. Saves a backup first.",
                this::confirmResetEverything);

        return scroll(list);
    }

    private View buildInfo() {
        LinearLayout list = vertical();
        shizukuNote(list);
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

        section(list, "TinyKit");
        Switch lightThemeSwitch = new Switch(this);
        lightThemeSwitch.setChecked(lightTheme);
        lightThemeSwitch.setClickable(false);
        lightThemeSwitch.setFocusable(false);
        row(list, "Light theme", "Use a light background instead of OLED black.", lightThemeSwitch, () -> {
            boolean on = !lightThemeSwitch.isChecked();
            lightThemeSwitch.setChecked(on);
            prefs.edit().putBoolean("light_theme", on).apply();
            recreate();
        }, false);
        // The one switch for everything TinyKit may keep running. Off: it never runs by itself.
        Switch backgroundSwitch = new Switch(this);
        backgroundSwitch.setClickable(false);
        backgroundSwitch.setFocusable(false);
        TextView backgroundStatus = text("", 13, false);
        backgroundStatus.setPadding(dp(16), dp(0), dp(16), dp(8));
        Runnable showBackground = () -> {
            boolean on = BackgroundService.on(prefs);
            backgroundSwitch.setChecked(on);
            List<String> jobs = new ArrayList<>();
            if (BackgroundService.shutUpRunning()) jobs.add(Tr.t("Shut Up"));
            if (BackgroundService.installsRunning()) jobs.add(Tr.t("new apps"));
            if (BackgroundService.workRunning()) jobs.add(Tr.t("work profile"));
            backgroundStatus.setText(!jobs.isEmpty() ? Tr.t("Running now for: ") + TextUtils.join(", ", jobs) + "."
                    : Tr.t(!on ? "Off. TinyKit never runs by itself."
                    : BackgroundService.needed(this, prefs) ? "Starting…"
                    : "On. Nothing needs it right now, so nothing is running."));
        };
        showBackground.run();
        row(list, "Run in the background", "Lets TinyKit keep a small service running, with a quiet "
                + "notification, for the tools that need it: Shut Up, blocking a new app's internet the moment "
                + "it is installed, and freezing work apps when the screen is off. It only runs while one of them "
                + "is in use, and uses almost no battery or memory. Off: TinyKit never runs by itself.", backgroundSwitch, () -> {
                    boolean on = !BackgroundService.on(prefs);
                    backgroundSwitch.setChecked(on);
                    worker.execute(() -> {
                        BackgroundService.setEnabled(this, prefs, on);
                        afterServiceChange(showBackground);
                    });
                }, false);
        list.addView(backgroundStatus);
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
        row(list, "What's new", "The changes in version " + versionName(getPackageName()) + ".", arrow(), () -> {
            String notes = releaseNotes(versionCode() - 1);
            showNotes(notes.isEmpty() ? "No release notes for this version." : notes, null);
        }, false);
        row(list, "Report a problem", "Something not working? Opens a ready-made GitHub issue that you read and "
                + "post yourself.", arrow(), this::reportProblem, false);
        Switch crashSwitch = new Switch(this);
        crashSwitch.setChecked(prefs.getBoolean(CrashReport.PREF, true));
        crashSwitch.setClickable(false);
        crashSwitch.setFocusable(false);
        row(list, "Offer to report crashes", "If TinyKit crashes, offer to send the details next time. Nothing is "
                + "sent unless you tap Send, and you post the issue yourself.", crashSwitch, () -> {
                    boolean on = !crashSwitch.isChecked();
                    crashSwitch.setChecked(on);
                    prefs.edit().putBoolean(CrashReport.PREF, on).apply();
                    if (!on) CrashReport.clear(this);
                }, false);
        row(list, "About", "Made by Ali Alakbar", arrow(), this::showAbout, false);
        View page = scroll(list);
        // The service can start or stop from other pages, so the status is read again each time.
        page.setTag(showBackground);
        return page;
    }

    /**
     * Call on the worker after changing a background choice: waits a moment for the service to start
     * or stop, then runs {@code then} on the main thread.
     */
    private void afterServiceChange(Runnable then) {
        boolean want = BackgroundService.needed(this, prefs);
        for (int i = 0; i < 30 && BackgroundService.isRunning() != want; i++) SystemClock.sleep(100);
        SystemClock.sleep(200); // the service sorts out its jobs just after it starts
        main.post(then);
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

    /** Remembers Disable HW overlays so {@link Restore} can switch it back on after a restart. */
    private String noOverlays(boolean on, Shell.Result r) {
        if (r.ok()) prefs.edit().putBoolean(Restore.PREF_NO_OVERLAYS, on).apply();
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

    private void openSharePins() {
        openPicker("Share menu pins",
                "The first three apps you check show in the top row of the share menu. Up to "
                        + ShareTo.MAX + ": the rest are in the Pinned apps entry. Tapping one hands what you share "
                        + "straight to that app.", true,
                () -> new HashSet<>(ShareTo.pins(prefs)),
                (pkg, on) -> ShareTo.choose(this, prefs, pkg, on),
                () -> {
                    Set<String> found = new HashSet<>();
                    for (String type : new String[]{"text/plain", "image/png", "video/mp4", "application/pdf",
                            "audio/mpeg", "*/*"}) {
                        Intent send = new Intent(Intent.ACTION_SEND).setType(type);
                        for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(send, 0)) {
                            found.add(r.activityInfo.packageName);
                        }
                    }
                    return found;
                });
    }

    // ---- Audio mixer ------------------------------------------------------------------------

    /** One app of the mixer, by uid: a work profile's copy of an app has its own uid. */
    private static final class Voice {
        int uid;
        String pkg;
        String label;
        boolean work;
    }

    private final java.util.concurrent.atomic.AtomicBoolean mixerQueued = new java.util.concurrent.atomic.AtomicBoolean();

    /** Sends the volumes to the helper; changes made while it is busy ride along with the next send. */
    private void mixerApply() {
        if (mixerQueued.getAndSet(true)) return;
        worker.execute(() -> {
            mixerQueued.set(false);
            Mixer.apply(this, prefs);
        });
    }

    /** The app behind a uid. A work profile's apps are copies of the main profile's, with the same app id. */
    private Voice voice(int uid) {
        Voice v = new Voice();
        v.uid = uid;
        int owner = Work.parent();
        v.work = uid / 100000 != owner;
        v.label = Tr.t("App") + " " + uid;
        PackageManager pm = getPackageManager();
        String[] names = pm.getPackagesForUid(uid % 100000 + owner * 100000);
        if (names != null && names.length > 0) {
            v.pkg = names[0];
            try {
                v.label = String.valueOf(pm.getApplicationInfo(v.pkg, 0).loadLabel(pm));
            } catch (PackageManager.NameNotFoundException e) {
                v.label = v.pkg;
            }
        }
        return v;
    }

    private static final String NOTE_SCALE = "Android caps an app at 100%. To make one app louder, raise this and turn the others down.";
    private static final String NOTE_MUTE = "This Android can only mute an app, not turn it down. Android 13 and newer can do both.";

    private static String percentText(int percent, boolean muteOnly) {
        return percent == 0 ? Tr.t("Muted") : muteOnly ? Tr.t("Sound on") : percent + "%";
    }

    private SeekBar slider(int max, int progress) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(progress);
        android.content.res.ColorStateList accent = android.content.res.ColorStateList.valueOf(
                attrColor(android.R.attr.colorAccent));
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        return bar;
    }

    private interface Changed {
        void to(int progress, boolean fromUser);
    }

    private static void onSlide(SeekBar bar, boolean[] dragging, Changed changed) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                changed.to(progress, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
                dragging[0] = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
                dragging[0] = false;
            }
        });
    }

    /** One app's line in the mixer: icon, name, what it is doing, its percent, and the slider. */
    private final class MixRow {
        final LinearLayout view = vertical();
        final ImageView icon = new ImageView(MainActivity.this);
        final TextView name = text("", 16, false);
        final TextView sub = text("", 13, false);
        final TextView value = text("", 14, true);
        final SeekBar bar = slider(20, 20);
        Voice voice;
        final boolean[] mute; // this Android can only mute an app: the slider has two stops

        MixRow(Map<Integer, Integer> volumes, boolean[] dragging, boolean[] mute) {
            this.mute = mute;
            view.setPadding(dp(16), dp(10), dp(16), dp(2));
            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(40), dp(40));
            size.setMargins(0, 0, dp(14), 0);
            top.addView(icon, size);
            LinearLayout texts = vertical();
            texts.addView(name);
            sub.setAlpha(0.7f);
            texts.addView(sub);
            top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            top.addView(value);
            view.addView(top);
            view.addView(bar);
            view.setTag(this);
            onSlide(bar, dragging, (progress, fromUser) -> {
                int percent = mute[0] ? progress * 100 : progress * 5;
                value.setText(percentText(percent, mute[0]));
                if (!fromUser || voice == null) return;
                if (percent == 100) volumes.remove(voice.uid);
                else volumes.put(voice.uid, percent);
                Mixer.save(prefs, volumes);
                if (mute[0]) {
                    Voice v = voice;
                    worker.execute(() -> {
                        if (v.pkg != null && !Mixer.mute(v.pkg, v.uid / 100000, percent == 0)) {
                            main.post(() -> toast(Tr.t("Couldn't change that app's sound.")));
                        }
                    });
                } else {
                    mixerApply();
                }
            });
        }

        void bind(Voice v, String state, int percent) {
            voice = v;
            name.setText(v.label);
            sub.setText(state);
            icon.setTag(null);
            if (v.pkg != null) showIcon(icon, v.pkg);
            else icon.setImageResource(android.R.drawable.sym_def_app_icon);
            value.setText(percentText(percent, mute[0]));
            bar.setMax(mute[0] ? 1 : 20);
            bar.setProgress(mute[0] ? (percent == 0 ? 0 : 1) : percent / 5);
        }
    }

    /** The audio mixer: the apps open now on top, every app below, a volume slider for each. */
    private void openMixer() {
        final Map<Integer, Integer> volumes = new java.util.TreeMap<>();
        final Set<Integer> playing = new HashSet<>();
        final List<Integer> open = new ArrayList<>();
        final String[] shown = {null}; // what the "open now" part was drawn from
        final boolean[] dragging = {false};
        // Before Android 13 (unless Shizuku is root) an app can only be muted, so the sliders have two stops.
        final boolean[] mute = {Build.VERSION.SDK_INT < 33};
        final int[] gen = {0};
        final boolean[] polling = {false};
        final boolean[] wasAlive = {false};
        final List<Voice> all = new ArrayList<>();
        final List<Voice> filtered = new ArrayList<>();
        final Set<Integer> launchable = new HashSet<>();

        ListView page = new ListView(this);
        page.setDivider(null);
        page.setHeaderDividersEnabled(false);
        LinearLayout top = vertical();
        LinearLayout upper = vertical(); // everything above the list of all apps, hidden while searching
        top.addView(upper);

        TextView problem = text("", 13, false);
        problem.setTextColor(attrColor(android.R.attr.colorAccent));
        problem.setPadding(dp(16), dp(12), dp(16), dp(12));
        problem.setBackground(roundRect(lightTheme ? 0xFFFFFFFF : 0xFF121212, 16));
        problem.setVisibility(View.GONE);
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2);
        margin.setMargins(dp(12), dp(8), dp(12), 0);
        upper.addView(problem, margin);

        section(upper, "Phone volume");
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        LinearLayout master = vertical();
        master.setPadding(dp(16), dp(4), dp(16), dp(4));
        LinearLayout masterTop = new LinearLayout(this);
        masterTop.setGravity(Gravity.CENTER_VERTICAL);
        masterTop.addView(text("Media volume", 16, false), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView masterValue = text("", 14, true);
        masterTop.addView(masterValue);
        master.addView(masterTop);
        int steps = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        SeekBar masterBar = slider(steps, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        master.addView(masterBar);
        upper.addView(master);
        final boolean[] masterDragging = {false};
        onSlide(masterBar, masterDragging, (progress, fromUser) -> {
            masterValue.setText(progress * 100 / steps + "%");
            if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
        });
        masterValue.setText(masterBar.getProgress() * 100 / steps + "%");
        TextView mixNote = note(mute[0] ? NOTE_MUTE : NOTE_SCALE);
        upper.addView(mixNote);

        section(upper, "Open now");
        LinearLayout openBox = vertical();
        upper.addView(openBox);
        TextView empty = note("No app is open. Open one, or start a video or a song, and it shows up here.");
        upper.addView(empty);

        Button reset = button("Reset all volumes", v -> {
            volumes.clear();
            Mixer.save(prefs, volumes);
            mixerApply();
            shown[0] = null;
            ((Runnable) page.getTag()).run();
        });
        reset.setPadding(dp(24), 0, dp(24), 0);
        LinearLayout.LayoutParams resetMargin = new LinearLayout.LayoutParams(-2, -2);
        resetMargin.setMargins(dp(16), dp(8), dp(16), dp(4));
        upper.addView(reset, resetMargin);
        section(top, "All apps");
        EditText search = searchField("Search apps");
        LinearLayout.LayoutParams searchMargin = fieldMargin();
        searchMargin.setMargins(dp(12), dp(4), dp(12), dp(4));
        top.addView(search, searchMargin);
        page.addHeaderView(top, null, false);

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return filtered.size();
            }

            @Override
            public Object getItem(int position) {
                return filtered.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                MixRow row = convert == null ? new MixRow(volumes, dragging, mute) : (MixRow) convert.getTag();
                Voice v = filtered.get(position);
                row.bind(v, state(v.uid, v.work, v.pkg, playing, open), volumes.getOrDefault(v.uid, 100));
                return row.view;
            }
        };
        page.setAdapter(adapter);

        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            filtered.clear();
            for (Voice v : all) {
                if (v.label.toLowerCase(Locale.ROOT).contains(q) || (v.pkg != null && v.pkg.toLowerCase(Locale.ROOT).contains(q))) {
                    filtered.add(v);
                }
            }
            adapter.notifyDataSetChanged();
        };
        // Searching hides the open apps above, so the matches sit right under the box, not under the keyboard.
        search.addTextChangedListener(watcher(() -> {
            upper.setVisibility(search.length() > 0 ? View.GONE : View.VISIBLE);
            filter.run();
        }));

        // The "open now" part: playing apps first, then the rest of the open ones, most recent first.
        Runnable drawOpen = () -> {
            openBox.removeAllViews();
            List<Integer> ids = new ArrayList<>(playing);
            for (int uid : open) if (!ids.contains(uid) && launchable.contains(uid) && uid % 100000 >= 10000) ids.add(uid);
            for (int uid : ids) {
                MixRow row = new MixRow(volumes, dragging, mute);
                Voice v = voice(uid);
                row.bind(v, state(uid, v.work, null, playing, open), volumes.getOrDefault(uid, 100));
                openBox.addView(row.view);
            }
            empty.setVisibility(ids.isEmpty() ? View.VISIBLE : View.GONE);
            reset.setVisibility(volumes.isEmpty() ? View.GONE : View.VISIBLE);
            shown[0] = playing + "|" + open;
        };

        class Loop implements Runnable {
            final int g;

            Loop(int g) {
                this.g = g;
            }

            @Override
            public void run() {
                if (g != gen[0] || !alive() || !page.isAttachedToWindow()) return;
                main.postDelayed(this, 1500);
                if (page.getWindowVisibility() != View.VISIBLE) return;
                if (!masterDragging[0]) {
                    masterBar.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
                }
                if (polling[0] || !Shell.ready()) return;
                polling[0] = true;
                reads.execute(() -> {
                    Mixer.State state = Mixer.poll(MainActivity.this);
                    // It died (Shizuku stopped, say): start it again once, so the volumes hold.
                    String error = !state.alive && wasAlive[0] && !mute[0] ? Mixer.apply(MainActivity.this, prefs) : null;
                    main.post(() -> {
                        polling[0] = false;
                        if (g != gen[0]) return;
                        wasAlive[0] = state.alive;
                        if (error != null) {
                            problem.setText(Tr.t(error));
                            problem.setVisibility(View.VISIBLE);
                        }
                        if ((state.alive || mute[0]) && !dragging[0] && !(state.playing + "|" + state.open).equals(shown[0])) {
                            playing.clear();
                            playing.addAll(state.playing);
                            open.clear();
                            open.addAll(state.open);
                            drawOpen.run();
                            adapter.notifyDataSetChanged(); // "Playing now" and "Open" in the list below
                        }
                    });
                });
            }
        }

        // Runs whenever the page comes into view: reads what is saved, starts the helper, and watches it.
        page.setTag((Runnable) () -> {
            int g = ++gen[0];
            volumes.clear();
            volumes.putAll(Mixer.rules(prefs));
            playing.clear();
            open.clear();
            drawOpen.run();
            adapter.notifyDataSetChanged();
            worker.execute(() -> {
                List<Voice> found = mixerApps();
                // Anything lowered earlier stays reachable, even an app that has no launcher icon any more.
                Set<Integer> have = new HashSet<>();
                for (Voice v : found) have.add(v.uid);
                for (int uid : volumes.keySet()) if (have.add(uid)) found.add(voice(uid));
                Collections.sort(found, (x, y) -> volumes.containsKey(x.uid) != volumes.containsKey(y.uid)
                        ? (volumes.containsKey(x.uid) ? -1 : 1) : x.label.compareToIgnoreCase(y.label) != 0
                        ? x.label.compareToIgnoreCase(y.label) : Boolean.compare(x.work, y.work));
                main.post(() -> {
                    if (g != gen[0]) return;
                    all.clear();
                    all.addAll(found);
                    launchable.clear();
                    for (Voice v : found) launchable.add(v.uid);
                    filter.run();
                });
            });
            if (!Shell.ready()) {
                problem.setText(Tr.t("Start Shizuku first. It is what lets TinyKit change one app's volume."));
                problem.setVisibility(View.VISIBLE);
                return;
            }
            problem.setVisibility(View.GONE);
            worker.execute(() -> {
                boolean scale = Mixer.scaling(this);
                String error = Mixer.apply(this, prefs);
                main.post(() -> {
                    if (g != gen[0]) return;
                    if (mute[0] == scale) { // the first guess was wrong: Shizuku can turn apps down here
                        mute[0] = !scale;
                        mixNote.setText(Tr.t(mute[0] ? NOTE_MUTE : NOTE_SCALE));
                        drawOpen.run();
                        adapter.notifyDataSetChanged();
                    }
                    wasAlive[0] = error == null;
                    if (error != null) {
                        problem.setText(Tr.t(error));
                        problem.setVisibility(View.VISIBLE);
                    } else {
                        new Loop(g).run();
                    }
                });
            });
        });
        push("Audio mixer", page);
    }

    private static String state(int uid, boolean work, String pkg, Set<Integer> playing, List<Integer> open) {
        String s = (work ? Tr.t("Work profile") : "");
        String what = playing.contains(uid) ? Tr.t("Playing now") : open.contains(uid) ? Tr.t("Open") : "";
        if (!what.isEmpty()) s += (s.isEmpty() ? "" : " · ") + what;
        return s.isEmpty() && pkg != null ? pkg : s;
    }

    /** Every app with a launcher icon, in this profile and in the work profile. */
    private List<Voice> mixerApps() {
        PackageManager pm = getPackageManager();
        List<Voice> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
            ApplicationInfo info = r.activityInfo.applicationInfo;
            if (info.packageName.equals(getPackageName()) || !seen.add(info.packageName)) continue;
            Voice v = new Voice();
            v.uid = info.uid;
            v.pkg = info.packageName;
            v.label = String.valueOf(info.loadLabel(pm));
            out.add(v);
        }
        int work = Work.id(this);
        if (work >= 0) {
            for (Work.App a : Work.apps(this, work)) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(a.pkg, 0);
                    Voice v = new Voice();
                    v.uid = work * 100000 + info.uid % 100000;
                    v.pkg = a.pkg;
                    v.label = String.valueOf(info.loadLabel(pm));
                    v.work = true;
                    out.add(v);
                } catch (PackageManager.NameNotFoundException e) {
                    // only in the work profile: no label or icon to show
                }
            }
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label) != 0
                ? a.label.compareToIgnoreCase(b.label) : Boolean.compare(a.work, b.work));
        return out;
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

    // ---- Shut Up ----------------------------------------------------------------------------------

    private void openShutUp() {
        LinearLayout list = vertical();
        list.addView(note("Some apps refuse to work, or nag you, when Developer options or USB or Wireless "
                + "debugging are on. Choose those apps here. While one of them is on screen, TinyKit turns the "
                + "three settings off, and it turns back on whatever was on the moment you leave the app. "
                + "Android can't hide a setting from one app only, so every app sees it off during that time. "
                + "An app that checks in its first moments, before TinyKit notices it, or that remembers an "
                + "earlier check, can still see it."));
        Switch onSwitch = new Switch(this);
        onSwitch.setClickable(false);
        onSwitch.setFocusable(false);
        TextView status = text("", 13, false);
        status.setPadding(dp(16), dp(0), dp(16), dp(8));
        View[] choose = new View[1];
        Runnable refresh = () -> {
            boolean on = prefs.getBoolean(ShutUp.PREF_ON, false);
            boolean background = BackgroundService.on(prefs);
            onSwitch.setChecked(on);
            int n = ShutUp.apps(prefs).size();
            ((TextView) choose[0].getTag()).setText(Tr.t(n == 0 ? "No app chosen yet."
                    : n + (n == 1 ? " app chosen." : " apps chosen.")));
            boolean paused = on && !background;
            status.setTextColor(paused ? attrColor(android.R.attr.colorAccent) : (lightTheme ? 0xFF000000 : 0xB3FFFFFF));
            status.setText(Tr.t(BackgroundService.shutUpRunning()
                    ? (ShutUp.hidden(prefs) ? "Running. Hidden right now, because a chosen app is open."
                    : "Running. Nothing is hidden until you open a chosen app.")
                    : paused ? "Paused, because Run in the background is off. Tap here to turn it back on."
                    : on ? (n == 0 ? "On. Choose at least one app above."
                    : !ShutUp.ready(this) ? "Waiting: start Shizuku once so TinyKit can give itself the access Shut Up needs."
                    : "Starting…")
                    : "Off. TinyKit changes nothing and runs nothing."));
        };
        status.setOnClickListener(v -> {
            if (!prefs.getBoolean(ShutUp.PREF_ON, false) || BackgroundService.on(prefs)) return;
            worker.execute(() -> {
                BackgroundService.setEnabled(this, prefs, true);
                afterServiceChange(refresh);
            });
        });
        choose[0] = row(list, "Choose apps", "", arrow(), () -> openPicker("Shut Up apps",
                "Checked apps see Developer options, USB debugging and Wireless debugging as off while they "
                        + "are open.", true,
                () -> ShutUp.apps(prefs),
                (pkg, on) -> ShutUp.choose(this, prefs, pkg, on)), false);
        row(list, "Shut Up", "Watches which app is open (a couple of times a second, only while the screen is "
                + "on) with a quiet notification. It uses very little battery. Turning it on also turns on Run "
                + "in the background (Info tab), which it needs.", onSwitch, () -> {
                    boolean on = !prefs.getBoolean(ShutUp.PREF_ON, false);
                    onSwitch.setChecked(on);
                    worker.execute(() -> {
                        String error = ShutUp.setEnabled(this, prefs, on);
                        afterServiceChange(() -> {
                            refresh.run();
                            if (error != null) toast(Tr.t(error));
                        });
                    });
                }, false);
        list.addView(status);
        Switch stopSwitch = new Switch(this);
        stopSwitch.setClickable(false);
        stopSwitch.setFocusable(false);
        TextView stopStatus = text("", 13, false);
        stopStatus.setPadding(dp(16), dp(0), dp(16), dp(8));
        Runnable showStop = () -> {
            boolean can = ShizukuControl.canRestart(this, prefs);
            stopSwitch.setChecked(prefs.getBoolean(ShutUp.PREF_STOP_SHIZUKU, true) && can);
            stopStatus.setText(Tr.t(can ? "TinyKit can start Shizuku again by itself."
                    : "Not available: TinyKit has no way to start Shizuku by itself. Save Shizuku's auth token "
                    + "(Shizuku tab, for a Shizuku fork with start and stop intents), or tap Start once so "
                    + "TinyKit pairs with Wireless debugging (Android 11+)."));
        };
        row(list, "Stop Shizuku while they're open", "Some apps notice Shizuku running. With this on, TinyKit "
                + "stops Shizuku while a chosen app is open and starts it again about 15 seconds after you "
                + "leave. Turning USB or Wireless debugging off can end Shizuku too, so TinyKit also starts it "
                + "again after that. It only does this when it can start Shizuku by itself.", stopSwitch, () -> {
                    if (!ShizukuControl.canRestart(this, prefs)) {
                        toast(Tr.t("TinyKit has no way to start Shizuku by itself yet. See the note below."));
                        showStop.run();
                        return;
                    }
                    prefs.edit().putBoolean(ShutUp.PREF_STOP_SHIZUKU,
                            !prefs.getBoolean(ShutUp.PREF_STOP_SHIZUKU, true)).apply();
                    showStop.run();
                }, false);
        list.addView(stopStatus);
        showStop.run();
        View page = scroll(list);
        page.setTag((Runnable) () -> {
            refresh.run();
            showStop.run();
        });
        push("Shut Up", page);
        refresh.run();
    }

    // ---- Block internet per app ---------------------------------------------------------------

    private void openFirewall() {
        if (!FirewallPolicy.supported()) {
            toast(Tr.t("Blocking an app's internet needs Android 14 or newer."));
            return;
        }
        LinearLayout list = vertical();
        TextView status = text("", 14, true);
        status.setPadding(dp(16), dp(12), dp(16), dp(4));
        list.addView(status);
        list.addView(note("Blocked apps can't use Wi-Fi or mobile data. Android itself enforces it, so "
                + "TinyKit runs nothing in the background unless you turn on \"Run in the background\" below, "
                + "and the block comes back by itself after a restart as soon as Shizuku is running again."));
        Switch modeSwitch = new Switch(this);
        modeSwitch.setClickable(false);
        modeSwitch.setFocusable(false);
        Switch pauseSwitch = new Switch(this);
        pauseSwitch.setClickable(false);
        pauseSwitch.setFocusable(false);
        Switch watchSwitch = new Switch(this);
        watchSwitch.setClickable(false);
        watchSwitch.setFocusable(false);
        TextView watchStatus = text("", 13, false);
        watchStatus.setPadding(dp(16), dp(0), dp(16), dp(8));
        View[] choose = new View[1];
        Runnable refresh = () -> {
            boolean allow = FirewallPolicy.allowMode(prefs);
            modeSwitch.setChecked(allow);
            boolean watch = BackgroundService.on(prefs);
            watchSwitch.setChecked(watch);
            pauseSwitch.setChecked(FirewallPolicy.paused(prefs));
            watchStatus.setText(Tr.t(BackgroundService.installsRunning()
                    ? "Running. New apps are blocked the moment they are installed."
                    : watch ? (FirewallPolicy.configured(prefs) ? "Starting…"
                    : "On. Nothing to watch until you block an app or turn on allow list mode.")
                    : "Off. Apps you install are blocked the next time you open TinyKit."));
            ((TextView) choose[0].getTag()).setText(Tr.t(allow
                    ? "Pick the apps that may use the internet. Everything else is blocked."
                    : "Pick the apps to cut off."));
            status.setText(Tr.t("…"));
            worker.execute(() -> {
                int n = FirewallPolicy.blockedCount(this, prefs);
                main.post(() -> status.setText(Tr.t(FirewallPolicy.paused(prefs)
                        ? "Paused. No app is blocked until you resume." : n == 0 ? "No app is blocked."
                        : n + (n == 1 ? " app is" : " apps are") + " blocked.")));
            });
        };
        choose[0] = row(list, "Choose apps", "", arrow(), this::openFirewallApps, true);
        row(list, "Allow list mode", "Block every app except the ones you allow. Apps you install later are "
                + "blocked when you next open TinyKit, or at once with \"Run in the background\" below. Allow Google Play "
                + "services if you want push notifications.", modeSwitch, () -> {
                    boolean on = !FirewallPolicy.allowMode(prefs);
                    if (!on) {
                        setFirewallMode(false, refresh);
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(Tr.t("Block everything except allowed apps?"))
                            .setMessage(Tr.t("Every app that isn't on your allow list loses Wi-Fi and mobile data "
                                    + "right away, including system apps like Google Play services. "
                                    + "Pick the apps to allow next."))
                            .setPositiveButton(Tr.t("Turn on"), (d, w) -> setFirewallMode(true, refresh))
                            .setNegativeButton(Tr.t("Cancel"), null)
                            .show();
                }, true);
        row(list, "Pause blocking", "Let every app use the internet for now and keep your lists. Also on the "
                + "Internet block quick tile.", pauseSwitch, () -> {
                    boolean on = !FirewallPolicy.paused(prefs);
                    prefs.edit().putBoolean(FirewallPolicy.PAUSED, on).apply();
                    worker.execute(() -> {
                        String error = FirewallPolicy.apply(this, prefs, true);
                        main.post(() -> {
                            refresh.run();
                            if (error != null) toast(Tr.t(error));
                        });
                    });
                }, true);
        row(list, "Run in the background", "Blocks a new app the moment you install it, with a quiet "
                + "notification. This is the same switch as in the Info tab, shared with Shut Up. It uses almost "
                + "no battery or memory.", watchSwitch, () -> {
                    boolean on = !BackgroundService.on(prefs);
                    watchSwitch.setChecked(on);
                    worker.execute(() -> {
                        BackgroundService.setEnabled(this, prefs, on);
                        afterServiceChange(refresh);
                    });
                }, true);
        list.addView(watchStatus);
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
                if (error != null) toast(Tr.t(error));
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
                        main.post(() -> toast(Tr.t("Also " + (on != allow ? "blocked: " : "allowed: ")
                                + TextUtils.join(", ", same))));
                    }
                    return null;
                }, null, true);
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
    private static final int[] LEVEL_COLORS_LIGHT = {0xFF1B5E20, 0xFFB25E00, 0xFFC2410C, 0xFFB71C1C};

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
            toast(Tr.t("The Debloater needs Android 8 or newer, so removed apps can be restored."));
            return;
        }
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Remove apps your phone came with, for you only. Nothing is deleted from the "
                + "system: bring any app back from Removed, and a factory reset restores all of them. "
                + "Hold an app to read about it.", 13, false);
        setDim(help, 0.8f);
        page.addView(help);
        EditText search = searchField("Search apps");
        page.addView(search, fieldMargin());
        CheckBox mine = new CheckBox(this);
        mine.setText(Tr.t("Also show apps I installed"));
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
            row.first.setText(Tr.t(p.label));
            row.second.setText(Tr.t(p.pkg));
            if (lightTheme) {
                String levelStr = Tr.t(Bloat.LEVELS[p.level]);
                String noteStr = Tr.t(p.note);
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
                sb.append(levelStr);
                sb.setSpan(new android.text.style.ForegroundColorSpan(LEVEL_COLORS_LIGHT[p.level]),
                        0, levelStr.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(" · ").append(noteStr);
                row.third.setTextColor(0xFF222222);
                row.third.setText(sb);
            } else {
                row.third.setText(Tr.t(Bloat.LEVELS[p.level]) + " · " + Tr.t(p.note));
                row.third.setTextColor(LEVEL_COLORS[p.level]);
            }
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
                if (p.label.toLowerCase(Locale.ROOT).contains(q) || p.pkg.toLowerCase(Locale.ROOT).contains(q)) shown.add(p);
            }
            Collections.sort(shown, (x, y) -> x.checked != y.checked ? (x.checked ? -1 : 1)
                    : x.label.compareToIgnoreCase(y.label));
            adapter.set(shown);
            go.setVisibility(picked > 0 ? View.VISIBLE : View.GONE);
            go.setText(Tr.t((removedView[0] ? "Restore " : "Remove ") + picked + (picked == 1 ? " app" : " apps")));
        };
        Runnable load = () -> {
            boolean wanted = removedView[0];
            all.clear();
            filter.run();
            loading.setText(Tr.t("Reading apps…"));
            loading.setVisibility(View.VISIBLE);
            worker.execute(() -> {
                List<Pkg> list = debloatList(wanted);
                main.post(() -> {
                    if (wanted != removedView[0]) return;
                    all.clear();
                    all.addAll(list);
                    loading.setText(Tr.t("No apps here."));
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
                toast(Tr.t(p.label + ": " + p.note + ". It can't be removed."));
                return;
            }
            p.checked = !p.checked;
            filter.run();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            Pkg p = adapter.getItem(position);
            new AlertDialog.Builder(this)
                    .setTitle(Tr.t(p.label))
                    .setMessage(Tr.t(Bloat.LEVELS[p.level]) + ": " + Tr.t(Bloat.MEANING[p.level]) + "\n\n" + Tr.t(p.note)
                            + "\n\n" + p.pkg + (versionName(p.pkg).isEmpty() ? "" : "\n" + Tr.t("Version ") + versionName(p.pkg)))
                    .setPositiveButton(Tr.t("OK"), null)
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
            List<String> yours = new ArrayList<>();
            for (Pkg p : picked) if (!p.system) yours.add(p.label);
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < picked.size() && i < 12; i++) names.append("• ").append(picked.get(i).label).append('\n');
            if (picked.size() > 12) names.append("and ").append(picked.size() - 12).append(" more\n");
            new AlertDialog.Builder(this)
                    .setTitle(Tr.t((restore ? "Restore " : "Remove ") + picked.size() + (picked.size() == 1 ? " app?" : " apps?")))
                    .setMessage(Tr.t(names + (restore
                            ? "\nThey come back as they were."
                            : (picked.size() == 1 ? "\nIt disappears" : "\nThey disappear")
                                    + " for you only and can be restored from Removed."
                                    + (yours.isEmpty() ? "" : "\n\nYou installed " + TextUtils.join(", ", yours)
                                    + " yourself: " + (yours.size() == 1 ? "it is" : "they are")
                                    + " uninstalled completely, with " + (yours.size() == 1 ? "its" : "their")
                                    + " data, and can't be restored here.")
                                    + (worst >= Bloat.EXPERT ? "\n\nSome of these are marked Expert: other "
                                    + "apps or features may depend on them." : ""))))
                    .setPositiveButton(Tr.t(restore ? "Restore" : "Remove"), (d, w) -> runDebloat(picked, restore, load))
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        load.run();
    }

    private void runDebloat(List<Pkg> picked, boolean restore, Runnable done) {
        TextView message = text((restore ? "Restoring " : "Removing ") + picked.size() + "…", 15, false);
        message.setPadding(dp(24), dp(16), dp(24), dp(8));
        AlertDialog progress = new AlertDialog.Builder(this).setTitle(Tr.t("Working"))
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
                if (!alive()) return;
                dismiss(progress);
                done.run();
                String summary = (restore ? "Restored " : "Removed ") + count + (count == 1 ? " app." : " apps.");
                if (failed.isEmpty() && count > 0) {
                    toast(Tr.t(summary));
                } else {
                    new AlertDialog.Builder(this).setTitle(Tr.t(summary))
                            .setMessage(Tr.t(failed.isEmpty() ? "Nothing changed." : TextUtils.join("\n", failed)))
                            .setPositiveButton(Tr.t("OK"), null).show();
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
            toast(Tr.t("A language for each app needs Android 13 or newer."));
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
                    .setTitle(Tr.t(label(pkg)))
                    .setItems(Tr.all(names), (d, which) -> worker.execute(() -> {
                        String tag = options.get(which)[0];
                        String error = error(Shell.run("cmd locale set-app-locales " + Shell.quote(pkg)
                                + " --user 0" + (tag.isEmpty() ? "" : " --locales " + Shell.quote(tag))));
                        main.post(() -> toast(Tr.t(error != null ? error : label(pkg) + ": " + options.get(which)[1])));
                    }))
                    .setNegativeButton(Tr.t("Cancel"), null)
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
        setDim(help, 0.7f);
        page.addView(help);
        TextView loading = text("Reading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        View view = page;
        Adapter<String[]> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(Tr.t(a[1]));
            row.second.setText(Tr.t(a[0]));
            row.third.setText(Tr.t(a[2]));
            showIcon(row, a[0]);
        });
        listView.setAdapter(adapter);
        Runnable load = () -> worker.execute(() -> {
            reloadApps();
            List<String[]> rows = installedByTinyKit();
            main.post(() -> {
                loading.setText(Tr.t("Nothing yet. Apps you install with TinyKit from now on show up here."));
                loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.set(rows);
            });
        });
        listView.setOnItemClickListener((parent, v, position, id) -> {
            String[] a = adapter.getItem(position);
            new AlertDialog.Builder(this)
                    .setTitle(Tr.t(a[1]))
                    .setMessage(Tr.t(a[2] + "\n\nRemoving it deletes the app and its data. TinyKit keeps no copy "
                            + "of the file, so an older version can't be put back."))
                    .setPositiveButton(Tr.t("Open"), (d, w) -> openApp(a[0]))
                    .setNegativeButton(Tr.t("Remove"), (d, w) -> new AlertDialog.Builder(this)
                            .setTitle(Tr.t("Remove " + a[1] + "?"))
                            .setMessage(Tr.t("The app and its data are deleted."))
                            .setPositiveButton(Tr.t("Remove"), (d2, w2) -> worker.execute(() -> {
                                String error = error(Shell.run("pm uninstall " + Shell.quote(a[0])));
                                main.post(() -> {
                                    toast(Tr.t(error != null ? error : "Removed."));
                                    load.run();
                                });
                            }))
                            .setNegativeButton(Tr.t("Cancel"), null)
                            .show())
                    .setNeutralButton(Tr.t("Close"), null)
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
            if ((options & INSTALL_OPTION_BITS[i]) != 0) on.append(on.length() > 0 ? ", " : "").append(Tr.t(short_[i]));
        }
        return on.length() == 0 ? Tr.t("Normal install. Tap to allow downgrades or grant permissions.")
                : Tr.t("On: ") + on + ".";
    }

    private void chooseInstallOptions(TextView summary) {
        boolean[] checked = new boolean[INSTALL_OPTION_BITS.length];
        int options = prefs.getInt(ApkInstaller.PREF, 0);
        for (int i = 0; i < checked.length; i++) checked[i] = (options & INSTALL_OPTION_BITS[i]) != 0;
        new AlertDialog.Builder(this)
                .setTitle(Tr.t("Install options"))
                .setMultiChoiceItems(Tr.all(INSTALL_OPTION_NAMES), checked, (d, which, on) -> checked[which] = on)
                .setPositiveButton(Tr.t("Save"), (d, w) -> {
                    int bits = 0;
                    for (int i = 0; i < checked.length; i++) if (checked[i]) bits |= INSTALL_OPTION_BITS[i];
                    prefs.edit().putInt(ApkInstaller.PREF, bits).apply();
                    if (summary != null) summary.setText(Tr.t(installOptionsSummary()));
                })
                .setNegativeButton(Tr.t("Cancel"), null)
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
        setDim(help, 0.8f);
        page.addView(help);
        CheckBox system = new CheckBox(this);
        system.setText(Tr.t("Show system apps"));
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
            row.first.setText(Tr.t(t[1]));
            row.second.setText(Tr.t(t[3] + (t[3].equals("1") ? " tracker" : " trackers")));
            row.third.setText(Tr.t(t[2]));
            showIcon(row, t[0]);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            List<String[]> shown = new ArrayList<>();
            for (String[] t : all) if (system.isChecked() || t[4].equals("0")) shown.add(t);
            adapter.set(shown);
            loading.setText(Tr.t("No trackers found."));
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
                    .setTitle(Tr.t(t[1]))
                    .setMessage(Tr.t(t[2] + "\n\nThese are declared inside " + t[0] + ". TinyKit can't switch them off "
                            + "one by one (Android doesn't allow it from the shell)."))
                    .setNegativeButton(Tr.t("Close"), null);
            if (FirewallPolicy.supported() && Shell.ready()) {
                d.setPositiveButton(Tr.t("Block internet"), (x, w) -> worker.execute(() -> {
                    String error = FirewallPolicy.block(this, prefs, t[0]);
                    main.post(() -> toast(Tr.t(error != null ? error : FirewallPolicy.paused(prefs)
                            ? t[1] + " will be blocked from the internet when you resume blocking."
                            : t[1] + " is blocked from the internet.")));
                }));
            }
            d.show();
        });
    }

    // ---- Notification channels ------------------------------------------------------------------

    private void openChannelsApp() {
        if (Build.VERSION.SDK_INT < 26) {
            toast(Tr.t("Notification channels need Android 8 or newer."));
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
        setDim(help, 0.8f);
        page.addView(help);
        TextView loading = text("Reading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push(label(pkg), page);

        // {id, name, importance}
        Adapter<String[]> adapter = new Adapter<>(true, (ch, row) -> {
            row.first.setText(Tr.t(ch[1]));
            row.second.setText(Tr.t(importanceName(Integer.parseInt(ch[2]))));
            row.check.setChecked(!ch[2].equals("0"));
        });
        listView.setAdapter(adapter);
        int uid;
        try {
            uid = getPackageManager().getApplicationInfo(pkg, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            loading.setText(Tr.t("This app isn't installed any more."));
            return;
        }
        boolean[] busy = {false};
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
                loading.setText(Tr.t(r.ok() ? "This app hasn't created any notification channels yet."
                        : "Couldn't read them: " + r.out));
                loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.set(rows);
                busy[0] = false;
            });
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            // One change at a time: the row shows the old state until the list is read again.
            if (busy[0]) return;
            busy[0] = true;
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
                    if (!r.ok()) toast(Tr.t(r.out));
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
        setDim(help, 0.7f);
        page.addView(help);
        TextView loading = text("Reading usage…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        Adapter<String[]> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(Tr.t(a[1]));
            row.second.setText(Tr.t(a[2]));
            showIcon(row, a[0]);
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, v, position, id) -> openAppInfo(adapter.getItem(position)[0]));
        final int[] range = {0};
        Runnable load = () -> {
            loading.setText(Tr.t("Reading usage…"));
            loading.setVisibility(View.VISIBLE);
            worker.execute(() -> {
                if (!Tweak.usageAllowed(this)) Tweak.grantSelf(this);
                if (!Tweak.usageAllowed(this)) {
                    main.post(() -> loading.setText(Tr.t("TinyKit needs usage access. Start Shizuku once and "
                            + "reopen this page, or allow it in Settings > Apps > Special access.")));
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
                    total.setText(Tr.t(sum[0] == 0 ? "" : "Total on screen: " + duration(sum[0])));
                    loading.setText(Tr.t("No app use recorded in this time."));
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

    /** The user the picked files go to: the work profile's id, or -1 for the usual install. */
    private int pickUser = -1;

    private void pickApks(int user) {
        pickUser = user;
        if (!Shell.ready()) {
            toast(Tr.t("Start Shizuku and allow TinyKit first."));
            return;
        }
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(pick, REQUEST_PICK_APK);
        } catch (ActivityNotFoundException e) {
            toast(Tr.t("No file picker found on this phone."));
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
        int user = pickUser;
        TextView message = text("Reading files…", 15, false);
        message.setPadding(dp(24), dp(16), dp(24), dp(8));
        AlertDialog progress = new AlertDialog.Builder(this).setTitle(Tr.t("Installing"))
                .setView(message).setCancelable(false).show();
        // Its own thread: an install can take a while and shouldn't hold up the other commands.
        new Thread(() -> {
            String result = ApkInstaller.install(this, uris, text -> main.post(() -> message.setText(Tr.t(text))),
                    user);
            main.post(() -> {
                if (!alive()) return;
                dismiss(progress);
                new AlertDialog.Builder(this).setTitle(Tr.t("Install")).setMessage(Tr.t(result))
                        .setPositiveButton(Tr.t("OK"), null).show();
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
                    main.post(() -> toast(Tr.t("Saved to " + where)));
                    return null;
                });
    }

    private void openPicker(String title, String help, boolean checkable, Loader loader, AppChange change) {
        openPicker(title, help, checkable, loader, change, null);
    }

    /** As above; {@code eligible}, when given, limits the list to the apps it returns. */
    private void openPicker(String title, String help, boolean checkable, Loader loader, AppChange change,
                            Loader eligible) {
        openPicker(title, help, checkable, loader, change, eligible, false);
    }

    /** As above; {@code internetFilter} adds the "Only apps with internet permission" checkbox. */
    private void openPicker(String title, String help, boolean checkable, Loader loader, AppChange change,
                            Loader eligible, boolean internetFilter) {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView helpView = text(help, 14, false);
        setDim(helpView, 0.8f);
        page.addView(helpView);
        EditText search = searchField("Search apps");
        page.addView(search, fieldMargin());
        CheckBox system = new CheckBox(this);
        system.setText(Tr.t("Show system apps"));
        page.addView(system);
        CheckBox net = null;
        final Set<String> withInternet = Collections.synchronizedSet(new HashSet<>());
        final boolean[] netReady = {false};
        if (internetFilter) {
            net = new CheckBox(this);
            net.setText(Tr.t("Only apps with internet permission"));
            net.setChecked(prefs.getBoolean(PREF_NET_ONLY, true));
            page.addView(net);
        }
        final CheckBox netBox = net;
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
            row.first.setText(Tr.t(app.label));
            row.second.setText(Tr.t(app.pkg));
            row.check.setChecked(app.checked);
            showIcon(row, app.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<App> shown = new ArrayList<>();
            for (App a : all) {
                if (limited[0] && !only.contains(a.pkg)) continue;
                // Apps already in the list stay visible so a saved choice never disappears.
                if (netBox != null && netBox.isChecked() && netReady[0] && !a.checked
                        && !withInternet.contains(a.pkg)) continue;
                if ((system.isChecked() || !a.system || a.checked)
                        && (a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.toLowerCase(Locale.ROOT).contains(q))) {
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
        if (netBox != null) {
            netBox.setOnCheckedChangeListener((v, checked) -> {
                prefs.edit().putBoolean(PREF_NET_ONLY, checked).apply();
                filter.run();
            });
            worker.execute(() -> {
                Set<String> found = internetApps();
                main.post(() -> {
                    withInternet.addAll(found);
                    netReady[0] = true;
                    filter.run();
                });
            });
        }
        listView.setOnItemClickListener((parent, view, position, id) -> {
            App app = adapter.getItem(position);
            boolean on = !app.checked;
            if (checkable) {
                app.checked = on;
                adapter.notifyDataSetChanged();
            } else {
                toast(Tr.t("Saving " + app.label + "…"));
            }
            worker.execute(() -> {
                String error = change.apply(app.pkg, on);
                main.post(() -> {
                    if (error != null) {
                        if (checkable) app.checked = !on;
                        adapter.notifyDataSetChanged();
                        toast(Tr.t(error));
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
            loading.setText(Tr.t("Reading current state…"));
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

    private static final String PREF_NET_ONLY = "fw_net_only";

    /**
     * Apps that can reach the network: those that ask for INTERNET, plus any app sharing a user id
     * with one (the firewall works per user id). Call off the main thread.
     */
    private Set<String> internetApps() {
        PackageManager pm = getPackageManager();
        Set<Integer> uids = new HashSet<>();
        for (android.content.pm.PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
            if (info.requestedPermissions == null || info.applicationInfo == null) continue;
            for (String r : info.requestedPermissions) {
                if ("android.permission.INTERNET".equals(r)) {
                    uids.add(info.applicationInfo.uid);
                    break;
                }
            }
        }
        Set<String> out = new HashSet<>();
        for (ApplicationInfo i : pm.getInstalledApplications(0)) if (uids.contains(i.uid)) out.add(i.packageName);
        return out;
    }

    /** Reloads the cached app list, e.g. after installing an app. Call off the main thread. */
    private void reloadApps() {
        // Built aside and swapped in, so lists opening meanwhile use the old one instead of waiting.
        List<App> fresh = loadApps();
        apps = fresh;
    }

    private boolean appsLoaded() {
        return apps != null;
    }

    /** The cached app list; loads it first if needed, so off the main thread unless {@link #appsLoaded}. */
    private List<App> installedApps() {
        List<App> list = apps;
        if (list != null) return list;
        synchronized (appsLock) {
            if (apps == null) apps = loadApps();
            return apps;
        }
    }

    private List<App> loadApps() {
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
        return list;
    }

    static Set<String> packages(String out) {
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

    private static final String PREF_STOP_ASK = "stop_ask";

    private void openRunning() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView summary = text("Loading…", 15, true);
        page.addView(summary);
        TextView help = text("Tap an app to stop it. Hold one to pick several. System processes can't be stopped.", 13, false);
        setDim(help, 0.7f);
        page.addView(help);
        CheckBox ask = new CheckBox(this);
        ask.setText(Tr.t("Ask before stopping"));
        ask.setChecked(prefs.getBoolean(PREF_STOP_ASK, true));
        ask.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(PREF_STOP_ASK, on).apply());
        page.addView(ask);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        Button stopSelected = primary(button("", null));
        stopSelected.setVisibility(View.GONE);
        LinearLayout.LayoutParams stopMargin = new LinearLayout.LayoutParams(-1, -2);
        stopMargin.setMargins(0, dp(8), 0, dp(8));
        page.addView(stopSelected, stopMargin);
        push("Running apps", page);

        final List<Proc> procs = new ArrayList<>();
        final Set<String> picked = new HashSet<>(); // packages chosen with a long press
        Adapter<Proc> adapter = new Adapter<>(true, (p, row) -> {
            row.first.setText(Tr.t(p.label));
            row.second.setText(Tr.t(Formatter.formatShortFileSize(this, p.kb * 1024)
                    + String.format(Locale.US, "  ·  %.1f%% CPU", p.cpu)
                    + (p.label.equals(p.name) ? "" : "  ·  " + p.name)));
            row.check.setVisibility(picked.isEmpty() || p.pkg == null ? View.GONE : View.VISIBLE);
            row.check.setChecked(p.pkg != null && picked.contains(p.pkg));
            showIcon(row, p.name.split(":")[0]);
        });
        listView.setAdapter(adapter);
        final int[] sort = {0};
        Runnable apply = () -> {
            if (sort[0] == 2) Collections.sort(procs, (x, y) -> x.label.compareToIgnoreCase(y.label));
            else if (sort[0] == 1) Collections.sort(procs, (x, y) -> Float.compare(y.cpu, x.cpu));
            else Collections.sort(procs, (x, y) -> Long.compare(y.kb, x.kb));
            adapter.set(procs);
        };
        page.addView(sortBar(new String[]{"Memory", "CPU", "Name"}, i -> {
            sort[0] = i;
            apply.run();
        }), 2);
        Runnable[] load = new Runnable[1];
        load[0] = () -> worker.execute(() -> {
            List<Proc> found = runningProcesses();
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mem);
            main.post(() -> {
                summary.setText(Tr.t("Memory: ") + Formatter.formatShortFileSize(this, mem.totalMem - mem.availMem)
                        + Tr.t(" used of ") + Formatter.formatShortFileSize(this, mem.totalMem));
                procs.clear();
                procs.addAll(found);
                apply.run();
            });
        });
        // Stops the given packages one after the other, then reads the list again.
        java.util.function.Consumer<List<String>> stop = pkgs -> worker.execute(() -> {
            String error = null;
            for (String pkg : pkgs) {
                String e = error(Shell.run("am force-stop " + Shell.quote(pkg)));
                if (e != null) error = e;
            }
            String failed = error;
            main.post(() -> {
                picked.clear();
                stopSelected.setVisibility(View.GONE);
                if (failed != null) toast(Tr.t(failed));
                load[0].run();
            });
        });
        Runnable showPicked = () -> {
            stopSelected.setVisibility(picked.isEmpty() ? View.GONE : View.VISIBLE);
            stopSelected.setText(Tr.t("Stop selected") + " (" + picked.size() + ")");
            adapter.notifyDataSetChanged();
        };
        stopSelected.setOnClickListener(v -> {
            List<String> pkgs = new ArrayList<>(picked);
            if (!prefs.getBoolean(PREF_STOP_ASK, true)) {
                stop.accept(pkgs);
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(Tr.t("Stop selected") + " (" + pkgs.size() + ")?")
                    .setMessage(Tr.t("They close now and can start again when you open them or they're needed."))
                    .setPositiveButton(Tr.t("Stop"), (d, w) -> stop.accept(pkgs))
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            Proc p = adapter.getItem(position);
            if (p.pkg == null) return true;
            if (!picked.remove(p.pkg)) picked.add(p.pkg);
            showPicked.run();
            return true;
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Proc p = adapter.getItem(position);
            if (p.pkg == null) {
                toast(Tr.t("This is a system process and can't be stopped."));
                return;
            }
            if (!picked.isEmpty()) { // choosing several: a tap adds or removes
                if (!picked.remove(p.pkg)) picked.add(p.pkg);
                showPicked.run();
                return;
            }
            if (!prefs.getBoolean(PREF_STOP_ASK, true)) {
                stop.accept(java.util.Collections.singletonList(p.pkg));
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(Tr.t("Stop " + p.label + "?"))
                    .setMessage(Tr.t("It closes now and can start again when you open it or it's needed."))
                    .setPositiveButton(Tr.t("Stop"), (d, w) -> stop.accept(java.util.Collections.singletonList(p.pkg)))
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        load[0].run();
    }

    private List<Proc> runningProcesses() {
        List<Proc> list = new ArrayList<>();
        // CPU comes from top's second sample (the first only has averages since boot), read while meminfo runs.
        String[] both = Shell.run("f=/data/local/tmp/tinykit-top; top -b -n 2 -d 1 -o PID,%CPU,ARGS >$f 2>/dev/null & "
                + "dumpsys meminfo | sed -n '/Total PSS by process/,/^$/p'; wait; echo @@; cat $f; rm -f $f").out.split("@@", 2);
        String out = both[0];
        Map<String, Float> cpu = new HashMap<>();
        if (both.length > 1) {
            for (String line : both[1].split("\n")) {
                Matcher c = CPUINFO.matcher(line);
                if (c.find()) cpu.put(c.group(2), Float.parseFloat(c.group(1))); // the later sample wins
            }
        }
        PackageManager pm = getPackageManager();
        for (String line : out.split("\n")) {
            Matcher m = MEMINFO.matcher(line);
            if (!m.find()) continue;
            Proc p = new Proc();
            p.kb = Long.parseLong(m.group(1).replace(",", ""));
            p.name = m.group(2);
            if (p.name.equals("top")) continue; // the sampler started just above
            p.cpu = cpu.getOrDefault(p.name, 0f);
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
                    setDim(key, 0.7f);
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
            main.post(() -> log.setText(Tr.t(out.trim().isEmpty() ? empty : out)));
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
                .setTitle(Tr.t("Update available"))
                .setMessage("TinyKit " + version + Tr.t(" is out (you have ") + versionName(getPackageName()) + ").")
                .setPositiveButton(Tr.t("Download"), (d, w) -> openUrl(download))
                .setNegativeButton(Tr.t("Later"), null)
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
                .setTitle(Tr.t("Add the widget"))
                .setMessage(Tr.t("Touch and hold an empty spot on your home screen, tap Widgets, then drag "
                        + "TinyKit's \"Speed up\" widget onto the screen."))
                .setPositiveButton(Tr.t("OK"), null)
                .show();
    }

    private void checkForUpdate(View row) {
        TextView status = (TextView) row.getTag();
        if (!row.isEnabled()) return;
        row.setEnabled(false);
        status.setText(Tr.t("Checking…"));
        worker.execute(() -> {
            String[] latest = latestRelease();
            String current = versionName(getPackageName());
            String found = latest == null ? null : latest[0];
            String download = latest == null ? null : latest[1];
            main.post(() -> {
                row.setEnabled(true);
                if (found == null) {
                    status.setText(Tr.t("Couldn't reach GitHub. Check your internet and try again."));
                } else if (compareVersions(found, current) > 0) {
                    status.setText(Tr.t("Version " + found + " is available."));
                    new AlertDialog.Builder(this)
                            .setTitle(Tr.t("Update available"))
                            .setMessage("TinyKit " + found + Tr.t(" is out (you have ") + current + ").")
                            .setPositiveButton(Tr.t("Download"), (d, w) -> openUrl(download))
                            .setNegativeButton(Tr.t("Later"), null)
                            .show();
                } else {
                    status.setText(Tr.t("You have the latest version (" + current + ")."));
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
                .setTitle(Tr.t(getString(R.string.app_name) + " " + versionName(getPackageName())))
                .setMessage(Tr.t("Made by Ali Alakbar\n\nSmall Android tweaks powered by Shizuku. "
                        + "Free and open source (MIT).\n\n" + REPO))
                .setPositiveButton(Tr.t("GitHub"), (d, w) -> openUrl(REPO))
                .setNegativeButton(Tr.t("Close"), null)
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
                log.setText(Tr.t(out.isEmpty()
                        ? "No crashes recorded since the phone started.\n\nAndroid clears this log on restart."
                        : out));
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
                {Tiles.ScreenRecord.class, "Screen record", "Record the screen to Movies.", R.drawable.ic_tile_record},
                {Tiles.Firewall.class, "Internet block", "Pause and resume blocking apps' internet.", R.drawable.ic_tile_firewall},
                {Tiles.HwOverlays.class, "HW overlays off", "Turn off hardware overlays (developer option).", R.drawable.ic_tile_overlays},
                {Tiles.ForceDark.class, "Force dark", "Dark-theme apps that have no dark mode.", R.drawable.ic_tile_forcedark},
                {Tiles.WorkProfile.class, "Work profile", "Pause and resume your work profile.", R.drawable.ic_tile_work}};
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
        input.setHint(Tr.t("e.g. getprop ro.product.model"));
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
        java.lang.Process[] runningCmd = {null};
        run.setOnClickListener(v -> {
            if (runningCmd[0] != null) {
                runningCmd[0].destroy();
                return;
            }
            String cmd = input.getText().toString().trim();
            if (cmd.isEmpty()) return;
            if (!Shell.ready()) {
                toast(Tr.t("Start Shizuku and allow TinyKit first."));
                return;
            }
            output.setText(Tr.t("Running…"));
            run.setText(Tr.t("Stop"));
            // Its own thread, so a long command never holds up the rest of TinyKit.
            new Thread(() -> {
                Shell.Result r = Shell.runLimited(cmd, 60_000L, 256 * 1024, p -> runningCmd[0] = p);
                main.post(() -> {
                    runningCmd[0] = null;
                    run.setText(Tr.t("Run"));
                    output.setText((r.out.isEmpty() ? Tr.t("(no output)") : r.out) + "\n\n[exit " + r.code + "]");
                });
            }, "command").start();
        });
    }

    // ---- Recover deleted files ------------------------------------------------------------------

    private static final String RECOVER_DIR = "/sdcard/Download/TinyKit/Recovered";
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long THUMB_MAX = 8L * 1024 * 1024;
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
        setDim(help, 0.7f);
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
                    .setTitle(Tr.t("Restore this file?"))
                    .setMessage(Tr.t(item.name + "\n\nCopied to Download/TinyKit/Recovered. The original stays in the bin."))
                    .setPositiveButton(Tr.t("Restore"), (d, w) -> restoreFile(item))
                    .setNegativeButton(Tr.t("Cancel"), null)
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
                if (found.isEmpty()) loading.setText(Tr.t("Nothing found in any recycle bin or trash."));
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
                setDim(sub, 0.65f);
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
            name.setText(Tr.t(d.name));
            sub.setText(Tr.t(d.source + "  ·  " + d.sizeText + (d.date.isEmpty() ? "" : "  ·  " + d.date)));
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
     * Reads a file's bytes as the shell user through Shizuku and decodes a small thumbnail. This works
     * for files in trash that the app can't open directly.
     */
    private Bitmap makeThumb(String path) {
        try {
            byte[] bytes = Shell.bytes("cat " + Shell.quote(path) + " 2>/dev/null", (int) THUMB_MAX);
            if (bytes == null || bytes.length == 0) return null;
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
        toast(Tr.t("Restoring " + item.name + "…"));
        worker.execute(() -> {
            // Never over an earlier recovered file: name-1.jpg, name-2.jpg and so on.
            String name = fileName(item.name);
            int dot = name.lastIndexOf('.');
            String base = RECOVER_DIR + "/" + (dot > 0 ? name.substring(0, dot) : name);
            String ext = dot > 0 ? name.substring(dot) : "";
            Shell.Result r = Shell.run("mkdir -p " + RECOVER_DIR + "; b=" + Shell.quote(base) + "; e=" + Shell.quote(ext)
                    + "; f=\"$b$e\"; i=1; while [ -e \"$f\" ]; do f=\"$b-$i$e\"; i=$((i+1)); done; "
                    + "cp " + Shell.quote(item.path) + " \"$f\" && echo \"$f\"");
            String dest = r.out.trim();
            main.post(() -> {
                if (r.ok() && dest.startsWith(RECOVER_DIR)) {
                    MediaScannerConnection.scanFile(this, new String[]{dest}, null, null);
                    toast(Tr.t("Restored to Download/TinyKit/Recovered"));
                } else {
                    toast(Tr.t(r.out.isEmpty() ? "Couldn't restore it." : r.out));
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
            main.post(() -> sub.setText(Tr.t(label)));
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
                    .setTitle(Tr.t("Animation speed"))
                    .setSingleChoiceItems(Tr.all(ANIM_LABELS), finalSel, (d, which) -> {
                        d.dismiss();
                        float s = ANIM_SCALES[which];
                        worker.execute(() -> {
                            String err = error(Shell.run("settings put global window_animation_scale " + s
                                    + "; settings put global transition_animation_scale " + s
                                    + "; settings put global animator_duration_scale " + s));
                            main.post(() -> {
                                if (err != null) toast(Tr.t(err));
                                refreshAnimationSpeed(row);
                            });
                        });
                    })
                    .setNegativeButton(Tr.t("Cancel"), null)
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
                .setTitle(Tr.t("Advanced reboot"))
                .setItems(Tr.all(labels), (d, which) -> confirmReboot(labels[which], cmds[which], notes[which]))
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    private void confirmReboot(String label, String cmd, String note) {
        String message = (label.contains("recovery") || label.contains("bootloader")
                ? "The phone will restart into that mode now."
                : "The phone will restart now.") + (note.isEmpty() ? "" : "\n\n" + note);
        new AlertDialog.Builder(this)
                .setTitle(Tr.t(label + "?"))
                .setMessage(Tr.t(message))
                .setPositiveButton(Tr.t(label), (d, w) -> {
                    toast(Tr.t(label + "…"));
                    worker.execute(() -> {
                        Shell.Result r = Shell.run(cmd);
                        if (!r.ok()) {
                            main.post(() -> toast(Tr.t("Couldn't do that. " + (r.out.isEmpty() ? "" : r.out))));
                        }
                    });
                })
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    // ---- Settings backup & restore --------------------------------------------------------------

    // ---- Reset ----------------------------------------------------------------------------------

    /** Asks what to reset, saves a backup, resets the chosen groups and shows exactly what happened. */
    private void confirmResetEverything() {
        if (!Shell.ready()) {
            toast(Tr.t("Start Shizuku and allow TinyKit first."));
            return;
        }
        String[] names = {
                "Internet block (allow list mode, blocked and allowed apps, pause)",
                "Permission choices (Fake, Real, Off) and any damage they caused",
                "Hidden apps and removed system apps (brought back, including apps you switched off in Settings)",
                "Force dark, HW overlays, sensors off, Shut Up, Run in the background, and the Quick toggles that are on",
                "Background limits, clipboard guard and kept-alive apps (these are Android's own settings, "
                        + "so this also clears limits you set in Settings)"};
        boolean[] on = {true, true, true, true, false};
        LinearLayout box = vertical();
        box.setPadding(dp(20), dp(8), dp(20), dp(0));
        TextView info = text("Puts what TinyKit changed back to Android's defaults. TinyKit can't know what a "
                + "setting was before it changed it, so this is Android's default, not a custom value you "
                + "had earlier. A backup is saved to Download/TinyKit first.\n\nNot touched: developer "
                + "options, USB debugging, screen timeout, notification channels and special access.", 14, false);
        info.setPadding(0, dp(8), 0, dp(8));
        box.addView(info);
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            CheckBox cb = new CheckBox(this);
            cb.setText(Tr.t(names[i]));
            cb.setChecked(on[i]);
            cb.setOnCheckedChangeListener((v, checked) -> on[index] = checked);
            box.addView(cb);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(Tr.t("Reset everything TinyKit changed?"))
                .setView(scroll)
                .setPositiveButton(Tr.t("Reset"), (d, w) -> runReset(on[0], on[1], on[2], on[3], on[4]))
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    /** The small version for the permissions: Real, Fake and Off choices, and what they broke. */
    private void confirmResetPermissions() {
        if (!Shell.ready()) {
            toast(Tr.t("Start Shizuku and allow TinyKit first."));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(Tr.t("Reset permission changes?"))
                .setMessage(Tr.t("Fake and Off permissions become not allowed, so Android asks the app again. Real "
                        + "ones stay allowed. Anything Fake changed on apps you never picked, like the camera, "
                        + "is repaired. A backup is saved to Download/TinyKit first."))
                .setPositiveButton(Tr.t("Reset"), (d, w) -> runReset(false, true, false, false, false))
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    private void runReset(boolean internet, boolean permissions, boolean apps, boolean switches, boolean limits) {
        if (!internet && !permissions && !apps && !switches && !limits) {
            toast(Tr.t("Nothing was picked."));
            return;
        }
        TextView message = text("Saving a backup, then resetting…", 15, false);
        message.setPadding(dp(24), dp(16), dp(24), dp(8));
        AlertDialog progress = new AlertDialog.Builder(this).setTitle(Tr.t("Working")).setView(message)
                .setCancelable(false).show();
        worker.execute(() -> {
            // The safety net first: if it can't be saved, nothing is changed.
            String backup = backupSetup();
            List<String> lines = new ArrayList<>();
            if (backup.startsWith("Failed")) {
                lines.add("Nothing was reset because the backup couldn't be saved. " + backup);
            } else {
                lines.add(backup);
                if (permissions) lines.add(Reset.permissions(this, prefs));
                if (internet) lines.add(Reset.internet(this, prefs));
                if (apps) lines.add(Reset.apps(this));
                if (switches) lines.add(Reset.switches(this, prefs));
                if (limits) lines.add(Reset.limits(this));
            }
            String result = String.join("\n\n", lines);
            main.post(() -> {
                if (!alive()) return;
                dismiss(progress);
                new AlertDialog.Builder(this).setTitle(Tr.t("Reset")).setMessage(Tr.t(result))
                        .setPositiveButton(Tr.t("OK"), null).show();
            });
        });
    }

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
        if (prefs.getBoolean(Restore.PREF_NO_OVERLAYS, false)) out.append("overlays\t1\n");
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
        for (String entry : FakePolicy.all(prefs)) {
            String[] f = entry.split("\\|", -1);
            if (f.length == 3) out.append("fake\t").append(f[1]).append('\t').append(f[2].isEmpty() ? "-" : f[2])
                    .append('\t').append(f[0]).append('\n');
        }
        for (String state : new String[]{"Real", "Off"}) {
            for (String entry : FakePolicy.entries(prefs, state)) {
                String[] f = entry.split("\\|", -1);
                if (f.length == 3) out.append(state.toLowerCase(Locale.ROOT)).append('\t').append(f[1]).append('\t')
                        .append(f[2].isEmpty() ? "-" : f[2]).append('\t').append(f[0]).append('\n');
            }
        }
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
                    toast(Tr.t("No setup backups yet. Back up first."));
                    return;
                }
                String[] names = new String[files.size()];
                for (int i = 0; i < names.length; i++) names[i] = files.get(i).substring(files.get(i).lastIndexOf('/') + 1);
                new AlertDialog.Builder(this)
                        .setTitle(Tr.t("Restore which setup?"))
                        .setItems(Tr.all(names), (d, which) -> new AlertDialog.Builder(this)
                                .setTitle(Tr.t("Restore this setup?"))
                                .setMessage(Tr.t("Puts back the internet block, permission choices, background limits, hidden and removed "
                                        + "apps saved in " + names[which] + ". Apps that aren't installed any more "
                                        + "are skipped."))
                                .setPositiveButton(Tr.t("Restore"), (x, y) -> withProgress("Restore",
                                        () -> restoreSetup(files.get(which))))
                                .setNegativeButton(Tr.t("Cancel"), null)
                                .show())
                        .setNegativeButton(Tr.t("Cancel"), null)
                        .show();
            });
        });
    }

    /** Applies a setup file. Apps that aren't installed any more are skipped. */
    private String restoreSetup(String file) {
        Set<String> blocked = new HashSet<>(), allowed = new HashSet<>(), fakes = new HashSet<>();
        List<String[]> choices = new ArrayList<>(); // Real and Off: {package, op, permission, state}
        boolean allowMode = false, dark = false, overlays = false;
        StringBuilder script = new StringBuilder();
        int count = 0;
        for (String line : Shell.run("cat " + Shell.quote(file)).out.split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 2) continue;
            // Package names are checked before they reach the shell.
            if (f.length > 1 && !f[0].equals("mode") && !f[0].equals("dark") && !f[0].equals("overlays") && !PACKAGE.matcher(f[f.length - 1]).matches()) continue;
            String q = Shell.quote(f[f.length - 1]);
            switch (f[0]) {
                case "mode": allowMode = f[1].equals("allow"); break;
                case "dark": dark = true; break;
                case "overlays": overlays = true; break;
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
                case "fake":
                    if (f.length == 4 && f[1].matches("[A-Z_]+") && f[2].matches("-|[A-Za-z0-9_.]+")) {
                        fakes.add(FakePolicy.key(f[3], f[1], f[2].equals("-") ? "" : f[2]));
                    }
                    break;
                case "real":
                case "off":
                    if (f.length == 4 && f[1].matches("[A-Z_]+") && f[2].matches("-|[A-Za-z0-9_.]+")) {
                        choices.add(new String[]{f[3], f[1], f[2].equals("-") ? "" : f[2], f[0].equals("real") ? "Real" : "Off"});
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
                .putBoolean(FirewallPolicy.MODE, allowMode).putBoolean(Restore.PREF_FORCE_DARK, dark)
                .putBoolean(Restore.PREF_NO_OVERLAYS, overlays).apply();
        if (script.length() > 0) Shell.run(script.toString());
        for (String[] ch : choices) FakePolicy.choose(prefs, ch[0], ch[1], ch[2], ch[3]);
        if (!fakes.isEmpty() || !choices.isEmpty()) {
            FakePolicy.addFakes(prefs, fakes);
            FakePolicy.enforce(this, prefs);
        }
        if (dark) Shell.run("setprop debug.hwui.force_dark true");
        if (overlays) Shell.run(Restore.overlaysCommand(true));
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
                    toast(Tr.t("No backups yet. Back up first."));
                    return;
                }
                String[] names = new String[files.size()];
                for (int i = 0; i < names.length; i++) {
                    names[i] = files.get(i).substring(files.get(i).lastIndexOf('/') + 1);
                }
                new AlertDialog.Builder(this)
                        .setTitle(Tr.t("Restore which backup?"))
                        .setItems(Tr.all(names), (d, which) -> confirmRestore(files.get(which)))
                        .setNegativeButton(Tr.t("Cancel"), null)
                        .show();
            });
        });
    }

    private void confirmRestore(String file) {
        new AlertDialog.Builder(this)
                .setTitle(Tr.t("Restore settings?"))
                .setMessage(Tr.t("This writes the saved settings back. Read-only keys are skipped. "
                        + "Some changes may need a restart."))
                .setPositiveButton(Tr.t("Restore"), (d, w) -> withProgress("Restore", () -> {
                    // Keys that had no value were saved as "null" and are left alone.
                    Shell.Result r = Shell.run("ok=0; bad=0; while IFS= read -r line; do t=${line%% *}; "
                            + "rest=${line#* }; key=${rest%%=*}; val=${rest#*=}; "
                            + "case $t in global|secure|system) ;; *) continue;; esac; "
                            + "[ -z \"$key\" ] || [ \"$val\" = null ] && continue; "
                            + "if settings put $t \"$key\" \"$val\" >/dev/null 2>&1; then ok=$((ok+1)); "
                            + "else bad=$((bad+1)); fi; done < " + Shell.quote(file) + "; echo \"$ok $bad\"");
                    String[] n = r.out.trim().split(" ");
                    if (!r.ok() || n.length != 2) return "Failed: " + r.out;
                    return "Restored " + n[0] + " settings." + ("0".equals(n[1]) ? ""
                            : " " + n[1] + " couldn't be written (read-only on this phone) and were skipped.")
                            + " Some changes need a restart.";
                }))
                .setNegativeButton(Tr.t("Cancel"), null)
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
            publicIp.setText(Tr.t("Offline"));
        } else {
            worker.execute(() -> {
                String pub = httpGet("https://api.ipify.org", 3000);
                if (pub == null) pub = httpGet("https://ifconfig.me/ip", 3000);
                String result = pub != null && pub.length() <= 45 && pub.matches("[0-9a-fA-F:.]+")
                        ? pub : "Couldn't reach the internet";
                main.post(() -> publicIp.setText(Tr.t(result)));
            });
        }
    }

    /** Adds a dim key line and a selectable value line, returning the value view so it can update. */
    private TextView addKv(LinearLayout page, String key, String value) {
        TextView k = text(key, 13, false);
        setDim(k, 0.7f);
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
        forgetProfiles();
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
                pkg = p != null && installedHere(p) ? p : "";
                who = p != null ? uidTitle(p) : token;
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

    /** User ids are {@code user * PER_USER_RANGE + app id}. */
    private static final int PER_USER_RANGE = 100000;

    /** First package sharing a batterystats uid token like "u0a162" or "1000", or null. */
    private String uidPackage(String token) {
        int uid = -1;
        Matcher app = Pattern.compile("u(\\d+)a(\\d+)").matcher(token);
        if (app.find()) {
            uid = Integer.parseInt(app.group(1)) * 100000 + 10000 + Integer.parseInt(app.group(2));
        } else if (token.matches("\\d+")) {
            uid = Integer.parseInt(token);
        }
        if (uid < 0) return null;
        // batterystats lists every profile (Secure Folder, Dual Apps); Android only answers apps
        // for our own, and throws a SecurityException for the others, so those are asked of the shell.
        if (uid / PER_USER_RANGE != Process.myUid() / PER_USER_RANGE) return profilePackage(uid);
        try {
            String[] pkgs = getPackageManager().getPackagesForUid(uid);
            if (pkgs != null && pkgs.length > 0) return pkgs[0];
        } catch (SecurityException ignored) {
        }
        return null;
    }

    /** Package lists of the other profiles, as user id to (uid to package), filled on first use. */
    private final Map<Integer, Map<Integer, String>> profileApps = new HashMap<>();

    private synchronized String profilePackage(int uid) {
        int user = uid / PER_USER_RANGE;
        Map<Integer, String> apps = profileApps.get(user);
        if (apps == null) {
            apps = new HashMap<>();
            Matcher m = Pattern.compile("package:(\\S+)\\s+uid:(\\d+)").matcher(
                    Shell.run("pm list packages -U --user " + user).out);
            while (m.find()) apps.putIfAbsent(Integer.parseInt(m.group(2)), m.group(1));
            profileApps.put(user, apps);
        }
        return apps.get(uid);
    }

    /** A row title for a batterystats uid: the app's name, or its package name when it only exists in another profile. */
    private String uidTitle(String pkg) {
        return installedHere(pkg) ? label(pkg) : pkg + " (other profile)";
    }

    private boolean installedHere(String pkg) {
        try {
            getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private synchronized void forgetProfiles() {
        profileApps.clear();
    }

    private void openWakeups() {
        rowsPage("Wake-ups", "What has held the phone awake since the last charge, longest first.",
                "Nothing recorded since the last charge, or this phone hides it.", this::wakeups);
    }

    private List<String[]> wakeups() {
        forgetProfiles();
        List<String[]> rows = new ArrayList<>();
        String out = Shell.run("dumpsys batterystats --charged | sed -n '/All partial wake locks/,/^$/p'").out;
        Pattern p = Pattern.compile("Wake lock (\\S+) (.+?): ([0-9hms ]+?) \\((\\d+) times\\)");
        for (String line : out.split("\n")) {
            Matcher m = p.matcher(line.trim());
            if (!m.find()) continue;
            String pkg = uidPackage(m.group(1));
            String who = pkg != null ? uidTitle(pkg) : m.group(1);
            String detail = "Held " + m.group(3).trim() + "  ·  " + m.group(4) + " times  ·  " + m.group(2);
            rows.add(new String[]{who, detail, pkg != null && installedHere(pkg) ? pkg : ""});
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
        setDim(helpView, 0.7f);
        page.addView(helpView);
        TextView loading = text("Loading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push(title, page);
        Adapter<String[]> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(Tr.t(a[0]));
            row.second.setText(Tr.t(a[1]));
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
            // Whatever goes wrong reading the phone's logs (another profile, an odd dump format), the
            // page shows its empty message instead of taking the whole app down.
            List<String[]> rows;
            try {
                rows = job.run();
            } catch (RuntimeException e) {
                android.util.Log.w("TinyKit", title + ": " + e);
                rows = new ArrayList<>();
            }
            final List<String[]> shownRows = rows;
            main.post(() -> {
                loading.setVisibility(shownRows.isEmpty() ? View.VISIBLE : View.GONE);
                if (shownRows.isEmpty()) loading.setText(Tr.t(empty));
                original.clear();
                original.addAll(shownRows);
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
    /** Every sensitive permission with how many apps hold it; a tap lists those apps. */
    private void openPermissionTypes() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Pick a permission to see which apps ask for it and which have it.", 14, false);
        setDim(help, 0.8f);
        page.addView(help);
        TextView loading = text("Reading permissions…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Permissions by type", page);
        final Map<String, List<App>> byPermission = new HashMap<>();
        Adapter<String[]> adapter = new Adapter<>(false, (op, row) -> {
            List<App> apps = byPermission.get(op[2]);
            int has = 0;
            if (apps != null) for (App a : apps) if (a.checked) has++;
            row.first.setText(Tr.t(op[1]));
            row.second.setText(apps == null ? "" : has + " " + Tr.t("of") + " " + apps.size() + " " + Tr.t("apps have it"));
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String[] op = adapter.getItem(position);
            List<App> apps = byPermission.get(op[2]);
            if (apps != null) openPermissionApps(op[1], apps);
        });
        worker.execute(() -> {
            PackageManager pm = getPackageManager();
            for (PackageInfo info : pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                if (info.requestedPermissions == null || info.packageName.equals(getPackageName())) continue;
                for (int i = 0; i < info.requestedPermissions.length; i++) {
                    for (String[] op : PERMISSION_OPS) {
                        if (!op[2].equals(info.requestedPermissions[i])) continue;
                        App a = new App();
                        a.pkg = info.packageName;
                        a.label = info.applicationInfo == null ? info.packageName
                                : String.valueOf(info.applicationInfo.loadLabel(pm));
                        a.checked = (info.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                        byPermission.computeIfAbsent(op[2], k -> new ArrayList<>()).add(a);
                    }
                }
            }
            List<String[]> shown = new ArrayList<>();
            for (String[] op : PERMISSION_OPS) if (byPermission.containsKey(op[2])) shown.add(op);
            main.post(() -> {
                loading.setVisibility(View.GONE);
                adapter.set(shown);
            });
        });
    }

    /** The apps that ask for one permission, the ones that have it first. A tap opens that app's permissions. */
    private void openPermissionApps(String title, List<App> apps) {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        EditText search = searchField("Search apps");
        page.addView(search, fieldMargin());
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push(title, page);
        Adapter<App> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(a.label);
            row.second.setText(a.pkg);
            row.third.setText(Tr.t(a.checked ? "Allowed" : "Not allowed"));
            row.third.setTextColor(a.checked ? stateColor("Real") : stateColor("Off"));
            showIcon(row, a.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<App> shown = new ArrayList<>();
            for (App a : apps) {
                if (a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.toLowerCase(Locale.ROOT).contains(q)) shown.add(a);
            }
            Collections.sort(shown, (x, y) -> x.checked != y.checked ? (x.checked ? -1 : 1)
                    : x.label.compareToIgnoreCase(y.label));
            adapter.set(shown);
        };
        search.addTextChangedListener(watcher(filter));
        listView.setOnItemClickListener((parent, view, position, id) -> openAppPage(adapter.getItem(position).pkg));
        filter.run();
    }

    private void openAppList(String title, String help, OnText onPick) {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView helpView = text(help, 14, false);
        setDim(helpView, 0.8f);
        page.addView(helpView);
        EditText search = searchField("Search apps");
        page.addView(search, fieldMargin());
        CheckBox system = new CheckBox(this);
        system.setText(Tr.t("Show system apps"));
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
            row.first.setText(Tr.t(app.label));
            row.second.setText(Tr.t(app.pkg));
            showIcon(row, app.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<App> shown = new ArrayList<>();
            for (App a : all) {
                if ((system.isChecked() || !a.system)
                        && (a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.toLowerCase(Locale.ROOT).contains(q))) {
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
        setDim(sub, 0.6f);
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
                List<java.util.function.Consumer<String>> setters = new ArrayList<>();
                int allAt = list.getChildCount();
                for (String[] op : PERMISSION_OPS) {
                    if (requested.contains(op[2]) || granted.contains(op[2])) {
                        setters.add(opRow(list, pkg, op, true, stateOf(true, granted.contains(op[2]), modes.get(op[0]))));
                    }
                }
                if (setters.isEmpty()) list.addView(note("This app requests no sensitive permissions."));
                else list.addView(allRow(setters), allAt); // one tap for every permission shown below
                section(list, "Hidden app-ops");
                for (String[] op : HIDDEN_OPS) {
                    opRow(list, pkg, op, false, stateOf(false, false, modes.get(op[0])));
                }
            });
        });
    }

    /** A permission with an inline Real / Fake / Off segmented control — one tap to change. */
    private java.util.function.Consumer<String> opRow(LinearLayout list, String pkg, String[] op, boolean permission,
                                                       String initial) {
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
        final java.util.function.Consumer<String> choose = target -> {
            if (!Shell.ready()) {
                toast(Tr.t("Start Shizuku and allow TinyKit first."));
                return;
            }
            if (target.equals(state[0])) return;
            state[0] = target;
            render.run();
            worker.execute(() -> {
                String err = setOp(pkg, op, permission, target);
                // Remembered (Real, Fake or Off) so Android can't quietly change it back.
                if (err == null) FakePolicy.choose(prefs, pkg, op[0], permission ? op[2] : "", target);
                String now = readState(pkg, op, permission);
                main.post(() -> {
                    state[0] = now;
                    render.run();
                    if (err != null) toast(Tr.t(err));
                });
            });
        };
        for (int i = 0; i < 3; i++) {
            final String target = keys[i];
            TextView pill = text(target, 13, true);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(0, dp(9), 0, dp(9));
            pill.setOnClickListener(v -> choose.accept(target));
            pills[i] = pill;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(i == 0 ? 0 : dp(4), 0, i == 2 ? 0 : dp(4), 0);
            seg.addView(pill, lp);
        }
        block.addView(seg);
        render.run();
        list.addView(block);
        return choose;
    }

    /** "All permissions": Real, Fake or Off applied to every permission row of the page in one tap. */
    private View allRow(List<java.util.function.Consumer<String>> setters) {
        LinearLayout block = vertical();
        block.setPadding(dp(16), dp(10), dp(16), dp(6));
        block.addView(text("All permissions", 16, true));
        LinearLayout seg = new LinearLayout(this);
        seg.setPadding(0, dp(8), 0, 0);
        String[] keys = {"Real", "Fake", "Off"};
        for (int i = 0; i < 3; i++) {
            String target = keys[i];
            TextView pill = text(target, 13, true);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(0, dp(9), 0, dp(9));
            stylePill(pill, target, false);
            pill.setOnClickListener(v -> {
                for (java.util.function.Consumer<String> set : setters) set.accept(target);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(i == 0 ? 0 : dp(4), 0, i == 2 ? 0 : dp(4), 0);
            seg.addView(pill, lp);
        }
        block.addView(seg);
        return block;
    }

    private void stylePill(TextView pill, String key, boolean selected) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(8));
        shape.setColor(selected ? stateColor(key) : (lightTheme ? 0xFFE0E0E0 : 0xFF1C1C1C));
        pill.setBackground(shape);
        pill.setTextColor(selected ? 0xFF000000 : (lightTheme ? 0xFF000000 : 0xFFBDBDBD));
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
            // Guarded: refuses Fake/Off where other apps or the camera would be hit, and undoes a
            // change that turns out to have touched an app that wasn't picked.
            return PermGuard.setPermission(this, pkg, op[0], op[2], target);
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

    // ---- Work profile -------------------------------------------------------------------------

    private static final String[] WORK_WAITS = {"Right away", "After 1 minute", "After 5 minutes", "After 15 minutes"};

    private void openWork() {
        LinearLayout body = vertical();
        View page = scroll(body);
        Runnable[] again = new Runnable[1];
        // Runs when the page opens and every time it comes back into view.
        again[0] = () -> worker.execute(() -> {
            int id = -1;
            int state = Work.RUNNING;
            List<Work.App> apps = new ArrayList<>();
            String lock = null;
            boolean lockOk = false;
            try {
                if (Shell.ready() && Work.supported(this)) {
                    id = Work.id(this);
                    if (id >= 0) {
                        state = Work.state(this, id);
                        apps = Work.apps(this, id);
                        lockOk = Work.lockSupported();
                        lock = lockOk ? Work.lockType(id) : null;
                    }
                }
            } catch (RuntimeException e) {
                android.util.Log.w("TinyKit", "work page: " + e);
            }
            int shownId = id;
            int shownState = state;
            List<Work.App> shownApps = apps;
            String shownLock = lock;
            boolean shownLockOk = lockOk;
            main.post(() -> renderWork(body, shownId, shownState, shownApps, shownLockOk, shownLock, again[0]));
        });
        body.addView(note("Loading…"));
        page.setTag(again[0]);
        push("Work profile", page);
    }

    /** {@code lockOk}: this Android can give the profile a PIN; {@code lock} is what locks it now, when known. */
    private void renderWork(LinearLayout body, int id, int state, List<Work.App> apps, boolean lockOk, String lock,
                            Runnable again) {
        body.removeAllViews();
        if (!Work.supported(this)) {
            body.addView(note("This phone can't have a work profile: it needs Android 8 or newer, and work "
                    + "profile support from its maker."));
            return;
        }
        if (id < 0) {
            body.addView(note("A work profile is a second, separate space on this phone. Apps you add to it are "
                    + "copies with their own accounts, data and notifications. Freeze them, or let them freeze by "
                    + "themselves, so they don't run at all. Give it its own PIN and it locks like Samsung's Secure "
                    + "Folder. Android allows one. TinyKit makes it by itself: no setup screens and no extra app "
                    + "inside it."));
            row(body, "Create work profile", "Takes a few seconds.", null, () -> new AlertDialog.Builder(this)
                    .setTitle(Tr.t("Create work profile?"))
                    .setMessage(Tr.t("This adds a second, separate space to your phone. Your own apps and data "
                            + "stay as they are. You can remove it later."))
                    .setPositiveButton(Tr.t("Create"), (d, w) -> {
                        toast(Tr.t("Creating…"));
                        worker.execute(() -> {
                            String error = Work.create(this);
                            main.post(() -> {
                                if (error != null) workMessage(error);
                                again.run();
                            });
                        });
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show(), true);
            return;
        }
        int frozen = 0;
        for (Work.App a : apps) if (a.frozen) frozen++;
        TextView status = text(Tr.t(state == Work.PAUSED ? "Paused" : state == Work.LOCKED ? "Locked" : "Running")
                + " · " + apps.size() + " " + Tr.t(apps.size() == 1 ? "app" : "apps")
                + (frozen > 0 ? " · " + frozen + " " + Tr.t("frozen") : ""), 16, true);
        status.setTextColor(attrColor(android.R.attr.colorAccent));
        status.setPadding(dp(16), dp(16), dp(16), dp(4));
        body.addView(status);
        if (state == Work.LOCKED) {
            row(body, "Unlock work profile", "The work profile is locked. Enter its PIN to use its apps.", arrow(),
                    () -> askPin("Unlock work profile", null, pin -> worker.execute(() -> {
                        String error = Work.unlock(this, id, pin);
                        main.post(() -> {
                            if (error != null) workMessage(error);
                            again.run();
                        });
                    })), true);
        }
        Switch pause = new Switch(this);
        pause.setClickable(false);
        pause.setFocusable(false);
        pause.setChecked(state == Work.PAUSED);
        row(body, "Pause work profile", "Stops every work app and silences its notifications, like the Work "
                + "switch in your launcher. With a PIN of its own (see Lock) it stays locked until the PIN is "
                + "entered.", pause, () -> {
            boolean on = state != Work.PAUSED;
            pause.setChecked(on);
            worker.execute(() -> {
                String error = Work.pause(this, id, on);
                main.post(() -> {
                    if (error != null) workMessage(error);
                    again.run();
                });
            });
        }, true);

        section(body, "Apps");
        link(body, "Add or remove apps", "Choose which apps have a copy in the work profile.",
                () -> openPicker("Work apps", "Checked apps have a copy in the work profile. Check one to add it. "
                                + "Uncheck it to remove that copy and its data from the work profile.", true,
                        () -> {
                            Set<String> in = new HashSet<>();
                            for (Work.App a : Work.apps(this, id)) in.add(a.pkg);
                            return in;
                        },
                        (pkg, on) -> {
                            String error = on ? Work.add(id, pkg) : Work.delete(id, pkg);
                            if (error == null && !on) forgetAutoFreeze(pkg);
                            return error;
                        }));
        link(body, "Work apps", "Open, freeze, add a launcher shortcut to, or remove the apps in the work profile.",
                () -> openWorkApps(id));
        link(body, "Install APK in the work profile", "Install .apk, .apks, .xapk and .apkm files into the work "
                + "profile only.", () -> pickApks(id));

        section(body, "Freeze");
        int auto = Work.auto(prefs).size();
        int delay = prefs.getInt(Work.PREF_DELAY, 60);
        row(body, "Freeze when the screen is off", auto == 0
                ? "No app chosen yet. Open Work apps, tap an app and choose Auto-freeze."
                : auto + " " + Tr.t(auto == 1 ? "app" : "apps") + " · " + Tr.t(waitName(delay))
                + ". Tap to change the wait.",
                arrow(), () -> chooseWait("Freeze after the screen has been off", Work.DELAYS, Work.PREF_DELAY,
                        60, again), false);
        row(body, "Freeze all", "Turns every work app off: it can't run, wake the phone or show in the launcher, "
                + "and keeps its data.", null, () -> freezeWork(id, true), true);
        row(body, "Unfreeze all", "Turns them all back on.", null, () -> freezeWork(id, false), true);

        section(body, "Lock");
        if (lockOk) {
            String pinState = lock == null ? "Give the work profile its own PIN, like Samsung's Secure Folder. "
                    + "Pausing then locks it: only the PIN opens it again."
                    : lock.equals("NONE") ? "No PIN of its own yet. Add one, and pausing locks the profile."
                    : "It has a PIN of its own. Tap to change or remove it.";
            row(body, "Work profile PIN", pinState, arrow(), () -> changePin(id, again), true);
        }
        int lockDelay = prefs.getInt(Work.PREF_LOCK, -1);
        row(body, "Lock when the screen is off", Tr.t(waitName(lockDelay))
                + ". Pauses the whole work profile. Tap to change.", arrow(),
                () -> chooseWait("Lock after the screen has been off", Work.LOCK_DELAYS, Work.PREF_LOCK, -1, again),
                false);
        Switch boot = new Switch(this);
        boot.setClickable(false);
        boot.setFocusable(false);
        boot.setChecked(prefs.getBoolean(Work.PREF_LOCK_BOOT, false));
        row(body, "Lock after a restart", "Pauses the work profile once each time the phone restarts, as soon as "
                + "Shizuku is running again.", boot, () -> {
            boolean on = !prefs.getBoolean(Work.PREF_LOCK_BOOT, false);
            prefs.edit().putBoolean(Work.PREF_LOCK_BOOT, on).apply();
            boot.setChecked(on);
        }, false);

        section(body, "Profile");
        row(body, "Remove work profile", "Deletes the profile, every app in it and all their data.", null,
                () -> new AlertDialog.Builder(this)
                        .setTitle(Tr.t("Remove the work profile?"))
                        .setMessage(Tr.t("Every app in it and all their data are deleted for good. Your main "
                                + "profile is not touched."))
                        .setPositiveButton(Tr.t("Remove"), (d, w) -> worker.execute(() -> {
                            String error = Work.remove(id);
                            if (error == null) forgetAutoFreeze(null);
                            main.post(() -> {
                                toast(Tr.t(error != null ? error : "Removed."));
                                again.run();
                            });
                        }))
                        .setNegativeButton(Tr.t("Cancel"), null)
                        .show(), true);
    }

    /** A longer message than a toast fits (why Android refused to make the profile). */
    private void workMessage(String message) {
        if (message.length() < 80) {
            toast(Tr.t(message));
        } else if (alive()) {
            new AlertDialog.Builder(this).setMessage(Tr.t(message)).setPositiveButton(Tr.t("OK"), null).show();
        }
    }

    private static String waitName(int seconds) {
        return seconds < 0 ? "Never" : seconds == 0 ? "Right away" : seconds == 60 ? "After 1 minute"
                : seconds == 300 ? "After 5 minutes" : "After 15 minutes";
    }

    private void chooseWait(String title, int[] values, String pref, int standard, Runnable again) {
        int current = prefs.getInt(pref, standard);
        String[] names = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            names[i] = waitName(values[i]);
            if (values[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(Tr.t(title))
                .setSingleChoiceItems(Tr.all(names), checked, (d, which) -> {
                    prefs.edit().putInt(pref, values[which]).apply();
                    d.dismiss();
                    // The service starts or stops to match what now waits for the screen to go off; choosing
                    // a lock wait turns on "Run in the background" (which it needs), as choosing an app to
                    // freeze does.
                    boolean needsService = pref.equals(Work.PREF_LOCK) && values[which] >= 0;
                    worker.execute(() -> {
                        boolean enabled = needsService && !BackgroundService.on(prefs);
                        if (enabled) BackgroundService.setEnabled(this, prefs, true);
                        else BackgroundService.sync(this);
                        if (enabled) main.post(() -> toast(Tr.t("Run in the background is on now. Auto-freeze and auto-lock need it.")));
                    });
                    again.run();
                })
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    /** One number box in a dialog; {@code onPin} gets the digits. */
    private void askPin(String title, String message, OnText onPin) {
        EditText pin = new EditText(this);
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setTypeface(Typeface.DEFAULT); // a number-password box would otherwise use a monospaced font
        pin.setHint(Tr.t("PIN"));
        LinearLayout box = vertical();
        box.setPadding(dp(24), dp(8), dp(24), 0);
        if (message != null) box.addView(text(message, 14, false));
        box.addView(pin);
        new AlertDialog.Builder(this).setTitle(Tr.t(title)).setView(box)
                .setPositiveButton(Tr.t("OK"), (d, w) -> onPin.run(pin.getText().toString()))
                .setNegativeButton(Tr.t("Cancel"), null).show();
    }

    /** Set, change or remove the work profile's own PIN: current PIN (if any) and new PIN (empty removes). */
    private void changePin(int id, Runnable again) {
        int type = android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        EditText old = new EditText(this);
        old.setInputType(type);
        old.setTypeface(Typeface.DEFAULT);
        old.setHint(Tr.t("Current PIN (empty if none)"));
        EditText next = new EditText(this);
        next.setInputType(type);
        next.setTypeface(Typeface.DEFAULT);
        next.setHint(Tr.t("New PIN (empty removes it)"));
        LinearLayout box = vertical();
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(text("A PIN is 4 to 16 digits. With one of its own the work profile's data is locked until "
                + "the PIN is entered. If you forget it, those apps and their data can't be opened again: the only "
                + "way out is to remove the work profile.", 14, false));
        box.addView(old);
        box.addView(next);
        new AlertDialog.Builder(this).setTitle(Tr.t("Work profile PIN")).setView(box)
                .setPositiveButton(Tr.t("Save"), (d, w) -> {
                    String before = old.getText().toString();
                    String after = next.getText().toString();
                    worker.execute(() -> {
                        String error = Work.setLock(id, before, after);
                        main.post(() -> {
                            toast(Tr.t(error != null ? error : after.isEmpty() ? "PIN removed." : "PIN saved."));
                            again.run();
                        });
                    });
                })
                .setNegativeButton(Tr.t("Cancel"), null).show();
    }

    private void freezeWork(int id, boolean frozen) {
        toast(Tr.t(frozen ? "Freezing…" : "Unfreezing…"));
        worker.execute(() -> {
            List<String> todo = new ArrayList<>();
            for (Work.App a : Work.apps(this, id)) if (a.frozen != frozen) todo.add(a.pkg);
            String error = Work.freezeAll(id, todo, frozen);
            main.post(() -> toast(Tr.t(error != null ? error : todo.isEmpty() ? "Nothing to change."
                    : (frozen ? "Froze " : "Unfroze ") + todo.size() + (todo.size() == 1 ? " app." : " apps."))));
        });
    }

    /** Adds or takes away an app's "freeze when the screen is off" choice; the service starts or stops to match. */
    private void toggleAutoFreeze(String pkg, Runnable done) {
        Set<String> auto = Work.auto(prefs);
        boolean on = auto.add(pkg);
        if (!on) auto.remove(pkg);
        prefs.edit().putStringSet(Work.PREF_AUTO, auto).apply();
        worker.execute(() -> {
            // It needs "Run in the background"; turning the first app on turns that on, like Shut Up does.
            boolean enabled = on && !BackgroundService.on(prefs);
            if (enabled) BackgroundService.setEnabled(this, prefs, true);
            else BackgroundService.sync(this);
            main.post(() -> {
                if (enabled) toast(Tr.t("Run in the background is on now. Auto-freeze and auto-lock need it."));
                done.run();
            });
        });
    }

    /** An app (or, with null, every app) that left the work profile no longer needs freezing by itself. */
    private void forgetAutoFreeze(String pkg) {
        Set<String> auto = Work.auto(prefs);
        if (pkg == null ? auto.isEmpty() : !auto.remove(pkg)) return;
        if (pkg == null) auto.clear();
        prefs.edit().putStringSet(Work.PREF_AUTO, auto).apply();
        BackgroundService.sync(this);
    }

    private boolean systemApp(String pkg) {
        try {
            int flags = getPackageManager().getApplicationInfo(pkg, 0).flags;
            return (flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openWorkApps(int id) {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Tap an app to open it, freeze it, set it to freeze by itself, or add a launcher "
                + "shortcut. A frozen app is off and keeps its data.", 13, false);
        setDim(help, 0.7f);
        page.addView(help);
        EditText search = searchField("Search apps");
        page.addView(search, fieldMargin());
        CheckBox system = new CheckBox(this);
        system.setText(Tr.t("Show system apps"));
        page.addView(system);
        TextView loading = text("Loading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        List<Work.App> all = new ArrayList<>();
        Adapter<Work.App> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(label(a.pkg));
            row.second.setText(a.pkg);
            List<String> tags = new ArrayList<>();
            if (a.frozen) tags.add(Tr.t("Frozen"));
            if (Work.auto(prefs).contains(a.pkg)) tags.add(Tr.t("Auto-freeze"));
            row.third.setText(TextUtils.join(" · ", tags));
            showIcon(row, a.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            Set<String> auto = Work.auto(prefs);
            List<Work.App> shown = new ArrayList<>();
            for (Work.App a : all) {
                // Frozen and auto-freeze apps always show, so one never gets lost behind the filter.
                boolean show = system.isChecked() || a.frozen || auto.contains(a.pkg) || !systemApp(a.pkg);
                if (show && (label(a.pkg).toLowerCase(Locale.ROOT).contains(q) || a.pkg.contains(q))) shown.add(a);
            }
            adapter.set(shown);
            loading.setText(Tr.t(all.isEmpty() ? "No apps in the work profile yet."
                    : "No match. Tick Show system apps to see more."));
            loading.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        };
        search.addTextChangedListener(watcher(filter));
        system.setOnCheckedChangeListener((v, checked) -> filter.run());
        Runnable load = () -> worker.execute(() -> {
            List<Work.App> apps = Work.apps(this, id);
            Collections.sort(apps, (x, y) -> label(x.pkg).compareToIgnoreCase(label(y.pkg)));
            main.post(() -> {
                all.clear();
                all.addAll(apps);
                filter.run();
            });
        });
        Runnable reload = load;
        listView.setOnItemClickListener((parentView, view, position, rowId) -> {
            Work.App a = adapter.getItem(position);
            boolean auto = Work.auto(prefs).contains(a.pkg);
            String[] items = {a.frozen ? "Unfreeze and open" : "Open", a.frozen ? "Unfreeze" : "Freeze",
                    auto ? "Stop auto-freezing" : "Auto-freeze when the screen is off", "Add launcher shortcut",
                    "Clear data", "Remove from work profile"};
            new AlertDialog.Builder(this)
                    .setTitle(label(a.pkg))
                    .setItems(Tr.all(items), (d, which) -> {
                        if (which == 2) {
                            toggleAutoFreeze(a.pkg, reload);
                        } else if (which == 3) {
                            String error = Work.pin(this, a.pkg, label(a.pkg));
                            if (error != null) toast(Tr.t(error));
                        } else if (which >= 4) {
                            boolean wipe = which == 4;
                            new AlertDialog.Builder(this)
                                    .setTitle(Tr.t((wipe ? "Clear data of " : "Remove ") + label(a.pkg) + "?"))
                                    .setMessage(Tr.t(wipe ? "Everything the app saved in the work profile is "
                                            + "deleted: accounts, files and settings. The app stays."
                                            : "Its copy in the work profile and that copy's data are deleted. The "
                                            + "app on your main profile stays."))
                                    .setPositiveButton(Tr.t(wipe ? "Clear" : "Remove"), (d2, w2) -> worker.execute(() -> {
                                        String error = wipe ? Work.clear(id, a.pkg) : Work.delete(id, a.pkg);
                                        if (error == null && !wipe) forgetAutoFreeze(a.pkg);
                                        main.post(() -> {
                                            if (error != null) toast(Tr.t(error));
                                            reload.run();
                                        });
                                    }))
                                    .setNegativeButton(Tr.t("Cancel"), null)
                                    .show();
                        } else {
                            worker.execute(() -> {
                                String error = which == 0 ? Work.launch(this, a.pkg)
                                        : Work.freeze(id, a.pkg, !a.frozen);
                                main.post(() -> {
                                    if (error != null) toast(Tr.t(error));
                                    reload.run();
                                });
                            });
                        }
                    })
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        push("Work apps", page);
        load.run();
    }

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
        // Roles (and the role command) are Android 10+.
        if (Build.VERSION.SDK_INT < 29) list.addView(note("Choosing default apps here needs Android 10 or newer. "
                + "Use Settings > Apps > Default apps instead."));
        for (String[] role : Build.VERSION.SDK_INT >= 29 ? ROLES : new String[0][]) {
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
                                        if (err != null) toast(Tr.t(err));
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
                        .setTitle(Tr.t(type[0]))
                        .setMessage(Tr.t(msg.toString()))
                        .setPositiveButton(Tr.t("OK"), null)
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
            main.post(() -> sub.setText(Tr.t(text)));
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
            main.post(() -> sub.setText(Tr.t(text)));
        });
    }

    private void openUnused() {
        LinearLayout page = vertical();
        page.setPadding(dp(16), dp(12), dp(16), 0);
        TextView help = text("Longest-unused apps first. Tap one to open its settings.", 13, false);
        setDim(help, 0.7f);
        page.addView(help);
        TextView loading = text("Reading usage…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Unused apps", page);

        Adapter<String[]> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(Tr.t(a[1]));
            row.second.setText(Tr.t(a[2]));
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
            if (!Tweak.usageAllowed(this) || used.isEmpty()) {
                // Without usage access every app would look "Never opened".
                main.post(() -> loading.setText(Tr.t("Couldn't read usage. Start Shizuku so TinyKit can turn it on.")));
                return;
            }
            long now = System.currentTimeMillis();
            for (App a : launchableApps()) {
                long last = used.containsKey(a.pkg) ? used.get(a.pkg) : 0L;
                String when = last == 0 ? "Never opened"
                        : "Last opened " + DateUtils.getRelativeTimeSpanString(last, now, DateUtils.DAY_IN_MILLIS);
                found.add(new String[]{a.pkg, a.label, when, String.valueOf(last)});
            }
            main.post(() -> {
                loading.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
                if (found.isEmpty()) loading.setText(Tr.t("Couldn't read usage. Start Shizuku so TinyKit can turn it on."));
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
            toast(Tr.t("Storage by app needs Android 8 or newer."));
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
            row.first.setText(Tr.t(a[1]));
            row.second.setText(Tr.t(a[2]));
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
                summary.setText(Tr.t(found.isEmpty()
                        ? "Couldn't measure storage. Start Shizuku so TinyKit can turn usage access on."
                        : "Apps use " + Formatter.formatShortFileSize(this, used) + " in total"));
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
        setDim(help, 0.7f);
        page.addView(help);
        TextView loading = text("Reading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Recent access", page);

        Adapter<String[]> adapter = new Adapter<>(false, (a, row) -> {
            row.first.setText(Tr.t(a[0]));
            row.second.setText(Tr.t(a[1] + "  ·  " + a[2]));
            showIcon(row, a[3]);
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> openAppInfo(adapter.getItem(position)[3]));
        worker.execute(() -> {
            List<String[]> rows = recentAccess();
            main.post(() -> {
                loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                if (rows.isEmpty()) loading.setText(Tr.t("This phone doesn't record recent access, or nothing recent."));
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
            toast(Tr.t("Couldn't open app settings."));
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
        setDim(help, 0.7f);
        page.addView(help);
        EditText search = searchField("Search apps");
        page.addView(search, fieldMargin());
        CheckBox system = new CheckBox(this);
        system.setText(Tr.t("Show system apps"));
        page.addView(system);
        final int[] sort = {0};
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Mixed audio", page);

        audioState.clear();
        List<App> all = new ArrayList<>();
        Adapter<App> adapter = new Adapter<>(false, (app, row) -> {
            row.first.setText(Tr.t(app.label));
            row.second.setText(Tr.t(audioStateText(app.pkg)));
            showIcon(row, app.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<App> shown = new ArrayList<>();
            for (App a : all) {
                boolean set = audioState.containsKey(a.pkg);
                if ((system.isChecked() || !a.system || set)
                        && (a.label.toLowerCase(Locale.ROOT).contains(q) || a.pkg.toLowerCase(Locale.ROOT).contains(q))) {
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
                .setTitle(Tr.t(app.label))
                .setItems(Tr.all(labels), (d, which) -> worker.execute(() -> {
                    String err = error(Shell.run(cmds[which]));
                    if (err != null) main.post(() -> toast(Tr.t(err)));
                    loadAudioState(refresh);
                }))
                .setNegativeButton(Tr.t("Cancel"), null)
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
                () -> {
                    String involved = sget("secure", "user_experience_involved");
                    String analytics = sget("secure", "hw_app_analytics_state");
                    // Neither key exists away from Huawei phones.
                    if (!Shell.ready() || ("null".equals(involved) && "null".equals(analytics))) return null;
                    return "0".equals(involved) && "0".equals(analytics);
                },
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
        // A read can take a second (some switches start a Java process), so reads run on their own thread,
        // never queue behind each other for the same switch, and never overwrite a newer toggle.
        java.util.concurrent.atomic.AtomicBoolean reading = new java.util.concurrent.atomic.AtomicBoolean();
        int[] toggled = {0};
        View row = row(list, title, summary, view, () -> {
            boolean block = !view.isChecked();
            view.setChecked(block);
            view.setEnabled(false);
            toggled[0]++;
            worker.execute(() -> {
                String err = applier.apply(block);
                Boolean now = checker.check();
                main.post(() -> {
                    view.setEnabled(true);
                    if (now != null) view.setChecked(now);
                    if (err != null) toast(Tr.t(err));
                });
            });
        }, true);
        TextView sub = (TextView) row.getTag();
        Runnable read = () -> {
            if (!reading.compareAndSet(false, true)) return;
            int version = toggled[0];
            reads.execute(() -> {
                try {
                    if (!Shell.ready()) return; // read again once Shizuku is usable
                    Boolean state = checker.check();
                    main.post(() -> {
                        if (version != toggled[0]) return;
                        showPrivacyState(row, view, sub, summary, state);
                    });
                } finally {
                    reading.set(false);
                }
            });
        };
        // Tab switches are read again whenever the settings are (Shizuku connecting, a tab opening).
        if (indexingTab >= 0) liveSwitches.add(read);
        read.run();
    }

    private void showPrivacyState(View row, Switch view, TextView sub, String summary, Boolean state) {
        if (state == null) {
            row.setEnabled(false);
            view.setEnabled(false);
            shizukuRows.remove(row);
            row.setAlpha(lightTheme ? 0.45f : 0.4f);
            sub.setText(Tr.t(summary + "  ·  Not on this phone"));
        } else {
            view.setChecked(state);
        }
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
        setDim(help, 0.7f);
        page.addView(help);
        LinearLayout buttons = new LinearLayout(this);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
        half.setMargins(0, dp(4), dp(8), dp(4));
        Button export = button("Export", null);
        Button importButton = button("Import", null);
        buttons.addView(export, half);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1f);
        second.setMargins(0, dp(4), 0, dp(4));
        buttons.addView(importButton, second);
        page.addView(buttons);
        EditText search = searchField("Search networks");
        page.addView(search, fieldMargin());
        TextView loading = text("Reading saved networks…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(this);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        push("Saved Wi-Fi", page);

        Adapter<String[]> adapter = new Adapter<>(false, (n, row) -> {
            row.first.setText(Tr.t(n[0]));
            row.second.setText(Tr.t("nopass".equals(n[1]) ? "Open network"
                    : n[2].isEmpty() ? n[1] : n[1] + "  ·  " + n[2]));
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
        Runnable[] reload = new Runnable[1];
        reload[0] = () -> worker.execute(() -> {
            List<String[]> nets = readWifi();
            main.post(() -> {
                all.clear();
                all.addAll(nets);
                filter.run();
                loading.setVisibility(nets.isEmpty() ? View.VISIBLE : View.GONE);
                if (nets.isEmpty()) {
                    loading.setText(Tr.t("No saved networks, or this phone doesn't allow reading them."));
                }
            });
        });
        reload[0].run();
        export.setOnClickListener(v -> {
            if (all.isEmpty()) {
                toast(Tr.t("No saved networks, or this phone doesn't allow reading them."));
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle(Tr.t("Export networks?"))
                    .setMessage(Tr.t("Saves every network with its password as plain text in Download/TinyKit. "
                            + "Anyone who gets the file can read the passwords, so delete it when you're done."))
                    .setPositiveButton(Tr.t("Export"), (d, w) -> worker.execute(() -> {
                        StringBuilder text = new StringBuilder();
                        for (String[] n : all) text.append(n[0]).append('\t').append(n[1]).append('\t').append(n[2]).append('\n');
                        String file = BACKUP_DIR + "/wifi-" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                                .format(new java.util.Date()) + ".txt";
                        Shell.Result r = Shell.run("mkdir -p " + BACKUP_DIR + "; cat > " + Shell.quote(file),
                                new java.io.ByteArrayInputStream(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        main.post(() -> toast(r.ok() ? Tr.t("Saved to ") + file : Tr.t("Couldn't save the file.")));
                    }))
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        importButton.setOnClickListener(v -> worker.execute(() -> {
            List<String> files = new ArrayList<>();
            for (String line : Shell.run("ls -1t " + BACKUP_DIR + "/wifi-*.txt 2>/dev/null").out.split("\n")) {
                if (!line.trim().isEmpty()) files.add(line.trim());
            }
            main.post(() -> {
                if (files.isEmpty()) {
                    toast(Tr.t("No exported networks yet. Export first, or put a wifi-….txt file in Download/TinyKit."));
                    return;
                }
                String[] names = new String[files.size()];
                for (int i = 0; i < names.length; i++) names[i] = files.get(i).substring(files.get(i).lastIndexOf('/') + 1);
                new AlertDialog.Builder(this)
                        .setTitle(Tr.t("Import which file?"))
                        .setItems(names, (d, which) -> worker.execute(() -> {
                            String apk = Shell.run("pm path " + getPackageName() + " | head -1 | sed 's/^package://'").out.trim();
                            Shell.Result r = Shell.run("CLASSPATH=" + Shell.quote(apk)
                                    + " app_process /system/bin io.github.order52.tinykit.WifiDump add "
                                    + Shell.quote(files.get(which)));
                            main.post(() -> {
                                toast(r.ok() ? Tr.t("Added ") + r.out.trim() + Tr.t(" networks that weren't saved yet.")
                                        : Tr.t("This phone doesn't let TinyKit add networks."));
                                reload[0].run();
                            });
                        }))
                        .setNegativeButton(Tr.t("Cancel"), null)
                        .show();
            });
        }));
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
                .setTitle(Tr.t(ssid))
                .setView(box)
                .setPositiveButton(Tr.t("Close"), null)
                .setNeutralButton(Tr.t("Copy password"), "nopass".equals(type) ? null : (d, w) -> {
                    ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                            .setPrimaryClip(android.content.ClipData.newPlainText("Wi-Fi password", password));
                    toast(Tr.t("Password copied"));
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
                    list.addView(note(Shell.ready() ? "This phone doesn't let apps change the quick panel."
                            : "Start Shizuku and allow TinyKit first."));
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
        Shell.Result r = Shell.run("settings get secure " + QS_TILES);
        // Without a real answer there is nothing to show, and nothing to save as the original layout.
        if (!r.ok()) return new ArrayList<>();
        String value = r.out.trim();
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
            shape.setColor(p == page ? (lightTheme ? 0xFF000000 : 0xFFFFFFFF) : (lightTheme ? 0x40000000 : 0x55FFFFFF));
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
            // Only TinyKit's own tiles: a drag from another app (split screen) carries no tile.
            if (!(e.getLocalState() instanceof String)) return false;
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
        shape.setColor(picked ? attrColor(android.R.attr.colorAccent) : (lightTheme ? 0xFFE0E0E4 : 0xFF262626));
        circle.setBackground(shape);
        circle.addView(tileIcon(tile, picked), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
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
        shape.setColor(picked ? attrColor(android.R.attr.colorAccent) : (lightTheme ? 0xFFE0E0E4 : 0xFF262626));
        cell.setBackground(shape);
        cell.setForeground(getDrawable(attrResource(android.R.attr.selectableItemBackground)));
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(20), dp(20));
        iconSize.setMargins(0, 0, dp(12), 0);
        cell.addView(tileIcon(tile, picked), iconSize);
        LinearLayout texts = vertical();
        TextView title = text(tile.title, 14, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView sub = text(tile.sub, 12, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        setDim(sub, 0.6f);
        texts.addView(sub);
        cell.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        return cell;
    }

    /** The tile's icon, or its first letter when there's no icon to show. */
    private View tileIcon(QsTile tile, boolean picked) {
        if (tile.icon == null) {
            TextView letter = text(tile.title.isEmpty() ? "?" : tile.title.substring(0, 1), 16, true);
            letter.setGravity(Gravity.CENTER);
            if (picked) letter.setTextColor(0xFFFFFFFF);
            return letter;
        }
        Drawable d = tile.icon.getConstantState() != null
                ? tile.icon.getConstantState().newDrawable(getResources()).mutate() : tile.icon.mutate();
        if (tile.tint) d.setTint(picked ? 0xFFFFFFFF : (lightTheme ? 0xFF000000 : 0xFFFFFFFF));
        ImageView view = new ImageView(this);
        view.setImageDrawable(d);
        return view;
    }

    private TextView tileArrow(String label, boolean enabled, Runnable onClick) {
        TextView view = text(label, 22, false);
        view.setGravity(Gravity.CENTER);
        view.setEnabled(enabled);
        view.setAlpha(enabled ? (lightTheme ? 1f : 0.9f) : (lightTheme ? 0.3f : 0.2f));
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
        if (from == to || from < 0 || from >= tiles.size() || to < 0 || to >= tiles.size()) return;
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
                toast(Tr.t(r.out.isEmpty() ? "Couldn't change the quick panel." : r.out));
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
        setDim(view, 0.6f);
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
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        chip.setTextColor(attrColor(android.R.attr.colorAccent));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(22));
        shape.setColor(lightTheme ? 0xFFFFFFFF : 0xFF161616);
        shape.setStroke(dp(1), lightTheme ? 0x26000000 : 0x40FFFFFF);
        chip.setBackground(shape);
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-2, -2);
        margin.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(margin);
        Runnable render = () -> chip.setText((icon.isEmpty() ? "" : icon) + Tr.t(title) + ": " + Tr.t(options[sel[0]]));
        chip.setOnClickListener(v -> {
            String[] items = new String[options.length];
            for (int i = 0; i < options.length; i++) {
                items[i] = (i == sel[0] ? "●  " : "○  ") + Tr.t(options[i]);
            }
            new AlertDialog.Builder(this)
                    .setTitle(Tr.t(title))
                    .setItems(items, (d, which) -> {
                        sel[0] = which;
                        render.run();
                        onSelect.run(which);
                    })
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        render.run();
        return chip;
    }

    // ---- Rows and widgets ---------------------------------------------------------------------------

    private void section(LinearLayout list, String title) {
        TextView view = text(Tr.t(title).toUpperCase(Locale.ROOT), 13, true);
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
                    if (!r.ok()) toast(Tr.t(r.out.isEmpty() ? "Failed." : r.out));
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
            status.setText(Tr.t("Working…"));
            worker.execute(() -> {
                String result = job.run();
                main.post(() -> {
                    holder[0].setEnabled(true);
                    status.setText(Tr.t(result));
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
        setDim(sub, 0.7f);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        if (end != null) row.addView(end);
        row.setTag(sub);
        if (indexingTab >= 0) {
            boolean opens = end instanceof TextView && !(end instanceof Switch);
            searchIndex.add(new Feature(title, summary, indexingTab, row, opens));
        }
        row.setOnClickListener(v -> {
            if (needsShizuku && !Shell.ready()) {
                toast(Tr.t("Start Shizuku and allow TinyKit first."));
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
        setDim(view, 0.5f);
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
            setDim(second, 0.7f);
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
        showIcon(row.icon, pkg);
    }

    private void showIcon(ImageView icon, String pkg) {
        icon.setVisibility(View.VISIBLE);
        icon.setTag(pkg);
        Bitmap cached = iconCache.get(pkg);
        if (cached != null) {
            icon.setImageBitmap(cached);
            return;
        }
        icon.setImageResource(android.R.drawable.sym_def_app_icon);
        thumbs.execute(() -> {
            // The row may have been recycled to another app while scrolling — skip the render for
            // one that's no longer on screen, so a fast scroll doesn't load hundreds of icons.
            if (!pkg.equals(icon.getTag()) || iconCache.containsKey(pkg)) return;
            Bitmap b = iconBitmap(pkg, dp(40));
            if (b == null) return;
            iconCache.put(pkg, b);
            main.post(() -> {
                if (pkg.equals(icon.getTag())) icon.setImageBitmap(b);
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
            row.third.setText(Tr.t(""));
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
        view.setText(Tr.t(value));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        if (lightTheme) view.setTextColor(0xFF000000);
        return view;
    }

    private void setDim(TextView view, float darkAlpha) {
        if (view == null) return;
        // On light, a grey from near-black (the lower the alpha, the lighter), so secondary text reads as
        // secondary and still passes contrast (about 6:1 at 0.6, 9:1 at 0.7).
        view.setAlpha(1f);
        if (lightTheme) {
            int grey = Math.round(230 * (1 - darkAlpha));
            view.setTextColor(0xFF000000 | grey << 16 | grey << 8 | grey);
        } else {
            view.setAlpha(darkAlpha);
        }
    }

    private Button button(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(Tr.t(label));
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setStateListAnimator(null); // no raised shadow: a flat pill like the rest of the app
        button.setBackground(pillBackground(lightTheme ? 0xFFE0E0E4 : 0xFF2C2C30));
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}}, 
                new int[]{lightTheme ? 0x8A000000 : 0x66FFFFFF, lightTheme ? 0xFF000000 : 0xFFFFFFFF}));
        return button;
    }

    /** The main button of a screen: filled with the accent colour. */
    private Button primary(Button button) {
        button.setBackground(pillBackground(attrColor(android.R.attr.colorAccent)));
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}}, 
                new int[]{lightTheme ? 0x8A000000 : 0x66FFFFFF, lightTheme ? 0xFFFFFFFF : 0xFF0D0D0D}));
        return button;
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radiusDp));
        shape.setColor(color);
        return shape;
    }

    /** A fully rounded button background that ripples when pressed and goes dark when disabled. */
    private android.graphics.drawable.Drawable pillBackground(int color) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, roundRect(lightTheme ? 0xFFEBEBEF : 0xFF1E1E22, 24));
        states.addState(new int[]{}, roundRect(color, 24));
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(lightTheme ? 0x20000000 : 0x33FFFFFF),
                states, roundRect(lightTheme ? 0xFFFFFFFF : 0xFF000000, 24));
    }

    /** One line at the top of a tab: dimmed tools need Shizuku. Shown only while it isn't usable. */
    private void shizukuNote(LinearLayout list) {
        TextView note = text("Dimmed tools need Shizuku. Start it on the first tab.", 13, false);
        note.setTextColor(attrColor(android.R.attr.colorAccent));
        note.setPadding(dp(16), dp(12), dp(16), dp(12));
        note.setBackground(roundRect(lightTheme ? 0xFFEBEBEF : 0xFF121212, 16));
        note.setVisibility(Shell.ready() ? View.GONE : View.VISIBLE);
        shizukuLabels.add(note);
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2);
        margin.setMargins(dp(12), dp(8), dp(12), 0);
        list.addView(note, margin);
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

    /** Runs a longer job on the worker behind a "Working…" dialog, then shows its result. */
    private void withProgress(String title, Job job) {
        TextView message = text("Working…", 15, false);
        message.setPadding(dp(24), dp(16), dp(24), dp(8));
        AlertDialog progress = new AlertDialog.Builder(this).setTitle(Tr.t(title))
                .setView(message).setCancelable(false).show();
        worker.execute(() -> {
            String result = job.run();
            main.post(() -> {
                if (!alive()) return;
                dismiss(progress);
                new AlertDialog.Builder(this).setTitle(Tr.t(title)).setMessage(Tr.t(result))
                        .setPositiveButton(Tr.t("OK"), null).show();
            });
        });
    }

    /** False once the screen is closing, so late results from a worker don't touch it. */
    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private static void dismiss(android.app.Dialog dialog) {
        try {
            dialog.dismiss();
        } catch (RuntimeException gone) {
            // Its window went away with a recreated screen.
        }
    }

    private void toast(String message) {
        Toast.makeText(this, Tr.t(message), Toast.LENGTH_LONG).show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            toast(Tr.t(url));
        }
    }

    private void openApp(String pkg) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) startActivity(launch);
    }
}
