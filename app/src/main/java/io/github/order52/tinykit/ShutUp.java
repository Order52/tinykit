package io.github.order52.tinykit;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Shut Up: for the apps you choose, Developer options, USB debugging and Wireless debugging look
 * switched off while the app is on screen, and come back the moment you leave it.
 *
 * Android has no way to hide a setting from one app, so this flips the settings themselves: they are
 * turned off when a chosen app comes to the front and restored when it leaves ({@link ShutUpService}
 * watches which app is in front). What was on is written down first, so a crash, a restart or turning
 * Shut Up off always puts it back. Apps that checked before TinyKit noticed them (the first moments of
 * a cold start) can still see it, and an app that remembers an earlier check can't be helped.
 */
final class ShutUp {
    static final String PREF_ON = "shutup_on";
    static final String PREF_APPS = "shutup_apps";
    /** The settings that were on when they were turned off, "key,key"; empty when nothing is hidden. */
    static final String PREF_SAVED = "shutup_saved";
    /** Also stop Shizuku while a chosen app is open (some apps notice it running). On unless turned off. */
    static final String PREF_STOP_SHIZUKU = "shutup_stop_shizuku";
    /** Set while Shizuku is stopped by Shut Up and still has to be started again. */
    static final String PREF_SHIZUKU_STOPPED = "shutup_shizuku_stopped";

    /** Restore order matters: Developer options first, the debugging switches need it. */
    static final String[] KEYS = {"development_settings_enabled", "adb_enabled", "adb_wifi_enabled"};

    private ShutUp() {
    }

    static Set<String> apps(SharedPreferences p) {
        return new HashSet<>(p.getStringSet(PREF_APPS, Collections.emptySet()));
    }

    static boolean needed(SharedPreferences p) {
        return p.getBoolean(PREF_ON, false) && !apps(p).isEmpty();
    }

    static boolean hidden(SharedPreferences p) {
        return !p.getString(PREF_SAVED, "").isEmpty();
    }

    /** Whether Shut Up stops Shizuku while a chosen app is open: wanted, and it can be started again. */
    static boolean stopsShizuku(Context c, SharedPreferences p) {
        return p.getBoolean(PREF_STOP_SHIZUKU, true) && ShizukuControl.canRestart(c, p);
    }

    /** Stops Shizuku once per visit to a chosen app. Call after {@link #hide}, off the main thread. */
    static void stopShizuku(Context c, SharedPreferences p) {
        if (!stopsShizuku(c, p) || !Shell.running()) return;
        // Written down first, so a crash still starts it again.
        p.edit().putBoolean(PREF_SHIZUKU_STOPPED, true).commit();
        ShizukuControl.stop(c, p);
        android.util.Log.i("TinyKit", "Shut Up: stopped Shizuku");
    }

    /**
     * Starts Shizuku again if Shut Up stopped it, or if turning USB debugging off ended it. Call off
     * the main thread, once the chosen app is gone.
     */
    static void startShizuku(Context c, SharedPreferences p) {
        if (!p.getBoolean(PREF_SHIZUKU_STOPPED, false)) return;
        p.edit().putBoolean(PREF_SHIZUKU_STOPPED, false).commit();
        if (Shell.running()) return; // something else already started it
        boolean ok = ShizukuControl.start(c, p);
        android.util.Log.i("TinyKit", "Shut Up: started Shizuku ok=" + ok);
    }

    /** Adds or removes an app. Returns null when it worked. */
    static String choose(Context c, SharedPreferences p, String pkg, boolean on) {
        Set<String> s = apps(p);
        if (on) s.add(pkg); else s.remove(pkg);
        p.edit().putStringSet(PREF_APPS, s).apply();
        ShutUpService.sync(c);
        return null;
    }

    /** Turns the settings the phone has on off, remembering which. Call off the main thread. */
    static synchronized boolean hide(Context c, SharedPreferences p) {
        StringBuilder saved = new StringBuilder(p.getString(PREF_SAVED, ""));
        for (String key : KEYS) {
            if (!"1".equals(Tweak.readGlobal(c, key))) continue;
            if (!has(saved.toString(), key)) saved.append(saved.length() == 0 ? "" : ",").append(key);
        }
        if (saved.length() == 0) return true;
        // Turning USB or Wireless debugging off can end a Shizuku that was started through adb.
        // Remember it was running, so it is started again afterwards.
        if (Shell.running() && ShizukuControl.canRestart(c, p)) p.edit().putBoolean(PREF_SHIZUKU_STOPPED, true).commit();
        // Written down before anything changes, so it can always be put back.
        p.edit().putString(PREF_SAVED, saved.toString()).commit();
        boolean ok = true;
        for (int i = KEYS.length - 1; i >= 0; i--) {
            if (has(saved.toString(), KEYS[i]) && "1".equals(Tweak.readGlobal(c, KEYS[i]))) {
                ok &= Tweak.writeGlobal(c, KEYS[i], "0");
            }
        }
        return ok;
    }

    /** Puts back what {@link #hide} turned off. Call off the main thread. */
    static synchronized boolean restore(Context c, SharedPreferences p) {
        String saved = p.getString(PREF_SAVED, "");
        if (saved.isEmpty()) return true;
        boolean ok = true;
        for (String key : KEYS) {
            if (has(saved, key)) ok &= Tweak.writeGlobal(c, key, "1");
        }
        if (ok) p.edit().putString(PREF_SAVED, "").commit();
        return ok;
    }

    private static boolean has(String list, String key) {
        for (String k : list.split(",")) if (k.equals(key)) return true;
        return false;
    }

    /** Whether TinyKit can change the settings and see the front app without Shizuku. */
    static boolean ready(Context c) {
        return Tweak.canWrite(c) && Tweak.usageAllowed(c);
    }

    /**
     * Follows the app in front by reading Android's usage events. Create one per service run and call
     * {@link #poll} on a background thread.
     */
    static final class Watcher {
        private final Context c;
        private final UsageStatsManager usm;
        private final PowerManager power;
        private long last;
        private String front;

        Watcher(Context c) {
            this.c = c;
            usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
            power = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            last = System.currentTimeMillis() - 3_600_000L; // what was already open before TinyKit looked
        }

        boolean interactive() {
            return power.isInteractive();
        }

        /** The package in front right now, or null (screen off, or nothing known). */
        String poll() {
            long now = System.currentTimeMillis();
            UsageEvents events = usm.queryEvents(last + 1, now);
            UsageEvents.Event e = new UsageEvents.Event();
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(e);
                last = Math.max(last, e.getTimeStamp());
                // 1: an activity came to the front, 2: it went away (the long-standing event values).
                if (e.getEventType() == 1) front = e.getPackageName();
                else if (e.getEventType() == 2 && e.getPackageName().equals(front)) front = null;
            }
            return interactive() ? front : null;
        }
    }
}
