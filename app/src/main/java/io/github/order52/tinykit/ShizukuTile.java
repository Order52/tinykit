package io.github.order52.tinykit;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/**
 * Quick Settings tile that turns Shizuku on and off with one tap.
 *
 * Starting and stopping go through {@link ShizukuControl}, same as the Shizuku tab. Android binds the tile only while the quick panel is open or when Shizuku's
 * state changes, so it costs nothing the rest of the time.
 */
public class ShizukuTile extends TileService {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static boolean listening;

    private final Handler main = new Handler(Looper.getMainLooper());
    // Set while a start or stop is on its way, so the tile shows progress instead of the old state.
    private static Boolean pending;

    @Override
    public void onCreate() {
        super.onCreate();
        listen(getApplicationContext());
    }

    @Override
    public void onStartListening() {
        // Shizuku's binder arrives a moment after the process starts.
        WORKER.execute(() -> {
            Shell.nudge(getApplicationContext());
            for (int i = 0; i < 10 && !Shell.running(); i++) android.os.SystemClock.sleep(100);
            main.post(this::update);
        });
    }

    @Override
    public void onClick() {
        boolean on = pending != null ? pending : Shell.running();
        SharedPreferences p = getSharedPreferences("tinykit", MODE_PRIVATE);
        Context app = getApplicationContext();
        if (on && ShizukuControl.canStop(this, p)) {
            WORKER.execute(() -> {
                String error = ShizukuControl.stop(app, p);
                if (error != null) main.post(() -> {
                    pending = null;
                    update();
                    Toast.makeText(app, Tr.t(error), Toast.LENGTH_LONG).show();
                });
            });
        } else if (!on && Banking.on(p)) {
            Toast.makeText(this, Tr.t("Banking mode is on. Turn it off in TinyKit first."), Toast.LENGTH_LONG).show();
        } else if (!on && ShizukuControl.canStart(this, p)) {
            ShizukuControl.start(this, p);
        } else {
            // Android 10 and older, with no token: only the Shizuku app can start it.
            Toast.makeText(this, Tr.t("Open TinyKit and tap Start."), Toast.LENGTH_LONG).show();
            openApp();
            return;
        }
        pending = !on;
        update();
        // Give up on "Starting…" if nothing happens, and show the real state again (pairing can take minutes).
        main.postDelayed(() -> {
            pending = null;
            update();
        }, on ? 30_000L : 90_000L);
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean on = Shell.running();
        if (pending != null && pending == on) pending = null;
        if (pending != null) {
            tile.setState(pending ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            setSubtitle(tile, pending ? "Starting…" : "Stopping…");
        } else {
            tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            setSubtitle(tile, on ? "On" : "Off");
        }
        tile.updateTile();
    }

    private static void setSubtitle(Tile tile, String text) {
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(Tr.t(text));
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // PendingIntent form used on 34+
    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(intent);
        }
    }

    /** Redraws the tile whenever Shizuku starts or stops while TinyKit's process is alive. */
    private static synchronized void listen(Context context) {
        if (listening) return;
        listening = true;
        ComponentName tile = new ComponentName(context, ShizukuTile.class);
        Shizuku.addBinderReceivedListener(() -> requestListeningState(context, tile));
        Shizuku.addBinderDeadListener(() -> requestListeningState(context, tile));
    }
}
