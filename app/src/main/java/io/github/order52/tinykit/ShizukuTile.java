package io.github.order52.tinykit;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
 * It uses Shizuku's auth token when one is saved in TinyKit, otherwise Termux with
 * shizuku-termux. Android binds the tile only while the quick panel is open or when Shizuku's
 * state changes, so it costs nothing the rest of the time.
 */
public class ShizukuTile extends TileService {

    static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final String TERMUX = "com.termux";
    private static final String SHIZUKU = "/data/data/com.termux/files/usr/bin/shizuku";
    private static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
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
        String token = getSharedPreferences("tinykit", MODE_PRIVATE).getString("auth", "");
        if (!token.isEmpty()) {
            sendBroadcast(new Intent(SHIZUKU_PKG + (on ? ".STOP" : ".START"))
                    .setPackage(SHIZUKU_PKG).putExtra("auth", token));
        } else if (termuxReady()) {
            startService(termux(on ? "stop" : "start"));
        } else if (on && Shell.ready()) {
            // No token and no Termux: end the server directly. Stopping the app first keeps
            // Shizuku's Watchdog from starting it straight back up.
            WORKER.execute(() -> Shell.run("am force-stop " + SHIZUKU_PKG
                    + "; pkill -f shizuku_server || killall shizuku_server"));
        } else {
            Toast.makeText(this, "Set Shizuku's auth token in TinyKit, or install shizuku-termux, "
                    + "to start Shizuku from here.", Toast.LENGTH_LONG).show();
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
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(text);
    }

    private boolean termuxReady() {
        try {
            getPackageManager().getPackageInfo(TERMUX, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED;
    }

    private Intent termux(String command) {
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent result = PendingIntent.getBroadcast(this, 0,
                new Intent(this, Result.class), flags);
        return new Intent("com.termux.RUN_COMMAND")
                .setClassName(TERMUX, "com.termux.app.RunCommandService")
                .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c",
                        "[ -x " + SHIZUKU + " ] || exit 127; exec " + SHIZUKU + " " + command + " </dev/null"})
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", result);
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
            Toast.makeText(context, message.isEmpty() ? "Couldn't change Shizuku." : message,
                    Toast.LENGTH_LONG).show();
            requestListeningState(context, new ComponentName(context, ShizukuTile.class));
        }
    }
}
