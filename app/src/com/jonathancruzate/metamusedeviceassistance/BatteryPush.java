package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Low Battery push channel (v1.6) — replaces MacroDroid's
 * "Low Battery Alert" macro (battery decreases to 20% → status POST).
 *
 * Runs on the BridgeService 60-second cycle (background thread, so the
 * blocking PushClient.push is safe here). When the push_battery
 * channel is ON:
 *  - a downward crossing of 20% latches (Store.K_PUSH_BATTERY_LATCHED)
 *    and pushes ONE event, source "phone-battery", carrying the same
 *    fields MacroDroid's status POST carried: battery, charging,
 *    wifi_ssid, ringer_mode, screen_on;
 *  - the latch re-arms when battery rises above 25% (hysteresis, so a
 *    level hovering at 20–21% never re-fires).
 * When the channel is OFF nothing is read for push and nothing fires;
 * the latch is left as-is so a later ON does not dump a stale crossing.
 * TV profile: gated off (PushChannels.isOn handles it).
 */
public final class BatteryPush {
    static final int CROSS_PCT = 20;
    static final int REARM_PCT = 25;

    private BatteryPush() {}

    public static void check(Context ctx) {
        if (!PushChannels.isOn(ctx, PushChannels.BATTERY)) return;
        try {
            JSONObject status = StatusProducer.produce(ctx);
            if (!status.has("battery_pct")) return;
            int pct = status.optInt("battery_pct", -1);
            if (pct < 0) return;
            boolean latched = Store.getBool(ctx, Store.K_PUSH_BATTERY_LATCHED, false);
            if (pct > REARM_PCT && latched) {
                Store.put(ctx, Store.K_PUSH_BATTERY_LATCHED, false);
                BridgeLog.log(ctx, "PUSH", "phone-battery latch re-armed at " + pct + "%");
                return;
            }
            if (pct > CROSS_PCT || latched) return;

            JSONObject item = new JSONObject();
            item.put("battery", pct);
            item.put("charging", status.optBoolean("charging", false));
            item.put("wifi_ssid", wifiSsid(ctx));
            item.put("ringer_mode", status.optString("ringer", ""));
            item.put("screen_on", status.optBoolean("screen_on", false));
            JSONArray items = new JSONArray();
            items.put(item);
            // Latch on the attempt: PushClient queues the payload on
            // failure, so the event is not lost; latching here keeps
            // the cycle from queueing a duplicate every minute while
            // the level sits at/below 20%.
            Store.put(ctx, Store.K_PUSH_BATTERY_LATCHED, true);
            BridgeLog.log(ctx, "PUSH", "phone-battery crossing at " + pct + "% — pushing");
            PushClient.push(ctx, PushChannels.BATTERY.source, items);
        } catch (Exception e) {
            BridgeLog.log(ctx, "PUSH", "phone-battery check failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private static String wifiSsid(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return "";
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return "";
            String ssid = info.getSSID();
            if (ssid == null) return "";
            // WifiInfo returns the SSID wrapped in quotes, and
            // "<unknown ssid>" when it is not readable.
            if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            if ("<unknown ssid>".equals(ssid)) return "";
            return ssid;
        } catch (Exception e) {
            return "";
        }
    }
}
