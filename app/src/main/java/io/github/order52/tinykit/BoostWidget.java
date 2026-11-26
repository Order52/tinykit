package io.github.order52.tinykit;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.app.ActivityManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.widget.RemoteViews;
import android.widget.Toast;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen widget: two bars (storage and RAM in use), colors and see-through level the user picks (see {@link #style}), and one tap that frees RAM (see {@link Ram}) and clears every app's cache, without
 * opening TinyKit. No service runs for it: the bars are refreshed by Android's own widget timer (about every
 * 30 minutes, skipped while the phone is idle), after a tap, and whenever TinyKit opens.
 */
public class BoostWidget extends AppWidgetProvider {
    private static final String ACTION_BOOST = "io.github.order52.tinykit.BOOST";
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, views(context, false));
    }

    /**
     * Puts the tap handler back on every widget on the home screen. After TinyKit is updated, the
     * launcher can keep showing the widget's bare starting layout, which has no tap handler, so
     * tapping it just opens the app until the widget is removed and added again.
     */
    static void refresh(Context context) {
        try {
            show(context.getApplicationContext(), false);
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
        show(app, true);
        WORKER.execute(() -> {
            try {
                // Shizuku's binder arrives a moment after TinyKit's process starts.
                boolean shizuku = Shell.await();
                String message;
                if (!shizuku && Ram.needsShizuku()) {
                    message = "Start Shizuku first.";
                } else {
                    message = Ram.free(app);
                }
                show(app, false);
                new Handler(Looper.getMainLooper()).post(
                        () -> Toast.makeText(app, Tr.t(message), Toast.LENGTH_LONG).show());
            } finally {
                pending.finish();
            }
        });
    }

    private static void show(Context context, boolean busy) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, BoostWidget.class));
        manager.updateAppWidget(ids, views(context, busy));
    }

    /** Marketing sizes in GB: a "128 GB" phone reports about 110 GB of storage, a "4 GB" one 3.7 GB of RAM. */
    private static final int[] SIZES = {1, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32, 48, 64, 128, 256, 512, 1024, 2048};

    private static double nominalGb(long bytes) {
        double gb = bytes / 1e9;
        for (int size : SIZES) if (size >= gb) return size;
        return Math.ceil(gb);
    }

    private static String gb(double value) {
        return value >= 1000 ? String.format(Locale.getDefault(), "%.1f TB", value / 1000)
                : String.format(Locale.getDefault(), "%.1f GB", value);
    }

    /** One bar: "used / total" on the bar, and its fill. The total is the phone's advertised size, as One UI shows it. */
    private static void bar(RemoteViews views, int[] ids, long total, long free, int[] colors, int accent) {
        double all = nominalGb(total);
        double used = Math.max(0, Math.min(all - free / 1e9, all));
        views.setInt(ids[0], "setImageLevel", (int) (used * 10000 / all));
        views.setInt(ids[0], "setColorFilter", accent);
        views.setInt(ids[1], "setColorFilter", mix(colors[0], accent));
        views.setInt(ids[3], "setColorFilter", colors[4]);
        views.setTextColor(ids[2], colors[4]);
        String size = all >= 1000 ? String.format(Locale.getDefault(), "%.0f TB", all / 1000)
                : String.format(Locale.getDefault(), "%.0f GB", all);
        views.setTextViewText(ids[2], gb(used) + " / " + size);
    }

    /** The track behind a fill: the background with a quarter of the bar's color in it. */
    private static int mix(int from, int to) {
        int out = 0xFF000000;
        for (int shift = 0; shift < 24; shift += 8) {
            out |= (((from >> shift & 255) * 3 + (to >> shift & 255)) / 4) << shift;
        }
        return out;
    }

    static final String PREF_THEME = "widget_theme";
    static final String PREF_GLASS = "widget_glass";
    private static final String[] THEMES = {"Ocean", "Mint", "Sunset", "Violet", "Light", "Match phone"};
    private static final String[] GLASS = {"Solid", "Glass", "Clear"};
    private static final int[] OPACITY = {255, 205, 120};
    /** Per theme: background, storage bar, RAM bar, icon circle, text. */
    private static final int[][] COLORS = {
            {0xFF101417, 0xFF0DB1CF, 0xFF3B78E7, 0xFF3B5FD0, 0xFFFFFFFF},
            {0xFF0E1A15, 0xFF2EE6A6, 0xFF8BE05A, 0xFF1FA67A, 0xFFFFFFFF},
            {0xFF1D1315, 0xFFFF9A3D, 0xFFFF5470, 0xFFE8503A, 0xFFFFFFFF},
            {0xFF15111F, 0xFFB388FF, 0xFFFF6FD8, 0xFF7C4DFF, 0xFFFFFFFF},
            {0xFFF3F5F8, 0xFF0A9DB8, 0xFF2F6BDB, 0xFF2F6BDB, 0xFF18222E}};

    /** Colors from the phone's own palette (Android 12+), so the widget matches the wallpaper. */
    private static int[] palette(Context context, int theme) {
        if (theme >= COLORS.length && Build.VERSION.SDK_INT >= 31) {
            return new int[]{context.getColor(android.R.color.system_neutral1_900),
                    context.getColor(android.R.color.system_accent1_300),
                    context.getColor(android.R.color.system_accent3_300),
                    context.getColor(android.R.color.system_accent1_600), 0xFFFFFFFF};
        }
        return COLORS[theme >= COLORS.length ? 0 : theme];
    }

    /** Pick the colors, then how see-through the widget is. Both apply to the widgets on the home screen at once. */
    static void style(MainActivity tk) {
        SharedPreferences prefs = tk.prefs;
        String[] themes = Build.VERSION.SDK_INT >= 31 ? THEMES : Arrays.copyOf(THEMES, COLORS.length);
        new AlertDialog.Builder(tk).setTitle(Tr.t("Widget colors"))
                .setSingleChoiceItems(Tr.all(themes), Math.min(prefs.getInt(PREF_THEME, 0), themes.length - 1), (d, theme) -> {
                    d.dismiss();
                    prefs.edit().putInt(PREF_THEME, theme).apply();
                    show(tk, false);
                    new AlertDialog.Builder(tk).setTitle(Tr.t("Widget background"))
                            .setSingleChoiceItems(Tr.all(GLASS), prefs.getInt(PREF_GLASS, 0), (g, glass) -> {
                                g.dismiss();
                                prefs.edit().putInt(PREF_GLASS, glass).apply();
                                show(tk, false);
                            }).show();
                }).show();
    }

    private static RemoteViews views(Context context, boolean busy) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_boost);
        views.setTextViewText(R.id.widget_status, context.getString(busy ? R.string.widget_busy : R.string.widget_name));
        SharedPreferences prefs = context.getSharedPreferences("tinykit", Context.MODE_PRIVATE);
        int[] colors = palette(context, Math.max(0, prefs.getInt(PREF_THEME, 0)));
        views.setInt(R.id.widget_bg, "setColorFilter", colors[0]);
        views.setInt(R.id.widget_bg, "setImageAlpha", OPACITY[Math.max(0, Math.min(prefs.getInt(PREF_GLASS, 0), 2))]);
        views.setInt(R.id.widget_circle, "setColorFilter", colors[3]);
        views.setTextColor(R.id.widget_status, colors[4]);
        try {
            StatFs disk = new StatFs(Environment.getDataDirectory().getPath());
            bar(views, new int[]{R.id.widget_storage_bar, R.id.widget_storage_track, R.id.widget_storage_text, R.id.widget_storage_icon},
                    disk.getTotalBytes(), disk.getAvailableBytes(), colors, colors[1]);
            ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
            context.getSystemService(ActivityManager.class).getMemoryInfo(mem);
            bar(views, new int[]{R.id.widget_ram_bar, R.id.widget_ram_track, R.id.widget_ram_text, R.id.widget_ram_icon},
                    mem.totalMem, mem.availMem, colors, colors[2]);
        } catch (RuntimeException ignored) {
            // The numbers stay as they were; the tap still works.
        }
        Intent tap = new Intent(context, BoostWidget.class).setAction(ACTION_BOOST);
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getBroadcast(context, 0, tap,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return views;
    }
}
