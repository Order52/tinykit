package io.github.order52.tinykit;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.format.Formatter;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
    private static final String SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/";
    private static final String SHIZUKU_TERMUX = "https://github.com/Order52/shizuku-termux";
    private static final String TERMUX = "com.termux";
    private static final String TERMUX_BIN = "/data/data/com.termux/files/usr/bin/";
    private static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    private static final String ACTION_TERMUX_RESULT = "io.github.order52.tinykit.TERMUX_RESULT";
    private static final int REQUEST_RUN_COMMAND = 2;
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
    private TextView shizukuText;
    private Button shizukuButton;
    private Button startButton;
    private boolean starting;

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> main.post(this::refreshShizuku);
    private final Shizuku.OnBinderDeadListener binderDead = () -> main.post(this::refreshShizuku);
    private final Shizuku.OnRequestPermissionResultListener permissionResult =
            (code, result) -> main.post(this::refreshShizuku);
    private final Runnable startTimeout = () -> startFinished(
            "No answer from Termux. Open Termux once, then try again.");
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
        frame = new FrameLayout(this);
        setContentView(frame);
        push(getString(R.string.app_name), buildHome());
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
            if (pages.size() == 1) loadSettings();
        } else {
            super.onBackPressed();
        }
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
            shizukuButton.setOnClickListener(v -> openUrl(SHIZUKU_DOWNLOAD));
        } else if (running && !ready) {
            shizukuText.setText("Shizuku is running. Allow TinyKit to use it.");
            shizukuButton.setText("Allow");
            shizukuButton.setOnClickListener(v -> Shizuku.requestPermission(REQUEST_SHIZUKU));
        } else {
            shizukuText.setText(running ? "Shizuku is running  ✓" : "Shizuku is not running");
            shizukuButton.setText("Open Shizuku");
            shizukuButton.setOnClickListener(v -> openApp(SHIZUKU_PKG));
        }
        startButton.setVisibility(installed ? View.VISIBLE : View.GONE);
        startButton.setEnabled(!starting && !running);
        startButton.setText(starting ? "Starting…" : "Start Shizuku");
        for (View row : shizukuRows) row.setAlpha(ready ? 1f : 0.4f);
        if (ready) loadSettings();
    }

    /**
     * Starts Shizuku without a computer by asking Termux to run the shizuku-termux command,
     * the same way the Shizuku Termux app does. The result comes back to {@link #termuxResult}.
     */
    private void startShizuku() {
        if (starting) return;
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

    /** Home: the Shizuku card and one row per category, so it fits on one screen. */
    private View buildHome() {
        LinearLayout home = vertical();

        LinearLayout card = vertical();
        card.setPadding(dp(16), dp(16), dp(16), dp(8));
        card.setBackgroundColor(0x22808080);
        shizukuText = text("", 15, false);
        shizukuButton = button("", null);
        startButton = button("Start Shizuku", v -> startShizuku());
        card.addView(shizukuText);
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(startButton, wrap());
        buttons.addView(shizukuButton, wrap());
        card.addView(buttons);
        home.addView(card);

        category(home, "Privacy", "Developer options, ad-blocking DNS, scanning, camera & mic",
                buildPrivacy());
        category(home, "Apps", "Running apps, background limits, keep alive, APK backup", buildApps());
        category(home, "Clean & speed", "Clear caches, optimize apps, trim storage", buildClean());
        category(home, "Network & battery", "Free hotspot, mobile data on Wi-Fi", buildNetwork());
        category(home, "Info & logs", "Phone info, crash log", buildInfo());
        return scroll(home);
    }

    private void category(LinearLayout home, String title, String summary, View page) {
        row(home, title, summary, arrow(), () -> {
            push(title, page);
            loadSettings();
        }, false);
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

    private View buildClean() {
        LinearLayout list = vertical();
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

        return scroll(list);
    }

    private View buildNetwork() {
        LinearLayout list = vertical();
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

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-2, -2);
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
