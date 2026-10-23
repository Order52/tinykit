package io.github.order52.tinykit;

import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Quick Settings tile that takes a screenshot through Shizuku: it closes the panel, waits for it
 * to slide away, then saves the screen to Pictures/Screenshots.
 *
 * Android never lets the shell user capture windows marked secure (FLAG_SECURE), so apps that
 * block screenshots stay blocked; the tile says so instead of saving a black image.
 */
public class ScreenshotTile extends TileService {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @Override
    public void onClick() {
        WORKER.execute(() -> {
            // Shizuku's binder arrives a moment after TinyKit's process starts.
            for (int i = 0; i < 20 && !Shell.ready(); i++) SystemClock.sleep(100);
            if (!Shell.ready()) {
                toast("Start Shizuku first.");
                return;
            }
            String name = "Screenshot_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                    .format(new Date()) + "_TinyKit.png";
            String dir = "/sdcard/Pictures/Screenshots";
            String path = dir + "/" + name;
            Shell.Result r = Shell.run("cmd statusbar collapse; sleep 0.7; mkdir -p " + dir
                    + "; screencap -p " + Shell.quote(path) + " && [ -s " + Shell.quote(path) + " ] || { rm -f "
                    + Shell.quote(path) + "; exit 3; }");
            if (r.ok()) {
                MediaScannerConnection.scanFile(this, new String[]{path}, new String[]{"image/png"}, null);
                toast("Screenshot saved to Pictures/Screenshots");
            } else if (r.code == 3) {
                toast("This app blocks screenshots. Android doesn't let any app get past that without root.");
            } else {
                toast("Couldn't take a screenshot. " + r.out);
            }
        });
    }

    private void toast(String message) {
        main.post(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }
}
