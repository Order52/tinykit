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
        // The removed notification history kept its saved notifications here.
        new java.io.File(getFilesDir(), "notifications.tsv").delete();
        Shizuku.addBinderReceivedListenerSticky(() -> Restore.start(this));
    }
}
