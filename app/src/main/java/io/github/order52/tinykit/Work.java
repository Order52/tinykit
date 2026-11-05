package io.github.order52.tinykit;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A work profile made and managed through Shizuku alone: no setup wizard, no second copy of TinyKit
 * inside the profile, no device-admin rights. Android's own {@code pm} creates the profile and puts
 * an app into it ({@code install-existing}, a separate copy with its own data); freezing is
 * {@code pm disable-user}; pausing is Android's quiet mode; a PIN of its own is Android's work
 * challenge ({@code cmd lock_settings}), which is what makes the profile behave like Samsung's Secure
 * Folder: locked and silent until the PIN is entered. The profile and its apps are read back from
 * Android each time (TinyKit only remembers which apps to freeze and when to lock), and a profile made
 * by another app (Shelter, Island) is managed the same way. Call off the main thread.
 *
 * Every shell call that could wait on the phone has a time limit: {@code am start-user -w} on a
 * profile with its own lock never returns (two minutes, then an error), so it is never used.
 */
final class Work {
    /** The apps to freeze when the screen has been off for a while (package names). */
    static final String PREF_AUTO = "work_auto";
    /** Seconds the screen stays off before those are frozen; 0 is right away. */
    static final String PREF_DELAY = "work_delay";
    /** Seconds the screen stays off before the whole profile is paused (locked); -1 is never. */
    static final String PREF_LOCK = "work_lock";
    /** Pause (lock) the profile once after every restart. */
    static final String PREF_LOCK_BOOT = "work_lock_boot";
    static final int[] DELAYS = {0, 60, 300, 900};
    static final int[] LOCK_DELAYS = {-1, 0, 60, 300, 900};
    static final String ACTION_FREEZE = "io.github.order52.tinykit.WORK_FREEZE";
    static final String ACTION_LOCK = "io.github.order52.tinykit.WORK_LOCK";

    static final int RUNNING = 0, PAUSED = 1, LOCKED = 2, STOPPED = 3;

    private static final int PER_USER_RANGE = 100000;
    private static final long FLAG_MANAGED = 0x20;
    private static final long FLAG_QUIET = 0x80;
    private static final Pattern USER = Pattern.compile("UserInfo\\{(\\d+):(.*):([0-9a-fA-F]+)\\}");
    /** Other kinds of separate space that must never be mistaken for the work profile (Samsung's Secure Folder is user 150). */
    private static final Pattern FOREIGN = Pattern.compile(
            "(?i)secure folder|knox|dual|twin|clone|parallel|second space|private|guest");
    private static final Pattern LAUNCH = Pattern.compile(
            "^\\s+([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/(\\S+)\\s*$", Pattern.MULTILINE);
    private static final Pattern PACKAGE = Pattern.compile("^package:(\\S+)\\s*$", Pattern.MULTILINE);
    private static final Pattern DIGITS = Pattern.compile("[0-9]{4,16}");
    private static volatile Boolean lockCommand;

    /** One app of the profile. */
    static final class App {
        final String pkg;
        final String component;
        final boolean frozen;

        App(String pkg, String component, boolean frozen) {
            this.pkg = pkg;
            this.component = component;
            this.frozen = frozen;
        }
    }

    private Work() {
    }

