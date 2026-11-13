package com.jonathancruzate.metamusedeviceassistance;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Setup + status screen. Sections: Phase 0 probe, worker settings
 * (secret entered here, on-device only), ground truth vs desired with
 * the reconciler's verdict and shadow plan, service controls, and the
 * on-device log tail.
 */
public class MainActivity extends Activity {
    private TextView stateView;
    private TextView adbView;
    private android.widget.CheckBox healCheck;
    private TextView shellView;
    private android.widget.CheckBox shellCheck;
    private boolean syncingShellCheck;
    private TextView extView;
    private TextView filesView;
    private android.widget.CheckBox voiceCheck;
    private boolean syncingVoiceCheck;
    private TextView pushView;
    private android.widget.CheckBox[] pushChecks;
    private boolean syncingPushCheck;
    private EditText pairAddrField;
    private EditText pairCodeField;
    private TextView lcView;
    private TextView probeView;
    private TextView logView;
    private EditText baseUrlField;
    private EditText secretField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Muse Device Assistance");
        title.setTextSize(22);
        root.addView(title);

        if (DeviceProfile.isTv(this)) {
            BridgeLog.log(this, "PROFILE", "tv profile detected: " + DeviceProfile.describe(this));
            if (!Store.getBool(this, Store.K_SERVICE_ON, false)) {
                BridgeService.start(this);
            }
        }
        TextView sub = new TextView(this);
        sub.setText("Control plane that complements MacroDroid: ground truth, "
                + "verified acks, drift plans. Shadow by design — the bridge "
                + "observes and reports only; it never fires routines.");
        root.addView(sub);

        stateView = new TextView(this);
        stateView.setPadding(0, pad, 0, 0);
        root.addView(stateView);

        root.addView(button("Refresh status", v -> refresh()));

        root.addView(button("Grant notification access", v -> {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(i);
        }));

