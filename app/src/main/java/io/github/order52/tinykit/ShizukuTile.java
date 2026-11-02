package io.github.order52.tinykit;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
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
                String error = ShizukuControl.stop(app, p, termuxResult());
                if (error != null) main.post(() -> {
                    pending = null;
                    update();
                    Toast.makeText(app, Tr.t(error), Toast.LENGTH_LONG).show();
                });
            });
        } else if (!on && ShizukuControl.canRestart(this, p)) {
            ShizukuControl.start(this, p, termuxResult());
        } else {
            Toast.makeText(this, Tr.t("Set Shizuku's auth token in TinyKit, or install shizuku-termux, "
                    + "to start Shizuku from here."), Toast.LENGTH_LONG).show();
            openApp();
            return;
        }
        pending = !on;
        update();
        // Give up on "Starting…" if nothing happens, and show the real state again.
        main.postDelayed(() -> {
            pending = null;
            update();
        }, 30_000L);
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

    /** Where Termux sends its reply, so a failed start or stop is shown instead of hanging on "Starting…". */
    private PendingIntent termuxResult() {
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(this, 0, new Intent(this, Result.class), flags);
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

    /** Termux's reply; only shown when something went wrong. */
    public static class Result extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle result = intent.getBundleExtra("result");
            int exitCode = result == null ? -1 : result.getInt("exitCode", -1);
            if (exitCode == 0) return;
            pending = null;
            String message;
            if (exitCode == 127) {
                message = "shizuku-termux isn't installed in Termux.";
            } else if (result != null && result.getString("errmsg", "").contains("allow-external-apps")) {
                message = "Run the shizuku-termux install command in Termux once.";
            } else {
                message = result == null ? "Termux didn't answer."
                        : (result.getString("stdout", "") + "\n" + result.getString("stderr", "")).trim();
            }
            Toast.makeText(context, Tr.t(message.isEmpty() ? "Couldn't change Shizuku." : message),
                    Toast.LENGTH_LONG).show();
            requestListeningState(context, new ComponentName(context, ShizukuTile.class));
        }
    }
}
