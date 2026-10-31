package io.github.order52.tinykit;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.widget.RemoteViews;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen widget: one tap frees RAM (see {@link Ram}) and clears every app's cache, without
 * opening TinyKit. It does nothing until tapped: no periodic updates, nothing in the background.
 */
public class BoostWidget extends AppWidgetProvider {
    private static final String ACTION_BOOST = "io.github.order52.tinykit.BOOST";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, views(context, "Tap to speed up"));
    }

    /**
     * Puts the tap handler back on every widget on the home screen. After TinyKit is updated, the
     * launcher can keep showing the widget's bare starting layout, which has no tap handler, so
     * tapping it just opens the app until the widget is removed and added again.
     */
    static void refresh(Context context) {
        try {
            show(context.getApplicationContext(), "Tap to speed up");
        } catch (RuntimeException ignored) {
            // The launcher isn't ready; the next start tries again.
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        // Sent to the updated app itself, even when Android holds back other broadcasts for it.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            refresh(context);
            return;
        }
        if (!ACTION_BOOST.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        show(app, "Speeding up…");
        WORKER.execute(() -> {
            try {
                // Shizuku's binder arrives a moment after TinyKit's process starts.
                boolean shizuku = Shell.await();
                String status;
                String message;
                if (!shizuku && Ram.needsShizuku()) {
                    status = "Start Shizuku first";
                    message = "Start Shizuku first.";
                } else {
                    message = Ram.free(app);
                    status = Formatter.formatShortFileSize(app, Ram.available(app)) + " free";
                }
                show(app, status);
                new Handler(Looper.getMainLooper()).post(
                        () -> Toast.makeText(app, message, Toast.LENGTH_LONG).show());
            } finally {
                pending.finish();
            }
        });
    }

    private static void show(Context context, String status) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, BoostWidget.class));
        manager.updateAppWidget(ids, views(context, status));
    }

    private static RemoteViews views(Context context, String status) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_boost);
        views.setTextViewText(R.id.widget_status, status);
        Intent tap = new Intent(context, BoostWidget.class).setAction(ACTION_BOOST);
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getBroadcast(context, 0, tap,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return views;
    }
}
