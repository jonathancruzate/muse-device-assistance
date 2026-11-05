package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Fires Samsung routines through the launch-shortcut intents Samsung
 * itself mints. The extras contract of RoutineLaunchActivity was never
 * decompiled (investigation §6.3), so the bridge does NOT hand-build
 * intents: linking runs Samsung's own CREATE_SHORTCUT picker flow once
 * per routine (the same capture MacroDroid's shortcut action performs),
 * stores the returned shortcut intent as a URI string in prefs, and
 * later replays exactly that intent. If the picker flow is unsupported
 * on a future build, linking fails loudly in the log — never silently.
 */
public final class RoutineFirer {

    public static final String ROUTINES_PACKAGE = "com.samsung.android.app.routines";

    private RoutineFirer() {}

    public static String linkKey(String houseMode) {
        switch (houseMode.toLowerCase()) {
            case "night": return Store.K_LINK_NIGHT;
            case "home": return Store.K_LINK_HOME;
            case "morning": return Store.K_LINK_MORNING;
            case "away": return Store.K_LINK_AWAY;
            default: return null;
        }
    }

    public static String linkNameKey(String houseMode) {
        switch (houseMode.toLowerCase()) {
            case "night": return Store.K_LINK_NAME_NIGHT;
            case "home": return Store.K_LINK_NAME_HOME;
            case "morning": return Store.K_LINK_NAME_MORNING;
            case "away": return Store.K_LINK_NAME_AWAY;
            default: return null;
        }
    }

    public static boolean isLinked(Context ctx, String houseMode) {
        String key = linkKey(houseMode);
        return key != null && !Store.get(ctx, key, "").isEmpty();
    }

    public static String linkedName(Context ctx, String houseMode) {
        String key = linkNameKey(houseMode);
        return key == null ? "" : Store.get(ctx, key, "");
    }

    /** The intent that opens Samsung's routine picker for shortcut creation. */
    public static Intent buildLinkIntent() {
        Intent i = new Intent(Intent.ACTION_CREATE_SHORTCUT);
        i.setPackage(ROUTINES_PACKAGE);
        return i;
    }

    public static boolean linkFlowAvailable(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        return pm != null && pm.resolveActivity(buildLinkIntent(), 0) != null;
    }

    /** Replay the stored shortcut intent for a house mode. Loud on failure. */
    public static boolean fire(Context ctx, String houseMode) {
        String key = linkKey(houseMode);
        String uri = key == null ? "" : Store.get(ctx, key, "");
        if (uri.isEmpty()) {
            BridgeLog.log(ctx, "ACT", "fire requested for " + houseMode
                    + " but no routine is linked");
            return false;
        }
        try {
            Intent i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            BridgeLog.log(ctx, "ACT", "fired linked routine for " + houseMode
                    + " (" + linkedName(ctx, houseMode) + ")");
            return true;
        } catch (Exception e) {
            BridgeLog.log(ctx, "ACT", "fire FAILED for " + houseMode + ": "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }
}
