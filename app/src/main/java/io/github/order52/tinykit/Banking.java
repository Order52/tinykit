package io.github.order52.tinykit;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Banking mode: one switch for apps that refuse to run (banking, payment and some game apps) when
 * the phone looks like it is being worked on. While it is on, Developer options, USB debugging and
 * Wireless debugging are off, and Shizuku is stopped when TinyKit can start it again. Turning it off
 * puts back exactly what was on before.
 *
 * What was on is written down before anything changes, so a restart or a crash never loses it.
 * Unlike {@link ShutUp} nothing runs in the background: it is a state, not a watcher. TinyKit writes
 * the settings through its own WRITE_SECURE_SETTINGS, so turning it off works without Shizuku.
 */
final class Banking {
    static final String PREF_ON = "banking_on";
    /** The settings that were on, "key,key"; empty when nothing was changed. */
    static final String PREF_SAVED = "banking_saved";
    /** Set while Shizuku was running when Banking mode came on and has to be started again. */
    static final String PREF_SHIZUKU = "banking_shizuku";

    /** Developer options first when putting back: the debugging switches need it. */
    private static final String[] KEYS = {"development_settings_enabled", "adb_enabled", "adb_wifi_enabled"};
    private static final String[] NAMES = {"Developer options", "USB debugging", "Wireless debugging"};

    private Banking() {
    }

    static boolean on(SharedPreferences p) {
        return p.getBoolean(PREF_ON, false);
    }

    /** Which of the settings in {@code saved} ("a,b") are named, in the order they are put back. */
    static boolean has(String saved, String key) {
        for (String k : saved.split(",")) if (k.equals(key)) return true;
        return false;
    }

    /** Names of the settings that are on right now (what a banking app would see), "" when all are off. */
    static String stillOn(Context c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < KEYS.length; i++) {
            if ("1".equals(Tweak.readGlobal(c, KEYS[i]))) sb.append(sb.length() == 0 ? "" : ", ").append(NAMES[i]);
        }
        return sb.toString();
    }

    /**
     * While Banking mode is on, turns off again whatever has come back on (a restart, Shizuku's own
     * auto-start, the phone's USB settings) and remembers it for turning Banking mode off. Returns
     * true when nothing is on afterwards. Needs no Shizuku. Call off the main thread.
     */
    static synchronized boolean reapply(Context c, SharedPreferences p) {
        if (!on(p)) return true;
        String saved = p.getString(PREF_SAVED, "");
        StringBuilder now = new StringBuilder(saved);
        for (String key : KEYS) {
            if ("1".equals(Tweak.readGlobal(c, key)) && !has(now.toString(), key)) now.append(now.length() == 0 ? "" : ",").append(key);
        }
        if (stillOn(c).isEmpty()) return true;
        if (!Tweak.canWrite(c)) return false;
        p.edit().putString(PREF_SAVED, now.toString()).commit();
        boolean ok = true;
        for (int i = KEYS.length - 1; i >= 0; i--) {
            if ("1".equals(Tweak.readGlobal(c, KEYS[i]))) ok &= Tweak.writeGlobal(c, KEYS[i], "0");
        }
        android.util.Log.i("TinyKit", "Banking mode: turned off again " + stillOn(c) + " ok=" + ok);
        return ok && stillOn(c).isEmpty();
    }

    /**
     * Turns Banking mode on. Returns null when it worked, else what is missing. Needs Shizuku once, to
     * give TinyKit the access to change these settings. Call off the main thread.
     */
    static synchronized String enable(Context c, SharedPreferences p) {
        Tweak.grantSelf(c);
        if (!Tweak.canWrite(c)) {
            return "Start Shizuku and allow TinyKit once, so Banking mode can change these settings.";
        }
        StringBuilder saved = new StringBuilder();
        for (String key : KEYS) {
            if ("1".equals(Tweak.readGlobal(c, key))) saved.append(saved.length() == 0 ? "" : ",").append(key);
        }
        // Turning debugging off can end a Shizuku that was started through adb, or it is stopped on purpose
        // below. Remember it was running, so it can be started again.
        boolean shizuku = Shell.running();
        // Written down before anything changes, so it can always be put back.
        p.edit().putString(PREF_SAVED, saved.toString()).putBoolean(PREF_SHIZUKU, shizuku).putBoolean(PREF_ON, true).commit();
        boolean ok = true;
        for (int i = KEYS.length - 1; i >= 0; i--) {
            if (has(saved.toString(), KEYS[i])) ok &= Tweak.writeGlobal(c, KEYS[i], "0");
        }
        String left = stillOn(c);
        if (!ok || !left.isEmpty()) {
            disable(c, p);
            return "Couldn't turn these off: " + left + ". Android or the phone's maker may block it.";
        }
        // Some apps notice Shizuku running. Only stopped when TinyKit can bring it back.
        if (shizuku && Shell.running() && ShizukuControl.canRestart(c, p)) ShizukuControl.stop(c, p);
        return null;
    }

    /**
     * Turns Banking mode off and puts back what was on. Returns null when everything is back, else what
     * to do. Call off the main thread.
     */
    static synchronized String disable(Context c, SharedPreferences p) {
        String saved = p.getString(PREF_SAVED, "");
        boolean ok = true;
        for (String key : KEYS) {
            if (has(saved, key)) ok &= Tweak.writeGlobal(c, key, "1");
        }
        if (!ok) return "Couldn't put the settings back. Turn them on in Settings > Developer options.";
        boolean shizuku = p.getBoolean(PREF_SHIZUKU, false);
        p.edit().putString(PREF_SAVED, "").putBoolean(PREF_SHIZUKU, false).putBoolean(PREF_ON, false).commit();
        if (shizuku && !Shell.running()) {
            if (!ShizukuControl.canRestart(c, p)) return "Banking mode is off. Start Shizuku again from the Shizuku tab.";
            ShizukuControl.start(c, p);
        }
        return null;
    }
}
