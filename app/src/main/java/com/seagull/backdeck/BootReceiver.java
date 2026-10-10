package com.seagull.backdeck;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Resume an explicitly enabled rear takeover after the user's first post-boot unlock. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            RearTakeoverService.ensureRunning(context);
        }
    }
}
