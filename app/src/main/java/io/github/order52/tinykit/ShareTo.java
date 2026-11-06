package io.github.order52.tinykit;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pinned apps in the share menu. Android keeps the share sheet's own pins to itself, so TinyKit gives
 * each of the first three pinned apps a sharing shortcut, which the sheet shows in its top row, no
 * scrolling. Tapping one lands here and hands what is being shared straight to that app. Also a plain "Pinned apps" entry
 * with the whole list, for the pins past three and for sheets that hide the top row. Android 10 or newer.
 */
public final class ShareTo extends Activity {
    static final String PREF_PINS = "share_pins";
    static final String CATEGORY = "io.github.order52.tinykit.SHARE";
    static final String ID = "share:";
    static final int MAX = 8;
    /** Android shows at most three shortcuts of one app in the top row. */
    static final int TOP = 3;
    static final String LIMIT = "You can pin up to 8 apps.";

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 29;
    }

    static List<String> pins(SharedPreferences p) {
        List<String> out = new ArrayList<>();
        for (String s : p.getString(PREF_PINS, "").split(",")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** Pins or unpins an app. Returns null when it worked, else why not. Call off the main thread. */
    static String choose(Context c, SharedPreferences p, String pkg, boolean on) {
        if (!supported()) return "Needs Android 10 or newer.";
        List<String> list = pins(p);
        list.remove(pkg);
        if (on) {
            if (list.size() >= MAX) return LIMIT;
            list.add(pkg);
        }
        p.edit().putString(PREF_PINS, TextUtils.join(",", list)).apply();
        return publish(c, list);
    }

    /** Makes the share sheet match the list: a shortcut for each of the first three installed pins, none when empty. */
    static String publish(Context c, List<String> pkgs) {
        if (!supported()) return null;
        try {
            PackageManager pm = c.getPackageManager();
            ShortcutManager sm = c.getSystemService(ShortcutManager.class);
            List<ShortcutInfo> shortcuts = new ArrayList<>();
            for (String pkg : pkgs) {
                CharSequence label;
                Drawable icon;
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                    icon = pm.getApplicationIcon(pkg);
                } catch (PackageManager.NameNotFoundException e) {
                    continue;
                }
                Bitmap bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
                icon.setBounds(0, 0, 192, 192);
                icon.draw(new Canvas(bitmap));
                ShortcutInfo.Builder b = new ShortcutInfo.Builder(c, ID + pkg)
                        .setShortLabel(label)
                        .setIcon(Icon.createWithBitmap(bitmap))
                        .setLongLived(true)
                        .setRank(shortcuts.size())
                        .setCategories(Collections.singleton(CATEGORY))
                        .setIntent(new Intent(c, ShareTo.class).setAction(Intent.ACTION_VIEW).putExtra("pkg", pkg));
                shortcuts.add(b.build());
                if (shortcuts.size() == TOP) break;
            }
            // The entry only exists while something is pinned, so the share sheet is untouched otherwise.
            pm.setComponentEnabledSetting(new ComponentName(c, ShareTo.class), shortcuts.isEmpty()
                            ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            if (sm == null) return "Android didn't take the shortcuts.";
            if (shortcuts.isEmpty()) sm.removeAllDynamicShortcuts();
            else sm.setDynamicShortcuts(shortcuts);
            return null;
        } catch (RuntimeException | OutOfMemoryError e) {
            return "Android didn't take the shortcuts.";
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        String action = in.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            // The same shortcuts also show in the launcher's menu on TinyKit's icon: there they open the app.
            String pkg = in.getStringExtra("pkg");
            Intent open = pkg == null ? null : getPackageManager().getLaunchIntentForPackage(pkg);
            if (open != null) startActivity(open);
            finish();
            return;
        }
        String id = in.getStringExtra(Intent.EXTRA_SHORTCUT_ID);
        if (id != null && id.startsWith(ID)) {
            forward(id.substring(ID.length()));
            return;
        }
        // Picked as a plain entry: ask which pinned app should get it.
        List<String> pkgs = pins(getSharedPreferences("tinykit", MODE_PRIVATE));
        PackageManager pm = getPackageManager();
        List<String> shown = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String pkg : pkgs) {
            try {
                labels.add(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString());
                shown.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (shown.isEmpty()) {
            finish();
            return;
        }
        new AlertDialog.Builder(this, getSharedPreferences("tinykit", MODE_PRIVATE).getBoolean("light_theme", false)
                ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert : android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(Tr.t("Share with"))
                .setItems(labels.toArray(new String[0]), (d, which) -> forward(shown.get(which)))
                .setOnCancelListener(d -> finish()).show();
    }

    /** Sends what was shared to the app, as if it had been picked in the share sheet. */
    private void forward(String pkg) {
        Intent out = new Intent(getIntent());
        out.removeExtra(Intent.EXTRA_SHORTCUT_ID);
        out.setComponent(null);
        out.setPackage(pkg);
        try {
            List<ResolveInfo> found = getPackageManager().queryIntentActivities(out, 0);
            if (found.isEmpty()) {
                Toast.makeText(this, Tr.t("That app can't take this."), Toast.LENGTH_LONG).show();
            } else {
                out.setClassName(pkg, found.get(0).activityInfo.name);
                startActivity(out);
            }
        } catch (RuntimeException e) {
            Toast.makeText(this, Tr.t("That app can't take this."), Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
