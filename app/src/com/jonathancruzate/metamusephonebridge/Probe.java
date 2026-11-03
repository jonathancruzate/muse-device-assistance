package com.jonathancruzate.metamusephonebridge;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;

/**
 * Phase 0 probe (investigation report §6): settle on-device, with evidence,
 * the questions the REA investigation could not — the installed Routines
 * version, whether the RoutineInfoProvider door is really closed on this
 * build (expect SecurityException on 5.1.x), whether RoutineLaunchActivity
 * is present and exported, and whether this app holds notification access.
 * Read-only: the probe never fires a routine and never changes a mode.
 */
public final class Probe {
    public static final String ROUTINES_PKG = "com.samsung.android.app.routines";
    public static final String MACRODROID_PKG = "com.arlosoft.macrodroid";

    private Probe() {}

    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = ctx.getPackageManager();

        sb.append("== Phase 0 probe ==\n");
        sb.append("Android: ").append(android.os.Build.VERSION.RELEASE)
          .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(android.os.Build.MANUFACTURER).append(' ')
          .append(android.os.Build.MODEL).append("\n\n");

        sb.append("-- Modes and Routines --\n");
        PackageInfo routines = info(pm, ROUTINES_PKG);
        if (routines == null) {
            sb.append("NOT INSTALLED (").append(ROUTINES_PKG).append(")\n");
        } else {
            sb.append("versionName: ").append(routines.versionName).append('\n');
            sb.append("versionCode: ").append(routines.getLongVersionCode()).append('\n');
        }

        // RoutineInfoProvider read attempt — the one historical side door.
        sb.append("RoutineInfoProvider query: ");
        try {
            Uri uri = Uri.parse("content://" + ROUTINES_PKG + ".routineinfoprovider/routine_info");
            ContentResolver cr = ctx.getContentResolver();
            Cursor c = cr.query(uri, null, null, null, null);
            if (c == null) {
                sb.append("returned null cursor (door closed or empty)\n");
            } else {
                sb.append("OPEN — ").append(c.getCount()).append(" rows, columns: ")
                  .append(java.util.Arrays.toString(c.getColumnNames())).append('\n');
                c.close();
            }
        } catch (SecurityException se) {
            sb.append("SecurityException (door closed on this build, as expected on 5.1.x)\n");
        } catch (Exception e) {
            sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append('\n');
        }

        // RoutineLaunchActivity — the exported launch surface Phase 2 would use.
        sb.append("RoutineLaunchActivity: ");
        try {
            ComponentName cn = new ComponentName(ROUTINES_PKG,
                    ROUTINES_PKG + ".activity.RoutineLaunchActivity");
            android.content.pm.ActivityInfo ai = pm.getActivityInfo(cn, 0);
            sb.append("present, exported=").append(ai.exported).append('\n');
        } catch (Exception e) {
            // Class name may differ by build; try resolving by package scan.
            boolean found = false;
            try {
                PackageInfo full = pm.getPackageInfo(ROUTINES_PKG,
                        PackageManager.GET_ACTIVITIES);
                if (full.activities != null) {
                    for (android.content.pm.ActivityInfo a : full.activities) {
                        if (a.name != null && a.name.contains("RoutineLaunch")) {
                            sb.append("found ").append(a.name)
                              .append(", exported=").append(a.exported).append('\n');
                            found = true;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (!found) sb.append("not found by either lookup\n");
        }

        sb.append("\n-- MacroDroid (stays for its non-mode jobs) --\n");
        PackageInfo md = info(pm, MACRODROID_PKG);
        sb.append(md == null ? "NOT INSTALLED\n"
                : "versionName: " + md.versionName + "\n");

        sb.append("\n-- This app --\n");
        String listeners = Settings.Secure.getString(ctx.getContentResolver(),
                "enabled_notification_listeners");
        boolean hasAccess = listeners != null && listeners.contains(ctx.getPackageName());
        sb.append("Notification listener access: ").append(hasAccess ? "GRANTED" : "NOT granted").append('\n');
        try {
            ApplicationInfo ai = pm.getApplicationInfo(ctx.getPackageName(), 0);
            sb.append("App installed from: ").append(
                    pm.getInstallerPackageName(ctx.getPackageName()) + "\n");
        } catch (Exception ignored) {}

        BridgeLog.log(ctx, "PROBE", sb.toString().replace('\n', ' '));
        return sb.toString();
    }

    private static PackageInfo info(PackageManager pm, String pkg) {
        try {
            return pm.getPackageInfo(pkg, 0);
        } catch (Exception e) {
            return null;
        }
    }
}
