package io.github.order52.tinykit;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.usage.StorageStats;
import android.appwidget.AppWidgetManager;
import android.app.usage.StorageStatsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.service.quicksettings.TileService;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class QuickPanelScreen {
    private final MainActivity tk;

    QuickPanelScreen(MainActivity tk) {
        this.tk = tk;
    }

    static final String QS_TILES = "sysui_qs_tiles";

    /** Names for built-in tiles; anything not listed is shown by its system name. */
    static final String[][] STOCK_TILES = {
            {"wifi", "Wi-Fi"}, {"bt", "Bluetooth"}, {"internet", "Internet"}, {"cell", "Mobile data"},
            {"data", "Mobile data"}, {"flashlight", "Flashlight"}, {"sound", "Sound"},
            {"rotation", "Auto-rotate"}, {"location", "Location"}, {"hotspot", "Hotspot"},
            {"airplane", "Airplane mode"}, {"dnd", "Do Not Disturb"}, {"busy", "Do Not Disturb"},
            {"battery", "Battery Saver"}, {"superpower", "Power saving"}, {"saver", "Data Saver"},
            {"cast", "Cast"}, {"nfc", "NFC"}, {"night", "Night Light"}, {"dark", "Dark theme"},
            {"eyecomfort", "Eye comfort"}, {"inversion", "Color inversion"}, {"work", "Work apps"},
            {"screenrecord", "Screen record"}, {"recorder", "Screen recorder"}, {"screenshot", "Screenshot"},
            {"airsharing", "Huawei Share"}, {"alarm", "Alarm"}, {"controls", "Device controls"},
            {"wallet", "Wallet"}, {"qr_code_scanner", "QR scanner"}, {"mictoggle", "Microphone access"},
            {"cameratoggle", "Camera access"}, {"onehanded", "One-handed mode"},
            {"color_correction", "Color correction"}, {"reduce_brightness", "Extra dim"},
            {"font_scaling", "Font size"}, {"hearing_devices", "Hearing devices"}};

    /** One tile: its spec as stored in the setting, plus how to show it. */
    static final class QsTile {
        String spec;
        String title;
        String sub;
        Drawable icon;
        // Tile icons are masks that SystemUI tints; app icons are shown as they are.
        boolean tint;
    }

    interface OnTile {
        void run(String spec);
    }

    interface OnDrop {
        void run(String spec, int index);
    }

    // Editor state: the page each grid shows and the tile that's picked for moving.
    int qsPage;

    int qsAddPage;

    String qsPicked;

    final Map<String, Drawable> qsIcons = new HashMap<>();

    // Tiles are looked up once per visit; every later change only rearranges views.
    final Map<String, QsTile> qsCache = new HashMap<>();

    final List<String> qsAppTiles = new ArrayList<>();

    /**
     * Shows the quick panel's tiles as pages laid out like the system's own panel. Every change is
     * written straight to the sysui_qs_tiles setting (through Shizuku), which SystemUI applies at once.
     */
    void openQuickPanel() {
        qsPage = 0;
        qsAddPage = 0;
        qsPicked = null;
        qsCache.clear();
        LinearLayout list = tk.vertical();
        list.addView(note("Loading…"));
        tk.push("Quick panel", tk.scroll(list));
        tk.worker.execute(() -> {
            List<String> tiles = readTiles();
            List<String> apps = new ArrayList<>();
            for (ResolveInfo info : tk.getPackageManager().queryIntentServices(new Intent(TileService.ACTION_QS_TILE), 0)) {
                apps.add("custom(" + new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name)
                        .flattenToShortString() + ")");
            }
            // Names and icons load here, off the main thread.
            Set<String> all = new LinkedHashSet<>(tiles);
            all.addAll(apps);
            all.addAll(tk.prefs.getStringSet("qs_known", Collections.emptySet()));
            Map<String, QsTile> described = new HashMap<>();
            for (String spec : all) described.put(spec, describeTile(spec));
            tk.main.post(() -> {
                qsAppTiles.clear();
                qsAppTiles.addAll(apps);
                qsCache.putAll(described);
                if (tiles.isEmpty()) {
                    list.removeAllViews();
                    list.addView(note(Shell.ready() ? "This phone doesn't let apps change the quick panel."
                            : "Start Shizuku and allow TinyKit first."));
                    return;
                }
                String value = TextUtils.join(",", tiles);
                if (!tk.prefs.contains("qs_original")) tk.prefs.edit().putString("qs_original", value).apply();
                rememberTiles(tiles);
                renderQuickPanel(list, tiles);
            });
        });
    }

    /** The current tiles; on phones that never edited the panel, SystemUI's defaults. */
    List<String> readTiles() {
        Shell.Result r = Shell.run("settings get secure " + QS_TILES);
        // Without a real answer there is nothing to show, and nothing to save as the original layout.
        if (!r.ok()) return new ArrayList<>();
        String value = r.out.trim();
        if (value.isEmpty() || value.equals("null") || value.equals("default") || value.contains(" ")) {
            value = systemString("quick_settings_tiles_default");
        }
        List<String> tiles = new ArrayList<>();
        for (String spec : value.split(",")) {
            if (!spec.trim().isEmpty() && !tiles.contains(spec.trim())) tiles.add(spec.trim());
        }
        return tiles;
    }

    Context systemUi() {
        try {
            return tk.createPackageContext("com.android.systemui", 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }
    }

    String systemString(String name) {
        Context ui = systemUi();
        if (ui == null) return "";
        int id = ui.getResources().getIdentifier(name, "string", "com.android.systemui");
        return id == 0 ? "" : ui.getString(id);
    }

    int systemInteger(String name, int fallback) {
        Context ui = systemUi();
        if (ui == null) return fallback;
        int id = ui.getResources().getIdentifier(name, "integer", "com.android.systemui");
        return id == 0 ? fallback : ui.getResources().getInteger(id);
    }

    /**
     * Columns and rows on one page of the system's panel. EMUI's control panel shows four rows
     * of round tiles; stock Android reads both numbers from SystemUI.
     */
    int[] panelGrid() {
        int columns = systemInteger("hw_quick_settings_num_columns", 0);
        int rows = 4;
        if (columns == 0) {
            columns = systemInteger("quick_settings_num_columns", 4);
            rows = systemInteger("quick_settings_max_rows", 3);
        }
        return new int[]{Math.max(2, Math.min(columns, 6)), Math.max(2, Math.min(rows, 5))};
    }

    /** Built-in tiles have no list to query, so remember every one seen to offer it again later. */
    void rememberTiles(List<String> tiles) {
        Set<String> known = new LinkedHashSet<>(tk.prefs.getStringSet("qs_known", Collections.emptySet()));
        for (String spec : tiles) if (!spec.startsWith("custom(")) known.add(spec);
        tk.prefs.edit().putStringSet("qs_known", known).apply();
    }

    void renderQuickPanel(LinearLayout list, List<String> tiles) {
        list.removeAllViews();
        int[] grid = panelGrid();
        int perPage = grid[0] * grid[1];
        if (qsPicked != null && !tiles.contains(qsPicked)) qsPicked = null;

        list.addView(note(qsPicked == null
                ? "Hold and drag a tile to move it, or tap two tiles to swap them. "
                + "Changes show in the quick panel right away."
                : "Tap another tile to swap them, use the buttons below, or tap it again to let go."));
        tk.section(list, "Your panel  ·  " + tiles.size());
        List<QsTile> mine = new ArrayList<>();
        for (String spec : tiles) mine.add(tile(spec));
        qsPage = Math.min(qsPage, tk.pages(mine.size(), perPage) - 1);
        list.addView(tileGrid(mine, grid, qsPage, qsPicked, spec -> {
            if (qsPicked == null || qsPicked.equals(spec)) {
                qsPicked = spec.equals(qsPicked) ? null : spec;
                renderQuickPanel(list, tiles);
                return;
            }
            List<String> next = new ArrayList<>(tiles);
            Collections.swap(next, next.indexOf(qsPicked), next.indexOf(spec));
            qsPicked = null;
            saveTiles(list, tiles, next);
        }, page -> {
            qsPage = page;
            renderQuickPanel(list, tiles);
        }, (spec, to) -> {
            qsPicked = null;
            moveTile(list, tiles, tiles.indexOf(spec), Math.min(to, tiles.size() - 1));
        }));

        if (qsPicked != null) {
            int index = tiles.indexOf(qsPicked);
            LinearLayout bar = new LinearLayout(tk);
            bar.setPadding(tk.dp(12), tk.dp(8), tk.dp(12), 0);
            Button earlier = tk.button("‹  Earlier", v -> {
                qsPage = (index - 1) / perPage;
                moveTile(list, tiles, index, index - 1);
            });
            Button later = tk.button("Later  ›", v -> {
                qsPage = (index + 1) / perPage;
                moveTile(list, tiles, index, index + 1);
            });
            Button remove = tk.button("Remove", v -> {
                qsPicked = null;
                List<String> next = new ArrayList<>(tiles);
                next.remove(index);
                saveTiles(list, tiles, next);
            });
            earlier.setEnabled(index > 0);
            later.setEnabled(index < tiles.size() - 1);
            remove.setEnabled(tiles.size() > 1);
            bar.addView(earlier, tk.weight());
            bar.addView(later, tk.weight());
            bar.addView(remove, tk.weight());
            list.addView(bar);
        }

        List<QsTile> available = availableTiles(tiles);
        tk.section(list, "Add  ·  " + available.size());
        if (available.isEmpty()) {
            list.addView(note("Every tile is already in your panel."));
        } else {
            list.addView(note("Tap a tile to add it to the end of your panel."));
            qsAddPage = Math.min(qsAddPage, tk.pages(available.size(), perPage) - 1);
            list.addView(tileGrid(available, grid, qsAddPage, null, spec -> {
                List<String> next = new ArrayList<>(tiles);
                next.add(spec);
                qsPicked = spec;
                qsPage = (next.size() - 1) / perPage;
                saveTiles(list, tiles, next);
            }, page -> {
                qsAddPage = page;
                renderQuickPanel(list, tiles);
            }, null));
        }

        tk.section(list, "Reset");
        String original = tk.prefs.getString("qs_original", "");
        tk.row(list, "Restore original layout", "Put the tiles back the way they were before you used this.",
                null, () -> {
                    qsPicked = null;
                    qsPage = 0;
                    saveTiles(list, tiles, new ArrayList<>(Arrays.asList(original.split(","))));
                }, true);
    }

    /** One page of tiles plus page dots; swiping sideways or tapping the arrows turns the page. */
    View tileGrid(List<QsTile> tiles, int[] grid, int page, String picked, OnTile onTile, MainActivity.OnPage onPage,
                          OnDrop onDrop) {
        int columns = grid[0];
        int perPage = columns * grid[1];
        int pages = tk.pages(tiles.size(), perPage);
        // A single page only takes the rows it needs; several pages keep one height, like the system.
        int rows = pages > 1 ? grid[1] : Math.max(1, (tiles.size() + columns - 1) / columns);
        boolean wide = columns <= 2;

        LinearLayout body = tk.vertical();
        body.setPadding(tk.dp(12), tk.dp(4), tk.dp(12), 0);
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(tk);
            for (int c = 0; c < columns; c++) {
                int i = page * perPage + r * columns + c;
                View cell;
                if (i < tiles.size()) {
                    QsTile tile = tiles.get(i);
                    boolean on = tile.spec.equals(picked);
                    cell = wide ? wideTile(tile, on) : roundTile(tile, on);
                    cell.setOnClickListener(v -> onTile.run(tile.spec));
                    if (onDrop != null) {
                        cell.setOnLongClickListener(v -> {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            v.startDragAndDrop(null, new View.DragShadowBuilder(v), tile.spec, 0);
                            v.setAlpha(0.3f);
                            return true;
                        });
                    }
                } else {
                    cell = new View(tk);
                }
                if (onDrop != null) cell.setOnDragListener(dropTarget(i, onDrop));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, tk.dp(wide ? 64 : 100), 1f);
                params.setMargins(tk.dp(4), tk.dp(4), tk.dp(4), tk.dp(4));
                row.addView(cell, params);
            }
            body.addView(row);
        }

        Swipe swipe = new Swipe(tk, page, pages, onPage);
        swipe.addView(body);
        if (pages == 1) return swipe;

        LinearLayout wrap = tk.vertical();
        wrap.addView(swipe);
        LinearLayout dots = new LinearLayout(tk);
        dots.setGravity(Gravity.CENTER);
        View back = tileArrow("‹", page > 0, () -> onPage.run(page - 1));
        dots.addView(back);
        for (int p = 0; p < pages; p++) {
            View dot = new View(tk);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(p == page ? (tk.lightTheme ? 0xFF000000 : 0xFFFFFFFF) : (tk.lightTheme ? 0x40000000 : 0x55FFFFFF));
            dot.setBackground(shape);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(tk.dp(7), tk.dp(7));
            params.setMargins(tk.dp(4), 0, tk.dp(4), 0);
            dots.addView(dot, params);
        }
        View forward = tileArrow("›", page < pages - 1, () -> onPage.run(page + 1));
        dots.addView(forward);
        if (onDrop != null) {
            // Holding a dragged tile over an arrow turns the page.
            back.setOnDragListener(pageTurn(page > 0, () -> onPage.run(page - 1)));
            forward.setOnDragListener(pageTurn(page < pages - 1, () -> onPage.run(page + 1)));
        }
        wrap.addView(dots);
        return wrap;
    }

    /** A grid slot that takes a dragged tile: it grows while one hovers over it. */
    View.OnDragListener dropTarget(int index, OnDrop onDrop) {
        return (v, e) -> {
            // Only TinyKit's own tiles: a drag from another app (split screen) carries no tile.
            if (!(e.getLocalState() instanceof String)) return false;
            switch (e.getAction()) {
                case DragEvent.ACTION_DRAG_ENTERED:
                    v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(80).start();
                    break;
                case DragEvent.ACTION_DRAG_EXITED:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start();
                    break;
                case DragEvent.ACTION_DROP:
                    onDrop.run((String) e.getLocalState(), index);
                    break;
                case DragEvent.ACTION_DRAG_ENDED:
                    v.setAlpha(1f);
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    break;
            }
            return true;
        };
    }

    /** Turns the page once a dragged tile has rested on the arrow for a moment, like the system. */
    View.OnDragListener pageTurn(boolean enabled, Runnable turn) {
        return (v, e) -> {
            if (!enabled) return false;
            if (e.getAction() == DragEvent.ACTION_DRAG_ENTERED) v.postDelayed(turn, 450);
            else if (e.getAction() != DragEvent.ACTION_DRAG_LOCATION) v.removeCallbacks(turn);
            return true;
        };
    }

    /** EMUI-style tile: a round button with the name under it. */
    View roundTile(QsTile tile, boolean picked) {
        LinearLayout cell = tk.vertical();
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.setPadding(0, tk.dp(6), 0, 0);
        FrameLayout circle = new FrameLayout(tk);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(picked ? tk.attrColor(android.R.attr.colorAccent) : (tk.lightTheme ? 0xFFE0E0E4 : 0xFF262626));
        circle.setBackground(shape);
        circle.addView(tileIcon(tile, picked), new FrameLayout.LayoutParams(tk.dp(24), tk.dp(24), Gravity.CENTER));
        cell.addView(circle, new LinearLayout.LayoutParams(tk.dp(52), tk.dp(52)));
        TextView label = tk.text(tile.title, 12, false);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setPadding(tk.dp(2), tk.dp(6), tk.dp(2), 0);
        cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
        cell.setBackgroundResource(tk.attrResource(android.R.attr.selectableItemBackgroundBorderless));
        return cell;
    }

    /** Stock Android 12+ tile: a wide rounded button with the name beside the icon. */
    View wideTile(QsTile tile, boolean picked) {
        LinearLayout cell = new LinearLayout(tk);
        cell.setGravity(Gravity.CENTER_VERTICAL);
        cell.setPadding(tk.dp(16), 0, tk.dp(12), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(tk.dp(24));
        shape.setColor(picked ? tk.attrColor(android.R.attr.colorAccent) : (tk.lightTheme ? 0xFFE0E0E4 : 0xFF262626));
        cell.setBackground(shape);
        cell.setForeground(tk.getDrawable(tk.attrResource(android.R.attr.selectableItemBackground)));
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(tk.dp(20), tk.dp(20));
        iconSize.setMargins(0, 0, tk.dp(12), 0);
        cell.addView(tileIcon(tile, picked), iconSize);
        LinearLayout texts = tk.vertical();
        TextView title = tk.text(tile.title, 14, false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView sub = tk.text(tile.sub, 12, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        tk.setDim(sub, 0.6f);
        texts.addView(sub);
        cell.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        return cell;
    }

    /** The tile's icon, or its first letter when there's no icon to show. */
    View tileIcon(QsTile tile, boolean picked) {
        if (tile.icon == null) {
            TextView letter = tk.text(tile.title.isEmpty() ? "?" : tile.title.substring(0, 1), 16, true);
            letter.setGravity(Gravity.CENTER);
            if (picked) letter.setTextColor(0xFFFFFFFF);
            return letter;
        }
        Drawable d = tile.icon.getConstantState() != null
                ? tile.icon.getConstantState().newDrawable(tk.getResources()).mutate() : tile.icon.mutate();
        if (tile.tint) d.setTint(picked ? 0xFFFFFFFF : (tk.lightTheme ? 0xFF000000 : 0xFFFFFFFF));
        ImageView view = new ImageView(tk);
        view.setImageDrawable(d);
        return view;
    }

    TextView tileArrow(String label, boolean enabled, Runnable onClick) {
        TextView view = tk.text(label, 22, false);
        view.setGravity(Gravity.CENTER);
        view.setEnabled(enabled);
        view.setAlpha(enabled ? (tk.lightTheme ? 1f : 0.9f) : (tk.lightTheme ? 0.3f : 0.2f));
        view.setBackgroundResource(tk.attrResource(android.R.attr.selectableItemBackgroundBorderless));
        if (enabled) view.setOnClickListener(v -> onClick.run());
        view.setLayoutParams(new LinearLayout.LayoutParams(tk.dp(48), tk.dp(40)));
        return view;
    }

    /** Lets a grid be swiped sideways to turn the page, while taps still reach its tiles. */
    static final class Swipe extends FrameLayout {
        private final int page;
        private final int pages;
        private final MainActivity.OnPage onPage;
        private final int slop;
        private float downX;
        private float downY;
        private boolean dragging;

        Swipe(Context context, int page, int pages, MainActivity.OnPage onPage) {
            super(context);
            this.page = page;
            this.pages = pages;
            this.onPage = onPage;
            slop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            return track(e);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            track(e);
            return pages > 1;
        }

        private boolean track(MotionEvent e) {
            if (pages < 2) return false;
            // Screen coordinates: the view itself slides with the finger.
            float dx = e.getRawX() - downX;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    dragging = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(dx) > slop * 2 && Math.abs(dx) > Math.abs(e.getRawY() - downY)) {
                        dragging = true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    // Follow the finger, with resistance at the first and last page.
                    if (dragging) setTranslationX((dx < 0 ? page < pages - 1 : page > 0) ? dx : dx / 4);
                    break;
                case MotionEvent.ACTION_UP:
                    if (dragging) {
                        dragging = false;
                        int next = dx < 0 ? page + 1 : page - 1;
                        if (Math.abs(dx) > getWidth() / 6f && next >= 0 && next < pages) {
                            onPage.run(next);
                        } else {
                            animate().translationX(0).setDuration(150).start();
                        }
                        return true;
                    }
                    break;
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    animate().translationX(0).setDuration(150).start();
                    break;
            }
            return dragging;
        }
    }

    void moveTile(LinearLayout list, List<String> tiles, int from, int to) {
        if (from == to || from < 0 || from >= tiles.size() || to < 0 || to >= tiles.size()) return;
        List<String> next = new ArrayList<>(tiles);
        next.add(to, next.remove(from));
        saveTiles(list, tiles, next);
    }

    /** Shows the new order at once, then writes it; goes back to the old order if that fails. */
    void saveTiles(LinearLayout list, List<String> old, List<String> next) {
        renderQuickPanel(list, next);
        tk.worker.execute(() -> {
            Shell.Result r = Shell.run("settings put secure " + QS_TILES + " "
                    + Shell.quote(TextUtils.join(",", next)));
            if (r.ok()) return;
            tk.main.post(() -> {
                tk.toast(Tr.t(r.out.isEmpty() ? "Couldn't change the quick panel." : r.out));
                renderQuickPanel(list, old);
            });
        });
    }

    List<QsTile> availableTiles(List<String> tiles) {
        Set<String> used = new HashSet<>();
        for (String spec : tiles) used.add(normalizeTile(spec));
        List<QsTile> result = new ArrayList<>();
        for (String spec : tk.prefs.getStringSet("qs_known", Collections.emptySet())) {
            if (!used.contains(spec)) result.add(tile(spec));
        }
        for (String spec : qsAppTiles) {
            if (!used.contains(normalizeTile(spec))) result.add(tile(spec));
        }
        Collections.sort(result, (a, b) -> a.title.compareToIgnoreCase(b.title));
        return result;
    }

    /** custom(pkg/.Name) and custom(pkg/pkg.Name) are the same tile. */
    static String normalizeTile(String spec) {
        if (!spec.startsWith("custom(") || !spec.endsWith(")")) return spec;
        ComponentName c = ComponentName.unflattenFromString(spec.substring(7, spec.length() - 1));
        return c == null ? spec : "custom(" + c.flattenToShortString() + ")";
    }

    QsTile tile(String spec) {
        QsTile tile = qsCache.get(spec);
        if (tile == null) qsCache.put(spec, tile = describeTile(spec));
        return tile;
    }

    QsTile describeTile(String spec) {
        QsTile tile = new QsTile();
        tile.spec = spec;
        if (spec.startsWith("custom(") && spec.endsWith(")")) {
            ComponentName c = ComponentName.unflattenFromString(spec.substring(7, spec.length() - 1));
            PackageManager pm = tk.getPackageManager();
            try {
                ServiceInfo si = pm.getServiceInfo(c, 0);
                tile.title = String.valueOf(si.loadLabel(pm));
                tile.sub = String.valueOf(si.applicationInfo.loadLabel(pm));
                // A tile without its own icon shows the app's, which is in full color.
                tile.icon = si.icon != 0 ? si.loadIcon(pm) : si.applicationInfo.loadIcon(pm);
                tile.tint = si.icon != 0 && !opaqueImage(tile.icon);
            } catch (PackageManager.NameNotFoundException | NullPointerException e) {
                tile.title = c == null ? spec : c.getPackageName();
                tile.sub = "App not installed";
            }
            return tile;
        }
        tile.title = spec.substring(0, 1).toUpperCase(Locale.ROOT) + spec.substring(1).replace('_', ' ');
        for (String[] stock : STOCK_TILES) {
            if (stock[0].equals(spec)) tile.title = stock[1];
        }
        tile.sub = "Built in";
        tile.icon = systemIcon(spec);
        tile.tint = true;
        return tile;
    }

    /** A picture rather than a mask, which tinting would turn into a solid square. */
    static boolean opaqueImage(Drawable icon) {
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888);
        Drawable d = icon.getConstantState() != null ? icon.getConstantState().newDrawable() : icon;
        d.setBounds(0, 0, 16, 16);
        d.draw(new android.graphics.Canvas(b));
        int solid = 0;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) if (Color.alpha(b.getPixel(x, y)) > 200) solid++;
        b.recycle();
        // Masks are mostly see-through; a picture fills most of its square.
        return solid > 16 * 16 * 0.6;
    }

    /** SystemUI's drawable names for each built-in tile, EMUI's first, then stock Android's. */
    static final String[][] TILE_ICONS = {
            {"wifi", "wifi", "wifi_signal_4"}, {"internet", "wifi", "wifi_signal_4"}, {"bt", "bluetooth"},
            {"cell", "dataswitch", "lte"}, {"data", "dataswitch", "lte"}, {"flashlight", "flashlight"},
            {"sound", "vibration"}, {"rotation", "rotation", "auto_rotate"}, {"location", "gps", "location"},
            {"hotspot", "hotspot"}, {"airplane", "airplanemode", "airplane"}, {"dnd", "dnd", "busymode"},
            {"busy", "busymode", "dnd"}, {"battery", "lowpowermode", "battery_saver"},
            {"superpower", "lowpowermode", "battery_saver"}, {"saver", "data_saver"}, {"cast", "cast"},
            {"nfc", "nfc"}, {"night", "nightlight"}, {"dark", "darkmode", "light_dark_theme"},
            {"eyecomfort", "eyecomfort"}, {"inversion", "invert_colors"},
            {"screenrecord", "screenrecorder", "screen_record"}, {"recorder", "screenrecorder", "screen_record"},
            {"screenshot", "screenshot"}, {"airsharing", "instantshare"}, {"cameratoggle", "camera_access"},
            {"mictoggle", "mic_access"}, {"reduce_brightness", "extra_dim"},
            {"color_correction", "color_correction"}, {"font_scaling", "font_scaling"}};

    static final String[] ICON_PATTERNS = {"ic_%s_tile_on", "%s_tile_on", "qs_%s_icon_on",
            "ic_qs_%s_on", "ic_qs_%s", "ic_%s_on", "ic_%s_enable", "ic_%s"};

    /** The icon SystemUI draws for a built-in tile, looked up by name; null if there isn't one. */
    Drawable systemIcon(String spec) {
        if (qsIcons.containsKey(spec)) return qsIcons.get(spec);
        String[] names = {spec};
        for (String[] row : TILE_ICONS) {
            if (row[0].equals(spec)) names = Arrays.copyOfRange(row, 1, row.length);
        }
        Context ui = systemUi();
        Drawable icon = null;
        search:
        for (String name : names) {
            for (String pattern : ICON_PATTERNS) {
                String res = String.format(Locale.ROOT, pattern, name);
                icon = load(ui, res, "com.android.systemui");
                if (icon == null) icon = load(null, res, "android");
                if (icon != null) break search;
            }
        }
        qsIcons.put(spec, icon);
        return icon;
    }

    Drawable load(Context ui, String name, String pkg) {
        try {
            android.content.res.Resources res = ui != null ? ui.getResources()
                    : android.content.res.Resources.getSystem();
            int id = res.getIdentifier(name, "drawable", pkg);
            return id == 0 ? null : res.getDrawable(id, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    TextView note(String message) {
        TextView view = tk.text(message, 13, false);
        tk.setDim(view, 0.6f);
        view.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), tk.dp(4));
        return view;
    }

    /**
     * A generic "Sort" control: an outlined chip showing the current choice that opens a menu of
     * options when tapped, calling onSelect with the chosen index. Reused by every sortable list.
     */
    View sortBar(String[] options, MainActivity.OnPage onSelect) {
        LinearLayout bar = new LinearLayout(tk);
        bar.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        bar.setPadding(tk.dp(12), tk.dp(8), tk.dp(12), tk.dp(4));
        bar.addView(chip("↕  ", "Sort", options, onSelect));
        return bar;
    }

    View sortBarLike(View chip) {
        LinearLayout bar = new LinearLayout(tk);
        bar.setPadding(0, tk.dp(8), 0, tk.dp(4));
        bar.addView(chip);
        return bar;
    }

    /** A rounded button that shows "Title: choice" and opens a list of {@code options} when tapped. */
    TextView chip(String icon, String title, String[] options, MainActivity.OnPage onSelect) {
        final int[] sel = {0};
        TextView chip = tk.text("", 13, true);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setPadding(tk.dp(12), tk.dp(6), tk.dp(12), tk.dp(6));
        chip.setTextColor(tk.attrColor(android.R.attr.colorAccent));
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(tk.dp(22));
        shape.setColor(tk.lightTheme ? 0xFFFFFFFF : 0xFF161616);
        shape.setStroke(tk.dp(1), tk.lightTheme ? 0x26000000 : 0x40FFFFFF);
        chip.setBackground(shape);
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-2, -2);
        margin.setMargins(0, 0, tk.dp(8), 0);
        chip.setLayoutParams(margin);
        Runnable render = () -> chip.setText((icon.isEmpty() ? "" : icon) + Tr.t(title) + ": " + Tr.t(options[sel[0]]));
        chip.setOnClickListener(v -> {
            String[] items = new String[options.length];
            for (int i = 0; i < options.length; i++) {
                items[i] = (i == sel[0] ? "●  " : "○  ") + Tr.t(options[i]);
            }
            new AlertDialog.Builder(tk)
                    .setTitle(Tr.t(title))
                    .setItems(items, (d, which) -> {
                        sel[0] = which;
                        render.run();
                        onSelect.run(which);
                    })
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        render.run();
        return chip;
    }
}
