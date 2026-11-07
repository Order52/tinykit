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
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class SavedWifiScreen {
    private final MainActivity tk;

    SavedWifiScreen(MainActivity tk) {
        this.tk = tk;
    }

    void openWifi() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), 0);
        TextView help = tk.text("Tap a network to see its password and a QR code to share it.", 13, false);
        tk.setDim(help, 0.7f);
        page.addView(help);
        LinearLayout buttons = new LinearLayout(tk);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f);
        half.setMargins(0, tk.dp(4), tk.dp(8), tk.dp(4));
        Button export = tk.button("Export", null);
        Button importButton = tk.button("Import", null);
        buttons.addView(export, half);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1f);
        second.setMargins(0, tk.dp(4), 0, tk.dp(4));
        buttons.addView(importButton, second);
        page.addView(buttons);
        EditText search = tk.searchField("Search networks");
        page.addView(search, tk.fieldMargin());
        TextView loading = tk.text("Reading saved networks…", 14, false);
        page.addView(loading);
        ListView listView = new ListView(tk);
        listView.setFastScrollEnabled(true);
        page.addView(listView, new LinearLayout.LayoutParams(-1, 0, 1f));
        tk.push("Saved Wi-Fi", page);

        MainActivity.Adapter<String[]> adapter = tk.new Adapter<>(false, (n, row) -> {
            row.first.setText(Tr.t(n[0]));
            row.second.setText(Tr.t("nopass".equals(n[1]) ? "Open network"
                    : n[2].isEmpty() ? n[1] : n[1] + "  ·  " + n[2]));
        });
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> showWifi(adapter.getItem(position)));
        List<String[]> all = new ArrayList<>();
        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            List<String[]> shown = new ArrayList<>();
            for (String[] n : all) if (n[0].toLowerCase(Locale.ROOT).contains(q)) shown.add(n);
            adapter.set(shown);
        };
        search.addTextChangedListener(tk.watcher(filter));
        Runnable[] reload = new Runnable[1];
        reload[0] = () -> tk.worker.execute(() -> {
            List<String[]> nets = readWifi();
            tk.main.post(() -> {
                all.clear();
                all.addAll(nets);
                filter.run();
                loading.setVisibility(nets.isEmpty() ? View.VISIBLE : View.GONE);
                if (nets.isEmpty()) {
                    loading.setText(Tr.t("No saved networks, or this phone doesn't allow reading them."));
                }
            });
        });
        reload[0].run();
        export.setOnClickListener(v -> {
            if (all.isEmpty()) {
                tk.toast(Tr.t("No saved networks, or this phone doesn't allow reading them."));
                return;
            }
            new AlertDialog.Builder(tk)
                    .setTitle(Tr.t("Export networks?"))
                    .setMessage(Tr.t("Saves every network with its password as plain text in Download/TinyKit. "
                            + "Anyone who gets the file can read the passwords, so delete it when you're done."))
                    .setPositiveButton(Tr.t("Export"), (d, w) -> tk.worker.execute(() -> {
                        StringBuilder text = new StringBuilder();
                        for (String[] n : all) text.append(n[0]).append('\t').append(n[1]).append('\t').append(n[2]).append('\n');
                        String file = MainActivity.BACKUP_DIR + "/wifi-" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT)
                                .format(new java.util.Date()) + ".txt";
                        Shell.Result r = Shell.run("mkdir -p " + MainActivity.BACKUP_DIR + "; cat > " + Shell.quote(file),
                                new java.io.ByteArrayInputStream(text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        tk.main.post(() -> tk.toast(r.ok() ? Tr.t("Saved to ") + file : Tr.t("Couldn't save the file.")));
                    }))
                    .setNegativeButton(Tr.t("Cancel"), null)
                    .show();
        });
        importButton.setOnClickListener(v -> tk.worker.execute(() -> {
            List<String> files = new ArrayList<>();
            for (String line : Shell.run("ls -1t " + MainActivity.BACKUP_DIR + "/wifi-*.txt 2>/dev/null").out.split("\n")) {
                if (!line.trim().isEmpty()) files.add(line.trim());
            }
            tk.main.post(() -> {
                if (files.isEmpty()) {
                    tk.toast(Tr.t("No exported networks yet. Export first, or put a wifi-….txt file in Download/TinyKit."));
                    return;
                }
                String[] names = new String[files.size()];
                for (int i = 0; i < names.length; i++) names[i] = files.get(i).substring(files.get(i).lastIndexOf('/') + 1);
                new AlertDialog.Builder(tk)
                        .setTitle(Tr.t("Import which file?"))
                        .setItems(names, (d, which) -> tk.worker.execute(() -> {
                            String apk = Shell.run("pm path " + tk.getPackageName() + " | head -1 | sed 's/^package://'").out.trim();
                            Shell.Result r = Shell.run("CLASSPATH=" + Shell.quote(apk)
                                    + " app_process /system/bin io.github.order52.tinykit.WifiDump add "
                                    + Shell.quote(files.get(which)));
                            tk.main.post(() -> {
                                tk.toast(r.ok() ? Tr.t("Added ") + r.out.trim() + Tr.t(" networks that weren't saved yet.")
                                        : Tr.t("This phone doesn't let TinyKit add networks."));
                                reload[0].run();
                            });
                        }))
                        .setNegativeButton(Tr.t("Cancel"), null)
                        .show();
            });
        }));
    }

    /** A feature request, bug report or question that goes to GitHub as an issue, through the browser. */
    void openFeedback() {
        LinearLayout page = tk.vertical();
        page.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), tk.dp(24));
        TextView help = tk.text("Your message opens as a new issue on GitHub, in your browser. You read it, change it if you "
                + "like and press Submit there. TinyKit sends nothing itself. You need a free GitHub account.", 13, false);
        tk.setDim(help, 0.7f);
        page.addView(help);
        String[] kinds = {"[FEATURE REQUEST]", "[BUG]", "[QUESTION]", "[FEEDBACK]"};
        String[] names = {"Feature request", "Problem", "Question", "Anything else"};
        RadioGroup group = new RadioGroup(tk);
        int[] ids = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            RadioButton b = new RadioButton(tk);
            ids[i] = View.generateViewId();
            b.setId(ids[i]);
            b.setText(Tr.t(names[i]));
            group.addView(b);
        }
        group.check(ids[0]);
        page.addView(group);
        EditText title = new EditText(tk);
        title.setHint(Tr.t("Short title"));
        title.setSingleLine();
        title.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        page.addView(title, tk.fieldMargin());
        EditText message = new EditText(tk);
        message.setHint(Tr.t("What do you want, or what went wrong?"));
        message.setMinLines(5);
        message.setGravity(Gravity.TOP);
        message.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        page.addView(message, tk.fieldMargin());
        CheckBox phone = new CheckBox(tk);
        phone.setText(Tr.t("Add my phone model, Android and TinyKit version"));
        phone.setChecked(true);
        page.addView(phone);
        Button send = tk.button("Open on GitHub", null);
        send.setOnClickListener(v -> {
            if (title.getText().toString().trim().isEmpty() || message.getText().toString().trim().isEmpty()) {
                tk.toast(Tr.t("Write a title and a message first."));
                return;
            }
            try {
                int kind = 0;
                for (int i = 0; i < ids.length; i++) if (ids[i] == group.getCheckedRadioButtonId()) kind = i;
                tk.startActivity(CrashReport.feedback(tk, kinds[kind],
                        title.getText().toString(), message.getText().toString(), phone.isChecked()));
            } catch (RuntimeException e) {
                tk.toast(Tr.t("No browser to open GitHub."));
            }
        });
        page.addView(send);
        ScrollView scroll = new ScrollView(tk);
        scroll.addView(page);
        tk.push("Send feedback", scroll);
    }

    /** Runs {@link WifiDump} as the shell user through Shizuku and parses its lines. */
    List<String[]> readWifi() {
        String apk = Shell.run("pm path " + tk.getPackageName() + " | head -1 | sed 's/^package://'").out.trim();
        List<String[]> nets = new ArrayList<>();
        if (apk.isEmpty()) return nets;
        Shell.Result r = Shell.run("CLASSPATH=" + Shell.quote(apk)
                + " app_process /system/bin io.github.order52.tinykit.WifiDump");
        if (!r.ok()) return nets;
        for (String line : r.out.split("\n")) {
            String[] parts = line.split("\t", -1);
            if (parts.length >= 3 && !parts[0].trim().isEmpty()) {
                nets.add(new String[]{parts[0], parts[1], parts[2]});
            }
        }
        Collections.sort(nets, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        return nets;
    }

    void showWifi(String[] net) {
        String ssid = net[0], type = net[1], password = net[2];
        LinearLayout box = tk.vertical();
        box.setPadding(tk.dp(24), tk.dp(12), tk.dp(24), 0);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        Bitmap qr = qrBitmap(wifiQr(ssid, type, password), tk.dp(240));
        if (qr != null) {
            ImageView image = new ImageView(tk);
            image.setImageBitmap(qr);
            box.addView(image, new LinearLayout.LayoutParams(tk.dp(240), tk.dp(240)));
        }
        TextView detail = tk.text("nopass".equals(type) ? "Open network — no password" : "Password: " + password, 16, false);
        detail.setTextIsSelectable(true);
        detail.setPadding(0, tk.dp(16), 0, 0);
        detail.setGravity(Gravity.CENTER);
        box.addView(detail);
        new AlertDialog.Builder(tk)
                .setTitle(Tr.t(ssid))
                .setView(box)
                .setPositiveButton(Tr.t("Close"), null)
                .setNeutralButton(Tr.t("Copy password"), "nopass".equals(type) ? null : (d, w) -> {
                    ((android.content.ClipboardManager) tk.getSystemService(Context.CLIPBOARD_SERVICE))
                            .setPrimaryClip(android.content.ClipData.newPlainText("Wi-Fi password", password));
                    tk.toast(Tr.t("Password copied"));
                })
                .show();
    }

    /** The WIFI: payload a phone camera reads to join a network. */
    static String wifiQr(String ssid, String type, String password) {
        String auth = "nopass".equals(type) ? "nopass" : "WEP".equals(type) ? "WEP" : "WPA";
        return "WIFI:T:" + auth + ";S:" + qrEscape(ssid) + ";P:" + qrEscape(password) + ";;";
    }

    static String qrEscape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    /** Renders a QR code to a square bitmap with a white quiet zone, or null if it won't fit. */
    Bitmap qrBitmap(String text, int sizePx) {
        Qr qr = Qr.encode(text);
        if (qr == null) return null;
        int quiet = 4;
        int modules = qr.size + quiet * 2;
        int scale = Math.max(1, sizePx / modules);
        int dim = modules * scale;
        Bitmap bitmap = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        for (int y = 0; y < qr.size; y++) {
            for (int x = 0; x < qr.size; x++) {
                if (qr.get(x, y)) {
                    float px = (x + quiet) * scale;
                    float py = (y + quiet) * scale;
                    canvas.drawRect(px, py, px + scale, py + scale, paint);
                }
            }
        }
        return bitmap;
    }
}
