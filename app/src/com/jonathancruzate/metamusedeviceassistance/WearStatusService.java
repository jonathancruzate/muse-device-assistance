package com.jonathancruzate.metamusedeviceassistance;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * WearStatusService — v1.13: receives Muse Wear Installer's job
 * status messages (PATH_STATUS) from the watch and hands them to
 * WearInstallRouter, which unblocks the waiting command execution
 * and mirrors the stage into the report. Bound only on the phone
 * profile in practice: the watch pairs with the phone, and
 * ShellBridge never runs the wear verb on the TV profile.
 */
public final class WearStatusService extends WearableListenerService {

    @Override
    public void onMessageReceived(MessageEvent event) {
        if (!WearProtocol.PATH_STATUS.equals(event.getPath())) return;
        String json = new String(event.getData(), StandardCharsets.UTF_8);
        Map<String, String> m = WearProtocol.parseFlat(json);
        String jobId = m.get("job_id");
        String stage = m.get("stage");
        if (jobId == null || jobId.isEmpty() || stage == null) return;
        WearInstallRouter.deliver(this, jobId,
                m.getOrDefault("label", ""), stage,
                m.getOrDefault("detail", ""));
    }
}
