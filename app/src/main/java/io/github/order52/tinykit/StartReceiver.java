package io.github.order52.tinykit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart or a TinyKit update, starts {@link NewAppService} again if the internet block needs it. */
public final class StartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        NewAppService.sync(context);
    }
}
