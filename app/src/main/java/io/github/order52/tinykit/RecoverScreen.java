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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaScannerConnection;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class RecoverScreen {
    private final MainActivity tk;

    RecoverScreen(MainActivity tk) {
        this.tk = tk;
    }

    static final String RECOVER_DIR = "/sdcard/Download/TinyKit/Recovered";

    static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static final long THUMB_MAX = 8L * 1024 * 1024;

    static final Set<String> IMAGE_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "dng"));

    static final class Deleted {
        String path;
        String name;
        String source;
        String sizeText;
        String date;
        long bytes;
        long mtime;
        boolean image;
    }

    void openRecover() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView help = tk.text("Files found in every recycle bin and trash, with thumbnails. "
                + "Tap one to restore it to Download/TinyKit/Recovered. Largest first.", 13, false);
        tk.setDim(help, 0.7f);
        page.addView(help);
        TextView loading = tk.text("Searching…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push("Recover files", page);

        RecoverAdapter adapter = new RecoverAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Deleted item = adapter.getItem(position);
            new AlertDialog.Builder(tk)
                    .setTitle(Tr.t("Restore this file?"))
                    .setMessage(Tr.t(item.name + "\n\nCopied to Download/TinyKit/Recovered. The original stays in the bin."))
                    .setPositiveButton(Tr.t("Restore"), (d, w) -> restoreFile(item))
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        final List<Deleted> items = new ArrayList<>();
        final int[] sort = {0};
        Runnable apply = () -> {
            if (sort[0] == 1) Collections.sort(items, (x, y) -> Long.compare(y.mtime, x.mtime));
            else if (sort[0] == 2) Collections.sort(items, (x, y) -> x.name.compareToIgnoreCase(y.name));
            else Collections.sort(items, (x, y) -> Long.compare(y.bytes, x.bytes));
            adapter.set(items);
        };
        page.addView(tk.sortBar(new String[]{"Size", "Date", "Name"}, i -> {
            sort[0] = i;
            apply.run();
        }), 1);
        tk.worker.execute(() -> {
            List<Deleted> found = scanRecoverable();
            tk.main.post(() -> {
                loading.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
                if (found.isEmpty()) loading.setText(Tr.t("Nothing found in any recycle bin or trash."));
                items.clear();
                items.addAll(found);
                apply.run();
            });
        });
    }

    /** One fast pass with {@code find -printf} over every trash location. */
    List<Deleted> scanRecoverable() {
        String find = "find /sdcard/ -type f \\( -path '*/.Gallery2/recycle/*' "
                + "-o -path '*/.File_Recycle/*' -o -path '*/.fvTrash/*' -o -path '*/.thumbnails/*' "
                + "-o -path '*/LOST.DIR/*' -o -name '.trashed-*' \\) -printf '%s|%T@|%p\\n' 2>/dev/null";
        long now = System.currentTimeMillis();
        List<Deleted> items = new ArrayList<>();
        for (String line : Shell.run(find).out.split("\n")) {
            String[] parts = line.split("\\|", 3);
            if (parts.length < 3) continue;
            long bytes;
            try {
                bytes = Long.parseLong(parts[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (bytes <= 0) continue;
            long mtime = 0;
            try {
                mtime = (long) (Double.parseDouble(parts[1].trim()) * 1000);
            } catch (NumberFormatException ignored) {
            }
            String path = parts[2];
            String base = path.substring(path.lastIndexOf('/') + 1);
            Deleted d = new Deleted();
            d.path = path;
            d.bytes = bytes;
            d.mtime = mtime;
            d.sizeText = Formatter.formatShortFileSize(tk, bytes);
            d.date = mtime > 0 ? DateUtils.getRelativeTimeSpanString(mtime, now, DateUtils.DAY_IN_MILLIS).toString() : "";
            if (path.contains("/.Gallery2/recycle/")) {
                d.source = "Gallery recycle bin";
                String decoded = decodeGalleryName(base);
                d.name = decoded != null ? decoded : base;
                d.image = true;
            } else if (base.startsWith(".trashed-")) {
                d.source = "Trash";
                d.name = base.replaceFirst("^\\.trashed-\\d+-", "");
            } else if (path.contains("/.File_Recycle/")) {
                d.source = "File recycle bin";
                d.name = base;
            } else if (path.contains("/.fvTrash/")) {
                d.source = "Trash";
                d.name = base;
            } else if (path.contains("/LOST.DIR/")) {
                d.source = "Recovered fragments";
                d.name = base;
            } else {
                d.source = "Thumbnail cache";
                d.name = base.isEmpty() ? "thumbnail" : base;
            }
            if (!d.image) d.image = IMAGE_EXT.contains(extensionOf(d.name));
            items.add(d);
            if (items.size() >= 6000) break;
        }
        Collections.sort(items, (x, y) -> Long.compare(y.bytes, x.bytes));
        return items;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** Rows with a lazily-loaded thumbnail for images and a file icon otherwise. */
    final class RecoverAdapter extends BaseAdapter {
        private List<Deleted> items = new ArrayList<>();

        void set(List<Deleted> list) {
            items = list;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Deleted getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView icon;
            TextView name, sub;
            View row;
            if (convertView == null) {
                LinearLayout line = new LinearLayout(tk);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setPadding(tk.dp(16), tk.dp(8), tk.dp(16), tk.dp(8));
                icon = new ImageView(tk);
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(tk.dp(48), tk.dp(48));
                ip.setMargins(0, 0, tk.dp(14), 0);
                line.addView(icon, ip);
                LinearLayout texts = tk.vertical();
                name = tk.text("", 15, false);
                name.setMaxLines(1);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                sub = tk.text("", 12, false);
                tk.setDim(sub, 0.65f);
                texts.addView(name);
                texts.addView(sub);
                line.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
                line.setTag(new Object[]{icon, name, sub});
                row = line;
            } else {
                row = convertView;
                Object[] tag = (Object[]) row.getTag();
                icon = (ImageView) tag[0];
                name = (TextView) tag[1];
                sub = (TextView) tag[2];
            }
            Deleted d = items.get(position);
            name.setText(Tr.t(d.name));
            sub.setText(Tr.t(d.source + "  ·  " + d.sizeText + (d.date.isEmpty() ? "" : "  ·  " + d.date)));
            icon.setTag(d.path);
            Bitmap cached = tk.thumbCache.get(d.path);
            if (cached != null) {
                icon.setImageBitmap(cached);
            } else {
                icon.setImageResource(d.image ? R.drawable.ic_image : R.drawable.ic_file);
                if (d.image && d.bytes <= THUMB_MAX) loadThumb(d, icon);
            }
            return row;
        }
    }

    void loadThumb(Deleted d, ImageView icon) {
        tk.thumbs.execute(() -> {
            if (!d.path.equals(icon.getTag()) || tk.thumbCache.containsKey(d.path)) return;
            Bitmap bitmap = makeThumb(d.path);
            if (bitmap == null) return;
            tk.thumbCache.put(d.path, bitmap);
            tk.main.post(() -> {
                if (d.path.equals(icon.getTag())) icon.setImageBitmap(bitmap);
            });
        });
    }

    /**
     * Reads a file's bytes as the shell user through Shizuku and decodes a small thumbnail. This works
     * for files in trash that the app can't open directly.
     */
    Bitmap makeThumb(String path) {
        try {
            byte[] bytes = Shell.bytes("cat " + Shell.quote(path) + " 2>/dev/null", (int) THUMB_MAX);
            if (bytes == null || bytes.length == 0) return null;
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outHeight <= 0) return null;
            int sample = 1;
            while (bounds.outHeight / sample > 160 || bounds.outWidth / sample > 160) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Gallery2 names files as Base32 of "…|name|.ext|mime|…"; decode and rebuild the real name. */
    static String decodeGalleryName(String base32) {
        try {
            String cleaned = base32.toUpperCase(Locale.ROOT).replaceAll("[^A-Z2-7]", "");
            if (cleaned.length() < 8) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int buffer = 0, bits = 0;
            for (char c : cleaned.toCharArray()) {
                buffer = (buffer << 5) | BASE32.indexOf(c);
                bits += 5;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((buffer >> bits) & 0xFF);
                }
            }
            String decoded = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            if (!decoded.contains("|")) return null;
            String[] fields = decoded.split("\\|");
            String name = null, ext = "";
            for (String f : fields) {
                if (f.contains("/") || f.equals("null") || f.isEmpty()) continue;
                if (f.startsWith(".") && f.length() <= 6) {
                    ext = f;
                } else if (f.matches(".*[A-Za-z].*") && !f.matches("\\d+")) {
                    // Prefer the longest field with letters: the real base name, not an id.
                    if (name == null || f.length() > name.length()) name = f.trim();
                }
            }
            if (name == null) return null;
            return name.contains(".") ? name : name + ext;
        } catch (RuntimeException e) {
            return null;
        }
    }

    void restoreFile(Deleted item) {
        tk.toast(Tr.t("Restoring " + item.name + "…"));
        tk.worker.execute(() -> {
            // Never over an earlier recovered file: name-1.jpg, name-2.jpg and so on.
            String name = tk.fileName(item.name);
            int dot = name.lastIndexOf('.');
            String base = RECOVER_DIR + "/" + (dot > 0 ? name.substring(0, dot) : name);
            String ext = dot > 0 ? name.substring(dot) : "";
            Shell.Result r = Shell.run("mkdir -p " + RECOVER_DIR + "; b=" + Shell.quote(base) + "; e=" + Shell.quote(ext)
                    + "; f=\"$b$e\"; i=1; while [ -e \"$f\" ]; do f=\"$b-$i$e\"; i=$((i+1)); done; "
                    + "cp " + Shell.quote(item.path) + " \"$f\" && echo \"$f\"");
            String dest = r.out.trim();
            tk.main.post(() -> {
                if (r.ok() && dest.startsWith(RECOVER_DIR)) {
                    MediaScannerConnection.scanFile(tk, new String[]{dest}, null, null);
                    tk.toast(Tr.t("Restored to Download/TinyKit/Recovered"));
                } else {
                    tk.toast(Tr.t(r.out.isEmpty() ? "Couldn't restore it." : r.out));
                }
            });
        });
    }
}
