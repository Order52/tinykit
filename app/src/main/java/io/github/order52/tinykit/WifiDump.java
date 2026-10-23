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
        Object slice = null;
        for (Method m : wifi.getClass().getMethods()) {
            if (!m.getName().equals("getPrivilegedConfiguredNetworks")) continue;
            Class<?>[] types = m.getParameterTypes();
            Object[] values = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (types[i] == String.class) values[i] = i == 0 ? SHELL : null;
                else if (types[i] == Bundle.class) values[i] = extras();
            }
            slice = m.invoke(wifi, values);
            break;
        }
        if (slice == null) throw new IllegalStateException("Not supported on this Android version");
        List<?> list = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
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
