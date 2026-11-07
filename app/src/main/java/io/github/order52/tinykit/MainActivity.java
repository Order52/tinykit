package io.github.order52.tinykit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    private final AnalyticsScreen analyticsScreen = new AnalyticsScreen(this);
    private final AnimationScreen animationScreen = new AnimationScreen(this);
    private final AppLanguageScreen appLanguageScreen = new AppLanguageScreen(this);
    private final AppToolsScreen appToolsScreen = new AppToolsScreen(this);
    private final BatteryScreen batteryScreen = new BatteryScreen(this);
    private final ChannelsScreen channelsScreen = new ChannelsScreen(this);
    private final ClipboardScreen clipboardScreen = new ClipboardScreen(this);
    private final CommandScreen commandScreen = new CommandScreen(this);
    private final CrashLogScreen crashLogScreen = new CrashLogScreen(this);
    private final DebloaterScreen debloaterScreen = new DebloaterScreen(this);
    private final DeepSleepScreen deepSleepScreen = new DeepSleepScreen(this);
    private final FirewallScreen firewallScreen = new FirewallScreen(this);
    private final InstallOptionsScreen installOptionsScreen = new InstallOptionsScreen(this);
    private final InstallScreen installScreen = new InstallScreen(this);
    private final InstalledScreen installedScreen = new InstalledScreen(this);
    private final MixedAudioScreen mixedAudioScreen = new MixedAudioScreen(this);
    private final MixerScreen mixerScreen = new MixerScreen(this);
    private final NetworkScreen networkScreen = new NetworkScreen(this);
    private final PermissionsScreen permissionsScreen = new PermissionsScreen(this);
    private final PhoneInfoScreen phoneInfoScreen = new PhoneInfoScreen(this);
    private final QuickPanelScreen quickPanelScreen = new QuickPanelScreen(this);
    private final RebootScreen rebootScreen = new RebootScreen(this);
    private final RecoverScreen recoverScreen = new RecoverScreen(this);
    private final ResetScreen resetScreen = new ResetScreen(this);
    private final RunningAppsScreen runningAppsScreen = new RunningAppsScreen(this);
    private final SavedWifiScreen savedWifiScreen = new SavedWifiScreen(this);
    private final ScreenTimeScreen screenTimeScreen = new ScreenTimeScreen(this);
    private final ServicesScreen servicesScreen = new ServicesScreen(this);
    private final ShutUpScreen shutUpScreen = new ShutUpScreen(this);
    private final SpecialAccessScreen specialAccessScreen = new SpecialAccessScreen(this);
    private final TilesCatalogScreen tilesCatalogScreen = new TilesCatalogScreen(this);
    private final TrackersScreen trackersScreen = new TrackersScreen(this);
    private final WorkProfileScreen workProfileScreen = new WorkProfileScreen(this);

    /** Opens TinyKit on this tab (the background notification opens the Info tab). */
    static final String EXTRA_TAB = "tab";
    /** Set by the Audio mixer tile: open the mixer page. */
    static final String EXTRA_MIXER = "mixer";
    /** Set by the Secure folder: open the work profile page. */
    static final String EXTRA_WORK = "work";
    private static final int REQUEST_SHIZUKU = 1;
    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    // thedjchi's fork adds a Watchdog that restarts Shizuku when Android kills it.
    private static final String SHIZUKU_DOWNLOAD = "https://github.com/thedjchi/Shizuku/releases/latest";
    private static final String SHIZUKU_ORIGINAL = "https://shizuku.rikka.app/download/";
    static final String REPO = "https://github.com/Order52/tinykit";
    private static final int REQUEST_NOTIFICATIONS = 2;
    private static final int REQUEST_RUN_COMMAND = 3;
    static final int REQUEST_PICK_APK = 4;
    private static final String[] TAB_NAMES = {"Shizuku", "Privacy", "Apps", "System", "Info"};
    private static final String DNS_HOST = "dns.adguard-dns.com";
    static final String BACKUP_DIR = "/sdcard/Download/TinyKit";
    private static final String READ_SETTINGS = "for k in development_settings_enabled "
            + "private_dns_mode private_dns_specifier wifi_scan_always_enabled "
            + "ble_scan_always_enabled tether_dun_required mobile_data_always_on; do "
            + "echo \"$k=$(settings get global $k)\"; done";
    static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    static final Pattern CPUINFO = Pattern.compile("^\\s*\\d+\\s+([\\d.]+)\\s+(\\S+)");
    static final Pattern MEMINFO = Pattern.compile("^\\s*([\\d,]+)K: (\\S+) \\(pid \\d+");

    interface IsOn {
        boolean test(Map<String, String> settings);
    }

    interface Command {
        String get(boolean on);
    }

    /** Applies a per-app change on the worker thread. Returns null on success, else the error. */
    interface AppChange {
        String apply(String pkg, boolean on);
    }

    interface Loader {
        Set<String> load();
    }

    interface Job {
        String run();
    }

    /** Reads whether something is currently blocked; null when it isn't available on this phone. */
    interface Checker {
        Boolean check();
    }

    /** Blocks or unblocks; returns null on success, else the error. Call off the main thread. */
    interface Applier {
        String apply(boolean block);
    }

    interface OnText {
        void run(String value);
    }

    interface InfoJob {
        List<String[]> run();
    }

    interface Binder<T> {
        void bind(T item, Row row);
    }

    static final class Toggle {
        Switch view;
        IsOn isOn;
    }

    static final class App {
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

    static final class Proc {
        String name;
        String label;
        String pkg;
        long kb;
        float cpu; // percent of the CPU in the recent past, from dumpsys cpuinfo
    }

    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Reads of switch states, kept off {@link #worker} because some take a second. */
    final ExecutorService reads = Executors.newSingleThreadExecutor();
    // Icons and recover-page thumbnails load off the main thread and are cached as small bitmaps,
    // both bounded (so memory stays low) and cleared when the app is sent to the background.
    final ExecutorService thumbs = Executors.newFixedThreadPool(3);
    final Map<String, Bitmap> thumbCache = Collections.synchronizedMap(
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
    final List<Runnable> liveSwitches = new ArrayList<>();
    // Every row of the five tabs, for the search on the home tab. Filled while the tabs are built.
    private final List<Feature> searchIndex = new ArrayList<>();
    int indexingTab = -1;
    // Rows that need Shizuku, dimmed while it isn't usable. Weak, so rows of closed pages can go.
    final Set<View> shizukuRows = Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Set<View> shizukuLabels = Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private Switch updateSwitch;
    final List<View> pages = new ArrayList<>();
    private final List<CharSequence> titles = new ArrayList<>();
    final Map<String, String> labels = new HashMap<>();
    // Mixed audio state per package: {muted 0/1, plays-with-others 0/1}.
    final Map<String, int[]> audioState = new HashMap<>();
    private Map<String, String> settings = new HashMap<>();
    volatile List<App> apps;
    final Object appsLock = new Object();
    SharedPreferences prefs;
    boolean lightTheme;
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
    private boolean starting, stopping, askedNotifications, termuxChecked, askedTermux, askedTermuxSetup, termuxResume,
            autoStartPending, termuxProbe;
    private long startedAt;
    private static final String PREF_TERMUX_OFFERED = "termux_offered";

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
        SecureFolder.sync(this, prefs); // the Secure folder icon, if that is the look chosen (not only once the page is opened)
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
        int tab = Math.max(0, Math.min(savedInstanceState != null ? savedInstanceState.getInt("tab", prefs.getInt("tab", 0))
                : getIntent().getIntExtra(EXTRA_TAB, prefs.getInt("tab", 0)), TAB_NAMES.length - 1));
        // Only the tab on screen is built before the first frame; the others follow, one per turn of the
        // main loop, and any of them is built on the spot if it is needed sooner (a tap, the search).
        buildTab(0); // Shizuku's status views are updated whenever its binder comes or goes
        selectTab(tab);
        if (savedInstanceState != null && savedInstanceState.getInt("scrollY", 0) > 0) {
            ScrollView sv = scrollOf(pages.get(pages.size() - 1));
            int y = savedInstanceState.getInt("scrollY");
            if (sv != null) { // back to the same spot as soon as the list has its height
                sv.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        if (sv.getChildAt(0) == null || sv.getChildAt(0).getHeight() < y) return true;
                        sv.getViewTreeObserver().removeOnPreDrawListener(this);
                        sv.scrollTo(0, y);
                        return true;
                    }
                });
            }
        }
        if (getIntent().getBooleanExtra(EXTRA_WORK, false)) {
            getIntent().removeExtra(EXTRA_WORK);
            main.post(this::openWork);
        }
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
        autoStartPending = savedInstanceState == null;
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
        nudged(this::refreshShizuku); // Shizuku may have started while TinyKit was away
        if (autoStartPending) {
            autoStartPending = false;
            // Shizuku's connection can take a moment to arrive when TinyKit has just started: look again before starting it.
            main.postDelayed(() -> {
                if (alive() && !starting && !Shell.running() && !Banking.on(prefs) && prefs.getBoolean(ShizukuControl.PREF_AUTO, false)
                        && ShizukuControl.canRestart(this, prefs)) {
                    if (ShizukuControl.intents(this, prefs)) ShizukuControl.start(this, prefs);
                    else AdbService.begin(this, true);
                }
            }, 2500);
        }
        if (termuxResume) { // back from Termux, which has read its settings again
            termuxResume = false;
            termuxChecked = false;
            termuxProbe = true; // see whether Termux took the setting
            main.postDelayed(this::startShizuku, 500);
        }
        refreshShizuku();
    }

    /** The tab's scroll position goes with the activity when the theme changes, so the screen comes back where it was. */
    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        ScrollView sv = pages.isEmpty() ? null : scrollOf(pages.get(pages.size() - 1));
        if (sv != null) out.putInt("scrollY", sv.getScrollY());
        out.putInt("tab", prefs.getInt("tab", 0));
        out.putInt("depth", pages.size());
    }

    private static ScrollView scrollOf(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                ScrollView found = scrollOf(((ViewGroup) v).getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Opened again from the background notification: go to the tab it asks for. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("termux_done", false)) { // the pasted command finished
            termuxResume = false;
            termuxChecked = false;
            termuxProbe = true;
            main.postDelayed(this::startShizuku, 500);
            return;
        }
        if (intent.hasExtra(EXTRA_TAB)) {
            selectTab(Math.max(0, Math.min(intent.getIntExtra(EXTRA_TAB, 0), TAB_NAMES.length - 1)));
        }
        if (intent.getBooleanExtra(EXTRA_WORK, false)) {
            selectTab(2);
            openWork();
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
        main.removeCallbacksAndMessages(null);
        worker.shutdown();
        thumbs.shutdownNow();
        super.onDestroy();
    }

    // ---- Pages ----------------------------------------------------------------------

    void push(CharSequence title, View page) {
        pages.add(page);
        titles.add(title);
        show();
    }

    void show() {
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
    android.graphics.drawable.RippleDrawable pill(boolean selected) {
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
        if (!alive() || worker.isShutdown()) return; // a callback that arrives after the screen is gone
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
            String step = stopping ? "" : AdbService.step;
            shizukuText.setText(Tr.t(stopping ? "Stopping Shizuku…" : "Starting Shizuku…")
                    + (step.isEmpty() || step.startsWith("Starting") ? "" : "\n" + Tr.t(step)));
        }
        statusDot(stopping || starting ? 0xFFFFB74D : ready ? 0xFF66BB6A : running ? 0xFFFFB74D : 0xFFEF5350);
        startButton.setEnabled(installed && !stopping && (starting || !running));
        startButton.setText(Tr.t(starting ? "Cancel" : "Start"));
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
            prefs.edit().putBoolean(PREF_TERMUX_OFFERED, true).apply(); // said no: not asked again on every tap
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
                } else if (r.allowMissing && !askedTermuxSetup && !prefs.getBoolean(PREF_TERMUX_OFFERED, false)) {
                    askedTermuxSetup = true;
                    prefs.edit().putBoolean(PREF_TERMUX_OFFERED, true).apply(); // Termux opens for this once, not on every tap
                    // The one command, copied: pasting it in Termux sets everything up and brings TinyKit back.
                    if (Termux.setUp(this)) {
                        termuxResume = true;
                        toast(Tr.t("Command copied. Paste it in Termux (hold, Paste) and press Enter."));
                    } else {
                        termuxChecked = true;
                        startShizuku();
                    }
                } else {
                    prefs.edit().putBoolean(PREF_TERMUX_OFFERED, true).apply();
                    termuxChecked = true; // Termux can't be used right now: the other way
                    startShizuku();
                }
                termuxProbe = false;
            });
        });
    }

    /** Asks Shizuku for its connection (a call into its app, so off the main thread), then runs {@code after} on the main thread. */
    private void nudged(Runnable after) {
        if (worker.isShutdown()) return;
        worker.execute(() -> {
            Shell.nudge(this);
            main.post(after);
        });
    }

    /** While a start is under way, asks Shizuku for its connection every second, so the card never lags behind. */
    private void watchBinder() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!alive() || !starting) return;
                // TinyKit's own start has ended and said nothing we heard (it can end before the screen listens): don't wait on it.
                if (!AdbService.active && !ShizukuControl.intents(MainActivity.this, prefs)
                        && SystemClock.elapsedRealtime() - startedAt > 3000) {
                    nudged(() -> {
                        if (starting && !AdbService.active) startFinished(Shell.running() ? null : AdbService.lastError);
                    });
                    return;
                }
                nudged(MainActivity.this::refreshShizuku); // off the main thread: it is a call into Shizuku's app
                main.postDelayed(this, 1000);
            }
        }, 1000);
    }

    /** Ends a start that is taking too long, so Start can be tried again. */
    private void cancelStart() {
        AdbService.onFinish = null;
        AdbService.cancel();
        main.removeCallbacks(startTimeout);
        starting = false;
        refreshShizuku();
    }

    private void startShizuku() {
        if (Banking.on(prefs)) {
            toast(Tr.t("Banking mode is on. Turn it off (Privacy tab) to start Shizuku."));
            return;
        }
        if (starting) return;
        if (Shell.running()) { // already up: starting it again would only restart it
            refreshShizuku();
            return;
        }
        if (!ShizukuControl.intents(this, prefs)) {
            // Termux is offered once (its setup opens Termux), then used if it was set up, and left alone if not.
            if (Termux.installed(this) && !termuxChecked && !prefs.getBoolean(AdbService.PREF_TERMUX, false)
                    && (termuxProbe || !prefs.getBoolean(PREF_TERMUX_OFFERED, false))) {
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
        startedAt = SystemClock.elapsedRealtime();
        refreshShizuku();
        AdbService.onFinish = message -> main.post(() -> {
            if (!starting) return;
            nudged(() -> {
                if (!starting) return;
                if (message == null || Shell.running()) { // it is up: the card follows by itself, and at once
                    starting = false;
                    main.removeCallbacks(startTimeout);
                    refreshShizuku();
                } else {
                    startFinished(message);
                }
            });
        });
        watchBinder();
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

    String token() {
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
            if (starting) { // a start that takes too long can always be ended, and tried again
                cancelStart();
                return;
            }
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
        Switch autoStart = new Switch(this);
        autoStart.setChecked(prefs.getBoolean(ShizukuControl.PREF_AUTO, false));
        autoStart.setClickable(false);
        autoStart.setFocusable(false);
        row(list, "Start by itself", "After a restart, and when you open TinyKit, start Shizuku if that needs nobody "
                + "(it has worked before, or the auth token is saved).", autoStart, () -> {
            boolean on = !autoStart.isChecked();
            autoStart.setChecked(on);
            prefs.edit().putBoolean(ShizukuControl.PREF_AUTO, on).apply();
        }, false);

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
    EditText searchField(String hint) {
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

    LinearLayout.LayoutParams fieldMargin() {
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
    static final class Feature {
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
    void addTile(Class<?> service, String label, int icon, String about) {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                    new ComponentName(this, service), Tr.t(label),
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

    View scroll(LinearLayout list) {
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
        Switch bankingSwitch = new Switch(this);
        bankingSwitch.setClickable(false);
        bankingSwitch.setFocusable(false);
        TextView bankingStatus = text("", 13, false);
        bankingStatus.setPadding(dp(16), dp(0), dp(16), dp(8));
        Runnable showBanking = () -> {
            boolean on = Banking.on(prefs);
            bankingSwitch.setChecked(on);
            String left = on ? Banking.stillOn(this) : "";
            bankingStatus.setText(!on ? Tr.t("Off. Nothing is changed.")
                    : !left.isEmpty() ? Tr.t("On, but still on: ") + translatedList(left) + Tr.t(". Tap here to turn them off again.")
                    : Tr.t(prefs.getBoolean(Banking.PREF_SHIZUKU, false) && !Shell.running()
                    ? "On. Developer options and debugging are off, and Shizuku is stopped until you turn this off."
                    : "On. Developer options, USB debugging and Wireless debugging are off."));
        };
        showBanking.run();
        bankingStatus.setOnClickListener(v -> {
            if (!Banking.on(prefs)) return;
            worker.execute(() -> {
                Banking.reapply(this, prefs);
                main.post(showBanking);
            });
        });
        // Whatever came back on while Banking mode is on goes off again when TinyKit opens this page.
        if (Banking.on(prefs)) worker.execute(() -> {
            Banking.reapply(this, prefs);
            main.post(showBanking);
        });
        row(list, "Banking mode", "One switch for banking and payment apps that refuse to run on a phone with "
                + "developer options or debugging on, or with Shizuku running. Turns them off and stops Shizuku. "
                + "Turn it off and everything comes back as it was, Shizuku included.", bankingSwitch, () -> {
                    boolean on = !Banking.on(prefs);
                    bankingSwitch.setChecked(on);
                    worker.execute(() -> {
                        String error = on ? Banking.enable(this, prefs) : Banking.disable(this, prefs);
                        main.post(() -> {
                            showBanking.run();
                            refreshShizuku();
                            if (error == null) return;
                            if (error.startsWith("Start Shizuku")) {
                                new AlertDialog.Builder(this).setTitle(Tr.t("Banking mode needs Shizuku once"))
                                        .setMessage(Tr.t("TinyKit has to be allowed to change these settings, and only Shizuku "
                                                + "can allow it. Start Shizuku, then turn Banking mode on again. After "
                                                + "that it works without Shizuku."))
                                        .setPositiveButton(Tr.t("Start Shizuku"), (d, w) -> startShizuku())
                                        .setNegativeButton(Tr.t("Cancel"), null).show();
                            } else {
                                new AlertDialog.Builder(this).setTitle(Tr.t("Banking mode"))
                                        .setMessage(Tr.t(error)).setPositiveButton(Tr.t("OK"), null).show();
                            }
                        });
                    });
                }, false);
        list.addView(bankingStatus);
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
        link(list, "Running services", "Every service running now, with its app. Stop one, or block it for good.",
                this::openServices);
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
        link(list, "Deep sleep", "Make the phone doze sooner after the screen goes off, and wake up less often, "
                + "like Comatose. Pick a profile, or doze right now.", this::openDoze);
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
        link(list, "Send feedback", "Ask for a feature, report a problem or say anything. It opens GitHub, ready to send.",
                this::openFeedback);
        Switch lightThemeSwitch = new Switch(this);
        lightThemeSwitch.setChecked(lightTheme);
        lightThemeSwitch.setClickable(false);
        lightThemeSwitch.setFocusable(false);
        row(list, "Light theme", "Use a light background instead of OLED black.", lightThemeSwitch, () -> {
            boolean on = !lightThemeSwitch.isChecked();
            lightThemeSwitch.setChecked(on);
            prefs.edit().putBoolean("light_theme", on).apply();
            recreate(); // same tab, same scroll position (see onSaveInstanceState)
            overridePendingTransition(0, 0); // no slide: the colours just change
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
    void afterServiceChange(Runnable then) {
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

    static String restore(String key, String value) {
        if (value == null || value.equals("null") || value.equals(DNS_HOST)) {
            return "settings delete global " + key;
        }
        return "settings put global " + key + " " + Shell.quote(value);
    }

    static String state(int uid, boolean work, String pkg, Set<Integer> playing, List<Integer> open) {
        String s = (work ? Tr.t("Work profile") : "");
        String what = playing.contains(uid) ? Tr.t("Playing now") : open.contains(uid) ? Tr.t("Open") : "";
        if (!what.isEmpty()) s += (s.isEmpty() ? "" : " · ") + what;
        return s.isEmpty() && pkg != null ? pkg : s;
    }

    // ---- Debloater --------------------------------------------------------------------------------

    static final class Pkg {
        String pkg;
        String label;
        String note;
        boolean system;
        boolean checked;
        boolean locked;
        int level;
    }

    // ---- Install APK files ----------------------------------------------------------------

    /** The user the picked files go to: the work profile's id, or -1 for the usual install. */
    int pickUser = -1;

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

    static Set<String> packages(String out) {
        Set<String> set = new HashSet<>();
        for (String line : out.split("\n")) {
            if (PACKAGE.matcher(line.trim()).matches()) set.add(line.trim());
        }
        return set;
    }

    static String error(Shell.Result r) {
        return r.ok() ? null : (r.out.isEmpty() ? "Failed." : r.out);
    }

    static String batteryStatus(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            default: return "Unknown";
        }
    }

    static String batteryHealth(int health) {
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

    /** The app_process command that toggles or reads all-sensors privacy. arg is on | off | state. */
    static String sensorsCmd(Context c, String arg) {
        return "CLASSPATH=" + c.getPackageCodePath()
                + " app_process /system/bin io.github.order52.tinykit.Sensors " + arg;
    }

    private static String translatedList(String commaList) {
        String[] parts = commaList.split(", ");
        for (int i = 0; i < parts.length; i++) parts[i] = Tr.t(parts[i]);
        return TextUtils.join(", ", parts);
    }

    interface OnPage {
        void run(int page);
    }

    static int pages(int count, int perPage) {
        return Math.max(1, (count + perPage - 1) / perPage);
    }

    // ---- Rows and widgets ---------------------------------------------------------------------------

    void section(LinearLayout list, String title) {
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

    void link(LinearLayout list, String title, String summary, Runnable open) {
        row(list, title, summary, arrow(), open, true);
    }

    /** A row that runs a job and shows its result in place of the description. */
    void action(LinearLayout list, String title, String summary, Job job) {
        action(list, title, summary, true, job);
    }

    void action(LinearLayout list, String title, String summary, boolean needsShizuku, Job job) {
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

    View row(LinearLayout list, String title, String summary, View end, Runnable onClick,
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

    TextView arrow() {
        TextView view = text("›", 24, false);
        setDim(view, 0.5f);
        view.setPadding(dp(12), 0, 0, 0);
        return view;
    }

    /** A list row with up to three lines and an optional checkbox. */
    final class Row {
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
    void showIcon(Row row, String pkg) {
        showIcon(row.icon, pkg);
    }

    void showIcon(ImageView icon, String pkg) {
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
    Bitmap iconBitmap(String pkg, int size) {
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

    final class Adapter<T> extends BaseAdapter {
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

    LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(Tr.t(value));
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        if (lightTheme) view.setTextColor(0xFF000000);
        return view;
    }

    void setDim(TextView view, float darkAlpha) {
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

    Button button(String label, View.OnClickListener listener) {
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
    Button primary(Button button) {
        button.setBackground(pillBackground(attrColor(android.R.attr.colorAccent)));
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}}, 
                new int[]{lightTheme ? 0x8A000000 : 0x66FFFFFF, lightTheme ? 0xFFFFFFFF : 0xFF0D0D0D}));
        return button;
    }

    GradientDrawable roundRect(int color, int radiusDp) {
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

    LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    static TextWatcher watcher(Runnable onChange) {
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

    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    int attrResource(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }

    int attrColor(int attr) {
        TypedValue value = new TypedValue();
        getTheme().resolveAttribute(attr, value, true);
        return value.data;
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    String label(String pkg) {
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

    String versionName(String pkg) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(pkg, 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static String fileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|'\\s]+", "_");
    }

    /** Runs a longer job on the worker behind a "Working…" dialog, then shows its result. */
    void withProgress(String title, Job job) {
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
    boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    static void dismiss(android.app.Dialog dialog) {
        try {
            dialog.dismiss();
        } catch (RuntimeException gone) {
            // Its window went away with a recreated screen.
        }
    }

    void toast(String message) {
        Toast.makeText(this, Tr.t(message), Toast.LENGTH_LONG).show();
    }

    void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            toast(Tr.t(url));
        }
    }

    void openApp(String pkg) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null) startActivity(launch);
    }

    // ---- Screens used by other screens ----

    void openAnalytics() {
        analyticsScreen.openAnalytics();
    }

    void privacySwitch(LinearLayout list, String title, String summary, Checker checker, Applier applier) {
        analyticsScreen.privacySwitch(list, title, summary, checker, applier);
    }

    Set<String> disabledPackages() {
        return analyticsScreen.disabledPackages();
    }
    void refreshAnimationSpeed(View row) {
        animationScreen.refreshAnimationSpeed(row);
    }

    void chooseAnimationSpeed(View row) {
        animationScreen.chooseAnimationSpeed(row);
    }
    void openAppLanguage() {
        appLanguageScreen.openAppLanguage();
    }
    void openBackground() {
        appToolsScreen.openBackground();
    }

    String forceDark(boolean on, Shell.Result r) {
        return appToolsScreen.forceDark(on, r);
    }

    String noOverlays(boolean on, Shell.Result r) {
        return appToolsScreen.noOverlays(on, r);
    }

    void openKeepAlive() {
        appToolsScreen.openKeepAlive();
    }

    void openSharePins() {
        appToolsScreen.openSharePins();
    }
    void openBatteryUsage() {
        batteryScreen.openBatteryUsage();
    }

    void openWakeups() {
        batteryScreen.openWakeups();
    }

    void openVibration() {
        batteryScreen.openVibration();
    }

    void openAppLog() {
        batteryScreen.openAppLog();
    }
    void openChannelsApp() {
        channelsScreen.openChannelsApp();
    }
    void openClipboardGuard() {
        clipboardScreen.openClipboardGuard();
    }
    void openRunCommand() {
        commandScreen.openRunCommand();
    }
    void openCrashes() {
        crashLogScreen.openCrashes();
    }
    List<Pkg> debloatList(boolean removed) {
        return debloaterScreen.debloatList(removed);
    }

    void openDebloat() {
        debloaterScreen.openDebloat();
    }
    void openDoze() {
        deepSleepScreen.openDoze();
    }
    void openFirewall() {
        firewallScreen.openFirewall();
    }
    String installOptionsSummary() {
        return installOptionsScreen.installOptionsSummary();
    }

    void chooseInstallOptions(TextView summary) {
        installOptionsScreen.chooseInstallOptions(summary);
    }
    void pickApks(int user) {
        installScreen.pickApks(user);
    }

    void openBackup() {
        installScreen.openBackup();
    }

    void openPicker(String title, String help, boolean checkable, Loader loader, AppChange change) {
        installScreen.openPicker(title, help, checkable, loader, change);
    }

    void openPicker(String title, String help, boolean checkable, Loader loader, AppChange change, Loader eligible) {
        installScreen.openPicker(title, help, checkable, loader, change, eligible);
    }

    void openPicker(String title, String help, boolean checkable, Loader loader, AppChange change, Loader eligible, boolean internetFilter) {
        installScreen.openPicker(title, help, checkable, loader, change, eligible, internetFilter);
    }

    void reloadApps() {
        installScreen.reloadApps();
    }

    boolean appsLoaded() {
        return installScreen.appsLoaded();
    }

    List<App> installedApps() {
        return installScreen.installedApps();
    }
    void openInstallHistory() {
        installedScreen.openInstallHistory();
    }
    void openMixedAudio() {
        mixedAudioScreen.openMixedAudio();
    }
    void openMixer() {
        mixerScreen.openMixer();
    }
    void openNetworkInfo() {
        networkScreen.openNetworkInfo();
    }

    void openChargingInfo() {
        networkScreen.openChargingInfo();
    }

    void openTemperatures() {
        networkScreen.openTemperatures();
    }

    void openDeviceIds() {
        networkScreen.openDeviceIds();
    }
    void openPermissionTypes() {
        permissionsScreen.openPermissionTypes();
    }

    void openAppList(String title, String help, OnText onPick) {
        permissionsScreen.openAppList(title, help, onPick);
    }

    void openAppPage(String pkg) {
        permissionsScreen.openAppPage(pkg);
    }
    void openInfo() {
        phoneInfoScreen.openInfo();
    }

    void infoPage(String title, InfoJob job) {
        phoneInfoScreen.infoPage(title, job);
    }

    void openLog(String title, String command, String empty) {
        phoneInfoScreen.openLog(title, command, empty);
    }

    void autoCheckForUpdate() {
        phoneInfoScreen.autoCheckForUpdate();
    }

    void addBoostWidget() {
        phoneInfoScreen.addBoostWidget();
    }

    void checkForUpdate(View row) {
        phoneInfoScreen.checkForUpdate(row);
    }

    void showAbout() {
        phoneInfoScreen.showAbout();
    }
    void openQuickPanel() {
        quickPanelScreen.openQuickPanel();
    }

    Drawable load(Context ui, String name, String pkg) {
        return quickPanelScreen.load(ui, name, pkg);
    }

    TextView note(String message) {
        return quickPanelScreen.note(message);
    }

    View sortBar(String[] options, OnPage onSelect) {
        return quickPanelScreen.sortBar(options, onSelect);
    }

    View sortBarLike(View chip) {
        return quickPanelScreen.sortBarLike(chip);
    }

    TextView chip(String icon, String title, String[] options, OnPage onSelect) {
        return quickPanelScreen.chip(icon, title, options, onSelect);
    }
    void openReboot() {
        rebootScreen.openReboot();
    }
    void openRecover() {
        recoverScreen.openRecover();
    }
    void confirmResetEverything() {
        resetScreen.confirmResetEverything();
    }

    void confirmResetPermissions() {
        resetScreen.confirmResetPermissions();
    }

    void openBackupRestore() {
        resetScreen.openBackupRestore();
    }
    void openRunning() {
        runningAppsScreen.openRunning();
    }
    void openWifi() {
        savedWifiScreen.openWifi();
    }

    void openFeedback() {
        savedWifiScreen.openFeedback();
    }
    void openScreenTime() {
        screenTimeScreen.openScreenTime();
    }
    void openServices() {
        servicesScreen.openServices();
    }
    void openShutUp() {
        shutUpScreen.openShutUp();
    }
    void openSpecialAccess() {
        specialAccessScreen.openSpecialAccess();
    }
    void openTiles() {
        tilesCatalogScreen.openTiles();
    }
    void openTrackers() {
        trackersScreen.openTrackers();
    }
    void openWork() {
        workProfileScreen.openWork();
    }

    void openHide() {
        workProfileScreen.openHide();
    }

    void openDefaults() {
        workProfileScreen.openDefaults();
    }

    void openUnused() {
        workProfileScreen.openUnused();
    }

    void openStorage() {
        workProfileScreen.openStorage();
    }

    void openRecentAccess() {
        workProfileScreen.openRecentAccess();
    }

    void openAppInfo(String pkg) {
        workProfileScreen.openAppInfo(pkg);
    }
}
