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

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class MixerScreen {
    private final MainActivity tk;

    MixerScreen(MainActivity tk) {
        this.tk = tk;
    }

    /** One app of the mixer, by uid: a work profile's copy of an app has its own uid. */
    static final class Voice {
        int uid;
        String pkg;
        String label;
        boolean work;
    }

    final java.util.concurrent.atomic.AtomicBoolean mixerQueued = new java.util.concurrent.atomic.AtomicBoolean();

    /** Sends the volumes to the helper; changes made while it is busy ride along with the next send. */
    void mixerApply() {
        if (mixerQueued.getAndSet(true)) return;
        tk.worker.execute(() -> {
            mixerQueued.set(false);
            Mixer.apply(tk, tk.prefs);
        });
    }

    /** The app behind a uid. A work profile's apps are copies of the main profile's, with the same app id. */
    Voice voice(int uid) {
        Voice v = new Voice();
        v.uid = uid;
        int owner = Work.parent();
        v.work = uid / 100000 != owner;
        v.label = Tr.t("App") + " " + uid;
        PackageManager pm = tk.getPackageManager();
        String[] names = pm.getPackagesForUid(uid % 100000 + owner * 100000);
        if (names != null && names.length > 0) {
            v.pkg = names[0];
            try {
                v.label = String.valueOf(pm.getApplicationInfo(v.pkg, 0).loadLabel(pm));
            } catch (PackageManager.NameNotFoundException e) {
                v.label = v.pkg;
            }
        }
        return v;
    }

    static final String NOTE_SCALE = "Android caps an app at 100%. To make one app louder, raise this and turn the others down.";

    static final String NOTE_MUTE = "This Android can only mute an app, not turn it down. Android 13 and newer can do both.";

    static String percentText(int percent, boolean muteOnly) {
        return percent == 0 ? Tr.t("Muted") : muteOnly ? Tr.t("Sound on") : percent + "%";
    }

    SeekBar slider(int max, int progress) {
        SeekBar bar = new SeekBar(tk);
        bar.setMax(max);
        bar.setProgress(progress);
        android.content.res.ColorStateList accent = android.content.res.ColorStateList.valueOf(
                tk.attrColor(android.R.attr.colorAccent));
        bar.setProgressTintList(accent);
        bar.setThumbTintList(accent);
        return bar;
    }

    interface Changed {
        void to(int progress, boolean fromUser);
    }

    static void onSlide(SeekBar bar, boolean[] dragging, Changed changed) {
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int progress, boolean fromUser) {
                changed.to(progress, fromUser);
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
                dragging[0] = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
                dragging[0] = false;
            }
        });
    }

    /** One app's line in the mixer: icon, name, what it is doing, its percent, and the slider. */
    final class MixRow {
        final LinearLayout view = tk.vertical();
        final ImageView icon = new ImageView(tk);
        final TextView name = tk.text("", 16, false);
        final TextView sub = tk.text("", 13, false);
        final TextView value = tk.text("", 14, true);
        final SeekBar bar = slider(20, 20);
        Voice voice;
        final boolean[] mute; // this Android can only mute an app: the slider has two stops

        MixRow(Map<Integer, Integer> volumes, boolean[] dragging, boolean[] mute) {
            this.mute = mute;
            view.setPadding(tk.dp(16), tk.dp(10), tk.dp(16), tk.dp(2));
            LinearLayout top = new LinearLayout(tk);
            top.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(tk.dp(40), tk.dp(40));
            size.setMargins(0, 0, tk.dp(14), 0);
            top.addView(icon, size);
            LinearLayout texts = tk.vertical();
            texts.addView(name);
            sub.setAlpha(0.7f);
            texts.addView(sub);
            top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
            top.addView(value);
            view.addView(top);
            view.addView(bar);
            view.setTag(this);
            onSlide(bar, dragging, (progress, fromUser) -> {
                int percent = mute[0] ? progress * 100 : progress * 5;
                value.setText(percentText(percent, mute[0]));
                if (!fromUser || voice == null) return;
                if (percent == 100) volumes.remove(voice.uid);
                else volumes.put(voice.uid, percent);
                Mixer.save(tk.prefs, volumes);
                if (mute[0]) {
                    Voice v = voice;
                    tk.worker.execute(() -> {
                        if (v.pkg != null && !Mixer.mute(v.pkg, v.uid / 100000, percent == 0)) {
                            tk.main.post(() -> tk.toast(Tr.t("Couldn't change that app's sound.")));
                        }
                    });
                } else {
                    mixerApply();
                }
            });
        }

        void bind(Voice v, String state, int percent) {
            voice = v;
            name.setText(v.label);
            sub.setText(state);
            icon.setTag(null);
            if (v.pkg != null) tk.showIcon(icon, v.pkg);
            else icon.setImageResource(android.R.drawable.sym_def_app_icon);
            value.setText(percentText(percent, mute[0]));
            bar.setMax(mute[0] ? 1 : 20);
            bar.setProgress(mute[0] ? (percent == 0 ? 0 : 1) : percent / 5);
        }
    }

    /** The audio mixer: the apps open now on top, every app below, a volume slider for each. */
    void openMixer() {
        final Map<Integer, Integer> volumes = new java.util.TreeMap<>();
        final Set<Integer> playing = new HashSet<>();
        final List<Integer> open = new ArrayList<>();
        final String[] shown = {null}; // what the "open now" part was drawn from
        final boolean[] dragging = {false};
        // Before Android 13 (unless Shizuku is root) an app can only be muted, so the sliders have two stops.
        final boolean[] mute = {Build.VERSION.SDK_INT < 33};
        final int[] gen = {0};
        final boolean[] polling = {false};
        final boolean[] wasAlive = {false};
        final List<Voice> all = new ArrayList<>();
        final List<Voice> filtered = new ArrayList<>();
        final Set<Integer> launchable = new HashSet<>();

        ListView page = new ListView(tk);
        page.setDivider(null);
        page.setHeaderDividersEnabled(false);
        LinearLayout top = tk.vertical();
        LinearLayout upper = tk.vertical(); // everything above the list of all apps, hidden while searching
        top.addView(upper);

        TextView problem = tk.text("", 13, false);
        problem.setTextColor(tk.attrColor(android.R.attr.colorAccent));
        problem.setPadding(tk.dp(16), tk.dp(12), tk.dp(16), tk.dp(12));
        problem.setBackground(tk.roundRect(tk.lightTheme ? 0xFFFFFFFF : 0xFF121212, 16));
        problem.setVisibility(View.GONE);
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, -2);
        margin.setMargins(tk.dp(12), tk.dp(8), tk.dp(12), 0);
        upper.addView(problem, margin);

        tk.section(upper, "Phone volume");
        AudioManager audio = (AudioManager) tk.getSystemService(Context.AUDIO_SERVICE);
        LinearLayout master = tk.vertical();
        master.setPadding(tk.dp(16), tk.dp(4), tk.dp(16), tk.dp(4));
        LinearLayout masterTop = new LinearLayout(tk);
        masterTop.setGravity(Gravity.CENTER_VERTICAL);
        masterTop.addView(tk.text("Media volume", 16, false), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView masterValue = tk.text("", 14, true);
        masterTop.addView(masterValue);
        master.addView(masterTop);
        int steps = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        SeekBar masterBar = slider(steps, audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        master.addView(masterBar);
        upper.addView(master);
        final boolean[] masterDragging = {false};
        onSlide(masterBar, masterDragging, (progress, fromUser) -> {
            masterValue.setText(progress * 100 / steps + "%");
            if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
        });
        masterValue.setText(masterBar.getProgress() * 100 / steps + "%");
        TextView mixNote = tk.note(mute[0] ? NOTE_MUTE : NOTE_SCALE);
        upper.addView(mixNote);

        tk.section(upper, "Open now");
        LinearLayout openBox = tk.vertical();
        upper.addView(openBox);
        TextView empty = tk.note("No app is open. Open one, or start a video or a song, and it shows up here.");
        upper.addView(empty);

        Button reset = tk.button("Reset all volumes", v -> {
            volumes.clear();
            Mixer.save(tk.prefs, volumes);
            mixerApply();
            shown[0] = null;
            ((Runnable) page.getTag()).run();
        });
        reset.setPadding(tk.dp(24), 0, tk.dp(24), 0);
        LinearLayout.LayoutParams resetMargin = new LinearLayout.LayoutParams(-2, -2);
        resetMargin.setMargins(tk.dp(16), tk.dp(8), tk.dp(16), tk.dp(4));
        upper.addView(reset, resetMargin);
        tk.section(top, "All apps");
        EditText search = tk.searchField("Search apps");
        LinearLayout.LayoutParams searchMargin = tk.fieldMargin();
        searchMargin.setMargins(tk.dp(12), tk.dp(4), tk.dp(12), tk.dp(4));
        top.addView(search, searchMargin);
        page.addHeaderView(top, null, false);

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return filtered.size();
            }

            @Override
            public Object getItem(int position) {
                return filtered.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                MixRow row = convert == null ? new MixRow(volumes, dragging, mute) : (MixRow) convert.getTag();
                Voice v = filtered.get(position);
                row.bind(v, tk.state(v.uid, v.work, v.pkg, playing, open), volumes.getOrDefault(v.uid, 100));
                return row.view;
            }
        };
        page.setAdapter(adapter);

        Runnable filter = () -> {
            String q = search.getText().toString().toLowerCase(Locale.ROOT);
            filtered.clear();
            for (Voice v : all) {
                if (v.label.toLowerCase(Locale.ROOT).contains(q) || (v.pkg != null && v.pkg.toLowerCase(Locale.ROOT).contains(q))) {
                    filtered.add(v);
                }
            }
            adapter.notifyDataSetChanged();
        };
        // Searching hides the open apps above, so the matches sit right under the box, not under the keyboard.
        search.addTextChangedListener(tk.watcher(() -> {
            upper.setVisibility(search.length() > 0 ? View.GONE : View.VISIBLE);
            filter.run();
        }));

        // The "open now" part: playing apps first, then the rest of the open ones, most recent first.
        Runnable drawOpen = () -> {
            openBox.removeAllViews();
            List<Integer> ids = new ArrayList<>(playing);
            for (int uid : open) if (!ids.contains(uid) && launchable.contains(uid) && uid % 100000 >= 10000) ids.add(uid);
            for (int uid : ids) {
                MixRow row = new MixRow(volumes, dragging, mute);
                Voice v = voice(uid);
                row.bind(v, tk.state(uid, v.work, null, playing, open), volumes.getOrDefault(uid, 100));
                openBox.addView(row.view);
            }
            empty.setVisibility(ids.isEmpty() ? View.VISIBLE : View.GONE);
            reset.setVisibility(volumes.isEmpty() ? View.GONE : View.VISIBLE);
            shown[0] = playing + "|" + open;
        };

        class Loop implements Runnable {
            final int g;

            Loop(int g) {
                this.g = g;
            }

            @Override
            public void run() {
                if (g != gen[0] || !tk.alive() || !page.isAttachedToWindow()) return;
                tk.main.postDelayed(this, 1500);
                if (page.getWindowVisibility() != View.VISIBLE) return;
                if (!masterDragging[0]) {
                    masterBar.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
                }
                if (polling[0] || !Shell.ready()) return;
                polling[0] = true;
                tk.reads.execute(() -> {
                    Mixer.State state = Mixer.poll(tk);
                    // It died (Shizuku stopped, say): start it again once, so the volumes hold.
                    String error = !state.alive && wasAlive[0] && !mute[0] ? Mixer.apply(tk, tk.prefs) : null;
                    tk.main.post(() -> {
                        polling[0] = false;
                        if (g != gen[0]) return;
                        wasAlive[0] = state.alive;
                        if (error != null) {
                            problem.setText(Tr.t(error));
                            problem.setVisibility(View.VISIBLE);
                        }
                        if ((state.alive || mute[0]) && !dragging[0] && !(state.playing + "|" + state.open).equals(shown[0])) {
                            playing.clear();
                            playing.addAll(state.playing);
                            open.clear();
                            open.addAll(state.open);
                            drawOpen.run();
                            adapter.notifyDataSetChanged(); // "Playing now" and "Open" in the list below
                        }
                    });
                });
            }
        }

        // Runs whenever the page comes into view: reads what is saved, starts the helper, and watches it.
        page.setTag((Runnable) () -> {
            int g = ++gen[0];
            volumes.clear();
            volumes.putAll(Mixer.rules(tk.prefs));
            playing.clear();
            open.clear();
            drawOpen.run();
            adapter.notifyDataSetChanged();
            tk.worker.execute(() -> {
                List<Voice> found = mixerApps();
                // Anything lowered earlier stays reachable, even an app that has no launcher icon any more.
                Set<Integer> have = new HashSet<>();
                for (Voice v : found) have.add(v.uid);
                for (int uid : volumes.keySet()) if (have.add(uid)) found.add(voice(uid));
                Collections.sort(found, (x, y) -> volumes.containsKey(x.uid) != volumes.containsKey(y.uid)
                        ? (volumes.containsKey(x.uid) ? -1 : 1) : x.label.compareToIgnoreCase(y.label) != 0
                        ? x.label.compareToIgnoreCase(y.label) : Boolean.compare(x.work, y.work));
                tk.main.post(() -> {
                    if (g != gen[0]) return;
                    all.clear();
                    all.addAll(found);
                    launchable.clear();
                    for (Voice v : found) launchable.add(v.uid);
                    filter.run();
                });
            });
            if (!Shell.ready()) {
                problem.setText(Tr.t("Start Shizuku first. It is what lets TinyKit change one app's volume."));
                problem.setVisibility(View.VISIBLE);
                return;
            }
            problem.setVisibility(View.GONE);
            tk.worker.execute(() -> {
                boolean scale = Mixer.scaling(tk);
                String error = Mixer.apply(tk, tk.prefs);
                tk.main.post(() -> {
                    if (g != gen[0]) return;
                    if (mute[0] == scale) { // the first guess was wrong: Shizuku can turn apps down here
                        mute[0] = !scale;
                        mixNote.setText(Tr.t(mute[0] ? NOTE_MUTE : NOTE_SCALE));
                        drawOpen.run();
                        adapter.notifyDataSetChanged();
                    }
                    wasAlive[0] = error == null;
                    if (error != null) {
                        problem.setText(Tr.t(error));
                        problem.setVisibility(View.VISIBLE);
                    } else {
                        new Loop(g).run();
                    }
                });
            });
        });
        tk.push("Audio mixer", page);
    }

    /** Every app with a launcher icon, in this profile and in the work profile. */
    List<Voice> mixerApps() {
        PackageManager pm = tk.getPackageManager();
        List<Voice> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(launcher, 0)) {
            ApplicationInfo info = r.activityInfo.applicationInfo;
            if (info.packageName.equals(tk.getPackageName()) || !seen.add(info.packageName)) continue;
            Voice v = new Voice();
            v.uid = info.uid;
            v.pkg = info.packageName;
            v.label = String.valueOf(info.loadLabel(pm));
            out.add(v);
        }
        int work = Work.id(tk);
        if (work >= 0) {
            for (Work.App a : Work.apps(tk, work)) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(a.pkg, 0);
                    Voice v = new Voice();
                    v.uid = work * 100000 + info.uid % 100000;
                    v.pkg = a.pkg;
                    v.label = String.valueOf(info.loadLabel(pm));
                    v.work = true;
                    out.add(v);
                } catch (PackageManager.NameNotFoundException e) {
                    // only in the work profile: no label or icon to show
                }
            }
        }
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label) != 0
                ? a.label.compareToIgnoreCase(b.label) : Boolean.compare(a.work, b.work));
        return out;
    }
}
