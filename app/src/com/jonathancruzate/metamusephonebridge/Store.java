package com.jonathancruzate.metamusephonebridge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * All bridge state in one SharedPreferences file: worker settings (entered
 * on-device, NEVER hardcoded — no secret ships in this APK), the latest
 * ground truth, the latest desired state, the Away-latch belief, and the
 * reconciler's last verdict.
 */
public final class Store {
    private static final String PREFS = "bridge";

    // Settings
    public static final String K_BASE_URL = "base_url";       // e.g. https://jonathancruzate.com/mode-sync
    public static final String K_SECRET = "secret";           // mode-state poll secret, entered on device
    public static final String K_SERVICE_ON = "service_on";

    // Ground truth (from GroundTruthListener)
    public static final String K_GT_MODE = "gt_mode";         // Samsung mode name, e.g. "Home", "Sleep", "None"
    public static final String K_GT_RUNNING = "gt_running";   // running routines list text
    public static final String K_GT_RAW = "gt_raw";           // raw dump fields, joined
    public static final String K_GT_AT = "gt_at";             // epoch ms of last parsed dump
    public static final String K_GT_PARSE_OK = "gt_parse_ok";

    // Desired state (from the Worker)
    public static final String K_DESIRED_MODE = "desired_mode"; // house mode: home|night|morning|away
    public static final String K_DESIRED_SEQ = "desired_seq";
    public static final String K_DESIRED_AT = "desired_at";

    // ADB bridge (v1.1)
    public static final String K_ADB_ENDPOINT = "adb_endpoint";       // "ip:port" of _adb-tls-connect
    public static final String K_ADB_ENDPOINT_AT = "adb_endpoint_at"; // epoch ms of last endpoint/pairing update
    public static final String K_ADB_ENDPOINTS_JSON = "adb_endpoints_json";
    public static final String K_ADB_PAIRING_OPEN = "adb_pairing_open";
    public static final String K_ADB_HEAL_ON = "adb_heal_on";         // self-heal armed (default true)
    public static final String K_ADB_HEAL_LAST_LOG = "adb_heal_last_log";
    public static final String K_ADB_WIFI_IP = "adb_wifi_ip";

    // Reconciler verdict
    public static final String K_VERDICT = "verdict";         // in_sync|drift|unknown
    public static final String K_PLAN = "plan";               // shadow plan text when drift
    public static final String K_LAST_REPORT_AT = "last_report_at";
    public static final String K_LAST_REPORT_CODE = "last_report_code";

    // Phase 2 actuation (v1.3) — night mode only, OFF unless armed here.
    // A routine is fired through the launch-shortcut intent Samsung itself
    // minted when the routine was linked on-device (CREATE_SHORTCUT flow);
    // the intent URI lives only in these prefs, never in the APK.
    public static final String K_ACT_ARMED = "act_armed";           // default false
    public static final String K_LINK_NIGHT = "link_night";         // intent URI for Switch Sleep
    public static final String K_LINK_HOME = "link_home";
    public static final String K_LINK_MORNING = "link_morning";
    public static final String K_LINK_AWAY = "link_away";
    public static final String K_LINK_NAME_NIGHT = "link_name_night";
    public static final String K_LINK_NAME_HOME = "link_name_home";
    public static final String K_LINK_NAME_MORNING = "link_name_morning";
    public static final String K_LINK_NAME_AWAY = "link_name_away";
    public static final String K_ACT_FIRED_SEQ = "act_fired_seq";   // desired seq already fired for
    public static final String K_ACT_LAST_LOG = "act_last_log";     // throttle for repeated actuation logs

    // Tailscale guardian (v1.3.1)
    public static final String K_TS_DOWN_SINCE = "ts_down_since";   // epoch ms tailnet first seen down (0 = up)
    public static final String K_TS_LAST_LAUNCH = "ts_last_launch"; // epoch ms of last fallback launch attempt

    private Store() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String get(Context ctx, String key, String def) {
        return prefs(ctx).getString(key, def);
    }

    public static long getLong(Context ctx, String key, long def) {
        return prefs(ctx).getLong(key, def);
    }

    public static boolean getBool(Context ctx, String key, boolean def) {
        return prefs(ctx).getBoolean(key, def);
    }

    public static void put(Context ctx, String key, String value) {
        prefs(ctx).edit().putString(key, value).apply();
    }

    public static void put(Context ctx, String key, long value) {
        prefs(ctx).edit().putLong(key, value).apply();
    }

    public static void put(Context ctx, String key, boolean value) {
        prefs(ctx).edit().putBoolean(key, value).apply();
    }

    /** Worker configured = base URL and secret both present. */
    public static boolean workerConfigured(Context ctx) {
        return !get(ctx, K_BASE_URL, "").trim().isEmpty()
                && !get(ctx, K_SECRET, "").trim().isEmpty();
    }
}
