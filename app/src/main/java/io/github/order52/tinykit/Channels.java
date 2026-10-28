package io.github.order52.tinykit;

import android.app.NotificationChannel;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Lists an app's notification channels and changes how important one is, through the system's
 * notification service. TinyKit starts it with app_process as the shell user via Shizuku, does one
 * thing and exits. Nothing runs afterwards.
 *
 * Args: list PACKAGE UID | set PACKAGE UID CHANNEL_ID IMPORTANCE
 * (list prints one "id TAB name TAB importance" line per channel; importance 0 is blocked)
 */
@android.annotation.TargetApi(26) // only started on Android 8+; the screen is hidden below that
public final class Channels {
    public static void main(String[] args) {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "notification");
            Object nm = Class.forName("android.app.INotificationManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            String pkg = args[1];
            int uid = Integer.parseInt(args[2]);
            Object slice = nm.getClass().getMethod("getNotificationChannelsForPackage",
                    String.class, int.class, boolean.class).invoke(nm, pkg, uid, false);
            List<?> channels = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
            if (args[0].equals("list")) {
                for (Object o : channels) {
                    NotificationChannel c = (NotificationChannel) o;
                    System.out.println(c.getId() + "\t" + c.getName() + "\t" + c.getImportance());
                }
                return;
            }
            if (!args[0].equals("set")) throw new IllegalArgumentException("unknown command " + args[0]);
            for (Object o : channels) {
                NotificationChannel c = (NotificationChannel) o;
                if (!c.getId().equals(args[3])) continue;
                c.setImportance(Integer.parseInt(args[4]));
                Method update = nm.getClass().getMethod("updateNotificationChannelForPackage",
                        String.class, int.class, NotificationChannel.class);
                update.invoke(nm, pkg, uid, c);
                System.out.println("OK");
                return;
            }
            throw new IllegalArgumentException("no channel " + args[3]);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.out.println("Couldn't change notification channels: " + cause);
            System.exit(1);
        }
    }
}
