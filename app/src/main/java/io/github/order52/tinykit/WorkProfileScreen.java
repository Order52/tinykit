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
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WorkProfileScreen {
    private final MainActivity tk;

    WorkProfileScreen(MainActivity tk) {
        this.tk = tk;
    }

    static final String[] WORK_WAITS = {"Right away", "After 1 minute", "After 5 minutes", "After 15 minutes"};

    void openWork() {
        LinearLayout body = tk.vertical();
        View page = tk.scroll(body);
        Runnable[] again = new Runnable[1];
        // Runs when the page opens and every time it comes back into view.
        again[0] = () -> tk.worker.execute(() -> {
            int id = -1;
            int state = Work.RUNNING;
            List<Work.App> apps = new ArrayList<>();
            String lock = null;
            boolean lockOk = false;
            try {
                if (Shell.ready() && Work.supported(tk)) {
                    id = Work.id(tk);
                    if (id >= 0) {
                        state = Work.state(tk, id);
                        apps = Work.apps(tk, id);
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
            tk.main.post(() -> renderWork(body, shownId, shownState, shownApps, shownLockOk, shownLock, again[0]));
        });
        body.addView(tk.note("Loading…"));
        page.setTag(again[0]);
        tk.push("Work profile", page);
    }

    /** {@code lockOk}: this Android can give the profile a PIN; {@code lock} is what locks it now, when known. */
    void renderWork(LinearLayout body, int id, int state, List<Work.App> apps, boolean lockOk, String lock,
                            Runnable again) {
        body.removeAllViews();
        if (!Work.supported(tk)) {
            body.addView(tk.note("This phone can't have a work profile: it needs Android 8 or newer, and work "
                    + "profile support from its maker."));
            return;
        }
        if (id < 0) {
            body.addView(tk.note("A second, separate space for apps, with their own accounts and data. Android allows one. "
                    + "Pick how you want it:"));
            tk.row(body, "Secure folder", "Like Samsung's: its own icon opens your work apps behind the lock screen, and "
                    + "they stay out of the launcher.", tk.arrow(), () -> confirmCreate(true, again), true);
            tk.row(body, "Work profile", "The plain Android one: its apps show in the launcher with a badge.", tk.arrow(),
                    () -> confirmCreate(false, again), true);
            tk.prefs.edit().putBoolean(SecureFolder.PREF_EXISTS, false).apply();
            SecureFolder.sync(tk, tk.prefs); // no profile: no icon
            return;
        }
        final boolean secure = SecureFolder.secure(tk.prefs);
        tk.prefs.edit().putBoolean(SecureFolder.PREF_EXISTS, true).apply();
        SecureFolder.sync(tk, tk.prefs);
        if (secure && tk.prefs.getBoolean(SecureFolder.PREF_HIDE, false)) {
            // Hiding is on: any app that isn't frozen yet (the profile's own, once they have arrived) is frozen now.
            List<String> todo = new ArrayList<>();
            for (Work.App a : apps) if (!a.frozen) todo.add(a.pkg);
            if (!todo.isEmpty()) {
                Set<String> auto = Work.auto(tk.prefs);
                auto.addAll(todo);
                tk.prefs.edit().putStringSet(Work.PREF_AUTO, auto).apply();
                tk.worker.execute(() -> {
                    Work.freezeAll(id, todo, true);
                    BackgroundService.sync(tk);
                    tk.main.post(again);
                });
            }
        }
        int frozen = 0;
        for (Work.App a : apps) if (a.frozen) frozen++;
        TextView status = tk.text(Tr.t(state == Work.PAUSED ? "Paused" : state == Work.LOCKED ? "Locked" : "Running")
                + " · " + apps.size() + " " + Tr.t(apps.size() == 1 ? "app" : "apps")
                + (frozen > 0 ? " · " + frozen + " " + Tr.t("frozen") : ""), 16, true);
        status.setTextColor(tk.attrColor(android.R.attr.colorAccent));
        status.setPadding(tk.dp(16), tk.dp(16), tk.dp(16), tk.dp(4));
        body.addView(status);
        tk.row(body, "Style", Tr.t(secure ? "Secure folder" : "Work profile") + " · " + Tr.t("Tap to change."), tk.arrow(),
                () -> chooseStyle(id, apps, again), false);
        if (!secure) {
            tk.section(body, "Work folder");
            tk.row(body, "Open Work folder", "Your work apps in one place, with no lock.", tk.arrow(),
                    () -> tk.startActivity(new Intent().setClassName(tk.getPackageName(), tk.getPackageName() + ".WorkFolder")), false);
            if (Build.VERSION.SDK_INT >= 26) tk.row(body, "Add to home screen", "Some launchers don't show a new icon by themselves. "
                    + "This asks yours to add it.", tk.arrow(), () -> {
                String why = SecureFolder.pin(tk, true);
                if (why != null) tk.toast(Tr.t(why));
            }, false);
            tk.row(body, "Lock", lockText(), tk.arrow(), () -> chooseFolderLock(again), false);
        }
        if (secure) tk.section(body, "Secure folder");
        if (secure) tk.row(body, "Open Secure folder", "Your work apps behind the phone's lock screen, like Samsung's Secure Folder.",
                tk.arrow(), () -> tk.startActivity(new Intent(tk, SecureFolder.class)), false);
        if (secure && Build.VERSION.SDK_INT >= 26) tk.row(body, "Add to home screen", "Some launchers don't show a new icon by themselves. "
                + "This asks yours to add it.", tk.arrow(), () -> {
            String why = SecureFolder.pin(tk);
            if (why != null) tk.toast(Tr.t(why));
        }, false);
        if (secure) prefRow(body, "Secure folder icon", "Show it on the home screen and in the app list.", SecureFolder.PREF_ICON, true,
                on -> SecureFolder.sync(tk, tk.prefs));
        if (secure) tk.row(body, "Lock", lockText(), tk.arrow(), () -> chooseFolderLock(again), false);
        if (secure) prefRow(body, "Hide work apps from the launcher", "Freezes them, and again whenever the screen has been off, so "
                + "they only show in the Secure folder. A tap there brings one back.", SecureFolder.PREF_HIDE, false, on -> {
            List<String> every = new ArrayList<>();
            for (Work.App a : apps) every.add(a.pkg);
            Set<String> auto = Work.auto(tk.prefs);
            if (on) auto.addAll(every); else auto.removeAll(every);
            tk.prefs.edit().putStringSet(Work.PREF_AUTO, auto).apply();
            tk.worker.execute(() -> {
                List<String> todo = new ArrayList<>();
                for (Work.App a : apps) if (a.frozen != on) todo.add(a.pkg);
                Work.freezeAll(id, todo, on);
                if (on && !BackgroundService.on(tk.prefs)) BackgroundService.setEnabled(tk, tk.prefs, true);
                else BackgroundService.sync(tk);
                tk.main.post(again);
            });
        });
        if (state == Work.LOCKED) {
            tk.row(body, "Unlock work profile", "The work profile is locked. Enter its PIN to use its apps.", tk.arrow(),
                    () -> askPin("Unlock work profile", null, pin -> tk.worker.execute(() -> {
                        String error = Work.unlock(tk, id, pin);
                        tk.main.post(() -> {
                            if (error != null) workMessage(error);
                            again.run();
                        });
                    })), true);
        }
        Switch pause = new Switch(tk);
        pause.setClickable(false);
        pause.setFocusable(false);
        pause.setChecked(state == Work.PAUSED);
        tk.row(body, "Pause work profile", "Stops every work app and silences its notifications, like the Work "
                + "switch in your launcher. With a PIN of its own (see Lock) it stays locked until the PIN is "
                + "entered.", pause, () -> {
            boolean on = state != Work.PAUSED;
            pause.setChecked(on);
            tk.worker.execute(() -> {
                String error = Work.pause(tk, id, on);
                tk.main.post(() -> {
                    if (error != null) workMessage(error);
                    again.run();
                });
            });
        }, true);

        tk.section(body, "Apps");
        tk.link(body, "Add or remove apps", "Choose which apps have a copy in the work profile.",
                () -> tk.openPicker("Work apps", "Checked apps have a copy in the work profile. Check one to add it. "
                                + "Uncheck it to remove that copy and its data from the work profile.", true,
                        () -> {
                            Set<String> in = new HashSet<>();
                            for (Work.App a : Work.apps(tk, id)) in.add(a.pkg);
                            return in;
                        },
                        (pkg, on) -> {
                            String error = on ? Work.add(id, pkg) : Work.delete(id, pkg);
                            if (error == null) { // the TinyFolder shows what was put in the profile, not the apps it starts with
                                Set<String> mine = new HashSet<>(tk.prefs.getStringSet(SecureFolder.PREF_ADDED, new HashSet<>()));
                                if (on) mine.add(pkg); else mine.remove(pkg);
                                tk.prefs.edit().putStringSet(SecureFolder.PREF_ADDED, mine).apply();
                            }
                            if (error == null && !on) forgetAutoFreeze(pkg);
                            if (error == null && on && tk.prefs.getBoolean(SecureFolder.PREF_HIDE, false)) {
                                // Hiding is on: the new app is frozen too, and stays so when the screen is off.
                                Set<String> auto = Work.auto(tk.prefs);
                                auto.add(pkg);
                                tk.prefs.edit().putStringSet(Work.PREF_AUTO, auto).apply();
                                Work.freeze(id, pkg, true);
                                BackgroundService.sync(tk);
                            }
                            return error;
                        }));
        tk.link(body, "Work apps", "Open, freeze, add a launcher shortcut to, or remove the apps in the work profile.",
                () -> openWorkApps(id));
        tk.link(body, "Install APK in the work profile", "Install .apk, .apks, .xapk and .apkm files into the work "
                + "profile only.", () -> tk.pickApks(id));

        tk.section(body, "Freeze");
        int auto = Work.auto(tk.prefs).size();
        int delay = tk.prefs.getInt(Work.PREF_DELAY, 60);
        tk.row(body, "Freeze when the screen is off", auto == 0
                ? "No app chosen yet. Open Work apps, tap an app and choose Auto-freeze."
                : auto + " " + Tr.t(auto == 1 ? "app" : "apps") + " · " + Tr.t(waitName(delay))
                + ". Tap to change the wait.",
                tk.arrow(), () -> chooseWait("Freeze after the screen has been off", Work.DELAYS, Work.PREF_DELAY,
                        60, again), false);
        tk.row(body, "Freeze all", "Turns every work app off: it can't run, wake the phone or show in the launcher, "
                + "and keeps its data.", null, () -> freezeWork(id, true), true);
        tk.row(body, "Unfreeze all", "Turns them all back on.", null, () -> freezeWork(id, false), true);

        tk.section(body, "Lock");
        if (lockOk) {
            String pinState = lock == null ? "Give the work profile its own PIN, like Samsung's Secure Folder. "
                    + "Pausing then locks it: only the PIN opens it again."
                    : lock.equals("NONE") ? "No PIN of its own yet. Add one, and pausing locks the profile."
                    : "It has a PIN of its own. Tap to change or remove it.";
            tk.row(body, "Work profile PIN", pinState, tk.arrow(), () -> changePin(id, again), true);
        }
        int lockDelay = tk.prefs.getInt(Work.PREF_LOCK, -1);
        tk.row(body, "Lock when the screen is off", Tr.t(waitName(lockDelay))
                + ". Pauses the whole work profile. Tap to change.", tk.arrow(),
                () -> chooseWait("Lock after the screen has been off", Work.LOCK_DELAYS, Work.PREF_LOCK, -1, again),
                false);
        Switch boot = new Switch(tk);
        boot.setClickable(false);
        boot.setFocusable(false);
        boot.setChecked(tk.prefs.getBoolean(Work.PREF_LOCK_BOOT, false));
        tk.row(body, "Lock after a restart", "Pauses the work profile once each time the phone restarts, as soon as "
                + "Shizuku is running again.", boot, () -> {
            boolean on = !tk.prefs.getBoolean(Work.PREF_LOCK_BOOT, false);
            tk.prefs.edit().putBoolean(Work.PREF_LOCK_BOOT, on).apply();
            boot.setChecked(on);
        }, false);

        tk.section(body, "Profile");
        tk.row(body, "Remove work profile", "Deletes the profile, every app in it and all their data.", null,
                () -> new AlertDialog.Builder(tk)
                        .setTitle(Tr.t("Remove the work profile?"))
                        .setMessage(Tr.t("Every app in it and all their data are deleted for good. Your main "
                                + "profile is not touched."))
                        .setPositiveButton(Tr.t("Remove"), (d, w) -> tk.worker.execute(() -> {
                            String error = Work.remove(id);
                            if (error == null) forgetAutoFreeze(null);
                            tk.main.post(() -> {
                                tk.toast(Tr.t(error != null ? error : "Removed."));
                                again.run();
                            });
                        }))
                        .setNegativeButton(Tr.t("Cancel"), null)
                        .show(), true);
    }

    /** A longer message than a toast fits (why Android refused to make the profile). */
    void workMessage(String message) {
        if (message.length() < 80) {
            tk.toast(Tr.t(message));
        } else if (tk.alive()) {
            new AlertDialog.Builder(tk).setMessage(Tr.t(message)).setPositiveButton(Tr.t("OK"), null).show();
        }
    }

    static String waitName(int seconds) {
        return seconds < 0 ? "Never" : seconds == 0 ? "Right away" : seconds == 60 ? "After 1 minute"
                : seconds == 300 ? "After 5 minutes" : "After 15 minutes";
    }

    void chooseWait(String title, int[] values, String pref, int standard, Runnable again) {
        int current = tk.prefs.getInt(pref, standard);
        String[] names = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            names[i] = waitName(values[i]);
            if (values[i] == current) checked = i;
        }
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t(title))
                .setSingleChoiceItems(Tr.all(names), checked, (d, which) -> {
                    tk.prefs.edit().putInt(pref, values[which]).apply();
                    d.dismiss();
                    // The service starts or stops to match what now waits for the screen to go off; choosing
                    // a lock wait turns on "Run in the background" (which it needs), as choosing an app to
                    // freeze does.
                    boolean needsService = pref.equals(Work.PREF_LOCK) && values[which] >= 0;
                    tk.worker.execute(() -> {
                        boolean enabled = needsService && !BackgroundService.on(tk.prefs);
                        if (enabled) BackgroundService.setEnabled(tk, tk.prefs, true);
                        else BackgroundService.sync(tk);
                        if (enabled) tk.main.post(() -> tk.toast(Tr.t("Run in the background is on now. Auto-freeze and auto-lock need it.")));
                    });
                    again.run();
                })
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    /** One number box in a dialog; {@code onPin} gets the digits. */
    void askPin(String title, String message, MainActivity.OnText onPin) {
        EditText pin = new EditText(tk);
        pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setTypeface(Typeface.DEFAULT); // a number-password box would otherwise use a monospaced font
        pin.setHint(Tr.t("PIN"));
        LinearLayout box = tk.vertical();
        box.setPadding(tk.dp(24), tk.dp(8), tk.dp(24), 0);
        if (message != null) box.addView(tk.text(message, 14, false));
        box.addView(pin);
        new AlertDialog.Builder(tk).setTitle(Tr.t(title)).setView(box)
                .setPositiveButton(Tr.t("OK"), (d, w) -> onPin.run(pin.getText().toString()))
                .setNegativeButton(Tr.t("Cancel"), null).show();
    }

    /** Set, change or remove the work profile's own PIN: current PIN (if any) and new PIN (empty removes). */
    void changePin(int id, Runnable again) {
        int type = android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        EditText old = new EditText(tk);
        old.setInputType(type);
        old.setTypeface(Typeface.DEFAULT);
        old.setHint(Tr.t("Current PIN (empty if none)"));
        EditText next = new EditText(tk);
        next.setInputType(type);
        next.setTypeface(Typeface.DEFAULT);
        next.setHint(Tr.t("New PIN (empty removes it)"));
        LinearLayout box = tk.vertical();
        box.setPadding(tk.dp(24), tk.dp(8), tk.dp(24), 0);
        box.addView(tk.text("A PIN is 4 to 16 digits. With one of its own the work profile's data is locked until "
                + "the PIN is entered. If you forget it, those apps and their data can't be opened again: the only "
                + "way out is to remove the work profile.", 14, false));
        box.addView(old);
        box.addView(next);
        new AlertDialog.Builder(tk).setTitle(Tr.t("Work profile PIN")).setView(box)
                .setPositiveButton(Tr.t("Save"), (d, w) -> {
                    String before = old.getText().toString();
                    String after = next.getText().toString();
                    tk.worker.execute(() -> {
                        String error = Work.setLock(id, before, after);
                        tk.main.post(() -> {
                            tk.toast(Tr.t(error != null ? error : after.isEmpty() ? "PIN removed." : "PIN saved."));
                            again.run();
                        });
                    });
                })
                .setNegativeButton(Tr.t("Cancel"), null).show();
    }

    void freezeWork(int id, boolean frozen) {
        tk.toast(Tr.t(frozen ? "Freezing…" : "Unfreezing…"));
        tk.worker.execute(() -> {
            List<String> todo = new ArrayList<>();
            for (Work.App a : Work.apps(tk, id)) if (a.frozen != frozen) todo.add(a.pkg);
            String error = Work.freezeAll(id, todo, frozen);
            tk.main.post(() -> tk.toast(Tr.t(error != null ? error : todo.isEmpty() ? "Nothing to change."
                    : (frozen ? "Froze " : "Unfroze ") + todo.size() + (todo.size() == 1 ? " app." : " apps."))));
        });
    }

    /** Adds or takes away an app's "freeze when the screen is off" choice; the service starts or stops to match. */
    void toggleAutoFreeze(String pkg, Runnable done) {
        Set<String> auto = Work.auto(tk.prefs);
        boolean on = auto.add(pkg);
        if (!on) auto.remove(pkg);
        tk.prefs.edit().putStringSet(Work.PREF_AUTO, auto).apply();
        tk.worker.execute(() -> {
            // It needs "Run in the background"; turning the first app on turns that on, like Shut Up does.
            boolean enabled = on && !BackgroundService.on(tk.prefs);
            if (enabled) BackgroundService.setEnabled(tk, tk.prefs, true);
            else BackgroundService.sync(tk);
            tk.main.post(() -> {
                if (enabled) tk.toast(Tr.t("Run in the background is on now. Auto-freeze and auto-lock need it."));
                done.run();
            });
        });
    }

    /** Remembers the look: the Secure folder one (icon, lock first, apps hidden) or the plain work profile (none of those). */
    void applyStyle(boolean secure) {
        tk.prefs.edit().putString(SecureFolder.PREF_MODE, secure ? "secure" : "plain")
                .putBoolean(SecureFolder.PREF_ICON, secure).putBoolean(SecureFolder.PREF_LOCK, secure)
                .putBoolean(SecureFolder.PREF_HIDE, secure).apply();
        SecureFolder.sync(tk, tk.prefs);
    }

    String lockText() {
        String mode = SecureFolder.lockMode(tk.prefs);
        return Tr.t("password".equals(mode) ? "TinyFolder password" : "device".equals(mode) ? "Phone lock screen" : "Off")
                + " · " + Tr.t("Tap to change.");
    }

    EditText passwordBox(String hint) {
        EditText box = new EditText(tk);
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.setHint(Tr.t(hint));
        if (Build.VERSION.SDK_INT >= 26) box.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); // no "save this password?" from a password manager
        return box;
    }

    /** How the folder is locked: nothing, the phone's lock screen, or a password of its own (changing it needs the old one). */
    void chooseFolderLock(Runnable again) {
        String now = SecureFolder.lockMode(tk.prefs);
        String[] modes = {"off", "device", "password"};
        int at = now.equals("password") ? 2 : now.equals("device") ? 1 : 0;
        new AlertDialog.Builder(tk).setTitle(Tr.t("Lock"))
                .setSingleChoiceItems(Tr.all(new String[]{"Off", "Phone lock screen", "TinyFolder password"}), at, (d, which) -> {
                    d.dismiss();
                    String mode = modes[which];
                    if (mode.equals("device") && !((android.app.KeyguardManager) tk.getSystemService(Context.KEYGUARD_SERVICE)).isDeviceSecure()) {
                        tk.toast(Tr.t("Set a screen lock in Android first."));
                        return;
                    }
                    Runnable apply = () -> {
                        if (mode.equals("password")) {
                            askNewPassword(again);
                        } else {
                            tk.prefs.edit().putString(SecureFolder.PREF_LOCK_MODE, mode).remove(SecureFolder.PREF_PASSWORD).apply();
                            again.run();
                        }
                    };
                    if (now.equals("password") && SecureFolder.hasPassword(tk.prefs)) { // leaving or changing a password needs it
                        EditText current = passwordBox("Current password");
                        LinearLayout box = tk.vertical();
                        box.setPadding(tk.dp(24), tk.dp(8), tk.dp(24), 0);
                        box.addView(current);
                        new AlertDialog.Builder(tk).setTitle(Tr.t("Lock")).setView(box)
                                .setPositiveButton(Tr.t("OK"), (dd, w) -> {
                                    if (SecureFolder.checkPassword(tk.prefs, current.getText().toString())) apply.run();
                                    else tk.toast(Tr.t("Wrong password."));
                                })
                                .setNegativeButton(Tr.t("Cancel"), null).show();
                    } else {
                        apply.run();
                    }
                })
                .setNegativeButton(Tr.t("Cancel"), null).show();
    }

    void askNewPassword(Runnable again) {
        EditText first = passwordBox("New password (at least 4 characters)");
        EditText second = passwordBox("Repeat the new password");
        LinearLayout box = tk.vertical();
        box.setPadding(tk.dp(24), tk.dp(8), tk.dp(24), 0);
        box.addView(tk.text("Only the folder is locked by this: the apps in the work profile can still be opened from the "
                + "launcher while they aren't hidden. If you forget it, the phone's lock screen opens the folder.", 14, false));
        box.addView(first);
        box.addView(second);
        new AlertDialog.Builder(tk).setTitle(Tr.t("TinyFolder password")).setView(box)
                .setPositiveButton(Tr.t("Save"), (d, w) -> {
                    String one = first.getText().toString();
                    if (one.length() < 4) {
                        tk.toast(Tr.t("New password (at least 4 characters)"));
                    } else if (!one.equals(second.getText().toString())) {
                        tk.toast(Tr.t("The passwords don't match."));
                    } else {
                        SecureFolder.setPassword(tk.prefs, one);
                        tk.prefs.edit().putString(SecureFolder.PREF_LOCK_MODE, "password").apply();
                        tk.toast(Tr.t("Password saved."));
                        again.run();
                    }
                })
                .setNegativeButton(Tr.t("Cancel"), null).show();
    }

    /** Switches an existing profile between the two looks; going plain brings the hidden apps back. */
    void chooseStyle(int id, List<Work.App> apps, Runnable again) {
        boolean now = SecureFolder.secure(tk.prefs);
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t("Style"))
                .setSingleChoiceItems(Tr.all(new String[]{"Secure folder", "Work profile"}), now ? 0 : 1, (d, which) -> {
                    d.dismiss();
                    boolean secure = which == 0;
                    if (secure == now) return;
                    applyStyle(secure);
                    if (!secure && SecureFolder.onHomeScreen(tk)) {
                        tk.toast(Tr.t("The old Secure folder icon is still on your home screen: touch and hold it, then Remove."));
                    }
                    if (!secure) {
                        Set<String> auto = Work.auto(tk.prefs);
                        List<String> back = new ArrayList<>();
                        for (Work.App a : apps) if (a.frozen && auto.contains(a.pkg)) back.add(a.pkg);
                        auto.clear();
                        tk.prefs.edit().putStringSet(Work.PREF_AUTO, auto).apply();
                        tk.worker.execute(() -> {
                            Work.freezeAll(id, back, false);
                            BackgroundService.sync(tk);
                            tk.main.post(again);
                        });
                    } else {
                        again.run();
                    }
                })
                .setNegativeButton(Tr.t("Cancel"), null)
                .show();
    }

    /** One short question before making it; the choice itself was the row that was tapped. */
    void confirmCreate(boolean secure, Runnable again) {
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t(secure ? "Create a Secure folder?" : "Create a work profile?"))
                .setMessage(Tr.t("This adds a separate space to your phone. Your own apps and data stay as they are. "
                        + "You can remove it later."))
                .setPositiveButton(Tr.t("Create"), (d, w) -> createWork(secure, again))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Makes the profile, set up as a Samsung-style Secure folder or as the plain work profile (the switches can change later). */
    void createWork(boolean secure, Runnable again) {
        tk.toast(Tr.t("Creating…"));
        applyStyle(secure);
        tk.worker.execute(() -> {
            String error = Work.create(tk);
            // Hiding the apps relies on auto-freeze, which needs "Run in the background".
            boolean enabled = error == null && secure && !BackgroundService.on(tk.prefs);
            if (enabled) BackgroundService.setEnabled(tk, tk.prefs, true);
            if (error == null) tk.prefs.edit().putBoolean(SecureFolder.PREF_EXISTS, true).apply();
            tk.main.post(() -> {
                if (error == null && Build.VERSION.SDK_INT >= 26) SecureFolder.pin(tk, !secure); // the launcher may not add the icon itself
                if (error != null) workMessage(error);
                else if (enabled) tk.toast(Tr.t("Run in the background is on now. Auto-freeze and auto-lock need it."));
                SecureFolder.sync(tk, tk.prefs); // the icon is there at once, before the page is opened again
                again.run();
            });
        });
    }

    /** A switch row for a yes/no TinyKit setting; {@code changed} runs after it flips. */
    void prefRow(LinearLayout list, String title, String summary, String key, boolean def,
                         java.util.function.Consumer<Boolean> changed) {
        Switch view = new Switch(tk);
        view.setChecked(tk.prefs.getBoolean(key, def));
        view.setClickable(false);
        view.setFocusable(false);
        tk.row(list, title, summary, view, () -> {
            boolean on = !view.isChecked();
            view.setChecked(on);
            tk.prefs.edit().putBoolean(key, on).apply();
            changed.accept(on);
        }, false);
    }

    /** An app (or, with null, every app) that left the work profile no longer needs freezing by itself. */
    void forgetAutoFreeze(String pkg) {
        Set<String> auto = Work.auto(tk.prefs);
        if (pkg == null ? auto.isEmpty() : !auto.remove(pkg)) return;
        if (pkg == null) auto.clear();
        tk.prefs.edit().putStringSet(Work.PREF_AUTO, auto).apply();
        BackgroundService.sync(tk);
    }

    boolean systemApp(String pkg) {
        try {
            int flags = tk.getPackageManager().getApplicationInfo(pkg, 0).flags;
            return (flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    void openWorkApps(int id) {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView help = tk.text("Tap an app to open it, freeze it, set it to freeze by itself, or add a launcher "
                + "shortcut. A frozen app is off and keeps its data.", 13, false);
        tk.setDim(help, 0.7f);
        page.addView(help);
        EditText search = tk.searchField("Search apps");
        page.addView(search, tk.fieldMargin());
        CheckBox system = new CheckBox(tk);
        system.setText(Tr.t("Show system apps"));
        page.addView(system);
        TextView loading = tk.text("Loading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        List<Work.App> all = new ArrayList<>();
        MainActivity.Adapter<Work.App> adapter = tk.new Adapter<>(false, (a, row) -> {
            row.first.setText(tk.label(a.pkg));
            row.second.setText(a.pkg);
            List<String> tags = new ArrayList<>();
            if (a.frozen) tags.add(Tr.t("Frozen"));
            if (Work.auto(tk.prefs).contains(a.pkg)) tags.add(Tr.t("Auto-freeze"));
            row.third.setText(TextUtils.join(" · ", tags));
            tk.showIcon(row, a.pkg);
        });
        listView.setAdapter(adapter);
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            Set<String> auto = Work.auto(tk.prefs);
            List<Work.App> shown = new ArrayList<>();
            for (Work.App a : all) {
                // Frozen and auto-freeze apps always show, so one never gets lost behind the filter.
                boolean show = system.isChecked() || a.frozen || auto.contains(a.pkg) || !systemApp(a.pkg);
                if (show && (tk.label(a.pkg).toLowerCase(Locale.ROOT).contains(q) || a.pkg.contains(q))) shown.add(a);
            }
            adapter.set(shown);
            loading.setText(Tr.t(all.isEmpty() ? "No apps in the work profile yet."
                    : "No match. Tick Show system apps to see more."));
            loading.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
        };
        search.addTextChangedListener(tk.watcher(filter));
        system.setOnCheckedChangeListener((v, checked) -> filter.run());
        Runnable load = () -> tk.worker.execute(() -> {
            List<Work.App> apps = Work.apps(tk, id);
            Collections.sort(apps, (x, y) -> tk.label(x.pkg).compareToIgnoreCase(tk.label(y.pkg)));
            tk.main.post(() -> {
                all.clear();
                all.addAll(apps);
                filter.run();
            });
        });
        Runnable reload = load;
        listView.setOnItemClickListener((parentView, view, position, rowId) -> {
            Work.App a = adapter.getItem(position);
            boolean auto = Work.auto(tk.prefs).contains(a.pkg);
            String[] items = {a.frozen ? "Unfreeze and open" : "Open", a.frozen ? "Unfreeze" : "Freeze",
                    auto ? "Stop auto-freezing" : "Auto-freeze when the screen is off", "Add launcher shortcut",
                    "Clear data", "Remove from work profile"};
            new AlertDialog.Builder(tk)
                    .setTitle(tk.label(a.pkg))
                    .setItems(Tr.all(items), (d, which) -> {
                        if (which == 2) {
                            toggleAutoFreeze(a.pkg, reload);
                        } else if (which == 3) {
                            String error = Work.pin(tk, a.pkg, tk.label(a.pkg));
                            if (error != null) tk.toast(Tr.t(error));
                        } else if (which >= 4) {
                            boolean wipe = which == 4;
                            new AlertDialog.Builder(tk)
                                    .setTitle(Tr.t((wipe ? "Clear data of " : "Remove ") + tk.label(a.pkg) + "?"))
                                    .setMessage(Tr.t(wipe ? "Everything the app saved in the work profile is "
                                            + "deleted: accounts, files and settings. The app stays."
                                            : "Its copy in the work profile and that copy's data are deleted. The "
                                            + "app on your main profile stays."))
                                    .setPositiveButton(Tr.t(wipe ? "Clear" : "Remove"), (d2, w2) -> tk.worker.execute(() -> {
                                        String error = wipe ? Work.clear(id, a.pkg) : Work.delete(id, a.pkg);
                                        if (error == null && !wipe) forgetAutoFreeze(a.pkg);
                                        tk.main.post(() -> {
                                            if (error != null) tk.toast(Tr.t(error));
                                            reload.run();
                                        });
                                    }))
                                    .setNegativeButton(Tr.t("Cancel"), null)
                                    .show();
                        } else {
                            tk.worker.execute(() -> {
                                String error = which == 0 ? Work.launch(tk, a.pkg)
                                        : Work.freeze(id, a.pkg, !a.frozen);
                                tk.main.post(() -> {
                                    if (error != null) tk.toast(Tr.t(error));
                                    reload.run();
                                });
                            });
                        }
                    })
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        tk.push("Work apps", page);
        load.run();
    }

    void openHide() {
        tk.openPicker("Hide apps",
                "Checked apps are disabled: they leave the launcher and stop running. Uncheck to "
                        + "bring one back. On this phone only disabling works to hide an app.", true,
                tk::disabledPackages,
                (pkg, on) -> tk.error(Shell.run(on
                        ? "pm disable-user --user 0 " + Shell.quote(pkg)
                        : "pm enable " + Shell.quote(pkg))));
    }

    /** Roles the "Default apps" page offers, as {role, name}. */
    static final String[][] ROLES = {
            {"android.app.role.HOME", "Launcher"},
            {"android.app.role.BROWSER", "Browser"},
            {"android.app.role.DIALER", "Phone"},
            {"android.app.role.SMS", "SMS"},
            {"android.app.role.ASSISTANT", "Assistant"}};

    /** Common file types the "File types" section can set a default app for, as {name, mime}. */
    static final String[][] FILE_TYPES = {
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

    void openDefaults() {
        LinearLayout list = tk.vertical();
        list.addView(tk.note("Tap a default to change it. Some phones only allow certain apps for a role."));
        tk.section(list, "Roles");
        // Roles (and the role command) are Android 10+.
        if (Build.VERSION.SDK_INT < 29) list.addView(tk.note("Choosing default apps here needs Android 10 or newer. "
                + "Use Settings > Apps > Default apps instead."));
        for (String[] role : Build.VERSION.SDK_INT >= 29 ? ROLES : new String[0][]) {
            View[] holder = new View[1];
            holder[0] = tk.row(list, role[1], "…", tk.arrow(),
                    () -> tk.openAppList("Set " + role[1],
                            "Pick the app to use as the default " + role[1].toLowerCase(Locale.ROOT) + ".",
                            pkg -> {
                                tk.onBackPressed();
                                tk.worker.execute(() -> {
                                    String err = tk.error(Shell.run("cmd role add-role-holder "
                                            + role[0] + " " + Shell.quote(pkg)));
                                    tk.main.post(() -> {
                                        if (err != null) tk.toast(Tr.t(err));
                                        refreshRole(role, holder[0]);
                                    });
                                });
                            }), true);
            refreshRole(role, holder[0]);
        }

        tk.section(list, "File types");
        list.addView(tk.note("The app that opens each kind of file. Tap to see every app that can "
                + "open it (Android needs root to pin a file-type default directly)."));
        for (String[] type : FILE_TYPES) {
            View[] holder = new View[1];
            holder[0] = tk.row(list, type[0], "…", tk.arrow(), () -> pickForType(type, holder[0]), true);
            refreshType(type, holder[0]);
        }
        tk.push("Default apps", tk.scroll(list));
    }

    /** Shows the apps that can open this type and the current default. */
    void pickForType(String[] type, View row) {
        tk.worker.execute(() -> {
            List<MainActivity.App> handlers = handlersFor(type[1]);
            tk.main.post(() -> {
                StringBuilder msg = new StringBuilder();
                if (handlers.isEmpty()) {
                    msg.append("No installed app handles this type.");
                } else {
                    msg.append("Apps that can open ").append(type[0]).append(':');
                    for (MainActivity.App h : handlers) msg.append("\n•  ").append(h.label);
                    msg.append("\n\nAndroid only lets the system pin a file-type default, so to set "
                            + "one, open a ").append(type[0]).append(" file and choose \"Always\". "
                            + "TinyKit shows the current default above.");
                }
                new AlertDialog.Builder(tk)
                        .setTitle(Tr.t(type[0]))
                        .setMessage(Tr.t(msg.toString()))
                        .setPositiveButton(Tr.t("OK"), null)
                        .show();
            });
        });
    }

    /** Apps (with their handling component) that can open a content type, as default-launchable. */
    List<MainActivity.App> handlersFor(String mime) {
        Intent intent = new Intent(Intent.ACTION_VIEW).setType(mime);
        List<MainActivity.App> result = new ArrayList<>();
        PackageManager pm = tk.getPackageManager();
        for (ResolveInfo info : pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            if (info.activityInfo == null) continue;
            MainActivity.App a = new MainActivity.App();
            a.pkg = info.activityInfo.packageName;
            a.component = info.activityInfo.name;
            a.label = String.valueOf(info.loadLabel(pm));
            result.add(a);
        }
        return result;
    }

    void refreshType(String[] type, View row) {
        TextView sub = (TextView) row.getTag();
        tk.worker.execute(() -> {
            String out = Shell.run("cmd package resolve-activity --brief -a android.intent.action.VIEW -t "
                    + Shell.quote(type[1]) + " 2>/dev/null").out;
            Matcher m = MainActivity.PACKAGE.matcher(out);
            String pkg = m.find() ? m.group() : null;
            String text;
            if (pkg == null || out.contains("ResolverActivity") || out.toLowerCase(Locale.ROOT).contains("none")) {
                text = "Ask every time";
            } else {
                text = tk.label(pkg);
            }
            tk.main.post(() -> sub.setText(Tr.t(text)));
        });
    }

    void refreshRole(String[] role, View row) {
        TextView sub = (TextView) row.getTag();
        tk.worker.execute(() -> {
            // Stock Android has get-role-holders; EMUI doesn't, so read dumpsys role instead.
            String out = Shell.run("cmd role get-role-holders " + role[0]).out;
            if (out.contains("Unknown command") || out.trim().isEmpty()) {
                out = Shell.run("dumpsys role | grep -A3 'name=" + role[0] + "' | grep -m1 holders=").out;
            }
            Matcher m = MainActivity.PACKAGE.matcher(out);
            String holder = m.find() ? m.group() : null;
            String text = holder == null ? "None set" : tk.label(holder);
            tk.main.post(() -> sub.setText(Tr.t(text)));
        });
    }

    void openUnused() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView help = tk.text("Longest-unused apps first. Tap one to open its settings.", 13, false);
        tk.setDim(help, 0.7f);
        page.addView(help);
        TextView loading = tk.text("Reading usage…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push("Unused apps", page);

        MainActivity.Adapter<String[]> adapter = tk.new Adapter<>(false, (a, row) -> {
            row.first.setText(Tr.t(a[1]));
            row.second.setText(Tr.t(a[2]));
            tk.showIcon(row, a[0]);
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
        page.addView(tk.sortBar(new String[]{"Least used", "Name"}, i -> {
            sort[0] = i;
            apply.run();
        }), 2);
        tk.worker.execute(() -> {
            if (!Tweak.usageAllowed(tk)) Tweak.grantSelf(tk);
            Map<String, Long> used = lastUsed();
            List<String[]> found = new ArrayList<>();
            if (!Tweak.usageAllowed(tk) || used.isEmpty()) {
                // Without usage access every app would look "Never opened".
                tk.main.post(() -> loading.setText(Tr.t("Couldn't read usage. Start Shizuku so TinyKit can turn it on.")));
                return;
            }
            long now = System.currentTimeMillis();
            for (MainActivity.App a : launchableApps()) {
                long last = used.containsKey(a.pkg) ? used.get(a.pkg) : 0L;
                String when = last == 0 ? "Never opened"
                        : "Last opened " + DateUtils.getRelativeTimeSpanString(last, now, DateUtils.DAY_IN_MILLIS);
                found.add(new String[]{a.pkg, a.label, when, String.valueOf(last)});
            }
            tk.main.post(() -> {
                loading.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
                if (found.isEmpty()) loading.setText(Tr.t("Couldn't read usage. Start Shizuku so TinyKit can turn it on."));
                rows.clear();
                rows.addAll(found);
                apply.run();
            });
        });
    }

    Map<String, Long> lastUsed() {
        Map<String, Long> map = new HashMap<>();
        try {
            UsageStatsManager usm = (UsageStatsManager) tk.getSystemService(Context.USAGE_STATS_SERVICE);
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

    List<MainActivity.App> launchableApps() {
        List<MainActivity.App> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        PackageManager pm = tk.getPackageManager();
        for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(tk.getPackageName()) || !seen.add(pkg)) continue;
            MainActivity.App a = new MainActivity.App();
            a.pkg = pkg;
            a.label = String.valueOf(info.activityInfo.applicationInfo.loadLabel(pm));
            list.add(a);
        }
        return list;
    }

    @android.annotation.TargetApi(26) // guarded below: StorageStatsManager is Android 8+
    void openStorage() {
        if (Build.VERSION.SDK_INT < 26) {
            tk.toast(Tr.t("Storage by app needs Android 8 or newer."));
            return;
        }
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView summary = tk.text("Measuring…", 15, true);
        page.addView(summary);
        ListView listView = new ListView(tk);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push("Storage by app", page);

        MainActivity.Adapter<String[]> adapter = tk.new Adapter<>(false, (a, row) -> {
            row.first.setText(Tr.t(a[1]));
            row.second.setText(Tr.t(a[2]));
            tk.showIcon(row, a[0]);
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
        page.addView(tk.sortBar(new String[]{"Size", "Name"}, i -> {
            sort[0] = i;
            apply.run();
        }), 1);
        tk.worker.execute(() -> {
            if (!Tweak.usageAllowed(tk)) Tweak.grantSelf(tk);
            StorageStatsManager ssm = (StorageStatsManager) tk.getSystemService(Context.STORAGE_STATS_SERVICE);
            List<String[]> found = new ArrayList<>();
            long total = 0;
            for (MainActivity.App a : tk.installedApps()) {
                try {
                    StorageStats s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, a.pkg,
                            Process.myUserHandle());
                    long bytes = s.getAppBytes() + s.getDataBytes() + s.getCacheBytes();
                    total += bytes;
                    found.add(new String[]{a.pkg, a.label, Formatter.formatShortFileSize(tk, bytes),
                            String.valueOf(bytes)});
                } catch (Exception ignored) {
                }
            }
            long used = total;
            tk.main.post(() -> {
                summary.setText(Tr.t(found.isEmpty()
                        ? "Couldn't measure storage. Start Shizuku so TinyKit can turn usage access on."
                        : "Apps use " + Formatter.formatShortFileSize(tk, used) + " in total"));
                rows.clear();
                rows.addAll(found);
                apply.run();
            });
        });
    }

    void openRecentAccess() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView help = tk.text("Most recent first, from the system's app-ops record.", 13, false);
        tk.setDim(help, 0.7f);
        page.addView(help);
        TextView loading = tk.text("Reading…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push("Recent access", page);

        MainActivity.Adapter<String[]> adapter = tk.new Adapter<>(false, (a, row) -> {
            row.first.setText(Tr.t(a[0]));
            row.second.setText(Tr.t(a[1] + "  ·  " + a[2]));
            tk.showIcon(row, a[3]);
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> openAppInfo(adapter.getItem(position)[3]));
        tk.worker.execute(() -> {
            List<String[]> rows = recentAccess();
            tk.main.post(() -> {
                loading.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                if (rows.isEmpty()) loading.setText(Tr.t("This phone doesn't record recent access, or nothing recent."));
                adapter.set(rows);
            });
        });
    }

    static final String[][] ACCESS_OPS = {
            {"CAMERA", "Camera"}, {"RECORD_AUDIO", "Microphone"},
            {"FINE_LOCATION", "Location"}, {"COARSE_LOCATION", "Location"},
            {"READ_CONTACTS", "Contacts"}, {"READ_CLIPBOARD", "Clipboard"}};

    /** Parses {@code dumpsys appops} for recent use of sensitive ops. Best-effort across phones. */
    List<String[]> recentAccess() {
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
                    rows.add(new String[]{tk.label(pkg), friendly.get(op), wm.group(1), pkg});
                }
                op = null;
            }
        }
        Collections.sort(rows, (x, y) -> y[2].compareTo(x[2]));
        return rows;
    }

    void openAppInfo(String pkg) {
        try {
            tk.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            tk.toast(Tr.t("Couldn't open app settings."));
        }
    }
}
