package io.github.order52.tinykit;

import android.content.AttributionSource;
import android.net.wifi.WifiConfiguration;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prints saved Wi-Fi networks with their passwords, one per line as SSID, type and password
 * separated by tabs.
 *
 * Not part of the app's UI: TinyKit starts it through Shizuku with app_process, so it runs as
 * the shell user, which Android lets read saved Wi-Fi credentials.
 */
public final class WifiDump {

    private static final String SHELL = "com.android.shell";

    public static void main(String[] args) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "wifi");
        Object wifi = Class.forName("android.net.wifi.IWifiManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Object slice = call(wifi, "getPrivilegedConfiguredNetworks", null);
        if (slice == null) throw new IllegalStateException("Not supported on this Android version");
        List<?> list = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
        if (args.length > 1 && args[0].equals("add")) {
            add(wifi, list, args[1]);
            return;
        }
        Map<String, String> lines = new LinkedHashMap<>();
        for (Object item : list) {
            WifiConfiguration c = (WifiConfiguration) item;
            String ssid = unquote(c.SSID);
            if (ssid == null || lines.containsKey(ssid)) continue;
            String type = "nopass";
            String password = "";
            if (c.preSharedKey != null && !c.preSharedKey.equals("*")) {
                type = "WPA";
                password = unquote(c.preSharedKey);
            } else if (c.wepKeys != null && c.wepKeys[0] != null) {
                type = "WEP";
                password = unquote(c.wepKeys[0]);
            } else if (c.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_EAP)) {
                type = "EAP";
            }
            lines.put(ssid, clean(ssid) + "\t" + type + "\t" + clean(password));
        }
        for (String line : lines.values()) System.out.println(line);
    }

    /** Calls an IWifiManager method whose arguments differ between Android versions, filling them by type. */
    private static Object call(Object wifi, String name, WifiConfiguration config) throws Exception {
        for (Method m : wifi.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] types = m.getParameterTypes();
            Object[] values = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == String.class) values[i] = i == 0 || config != null ? SHELL : null;
                else if (types[i] == Bundle.class) values[i] = extras();
                else if (types[i] == WifiConfiguration.class) values[i] = config;
            }
            return m.invoke(wifi, values);
        }
        return null;
    }

    /**
     * Saves the networks in a file of "SSID, type, password" lines that are not saved yet, and prints how
     * many it added. Open and WPA networks, and WEP ones where Android still takes them.
     */
    private static void add(Object wifi, List<?> saved, String file) throws Exception {
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Object item : saved) have.add(unquote(((WifiConfiguration) item).SSID));
        int added = 0;
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(file), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String[] part = line.split("\t", -1);
                if (part.length < 3 || part[0].isEmpty() || have.contains(part[0]) || part[1].equals("EAP")) continue;
                WifiConfiguration c = new WifiConfiguration();
                c.SSID = "\"" + part[0] + "\"";
                if (part[1].equals("nopass")) {
                    c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                } else if (part[1].equals("WEP")) {
                    c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    c.wepKeys[0] = "\"" + part[2] + "\"";
                    c.wepTxKeyIndex = 0;
                } else {
                    c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                    c.preSharedKey = "\"" + part[2] + "\"";
                }
                Object id = call(wifi, "addOrUpdateNetwork", c);
                if (id instanceof Integer && (Integer) id >= 0) {
                    added++;
                    have.add(part[0]);
                }
            }
        }
        System.out.println(added);
    }

    /** Android 12+ wants to know who is asking. */
    private static Bundle extras() {
        Bundle extras = new Bundle();
        if (Build.VERSION.SDK_INT >= 31) {
            extras.putParcelable("EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE",
                    new AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL).build());
        }
        return extras;
    }

    private static String unquote(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String clean(String value) {
        return value.replace('\t', ' ').replace('\n', ' ');
    }
}