        root.addView(button("Allow unrestricted battery", v -> {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "Open Battery settings manually for this app",
                        Toast.LENGTH_LONG).show();
            }
        }));

        LinearLayout svcRow = new LinearLayout(this);
        svcRow.setOrientation(LinearLayout.HORIZONTAL);
        Button startBtn = button("Start bridge", v -> {
            BridgeService.start(this);
            toast("Bridge started");
            refreshSoon();
        });
        Button stopBtn = button("Stop bridge", v -> {
            BridgeService.stop(this);
            toast("Bridge stopped");
            refreshSoon();
        });
        svcRow.addView(startBtn);
        svcRow.addView(stopBtn);
        root.addView(svcRow);

        TextView adbLabel = new TextView(this);
        adbLabel.setText("\nADB bridge — endpoint discovery + self-heal");
        adbLabel.setTextSize(16);
        root.addView(adbLabel);

        adbView = new TextView(this);
        adbView.setTextIsSelectable(true);
        root.addView(adbView);

        healCheck = new android.widget.CheckBox(this);
        healCheck.setText("Self-heal: re-enable wireless debugging when it turns off "
                + "(needs the one-time WRITE_SECURE_SETTINGS grant)");
        healCheck.setChecked(Store.getBool(this, Store.K_ADB_HEAL_ON, true));
        healCheck.setOnCheckedChangeListener((v, checked) -> {
            Store.put(this, Store.K_ADB_HEAL_ON, checked);
            BridgeLog.log(this, "ADB_HEAL", "self-heal " + (checked ? "ARMED" : "DISARMED") + " from UI");
            refresh();
        });
        root.addView(healCheck);

        root.addView(button("Rediscover ADB endpoint", v -> {
            if (android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.NEARBY_WIFI_DEVICES}, 41);
                toast("Grant Nearby Wi-Fi, then tap Rediscover again");
                return;
            }
            AdbBridge.startDiscovery(this);
            toast("Discovering…");
            refreshSoon();
        }));

        TextView shellLabel = new TextView(this);
        shellLabel.setText("\nLocal shell bridge (v1.4) — allowlisted commands over loopback ADB");
        shellLabel.setTextSize(16);
        root.addView(shellLabel);

        TextView shellHint = new TextView(this);
        shellHint.setText("One-time pairing: Settings → Developer options → Wireless "
                + "debugging → Pair device with pairing code. Type the address "
                + "(IP:port) and the 6-digit code it shows below, then tap Pair. "
                + "After pairing, the bridge runs the battery self-test and arms "
                + "itself. Only allowlisted commands from the Worker ever run.");
        root.addView(shellHint);

        shellView = new TextView(this);
        shellView.setTextIsSelectable(true);
        root.addView(shellView);

        pairAddrField = new EditText(this);
        pairAddrField.setHint("Pairing address, e.g. 192.168.1.10:37145");
        pairAddrField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        String wifi = AdbBridge.wifiIp(this);
        if (!wifi.isEmpty()) pairAddrField.setText(wifi + ":");
        root.addView(pairAddrField);

        pairCodeField = new EditText(this);
        pairCodeField.setHint("6-digit pairing code");
        pairCodeField.setInputType(InputType.TYPE_CLASS_NUMBER);
        root.addView(pairCodeField);

        root.addView(button("Pair local shell", v -> {
            String addr = pairAddrField.getText().toString().trim();
            String code = pairCodeField.getText().toString().trim();
            int colon = addr.lastIndexOf(':');
            if (colon <= 0 || code.isEmpty()) {
                toast("Enter the pairing address (IP:port) and code first");
                return;
            }
            String host = addr.substring(0, colon);
            int port;
            try {
                port = Integer.parseInt(addr.substring(colon + 1));
            } catch (NumberFormatException e) {
                toast("Pairing address must end in :port");
                return;
            }
            toast("Pairing…");
            new Thread(() -> {
                boolean ok = LoopbackAdb.ensureIdentity(this)
                        && LoopbackAdb.pair(this, host, port, code);
                if (ok) {
                    Store.put(this, Store.K_SHELL_PAIRED, true);
                    LoopbackAdb.Result r = ShellBridge.runSelfTest(this);
                    runOnUiThread(() -> {
                        toast(r.ok ? "Paired — self-test passed, shell bridge armed"
                                : "Paired, but self-test failed — see log");
                        refresh();
                    });
                } else {
                    runOnUiThread(() -> {
                        toast("Pairing failed — check the code and try again");
                        refresh();
                    });
                }
            }).start();
        }));

        root.addView(button("Run shell self-test (battery)", v -> {
            new Thread(() -> {
                LoopbackAdb.Result r = ShellBridge.runSelfTest(this);
                runOnUiThread(() -> {
                    toast(r.ok ? "Self-test passed" : "Self-test failed — see log");
                    refresh();
                });
            }).start();
            toast("Running self-test…");
        }));

        shellCheck = new android.widget.CheckBox(this);
        shellCheck.setText("Local shell: run allowlisted Worker commands over loopback ADB");
        shellCheck.setChecked(Store.getBool(this, Store.K_SHELL_ON, false));
        shellCheck.setOnCheckedChangeListener((v, checked) -> {
            if (syncingShellCheck) return;
            Store.put(this, Store.K_SHELL_ON, checked);
            BridgeLog.log(this, "SHELL", "executor " + (checked ? "ARMED" : "DISARMED") + " from UI");
            refresh();
        });
        root.addView(shellCheck);

        TextView filesLabel = new TextView(this);
        filesLabel.setText("\nPhone files (v1.24) — Downloads & public folders");
        filesLabel.setTextSize(16);
        root.addView(filesLabel);

        TextView filesHint = new TextView(this);
        filesHint.setText("Lets Muse inventory and organize this phone's "
                + "public folders (Download, Documents, DCIM, Pictures, "
                + "Movies, Music): list, hash, move between folders, "
                + "file loose downloads by type, and trash — trash is a "
                + "MOVE to .muse-trash, never a permanent delete. The "
                + "one-time switch is Android's All Files Access grant "
                + "below; without it every file command refuses.");
        root.addView(filesHint);

        filesView = new TextView(this);
        filesView.setTextIsSelectable(true);
        root.addView(filesView);

        root.addView(button("Grant All Files Access", v -> {
            try {
                Intent i = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(
                            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) {
                    toast("Could not open All Files Access settings");
                }
            }
        }));

        root.addView(button("Inventory Downloads now", v -> {
            new Thread(() -> {
                LoopbackAdb.Result r = FileRouter.execute(this, "files_list",
                        new String[]{"download", "0"});
                runOnUiThread(() -> {
                    if (r.ok) {
                        String first = r.stdoutTail == null ? ""
                                : r.stdoutTail.split("\n")[0];
                        toast("Downloads: " + first);
                    } else {
                        toast("Inventory refused: " + r.error);
                    }
                    refresh();
                });
            }).start();
            toast("Reading Downloads…");
        }));

        TextView extLabel = new TextView(this);
        extLabel.setText("\nExtension pack (v1.5) — device controls");
        extLabel.setTextSize(16);
        root.addView(extLabel);

        TextView extHint = new TextView(this);
        extHint.setText("Worker commands can now also launch apps, open "
                + "navigation, control media + volume, set Do Not Disturb, "
                + "read the foreground app, and switch the flashlight — "
                + "all through the same allowlist, nothing free-form.");
        root.addView(extHint);

        extView = new TextView(this);
        extView.setTextIsSelectable(true);
        root.addView(extView);

        root.addView(button("Flashlight ON (test)", v -> {
            new Thread(() -> {
                LoopbackAdb.Result r = NativeCommands.execute(this, "torch_on");
                runOnUiThread(() -> {
                    toast(r.ok ? "Flashlight on" : "Flashlight failed: " + r.error);
                    refresh();
                });
            }).start();
        }));

        root.addView(button("Flashlight OFF (test)", v -> {
            new Thread(() -> {
                LoopbackAdb.Result r = NativeCommands.execute(this, "torch_off");
                runOnUiThread(() -> {
                    toast(r.ok ? "Flashlight off" : "Flashlight failed: " + r.error);
                    refresh();
                });
            }).start();
        }));

        TextView voiceHint = new TextView(this);
        voiceHint.setText("\nVoice mode self-activation (optional, OFF by default): "
                + "lets Muse start/stop Muse voice mode from chat, using the same "
                + "accessibility tap as the Voice Mode Shortcut app. Two steps, both "
                + "yours: (1) check the box below, (2) enable this app's accessibility "
                + "service. While either is off, the voice command is refused — the "
                + "capability does not exist.");
        root.addView(voiceHint);

        voiceCheck = new android.widget.CheckBox(this);
        voiceCheck.setText("Voice module: allow Muse voice-mode start/stop from chat");
        voiceCheck.setChecked(Store.getBool(this, Store.K_VOICE_ENABLED, false));
        voiceCheck.setOnCheckedChangeListener((v, checked) -> {
            if (syncingVoiceCheck) return;
            Store.put(this, Store.K_VOICE_ENABLED, checked);
            BridgeLog.log(this, "VOICE", "module " + (checked ? "ENABLED" : "DISABLED") + " from UI");
            refresh();
        });
        root.addView(voiceCheck);

        root.addView(button("Open accessibility settings", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                toast("Could not open accessibility settings");
            }
        }));

        root.addView(button("Test voice toggle", v -> {
            LoopbackAdb.Result r = VoiceModeModule.execute(this, "voice_toggle");
            toast(r.ok ? r.stdoutTail : "Voice toggle refused: " + r.error);
            refresh();
        }));

        TextView pushLabel = new TextView(this);
        pushLabel.setText("\nPush channels — this device → Muse (you control each one)");
        pushLabel.setTextSize(16);
        root.addView(pushLabel);

        TextView pushHint = new TextView(this);
        pushHint.setText("Each channel, when ON, pushes changes from this "
                + "device to Muse as they happen — no pulling. OFF means "
                + "not registered, not read, not pushed. All default OFF "
                + "except Mode reconcile (the bridge's existing loop). "
                + "Live since v1.7: Notifications, Low Battery, Calendar, "
                + "Contacts, Messages, Phone dialer, and Call Log. "
                + "(The Health Connect channel was removed in v1.12 — "
                + "health data flows through the Health Connect "
                + "connector.) On TV every channel is gated off.");
        root.addView(pushHint);

        pushView = new TextView(this);
        pushView.setTextIsSelectable(true);
        root.addView(pushView);

        pushChecks = new android.widget.CheckBox[PushChannels.ALL.length];
        for (int i = 0; i < PushChannels.ALL.length; i++) {
            final PushChannels.Channel ch = PushChannels.ALL[i];
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(ch.label);
            cb.setChecked(Store.getBool(this, ch.toggleKey, ch.defaultOn));
            if (DeviceProfile.isTv(this)) cb.setEnabled(false);
            cb.setOnCheckedChangeListener((v, checked) -> {
                if (syncingPushCheck) return;
                onPushToggled(ch, checked);
            });
            pushChecks[i] = cb;
            root.addView(cb);
        }

        TextView lcLabel = new TextView(this);
        lcLabel.setText("\nMuse Launcher Pack — local channel");
        lcLabel.setTextSize(16);
        root.addView(lcLabel);

        TextView lcHint = new TextView(this);
        lcHint.setText("The Muse Launcher Pack widgets can run the same "
                + "allowlisted commands as the Worker channel, locally: "
                + "opening the pairing window below mints a one-time token "
                + "the pack claims (its app signature is verified first). "
                + "Revoke here or in the pack's setup at any time.");
        root.addView(lcHint);

        lcView = new TextView(this);
        lcView.setText(LocalChannel.statusText(this));
        root.addView(lcView);

        root.addView(button("Open pairing window (5 min)", v -> {
            LocalChannel.openPairingWindow(this);
            lcView.setText(LocalChannel.statusText(this));
        }));

        root.addView(button("Revoke Launcher Pack pairing", v -> {
            LocalChannel.revokePairing(this);
            lcView.setText(LocalChannel.statusText(this));
        }));

        TextView settingsLabel = new TextView(this);
        settingsLabel.setText("\nWorker settings (secret lives only on this phone)");
        settingsLabel.setTextSize(16);
        root.addView(settingsLabel);

        baseUrlField = new EditText(this);
        baseUrlField.setHint("Worker base URL, e.g. https://jonathancruzate.com/mode-sync");
        baseUrlField.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        baseUrlField.setText(Store.get(this, Store.K_BASE_URL,
                "https://jonathancruzate.com/mode-sync"));
        root.addView(baseUrlField);

        secretField = new EditText(this);
        secretField.setHint("Mode-state secret (entered on device, never in the APK)");
        secretField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secretField.setText(Store.get(this, Store.K_SECRET, ""));
        root.addView(secretField);

        root.addView(button("Save worker settings", v -> {
            Store.put(this, Store.K_BASE_URL, baseUrlField.getText().toString().trim());
            Store.put(this, Store.K_SECRET, secretField.getText().toString().trim());
            BridgeLog.log(this, "SETUP", "worker settings saved (configured="
                    + Store.workerConfigured(this) + ")");
            toast("Saved");
            refresh();
        }));

        root.addView(button("Sync now (fetch desired + report)", v -> {
            new Thread(() -> {
                WorkerClient.fetchDesired(this);
                Reconciler.evaluate(this);
                WorkerClient.postReport(this, "manual");
                refreshSoon();
            }).start();
            toast("Syncing…");
        }));

        TextView probeLabel = new TextView(this);
        probeLabel.setText("\nPhase 0 probe");
        probeLabel.setTextSize(16);
        root.addView(probeLabel);

        root.addView(button("Run Phase 0 probe", v -> {
            new Thread(() -> {
                String result = Probe.run(this);
                runOnUiThread(() -> {
                    probeView.setText(result);
                });
            }).start();
        }));

        probeView = new TextView(this);
        probeView.setTextIsSelectable(true);
        root.addView(probeView);

        TextView logLabel = new TextView(this);
        logLabel.setText("\nBridge log (latest 40)");
        logLabel.setTextSize(16);
        root.addView(logLabel);

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    /**
     * Push-channel toggle handler (v1.6, design §4) — follows the
     * voiceCheck/shellCheck pattern: Store.put, a BridgeLog line, and a
     * refresh. Turning a channel ON also jumps its cursor to now (no
     * historical dump without asking) and starts its permission flow:
     * Notifications is already granted (status only); Calendar /
     * Contacts / Messages / Dialer / Call Log request their runtime
     * permission here (their observers are v1.7 — toggle + Store + log
     * + permission is the whole v1.6 surface for them).
     */
    private void onPushToggled(PushChannels.Channel ch, boolean checked) {
        if (DeviceProfile.isTv(this)) {
            toast("Push channels are gated off on TV");
            refresh();
            return;
        }
        Store.put(this, ch.toggleKey, checked);
        BridgeLog.log(this, "PUSH", "channel " + ch.id + " "
                + (checked ? "ON" : "OFF") + " from UI");
        if (checked) {
            Store.put(this, ch.cursorKey, System.currentTimeMillis());
            switch (ch.id) {
                case "notifications": {
                    String listeners = Settings.Secure.getString(getContentResolver(),
                            "enabled_notification_listeners");
                    boolean granted = listeners != null
                            && listeners.contains(getPackageName());
                    toast(granted
                            ? "Notifications: notification access already granted"
                            : "Notifications: grant notification access (button above)");
                    BridgeLog.log(this, "PUSH", "notifications access "
                            + (granted ? "already granted" : "NOT granted"));
                    break;
                }
                case "calendar":
                    requestPermissions(new String[]{android.Manifest.permission.READ_CALENDAR}, 71);
                    break;
                case "contacts":
                    requestPermissions(new String[]{android.Manifest.permission.READ_CONTACTS}, 72);
                    break;
                case "messages":
                    requestPermissions(new String[]{
                            android.Manifest.permission.READ_SMS,
                            android.Manifest.permission.RECEIVE_SMS}, 73);
                    break;
                case "dialer":
                    requestPermissions(new String[]{android.Manifest.permission.READ_PHONE_STATE}, 74);
                    break;
                case "call_log":
                    requestPermissions(new String[]{android.Manifest.permission.READ_CALL_LOG}, 75);
                    break;
                default:
                    // battery + mode_reconcile need no permission.
                    break;
            }
        }
        refresh();
    }

    private Button button(String label, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(label);
        b.setOnClickListener(onClick);
        return b;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void refreshSoon() {
        new android.os.Handler(getMainLooper()).postDelayed(this::refresh, 800);
    }

    private void refresh() {
        if (stateView == null) return;
        Reconciler.Verdict v = Reconciler.evaluate(this);
        SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
        long gtAt = Store.getLong(this, Store.K_GT_AT, 0);
        long desiredAt = Store.getLong(this, Store.K_DESIRED_AT, 0);
        long reportAt = Store.getLong(this, Store.K_LAST_REPORT_AT, 0);
        int reportCode = (int) Store.getLong(this, Store.K_LAST_REPORT_CODE, 0);

        StringBuilder sb = new StringBuilder();
        sb.append("Profile: ").append(DeviceProfile.describe(this)).append('\n');
        sb.append("Bridge service: ")
          .append(Store.getBool(this, Store.K_SERVICE_ON, false) ? "ON" : "OFF").append('\n');
        sb.append("Worker configured: ").append(Store.workerConfigured(this)).append('\n');
        sb.append("\nGROUND TRUTH (from Modes and Routines notifications)\n");
        sb.append("Current mode: ").append(Store.get(this, Store.K_GT_MODE, "—")).append('\n');
        sb.append("Running: ").append(Store.get(this, Store.K_GT_RUNNING, "—")).append('\n');
        sb.append("Last dump: ").append(gtAt == 0 ? "never" : fmt.format(new Date(gtAt)))
          .append(" · parse ok: ").append(Store.getBool(this, Store.K_GT_PARSE_OK, false)).append('\n');
        sb.append("\nDESIRED (from Worker)\n");
        sb.append("Mode: ").append(Store.get(this, Store.K_DESIRED_MODE, "—"))
          .append(" · seq ").append(Store.getLong(this, Store.K_DESIRED_SEQ, -1))
          .append(" · fetched ").append(desiredAt == 0 ? "never" : fmt.format(new Date(desiredAt)))
          .append('\n');
        sb.append("\nVERDICT: ").append(v.state).append('\n');
        if (v.plan != null && !v.plan.isEmpty()) sb.append("Plan: ").append(v.plan).append('\n');
        sb.append("Last report: ").append(reportAt == 0 ? "never"
                : fmt.format(new Date(reportAt)) + " (HTTP " + reportCode + ")").append('\n');

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            sb.append("Battery unrestricted: ")
              .append(pm.isIgnoringBatteryOptimizations(getPackageName())).append('\n');
        }
        stateView.setText(sb.toString());

        if (adbView != null) {
            long epAt = Store.getLong(this, Store.K_ADB_ENDPOINT_AT, 0);
            StringBuilder ab = new StringBuilder();
            ab.append("Wireless debugging: ")
              .append(AdbBridge.wirelessDebuggingOn(this) ? "ON" : "OFF").append('\n');
            ab.append("WRITE_SECURE_SETTINGS grant: ")
              .append(AdbBridge.secureSettingsGranted(this) ? "GRANTED" : "not granted").append('\n');
            ab.append("Connect endpoint: ")
              .append(Store.get(this, Store.K_ADB_ENDPOINT, "—"))
              .append(epAt == 0 ? "" : " (updated " + fmt.format(new Date(epAt)) + ")").append('\n');
            ab.append("Pairing window advertised: ")
              .append(Store.getBool(this, Store.K_ADB_PAIRING_OPEN, false)).append('\n');
            ab.append("Wi-Fi IP: ").append(AdbBridge.wifiIp(this)).append('\n');
            ab.append("Discovered services: ")
              .append(Store.get(this, Store.K_ADB_ENDPOINTS_JSON, "[]")).append('\n');
            adbView.setText(ab.toString());
        }

        if (shellView != null) {
            StringBuilder sv = new StringBuilder();
            sv.append("Paired: ").append(Store.getBool(this, Store.K_SHELL_PAIRED, false)).append('\n');
            sv.append("Self-test passed: ")
              .append(Store.getBool(this, Store.K_SHELL_SELF_TEST_OK, false)).append('\n');
            sv.append("Executor armed: ")
              .append(Store.getBool(this, Store.K_SHELL_ON, false)).append('\n');
            sv.append("Loopback connected: ").append(LoopbackAdb.isConnected(this)).append('\n');
            long lastAt = Store.getLong(this, Store.K_SHELL_LAST_AT, 0);
            if (lastAt > 0) {
                sv.append("Last command: ").append(Store.get(this, Store.K_SHELL_LAST_TEMPLATE, ""))
                  .append(" (").append(Store.get(this, Store.K_SHELL_LAST_ID, "")).append(") ok=")
                  .append(Store.getBool(this, Store.K_SHELL_LAST_OK, false))
                  .append(' ').append(Store.getLong(this, Store.K_SHELL_LAST_MS, 0)).append("ms · ")
                  .append(fmt.format(new Date(lastAt))).append('\n');
            }
            shellView.setText(sv.toString());
            if (shellCheck != null) {
                syncingShellCheck = true;
                shellCheck.setChecked(Store.getBool(this, Store.K_SHELL_ON, false));
                syncingShellCheck = false;
            }
        }

        if (filesView != null) {
            filesView.setText(FileRouter.uiState(this));
        }

        if (extView != null) {
            StringBuilder ev = new StringBuilder();
            long torchAt = Store.getLong(this, Store.K_TORCH_AT, 0);
            ev.append("Flashlight: ");
            if (torchAt == 0) {
                ev.append("no state observed yet\n");
            } else {
                ev.append(Store.getBool(this, Store.K_TORCH_ON, false) ? "ON" : "OFF")
                  .append(" (").append(fmt.format(new Date(torchAt))).append(")\n");
            }
            ev.append("Voice module enabled: ")
              .append(Store.getBool(this, Store.K_VOICE_ENABLED, false)).append('\n');
            ev.append("Voice accessibility service: ")
              .append(VoiceModeModule.accessibilityEnabled(this) ? "ENABLED" : "not enabled")
              .append(VoiceModeModule.serviceRunning() ? " · running" : " · not running")
              .append('\n');
            ev.append("Voice session believed active: ")
              .append(Store.getBool(this, Store.K_VOICE_ACTIVE, false)).append('\n');
            extView.setText(ev.toString());
            if (voiceCheck != null) {
                syncingVoiceCheck = true;
                voiceCheck.setChecked(Store.getBool(this, Store.K_VOICE_ENABLED, false));
                syncingVoiceCheck = false;
            }
        }

        if (pushView != null) {
            StringBuilder pv = new StringBuilder();
            boolean tv = DeviceProfile.isTv(this);
            for (PushChannels.Channel ch : PushChannels.ALL) {
                long lp = Store.getLong(this, ch.lastPushKey, 0);
                String err = Store.get(this, ch.lastErrorKey, "");
                pv.append(ch.label).append(": ")
                  .append(tv ? "OFF (TV gated)" : (Store.getBool(this, ch.toggleKey, ch.defaultOn) ? "ON" : "OFF"))
                  .append(" · last push ").append(lp == 0 ? "never" : fmt.format(new Date(lp)));
                if (err != null && !err.isEmpty()) pv.append(" · last error: ").append(err);
                pv.append('\n');
            }
            pv.append("Queued retries: ").append(PushClient.queueSize(this)).append('\n');
            pushView.setText(pv.toString());
            if (pushChecks != null) {
                syncingPushCheck = true;
                for (int i = 0; i < PushChannels.ALL.length && i < pushChecks.length; i++) {
                    PushChannels.Channel ch = PushChannels.ALL[i];
                    pushChecks[i].setChecked(Store.getBool(this, ch.toggleKey, ch.defaultOn));
                }
                syncingPushCheck = false;
            }
        }

        List<String> tail = BridgeLog.tail(this, 40);
        StringBuilder lb = new StringBuilder();
        for (String line : tail) lb.append(line).append('\n');
        logView.setText(lb.toString());
    }
}
