package io.github.order52.tinykit;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Secure Folder-style front door to the work profile, with its own icon: after the phone's lock screen it shows
 * only the work apps, as a grid. A tap unfreezes the app if it was frozen and opens it in the profile, so the
 * apps can stay out of the launcher the rest of the time. The icon is switched on while a work profile exists.
 */
public final class SecureFolder extends Activity {
    static final String PREF_LOCK = "secure_lock", PREF_ICON = "secure_icon", PREF_HIDE = "secure_hide";
    private static final int REQUEST_LOCK = 1;

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Work.App> apps = new ArrayList<>();
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private BaseAdapter adapter;
    private TextView status;
    private boolean unlocked;
    private int profile = -1;

    /** Shows or hides the home screen icon (it exists only while there is a work profile and the person wants it). */
    static void icon(Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, SecureFolder.class),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    @Override
    protected void onCreate(Bundle state) {
        prefs = getSharedPreferences("tinykit", MODE_PRIVATE);
        if (prefs.getBoolean("light_theme", false)) setTheme(R.style.AppTheme_Light);
        super.onCreate(state);
        setTitle(Tr.t("Secure folder"));
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!unlocked) {
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            Intent confirm = prefs.getBoolean(PREF_LOCK, true) && km.isDeviceSecure()
                    ? km.createConfirmDeviceCredentialIntent(Tr.t("Secure folder"), null) : null;
            if (confirm != null) {
                startActivityForResult(confirm, REQUEST_LOCK);
                return;
            }
            unlocked = true;
        }
        load();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOCK) return;
        if (resultCode == RESULT_OK) unlocked = true; else finish();
    }

    /** Leaving it locks it again, like Samsung's. */
    @Override
    protected void onStop() {
        super.onStop();
        unlocked = false;
        if (!isChangingConfigurations()) apps.clear();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setPadding(dp(20), dp(16), dp(20), dp(8));
        root.addView(status);
        GridView grid = new GridView(this);
        grid.setNumColumns(3);
        grid.setVerticalSpacing(dp(12));
        grid.setPadding(dp(8), dp(8), dp(8), dp(8));
        adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size();
            }

            @Override
            public Object getItem(int position) {
                return apps.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convert, ViewGroup parent) {
                LinearLayout cell = new LinearLayout(SecureFolder.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(4), dp(8), dp(4), dp(8));
                Work.App app = apps.get(position);
                ImageView icon = new ImageView(SecureFolder.this);
                icon.setImageDrawable(icons.get(app.pkg));
                cell.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(56)));
                TextView label = new TextView(SecureFolder.this);
                label.setText(labels.get(app.pkg));
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                label.setGravity(Gravity.CENTER);
                label.setMaxLines(2);
                label.setPadding(0, dp(4), 0, 0);
                cell.addView(label);
                cell.setAlpha(app.frozen ? 0.55f : 1f); // frozen ones are out of the launcher: a tap brings them back
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, position, id) -> open(apps.get(position)));
        grid.setOnItemLongClickListener((p, v, position, id) -> {
            Work.App app = apps.get(position);
            new AlertDialog.Builder(this).setTitle(labels.get(app.pkg))
                    .setItems(Tr.all(new String[]{app.frozen ? "Unfreeze" : "Freeze (hide from the launcher)"}), (d, which) ->
                            new Thread(() -> {
                                Work.freeze(profile, app.pkg, !app.frozen);
                                main.post(this::load);
                            }).start())
                    .setNegativeButton(Tr.t("Cancel"), null).show();
            return true;
        });
        root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(12), dp(4), dp(12), dp(12));
        bar.addView(button("Add apps", v -> startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_WORK, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))),
                new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(button("Lock", v -> new Thread(() -> {
            if (profile >= 0) Work.pause(this, profile, true);
            main.post(this::finish);
        }).start()), new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar);
        setContentView(root);
    }

    private Button button(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(Tr.t(label));
        b.setAllCaps(false);
        b.setOnClickListener(click);
        return b;
    }

    private void load() {
        if (!Shell.ready()) {
            status.setText(Tr.t("Start Shizuku first (open TinyKit)."));
            return;
        }
        new Thread(() -> {
            int id = Work.id(this);
            List<Work.App> found = id >= 0 ? Work.apps(this, id) : new ArrayList<>();
            int state = id >= 0 ? Work.state(this, id) : Work.RUNNING;
            PackageManager pm = getPackageManager();
            for (Work.App a : found) {
                try {
                    icons.put(a.pkg, pm.getApplicationIcon(a.pkg));
                    labels.put(a.pkg, String.valueOf(pm.getApplicationInfo(a.pkg, 0).loadLabel(pm)));
                } catch (PackageManager.NameNotFoundException e) {
                    labels.put(a.pkg, a.pkg);
                }
            }
            java.util.Collections.sort(found, (x, y) -> labels.get(x.pkg).compareToIgnoreCase(labels.get(y.pkg)));
            main.post(() -> {
                profile = id;
                apps.clear();
                apps.addAll(found);
                adapter.notifyDataSetChanged();
                status.setText(id < 0 ? Tr.t("There is no work profile yet. Add apps in TinyKit.")
                        : Tr.t(state == Work.PAUSED ? "Paused" : state == Work.LOCKED ? "Locked" : "Unlocked")
                        + " · " + found.size() + " " + Tr.t(found.size() == 1 ? "app" : "apps"));
            });
        }, "secure-folder").start();
    }

    private void open(Work.App app) {
        Context c = getApplicationContext();
        new Thread(() -> {
            String error = Work.launch(c, app.pkg);
            if (error != null) main.post(() -> Toast.makeText(c, Tr.t(error), Toast.LENGTH_LONG).show());
            else main.postDelayed(this::load, 1500);
        }).start();
    }
}
