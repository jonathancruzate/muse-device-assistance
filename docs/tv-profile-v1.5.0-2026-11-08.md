# TV profile — v1.5.0 (LEG A, 2026-11-08 evening)

Built at Jonathan's order ("Do both") under tracked item
goal_8bdf2969b528 (Muse integration on NVIDIA Shield). Architecture
ratified 2026-11-08 10:10 EST: ONE app, runtime Shield detection +
TV profile, no separate app.

## APK

- `app/Muse-Device-Assistance-1.5.0.apk`, versionCode 12 / 1.5.0
- 5,699,668 bytes, SHA-256
  `8dfbba37bc998e476de64732b945bad8db9096af8e6a91fef845a928e20666e9`
  (the artifact on disk and installed on the Shield — device-side
  sha256sum of the installed base.apk matches exactly. This leg's
  own build session also completed a same-source build,
  5,687,299 bytes / b7baa9…, which was superseded on disk at 01:51Z
  by a parallel rebuild; both contain the TV code, proven live.)
- Signer cert SHA-256
  `4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236`
  — same as every prior version. All build.sh guards passed
  (app classes incl. new DeviceProfile, named v1.4 libs, no
  non-annotation androidx, native libs packaged).
- ShellAllowlist gate tests: ALL_PASS (unchanged allowlist).

## The five items

1. **Runtime TV detection — BUILT.** New `DeviceProfile`:
   UiModeManager `UI_MODE_TYPE_TELEVISION`, else Leanback feature,
   else `android.hardware.type.television`. Cached per process.
   Live proof on Shield: `dumpsys uimode` mCurUiMode=0x14 (TV), and
   the running service's notification text (below) is the TV branch.
   Manifest adds optional Leanback feature, touchscreen
   not-required, and a LEANBACK_LAUNCHER category (phone launcher
   entry unchanged).
2. **shieldbridge identity — BUILT + WORKER DEPLOYED + VERIFIED.**
   App: reports carry `device="shield-bridge"`, `profile="tv"`;
   command fetch/result carry the device string.
   Worker (`mode-sync`): report POST stores shield reports under
   KV `shieldbridge:report` (GET via `?device=shield-bridge`);
   command channel split into `shieldbridge:queue` /
   `shieldbridge:cmd:<id>` (enqueue honors body `device`;
   command-status searches both lanes and its pending list adds
   `shield_pending`). TV status object preserved sanitized as
   `tv_status`. Deployed 2026-11-09T01:52Z via the
   binding-preserving deploy.py — all pre-existing bindings
   preserved. Verification: existing unit suite ALL PASS (34);
   new shield-lane suite 9/9 PASS (report split, tv_status,
   queue isolation — shield pull never gets a phone command);
   live post-deploy proof: a phone `battery` command enqueued
   01:52:16Z was delivered 01:52:44Z and completed `ok:true`
   (144 ms) on the S24+ through the new Worker code, and the
   phone report GET still returns the phone's report untouched;
   shield report GET correctly returns "no report yet".
   Pre-change source: `worker.js.bak-20261108-pre-shieldbridge`.
3. **Auto-start on TV — BUILT, service live-verified.** TV:
   MainActivity auto-starts the service; BootReceiver auto-starts
   unconditionally (no `service_on` pref needed). Live: after the
   install (below), the system restarted the sticky BridgeService
   into the new code — `dumpsys activity services` shows it
   foreground (startForegroundCount=1, isForeground=true) and its
   notification reads "Shield TV profile - shieldbridge -
   observing (phone surfaces off)", a string only the TV branch
   produces. The boot path itself has not been exercised (no
   reboot done/permitted in this leg).
4. **TV heal variant — BUILT as no-op-with-logging.** Live
   evidence: `settings get global adb_wifi_enabled` returned 0 on
   the Shield while its LAN ADB :5555 was connected and answering
   (`adb get-state` = device) — NVIDIA's network-debugging switch
   is not `adb_wifi_enabled`, and no other stock key was provable
   from source or live reads. `AdbBridge.tickTv` therefore never
   writes that setting; it logs the observation throttled (6 h).
   Snapshot carries `heal_mode="tv_noop"`. Discovery unchanged;
   wifiIp now also accepts eth* interfaces on TV (Shield is wired).
5. **Phone-only surfaces gated off on TV — BUILT.** Reconciler
   returns unknown/"gated off" on TV; Actuation returns
   immediately (Phase 2 stays DISARMED everywhere); WorkerClient
   never fetches desired state on TV and reports carry empty
   observed/desired with ack=false; StatusProducer produces a TV
   set (model/manufacturer/sdk, screen_on, ethernet-aware network,
   storage — no battery/ringer/power-save); GroundTruthListener
   is inert on TV; ShellBridge.tick is gated off on TV (Shield
   debugging is LAN ADB, not the phone pairing flow — a TV shell
   lane is a separate design if ever wanted).

## Shield install

- Pre-check: Shield awake, screen ON, YouTube TV actively playing
  (state=3) — per instructions, install only, no launch/wake.
  (The package update itself restarted the already-existing sticky
  BridgeService; nothing was launched by Muse and playback
  surfaces were not touched.)
- `adb install -r` over tailnet-forward 5556 → 100.82.130.50:5555
  (incremental refused by Shield, streamed fallback): Success.
- Read-back: versionCode 12 / versionName 1.5.0,
  lastUpdateTime 2026-11-08 20:53:53 EST;
  WRITE_SECURE_SETTINGS still granted=true.
- Phone: THIS leg installed nothing on the S24+ (per instructions)
  and proved the Worker with a command while it was on v1.4.3.
  A parallel leg then installed v1.5.0 on the phone too —
  read-back: versionCode 12 / 1.5.0, lastUpdateTime 2026-11-08
  20:53:57 EST, BridgeService foreground, reports flowing with
  profile="phone" and the phone verdict machinery intact
  (observed Home / desired away at 01:53:58Z). The TV fencing
  holds on the phone exactly as designed: same APK, phone profile.

## Remaining Jonathan step (unchanged, on-device by design)

The Shield instance has **no Worker secret entered** — the v1.3.2
install deliberately never received one, and this leg did not
enter any secret. Until Jonathan opens Muse Device Assistance
on the Shield and saves the Worker base URL + secret there, the
TV service runs and observes locally but posts no shieldbridge
reports (workerConfigured=false). After that one step, reports
land under `shieldbridge:report` with no further code needed.
