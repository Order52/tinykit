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
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
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

    /**
     * A panel with a live preview of the widget, the colors and the background to pick from, and OK to keep
     * them. Nothing changes on the home screen until OK.
     */
    static void style(MainActivity tk) {
        SharedPreferences prefs = tk.prefs;
        String[] themes = Build.VERSION.SDK_INT >= 31 ? THEMES : Arrays.copyOf(THEMES, COLORS.length);
        int[] pick = {Math.min(prefs.getInt(PREF_THEME, 0), themes.length - 1), Math.max(0, Math.min(prefs.getInt(PREF_GLASS, 0), 2))};
        LinearLayout panel = tk.vertical();
        panel.setPadding(tk.dp(16), tk.dp(8), tk.dp(16), 0);
        FrameLayout preview = new FrameLayout(tk);
        preview.setBackgroundColor(0xFF6B7A99);
        preview.setPadding(tk.dp(8), tk.dp(8), tk.dp(8), tk.dp(8));
        panel.addView(preview, new LinearLayout.LayoutParams(-1, tk.dp(96)));
        Runnable draw = () -> {
            preview.removeAllViews();
            preview.addView(render(tk, false, pick[0], pick[1]).apply(tk, preview));
        };
        draw.run();
        panel.addView(chips(tk, "Widget colors", themes, pick, 0, draw, true));
        panel.addView(chips(tk, "Widget background", GLASS, pick, 1, draw, false));
        new AlertDialog.Builder(tk).setTitle(Tr.t("Widget style")).setView(panel)
                .setPositiveButton(Tr.t("OK"), (d, w) -> {
                    prefs.edit().putInt(PREF_THEME, pick[0]).putInt(PREF_GLASS, pick[1]).apply();
                    show(tk, false);
                })
                .setNegativeButton(Tr.t("Cancel"), null).show();
    }

    /** A heading and rows of three chips; tapping one sets pick[slot] and redraws the preview. */
    private static View chips(MainActivity tk, String title, String[] names, int[] pick, int slot, Runnable draw, boolean colored) {
        LinearLayout box = tk.vertical();
        TextView heading = new TextView(tk);
        heading.setText(Tr.t(title));
        heading.setPadding(0, tk.dp(14), 0, tk.dp(4));
        box.addView(heading);
        LinearLayout row = null;
        View[] all = new View[names.length];
        for (int i = 0; i < names.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(tk);
                box.addView(row);
            }
            int[] colors = palette(tk, colored ? i : 0);
            TextView chip = new TextView(tk);
            chip.setText(Tr.t(names[i]));
            chip.setGravity(Gravity.CENTER);
            chip.setSingleLine();
            chip.setTextColor(colored ? colors[0] : 0xFFFFFFFF);
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(tk.dp(20));
            shape.setColor(colored ? colors[1] : 0xFF5A6068);
            chip.setBackground(shape);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, tk.dp(40), 1);
            lp.setMargins(tk.dp(3), tk.dp(3), tk.dp(3), tk.dp(3));
            row.addView(chip, lp);
            all[i] = chip;
            int index = i;
            chip.setOnClickListener(v -> {
                pick[slot] = index;
                for (int j = 0; j < all.length; j++) all[j].setAlpha(j == index ? 1f : 0.4f);
                draw.run();
            });
        }
        for (int j = 0; j < all.length; j++) all[j].setAlpha(j == pick[slot] ? 1f : 0.4f);
        return box;
    }

    private static RemoteViews views(Context context, boolean busy) {
        SharedPreferences prefs = context.getSharedPreferences("tinykit", Context.MODE_PRIVATE);
        RemoteViews views = render(context, busy, Math.max(0, prefs.getInt(PREF_THEME, 0)), Math.max(0, Math.min(prefs.getInt(PREF_GLASS, 0), 2)));
        Intent tap = new Intent(context, BoostWidget.class).setAction(ACTION_BOOST);
        views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getBroadcast(context, 0, tap,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return views;
    }

    /** The widget as it looks with this theme and background (also used for the preview, which has no tap). */
    private static RemoteViews render(Context context, boolean busy, int theme, int glass) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_boost);
        views.setTextViewText(R.id.widget_status, context.getString(busy ? R.string.widget_busy : R.string.widget_name));
        int[] colors = palette(context, theme);
        views.setInt(R.id.widget_bg, "setColorFilter", colors[0]);
        views.setInt(R.id.widget_bg, "setImageAlpha", OPACITY[glass]);
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
        return views;
    }
}
