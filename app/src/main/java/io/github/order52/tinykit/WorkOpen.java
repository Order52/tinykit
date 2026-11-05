package io.github.order52.tinykit;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * What a pinned work-app shortcut starts: it has no screen. It unfreezes the app if it is frozen, wakes
 * the profile if it was paused, and opens the app there, all through Shizuku, then goes away.
 */
public final class WorkOpen extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String pkg = getIntent().getStringExtra("pkg");
        Context app = getApplicationContext();
        if (pkg != null) {
            new Thread(() -> {
                String error;
                try {
                    error = !Shell.await() ? "Start Shizuku first." : Work.launch(app, pkg);
                } catch (RuntimeException e) {
                    error = "Couldn't open it.";
                }
                if (error != null) {
                    String shown = error;
                    new Handler(Looper.getMainLooper()).post(
                            () -> Toast.makeText(app, Tr.t(shown), Toast.LENGTH_LONG).show());
                }
            }, "work-open").start();
        }
        finish();
    }
}
