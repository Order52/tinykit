package io.github.order52.tinykit;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
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
    // Kept for as long as the process lives, so the next opening has the grid before the shell has answered.
    private static final Map<String, Drawable> icons = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> labels = new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile List<Work.App> lastApps;
    private static volatile int lastProfile = -1, lastState = Work.RUNNING;
    private View content;
    private boolean loading;
    /** The app being dragged to a new place in the grid, and whether it has moved yet. */
    private String dragging;
    private boolean dragMoved;
    static final String PREF_ADDED = "folder_added", PREF_ORDER = "folder_order";
    private BaseAdapter adapter;
    private TextView status;
    private boolean unlocked;
    /** Opened through the work folder alias: the same grid, with no lock. */
    private boolean plain;
    private int profile = -1;

    static final String PREF_MODE = "work_mode", PREF_EXISTS = "work_exists";

    /** Which look the person chose when making the profile (or since): the Samsung-style one, or the plain work profile. */
    static boolean secure(SharedPreferences p) {
        String mode = p.getString(PREF_MODE, null);
        // A profile made before the choice existed has no mode: it got the icon unless that was switched off.
        return mode != null ? mode.equals("secure") : p.getBoolean(PREF_ICON, true);
    }

    /** Whether a work profile exists, asked of Android itself (no shell needed), else what was seen last. */
    static boolean exists(Context c, SharedPreferences p) {
        try {
            return ((android.os.UserManager) c.getSystemService(Context.USER_SERVICE)).getUserProfiles().size() > 1;
        } catch (RuntimeException e) {
            return p.getBoolean(PREF_EXISTS, false);
        }
    }

    /** The icon is there only in the Secure folder look, with a work profile, unless switched off. Cheap: call it any time. */
    static void sync(Context c, SharedPreferences p) {
        boolean exists = exists(c, p);
        icon(c, secure(p) && p.getBoolean(PREF_ICON, true) && exists);
        folder(c, !secure(p) && exists);
    }

    /** How opening the folder is guarded: "off", "device" (the phone's lock screen) or "password" (TinyFolder's own). */
    static final String PREF_LOCK_MODE = "folder_lock", PREF_PASSWORD = "folder_pw";

    static String lockMode(SharedPreferences p) {
        String mode = p.getString(PREF_LOCK_MODE, null);
        if (mode != null) return mode;
        return secure(p) && p.getBoolean(PREF_LOCK, true) ? "device" : "off"; // before the choice: the Secure folder asked, the plain one didn't
    }

    static boolean hasPassword(SharedPreferences p) {
        return p.getString(PREF_PASSWORD, "").contains(":");
    }

    /** Keeps only a salted hash of the password (many rounds, so guessing is slow), never the password. */
    static void setPassword(SharedPreferences p, String password) {
        byte[] salt = new byte[16];
        new java.security.SecureRandom().nextBytes(salt);
        String s = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP);
        p.edit().putString(PREF_PASSWORD, s + ":" + hash(salt, password)).apply();
    }

    static boolean checkPassword(SharedPreferences p, String password) {
        String saved = p.getString(PREF_PASSWORD, "");
        int at = saved.indexOf(':');
        if (at < 0) return false;
        byte[] salt = android.util.Base64.decode(saved.substring(0, at), android.util.Base64.NO_WRAP);
        return java.security.MessageDigest.isEqual(hash(salt, password).getBytes(), saved.substring(at + 1).getBytes());
    }

    private static String hash(byte[] salt, String password) {
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            byte[] out = sha.digest(concat(salt, password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            for (int i = 0; i < 50_000; i++) out = sha.digest(concat(out, salt));
            return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static final String WORK_FOLDER = "io.github.order52.tinykit.WorkFolder";

    /** The plain work profile's folder: its own launcher entry for the same grid, with no lock. */
    static void folder(Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c.getPackageName(), WORK_FOLDER),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                ShortcutManager manager = c.getSystemService(ShortcutManager.class);
                if (manager == null) return;
                java.util.List<String> ids = java.util.Collections.singletonList("work_folder");
                if (on) {
                    manager.enableShortcuts(ids);
                } else {
                    if (Build.VERSION.SDK_INT >= 30) manager.removeLongLivedShortcuts(ids);
                    manager.disableShortcuts(ids, Tr.t("The work folder is off. Touch and hold this icon, then Remove."));
                }
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * Asks the launcher to put the Secure folder on the home screen. Some launchers (Huawei's without an app drawer, for one)
     * don't add an icon by themselves when an app gets a new one. Returns null when the launcher was asked, else why not.
     */
    static String pin(Context c) {
        return pin(c, false);
    }

    /** {@code plain}: the work folder of the plain work profile, else the Secure folder. */
    static String pin(Context c, boolean plain) {
        if (Build.VERSION.SDK_INT < 26) return "Needs Android 8 or newer.";
        try {
            ShortcutManager manager = c.getSystemService(ShortcutManager.class);
            if (manager == null || !manager.isRequestPinShortcutSupported()) return "Your launcher can't add shortcuts.";
            String id = plain ? "work_folder" : "secure_folder";
            manager.enableShortcuts(java.util.Collections.singletonList(id));
            Intent open = plain ? new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(c.getPackageName(), WORK_FOLDER))
                    : new Intent(c, SecureFolder.class).setAction(Intent.ACTION_MAIN);
            ShortcutInfo info = new ShortcutInfo.Builder(c, id)
                    .setShortLabel(c.getString(R.string.tiny_folder))
                    .setIcon(Icon.createWithResource(c, plain ? R.mipmap.ic_work_folder : R.mipmap.ic_secure_folder))
                    .setIntent(open)
                    .setLongLived(true)
                    .build();
            return manager.requestPinShortcut(info, null) ? null : "Your launcher didn't add it.";
        } catch (RuntimeException e) {
            return "Your launcher didn't add it.";
        }
    }

    /** Whether a Secure folder shortcut is still on the home screen (only the person can take it off). */
    static boolean onHomeScreen(Context c) {
        if (Build.VERSION.SDK_INT < 26) return false;
        try {
            ShortcutManager manager = c.getSystemService(ShortcutManager.class);
            if (manager != null) for (ShortcutInfo i : manager.getPinnedShortcuts()) if ("secure_folder".equals(i.getId())) return true;
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Shows or hides the home screen icon (it exists only while there is a work profile and the person wants it). */
    static void icon(Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, SecureFolder.class),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        if (on && Build.VERSION.SDK_INT >= 26) { // a shortcut that was switched off while the look was plain works again
            try {
                ShortcutManager manager = c.getSystemService(ShortcutManager.class);
                if (manager != null) manager.enableShortcuts(java.util.Collections.singletonList("secure_folder"));
            } catch (RuntimeException ignored) {
            }
        }
        if (!on && Build.VERSION.SDK_INT >= 26) { // a shortcut put on the home screen goes too (greyed out), so the look doesn't linger
            try {
                ShortcutManager manager = c.getSystemService(ShortcutManager.class);
                if (manager != null && Build.VERSION.SDK_INT >= 30) { // takes a long-lived shortcut off the home screen too, where the launcher lets it
                    manager.removeLongLivedShortcuts(java.util.Collections.singletonList("secure_folder"));
                }
                if (manager != null) manager.disableShortcuts(java.util.Collections.singletonList("secure_folder"),
                        Tr.t("Secure folder is off. Touch and hold this icon, then Remove."));
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        prefs = getSharedPreferences("tinykit", MODE_PRIVATE);
        if (prefs.getBoolean("light_theme", false)) setTheme(R.style.AppTheme_Light);
        super.onCreate(state);
        plain = getIntent().getComponent() != null && getIntent().getComponent().getClassName().endsWith(".WorkFolder");
        setTitle(Tr.t("TinyFolder"));
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!unlocked) {
            load(); // runs behind the lock screen, so the grid is ready the moment it is passed
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            String mode = lockMode(prefs);
            if ("password".equals(mode) && hasPassword(prefs)) {
                askPassword();
                return;
            }
            Intent confirm = "device".equals(mode) && km.isDeviceSecure()
                    ? km.createConfirmDeviceCredentialIntent(Tr.t("TinyFolder"), null) : null;
            if (confirm != null) {
                startActivityForResult(confirm, REQUEST_LOCK);
                return;
            }
            unlocked = true;
        }
        content.setVisibility(View.VISIBLE);
        load();
    }

    private boolean asking;
    private int wrong;
    private long lockedUntil;

    /** TinyFolder's own password, asked before the grid shows. Cancelling closes the folder. */
    private void askPassword() {
        if (asking) return;
        asking = true;
        android.widget.EditText box = new android.widget.EditText(this);
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.setHint(Tr.t("Password"));
        if (Build.VERSION.SDK_INT >= 26) box.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); // no "save this password?" from a password manager
        LinearLayout holder = new LinearLayout(this);
        holder.setPadding(dp(24), dp(8), dp(24), 0);
        holder.addView(box, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(Tr.t("TinyFolder")).setView(holder)
                .setPositiveButton(Tr.t("OK"), null).setNegativeButton(Tr.t("Cancel"), (d, w) -> finish())
                .setOnCancelListener(d -> finish());
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km.isDeviceSecure()) { // forgot it: the phone's own lock screen opens the folder too
            b.setNeutralButton(Tr.t("Use phone lock screen"), null);
        }
        AlertDialog dialog = b.create();
        dialog.setOnDismissListener(d -> asking = false);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (System.currentTimeMillis() < lockedUntil) {
                Toast.makeText(this, Tr.t("Try again in a few seconds."), Toast.LENGTH_SHORT).show();
            } else if (checkPassword(prefs, box.getText().toString())) {
                wrong = 0;
                unlocked = true;
                content.setVisibility(View.VISIBLE);
                dialog.dismiss();
                load();
            } else {
                box.setText("");
                if (++wrong >= 5) {
                    wrong = 0;
                    lockedUntil = System.currentTimeMillis() + 15_000;
                }
                Toast.makeText(this, Tr.t("Wrong password."), Toast.LENGTH_SHORT).show();
            }
        });
        android.widget.Button neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutral != null) neutral.setOnClickListener(v -> {
            dialog.setOnDismissListener(d -> asking = false);
            dialog.dismiss();
            startActivityForResult(km.createConfirmDeviceCredentialIntent(Tr.t("TinyFolder"), null), REQUEST_LOCK);
        });
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_LOCK) return;
        if (resultCode == RESULT_OK) {
            unlocked = true;
            content.setVisibility(View.VISIBLE);
        } else {
            finish();
        }
    }

    /** Leaving it locks it again, like Samsung's. */
    @Override
    protected void onStop() {
        super.onStop();
        unlocked = false;
        content.setVisibility(View.INVISIBLE);
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
                cell.setAlpha(app.pkg.equals(dragging) ? 0.25f : app.frozen ? 0.55f : 1f); // frozen ones are out of the launcher: a tap brings them back
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, position, id) -> open(apps.get(position)));
        // Holding an app picks it up: drag it to where it should be. Letting go without moving it opens its menu.
        grid.setOnItemLongClickListener((p, v, position, id) -> {
            dragging = apps.get(position).pkg;
            dragMoved = false;
            v.startDragAndDrop(android.content.ClipData.newPlainText("app", dragging), new View.DragShadowBuilder(v), null, 0);
            adapter.notifyDataSetChanged();
            return true;
        });
        grid.setOnDragListener((v, e) -> {
            switch (e.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    return dragging != null;
                case android.view.DragEvent.ACTION_DRAG_LOCATION:
                    int to = grid.pointToPosition((int) e.getX(), (int) e.getY());
                    int from = -1;
                    for (int i = 0; i < apps.size(); i++) if (apps.get(i).pkg.equals(dragging)) from = i;
                    if (to >= 0 && from >= 0 && to != from) {
                        apps.add(to, apps.remove(from));
                        dragMoved = true;
                        adapter.notifyDataSetChanged();
                    }
                    return true;
                case android.view.DragEvent.ACTION_DRAG_ENDED:
                    String done = dragging;
                    dragging = null;
                    adapter.notifyDataSetChanged();
                    if (done != null && dragMoved) saveOrder();
                    else if (done != null) actions(done);
                    return true;
                default:
                    return true;
            }
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
        content = root;
        content.setVisibility(View.INVISIBLE); // nothing shows until the lock screen has been passed (and not in the recents)
        if (lastApps == null) readSaved();
        List<Work.App> cached = lastApps;
        if (cached != null) {
            profile = lastProfile;
            apps.addAll(cached);
            showStatus(lastProfile, lastState, cached.size());
            // The icons and names for what was there last time, while the shell is still being asked.
            new Thread(() -> {
                fill(cached);
                main.post(adapter::notifyDataSetChanged);
            }, "folder-icons").start();
        }
        setContentView(root);
    }

    /** What the grid held when it was last open, kept across restarts so even the first opening has something at once. */
    private void readSaved() {
        String saved = prefs.getString("folder_list", "");
        if (saved.isEmpty()) return;
        List<Work.App> list = new ArrayList<>();
        for (String line : saved.split("\n")) {
            String[] f = line.split("\\|");
            if (f.length == 3) list.add(new Work.App(f[0], f[1], "1".equals(f[2])));
        }
        lastProfile = prefs.getInt("folder_profile", -1);
        lastState = prefs.getInt("folder_state", Work.RUNNING);
        lastApps = list;
    }

    private void save(List<Work.App> list, int id, int state) {
        StringBuilder out = new StringBuilder();
        for (Work.App a : list) out.append(a.pkg).append('|').append(a.component).append('|').append(a.frozen ? '1' : '0').append('\n');
        prefs.edit().putString("folder_list", out.toString()).putInt("folder_profile", id).putInt("folder_state", state).apply();
    }

    /** Looks up the icon and name of each app once, and sorts the list by name. */
    private void fill(List<Work.App> list) {
        PackageManager pm = getPackageManager();
        for (Work.App a : list) {
            if (icons.containsKey(a.pkg)) continue;
            try {
                icons.put(a.pkg, pm.getApplicationIcon(a.pkg));
                labels.put(a.pkg, String.valueOf(pm.getApplicationInfo(a.pkg, 0).loadLabel(pm)));
            } catch (PackageManager.NameNotFoundException e) {
                labels.put(a.pkg, a.pkg);
            }
        }
        List<String> order = java.util.Arrays.asList(prefs.getString(PREF_ORDER, "").split("\n"));
        java.util.Collections.sort(list, (x, y) -> {
            int i = order.indexOf(x.pkg), j = order.indexOf(y.pkg);
            if (i >= 0 && j >= 0) return Integer.compare(i, j);
            if (i >= 0) return -1; // the ones put in place first
            if (j >= 0) return 1;
            return labels.get(x.pkg).compareToIgnoreCase(labels.get(y.pkg));
        });
    }

    private void showStatus(int id, int state, int count) {
        status.setText(id < 0 ? Tr.t("There is no work profile yet. Add apps in TinyKit.")
                : count == 0 ? Tr.t("Nothing here yet. Tap Add apps.")
                : Tr.t(state == Work.PAUSED ? "Paused" : state == Work.LOCKED ? "Locked" : "Unlocked")
                + " · " + count + " " + Tr.t(count == 1 ? "app" : "apps"));
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
        if (loading) return;
        loading = true;
        new Thread(() -> {
            long began = android.os.SystemClock.elapsedRealtime();
            int id = Work.id(this);
            List<Work.App> all = id >= 0 ? Work.apps(this, id) : new ArrayList<>();
            List<Work.App> found = new ArrayList<>();
            PackageManager pm0 = getPackageManager();
            java.util.Set<String> added = prefs.getStringSet(PREF_ADDED, java.util.Collections.emptySet());
            for (Work.App a : all) if (wanted(pm0, a.pkg, added)) found.add(a);
            int state = id >= 0 ? Work.state(this, id) : Work.RUNNING;
            fill(found);
            save(found, id, state);
            lastApps = found;
            lastProfile = id;
            lastState = state;
            android.util.Log.i("TinyKit", "folder: loaded " + found.size() + " apps in "
                    + (android.os.SystemClock.elapsedRealtime() - began) + " ms");
            main.post(() -> {
                loading = false;
                profile = id;
                apps.clear();
                apps.addAll(found);
                adapter.notifyDataSetChanged();
                showStatus(id, state, found.size());
            });
        }, "secure-folder").start();
    }

    private void saveOrder() {
        StringBuilder out = new StringBuilder();
        for (Work.App a : apps) out.append(a.pkg).append('\n');
        prefs.edit().putString(PREF_ORDER, out.toString()).apply();
        save(new ArrayList<>(apps), profile, lastState);
        lastApps = new ArrayList<>(apps);
    }

    /** What holding an app without moving it offers. */
    private void actions(String pkg) {
        Work.App app = null;
        for (Work.App a : apps) if (a.pkg.equals(pkg)) app = a;
        if (app == null) return;
        final Work.App target = app;
        new AlertDialog.Builder(this).setTitle(labels.get(pkg))
                .setItems(Tr.all(new String[]{target.frozen ? "Unfreeze" : "Freeze (hide from the launcher)"}), (d, which) ->
                        new Thread(() -> {
                            Work.freeze(profile, pkg, !target.frozen);
                            main.post(this::load);
                        }).start())
                .setNegativeButton(Tr.t("Cancel"), null).show();
    }

    /** Only what was put in the work profile (added from TinyKit, or installed there), not the phone's own apps it starts with. */
    private boolean wanted(PackageManager pm, String pkg, java.util.Set<String> added) {
        if (added.contains(pkg)) return true;
        try {
            int flags = pm.getApplicationInfo(pkg, 0).flags;
            return (flags & (android.content.pm.ApplicationInfo.FLAG_SYSTEM | android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return true; // installed in the work profile alone
        }
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
