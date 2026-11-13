package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.content.Intent;

/**
 * Context broadcasts to the Muse Launcher Pack (v1.20.0,
 * Launcher Pack design Section 7.3). Explicit, package-scoped
 * intents carrying observed state only (never commands, never
 * coordinates): a house-mode change observed in ground truth.
 * The pack treats these strictly as re-render pokes over its
 * own cached data, because a broadcast sender cannot be
 * authenticated; anything that ACTS rides the cert-verified
 * LocalChannel instead.
 */
public final class ContextBroadcasts {

    static final String PACK_PACKAGE =
            "com.jonathancruzate.metamusewidget";
    static final String ACTION_CONTEXT_MODE =
            "com.jonathancruzate.metamusedeviceassistance.action.CONTEXT_MODE";

    private ContextBroadcasts() {}

    public static void sendModeChanged(Context ctx, String samsungMode) {
        try {
            Intent i = new Intent(ACTION_CONTEXT_MODE);
            i.setPackage(PACK_PACKAGE);
            i.putExtra("mode", samsungMode);
            ctx.sendBroadcast(i);
            BridgeLog.log(ctx, "LOCAL",
                    "context broadcast: mode=" + samsungMode);
        } catch (Exception e) {
            BridgeLog.log(ctx, "LOCAL", "context broadcast failed: "
                    + e.getClass().getSimpleName());
        }
    }
}
