package io.github.order52.tinykit;

import android.app.Application;

import rikka.shizuku.Shizuku;

/** Hooks {@link Restore} to Shizuku's binder arriving, which is when it has just been (re)started. */
public final class TinyKitApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Keeps a crash for the "send this?" offer on the next start; the default handler still runs.
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            CrashReport.save(this, thread, error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
        // After an update, put the tap handler back on the home screen widget (once per version).
        android.content.SharedPreferences prefs = getSharedPreferences("tinykit", MODE_PRIVATE);
        try {
            int code = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            if (prefs.getInt("widget_refreshed", -1) != code) {
                BoostWidget.refresh(this);
                prefs.edit().putInt("widget_refreshed", code).apply();
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        // The removed notification history kept its saved notifications here.
        new java.io.File(getFilesDir(), "notifications.tsv").delete();
        Shizuku.addBinderReceivedListenerSticky(() -> Restore.start(this));
        // Keep the install watcher running while the internet block needs it (also covers a process
        // Android killed and later restarted for any other reason).
        NewAppService.sync(this);
    }
}
