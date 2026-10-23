package io.github.order52.tinykit;

import android.app.AppOpsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Process;
import android.provider.Settings;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The settings TinyKit flips, shared by the app and its Quick Settings tiles.
 *
 * TinyKit grants itself WRITE_SECURE_SETTINGS through Shizuku once, after which these work
 * without Shizuku running. When a write is refused (Huawei locks some keys for apps), it goes
 * through Shizuku's shell instead.
 */
enum Tweak {
    HIDE_DEV, DEV_OPTIONS, USB_DEBUG, AD_DNS, NO_SCAN, FREE_HOTSPOT, NO_MOBILE_ON_WIFI,
    GRAYSCALE, EXTRA_DIM, MIC_CAM_OFF;

    static final String DNS_HOST = "dns.adguard-dns.com";
    static final int[] TIMEOUTS = {30_000, 60_000, 300_000, 1_800_000, Integer.MAX_VALUE};
    private static final int GLOBAL = 0, SECURE = 1, SYSTEM = 2;
    // Android 12 reports "sensor=N … is_enabled=true"; Android 14 nests "sensor=N … state_type=1"
    // (1 = privacy on, 2 = off). The tile reads both so its state is right on either.
    private static final Pattern SENSOR = Pattern.compile("sensor=(\\d+)\\s+is_enabled=(true|false)");
    private static final Pattern SENSOR_STATE = Pattern.compile("sensor=(\\d+)[\\s\\S]*?state_type=(\\d+)");

    /** Call off the main thread: MIC_CAM_OFF asks Shizuku. */
    boolean isOn(Context c) {
        switch (this) {
            case HIDE_DEV:
                return "0".equals(get(c, GLOBAL, "development_settings_enabled"));
            case DEV_OPTIONS:
                return "1".equals(get(c, GLOBAL, "development_settings_enabled"));
            case USB_DEBUG:
                return "1".equals(get(c, GLOBAL, "adb_enabled"));
            case AD_DNS:
                return "hostname".equals(get(c, GLOBAL, "private_dns_mode"))
                        && DNS_HOST.equals(get(c, GLOBAL, "private_dns_specifier"));
            case NO_SCAN:
                return "0".equals(get(c, GLOBAL, "wifi_scan_always_enabled"))
                        && "0".equals(get(c, GLOBAL, "ble_scan_always_enabled"));
            case FREE_HOTSPOT:
                return "0".equals(get(c, GLOBAL, "tether_dun_required"));
            case NO_MOBILE_ON_WIFI:
                return "0".equals(get(c, GLOBAL, "mobile_data_always_on"));
            case GRAYSCALE:
                return "1".equals(get(c, SECURE, "accessibility_display_daltonizer_enabled"))
                        && "0".equals(get(c, SECURE, "accessibility_display_daltonizer"));
            case EXTRA_DIM:
                return "1".equals(get(c, SECURE, "reduce_bright_colors_activated"));
            default:
                if (!Shell.ready()) return false;
                String out = Shell.run("dumpsys sensor_privacy").out;
                boolean mic = false, camera = false;
                Matcher m = SENSOR.matcher(out);
                while (m.find()) {
                    boolean on = m.group(2).equals("true");
                    if (m.group(1).equals("1")) mic = on;
                    if (m.group(1).equals("2")) camera = on;
                }
                Matcher s = SENSOR_STATE.matcher(out);
                while (s.find()) {
                    boolean on = s.group(2).equals("1");
                    if (s.group(1).equals("1")) mic = on;
                    if (s.group(1).equals("2")) camera = on;
                }
                return mic && camera;
        }
    }

    /** Whether this can be changed right now. */
    boolean usable(Context c) {
        return this == MIC_CAM_OFF ? Shell.ready() : canWrite(c) || Shell.ready();
    }