    /**
     * Android's own "pause work apps" (quiet mode), the switch Pixel's Work tab has: every app in the
     * profile stops and its notifications go quiet until it is switched back. Started with app_process
     * as the shell user, the way {@link Sensors} is. Args: {@code pause|resume <user id>}. The
     * method has had three shapes (Android 7 to 16), so the arguments are filled in by type.
     */
    public static void main(String[] args) throws Exception {
        Object users = Class.forName("android.os.IUserManager$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                        .invoke(null, "user"));
        int id = Integer.parseInt(args[1]);
        try {
            boolean on = args[0].equals("pause");
            for (String name : new String[]{"requestQuietModeEnabled", "setQuietModeEnabled"}) {
                for (Method m : users.getClass().getMethods()) {
                    if (!m.getName().equals(name)) continue;
                    Class<?>[] types = m.getParameterTypes();
                    Object[] call = new Object[types.length];
                    boolean user = false;
                    for (int i = 0; i < types.length; i++) {
                        if (types[i] == String.class) call[i] = "com.android.shell";
                        else if (types[i] == boolean.class) call[i] = on;
                        else if (types[i] == int.class) {
                            call[i] = user ? 0 : id; // the user id comes first, then flags
                            user = true;
                        }
                    }
                    System.out.println(m.invoke(users, call));
                    return;
                }
            }
            throw new IllegalStateException("no quiet mode method");
        } catch (InvocationTargetException e) {
            System.out.println(e.getCause());
            System.exit(1);
        }
    }

    // ---- The profile ------------------------------------------------------------------------------

    /** Whether this phone can have a work profile at all (managed profiles, and {@code cmd package install-existing} for a user: Android 8). */
    static boolean supported(Context c) {
        return Build.VERSION.SDK_INT >= 26 && c.getPackageManager().hasSystemFeature("android.software.managed_users");
    }

    private static Shell.Result run(String command, long ms) {
        return Shell.runLimited(command, ms, 256 * 1024, null);
    }

    /** The user id of the work profile in {@code pm list users} output, or -1 when there is none. */
    static int parseId(String out) {
        return parseId(out, null);
    }

    /** {@code mine} (the user's own profiles, when known) breaks a tie between several work profiles. */
    static int parseId(String out, Set<Integer> mine) {
        List<Integer> found = new ArrayList<>();
        Matcher m = USER.matcher(out);
        while (m.find()) {
            try {
                if ((Long.parseLong(m.group(3), 16) & FLAG_MANAGED) == 0 || FOREIGN.matcher(m.group(2)).find()) continue;
                found.add(Integer.parseInt(m.group(1)));
            } catch (NumberFormatException skip) {
                // Not a line of the list.
            }
        }
        if (found.isEmpty()) return -1;
        if (mine != null) for (int id : found) if (mine.contains(id)) return id;
        return found.get(0);
    }

    static int id(Context c) {
        Shell.Result r = run("pm list users", 10_000);
        return r.ok() ? parseId(r.out, profiles(c)) : -1;
    }

    private static Set<Integer> profiles(Context c) {
        try {
            Set<Integer> ids = new HashSet<>();
            for (UserHandle h : ((UserManager) c.getSystemService(Context.USER_SERVICE)).getUserProfiles()) {
                ids.add(h.hashCode()); // an UserHandle's hash code is its user id
            }
            return ids;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The user TinyKit itself runs as, which the profile belongs to. */
    static int parent() {
        return android.os.Process.myUid() / PER_USER_RANGE;
    }

    /** Makes the profile and starts it. Returns null on success, else why not. */
    static String create(Context c) {
        if (!supported(c)) return "This phone can't have a work profile.";
        Shell.Result r = run("pm create-user --profileOf " + parent() + " --managed Work", 60_000);
        if (!r.ok()) {
            if (r.out.contains("Cannot add more")) {
                return "This phone allows only one work profile, and it already has one.";
            }
            return "Android refused: " + (r.out.isEmpty() ? "no reason given." : r.out)
                    + "\n\nSome phones block work profiles. Dual Apps, App Twin, Second space and a custom recovery "
                    + "are common causes: turn those off and try again.";
        }
        int id = id(c);
        if (id < 0) return "Android didn't create the profile.";
        start(c, id);
        return null;
    }

    static String remove(int id) {
        return failure(run("pm remove-user " + id, 60_000));
    }

    // ---- State: running, paused, locked -------------------------------------------------------------

    private static int userState(int id) {
        String s = run("am get-started-user-state " + id, 8_000).out;
        if (s.contains("RUNNING_UNLOCKED")) return RUNNING;
        if (s.contains("RUNNING_LOCKED") || s.contains("RUNNING_UNLOCKING") || s.contains("BOOTING")) return LOCKED;
        if (s.contains("not started") || s.contains("SHUTDOWN") || s.contains("STOPPING")) return STOPPED;
        return RUNNING; // an answer this Android words differently: assume it is usable
    }

    /**
     * Running and usable, paused (locked by the user), or locked until its PIN is entered. A profile that
     * isn't running counts as paused: it is what pausing does where Android won't let TinyKit use its
     * quiet mode (Android 11 and older), and switching it on starts it.
     */
    static int state(Context c, int id) {
        if (paused(c, id)) return PAUSED;
        int state = userState(id);
        return state == STOPPED ? PAUSED : state;
    }

    private static String quiet(Context c, String arg, int id) {
        return "CLASSPATH=" + c.getPackageCodePath() + " app_process /system/bin io.github.order52.tinykit.Work "
                + arg + " " + id;
    }

    /**
     * Whether the profile is paused, read from the quiet-mode flag in {@code pm list users} ("1030" becomes
     * "10b0" while paused), which costs a few milliseconds. Asking Android's user service through app_process
     * says the same, and takes a second while a whole Java process starts.
     */
    static boolean paused(Context c, int id) {
        Shell.Result r = run("pm list users", 10_000);
        return r.ok() && quiet(r.out, id);
    }

    static boolean quiet(String out, int id) {
        Matcher m = USER.matcher(out);
        while (m.find()) {
            try {
                if (Integer.parseInt(m.group(1)) == id) return (Long.parseLong(m.group(3), 16) & FLAG_QUIET) != 0;
            } catch (NumberFormatException skip) {
                // Not a line of the list.
            }
        }
        return false;
    }

    /** Pauses or resumes the profile. Returns null on success, else why not (or what to do next). */
    static String pause(Context c, int id, boolean on) {
        Shell.Result r = run(quiet(c, on ? "pause" : "resume", id), 20_000);
        // Android 11 and older keep quiet mode from the shell user; stopping the profile does the job.
        if (!r.ok()) return pauseByStopping(c, id, on);
        for (int i = 0; i < 15; i++) {
            if (paused(c, id) == on) return null;
            android.os.SystemClock.sleep(200);
        }
        return on ? "This phone didn't pause the work profile."
                : "Enter the work profile's PIN on the screen that opened, to switch it back on.";
    }

    /** Pausing the other way: stops the profile (apps end, and one with a PIN comes back locked) or starts it. */
    private static String pauseByStopping(Context c, int id, boolean on) {
        if (!on) return start(c, id) == STOPPED ? "This phone didn't switch the work profile on." : null;
        run("am stop-user -f " + id, 30_000);
        for (int i = 0; i < 8; i++) {
            if (userState(id) == STOPPED) return null;
            android.os.SystemClock.sleep(500);
        }
        return "This phone didn't pause the work profile.";
    }

    /**
     * Starts the profile without waiting for it to unlock, and gives its state a few seconds to settle:
     * the usual profile unlocks by itself within a second or two; one with its own PIN stays locked.
     */
    static int start(Context c, int id) {
        run("am start-user " + id, 15_000);
        int state = STOPPED;
        for (int i = 0; i < 16; i++) {
            state = userState(id);
            if (state == RUNNING) break;
            android.os.SystemClock.sleep(500);
        }
        return state;
    }

    /** Gets the profile ready to open an app: resumed and unlocked. Returns null when it is, else what to do. */
    private static String wake(Context c, int id) {
        if (paused(c, id)) {
            String next = pause(c, id, false);
            if (next != null) return next;
        }
        return start(c, id) == LOCKED
                ? "The work profile is locked. Open TinyKit > Apps > Work profile and unlock it with its PIN." : null;
    }

    // ---- Apps ---------------------------------------------------------------------------------------

    /**
     * The profile's apps that have a launcher icon, frozen ones included. The query asks for disabled apps
     * too, and for apps that aren't direct-boot aware, which a stopped or locked profile hides otherwise.
     * Android 8 and 9 don't know those options (and then hide frozen apps), so there the profile's own
     * packages that have a launcher icon in the main profile are added. An app is frozen when the user
     * switched it off (Android's "enabled=3"); apps the phone ships disabled (state 2) are not.
     */
    static List<App> apps(Context c, int id) {
        String query = "cmd package query-activities --brief%s --user " + id
                + " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER 2>&1";
        Shell.Result r = run("Q=$(" + String.format(query, " --query-flags 786944") + "); L=; "
                + "case \"$Q\" in *\"activities found\"*) ;; *) L=legacy; Q=$(" + String.format(query, "") + ");; esac; "
                + "echo \"$Q\"; echo @@; echo $L; echo @@; pm list packages -d --user " + id
                + "; echo @@; pm list packages --user " + id, 30_000);
        String[] part = r.out.split("(?m)^@@$");
        Map<String, String> launch = parseLaunch(part[0]);
        boolean legacy = part.length > 1 && part[1].contains("legacy");
        List<String> disabled = packages(part.length > 2 ? part[2] : "");
        Map<String, String> own = new LinkedHashMap<>();
        if (legacy || launch.isEmpty()) {
            PackageManager pm = c.getPackageManager();
            for (String pkg : packages(part.length > 3 ? part[3] : "")) {
                Intent launcher = pm.getLaunchIntentForPackage(pkg);
                if (launcher != null && launcher.getComponent() != null) {
                    own.put(pkg, launcher.getComponent().flattenToShortString());
                }
            }
        }
        // Only the disabled packages that have an icon are checked one by one (a work profile can list
        // hundreds of default-disabled system packages, and most were never switched off by the user).
        StringBuilder check = new StringBuilder();
        for (String pkg : disabled) {
            if (launch.containsKey(pkg) || own.containsKey(pkg)) {
                check.append("dumpsys package ").append(Shell.quote(pkg)).append(" | grep -q -E \"^ +User ").append(id)
                        .append(": .*enabled=3\" && echo \"package:").append(pkg).append("\"; ");
            }
        }
        List<String> frozen = check.length() == 0 ? new ArrayList<>() : packages(run(check.toString(), 30_000).out);
        List<App> apps = build(launch, frozen);
        for (Map.Entry<String, String> e : own.entrySet()) {
            if (!launch.containsKey(e.getKey())) apps.add(new App(e.getKey(), e.getValue(), frozen.contains(e.getKey())));
        }
        return apps;
    }

    /** {@code package -> component} from {@code query-activities --brief} output. */
    static Map<String, String> parseLaunch(String out) {
        Map<String, String> launch = new LinkedHashMap<>();
        Matcher m = LAUNCH.matcher(out);
        while (m.find()) launch.putIfAbsent(m.group(1), m.group(2));
        return launch;
    }

    static List<App> build(Map<String, String> launch, List<String> frozen) {
        List<App> apps = new ArrayList<>();
        for (Map.Entry<String, String> e : launch.entrySet()) {
            apps.add(new App(e.getKey(), e.getKey() + "/" + e.getValue(), frozen.contains(e.getKey())));
        }
        return apps;
    }

    private static List<String> packages(String out) {
        List<String> list = new ArrayList<>();
        Matcher m = PACKAGE.matcher(out);
        while (m.find()) list.add(m.group(1));
        return list;
    }

    /** Puts a separate copy of an app (with its own data) into the profile. */
    static String add(int id, String pkg) {
        return failure(run("cmd package install-existing --user " + id + " " + Shell.quote(pkg), 60_000));
    }

    /** Takes the app out of the profile, with its data there. The main profile's copy is untouched. */
    static String delete(int id, String pkg) {
        return failure(run("pm uninstall --user " + id + " " + Shell.quote(pkg), 60_000));
    }

    /** A frozen app is off: it can't run, wake up or show in the launcher, and keeps its data. */
    static String freeze(int id, String pkg, boolean frozen) {
        return failure(run("pm " + (frozen ? "disable-user" : "enable") + " --user " + id + " "
                + Shell.quote(pkg), 30_000));
    }

    /** Freezes or unfreezes several apps in one go. */
    static String freezeAll(int id, List<String> pkgs, boolean frozen) {
        StringBuilder script = new StringBuilder();
        for (String pkg : pkgs) {
            script.append("pm ").append(frozen ? "disable-user" : "enable").append(" --user ").append(id)
                    .append(' ').append(Shell.quote(pkg)).append(" >/dev/null; ");
        }
        return script.length() == 0 ? null : failure(run(script.toString(), 120_000));
    }

    /** Wipes the app's data in the profile (the app stays). */
    static String clear(int id, String pkg) {
        return failure(run("pm clear --user " + id + " " + Shell.quote(pkg), 60_000));
    }

    /** Starts the app in the profile, waking the profile first if it was paused or stopped. */
    static String open(Context c, int id, String component) {
        String blocked = wake(c, id);
        if (blocked != null) return blocked;
        Shell.Result r = run("am start --user " + id + " -n " + Shell.quote(component), 20_000);
        return r.ok() && !r.out.contains("Error") ? null : r.out.isEmpty() ? "Failed." : r.out;
    }

    /** Opens an app of the profile, unfreezing it first when it is frozen. Used by shortcuts and the app list. */
    static String launch(Context c, String pkg) {
        int id = id(c);
        if (id < 0) return "There is no work profile.";
        for (App a : apps(c, id)) {
            if (!a.pkg.equals(pkg)) continue;
            String error = a.frozen ? freeze(id, pkg, false) : null;
            return error != null ? error : open(c, id, a.component);
        }
        return "That app isn't in the work profile any more.";
    }

    // ---- Its own PIN (the work challenge) -----------------------------------------------------------

    /** Whether this Android can set a lock on the profile ({@code cmd lock_settings}, Android 10). */
    static boolean lockSupported() {
        if (lockCommand != null) return true;
        boolean has = run("cmd lock_settings help", 8_000).out.contains("set-pin");
        if (has) lockCommand = true; // only a yes is remembered: a failed first try must not turn it off for good
        return has;
    }

    /**
     * What locks the profile: "PIN", "PASSWORD", "PATTERN", "NONE", or null when this Android doesn't
     * say (the report's wording changed between versions; the lock dialog works either way).
     */
    static String lockType(int id) {
        return parseLockType(run("dumpsys lock_settings", 10_000).out, id);
    }

    static String parseLockType(String dump, int id) {
        Matcher m = Pattern.compile("(?m)^\\s*User " + id + "\\s*$[\\s\\S]*?CredentialType: (\\w+)").matcher(dump);
        return m.find() ? m.group(1).toUpperCase(java.util.Locale.ROOT) : null; // "Pin" on Android 11, "PIN" on 14
    }

    /**
     * Sets, changes or removes the profile's own PIN. {@code old} is the current one ("" when there is
     * none), {@code pin} the new one ("" removes it). Returns null on success, else why not.
     */
    static String setLock(int id, String old, String pin) {
        if (!old.isEmpty() && !DIGITS.matcher(old).matches() || !pin.isEmpty() && !DIGITS.matcher(pin).matches()) {
            return "A PIN is 4 to 16 digits.";
        }
        if (pin.isEmpty() && old.isEmpty()) return "Nothing to change.";
        String before = old.isEmpty() ? "" : " --old " + Shell.quote(old);
        Shell.Result r = run("cmd lock_settings " + (pin.isEmpty() ? "clear" : "set-pin") + before + " --user " + id
                + (pin.isEmpty() ? "" : " " + Shell.quote(pin)), 20_000);
        if (r.out.contains("Pin set to") || r.out.contains("Lock credential cleared")) return null;
        if (r.out.contains("didn't match")) return "That current PIN isn't right.";
        if (r.out.contains("Error while executing")) {
            return "The profile already has a PIN. Type it in the first box to change or remove it.";
        }
        return r.out.isEmpty() ? "Failed." : r.out;
    }

    /** Enters the PIN to unlock a locked profile. Returns null on success, else why not. */
    static String unlock(Context c, int id, String pin) {
        if (!DIGITS.matcher(pin).matches()) return "A PIN is 4 to 16 digits.";
        Shell.Result r = run("cmd lock_settings verify --old " + Shell.quote(pin) + " --user " + id, 20_000);
        if (r.out.contains("didn't match")) return "That PIN isn't right.";
        if (!r.out.contains("verified")) return r.out.isEmpty() ? "Failed." : r.out;
        for (int i = 0; i < 10; i++) {
            if (userState(id) == RUNNING) return null;
            android.os.SystemClock.sleep(400);
        }
        return null;
    }

    // ---- Auto-freeze and auto-lock ------------------------------------------------------------------

    static Set<String> auto(SharedPreferences p) {
        return new HashSet<>(p.getStringSet(PREF_AUTO, Collections.emptySet()));
    }

    /** Something waits for the screen to go off, so {@link BackgroundService} has work to do. */
    static boolean wanted(SharedPreferences p) {
        return !auto(p).isEmpty() || p.getInt(PREF_LOCK, -1) >= 0;
    }

    private static PendingIntent alarm(Context c, String action, int which) {
        return PendingIntent.getBroadcast(c, which, new Intent(c, StartReceiver.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * The screen went off: freezes the chosen apps and locks the profile, each after the wait the user
     * picked. Two alarms do each waiting: a tight one for when the phone is awake enough to keep time,
     * and a loose one that Android lets through even in Doze, as a backup (doing it twice is harmless).
     */
    static void schedule(Context c, SharedPreferences p) {
        Context app = c.getApplicationContext();
        if (!auto(p).isEmpty()) arm(app, ACTION_FREEZE, 0, p.getInt(PREF_DELAY, 60), () -> autoFreeze(app));
        arm(app, ACTION_LOCK, 2, p.getInt(PREF_LOCK, -1), () -> autoLock(app));
    }

    private static void arm(Context c, String action, int which, int delay, Runnable now) {
        if (delay < 0) return;
        if (delay == 0) {
            new Thread(now, "work-auto").start();
            return;
        }
        try {
            long at = SystemClock.elapsedRealtime() + delay * 1000L;
            AlarmManager alarms = c.getSystemService(AlarmManager.class);
            alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, 10_000, alarm(c, action, which + 1));
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at + 10_000, alarm(c, action, which));
        } catch (RuntimeException e) {
            android.util.Log.w("TinyKit", "work alarm: " + e); // too many alarms, or this phone refuses: skipped
        }
    }

    /** The screen came on before the waits were over. */
    static void cancel(Context c) {
        try {
            AlarmManager alarms = c.getSystemService(AlarmManager.class);
            for (int i = 0; i < 4; i++) alarms.cancel(alarm(c, i < 2 ? ACTION_FREEZE : ACTION_LOCK, i));
        } catch (RuntimeException e) {
            android.util.Log.w("TinyKit", "work alarm: " + e);
        }
    }

    /** Freezes the chosen apps that aren't frozen yet. Waits briefly for Shizuku; does nothing without it. */
    static void autoFreeze(Context c) {
        Set<String> chosen = auto(c.getSharedPreferences("tinykit", Context.MODE_PRIVATE));
        if (chosen.isEmpty() || !Shell.await()) return;
        int id = id(c);
        if (id < 0) return;
        List<String> todo = new ArrayList<>();
        for (App a : apps(c, id)) if (!a.frozen && chosen.contains(a.pkg)) todo.add(a.pkg);
        freezeAll(id, todo, true);
    }

    /** Pauses (locks) the profile if it is running. */
    static void autoLock(Context c) {
        if (!Shell.await()) return;
        int id = id(c);
        if (id >= 0 && state(c, id) == RUNNING) pause(c, id, true);
    }

    /** After a restart: locks the profile once, if the user asked for that. Runs when Shizuku comes up. */
    static void lockAfterRestart(Context c, SharedPreferences p) {
        if (!p.getBoolean(PREF_LOCK_BOOT, false)) return;
        int boot = android.provider.Settings.Global.getInt(c.getContentResolver(),
                android.provider.Settings.Global.BOOT_COUNT, -1);
        if (boot != -1 && boot == p.getInt("work_locked_boot", -2)) return;
        p.edit().putInt("work_locked_boot", boot).apply();
        autoLock(c);
    }

    // ---- Shortcut -----------------------------------------------------------------------------------

    /**
     * Asks the launcher to pin a shortcut that unfreezes and opens the app (see {@link WorkOpen}), because
     * a frozen app is gone from the launcher. Returns null when the launcher was asked, else why not.
     */
    static String pin(Context c, String pkg, String label) {
        if (Build.VERSION.SDK_INT < 26) return "Needs Android 8 or newer.";
        try {
            ShortcutManager manager = c.getSystemService(ShortcutManager.class);
            if (manager == null || !manager.isRequestPinShortcutSupported()) return "Your launcher can't add shortcuts.";
            Drawable icon;
            try {
                icon = c.getPackageManager().getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                icon = c.getPackageManager().getDefaultActivityIcon();
            }
            Bitmap bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 192, 192);
            icon.draw(new Canvas(bitmap));
            ShortcutInfo info = new ShortcutInfo.Builder(c, "work:" + pkg)
                    .setShortLabel(label + " (" + Tr.t("work") + ")")
                    .setIcon(Icon.createWithBitmap(bitmap))
                    .setIntent(new Intent(c, WorkOpen.class).setAction(Intent.ACTION_VIEW).putExtra("pkg", pkg))
                    .build();
            return manager.requestPinShortcut(info, null) ? null : "Your launcher didn't add it.";
        } catch (RuntimeException | OutOfMemoryError e) {
            return "Your launcher didn't add it.";
        }
    }

    private static String failure(Shell.Result r) {
        return r.ok() ? null : r.out.isEmpty() ? "Failed." : r.out;
    }
}
