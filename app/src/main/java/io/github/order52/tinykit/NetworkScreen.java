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

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class NetworkScreen {
    private final MainActivity tk;

    NetworkScreen(MainActivity tk) {
        this.tk = tk;
    }

    /** Local network details render instantly; only the public IP is fetched in the background. */
    void openNetworkInfo() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), tk.dp(24));
        ScrollView scroll = new ScrollView(tk);
        scroll.addView(page);
        tk.push("Network info", scroll);

        ConnectivityManager cm = (ConnectivityManager) tk.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        LinkProperties lp = network == null ? null : cm.getLinkProperties(network);

        tk.section(page, "Connection");
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
            WifiManager wm = (WifiManager) tk.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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
            tk.section(page, "Addresses");
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
            tk.section(page, "Router");
            tk.row(page, "Open router settings", "Opens " + url + " in your browser.", tk.arrow(),
                    () -> tk.openUrl(url), false);
        }

        tk.section(page, "Internet");
        TextView publicIp = addKv(page, "Public IP", "Checking…");
        boolean online = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        if (!online) {
            publicIp.setText(Tr.t("Offline"));
        } else {
            tk.worker.execute(() -> {
                String pub = httpGet("https://api.ipify.org", 3000);
                if (pub == null) pub = httpGet("https://ifconfig.me/ip", 3000);
                String result = pub != null && pub.length() <= 45 && pub.matches("[0-9a-fA-F:.]+")
                        ? pub : "Couldn't reach the internet";
                tk.main.post(() -> publicIp.setText(Tr.t(result)));
            });
        }
    }

    /** Adds a dim key line and a selectable value line, returning the value view so it can update. */
    TextView addKv(LinearLayout page, String key, String value) {
        TextView k = tk.text(key, 13, false);
        tk.setDim(k, 0.7f);
        k.setPadding(0, tk.dp(8), 0, 0);
        page.addView(k);
        TextView v = tk.text(value, 16, false);
        v.setTextIsSelectable(true);
        page.addView(v);
        return v;
    }

    /** A short-timeout HTTP GET returning the trimmed body, or null. Call off the main thread. */
    static String httpGet(String url, int timeoutMs) {
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

    void openChargingInfo() {
        tk.infoPage("Charging info", () -> {
            List<String[]> info = new ArrayList<>();
            Intent battery = tk.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            BatteryManager bm = (BatteryManager) tk.getSystemService(Context.BATTERY_SERVICE);
            info.add(new String[]{"Now", null});
            if (battery != null) {
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, 1);
                info.add(new String[]{"Status", tk.batteryStatus(status)});
                int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                String source = plugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC charger"
                        : plugged == BatteryManager.BATTERY_PLUGGED_USB ? "USB"
                        : plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ? "Wireless" : "Not plugged in";
                info.add(new String[]{"Power source", source});
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                info.add(new String[]{"Charge", level * 100 / Math.max(scale, 1) + "%"});
                info.add(new String[]{"Health", tk.batteryHealth(battery.getIntExtra(BatteryManager.EXTRA_HEALTH, 1))});
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
                for (String line : Shell.run("dumpsys batterystats 2>/dev/null | grep -m3 -iE "
                        + "'Estimated battery capacity|learned battery capacity'").out.split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && line.toLowerCase(Locale.ROOT).contains("battery capacity")) {
                        info.add(new String[]{line.substring(0, colon).trim(),
                                line.substring(colon + 1).trim()});
                    }
                }
            }
            return info;
        });
    }

    void openTemperatures() {
        tk.infoPage("Temperatures", () -> {
            List<String[]> info = new ArrayList<>();
            Intent battery = tk.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
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

    void openDeviceIds() {
        tk.infoPage("Device IDs", () -> {
            List<String[]> info = new ArrayList<>();
            info.add(new String[]{"Identifiers", null});
            info.add(new String[]{"Android ID",
                    Settings.Secure.getString(tk.getContentResolver(), Settings.Secure.ANDROID_ID)});
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
}
