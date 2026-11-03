package com.jonathancruzate.metamusephonebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restart the bridge after reboot if it was left enabled. Force-stop
 *  still kills everything until a manual relaunch — true for MacroDroid
 *  too, by platform design (report §4.6). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (Store.getBool(ctx, Store.K_SERVICE_ON, false)) {
            BridgeLog.log(ctx, "BOOT", "restarting bridge service after boot");
            try {
                BridgeService.start(ctx);
            } catch (Exception e) {
                BridgeLog.log(ctx, "BOOT", "restart failed: " + e.getMessage());
            }
        }
    }
}
