package com.jonathancruzate.metamusedeviceassistance;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/**
 * PluginRegistry — the host's registry of Device Assistance
 * plugins (v1.31.0). Plugins are separate apps that register a
 * command namespace with this host (buds, duo); the host routes by
 * namespace and never interprets plugin semantics.
 *
 * Standing plugin rules (Jonathan, 2026-11-22):
 *  - A plugin carries NO launcher activity and never appears in
 *    the app drawer (Galaxy Wearable style).
 *  - Plugins are managed from INSIDE this app: MainActivity's
 *    Plugins section (rendered from this registry) shows each
 *    plugin's installed state + version, an Open action into the
 *    plugin's settings/status activity (explicit component
 *    intent; the activity is exported for the host but is not a
 *    launcher activity), and an Uninstall action firing the
 *    system uninstall flow for the plugin package.
 */
public final class PluginRegistry {
    private PluginRegistry() {}

    public static final class Plugin {
        public final String namespace;
        public final String label;
        public final String pkg;
        public final String settingsActivity;
        public final String blurb;

        Plugin(String namespace, String label, String pkg,
               String settingsActivity, String blurb) {
            this.namespace = namespace;
            this.label = label;
            this.pkg = pkg;
            this.settingsActivity = settingsActivity;
            this.blurb = blurb;
        }
    }

    public static final Plugin[] ALL = {
            new Plugin("duo", "Muse Duo Companion",
                    DuoRouter.PLUGIN_PKG,
                    "com.jonathancruzate.metamuseduo.MainActivity",
                    "Surface Duo wake ritual, Duo debug mode, posture, wake pair."),
            new Plugin("buds", "Muse Galaxy Buds",
                    BudsRouter.PLUGIN_PKG,
                    "com.jonathancruzate.metamusebuds.MainActivity",
                    "Galaxy Buds control over Samsung's SPP protocol."),
    };

    /** "1.0.0 (code 1)" or null when not installed. */
    public static String installedVersion(Context ctx, Plugin p) {
        try {
            android.content.pm.PackageInfo pi =
                    ctx.getPackageManager().getPackageInfo(p.pkg, 0);
            return pi.versionName + " (code " + pi.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Open the plugin's settings/status screen. Returns false
     *  when the plugin (or its screen) is not there. */
    public static boolean openSettings(Context ctx, Plugin p) {
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(p.pkg, p.settingsActivity));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Fire the system uninstall flow for the plugin package.
     *  The system owns the confirmation; nothing is removed by
     *  this call itself. */
    public static boolean requestUninstall(Context ctx, Plugin p) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + p.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
