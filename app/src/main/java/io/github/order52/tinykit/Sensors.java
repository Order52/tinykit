package io.github.order52.tinykit;

import android.os.IBinder;

import java.lang.reflect.Method;

/**
 * Toggles the global "all sensors" privacy — the developer "Sensors off" tile some manufacturers
 * remove — through ISensorPrivacyManager. TinyKit starts it with app_process (as the shell user)
 * via Shizuku, the same way the SensorsOff app does. Nothing runs in the background.
 *
 * Args: on | off | state | list
 */
public final class Sensors {

    public static void main(String[] args) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "sensor_privacy");
        Object spm = Class.forName("android.hardware.ISensorPrivacyManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        String cmd = args.length > 0 ? args[0] : "state";

        if (cmd.equals("list")) {
            for (Method m : spm.getClass().getMethods()) {
                if (m.getName().toLowerCase().contains("sensorprivacy")) System.out.println(m);
            }
            return;
        }

        if (cmd.equals("state")) {
            for (Method m : spm.getClass().getMethods()) {
                if (m.getName().equals("isSensorPrivacyEnabled") && m.getParameterTypes().length == 0) {
                    System.out.println(m.invoke(spm));
                    return;
                }
            }
            System.out.println("unknown");
            return;
        }

        boolean enable = cmd.equals("on");
        for (Method m : spm.getClass().getMethods()) {
            if (!m.getName().equals("setSensorPrivacy")) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == boolean.class) {
                m.invoke(spm, enable);
                System.out.println("OK");
                return;
            }
            if (p.length == 2 && p[0] == int.class && p[1] == boolean.class) {
                m.invoke(spm, 0, enable);
                System.out.println("OK");
                return;
            }
        }
        throw new IllegalStateException("setSensorPrivacy(boolean) not found");
    }
}
