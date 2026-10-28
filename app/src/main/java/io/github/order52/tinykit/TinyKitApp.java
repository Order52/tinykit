package io.github.order52.tinykit;

import android.app.Application;

import rikka.shizuku.Shizuku;

/** Hooks {@link Restore} to Shizuku's binder arriving, which is when it has just been (re)started. */
public final class TinyKitApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Shizuku.addBinderReceivedListenerSticky(() -> Restore.start(this));
    }
}
