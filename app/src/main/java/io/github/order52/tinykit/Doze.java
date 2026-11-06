package io.github.order52.tinykit;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deep sleep: how soon after the screen goes off Android's Doze starts, and how rarely it wakes up again. The
 * profiles are Comatose's (Light to Extreme). Unlike writing the one global setting, which Android 12 and 13
 * ignore and some 14s too, this writes where the system reads on each version, then reads back what Android is
 * really using and says so.
 */
final class Doze {
    static final String PREF = "doze_profile";
    static final String[] NAMES = {"Default", "Light", "Moderate", "High", "Extreme"};
    static final String[] SUMMARY = {
            "Android's own timing.",
            "Doze 10 minutes after the screen goes off. Doesn't change day-to-day use.",
            "Doze after a minute, wakes every 2 hours. Notifications arrive a little later.",
            "Doze after 30 seconds, wakes every 4 hours. Background work is delayed.",
            "Doze as soon as the screen is off, wakes every 6 hours. The most battery saved, the most delayed."};

    /** Key, then Light, Moderate, High, Extreme in seconds (the accuracy in metres); -1 is "not set". */
    private static final Object[][] TABLE = {
            {"light_after_inactive_to", 120, 60, 5, 0},
            {"light_pre_idle_to", 120, 60, 30, 5},
            {"light_idle_to", 600, 900, 1800, 3600},
            {"light_max_idle_to", 3600, 10800, 21600, 43200},
            {"locating_to", 120, 60, 10, 5},
            {"location_accuracy", 50, 100, 500, 1000},
            {"inactive_to", 600, 60, 30, 0},
            {"sensing_to", 120, 60, 30, 0},
            {"motion_inactive_to", 300, 60, 30, 0},
            {"idle_after_inactive_to", 300, 60, 30, 0},
            {"idle_to", -1, 7200, 14400, 21600},
            {"max_idle_to", -1, 28800, 43200, 172800},
            {"quick_doze_delay_to", -1, 30, 10, 5},
            {"min_time_to_alarm", -1, 1800, 600, 300}};

    private static final String SETTING = "device_idle_constants";

    private Doze() {
    }

    /** One profile as "key=value,…", or null for Default. */
    static String constants(int profile) {
        if (profile <= 0) return null;
        StringBuilder out = new StringBuilder();
        for (Object[] row : TABLE) {
            int v = (Integer) row[profile];
            if (v < 0) continue;
            out.append(out.length() > 0 ? "," : "").append(row[0]).append('=')
                    .append(row[0].equals("location_accuracy") ? v : v * 1000L);
        }
        return out.toString();
    }

    /** Puts a profile in force, where this Android reads it. Returns null when written, else why not. Off the main thread. */
    static String apply(Context c, int profile) {
        StringBuilder script = new StringBuilder();
        boolean config = Build.VERSION.SDK_INT >= 31; // Android 12 on reads DeviceConfig; before that, the global setting
        if (profile == 0) {
            script.append("settings delete global ").append(SETTING).append(" >/dev/null 2>&1; ");
            if (config) {
                for (Object[] row : TABLE) script.append("device_config delete device_idle ").append(row[0]).append(" >/dev/null 2>&1; ");
                script.append("device_config set_sync_disabled_for_tests none >/dev/null 2>&1; ");
            }
        } else {
            script.append("settings put global ").append(SETTING).append(" \"").append(constants(profile)).append("\"; ");
            if (config) {
                script.append("device_config set_sync_disabled_for_tests persistent; ");
                for (Object[] row : TABLE) {
                    int v = (Integer) row[profile];
                    if (v < 0) {
                        script.append("device_config delete device_idle ").append(row[0]).append(" >/dev/null 2>&1; ");
                    } else {
                        script.append("device_config put device_idle ").append(row[0]).append(' ')
                                .append(row[0].equals("location_accuracy") ? v : v * 1000L).append("; ");
                    }
                }
            }
        }
        Shell.Result r = Shell.run(script.toString());
        return r.ok() ? null : "Couldn't change deep sleep. Is Shizuku running?";
    }

    /** What Android is using right now: its Doze settings (milliseconds) and the two states. */
    static final class Status {
        final Map<String, Long> settings = new HashMap<>();
        String state = "";

        /** Whether those settings are the profile's. Default has nothing to compare, so it counts. */
        boolean matches(int profile) {
            if (profile <= 0) return true;
            for (Object[] row : TABLE) {
                int v = (Integer) row[profile];
                Long now = settings.get(row[0]);
                if (v < 0 || now == null || row[0].equals("location_accuracy")) continue;
                if (now != v * 1000L) return false;
            }
            return true;
        }
    }

    private static final Pattern PART = Pattern.compile("(\\d+)(ms|d|h|m|s)");

    static Status read() {
        Status status = new Status();
        String out = Shell.run("dumpsys deviceidle").out;
        boolean inSettings = false;
        String deep = "", light = "";
        for (String line : out.split("\n")) {
            if (line.startsWith("  Settings:")) {
                inSettings = true;
                continue;
            }
            if (inSettings) {
                int eq = line.indexOf('=');
                if (!line.startsWith("    ") || eq < 0) {
                    inSettings = false;
                } else {
                    Long ms = duration(line.substring(eq + 1));
                    if (ms != null) status.settings.put(line.substring(4, eq), ms);
                    continue;
                }
            }
            Matcher m = Pattern.compile("mState=(\\w+)").matcher(line);
            if (m.find()) deep = m.group(1);
            m = Pattern.compile("mLightState=(\\w+)").matcher(line);
            if (m.find()) light = m.group(1);
        }
        status.state = deep.isEmpty() ? "" : Tr.t("Deep sleep") + ": " + deep + " · " + Tr.t("Light") + ": " + light;
        return status;
    }

    /** "+1h0m30s0ms" in milliseconds; null if it isn't a duration. */
    static Long duration(String text) {
        String t = text.trim();
        if (!t.startsWith("+") && !t.startsWith("-")) return null;
        Matcher m = PART.matcher(t);
        long ms = 0;
        boolean any = false;
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "d": ms += n * 86_400_000L; break;
                case "h": ms += n * 3_600_000L; break;
                case "m": ms += n * 60_000L; break;
                case "s": ms += n * 1000L; break;
                default: ms += n;
            }
            any = true;
        }
        return any ? ms : null;
    }

    /** Doze right now ("deep" sleep), until the phone is used again; or back to normal. */
    static String force(boolean on) {
        Shell.Result r = Shell.run(on ? "dumpsys deviceidle force-idle" : "dumpsys deviceidle unforce");
        return r.ok() ? null : "Couldn't change deep sleep. Is Shizuku running?";
    }

    /** After Shizuku starts: puts the chosen profile back if Android lost it (a restart can reset it). */
    static void restore(Context c, SharedPreferences p) {
        int profile = p.getInt(PREF, 0);
        if (profile > 0 && !read().matches(profile)) apply(c, profile);
    }
}