    /** Returns null when it worked, otherwise what went wrong. Call off the main thread. */
    String set(Context c, boolean on) {
        if (!usable(c)) return "Start Shizuku once so TinyKit can change this.";
        boolean ok;
        switch (this) {
            case HIDE_DEV:
                ok = put(c, GLOBAL, "development_settings_enabled", on ? "0" : "1");
                break;
            case DEV_OPTIONS:
                ok = put(c, GLOBAL, "development_settings_enabled", on ? "1" : "0");
                break;
            case USB_DEBUG:
                ok = put(c, GLOBAL, "adb_enabled", on ? "1" : "0");
                break;
            case AD_DNS:
                ok = setDns(c, on);
                break;
            case NO_SCAN:
                ok = put(c, GLOBAL, "wifi_scan_always_enabled", on ? "0" : "1")
                        & put(c, GLOBAL, "ble_scan_always_enabled", on ? "0" : "1");
                break;
            case FREE_HOTSPOT:
                ok = put(c, GLOBAL, "tether_dun_required", on ? "0" : null);
                break;
            case NO_MOBILE_ON_WIFI:
                ok = put(c, GLOBAL, "mobile_data_always_on", on ? "0" : "1");
                break;
            case GRAYSCALE:
                ok = (!on || put(c, SECURE, "accessibility_display_daltonizer", "0"))
                        && put(c, SECURE, "accessibility_display_daltonizer_enabled", on ? "1" : "0");
                break;
            case EXTRA_DIM:
                ok = put(c, SECURE, "reduce_bright_colors_activated", on ? "1" : "0");
                break;
            default:
                String verb = on ? "enable" : "disable";
                ok = Shell.run("cmd sensor_privacy " + verb + " 0 microphone && "
                        + "cmd sensor_privacy " + verb + " 0 camera").ok();
        }
        return ok ? null : "This phone didn't accept the change.";
    }

    static int timeout(Context c) {
        try {
            return Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT);
        } catch (Settings.SettingNotFoundException e) {
            return 30_000;
        }
    }

    /** Call off the main thread. */
    static boolean setTimeout(Context c, int ms) {
        return put(c, SYSTEM, Settings.System.SCREEN_OFF_TIMEOUT, String.valueOf(ms));
    }

    static String timeoutLabel(int ms) {
        if (ms >= 24 * 3_600_000) return "Never";
        if (ms < 60_000) return ms / 1000 + " s";
        return ms / 60_000 + " min";
    }

    static boolean canWrite(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean usageAllowed(Context c) {
        AppOpsManager ops = c.getSystemService(AppOpsManager.class);
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * Lets TinyKit change settings and read usage itself, so toggles and tiles keep working
     * when Shizuku is off. Done once through Shizuku; call off the main thread.
     */
    static void grantSelf(Context c) {
        if (canWrite(c) && usageAllowed(c) || !Shell.ready()) return;
        String p = c.getPackageName();
        Shell.run("pm grant " + p + " android.permission.WRITE_SECURE_SETTINGS; "
                + "appops set " + p + " WRITE_SETTINGS allow; appops set " + p + " GET_USAGE_STATS allow");
    }

    private static boolean setDns(Context c, boolean on) {
        SharedPreferences prefs = c.getSharedPreferences("tinykit", Context.MODE_PRIVATE);
        if (on) {
            // Remember the previous Private DNS so turning this off restores it.
            prefs.edit()
                    .putString("dns_mode", get(c, GLOBAL, "private_dns_mode"))
                    .putString("dns_host", get(c, GLOBAL, "private_dns_specifier"))
                    .apply();
            return put(c, GLOBAL, "private_dns_specifier", DNS_HOST)
                    & put(c, GLOBAL, "private_dns_mode", "hostname");
        }
        String mode = previous(prefs.getString("dns_mode", null));
        return put(c, GLOBAL, "private_dns_mode", "hostname".equals(mode)
                        && previous(prefs.getString("dns_host", null)) == null ? null : mode)
                & put(c, GLOBAL, "private_dns_specifier", previous(prefs.getString("dns_host", null)));
    }

    private static String previous(String value) {
        return value == null || value.equals("null") || value.equals(DNS_HOST) ? null : value;
    }

    private static String get(Context c, int table, String key) {
        ContentResolver r = c.getContentResolver();
        try {
            switch (table) {
                case GLOBAL: return Settings.Global.getString(r, key);
                case SECURE: return Settings.Secure.getString(r, key);
                default: return Settings.System.getString(r, key);
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean put(Context c, int table, String key, String value) {
        if (canWrite(c)) {
            ContentResolver r = c.getContentResolver();
            try {
                boolean ok;
                switch (table) {
                    case GLOBAL: ok = Settings.Global.putString(r, key, value); break;
                    case SECURE: ok = Settings.Secure.putString(r, key, value); break;
                    default: ok = Settings.System.putString(r, key, value);
                }
                if (ok) return true;
            } catch (RuntimeException ignored) {
            }
        }
        String name = table == GLOBAL ? "global" : table == SECURE ? "secure" : "system";
        return Shell.run(value == null ? "settings delete " + name + " " + key
                : "settings put " + name + " " + key + " " + Shell.quote(value)).ok();
    }
}
