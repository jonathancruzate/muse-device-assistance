package com.jonathancruzate.metamusedeviceassistance;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * PluginVoiceService — the host's generic plugin voice endpoint
 * (v1.31.0). A family plugin (today: Muse Duo Companion) asks the
 * core to run ONE voice activation: the accessibility machinery
 * stays here, the decision lives in the plugin.
 *
 * Guarded by the signature-level permission
 * com.jonathancruzate.metamusedeviceassistance.permission.PLUGIN_VOICE
 * (manifest): only apps signed with the Muse family cert can
 * start this service at all. That is the platform-level answer to
 * the spoofing question: there is no open endpoint.
 *
 * Extras: {payload (the typed prime), no_pip_return (bool),
 * nonce, reply PendingIntent}. The reply carries
 * {nonce, ok, out, error} — dispatch is the result; the session
 * itself connects asynchronously under the v1.16/v1.17 hold
 * discipline inside VoiceBridge.
 */
public class PluginVoiceService extends Service {

    public static final String ACTION_PLUGIN_VOICE =
            "com.jonathancruzate.metamusedeviceassistance.action.PLUGIN_VOICE";

    @Override
    public int onStartCommand(Intent intent, int flags, final int startId) {
        if (intent == null || !ACTION_PLUGIN_VOICE.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final String payload = intent.getStringExtra("payload");
        final boolean noPipReturn = intent.getBooleanExtra("no_pip_return", false);
        final String nonce = intent.getStringExtra("nonce");
        final PendingIntent reply = intent.getParcelableExtra("reply");
        new Thread(() -> {
            LoopbackAdb.Result r = VoiceBridge.executePluginVoiceRequest(
                    getApplicationContext(), payload, noPipReturn);
            if (reply != null) {
                Intent fill = new Intent();
                fill.putExtra("nonce", nonce);
                fill.putExtra("ok", r.ok);
                if (r.stdoutTail != null) fill.putExtra("out", r.stdoutTail);
                if (r.error != null) fill.putExtra("error", r.error);
                try {
                    reply.send(getApplicationContext(), 0, fill);
                } catch (Exception ignored) {}
            }
            stopSelf(startId);
        }, "plugin-voice").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
