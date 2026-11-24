package com.jonathancruzate.metamusedeviceassistance;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import org.json.JSONObject;

/**
 * RoutineFirer (v1.35.0) — command-driven generic Samsung Routine
 * firing, the MacroDroid universal-macro replacement.
 *
 * Recovered from the pre-v1.23 RoutineFirer (repo
 * jonathancruzate/muse-phone-bridge @ 8cd7445, v1.3.x) and
 * generalized from four house modes to a fixed registry of keys.
 * The v1.23 removal (docs/v1.23-actuation-removal-2026-11-13.md)
 * deleted the old module because Actuation auto-fired on
 * desired-poll drift and double-fired against mode-sync.sh. This
 * revival carries NONE of that machinery: there is no reconciler
 * hook, no desired-poll check, no arm switch, and no automatic
 * firing of any kind. A routine fires ONLY when an explicit
 * `routine_fire <key>` relay command passes the ShellAllowlist
 * native gate and lands here, or when Jonathan taps a link button
 * in MainActivity (linking only, never firing).
 *
 * Samsung signature-gates the direct Modes/Routines APIs, so
 * firing is capture-based, exactly as before: linking runs
 * Samsung's own CREATE_SHORTCUT picker flow once per key and
 * stores the returned shortcut intent (URI form) plus its display
 * name in on-device prefs; firing replays exactly that intent.
 * The app never hand-builds a routine intent.
 *
 * Phone profile only: ShellBridge/NativeCommands reach this class
 * through the native gate, WorkerClient attaches the snapshot
 * only on the phone profile, and MainActivity renders the link
 * section only on the phone profile.
 */
public final class RoutineFirer {

    public static final String ROUTINES_PACKAGE = "com.samsung.android.app.routines";

    /** The fixed registry. Order is the MainActivity link-section
     *  order and the report's linked-object order. */
    public static final String[] KEYS = {
            "sleep", "home", "away", "morning", "theater", "driving",
            "exercise", "work", "relax", "before_bed", "commute_home",
            "commute_work", "meeting", "power_standard", "power_max",
            "status_full",
    };

    /** Human labels for the link buttons. */
    public static String label(String key) {
        switch (key) {
            case "sleep": return "Sleep";
            case "home": return "Home";
            case "away": return "Away";
            case "morning": return "Morning";
            case "theater": return "Theater";
            case "driving": return "Driving";
            case "exercise": return "Exercise";
            case "work": return "Work";
            case "relax": return "Relax";
            case "before_bed": return "Before bed";
            case "commute_home": return "Commute home";
            case "commute_work": return "Commute work";
            case "meeting": return "Meeting";
            case "power_standard": return "Power: standard";
            case "power_max": return "Power: max saving";
            case "status_full": return "Status: full dump";
            default: return key;
        }
    }

    public static boolean isKey(String key) {
        if (key == null) return false;
        for (String k : KEYS) if (k.equals(key)) return true;
        return false;
    }

    public static String linkKey(String key) {
        return isKey(key) ? "rf_link_" + key : null;
    }

    public static String linkNameKey(String key) {
        return isKey(key) ? "rf_link_name_" + key : null;
    }

    private RoutineFirer() {}

    public static boolean isLinked(Context ctx, String key) {
        String k = linkKey(key);
        return k != null && !Store.get(ctx, k, "").isEmpty();
    }

    public static String linkedName(Context ctx, String key) {
        String k = linkNameKey(key);
        return k == null ? "" : Store.get(ctx, k, "");
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

    /**
     * Replay the stored shortcut intent for a registry key.
     * Returns null on success, otherwise a typed error string:
     * not_linked, bal_blocked, routine_app_missing, fire_failed.
     * Records the attempt as the report's last_fire either way.
     */
    public static String fire(Context ctx, String key) {
        long at = System.currentTimeMillis();
        String error;
        String k = linkKey(key);
        String uri = k == null ? "" : Store.get(ctx, k, "");
        if (k == null) {
            error = "unknown_key: " + key;
        } else if (uri.isEmpty()) {
            error = "not_linked: no routine linked for " + key;
            BridgeLog.log(ctx, "ROUTINE", "fire requested for " + key
                    + " but no routine is linked");
        } else {
            try {
                Intent i = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                BridgeLog.log(ctx, "ROUTINE", "fired linked routine for " + key
                        + " (" + linkedName(ctx, key) + ")");
                error = null;
            } catch (ActivityNotFoundException e) {
                error = "routine_app_missing: " + e.getMessage();
                BridgeLog.log(ctx, "ROUTINE", "fire FAILED for " + key
                        + ": ActivityNotFoundException " + e.getMessage());
            } catch (SecurityException e) {
                // Background activity launch denial surfaces as a
                // SecurityException on this build (same typed error
                // family NativeCommands uses for BAL refusals).
                error = "bal_blocked: " + e.getMessage();
                BridgeLog.log(ctx, "ROUTINE", "fire FAILED for " + key
                        + ": SecurityException " + e.getMessage());
            } catch (Exception e) {
                error = "fire_failed: " + e.getClass().getSimpleName()
                        + " " + e.getMessage();
                BridgeLog.log(ctx, "ROUTINE", "fire FAILED for " + key + ": "
                        + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }
        Store.put(ctx, Store.K_RF_LAST_KEY, key == null ? "" : key);
        Store.put(ctx, Store.K_RF_LAST_AT, at);
        Store.put(ctx, Store.K_RF_LAST_RESULT, error == null ? "fired" : error);
        return error;
    }

    /** NativeCommands entry point for the routine_fire op. Never throws. */
    public static LoopbackAdb.Result execute(Context ctx, String key, long t0) {
        if (DeviceProfile.isTv(ctx) || DeviceProfile.isDuo(ctx)) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "profile_gated: routine_fire is phone-only");
        }
        String error = fire(ctx, key);
        if (error != null) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0, error);
        }
        return new LoopbackAdb.Result(true,
                "routine_fire " + key + " -> " + linkedName(ctx, key),
                System.currentTimeMillis() - t0, null);
    }

    /** Report snapshot: linked names per key + the last fire attempt. */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            JSONObject linked = new JSONObject();
            for (String key : KEYS) linked.put(key, linkedName(ctx, key));
            o.put("linked", linked);
            JSONObject last = new JSONObject();
            last.put("key", Store.get(ctx, Store.K_RF_LAST_KEY, ""));
            last.put("at", Store.getLong(ctx, Store.K_RF_LAST_AT, 0));
            last.put("result", Store.get(ctx, Store.K_RF_LAST_RESULT, ""));
            o.put("last_fire", last);
        } catch (Exception ignored) {}
        return o;
    }

    /** MainActivity status block for the link section. */
    public static String uiState(Context ctx) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String key : KEYS) if (isLinked(ctx, key)) n++;
        sb.append("Linked: ").append(n).append(" of ").append(KEYS.length)
                .append(" keys\n");
        String lastKey = Store.get(ctx, Store.K_RF_LAST_KEY, "");
        long lastAt = Store.getLong(ctx, Store.K_RF_LAST_AT, 0);
        if (lastAt == 0) {
            sb.append("Last fire: never\n");
        } else {
            sb.append("Last fire: ").append(lastKey).append(" at ")
                    .append(lastAt).append(" result=")
                    .append(Store.get(ctx, Store.K_RF_LAST_RESULT, ""))
                    .append('\n');
        }
        return sb.toString();
    }
}
