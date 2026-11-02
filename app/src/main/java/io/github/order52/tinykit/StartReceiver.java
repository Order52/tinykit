package io.github.order52.tinykit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart or a TinyKit update, starts {@link BackgroundService} again if it is on and a tool needs it. */
public final class StartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        BackgroundService.sync(context);
    }
}
