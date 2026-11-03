package com.jonathancruzate.metamusephonebridge;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * The status producer: every field of the phone status set that is plain
 * Android API surface (report §4.4). The two Samsung-flavored fields —
 * current mode and running routines — come from the notification ground
 * truth, not from here. Known partial, stated in the report: Samsung's
 * power-saving TIER (Standard vs Maximum) has no stock API; only the
 * boolean is reported.
 */
public final class StatusProducer {
    private StatusProducer() {}

    public static JSONObject produce(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            Intent batt = ctx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batt != null) {
                int level = batt.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batt.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = batt.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (level >= 0 && scale > 0) o.put("battery_pct", level * 100 / scale);
                o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL);
            }

            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                o.put("power_save", pm.isPowerSaveMode());
                o.put("screen_on", pm.isInteractive());
                o.put("battery_unrestricted", pm.isIgnoringBatteryOptimizations(ctx.getPackageName()));
            }

            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                String ringer;
                switch (am.getRingerMode()) {
                    case AudioManager.RINGER_MODE_SILENT: ringer = "silent"; break;
                    case AudioManager.RINGER_MODE_VIBRATE: ringer = "vibrate"; break;
                    default: ringer = "sound";
                }
                o.put("ringer", ringer);
            }

            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
                String net = "none";
                if (caps != null) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) net = "wifi";
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) net = "cellular";
                    else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) net = "ethernet";
                }
                o.put("network", net);
            }

            File data = Environment.getDataDirectory();
            StatFs stat = new StatFs(data.getPath());
            long totalGb = stat.getTotalBytes() / (1024L * 1024 * 1024);
            long freeGb = stat.getAvailableBytes() / (1024L * 1024 * 1024);
            o.put("storage_total_gb", totalGb);
            o.put("storage_free_gb", freeGb);

            o.put("ts", System.currentTimeMillis());
        } catch (JSONException ignored) {
            // org.json in-memory puts do not fail on these types.
        }
        return o;
    }
}
