package com.jonathancruzate.metamusedeviceassistance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BudsResultReceiver — receives Galaxy Buds plugin results sent
 * through the per-command PendingIntent minted by BudsRouter. The
 * PendingIntent carries this app's identity, so the receiver can
 * stay non-exported: only a holder of a live router PendingIntent
 * (i.e. the plugin, mid-command) can deliver here, and the nonce
 * must match an in-flight command.
 */
public class BudsResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        BudsRouter.deliver(
                intent.getStringExtra("nonce"),
                intent.getBooleanExtra("ok", false),
                intent.getStringExtra("out"),
                intent.getStringExtra("error"));
    }
}
