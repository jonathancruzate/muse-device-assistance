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
    private EditText pairAddrField;
    private EditText pairCodeField;
    private TextView actView;
    private android.widget.CheckBox armCheck;
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

        TextView sub = new TextView(this);
        sub.setText("Control plane that complements MacroDroid: ground truth, "
                + "verified acks, drift plans. Shadow by default — Phase 2 "
                + "actuation (night only) stays OFF unless armed below.");
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

        TextView actLabel = new TextView(this);
        actLabel.setText("\nPhase 2 actuation — night mode only, OFF by default");
        actLabel.setTextSize(16);
        root.addView(actLabel);

        TextView actHint = new TextView(this);
        actHint.setText("Linking opens Samsung's routine picker — pick the matching "
                + "Switch routine once; its launch shortcut is stored on this phone "
                + "only. Arming lets the bridge fire Night itself when the Worker "
                + "asks and a fresh dump shows drift (once per request).");
        root.addView(actHint);

        actView = new TextView(this);
        actView.setTextIsSelectable(true);
        root.addView(actView);

        armCheck = new android.widget.CheckBox(this);
        armCheck.setText("ARM Phase 2: fire the Night routine on drift (all other modes stay shadow)");
        armCheck.setChecked(Store.getBool(this, Store.K_ACT_ARMED, false));
        armCheck.setOnCheckedChangeListener((v, checked) -> {
            Store.put(this, Store.K_ACT_ARMED, checked);
            BridgeLog.log(this, "ACT", "Phase 2 actuation " + (checked ? "ARMED" : "DISARMED") + " from UI");
            refresh();
        });
        root.addView(armCheck);

        root.addView(button("Link routine: Night (Switch Sleep mode)", v -> startLink("night", 61)));
        root.addView(button("Link routine: Home", v -> startLink("home", 62)));
        root.addView(button("Link routine: Morning", v -> startLink("morning", 63)));
        root.addView(button("Link routine: Away latch", v -> startLink("away", 64)));
        root.addView(button("Test fire: Night routine", v -> {
            if (!RoutineFirer.isLinked(this, "night")) {
                toast("Night routine not linked yet");
                return;
            }
            boolean ok = RoutineFirer.fire(this, "night");
            toast(ok ? "Fired Night routine" : "Fire failed — see log");
            refreshSoon();
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

    private void startLink(String houseMode, int requestCode) {
        if (!RoutineFirer.linkFlowAvailable(this)) {
            BridgeLog.log(this, "LINK", "CREATE_SHORTCUT picker NOT resolvable on this "
                    + "build — link flow unavailable for " + houseMode);
            toast("Samsung's shortcut picker isn't available on this build — see log");
            return;
        }
        try {
            startActivityForResult(RoutineFirer.buildLinkIntent(), requestCode);
        } catch (Exception e) {
            BridgeLog.log(this, "LINK", "startActivityForResult failed for " + houseMode
                    + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
            toast("Could not open the routine picker — see log");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        String mode;
        switch (requestCode) {
            case 61: mode = "night"; break;
            case 62: mode = "home"; break;
            case 63: mode = "morning"; break;
            case 64: mode = "away"; break;
            default: return;
        }
        if (resultCode != RESULT_OK || data == null) {
            BridgeLog.log(this, "LINK", "link cancelled for " + mode);
            refresh();
            return;
        }
        Intent shortcut = null;
        try {
            shortcut = data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT);
        } catch (Exception ignored) {}
        String name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME);
        if (shortcut == null) {
            BridgeLog.log(this, "LINK", "picker returned NO shortcut intent for " + mode
                    + " — the CREATE_SHORTCUT contract is not honored on this build");
            toast("Picker returned no shortcut — see log");
            refresh();
            return;
        }
        Store.put(this, RoutineFirer.linkKey(mode), shortcut.toUri(Intent.URI_INTENT_SCHEME));
        if (name != null) Store.put(this, RoutineFirer.linkNameKey(mode), name);
        BridgeLog.log(this, "LINK", "linked " + mode + " -> " + name);
        toast("Linked " + mode + (name != null ? ": " + name : ""));
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

        if (actView != null) {
            StringBuilder av = new StringBuilder();
            av.append("Actuation armed: ")
              .append(Store.getBool(this, Store.K_ACT_ARMED, false)).append('\n');
            for (String m : new String[]{"night", "home", "morning", "away"}) {
                av.append("Linked ").append(m).append(": ");
                if (RoutineFirer.isLinked(this, m)) {
                    String n = RoutineFirer.linkedName(this, m);
                    av.append(n.isEmpty() ? "yes" : n);
                } else {
                    av.append("not linked");
                }
                av.append('\n');
            }
            av.append("Last fired seq: ")
              .append(Store.getLong(this, Store.K_ACT_FIRED_SEQ, -1)).append('\n');
            actView.setText(av.toString());
        }

        List<String> tail = BridgeLog.tail(this, 40);
        StringBuilder lb = new StringBuilder();
        for (String line : tail) lb.append(line).append('\n');
        logView.setText(lb.toString());
    }
}
