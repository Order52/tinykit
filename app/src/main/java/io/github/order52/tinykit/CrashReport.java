package io.github.order52.tinykit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Lets the user report a crash with one tap, and only if they want to. When TinyKit crashes it
 * keeps the error in a private file (nothing is sent anywhere) and, where notifications are
 * allowed, posts a notification. The next time TinyKit opens it offers to open a ready-made GitHub
 * issue in the browser: the user reads it, edits it if they like and presses Submit there. Turned
 * off with the "Offer to report crashes" switch, in which case nothing is even kept.
 */
final class CrashReport {
    static final String PREF = "crash_reports";
    static final String EXTRA = "crash_report";
    private static final String FILE = "last_crash.txt";
    private static final String CHANNEL = "crash";
    private static final int NOTIFICATION = 1;
    private static final String ISSUES = "https://github.com/Order52/tinykit/issues/new";
    // GitHub refuses addresses much longer than 8000 characters.
    private static final int MAX_URL = 7000;

    private CrashReport() {
    }

    /** Called from the uncaught-exception handler: keeps the error and posts the notification. */
    static void save(Context c, Thread thread, Throwable error) {
        try {
            if (!c.getSharedPreferences("tinykit", Context.MODE_PRIVATE).getBoolean(PREF, true)) return;
            String trace = Log.getStackTraceString(error);
            String first = trace.indexOf('\n') > 0 ? trace.substring(0, trace.indexOf('\n')) : trace;
            String text = "Crash: " + first.trim() + "\n" + header(c) + "Thread: " + thread.getName() + "\n\n" + trace;
            try (FileOutputStream out = new FileOutputStream(new File(c.getFilesDir(), FILE))) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            notifyCrash(c);
        } catch (Throwable ignored) {
            // The app is already crashing; never make it worse.
        }
    }

    /** The saved crash (first line is the issue title), or null. */
    static String pending(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.isFile()) return null;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder text = new StringBuilder();
            char[] chunk = new char[4096];
            for (int n; (n = in.read(chunk)) != -1 && text.length() < 60_000; ) text.append(chunk, 0, n);
            return text.length() == 0 ? null : text.toString();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    static void clear(Context c) {
        new File(c.getFilesDir(), FILE).delete();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION);
    }

    /** Phone, Android and TinyKit versions: what's needed to reproduce a problem, and nothing personal. */
    static String header(Context c) {
        String version = "?";
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            version = info.versionName + " (" + info.versionCode + ")";
        } catch (Exception ignored) {
        }
        String shizuku;
        try {
            shizuku = Shell.ready() ? "allowed" : Shell.running() ? "running, not allowed" : "not running";
        } catch (Throwable t) {
            shizuku = "unknown";
        }
        return "TinyKit: " + version + "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n"
                + "Phone: " + Build.MANUFACTURER + " " + Build.MODEL + "\nShizuku: " + shizuku + "\n";
    }

    /** The phone's own log for this app, newest last (an app can only read its own lines). */
    static String recentLog() {
        try {
            Process p = new ProcessBuilder("logcat", "-d", "-t", "150", "-v", "time").redirectErrorStream(true).start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = in.readLine()) != null; ) out.append(line).append('\n');
            }
            String log = out.toString();
            return log.length() > 6000 ? log.substring(log.length() - 6000) : log;
        } catch (Exception e) {
            return "";
        }
    }

    /** An intent that opens a new GitHub issue with {@code title} and {@code details} filled in. */
    static Intent issue(String title, String details) {
        String d = details;
        String url;
        while (true) {
            String body = "**What were you doing when it happened?**\n(optional)\n\n**Details**\n```\n" + d + "\n```\n";
            url = ISSUES + "?title=" + Uri.encode(title) + "&body=" + Uri.encode(body);
            if (url.length() <= MAX_URL || d.length() < 400) break;
            d = d.substring(0, d.length() * 4 / 5) + "\n… (trimmed)";
        }
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    private static void notifyCrash(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Crash reports", NotificationManager.IMPORTANCE_DEFAULT));
            b = new Notification.Builder(c, CHANNEL);
        } else {
            b = new Notification.Builder(c);
        }
        Intent open = new Intent(c, MainActivity.class).putExtra(EXTRA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        nm.notify(NOTIFICATION, b.setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("TinyKit stopped")
                .setContentText("Tap to send the crash details so it can be fixed.")
                .setContentIntent(tap).setAutoCancel(true).build());
    }
}
