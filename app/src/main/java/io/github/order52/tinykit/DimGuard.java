package io.github.order52.tinykit;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;

/**
 * Safety for Extra dim: a screen that has gone black can't be used to turn Extra dim back off.
 *
 * - The strength is capped when Extra dim is turned on (a very strong setting is close to black on some
 *   phones) and put back when it is turned off.
 * - A countdown always starts as well: unless you press Keep in the notification within 20 seconds, Extra
 *   dim turns itself off. Doing nothing is always the safe answer, so a black screen fixes itself. With
 *   notifications blocked Keep can't be pressed, so Extra dim lasts two minutes and then ends.
 * - The countdown is an alarm-clock alarm, the one kind phone makers' battery managers let through, and it
 *   is also checked at every restart.
 */
final class DimGuard {
    static final String ACTION_REVERT = "io.github.order52.tinykit.DIM_REVERT";
    static final String ACTION_KEEP = "io.github.order52.tinykit.DIM_KEEP";
    /** True while Extra dim waits for Keep. Survives a restart: {@link StartReceiver} reverts it then. */
    private static final String PREF_PENDING = "dim_pending";
    /** The strength before the cap ("null" when it wasn't set), absent when nothing was changed. */
    private static final String PREF_PREV = "dim_prev_level";
    static final int MAX_LEVEL = 30;
    static final long WAIT_MS = 20_000;
    /** How long Extra dim lasts when Keep can't be offered (notifications blocked). */
    static final long NO_KEEP_MS = 120_000;
    private static final String LEVEL = "reduce_bright_colors_level";
    private static final String CHANNEL = "dimguard";
    private static final int NOTIFICATION = 3;

    private DimGuard() {
    }

    /** The strength to use: never above {@link #MAX_LEVEL}. */
    static int capped(int level) {
        return Math.min(level, MAX_LEVEL);
    }

    static boolean pending(SharedPreferences p) {
        return p.getBoolean(PREF_PENDING, false);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("tinykit", Context.MODE_PRIVATE);
    }

    /** Extra dim is about to be turned on: cap its strength and start the countdown. */
    static void arm(Context c) {
        SharedPreferences p = prefs(c);
        String now = Tweak.readSecure(c, LEVEL);
        int level = -1;
        try {
            level = Integer.parseInt(now);
        } catch (NumberFormatException ignored) {
            // not set: Android's own default is used, which may be strong
        }
        if (level < 0 || level > MAX_LEVEL) {
            if (!p.contains(PREF_PREV)) p.edit().putString(PREF_PREV, now == null ? "null" : now).commit();
            Tweak.writeSecure(c, LEVEL, String.valueOf(level < 0 ? MAX_LEVEL : capped(level)));
        }
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        boolean canKeep = nm != null && nm.areNotificationsEnabled();
        p.edit().putBoolean(PREF_PENDING, true).commit();
        PendingIntent revert = intent(c, ACTION_REVERT);
        PendingIntent show = PendingIntent.getActivity(c, 2, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(System.currentTimeMillis() + (canKeep ? WAIT_MS : NO_KEEP_MS),
                show), revert);
        if (canKeep) {
            notifyKeep(c, nm);
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(c,
                    Tr.t("Extra dim ends by itself in 2 minutes. Allow notifications to be able to keep it."),
                    android.widget.Toast.LENGTH_LONG).show());
        }
    }

    /** Extra dim is off (or never went on): stop the countdown and put the strength back. */
    static void disarm(Context c) {
        SharedPreferences p = prefs(c);
        cancel(c);
        if (p.contains(PREF_PREV)) {
            String prev = p.getString(PREF_PREV, "null");
            Tweak.writeSecure(c, LEVEL, "null".equals(prev) ? null : prev);
            p.edit().remove(PREF_PREV).commit();
        }
    }

    /** Keep was pressed: the dim stays, with the capped strength. */
    static void keep(Context c) {
        cancel(c);
    }

    /** The time is up (or the phone restarted) and nobody pressed Keep: Extra dim goes off. */
    static void revert(Context c) {
        SharedPreferences p = prefs(c);
        if (!pending(p)) return;
        android.util.Log.i("TinyKit", "Extra dim: not kept, turning it off");
        Tweak.EXTRA_DIM.set(c, false);
        disarm(c);
    }

    private static void cancel(Context c) {
        prefs(c).edit().putBoolean(PREF_PENDING, false).commit();
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(intent(c, ACTION_REVERT));
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION);
    }

    private static PendingIntent intent(Context c, String action) {
        return PendingIntent.getBroadcast(c, action.equals(ACTION_KEEP) ? 1 : 0,
                new Intent(c, StartReceiver.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void notifyKeep(Context c, NotificationManager nm) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, Tr.t("Extra dim safety"),
                    NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(c, CHANNEL);
            b.setTimeoutAfter(WAIT_MS);
        } else {
            b = new Notification.Builder(c).setPriority(Notification.PRIORITY_HIGH);
        }
        b.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle(Tr.t("Extra dim is on"))
                .setContentText(Tr.t("It turns itself off in 20 seconds unless you press Keep."))
                .addAction(new Notification.Action.Builder(Icon.createWithResource(c, android.R.drawable.ic_menu_save),
                        Tr.t("Keep"), intent(c, ACTION_KEEP)).build())
                .setOngoing(false);
        nm.notify(NOTIFICATION, b.build());
    }
}
