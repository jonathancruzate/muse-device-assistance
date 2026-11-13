# Muse Device Assistance — ground truth, verified acks, ADB + Tailscale self-heal

The phone-side control plane for Jonathan's Galaxy S24+ mode-sync
system: the custom app that **complements MacroDroid** by owning the
truth, the verification, and the reconciliation logic Samsung's gated
mode surfaces leave to everyone else. Built from the 2026-11-01
investigation *"Custom app vs MacroDroid as the phone-control plane"*.
Project: `~/workspace/muse-device-assistance/` (repo
`jonathancruzate/muse-device-assistance` — renamed on GitHub
2026-11-04, the old `muse-phone-bridge` URL redirects; the GitHub
snapshot source is `github-export/` inside the project).

**Renamed 2026-11-03 (Jonathan's order, live voice call): Muse Phone
Bridge → Muse Device Assistance** (an interim name applied earlier
the same evening was superseded minutes later; this is the final
name). The project, this skill, the local
directory, and the Device Assistance connector now carry the new name,
and the app is being extended from bridge access to full device
control (roadmap in the connector doc,
`~/workspace/custom-connectors/custom.device-assistance/CONNECTOR.md`;
execution sequence in `RENAME-PLAN.md` at the project root). What has
**not** changed, deliberately: the wire names (Worker endpoint
`/phone-bridge/report`, KV key `phonebridge:report`, report
`device:"phone-bridge"`) — a live contract with the mode-sync
Worker, not branding. The label + package rename itself was
**executed 2026-11-05** (evidence: `RENAME-PLAN.md` step B,
`RENAME-BUILD-PLAN.md`): the renamed build (label "Muse Device
Assistance", package
`com.jonathancruzate.metamusedeviceassistance`) was installed
alongside the old package, re-granted, verified reporting with
fresh `parse_ok=true` ground truth, and only then was the old
package uninstalled. The canonical sources now carry the new
package throughout; the wire names above are unchanged, as are the
prefs name, Store keys, log filename, and notification channel
IDs.

This skill ships with the app (Jonathan's standing rule, 2026-11-03:
every APK we build carries a skill teaching Muse how to interact with
and use it). The in-repo copy is
`~/workspace/muse-device-assistance/SKILL.md`; the installed copy is
`~/workspace/custom-skills/local-muse-device-assistance/SKILL.md`. They
are the same document — update both in the same handling, always.

## Identity and version source of truth

- **Package:** `com.jonathancruzate.metamusedeviceassistance`
  (label "Muse Device Assistance" — the label + package rename was
  executed 2026-11-05; the old package
  `com.jonathancruzate.metamusephonebridge` / label "Muse Phone
  Bridge" was uninstalled after the verified cutover)
- **Version source of truth:** `app/build.sh` — `VERSION_NAME` /
  `VERSION_CODE` in that script, backed by the build records in
  `github-export/README.md`. Current: **v1.24.0, versionCode 35** (this line was stale at v1.5.1 until the 2026-11-12 correction; app/build.sh is always the truth)
  (APK `app/Muse-Device-Assistance-1.3.2.apk`, SHA-256
  `3b2adc07d64eb000369f316bfa6f713a376e49c357109aba842166d82ab06166`,
  rebuilt from the renamed canonical tree 2026-11-05.
- **v1.4.0 (versionCode 7) built 2026-11-05** — the loopback shell
  bridge (design: `docs/loopback-shell-bridge-v1.4-design-2026-11-04.md`).
  APK `app/Muse-Device-Assistance-1.4.0.apk`, SHA-256
  `b422fb71b214ed009060428aa7f9ed104496b0e41cdcae85a6cdbcb8d4fa3535`.
  **Installed and verified live 2026-11-08** (execution record:
  `docs/execution-2026-11-08.md`): an earlier install had in fact
  already landed it (dumpsys showed versionCode 7 before this run's
  in-place `install -r`); verified post-install by dumpsys
  versionCode 7, running BridgeService, fresh `parse_ok=true`
  reports, all grants read back (`WRITE_SECURE_SETTINGS`,
  notification listener, `POST_NOTIFICATIONS`,
  `NEARBY_WIFI_DEVICES`), and the deviceidle whitelist re-added
  (it had dropped off). The Worker command channel
  (`enqueue`/`commands`/`command-result`/`command-status`) was
  already deployed and was verified live with a full round-trip;
  the report sanitizer was patched + redeployed 2026-11-08 to keep
  the app's `shell_bridge` object. **Still pending: the one-time
  pairing session with Jonathan** (in-app pairing + self-test) —
  live `shell_bridge` state is `{paired:false, self_test_ok:false,
  on:false}`. The renamed v1.3.2 paragraph above is now the previous
  version of record. The retired
  `app/Muse-Phone-Bridge-1.3.2.apk`, SHA-256
  `1f2055ca02987af63cfea50d9610200223b2ec73d3ba4a7601a2d9ea7cd45cae`,
  is kept alongside as the rollback build.)
- **v1.4.1 (versionCode 8) built + installed 2026-11-08** — shell
  bridge self-test fix. The 2026-11-08 03:43 EST pairing session
  paired successfully (SPAKE2 + connect OK) but both `battery`
  self-test attempts failed in ~50-100 ms with IOException
  "Stream closed." — adbd OKAYed the `shell:` OPEN, then CLSEd it
  with no payload. Root cause AS THEN THEORIZED: `LoopbackAdb`
  never called libadb-android's `setApi()`, so every connection
  negotiated as a legacy client (CNXN version 0x01000000, maxData
  4096, bare `host::` identity with no feature list — the library's
  default API level is 1, verified in the vendored jar's bytecode);
  the modern daemon refuses shell service to a feature-less legacy
  peer while pairing/auth (API-independent) still succeed.
  **CORRECTION (2026-11-08, section (g)): that theory was wrong.**
  A raw wire probe sending the exact feature-less CNXN (legacy and
  modern forms) received the full payload from the same adbd; the
  real cause is a libadb-android end-of-stream race (output
  delivered, final read throws instead of returning -1), fixed in
  v1.4.3 below. The `setApi()` change is correct and kept; it was
  never the bug. Fix (as shipped in v1.4.1):
  `setApi(Build.VERSION.SDK_INT)`, a 300 ms post-connect settle +
  negotiated protocol/maxData in the connect log line, and one
  execute() retry on a fresh connection from the alternate source
  address (all allowlisted templates are idempotent). APK
  `app/Muse-Device-Assistance-1.4.1.apk`, SHA-256
  `19a9ad627a8aa7d823718981faeb3f2b0d0a9a0a4f3f4481b50cb2887f27181e`,
  same signing cert as every prior version; all build guards pass;
  ShellAllowlist gate tests ALL_PASS. Installed in place
  (`install -r`, lastUpdateTime 2026-11-08 04:02:59 EST);
  post-install report (04:03:02 EST) shows
  `shell_bridge {paired:true, self_test_ok:false, on:true}` — the
  03:43 pairing SURVIVED the update (no re-pairing needed) and
  Phase 2 remains DISARMED ("shadow: not fired"). **Bridge is NOT
  live yet**: it goes live only when Jonathan taps "Run shell
  self-test (battery)" in the app and it passes, followed by the
  Worker `battery` command proof from the chat side.
- **v1.4.2 (versionCode 9) built + installed 2026-11-08** — the
  dataSync crash-loop fix. Device care flagged the app for crashes;
  the evidence (exit-info + dropbox + logcat) showed a chronic loop
  dating to **2026-11-05 on v1.4.0**, unrelated to the shell path:
  BridgeService ran as FGS type `dataSync`, whose ~6 h platform
  time budget a 24/7 service always exhausts. On exhaustion the
  system timed the service out (no `onTimeout` handler →
  `ForegroundServiceDidNotStopInTimeException` kill, most recently
  2026-11-08 10:12:50 EST), and every restart then crashed in
  `onCreate` at the unguarded `startForeground` with
  `ForegroundServiceStartNotAllowedException: Time limit already
  exhausted for foreground service type dataSync` until budget slid
  back. Fix: FGS type → **specialUse** (manifest type + permission
  + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`; explicit
  `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` at the call), guarded
  `startForeground` (a refused start logs + stops cleanly, never
  crashes), an `onTimeout` clean-stop, and a try/catch in
  `BridgeService.start()`. APK
  `app/Muse-Device-Assistance-1.4.2.apk`, SHA-256
  `f9cbb9379a1ddb32e8a33a161fd8f1958571ac095d3fbfdd70898483b036a3d7`,
  same signing cert; all build guards pass; ShellAllowlist gate
  tests ALL_PASS. Installed in place (`install -r`,
  lastUpdateTime 2026-11-08 10:26:56 EST); pairing survived (first
  v1.4.2 report carries `shell_bridge {paired:true,
  self_test_ok:false, on:true}`), the post-install process stayed
  up with **no new crash records**, and Phase 2 remains DISARMED.
  Record: `docs/execution-2026-11-08.md` section (f). The self-test
  still awaits Jonathan's tap — now against a stable app.
- **v1.4.3 (versionCode 11) built + installed 2026-11-08** — the
  REAL self-test fix; the v1.4.1 entry above states a root cause
  that was DISPROVEN the same day (see its correction note). After
  Jonathan's fresh pairing (17:07 EST), the self-test still failed
  at ~40–95 ms on modern negotiation. Evidence chain (record:
  `docs/execution-2026-11-08.md` section (g)): desktop adb and a
  raw wire probe sending libadb's exact feature-less CNXN both got
  the full `dumpsys battery` payload from the same adbd; daemon
  logcat showed the client vanishing ~50 ms after each OPEN; an
  instrumented diagnostic build (v1.4.3-diag1, versionCode 10)
  then showed the app reading 13,069 bytes of payload and throwing
  `IOException: Stream closed.` on the NEXT read. Root cause:
  **libadb-android 3.1.0 end-of-stream race** — adbd sends a shell
  command's output as WRTEs then CLSEs; `notifyClose(true)` marks
  the stream hard-closed whenever the read queue is momentarily
  empty (even with the last payload undrained in the reader's
  buffer), so `AdbStream.read()` throws "Stream closed." instead
  of returning -1 after all output arrived. The output was being
  delivered and discarded on the exception all along. Fix
  (app-side, in `LoopbackAdb.executeOnce`): a `"Stream closed."`
  IOException while the connection is still alive is treated as
  EOF; any other failure still reaps as before. The diag
  instrumentation (logcat tag `LoopbackAdbDiag`: reap call sites,
  stream open/failure with stack, race-EOF line) is kept as the
  standing discriminator. APK
  `app/Muse-Device-Assistance-1.4.3.apk`, SHA-256
  `b40b70827b6b03380be05983c01e28ad2dd1fb64ad2853c3d6a982ffbaec4d6b`,
  same signing cert; all build guards pass; ShellAllowlist gate
  tests ALL_PASS. Installed in place (`install -r`,
  lastUpdateTime 2026-11-08 17:39:35 EST); pairing survived.
  **Bridge LIVE for Worker commands, proven end-to-end the same
  handling**: Worker `battery` → `ok:true` (full dumpsys tail,
  75 ms) and `getprop_model` → `ok:true` (`SM-S926U1`). The
  report's `self_test_ok` flag is set only by the in-app self-test
  (same execute path) and still reads false until Jonathan taps
  "Run shell self-test (battery)" once — bookkeeping, not function.
- **v1.5.0 (versionCode 12) built + installed 2026-11-08** — the
  extension pack, plus the ratified TV profile in the same binary
  (one source tree; TV record:
  `docs/tv-profile-v1.5.0-2026-11-08.md`). Eleven new shell
  templates (`app_launch`, `nav_open`, `media_play_pause`,
  `media_next`, `media_previous`, `media_volume`,
  `media_volume_set`, `media_volume_get`, `dnd_on`, `dnd_off`,
  `screen_foreground`) and three gated native templates
  (`flashlight`, `flashlight_status`, `voice_mode`) join the
  allowlist — no free-form shell anywhere; every arg validated by
  the same hygiene + slot validators, gate tests extended,
  ALL_PASS. Each verb was proven on the phone over the ADB lane
  BEFORE encoding, then live-verified through the Worker channel
  with independent read-backs (record:
  `docs/execution-2026-11-08.md` section (h)): `app.launch`
  (Maps — Jonathan also confirmed in chat: "App control is
  working well and opening apps"), `nav.open` (single-token
  destination, percent-encoded; spaces/`&`/`=` unrepresentable),
  `media.*` transport + volume (bounded set 0..15, this device's
  STREAM_MUSIC range), `dnd.set` via the in-app shell lane
  (`zen_mode` 0→2→0 read back; the MacroDroid pair is now the
  fallback), `flashlight.set` via CameraManager torch (no shell
  route exists on this build; camera-service log + status
  read-back), `screen.foreground` (fixed zero-arg `dumpsys …
  | grep mCurrentFocus` pipe — the raw dump would scroll the
  focus line off the executor's tail). The **voice-mode module**
  also shipped in this build (see the Optional feature section —
  built, gated, NOT live until Jonathan's two enable steps). APK
  `app/Muse-Device-Assistance-1.5.0.apk`, 5,699,668 bytes,
  SHA-256
  `8dfbba37bc998e476de64732b945bad8db9096af8e6a91fef845a928e20666e9`,
  same signing cert; all build guards pass. Installed in place
  (`install -r`, lastUpdateTime 2026-11-08 20:53:57 EST);
  pairing + `self_test_ok:true` survived. **Worker channel
  operating rule learned this night: enqueue commands SINGLY** —
  the app executes one command per fetch, newest first; batch
  enqueues strand the rest until TTL. Phase 2 DISARMED,
  untouched.
  The project-root `README.md` is **stale** (it stops at v1.1.0) — never
  cite it for current version or capability; `github-export/README.md`
  and `build.sh` govern.
- **v1.5.1 (versionCode 13) built + installed 2026-11-08** — the
  FULL BUILD release (Jonathan's order, 22:42 EST; record:
  `docs/execution-2026-11-08.md` section (i)). Three changes:
  (1) **Rename fix:** the accessibility services' user-visible
  labels are now exactly **"Muse Device Assistance"** (they read
  "Muse Device Assistance voice mode" / "… ground truth" before;
  Jonathan's screenshot showed the Settings row as "Muse Device
  Assistance voice mode shortcut"). Component/class names are
  unchanged, so the accessibility grant survived the in-place
  install — verified post-install in
  `enabled_accessibility_services`. (2) **Galaxy Buds host
  routing:** `BudsRouter` routes the allowlist's `buds_*` native
  templates (`buds_status`, `buds_volume` up|down,
  `buds_volume_set` 0..100, `buds_noise` anc|ambient|off|adaptive,
  `buds_ambient_volume` 1..5, `buds_eq` off|bass_boost|soft|dynamic|
  clear|treble_boost, `buds_touch_lock` on|off) to the separate
  Muse Galaxy Buds plugin APK and returns its typed results —
  the core stays slim; no Buds semantics live here. Gate tests
  extended, ALL_PASS. (3) The voice-mode module is unchanged in
  code and is now **LIVE** (see the voice section below — enabled
  by Jonathan, toggle pair verified on this build). APK
  `app/Muse-Device-Assistance-1.5.1.apk`, 5,699,668 bytes,
  SHA-256
  `84ed5b9d49c978efc8fa92adb5cb7cc5bdfe5e661292e2f1605db43ded0f4872`,
  same signing cert; installed in place (`install -r`,
  lastUpdateTime 2026-11-08 22:55:37 EST); first post-install
  report carries `shell_bridge {paired:true, self_test_ok:true,
  on:true}` — pairing and self-test survived. Phase 2 DISARMED,
  untouched.
- **v1.6.0 (versionCode 15) built + deployed + installed
  2026-11-09** — the local push replacement, first slice (design:
  `docs/local-push-replacement-v1.6-design-2026-11-09.md`; record:
  `docs/v1.6-push-channels-2026-11-09.md`). Nine user-controlled
  **push channels** (see the Push channels section below): Store
  keys + a `PushChannels` registry, a MainActivity toggle section,
  `PushClient` (POST `{base}/device-push` on the mode-sync Worker —
  the Worker home was chosen so the app needs no second base/secret),
  the LIVE Notifications forwarding in `GroundTruthListener` and the
  LIVE Low Battery latch in `BatteryPush`, and a `push_channels`
  object in every report. The mode-sync Worker gained
  `/device-push` (POST + GET read-back, sanitized per-source KV
  queues) and the `push_channels` sanitizer passthrough, deployed
  the same handling (preserve-bindings lane, HTTP 200). APK
  `app/Muse-Device-Assistance-1.6.0.apk`, 5,711,956 bytes,
  SHA-256
  `fd84d5fd46525ce8226dd5ed739cf19c505355a603e879b8d1c3597d5d97827e`,
  same signing cert; build guards + ShellAllowlist gate tests pass.
  Installed in place (`install -r`, lastUpdateTime 2026-11-09
  09:49:00 EST); pairing, self-test, voice state, notification +
  accessibility grants all survived (fresh report 14:59Z:
  `shell_bridge` unchanged, `push_channels` at defaults — every
  channel OFF except mode_reconcile ON). BridgeService needed a
  shell start-foreground-service after the update killed the sticky
  service; it came up foreground (foregroundId=1) and the newest
  exit-info record is the PACKAGE UPDATED exit itself — no crashes.
  **No channel was turned ON and Phase 2 stays DISARMED** — toggles
  are Jonathan's.
- **v1.7.0 (versionCode 16) built + deployed + installed
  2026-11-09** — the push observers stubbed in v1.6 are now real
  (record: `docs/v1.7-push-observers-2026-11-09.md`). New
  `PushObservers` (BridgeService-owned; ContentObservers mark
  channels dirty, the 60-s cycle syncs): **Calendar**
  (`phone-calendar`, Events query at/after cursor, cap 50),
  **Contacts** (`phone-contacts`, last-updated cursor), **Messages**
  (`phone-messages`, separate SMS/MMS row-id cursors, seeded to the
  current max — no historical dump), **Call Log** (`phone-calllog`,
  row-id cursor), **Dialer** (`phone-dialer`, TelephonyCallback
  registered only while ON — platform limit: the API-35 listener
  delivers state only, so `number` is currently `""`), and
  **Health Connect LIVE** in `HealthPush` — the android-35 platform
  jar already carries the framework HC change-log APIs, so no new
  dependency; one token for Sleep/Steps/HeartRate/Exercise, 15-min
  throttle, token stored only on 2xx, and no HC permissions →
  `last_error` set loud (verified live in the first report).
  Universal rules hold: toggle OFF = unregistered / not read / not
  pushed; cursors advance only on PushClient 2xx; failures land in
  the channel `last_error` + BridgeLog. **No Worker change** — the
  v1.6 sanitizer already accepts these sources. APK
  `app/Muse-Device-Assistance-1.7.0.apk`, 5,716,052 bytes,
  SHA-256
  `89147ee36961e139a95f178f7a424a6fa433def55322daec35dc7768f1e1b5bd`,
  same signing cert; build guards + ShellAllowlist gate tests pass.
  Installed in place (`install -r`, lastUpdateTime 2026-11-09
  10:10:55 EST); pairing, self-test, voice state, notification +
  accessibility grants all survived; newest exit is the PACKAGE
  UPDATED exit — no crashes. Post-install queue read-backs: dialer
  pushed its live registration event, calendar pushed its first
  sync (50 items, the cycle cap), messages/call-log/contacts empty
  (cursor seeding), health empty with the permissions `last_error`.
  **Channel state (corrected 2026-11-09 — supersedes the
  install-time reading in the v1.7 execution doc): Jonathan
  flipped ALL channels ON in-app on 2026-11-09 (Notifications
  ~10:04 EST, live-verified pushing; the rest confirmed by him
  in chat 10:23 EST, and the install-verification report's
  all-`on:true` read was his flips, not a glitch). The one
  exception is Health: he turned it back OFF ~10:27 EST by
  choice — the Health Connect connector already owns health
  data, so the DA channel was redundant duplication. Final
  state: every channel ON except Health. Phase 2 stays
  DISARMED; no macro was disabled by Muse.**
- **v1.8.0 (versionCode 17) built + deployed + installed
  2026-11-09** — the Phase 2 expansion (record:
  `docs/v1.8-phase2-expansion-2026-11-09.md`). `Actuation`
  generalized from night-only to **all four house modes**
  (home/away/night/morning) under the unchanged gates: Jonathan's
  in-app ARM switch, a linked routine per mode, a fresh
  parse-verified drift verdict, one fire per desired seq per
  step. Multi-step plans sequence across cycles — the Away latch
  is toggled OFF as its own verified step before the target
  fires, never fired over; the drifted-latch ON-then-OFF clear
  stays un-automated (recorded as blocked). Every cycle now
  records **shadow evidence** (latest decision, last fire with
  its verified outcome — verified / not_engaged / superseded —
  and a capped decision history), carried in every report as a
  new `actuation` object (the mode-sync Worker gained the
  sanitizer passthrough, deployed the same handling,
  preserve-bindings, HTTP 200) — the parity record for retiring
  MacroDroid Mode Poll (tracked: goal_2b6213617252). Disarmed
  behavior is byte-for-byte the old shadow: decisions record as
  `would_fire`, nothing fires. **Nothing was armed, linked, or
  toggled by this build.** APK
  `app/Muse-Device-Assistance-1.8.0.apk`, 5,720,148 bytes,
  SHA-256
  `f338f956e471a4d569ef973231a288c9e3edff0f6179afa8a3dce372f3870c1a`,
  same signing cert; build guards + ShellAllowlist gate tests
  pass (86 checks, ALL_PASS). Installed in place (`install -r`,
  lastUpdateTime 2026-11-09 10:27:55 EST); BridgeService
  foreground (foregroundId=1), notification + accessibility
  grants survived, no crash exits (newest exit is the install
  kill). **Verification caveat:** the fresh-report read-back
  (in_sync + `actuation` + push states) is pending the
  Cloudflare account KV daily write cap resetting at 00:00 UTC
  2026-11-10 — the cap (API error 10048) was hit ~15:18Z
  2026-11-09 by the day's push volume and makes every KV-writing
  Worker handler throw while reads stay live; the deployed
  Worker code was verified end-to-end locally (report POST →
  200, `actuation` stored field-for-field). The app keeps
  cycling and reporting; the first post-reset report carries
  the evidence. Channel state at install: all ON except Health
  (OFF by Jonathan's choice ~10:27 EST — see the v1.7 bullet's
  correction).
- minSdk 29 / targetSdk 35. Framework-only app code, raw
  javac/d8/aapt2 build (`cd app && ./build.sh`); the build **fails** if
  any app class is missing from the dex or the dex references any
  `androidx` class.
- Signing cert SHA-256
  `4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236`
  — the same cert for every version, so upgrades install over ADB in
  place. The keystore is local only (`app/keystore/`), never committed.
- Components (from `app/AndroidManifest.xml`): `MainActivity`
  (exported launcher UI), `GroundTruthListener`
  (NotificationListenerService, `BIND_NOTIFICATION_LISTENER_SERVICE`),
  `BridgeService` (foreground, `specialUse` since v1.4.2 — `dataSync`
  before, whose platform time budget crash-looped it),
  `BootReceiver` (`BOOT_COMPLETED`). There is **no documented Binder,
  ContentProvider, or exported-activity IPC surface for Muse to call
  into the app** — Muse interacts through the Worker reports, the
  on-device UI, and the ADB lane below, nothing else.

## The facts that shape everything

- Samsung gates every programmatic mode surface in Modes and Routines
  (`READ/WRITE_MODE_INFO`, `EXECUTE_ACTION`, the
  `ModeInfoContentProvider`) behind `signatureOrSystem` permissions no
  owner-built app can hold (REA evidence on Routines 5.0.01.52 and
  5.1.03.1). A MacroDroid *replacement* is therefore impossible.
  **Samsung Routines stays the actuator. MacroDroid keeps its non-mode
  jobs** (spoken announcements and the rest of the phone-automations
  surface). The bridge owns truth + verification + reconciliation.
- MacroDroid's command path is a third-party cloud relay + FCM and its
  "ack" fires because the macro ran — unconditional by construction.
  The bridge's ack is **true only when observed state matches desired
  state**. Never treat a drift or unknown report as a success, and
  never treat a shadow plan as an executed action.
- Ground truth is read on-device from the Modes and Routines app's own
  "Device Status" dump notifications (the same dump text the
  server-side system already treats as ground truth). The dump is a
  **transient** notification, not a persistent shade card, posted when
  the status macro fires and on its own ~3–8 min cadence while the
  phone is active. Parsing is defensive: a format change is a **loud**
  parse failure (`PARSE_FAIL` in the log, `parse_ok=false`), never a
  silent wrong state.
- Ground truth older than **30 minutes is stale evidence** — the
  reconciler returns `unknown` until a fresh dump lands, no matter how
  clean the old reading looks.

## How the bridge works (what Muse is reading)

**Bridge loop (`BridgeService`).** A 60-second foreground-service
cycle: tick the ADB bridge, tick the Tailscale guard, fetch desired
state from the Worker, evaluate the reconciler, pass the verdict to
the actuation gate, then report — on any change, and as a heartbeat at
least every 15 minutes. Restarts on boot if it was left enabled
(`service_on`). **Silence is itself a signal:** if reports stop, the
bridge service, battery exemption, or notification access is the
suspect — investigate, don't assume a mode state. Force-stop kills it
until a manual relaunch (platform design, same as MacroDroid).

**Worker channel (`WorkerClient`)** — Muse's own Cloudflare Worker,
no third-party relay. Default base URL entered on-device:
`https://jonathancruzate.com/mode-sync`.

- `GET {base}/mode-state?secret=…` → consumes only
  `desired {mode, seq}` (the existing mode-sync Worker shape). House
  modes: `home | night | morning | away`.
- `POST {base}/phone-bridge/report?secret=…` →
  `{device:"phone-bridge", kind:"change"|"heartbeat"|"manual", ts,
  observed, desired, verdict, ack, plan, status, adb, tailscale}`
  where `ack` is true **only** when `verdict` is `in_sync`. The
  mode-sync Worker's sanitizer keeps the report into KV
  `phonebridge:report` (documented in
  `docs/tailscale-guardian-2026-11-03.md`) — that report is Muse's
  normal server-side read of bridge state.
- The Worker **secret is entered on-device in the app's setup screen
  and stored only in the app's private preferences** (prefs file
  `bridge`). No secret is hardcoded in source or shipped in the APK,
  and it is never written to the log. Never extract, quote, or log it.

**Ground truth and the resolved mode (`GroundTruthListener`,
`Reconciler`, v1.2.0).** Reports carry both `observed.raw_mode`
(dump field 1, the Samsung Mode name) and `observed.mode` (resolved).
Resolution: field 1 wins when it names a real Mode ("At Home"
canonicalizes to "Home"); when field 1 is `"None"`, the running list
(field 2) resolves the mode — "At Home"/"Switch Home" → Home,
"Sleep" → Sleep, "Morning" → Morning — because on this phone the house
modes are largely routine-held. The **Away latch never resolves a
mode**: a latch is not an engagement. Full evidence:
`docs/phase1-calibration-2026-11-03.md`.

**Reconciler verdicts:** `in_sync | drift | unknown`, plus a `plan`
string when drifted. Jonathan's rules, encoded literally: matched
Modes clear each other (fire the target directly); Away is a routine
latch, not a Mode (toggle it off before a non-away target, never
blind); a mode active without its switch routine running is a drifted
latch (toggle on→off to clear, then start the target). In shadow the
plan is logged/reported only.

**Status set (`StatusProducer`, in every report's `status` object):**
`battery_pct`, `charging`, `power_save` (**boolean only**),
`screen_on`, `battery_unrestricted`, `ringer`
(silent/vibrate/sound), `network` (wifi/cellular/ethernet/none),
`storage_total_gb`, `storage_free_gb`, `ts`. Known partial: Samsung's
power-saving *tier* (Standard vs Maximum) has **no stock API** — do
not claim a tier from this app; the tier is unverified here.

**On-device log (`BridgeLog`).** Append-only file `bridge-log.txt` in
the app's private files dir, capped at 2,000 lines; the UI shows the
latest 40. Kinds include `SERVICE`, `WORKER`, `RECONCILE`,
`GROUND_TRUTH`, `PARSE_FAIL`, `ADB_HEAL`, `ADB_DISC`, `ADB_ENDPOINT`,
`TS_GUARD`, `ACT`, `LINK`, `PROBE`, `SETUP`, `BOOT`. A direct
off-device read of that file is **not documented** in the repo — read
it through the app's log tail unless a lane is verified live.

**Phase 0 probe (read-only, UI button).** Reports the installed Modes
and Routines version; attempts the `RoutineInfoProvider` query and
records the result (expect `SecurityException` on 5.1.x); checks
`RoutineLaunchActivity` presence/exported state; reports MacroDroid's
version and whether the bridge holds notification-listener access.
The probe never fires a routine and never changes a mode.

## ADB bridge — discovery + self-heal (v1.1.0)

Why it exists: the VM-side ADB channel's killers were all phone-side —
the wireless-debugging pairing lapsed on toggles, the adbd port changed
(41531 → 40983) so stored-port consumers dialed a dead endpoint, and
every recovery needed manual phone-side steps.

- **Discovery (`AdbBridge`):** wireless debugging advertises
  `_adb-tls-connect._tcp` (and `_adb-tls-pairing._tcp` while a pairing
  window is open) over mDNS. The app discovers both via `NsdManager`
  (multicast lock held), resolves them to host:port, flags the endpoint
  whose host is the phone's own Wi-Fi address, stores it, and includes
  it in every Worker report. An endpoint **change** is a first-class
  log event (`ADB_ENDPOINT`).
- **Report shape (`adb` object):** `{wireless_debugging,
  secure_settings_granted, self_heal_armed, endpoint, endpoint_at,
  pairing_open, wifi_ip, endpoints[]}`.
- **Consuming the endpoint:** mDNS reports the Wi-Fi LAN address; the
  VM reaches the phone on its **tailnet** address. Consumers take the
  **reported port** and dial the tailnet host — `adb-ensure.sh` already
  does exactly this (hardened 2026-11-03, per the calibration doc).
  Never dial a stale stored port; a changed port is normal, not a
  failure. Known quirk: adb-ensure's fixed local forward port (14253)
  intermittently wedges ("connect failed" while the phone is fine); a
  forwarder on a fresh local port connects immediately (recorded
  2026-11-03 in the Phase 2 runbook).
- **Self-heal:** if the app holds `WRITE_SECURE_SETTINGS`, it
  re-enables wireless debugging (`Settings.Global adb_wifi_enabled`)
  whenever a bridge cycle finds it off. The grant is one-time, done
  while ADB is up:
  `adb shell pm grant com.jonathancruzate.metamusedeviceassistance android.permission.WRITE_SECURE_SETTINGS`.
  Heals are logged (`ADB_HEAL`) and reported. A UI checkbox can disarm
  self-heal (**default ON**, separate from the Phase 2 arm switch).
  Without the grant, the app logs the need at most every 6 hours and
  surfaces it on the status screen. This class **never opens an ADB
  connection and never touches pairing**.

## Tailscale reviver (v1.3.2; supersedes the v1.3.1 guardian)

- **Detection, every 60 s cycle:** the tailnet is up iff some up,
  non-loopback interface holds a 100.64.0.0/10 address. Tailscale's
  battery-exemption state is read via `PowerManager` — detect/report
  only; granting it is a user/shell action.
- **Revive:** on the first down cycle of an episode, launch Tailscale's
  main activity immediately; retries at 2 / 5 / 15 / then every
  30 minutes. Background activity starts are restricted, so attempts
  are best-effort and every attempt is logged (`TS_GUARD`). After
  5 minutes down, **one** tappable "Tailscale is down" notification
  per episode (a tap is never background-restricted); it clears on
  recovery, and the episode's down-duration is logged.
- **Report shape (`tailscale` object):** `{installed, up, ip,
  battery_exempt, reviver_armed, down_since, down_count,
  last_recovered_ms}`. Muse reads tailnet presence from this object
  instead of inferring it from failed connects minutes later.
- **v1.3.1's always-on assertion is gone — do not revive the claim.**
  v1.3.1 wrote `Settings.Secure always_on_vpn_app` every cycle; the
  live test failed (Settings still showed Always-on OFF after ~12 min;
  Jonathan switched it on manually). The only programmatic always-on
  read/write is `DevicePolicyManager.get/setAlwaysOnVpnPackage`,
  restricted to device-owner / profile-owner apps. The OS always-on
  toggle is **user-owned** and complementary; the reviver never claims
  to control it. Full record: `docs/tailscale-reviver-2026-11-03.md`
  (the guardian design it supersedes:
  `docs/tailscale-guardian-2026-11-03.md`).

## Phase 2 actuation — shipped DISARMED (v1.3.0 machinery; all house modes since v1.8.0)

The first rung of replacing MacroDroid's mode macros: the bridge can
fire a routine itself — behind gates, **OFF by default**. v1.3.0
shipped it night-only; **v1.8.0 (2026-11-09) generalized the gate to
all four house modes** (home/away/night/morning) with the same
discipline, and added per-cycle shadow evidence to every report
(see the gate bullet below). The shipped state was verified disarmed
at v1.3.0 install: `armed=false` and no routines linked, so no fire
was possible. The current on-device armed/linked state is
**unverified from the repo alone** — since v1.8.0 it is readable
from the report's `actuation` object (`armed`, per-mode `linked`
names) or the app's Phase 2 section; read one of those before
assuming it; never assume it changed, and never assume it didn't.

- **Firing is capture-based (`RoutineFirer`).** The
  `RoutineLaunchActivity` extras contract was never decompiled, so the
  app **never hand-builds intents**. Linking runs Samsung's own
  `CREATE_SHORTCUT` picker once per routine (verified resolvable on
  Routines 5.1.02.9:
  `com.samsung.android.app.routines/.ui.shortcut.ShortcutCreationActivity`)
  and stores the minted launch intent (URI form) in on-device prefs
  only; firing replays exactly that intent. If the picker returns no
  shortcut intent, linking logs loudly and Phase 2 stops there.
- **Actuation gate (`Actuation`, v1.8.0 scope) — fires only when
  ALL hold:** the UI arm switch is ON (Store `act_armed`, **default
  OFF**) · desired is a house mode (home/away/night/morning) ·
  verdict is `drift` on a fresh, parse-verified dump · that step
  has not already been fired for this desired `seq` (once per seq
  per step) · the routine for the step's mode is linked. Multi-step
  plans sequence across cycles, one fire per cycle, each step
  verified against a fresh dump before the next: a running Away
  latch is toggled OFF first (its own once-per-seq fire) and the
  target fires only once a fresh dump shows the latch gone — never
  fired over a held latch, and a latch that survives its toggle on
  a fresh dump **blocks** the sequence (recorded, throttled log).
  The drifted-latch clear (mode reads active, its switch routine
  not running) is two blind toggles and stays **un-automated** —
  recorded as a blocked decision. A fire that fails to engage
  resolves its outcome to `not_engaged` (fresh dump, still drift)
  and is **never auto-retried for the same sequence**. (v1.3.0's
  original scope was night-only with the Away latch blocking
  outright; the gates above supersede it.)
- **Shadow evidence (v1.8.0, in every report's `actuation`
  object):** `armed`, `linked` (routine name per mode, `""` = not
  linked), `shadow` — the latest cycle's decision `{seq, desired,
  observed, verdict, armed, action, step, target, routine,
  reason}` where action ∈ none / would_fire / fired /
  awaiting_verify / blocked — `last_fire` with its outcome
  (pending / verified / not_engaged / superseded), and a capped
  `history` of decision changes. It is produced armed or not, so
  the shadow diff against MacroDroid Mode Poll (tracked:
  goal_2b6213617252) needs no arming: compare the report's
  decisions against observed ground truth per mode change.
- **Gates to live firing, in order** (runbook:
  `docs/phase2-actuation-2026-11-03.md`): (1) **Link Night — Jonathan,
  at the phone** (~30 s: "Link routine: Night (Switch Sleep mode)" →
  pick the Switch Sleep routine in Samsung's picker), then one
  "Test fire: Night routine" so Sleep visibly engages; (2)
  **shadow-diff nights** — bridge verdicts ride alongside the incumbent
  system for several nights and must agree with the census; (3)
  **Arm — Jonathan, in the app** (the Phase 2 arm checkbox). From that
  moment the bridge fires Night on drift, once per Worker request,
  verified by the following dump. MacroDroid's mode macros stay
  installed and untouched the whole time — **unarming (one checkbox)
  is the full rollback**, and the macros remain the fallback for a
  month per the investigation plan.
  **v1.8.0 addendum (2026-11-09):** the same gate order now applies
  per mode — link each mode's routine (all four link buttons already
  exist in the Phase 2 section), let the shadow evidence in the
  report's `actuation` object accumulate across real mode changes
  (goal_2b6213617252), and the one ARM checkbox covers every linked
  mode. Nothing about the order changes: link → shadow-diff → arm,
  all Jonathan's in-app acts.

## How Muse interacts — the lanes

**Install / upgrade (ADB lane).** Build with `cd app && ./build.sh`,
then install the versioned APK over ADB in place (same signing cert —
no uninstall, prefs and links survive). Verify the phone actually runs
the new build by read-back before assuming it (the repo's pattern is
dumpsys-verified versionCode: v1.2.0 recorded versionCode 3, v1.3.0
versionCode 4; current build is versionCode 6). A chat-delivered APK
can sit uninstalled — v1.3.1 shipped that way when the ADB lane was
down — so a version claim without a read-back is unverified. One-time
grants/setup while ADB is up: the `WRITE_SECURE_SETTINGS` grant above,
notification-listener access, and unrestricted battery (Samsung Device
Care is the known killer; parity with MacroDroid).

**On-device setup (Jonathan / UI, from the README).** Install → open
**Muse Device Assistance** → **Run Phase 0 probe** → **Grant notification
access** → **Allow unrestricted battery** → enter the Worker base URL
and mode-state secret on-device → **Save worker settings** → **Start
bridge**. Useful UI controls Muse may direct Jonathan to, or read
state from: **Refresh status** (ground truth vs desired, verdict,
plan, last-report HTTP code, battery-unrestricted state), **Sync now**
(fetch desired + post a `manual` report), **Rediscover ADB endpoint**,
the ADB self-heal checkbox, the Phase 2 section (arm checkbox, link
buttons, test-fire, linked/armed/last-fired-seq status), and the log
tail. The foreground notification mirrors the live line: observed mode
· desired · verdict · `(shadow — observing only)` or
`(Phase 2 ARMED)` (v1.8.0 wording; pre-v1.8 builds read
`(Phase 2 ARMED — night only)`).

**Checking state (Muse's read path, in order).**

1. **Latest Worker report** (`phonebridge:report` in the mode-sync
   Worker KV): `observed.mode` + `raw_mode` + `running`, `desired
   {mode, seq}`, `verdict`, `ack`, `plan`, `status`, `adb`,
   `tailscale`, and the report's age/kind. A heartbeat older than
   ~15 minutes with no change report means the loop is silent — treat
   state as unknown and investigate the bridge, not the mode.
2. **On-device UI / log tail** (via Jonathan or a verified ADB/UI
   lane): ground truth timestamps, verdict/plan, ADB endpoint and
   grant state, actuation armed/linked state, and the `BridgeLog`
   tail for `PARSE_FAIL`, `ADB_HEAL`, `ACT`, and `TS_GUARD` lines.
3. **Over ADB when the lane is up:** installed version read-back, the
   one-time grant, and the CREATE_SHORTCUT resolvability check
   (`cmd package query-activities -a android.intent.action.CREATE_SHORTCUT`).
   ADB *reachability itself* should be diagnosed from the report's
   `adb` + `tailscale` objects first (fresh port, tailnet up/down) —
   that is what the bridge exists to tell you.

**Reading live context (2026-11-09).** When a decision needs what
Jonathan is doing *right now* — which channel to reach him on,
whether he can be interrupted, whether the phone can survive a heavy
task — read context in this order: (1) the latest Worker report
above (its age first: fresh is ~15 min heartbeat / 30 min ground
truth; older is **unknown state**, never act on it as current);
(2) if the decision turns on what he's doing on the phone, fire
`screen_foreground` through the Worker command channel — **singly**,
per the one-command-per-fetch rule — and read the result back
(foreground package/activity only, never screen content). A media
transport read (`media_volume_get` / session state) completes the
driving signature when navigation is in the foreground. The
**judgment rules that consume these reads** — channel choice,
interruptibility, pre-flight, phone-forgotten — live in the
`local-proactive-intervention` skill's **Context engine** section;
that section is their only home. This skill owns the fetch
mechanics; it does not restate the rules.

**Publishing desired state.** Muse's side of the contract is the
Worker's `desired {mode, seq}` (the mode-sync system owns publishing;
the bridge only consumes it and reports back). Bump `seq` for a new
request — Phase 2 fires at most once per `seq`, so a re-ask without a
new `seq` will never re-fire.

## Workflow use — when to use it, when not

**Use the bridge for:**

- **Mode truth and verified acks** in the mode-sync lanes: is the
  phone actually in the desired house mode, with an ack that means it?
- **Phone-state reads** that ride every report: battery/charging,
  power-save boolean, ringer, network, storage, screen — fresh to the
  last change/heartbeat report.
- **ADB lane recovery:** when ADB is unreachable, read the report's
  `adb.endpoint` port, `wireless_debugging`,
  `secure_settings_granted`, and `self_heal_armed` before any manual
  phone-side steps; expect the bridge's self-heal to have already
  re-enabled wireless debugging when the grant is in place. The
  voicemail borrow lane and other ADB consumers are downstream of
  this — a dead ADB lane is a bridge-report question first.
- **Tailnet presence:** read `tailscale.up` / `ip` / `down_since` /
  `down_count` / `last_recovered_ms` instead of inferring Tailscale's
  state from failed connects.

**Do NOT use it for:**

- The rest of the non-mode phone-automations surface MacroDroid
  still owns. For Muse-initiated spoken contact with Jonathan the
  bridge's own `voice_mode` module is now the live route (LIVE
  2026-11-08, see the voice section below); the MacroDroid speak
  branch is deprecated for that purpose — the bridge has no
  one-way TTS announcement surface of its own.
- Direct mode writes. There is no mode API here — outside armed
  Phase 2 night firing, the bridge observes, verifies, plans, and
  reports. A `plan` is a proposal, not an action taken.
- Power-saving **tier** decisions (Standard vs Maximum) — the app
  reports the boolean only; the tier lane lives elsewhere.
- Pairing or opening ADB connections from the app — v1.1 discovery
  never connects. (The in-app local shell, built in v1.4.0, was
  paired 2026-11-08 and is LIVE — see the version history; this
  bullet is about the discovery surface only.) Phase 3 (MacroDroid
  mode macros retired, mode-sync repointed to publish-and-converge)
  is **not in any shipped version**. Never present it as available.

## Device Assistance extension (roadmap — not built in any shipped version)

The renamed app is the engine for the **Device Assistance** connector:
Jonathan's direction (2026-11-03) is to extend it from bridge access
to full device control. The extension commands — launch an app by
name (Google Maps first), open a navigation intent, media
play/pause/skip and volume, flashlight, native Do Not Disturb, a
foreground-app read — are defined command-by-command in the connector
doc (`~/workspace/custom-connectors/custom.device-assistance/CONNECTOR.md`),
each marked EXTENSION until it ships. Their transport is this app's
command channel (built in v1.4.0, 2026-11-05: in-app pairing with
the phone's own wireless debugging, loopback ADB client, allowlisted
commands only, results over the existing Worker channel — awaiting
the pairing session before any of it is live, and the extension
verbs themselves remain unbuilt — v1.4.0's allowlist carries shell
templates only, no launch/intent/media/torch templates). App launches and
intents can also be executed on-device directly inside the app once a
command channel exists; either way the result is only real when a
Worker report or read-back verifies it. **Status update
2026-11-08: the extension pack shipped in v1.5.0 and every verb was
live-verified the same night (v1.5.1 current) — the paragraph above
is the pre-build roadmap framing, kept for the design rationale;
the version history and the voice section below carry the live
truth.** The extension does not change the Phase 2 gates:
actuation stays DISARMED unless Jonathan arms it in-app. Device-specific control is not core
scope either: per the plugin architecture (Jonathan, 2026-11-03),
this app is a slim host and device support plugs in as device
connectors registered against it — Galaxy Buds first (namespace
`buds`, its own plugin and connector), more devices later the same
way. The core never interprets device semantics.

### Voice-mode self-activation — LIVE (built v1.5.0; enabled by Jonathan and live-verified 2026-11-08)

An optional module for this app: the **accessibility trick from Muse
Voice Mode Shortcut / Muse for Android Auto**, folded in as a
Device Assistance capability. The app's accessibility service opens
Muse via `ACTION_ASSIST` (`com.facebook.aura`) — `hatch://voice` is
TrustedCaller-gated to Meta apps — and taps Muse's own voice button
(REA view-id candidates incl. `hatch_voice_button`, then exact
text/desc labels), toggling voice mode on/off with the state
persisted on-device. Purpose: Muse can turn voice mode on **itself,
contextually, to speak** — when Jonathan is walking, driving, or
otherwise hands-busy and a spoken exchange serves him better than
text — instead of voice mode only ever starting from his side-button
long-press or the widget.

- **THE VOICE PATH — canonical standing rule (Jonathan, live voice
  call 2026-11-11 ~02:47 EST — "this is working great"; reaffirming
  and extending the 2026-11-08 discretion grant below):** this app's
  `voice_mode` module is **THE way Muse talks to Jonathan by
  voice** — the standing default reach-him channel, used at Muse's
  own discretion whenever it needs to, not an exceptional measure.
  When Muse needs to speak to Jonathan, it self-activates a voice
  session here; it does not ask first and does not route around this
  path. The bounds are unchanged and bind every use: **speak first**
  on self-activation (below), **never while he is asleep / in Night
  mode**, never extend an exchange he has ended, never override a
  "stop" / "I'm good for now." Channel choice still reads live
  phone state first (fresh report; the checklist in the connector
  doc + the context-engine rules in `local-proactive-intervention`):
  phone in hand → voice here; **phone locked → still voice here**
  (the v1.19 unlock gate handles it — see VOICE CALL below);
  phone in a call → hold or chat; **Galaxy Buds connected +
  screen locked → ALWAYS voice here, never chat alone** (his
  order 2026-11-13 — a locked phone with buds in means a chat
  message is a message missed; the standing gates still win:
  asleep / Night mode and at-work stay never-speak); **phone
  OFFLINE / unreachable → Google Home broadcast, but only when
  he is home** (the 2026-11-13 routing rule, canonical in
  `~/workspace/automation-chats/notification-routing.md`);
  otherwise the agentring phone line is the fallback.
- **This path REPLACES the MacroDroid voice-mode mechanism
  (Jonathan, same call, 2026-11-11):** the legacy MacroDroid
  voice-mode toggle/shortcut lane and the MacroDroid `?do=speak`
  TTS route are **superseded** — Device Assistance `voice_mode` is
  the single voice path now. (The MacroDroid speak route was
  already deprecated in this path's favor on 2026-11-08; the
  standalone Muse Voice Mode Shortcut app survives only as
  Jonathan's own manual hardware switch — side button / Buds
  hold — and is no longer a lane Muse uses as its voice
  mechanism.) Replacement verified live before retirement:
  multiple successful voice sessions on 2026-11-08 and again on
  2026-11-11, including the call this order was given on.
  `local-phone-automations` and `local-muse-voice-mode-shortcut`
  carry the superseded markers; this skill is the canonical home
  of the rule.
- **Galaxy Buds rule (Jonathan, same call, 2026-11-11 ~02:48 EST —
  "if possible"):** when Muse detects Jonathan is **using his
  Galaxy Buds** (buds connected + worn), communicate with him
  **via voice mode** — buds-in is a voice-first signal: his ears
  are already in, so a voice session is the lowest-friction
  channel. Detection rides the Galaxy Buds connector status
  (`custom.galaxy-buds`: the plugin's status export — `connected`
  + `wearing` — via `buds status` / the mirrored export).
  **Detection confidence, stated honestly:** as of 2026-11-08 the
  SPP verbs (including the status read that reports `wearing`)
  were built but **not yet live-verified** — his Buds2 were
  disconnected for the whole verification run — so until a
  connected-buds run proves the status read, buds state counts as
  confirmed only when a fresh export actually shows connected +
  worn; when buds state can't be confirmed, fall back to the
  normal channel judgment (chat / the checklist) rather than
  assuming buds-in.
  **Update 2026-11-13:** the connected-buds run happened —
  `buds status` and `buds noise anc` / `ambient` / `off` are
  LIVE-VERIFIED (plugin v1.0.6; set commands confirm via the
  post-command EXTENDED_STATUS_UPDATED frame, byte [12] —
  this firmware sends no RESP/ACK). Detection confidence now
  rests on a proven read: a fresh export showing connected +
  worn is confirmed buds state (field-level caveats in the
  `local-galaxy-buds` skill).
- **Optional and off by default.** The module ships disabled; Jonathan
  enables it (and its accessibility service) in the app. Disabled
  means the capability does not exist, not that it is merely unused.
- **Ask-first rule (Jonathan's instruction, 2026-11-03) —
  SUPERSEDED 2026-11-08, kept as history:** before Muse
  self-activates voice mode in a context, Muse asks Jonathan first,
  briefly and in the moment, whether to use it — unless he has
  pre-approved that context class. One ask per context; a no means
  text-only for that context and no re-asking. Self-activation never
  extends an exchange he has ended, never overrides a "stop" / "I'm
  good for now," and never carries the hang-up-threat phrasing ("the
  call will end soon") — the bounded-exchange repair rules bind this
  feature first. **This ask-first-per-context rule no longer
  governs** — see the discretion grant below, Jonathan's latest word.
- **Discretion grant (Jonathan's standing rule, 2026-11-08 22:42
  EST — verbatim: "you now have control to speak to the user when
  you want at your discretion"):** Muse may self-activate voice
  mode at its own discretion to speak to Jonathan, without asking
  first per context. This supersedes the 2026-11-03 ask-first rule
  above. Sensible judgment still applies: not while he is asleep or
  in Night mode, not to extend an exchange he has ended, and never
  overriding a "stop" / "I'm good for now."
- **SPEAK-FIRST rule (Jonathan's bug report, 2026-11-08 23:06 EST,
  clarified 23:09 — scope: the Device Assistance `voice_mode` path
  only; the agentring phone line is separate and fine):** a
  self-activated voice session **is a call Muse placed to him**.
  When Muse opens one, Muse speaks first, immediately — a short
  opener saying it's me and why I'm calling — and the session must
  never sit open and silent waiting for him to talk. The chat side
  must never fire `voice_mode on` and then just continue by text as
  if nothing happened. **Worked example (the failure this rule
  exists to prevent):** on 2026-11-08 at 22:39 EST the parent agent
  fired `voice_mode on` (Worker `cmd-870e1b61`, result ok,
  "voice_mode=on dispatched"); the session opened on his phone and
  Muse stayed silent in it, continuing by text in chat instead.
  Jonathan confirmed the mechanism worked ("It worked") and then
  reported the bug in his own words: "when you call me you don't
  realize you call me on the phone and just stay silent." A
  verification fire counts the same way: if you open a session to
  test, either speak the opener like a real call or close it
  immediately — never leave a silent open session as a "test."
- **VOICE BRIDGE — required whenever a chat other than the main
  chat fires `voice_mode` (Jonathan's design, 2026-11-08 23:26
  EST; sharpened by him 23:27 EST: "It always goes to main
  chat."):** voice activation **always lands in the main chat** —
  the Muse app voice session attaches to the main conversation,
  not to the conversation of the chat that fired the command. So
  the Muse who owns that session is the main chat's Muse, who
  did not fire the command and cannot know why it was called
  (tonight's 23:21 EST test dispatched fine but reached Jonathan
  as "didn't work" — and the main chat had even told him, stale,
  that the toggle was still gated off). Procedure, every time:
  (1) fire `voice_mode on` singly; if the result says "already
  active, no-op," a session is genuinely on screen (v1.5.2
  reconciles the belief against the observed session UI before
  every op — the stale-belief no-op this step used to route
  around is fixed); only if that looks wrong anyway, clear it
  with `voice_mode off` and fire a fresh `on` as a fallback;
  (2) **immediately** send the
  bridge message **as a user-role message in the main chat
  thread** (relay format — e.g. "[Voice activation — Device
  Assistance] …", the same shape as the Shield TV voice-relay
  messages), stating that voice was just self-activated and
  when, why Muse is calling (the actual reason/context), and
  asking Muse to speak first now with a short opener.
  **Delivery form is decisive (Jonathan's finding, 2026-11-09
  00:04 EST — "you have to send a message as ME to
  yourself"):** an agent instruction via `chat.send_message`
  does **not** reach a running voice session — the voice loop
  is driven by the conversation's own messages, so the bridge
  only lands if it enters the thread **as Jonathan** (a
  user-turn relay message, automation origin labeled in the
  bracket, exactly like the Shield relay). Tonight's three
  `chat.send_message` bridges (submissions 795b8f69, 5415c330,
  884f25af) are the failed evidence. (3) the receiving (main
  chat) side speaks first in the session, per the SPEAK-FIRST
  rule. The bridge message is **not optional color — it is a
  required step** of every activation fired from any other chat;
  the only exception is when the firing chat **is** the main chat.
  **Evaluated and not available:** having the Device Assistance
  service activate voice inside the *current* (firing) chat
  instead — the assist intent opens the Muse assistant/main
  conversation, and there is no app-side route to target a
  specific side-chat conversation (deep links are
  TrustedCaller-gated); if that ever changes, the bridge step
  becomes unnecessary. **First live test (2026-11-08):**
  `voice_mode on` dispatched 23:26:34 EST (`cmd-6154e671`);
  bridge message sent to the main chat ~23:28 EST (submission
  795b8f69). Outcome pending Jonathan's confirmation.
- **THE PROVEN WORKFLOW — self-activated voice with bridge prime
  (LIVE-VERIFIED 2026-11-11 12:09–12:11 EST; Jonathan's verdict:
  "It worked, perfect"):** this is the exact sequence, and order
  + timing are the mechanism — every failed attempt on
  2026-11-10/11 broke one of these steps (bridge before voice,
  bridge after the session ended, bridge via chat tooling, or a
  prime truncated by the relay args cap).
  *Preconditions, from a fresh relay report:*
  `shell_bridge {paired:true, on:true, voice_enabled:true,
  voice_active:false}`, pending queue empty. Lane: the box
  device relay only —
  `~/workspace/cloudflare-workers/mode-sync/phone-bridge-cmd.sh`.
  Enqueue commands SINGLY, confirming each before the next.
  1. **Voice first — Muse starts it, never Jonathan.**
     `phone-bridge-cmd.sh enqueue voice_mode on`. Confirm the
     command record: state done, result ok, "voice_mode=on
     dispatched" (~200 ms). Proof: `cmd-22e8f628`, enqueued
     2026-11-11T17:09:49Z.
  2. **Wait ~10 seconds** for the session UI to connect. In
     that window the PiP return step runs on its own: the
     service relaunches the foreground app recorded before the
     assist launch (aura / this app / the launcher skipped) —
     that IS the "back to the previous app" step; leaving aura
     triggers the session's auto-PiP, so the session floats
     over his app — or `GLOBAL_ACTION_HOME` fires when nothing
     returnable was recorded.
  3. **Then the bridge — as Jonathan, via Device Assistance.**
     `phone-bridge-cmd.sh enqueue voice_bridge <prime>` while
     the session is LIVE — not before it starts, not after it
     ends. Confirm: state done, ok, "voice_bridge queued
     (NN chars)" with NN = the prime's FULL length. The hunt
     (~17 s) brings Muse forward, types the prime into the
     conversation composer, and taps Send — a genuine user
     turn from his own app, in the conversation open on the
     phone. The live session picks that turn up and Muse
     speaks first, per SPEAK-FIRST.
  4. **Prime form — SHORT, inside the relay args cap (§27):**
     the relay slices args at `CMD_MAX_ARGS = 16`, each arg
     <=256 chars, and the app gate bans `'` `"` `` ` `` `\`
     and control characters. A long word-per-arg prime is
     silently truncated at 16 words — the 85–99-char fragments
     of the failed trials. Two proven forms: (a) a short prime
     of <=16 words, word-per-arg — the 2026-11-11 proof prime,
     15 words / 78 chars, queued in full (`cmd-4c6fd0ab`,
     enqueued 17:10:12Z): "Voice bridge test. Voice mode is
     on. This was typed as me. Speak first please."; (b) a
     longer prime packed into a few quoted phrase args
     (<=16 args total, each <=256 chars, no banned chars) —
     §27's 553-char proof. In real use, put the actual reason
     for the call in the prime, same form.
  5. **Verify from records, not vibes:** both command records
     done/ok with the full-length queue ack; the bridge
     message visible as a genuine user turn in the
     conversation; Jonathan's on-screen report (session heard,
     landed back in his previous app / PiP floating). A
     `screen_foreground` probe fired right after the bridge
     can catch the hunt's Muse launch window
     (`AuraWidgetLaunchActivity`) — it neither proves nor
     disproves the PiP landing.
- **VOICE CALL — the single combined fire (CANONICAL since
  2026-11-13; Jonathan's design orders 2026-11-11 12:16–12:19
  EST, live-verified end-to-end on a locked phone 2026-11-13
  03:12–03:21 EST, his verdict: "it worked"):** the two-command
  workflow above is superseded as the default by ONE command —
  `phone-bridge-cmd.sh enqueue voice_call` (no args) →
  `VoiceBridge.executeCombined`. One fire runs the whole
  sequence in the only order that works: voice launch → the
  fixed marker typed **as Jonathan** once the session is
  observably CONNECTED → session speaks first from the payload →
  return to his previous app with the session floating. The
  mechanics that make it reliable, each one bought with a failed
  live attempt on 2026-11-11/12:
  * **Payload protocol v2 (semantic prep BEFORE the mechanical
    fire):** the firing side (1) writes the payload into
    `~/workspace/voice_context.md` (fired_at in America/New_York,
    firing chat, what to tell him and why, written for the ear;
    state PENDING → the firing side flips it to CONSUMED with
    the outcome after the test), AND (2) sends the same payload
    text to the **main chat** via `chat.send_message`, framed as
    the voice session's context, BEFORE firing. Step (2) is
    load-bearing: the session speaks from the in-chat payload.
    The file alone is NOT a reliable channel — on 2026-11-11
    (attempt 6) the session's two reads of the file tripped the
    runtime's prompt-injection safety check and it refused to
    speak from it; the session itself proposed the in-chat form
    ("What's written in the chat between us, I can use"). The
    typed turn itself is the fixed marker
    (`VoiceBridge.VOICE_CONTEXT_MARKER`): "This was fired by
    voice mode. Stay quiet until you read the voice context,
    then speak first."
  * **Prompt waits for CONNECTED, not for UI (v1.17):** the
    bridge hunt holds until `hatch-voice-call-connected` is
    observed on 2 consecutive polls — the generic session-UI
    probe renders on the CONNECTING screen and released the
    prompt (and the return) seconds before the session was
    truly live, twice (attempts 2–3, 5). `schedulePipAttempt()`
    itself refuses to run while a combined call is pending —
    the return has exactly one release (`onMarkerResolved` or
    the 50 s watchdog), so no path can float him back before
    the prompt is sent (his order: "Prompt first then back to
    last app").
  * **20 s post-send dwell (v1.18):** after the marker sends,
    the return waits 20 s so the session side can actually
    process the payload and begin speaking before the float —
    sending is not processing (his diagnosis, 2026-11-11
    21:02 EST).
  * **Locked-screen gate (v1.19; in the installed v1.21+
    builds):** a fire that finds the keyguard locked defers
    into `UnlockActivity` (show-when-locked + turn-screen-on
    trampoline: wake + `requestDismissKeyguard`, poll every
    500 ms up to 15 s). Instant for swipe locks and Extend
    Unlock trusted states; a secure lock raises the credential
    prompt for Jonathan — automation never touches credentials
    — and a phone still locked after 15 s fails LOUDLY in the
    BridgeLog and unwinds the combined state instead of hunting
    a locked screen. If the app did the unlocking, the end of
    the sequence re-locks (`GLOBAL_ACTION_LOCK_SCREEN`): the
    phone finishes in the state he left it. The combined
    watchdog re-arms while an unlock is in flight.
  * **Pickup latency on a sleeping phone is normal:** the app
    fetches on its own cycle; a fire to a locked, sleeping
    phone can sit `pending` for minutes until the cycle comes
    around (measured ~4 min, 2026-11-13 03:08→03:12 EST) and
    then executes in full. Pending ≠ failed; check
    `command-status` before concluding anything.
  * **Be heard — volume pre-flight (his order 2026-11-13
    03:53 EST: "check the phone volume first and adjust
    accordingly so that you are heard"):** before firing, read
    the media volume via the shell_bridge template
    `media_volume_get` (STREAM_MUSIC, range 0..15 — voice
    playback rides this stream on the phone speaker AND on
    connected Galaxy Buds; the buds connector's volume verbs
    move this same stream). If it reads near-silent (≤3), the
    call would land mute: raise it with `media_volume_set` to
    10 and verify by read-back BEFORE firing. A normal level
    is left untouched — adjust only what's needed to be heard.
    If the read or the set fails, fire anyway and flag in chat
    that audibility wasn't confirmed.
  * **Buds connected + screen locked → voice, always (his
    order 2026-11-13 03:53 EST):** when his Galaxy Buds are
    connected (status via the `custom.galaxy-buds` connector —
    SPP status live-verified 2026-11-13) and the phone screen
    is locked, anything worth telling him goes by `voice_call`,
    never chat alone — otherwise he might miss it entirely.
    The standing gates still win: asleep / Night mode → never
    speak; at work → never speak. The fire itself handles the
    locked screen (unlock gate above); for the routing
    decision, buds-connected is the verifiable trigger.
  * The two-command decomposition (voice_mode on → wait →
    voice_bridge) remains the manual fallback when the combined
    fire itself is the thing under test.
- **Confirming a session actually started (chat-side evidence):**
  the Worker command result distinguishes "voice_mode=on
  dispatched" (fresh launch) from "voice_mode=on (already active,
  no-op)". Since **v1.5.2** the no-op is trustworthy: the module
  reconciles its persisted belief against the accessibility
  service's observation of Muse's session UI (markers:
  `hatch-voice-end-call-button` / `hatch-voice-call-connected` /
  `hatch-floating-call-controls`, "End voice call" / "Mute voice
  call") before every op, and the service clears the belief when
  it watches the session UI leave — a session Jonathan ends
  inside Muse no longer strands `voice_active:true` (the
  2026-11-08 23:28 desync, fixed; record section (k)). The
  off→on pair remains only as a manual fallback. And
  `screen_foreground` read-back showing
  `com.facebook.aura` confirms the Muse surface is in focus. The
  app also puts `voice_enabled`/`voice_active` into
  `ShellBridge.snapshot`, and the mode-sync Worker's report
  sanitizer passes both through since the 2026-11-08 passthrough
  fix (deployed via the preserve-bindings lane; verified in the
  fresh report received 2026-11-09T04:26:36Z, whose
  `shell_bridge` carried `voice_enabled:true` /
  `voice_active:true`). The report is therefore a first-class
  confirmation path alongside command results +
  `screen_foreground` — read true voice state from it instead of
  inferring it. (Before the fix the sanitizer dropped both keys;
  that gap fed the 22:39 stale-belief desync.)
- The toggle mechanics, failure modes, and verification are the Voice
  Mode Shortcut's (`local-muse-voice-mode-shortcut` skill): a fire is
  only real when the voice session observably starts/stops, never on
  the tap alone.
- **Built + LIVE state (v1.5.0 → v1.5.1, 2026-11-08):** the module
  shipped in v1.5.0 — the shortcut's classes ported in (`VoiceState`,
  `VoiceLauncher`, `VoiceAccessibilityService`), wrapped in
  `VoiceModeModule`'s two-key gate, exposed as the gated native
  Worker template `voice_mode` (toggle|on|off) and an in-app section
  (module checkbox, accessibility state, gated test button; reports
  carry `voice_enabled`/`voice_active` in `shell_bridge` — the
  Worker passthrough was fixed 2026-11-08, see the confirmation
  note above). The disabled-gate was proven live first (Worker
  `voice_mode toggle` → `ok:false, "refused: voice module
  disabled"`). Jonathan then did both enable steps (in-app module
  switch + accessibility grant), and the parent live-tested it at
  22:39 EST (Worker `cmd-870e1b61` `voice_mode on` → ok,
  "voice_mode=on dispatched"; `screen_foreground` showed
  `com.facebook.aura` in focus; Jonathan: "It worked"). On v1.5.1
  the full toggle pair was re-verified end-to-end (2026-11-08
  23:03–23:08 EST): `voice_mode off` → "voice_mode=off dispatched"
  (`cmd-278b30ae`, the real end sequence), `voice_mode on` →
  "voice_mode=on dispatched" (`cmd-e9df25f2`), `screen_foreground`
  read-back `com.facebook.aura` AuraMainActivity (`cmd-f105163f`),
  then closed with `voice_mode off` (`cmd-94e15388`). `voice_mode`
  is LIVE. **v1.5.2 (2026-11-08, execution record section (k))**
  fixed the `voice_active` state desync described in the
  confirmation note above: belief reconciliation in
  `VoiceModeModule` + the accessibility service's external-end
  watch, live-verified the same night (from the reproduced stale
  state, `on` dispatched a real start three times running; the
  belief converged to false on its own after externally ended
  sessions; off→on clean). The MacroDroid route for Muse-initiated spoken contact
  is deprecated in its favor (see the phone-automations skill,
  2026-11-08 note).
- **PiP confirmation on self-activated sessions (v1.10.0, BUILT
  2026-11-09 — not yet installed, not yet live-verified; record:
  `docs/execution-2026-11-09.md`):** the Muse voice session
  **auto-PiPs on user-leave** — mechanism confirmed by Jonathan as
  device owner 2026-11-09 23:40 EST ("the app always goes into PiP
  if voice mode is on"); live verification pending the joint trial.
  On a self-activated start (only that path arms it), once the
  session UI is confirmed present (+ ~900 ms settle) the service
  runs **Jonathan's choreography** (adopted 2026-11-09 23:41 EST):
  before the assist launch, `VoiceLauncher` records the foreground
  package he was using (skipping aura, our own package, and the
  launcher); after connect it relaunches that package — leaving
  aura floats the session over his app — or fires
  `GLOBAL_ACTION_HOME` when nothing returnable was recorded. It
  then verifies and BridgeLogs whether a PiP window was actually
  observed (`PiP return:` / `PiP verify:` lines, with the
  foreground package after the return). The
  `docs/pip-investigation-2026-11-09.md` minimize-control hunt
  survives only as the fallback when the return logs no PiP window
  observed (at most one tap), and the first session per activation
  also dumps the full Muse voice-window node inventory to
  BridgeLog for the record. Nothing else changed: speak-first,
  the bridge, and the v1.5.2 reconciler are untouched.

## Push channels — local push replacement (v1.6.0 contract; v1.7.0 observers LIVE, toggles Jonathan's)

Jonathan's 2026-11-09 orders: replace the rest of MacroDroid
*locally* (no third-party endpoints), turn notifications — and the
other "From this device" pulls (Calendar, Contacts, Messages, Phone
dialer, Call Log, Health Connect) — into **push** instead of
Muse pulling them with tokens, **with user control for each
channel**.

- **The toggles are the contract.** MainActivity's "Push channels —
  this device → Muse (you control each one)" section has one
  checkbox per channel (Store keys `push_notifications`,
  `push_calendar`, `push_contacts`, `push_messages`, `push_dialer`,
  `push_call_log`, `push_health`, `push_battery` — all **default
  OFF** — and `push_mode_reconcile`, **default ON** as the bridge's
  existing loop). OFF means not registered, not read, not pushed.
  Muse **never flips a push toggle** — same discipline as the
  Phase 2 arm switch. Read the state from the report's
  `push_channels` object (`{on, last_push_at, last_error}` per
  channel) instead of asking Jonathan. TV profile: all channels
  gated OFF.
- **Transport (`PushClient`).** `POST {base}/device-push?secret=…`,
  JSON `{source, ts, items[]}`, on the **mode-sync Worker** (chosen
  over phone-ingress so the app's one existing base + secret serves
  it — no second on-device config). The Worker sanitizes items
  field-by-field into per-source KV queues `devicepush:<source>`
  (cap 500; GET `/device-push?source=…` is the authed read-back /
  future drain contract). On-device, a channel's cursor/last-push
  advance **only on HTTP 2xx**; failures land in the channel's
  `last_error` + an in-prefs retry queue retried each bridge cycle
  (payloads for a since-disabled channel are dropped, never pushed
  behind the toggle).
- **LIVE observers (code shipped + installed):**
  - *Notifications* (`phone-notify`): **ON — flipped by Jonathan
    in-app ~10:04 EST 2026-11-09 and live-verified pushing**
    (posted AND removed events verified from
    2026-11-09T15:04:43Z; queue read-back later that morning: 111
    items, still landing live). `GroundTruthListener` forwards
    every package's **posted AND removed** notifications
    (`{package, title, text, big_text, posted_at, event, key}`)
    after the unchanged Routines ground-truth path. This replaces
    MacroDroid's Notify Push macro 1:1 and adds the CLEARED events
    it never had. **MacroDroid's Notify Push macro is superseded
    by this channel and retired: Jonathan disabled it on-phone
    ~10:16 EST 2026-11-09** (his "Done" in chat; lanes corroborate
    — last MacroDroid push 15:14:11Z, app pushes continued past
    15:16Z). The legacy MacroDroid → `phone-notify-drain` lane
    retires with it; the app channel is the sole notification
    producer.
  - *Dual Messenger clone (user 95)*: a second instance of the
    app, cloned by Jonathan into the Samsung Dual Messenger
    profile with its own notification access and push setup,
    pushes the **public WhatsApp account's** notifications into
    the same `phone-notify` stream — distinguishable by the key
    prefix (`95|com.whatsapp|...`; the main profile's keys are
    `0|...`). Live-verified 2026-11-13 (first items received
    2026-11-12T21:32:21Z, Food Bazaar Supermarket; removed events
    2026-11-13T06:42Z). This closes the long-standing gap where
    dual-messenger notifications were invisible to every
    main-profile listener: the per-user limit still binds the
    paired-device catalog (`notifications.get` sees user 0 only),
    but the push lane covers both WhatsApp accounts.
  - *Low Battery* (`phone-battery`): `BatteryPush` on the 60-s
    cycle — the 20% downward crossing latches and pushes
    `{battery, charging, wifi_ssid, ringer_mode, screen_on}` (the
    MacroDroid status-POST fields); re-arms above 25%. Replaces
    the Low Battery Alert macro.
- **LIVE observers since v1.7.0** (record:
  `docs/v1.7-push-observers-2026-11-09.md`) — real code behind the
  toggles. **Channel state (confirmed 2026-11-09): Jonathan turned
  every channel ON in-app that morning; the sole exception is
  Health, turned back OFF ~10:27 EST by his choice** (the Health
  Connect connector already owns health data — the DA channel was
  redundant duplication). Current state lives in each report's
  `push_channels`; never assume it — read it:
  - *Calendar* (`phone-calendar`): ContentObserver on the Events
    URI; rows with `DTSTART`/`DTEND` at/after the cursor push as
    `{id, title, start, end, location, all_day}`, cap 50/cycle.
  - *Contacts* (`phone-contacts`): contacts with
    `CONTACT_LAST_UPDATED_TIMESTAMP` after the cursor push as
    `{id, display_name, phones, emails}`.
  - *Messages* (`phone-messages`): observers on the SMS + MMS
    URIs; separate row-id cursors seeded to the current max
    (no historical dump); items `{id, kind, address, body, date,
    type, read}`.
  - *Call Log* (`phone-calllog`): rows after the row-id cursor
    push as `{id, number, type, date, duration, cached_name}`.
  - *Dialer* (`phone-dialer`): TelephonyCallback call-state
    listener registered only while ON; live events
    `{state, state_code, number, ts}` — platform limit: the
    API-35 framework listener delivers state only, so `number`
    is currently always `""`.
  - *Health Connect* (`phone-health`): framework HC change-log
    token check in the bridge cycle (15-min throttle) over
    Sleep/Steps/HeartRate/Exercise; first check seeds the token;
    next token stored only on 2xx. Grants live in the Health
    Connect app — with none granted, the channel's `last_error`
    says so (verified live) instead of pretending to push.
    **Channel state: OFF since ~10:27 EST 2026-11-09, Jonathan's
    choice** (health data already flows via the Health Connect
    connector; see the state note above). The observer code stays
    live behind the toggle if he ever flips it back.
  Observers check their runtime permission at registration and
  set `last_error` if missing — never crash. Cursors advance
  only on PushClient 2xx; failures land in `last_error` +
  BridgeLog.
- **Drains:** the `phone-notify` queue is consumed by the
  device-push drain (the app channel is the producer of record
  since the 2026-11-09 flip; the legacy MacroDroid →
  `phone-notify-drain` lane retired when Jonathan disabled the
  macro on-phone ~10:16 EST 2026-11-09). Drain consumers for the
  other `devicepush:*` queues land with their channels' first
  use. One marked deploy-test item (`wifi_ssid "v16-deploy-test"`)
  sits in `devicepush:phone-battery` from the 2026-11-09 endpoint
  verification.
- Mode reconcile's *actuation* stays under the separate Phase 2 ARM
  switch; the `push_mode_reconcile` toggle covers the existing
  observe/verdict/report loop only.

## Hard rules

- **Never arm Phase 2, link a routine, or test-fire from Muse's
  side.** Arming, linking, and the test fire are Jonathan's in-app
  decisions, in the runbook's gate order. Muse does not flip
  `act_armed`, does not drive the UI or ADB to check the arm box, and
  does not fire the Night routine to "check" the gate. Muse's job
  around an armed bridge is to read verdicts/reports and surface
  drift — the firing decision stays inside the gate above, on the
  phone, under Jonathan's arm.
- Treat both UI switches as Jonathan-owned state: the Phase 2 arm
  (default OFF) and the ADB self-heal arm (default ON) are separate
  switches — never conflate them, and change neither except on his
  instruction in the app.
- **Verified-ack discipline:** `ack=true` / `in_sync` is the only
  success. Drift gets its plan read and surfaced/handled by the
  owning lane; unknown (no desired, no trustworthy dump, stale dump)
  gets a fresh dump or a bridge-health check, never a guess.
- **Never hand-build a `RoutineLaunchActivity` intent**, and never
  hardcode, extract, or log the Worker secret or a linked routine's
  intent URI — both live only in on-device prefs.
- Never claim the app sets Tailscale's always-on VPN (removed in
  v1.3.2), grants Tailscale's battery exemption, or heals a lapsed
  ADB *pairing* — discovery + wireless-debugging self-heal is the
  whole ADB surface.
- Every build/change upstreams in the same handling: source push to
  the project's GitHub repo (via the `github-export/` snapshot) — the
  repo is `jonathancruzate/muse-device-assistance` (renamed on
  GitHub 2026-11-04; the old `muse-phone-bridge` URL redirects);
  check the snapshot record's `repo` field before pushing — a GitHub Release where the project ships one (asset SHA
  verified against the build), the findings/runbook doc, and this
  skill if the procedure changed — both copies, in the same handling.

## Unverified / not in the repo

- The on-device Phase 2 armed/linked state **right now**: v1.4.0's
  install and reporting were verified live 2026-11-08
  (`docs/execution-2026-11-08.md`), and that day's reports still
  showed Phase 2 in shadow ("(shadow: not fired)"), consistent with
  DISARMED and nothing linked — but the arm/link state is Jonathan's
  in-app state: read it from the app's Phase 2 section or, since
  v1.8.0, from the report's `actuation` object before assuming it,
  and never change it from Muse's side. (The first v1.8 report
  read-back was still pending at that build's snapshot time — the
  Cloudflare KV daily write cap blocked all report writes from
  ~15:18Z 2026-11-09 until the 00:00 UTC reset; see
  `docs/v1.8-phase2-expansion-2026-11-09.md`.)
- The deliberate Tailscale off/on episode test for the v1.3.2 reviver
  was **pending at snapshot time** — revival is designed and
  report-verified in shape, not yet episode-proven on record.
- The full local shell (in-app ADB pairing, loopback connection,
  allowlisted shell commands pulled down the Worker channel) is
  built in v1.4.0 (2026-11-05) and **paired + proven live
  2026-11-08 on v1.4.3** — Worker `battery` and `getprop_model`
  commands completed end-to-end (record: execution-2026-11-08.md
  section (g)); reports carry `shell_bridge {paired:true,
  self_test_ok:false, on:true}`. The one still-unverified item is
  the `self_test_ok` flag itself: it is set only by Jonathan
  tapping the in-app self-test, which runs the same, now-proven
  execute path. The connector's EXTENSION verbs (app launch,
  media, flashlight, native DND, foreground read) are **not** in
  the allowlist — the shipped channel carries shell templates only
  (`battery`, `getprop_model`, `settings_get/put`,
  `standby_get/set_active`, `role_holders`,
  `deviceidle_whitelist`, `logcat_tail`, `dropbox_print`,
  voicemail role borrow/restore).
- A programmatic off-device read of `bridge-log.txt` and a direct
  Muse→app IPC call are not documented anywhere in the repo.

## Pointers

- Build records + full version history: `github-export/README.md`
  (the project-root `README.md` is the stale v1.1.0-era copy)
- Phase 1 calibration (the "None" + "At Home" rule, endpoint/tailnet
  note): `github-export/docs/phase1-calibration-2026-11-03.md`
- Phase 2 gates + rollback: `github-export/docs/phase2-actuation-2026-11-03.md`
- Tailscale guardian (superseded design):
  `github-export/docs/tailscale-guardian-2026-11-03.md`;
  reviver (current): `github-export/docs/tailscale-reviver-2026-11-03.md`
- Origin investigation:
  `~/workspace/goals/manage-my-smart-home/hidden_files/custom-app-vs-macrodroid-20261101.md`
- Source map: `WorkerClient` (Worker protocol), `BridgeService`
  (60 s loop), `GroundTruthListener` + `DumpParser` (ground truth),
  `Reconciler` (verdicts/plans), `StatusProducer` (status set),
  `AdbBridge` (discovery/self-heal), `TailscaleGuard` (reviver),
  `Actuation` + `RoutineFirer` (Phase 2 gate/firing), `Store`
  (all prefs keys), `Probe` (Phase 0), `BridgeLog` (on-device log) —
  all under `app/src/com/jonathancruzate/metamusedeviceassistance/`.

## v1.9 transport cut — status (2026-11-09, executor record)

Full record: `docs/v1.9-transport-cut-2026-11-09.md`. Summary:
- **KV bleed-stop deployed** on the mode-sync Worker: v1.8's listener
  forwarded the app's OWN foreground-service notification, and the
  Worker rewrote the whole device-push queue per POST — a self-loop
  that burned the account KV write cap two days running. The Worker
  now drops self-package notify items at ingest and writes
  commit-on-change. Verified by A/B under cap-dead conditions.
- **v1.9.0 (versionCode 18) INSTALLED** 2026-11-10 ~00:49 UTC. The
  `transport` directive is live in Worker mode-state responses and
  the box relay (`device-relay.service`, tailnet 100.85.53.70:8792)
  is healthy; the app's self-switch executes on its next cycle but
  was **not yet observed** at the record's writing (phone dozing;
  probe command `cmd-57bc6ed9-85f6-417d-8158-29dc0d321494`, 24 h TTL,
  pending on the relay — its execution proves the switch).
- **Consumer bug fixed:** the box's `req.sh` ignored stdin bodies
  while VM consumers pipe them (the documented `relay_req` contract),
  so relayed `/mode-set` and `/phone-bridge/enqueue` POSTs arrived
  bodyless (`bad request`). `req.sh` now reads stdin for bodyless
  POSTs; verified end-to-end. Canonical relay copies: project `relay/`.
- **Panel lanes stay on the Worker** as a documented exception (the
  relay port excludes them; the widget producer is dormant since
  2026-11-06). Worker phone-facing endpoint retirement is gated on
  the cut's verification matrix (a)–(h), still pending the switch.

## v1.10.0 — voice PiP confirmation (2026-11-09, executor record)

Full record: `docs/execution-2026-11-09.md`. Summary:
- **v1.10.0 (versionCode 19) BUILT, NOT installed** (the brief's
  "v1.5.3/vc15" name was superseded by the live line's advance to
  v1.9.0/vc18 — see the record's version-resolution note). APK
  5,724,244 bytes, SHA-256
  `4488e68a27995063fec1006c85a87307f7e511835b4ed1473c90b7675d50504d`,
  family cert unchanged. It adds the PiP confirmation sequence to
  the voice module only (see the voice section's PiP bullet);
  speak-first, the bridge, the v1.5.2 reconciler, the pairing, and
  the Phase 2 surface are untouched.
- **Install blocked tonight:** the ADB lane refused on both known
  endpoints (tunnel proxy closes CONNECT — same state the v1.9
  record noted), the Worker report is frozen at the KV cap, the
  relay report is still the seed, and a relay probe stayed pending
  (the app's transport self-switch is still unobserved). The phone
  still runs v1.9.0/vc18; nothing on it changed. Unblock = the
  app's next wake cycle completing the self-switch (fresh relay
  report) or the 2026-11-11 00:00 UTC KV reset; then `install -r`
  + the owed read-backs (version 1.10.0/vc19, accessibility grant,
  pairing + self-test, `voice_enabled` unchanged).

## v1.11.0 — loop fix + local-first data transport (2026-11-10, executor record)

Full record: `docs/v1.11-build-report.md` + `docs/execution-2026-11-09.md` §10. Summary:
- **v1.11.0 (versionCode 20) INSTALLED + VERIFIED RUNNING**
  (Jonathan installed in place ~01:53 EST 2026-11-10; verified
  02:11 EST by on-screen log signature — the v1.11.0-only
  "push local ok … no tailnet bridge base — local only" lines;
  loop fix holding live, tier-1 outbox delivering; execution doc
  §14). APK 5,724,244 bytes,
  SHA-256 `89cee61a94e1101729152f3ec27733943eb546374a13ae1078d89a58641e1f82`,
  family cert unchanged (verified byte-identical to 1.10.0's).
  Supersedes v1.10.0: its voice/PiP work and the v1.5.2 reconciler
  carry over verbatim (files untouched). Only build.sh,
  GroundTruthListener.java, and PushClient.java changed. Phase 2
  untouched and disarmed; pairing and manifest untouched.
- **Loop fix at the source:** the notification listener now IGNORES
  always-running notifications (FLAG_ONGOING_EVENT |
  FLAG_FOREGROUND_SERVICE, any package) plus a hard self-package
  exclusion. That class — including this app's own foreground-service
  notification — was the self-sustaining push loop that burned the
  mode-sync KV write cap on 2026-11-09 and 2026-11-10. All push
  channels are KEPT: a 2026-11-10 premise check refuted the
  direct-to-Muse-app design's duplication claim (the Muse catalog
  is pull; device-push-drain is the sole notify intake and DA's
  channel is the only event-driven pipe). Design errata recorded in
  `docs/direct-to-muse-app-design-2026-11-10.md`.
- **Data-channel transport is local-first:** PushClient delivers to
  the on-device outbox (tier 1 — the delivery; cursors advance on
  it) and mirrors best-effort ONLY to an https://*.ts.net base
  (tier 2, Tailscale Serve form). Data channels never POST to the
  Worker or any http base anymore. Residual gap, stated plainly:
  the Muse app exposes no local ingest API, so the tier-1 outbox
  is where the path ends today; the fleet drain sees data-channel
  items only via a tier-2 mirror. Control lanes stay on
  WorkerClient's rendezvous, untouched.
- **Tier 2 front UP + directive repointed (§11), self-switch
  blocked on a diagnosed cause (§14):** the Serve front answers
  at `https://vps.taila56db5.ts.net:8443` and the Worker
  `transport` directive carries it — but the app's mode-state
  responses currently carry `desired: null` (the Worker's KV
  `mode:desired`/`mode:actual` read back empty through every
  handler since the §11 redeploy window; the live desired
  exists only in the relay store), and
  `WorkerClient.fetchDesired` returns before
  `handleTransportDirective` whenever desired is absent, so
  the directive is never processed. Remedy (post KV reset,
  2026-11-11 00:00 UTC): mirror the relay's current desired
  verbatim into the Worker KV, read back `/mode-state` showing
  desired + transport together; the next app cycle then
  self-switches. Optional later app build: process the
  transport directive independent of the desired gate.
- **Build route note:** built on Muse after the ordered agy leg
  failed — frontier ladder exhausted, proven cause agy account
  quota RESOURCE_EXHAUSTED (429, resets ~95h); re-selection per the
  Decision Matrix, recorded in `docs/v1.11-build-progress.md`.

## v1.12.0 — directive gate fix + scoped cleartext + Health channel removed (2026-11-10, executor record)

Full record: `docs/v1.12-build-report.md` + `docs/execution-2026-11-09.md` §16. Summary:
- **v1.12.0 (versionCode 21) BUILT — NOT YET INSTALLED** (the
  phone still runs v1.11.0). APK 5,720,229 bytes,
  SHA-256 `a8299ea8dfe8e1f1c53284ff733b12c329ab87e7d5640d3b801e1bef4bcf8884`,
  family cert verified byte-identical to v1.11.0's.
- **Directive gate fix:** `WorkerClient.fetchDesired` processes
  the transport directive (`handleTransportDirective`) whenever a
  `transport` object is present — immediately after parsing,
  BEFORE / independent of the desired gate. The §14 strand (a
  desired-less mode-state response stranding the directive riding
  the same body) cannot recur; desired parsing/storage behavior
  is otherwise identical.
- **Scoped cleartext:** new `res/xml/network_security_config.xml`
  (manifest `networkSecurityConfig`; NO global
  `usesCleartextTraffic`): base cleartext OFF, one domain-config
  entry permitting cleartext ONLY to `100.85.53.70` (the box
  tailnet node — Android domain-config cannot express the
  100.64.0.0/10 CGNAT range; the file's comment says so). The app
  can now reach the tailnet-local endpoints (box relay, VM
  endpoint tunnel terminus) directly, without the Serve https
  hairpin.
- **Health Connect channel REMOVED** (Jonathan's order
  2026-11-10; agreed 2026-11-09 — redundant with the Health
  Connect connector, which keeps owning health data): channel
  registration, push path (HealthPush.java deleted), settings
  toggle, Store keys, and the manifest HC permission
  declarations are all out — not defaulted off, gone. Channel
  set is now: notifications, calendar, contacts, messages,
  dialer, call log, battery (+ mode_reconcile). The Health
  Connect connector and the Muse app's permission are untouched.
- Carry-overs verbatim: v1.11.0 loop fix, tier-1 outbox + tier-2
  mirror, the v1.10/v1.11 PiP sequence, the v1.5.2 reconciler,
  pairing, Phase 2 DISARMED untouched.

## Consumed via muse-mcp tools (2026-11-10) — RETIRED the same night

**muse-mcp was RETIRED 2026-11-10 (Jonathan's order — "retire
the mcp, we don't need it"); the Worker script + routes are
deleted.** The five `muse_device_*` tools' semantics were folded
into the VM endpoint service and verified locally first — the
agent surface for this app is now the local CLI
**`~/workspace/muse-device-assistance/vm-endpoint/bin/device-tools.js`**
(`report` / `data <source>` / `command <template>` — enqueue
singly — / `command-status <id>` / `mode`) against the VM
endpoint (127.0.0.1:8793, local file store). Record:
`~/workspace/cloudflare-workers/muse-mcp/RETIREMENT-2026-11-10.md`
+ execution doc §17. (Historical: the tools were deployed +
harness-verified on muse-mcp v1.1.0 earlier that night; live
authed calls never ran — KV 429 hard stop — and are moot.)


## v1.14.1 — PiP return-target fix: One Hand Operation + was recorded as the "last app" (2026-11-11)

Full record: `docs/execution-2026-11-09.md` §25. Summary:
- **Bug (Jonathan's report, live call 2026-11-11 ~02:50 EST):** after
  the self-activated voice flow, the PiP return landed him in
  Samsung **One Hand Operation +** (`com.samsung.android.sidegesturepad`)
  instead of the app he had been using. Root cause is code-confirmed:
  the foreground capture's fallback branch scanned
  `getWindows()` **without a window-type filter**, and One Hand
  Operation +'s persistent **TYPE_APPLICATION_OVERLAY** edge-handle
  window passed `isReturnable` — the overlay gets recorded as the
  "foreground app" and `pipDoStep` then faithfully relaunches it.
  The capture→relaunch path shipped in v1.10.0 (2026-11-09), first
  installed via v1.11.0 (~2026-11-10 01:53 EST), carried verbatim
  through v1.12.0/v1.14.0 — matching his "worked a couple of
  versions ago" (the pre-v1.10 builds never relaunched a recorded
  package at all).
- **v1.14.1 (versionCode 24) BUILT — STAGED, not installed.** APK
  6,101,157 bytes (byte-size identical to v1.13.0/v1.14.0;
  code-only change), SHA-256
  `a8014a66f3183be8bb8b38c17f0359b6e9875df1913bbf8717861deb1f0eb2d2`,
  family cert unchanged (in-place install preserves pairing +
  grants). Changes, voice files only: (1) the
  `VoiceAccessibilityService.currentForegroundPackage()` fallback
  scan accepts only `TYPE_APPLICATION` windows; (2) `isReturnable()`
  also excludes `com.samsung.android.sidegesturepad` as
  belt-and-braces; (3) `VoiceLauncher.launchVoice()` BridgeLogs the
  capture (`return target recorded: … (launch voice — return
  target)`) — the capture was previously unlogged, so a regression
  can never again be diagnosed blind.
- **Deploy staged, not forced:** Jonathan was mid-voice-session
  when the fix was built, the VM install lane was down, and his
  pattern is installing in place (as for v1.11.0/v1.14.0). The
  phone still runs v1.14.0/vc23. **Verification owed:** after his
  in-place install, run one joint voice activation from a real
  app — the BridgeLog must show `return target recorded: <his
  app>`, `PiP return: relaunched <his app>`, and
  `PiP verify: … foreground=<his app>`, and he must land back in
  his app.


## v1.20.0 — Muse Launcher Pack local channel (built 2026-11-12)

- **LocalChannel** is a ContentProvider whose only surface is
  `call()`: methods execute / claim_pairing / pair_status /
  revoke. It is the pack's on-device command channel (design
  Section 7.2 as amended at execution: broadcast transport was
  rejected because a broadcast sender cannot be authenticated).
  Caller authentication is two-factor: (1) calling package is
  the Muse Widget app AND its signing cert SHA-256 equals
  `2746d2a2d6dbe0fda4271acd14f4e5ab990da4e9155e7f9e75ae07fca4b814da`
  (the widget app's public fingerprint, printed by its own
  build), (2) the pairing token for execute: generated here
  when Jonathan taps "Open pairing window" in MainActivity,
  claimable once inside 5 minutes, stored only in both apps'
  private prefs, revocable from either app, never logged.
- Execution reuses **ShellBridge.executeGated** (extracted
  from tick; the Worker channel now calls it too): identical
  ShellAllowlist gates, no new verbs. wear_install refused
  locally; shell templates still require the loopback bridge
  paired + on. Rate limit 30/min; refusals counted + logged;
  the synchronous Bundle return is the ack. Report carries
  `local_channel` (paired/count/last, never the token).
- **ContextBroadcasts** sends package-scoped CONTEXT_MODE to
  the pack on observed ground-truth mode changes (poke only).
- Phase 2 actuation machinery and its arming state are
  untouched by this version. Build guards + ShellAllowlist
  gate tests pass (ALL_PASS). APK SHA-256
  `a935fdf62c02afe98e16cf2eb71627016ac0b562d1b0d271c61a28c4af7be9c5`;
  staged, not yet installed as of 2026-11-12 (ADB lane down).

## v1.21.0 — loopback self-update (built + installed + PROVEN 2026-11-13)

- **Why:** ~2026-11-10 the phone forgot the VM's wireless-debugging
  pairing; v1.19/v1.20 installs were stranded for ~3 days because DA
  (by its v1.1 design) manages only its OWN loopback pairing and
  never an external client's authorization. Jonathan's ruling: the
  app was supposed to make the ADB connection stable — so v1.21
  removes external ADB from the release path entirely.
- **New native template `self_update`** (args `<url> <sha256>`,
  gate `ShellAllowlist.checkSelfUpdate`): https to any host, or
  http ONLY to the box tailnet host 100.85.53.70 (the manifest's
  one cleartext domain since v1.12); sha256 exact lowercase hex;
  URL/userinfo/charset rules mirror `wear_install_face`. The args
  are typed values and never become shell text.
- **SelfUpdateRouter flow:** streaming download with SHA-256
  verification into the app's external files dir → loopback shell
  `cp` to /data/local/tmp (system_server cannot read FUSE paths
  for install — proven live; /data/local/tmp works, full cp+pm
  sequence 3.4s) → stage "installing" mirrored to the Store BEFORE
  `pm install -r` runs, because the install kills the process and
  the command result may never post → ground truth is the report.
  Both shell argv arrays are fully hardcoded in the router.
- **Report fix (the blind-diagnosis defect):** `shell_bridge` now
  carries `self_update`: {version_name, version_code,
  last_update_time, stage, sha, detail, from_code, at}. Before
  v1.21 the report had NO app-version field at all. Snapshot
  reconciles a stranded "installing" to "installed" when the
  running versionCode differs from from_code OR lastUpdateTime is
  newer than the mirror (same-version reinstall case).
- **Proof (2026-11-13 01:32 EST):** v1.21.0 installed first via the
  restored external lane (re-pair completed 01:14 EST), then a
  `self_update` command served the final v1.21.0 APK (SHA-256
  `3a5290cc8ea046bb5fd0b26c438c6cdda1cb6734acd976ddf93b19b87904cbea`)
  from a one-shot HTTP server on the box (systemd-run unit
  `da-update-serve`, port 8795, tailnet bind). Box log shows the
  phone's GET at 01:32:22; `dumpsys` lastUpdateTime=01:32:31 —
  the app reinstalled itself 9 seconds after fetching, no external
  ADB involved. Post-update process healthy; relay commands
  executing (battery ok). Companion fix the same night:
  `adb-ensure.sh` now probes the daemon (raw CNXN) and reports
  PAIRING LAPSED vs ENDPOINT DEAD distinctly instead of guessing.
- **To ship the NEXT release:** build, rsync the APK to the box
  serve dir (verify sha on the box — an scp truncated silently
  once tonight), enqueue `self_update <url> <sha256>`, verify by
  report (`self_update.stage=installed`, version bumped) or
  dumpsys lastUpdateTime. External ADB is now the fallback, not
  the path.
- **Report-field whitelist trap (learned 2026-11-13, closing the
  v1.21 verification):** reports pass through TWO rebuilding
  sanitizers — the mode-sync Worker (`worker.js`
  handlePhoneBridgeReportPost) and the box device-relay
  (`~/device-relay/server.js`) — each constructs `shell_bridge`
  from a fixed field whitelist. A new report field that is not
  added to BOTH whitelists is silently stripped even though the
  app sends it (v1.21's `self_update` was stripped for ~1h until
  both were patched + Worker redeployed + relay restarted).
  Any future report-field addition ships with both whitelist
  entries in the same handling.

## v1.22.0 — Muse-app theme (built + installed 2026-11-13)

Aesthetic match to the official Muse app at Jonathan's order.
New `MuseTheme` (res/values/styles.xml + values-night): the
VDS palette decomposed from com.facebook.aura 13.0.0.4.159
(muse-ux-ui-rea/MUSE-DESIGN-TOKENS.md) applied to the
framework Material themes — colorAccent #0064D4 (dark
#007FFD), windowBackground #FCFCFC (dark #050505),
textPrimary #111112 (dark #F5F5F5) with the VDS alpha tiers
for secondary/tertiary text, control colors to match. The
manifest application theme moved from
@android:style/Theme.Material to @style/MuseTheme; no Java
changes, Phase 2 actuation untouched. Guards passed; cert
unchanged. APK 6,113,445 bytes, SHA-256
2a13dadb0a7af107a8a3ef8e2beffc044bf46777ef5eff48fe360fc5e36e775b.
Installed -r via the ADB lane, dumpsys verified (code 33);
MainActivity resumed with no crash after a test launch (the
verification screencap was black because the display was off
overnight; visual check lands when the app is next opened).

## v1.22.0 union addendum (2026-11-13, later)

The theme build above (SHA `2a13dadb…`) was compiled before the
v1.21 self-update sources merged, so it did not contain them.
The trees were unioned the same night: canonical sources are now
theme (v1.22) + self-update (v1.21) at versionCode 33, rebuilt as
APK SHA-256
`93dca12e049535e8766be8bb2d3d426de5cd4e589eba92ef160dd7a06c12b88b`
and delivered to the phone BY the self-update path itself (fetch
03:07:08 EST, lastUpdateTime 03:07:16 EST, dumpsys-verified
1.22.0/33) — the second live self-update, and the first carrying
a real version bump. This union build is the v1.22.0 of record.

## v1.23.0 — Phase 2 actuation REMOVED (built + installed 2026-11-13)

At Jonathan's order (2026-11-13 ~03:10–03:27 EST: disarm + remove
actuation from the app, fix it all, upstream), after the Nov 10–13
double-fire Sleep failures — the app's armed actuation and
mode-sync.sh fired the same Samsung mode switches seconds apart,
two writers cancelling each other out — the Phase 2 firing
machinery was **removed**, not just disarmed:

- `Actuation.java` and `RoutineFirer.java` **DELETED** (both
  trees). The arm switch, the per-mode routine-link UI and its
  shortcut-capture flow, the `K_ACT*` / `K_LINK*` Store keys, the
  report's `actuation` object, and every call site in
  `BridgeService.java`, `WorkerClient.java`, `MainActivity.java`,
  and `Store.java` are gone. Zero-reference proof + full record:
  `docs/v1.23-actuation-removal-2026-11-13.md`.
- The app is now **observe/report only** for house modes: ground
  truth, verdicts, and the Reconciler's shadow plan (what actuation
  *would* have done — it NEVER fires) remain; there is no arm
  switch anymore and nothing in the app can fire a routine.
- **mode-sync.sh is the sole phone-mode writer by construction**,
  not by gate state — the one-writer rule from the Sleep failure,
  enforced by there being no second writer left to fire.

**Build record:** versionCode 34 / 1.23.0; APK
`app/Muse-Device-Assistance-1.23.0.apk`, 6,105,253 bytes, SHA-256
`799b6e0f173c1f0e459dec3d7b22a6cd9b90bbccdcd270b1908aa9e3f6432013`,
same signer as v1.22.0 (family cert 4dcb45fa…7236).

**Install + verification:** delivered by `self_update`
(cmd-15cbe878-f991-4fca-9ab4-ad599903799d, enqueued 03:47:17 EST);
the phone fetched the APK from the box serve unit at 03:47:32 EST
(serve log, from the phone's tailnet IP). The overnight post-install
report was delayed by deep doze, and v1.24.0 (below) shipped on
this tree hours later: the fresh relay report of 2026-11-13
07:52 EST reads `self_update {version_name: 1.24.0,
version_code: 35, stage: installed, from_code: 34}` — code 34 is
v1.23.0, its direct predecessor — with **NO `actuation` field
anywhere in the report**, observed mode Home, and
`shell_bridge {paired: true, self_test_ok: true}`. The removal is
live-verified and carried forward into v1.24.0. The box serve
unit (da-update-serve, port 8795) was stopped after verification.

## v1.24.0 — phone file management (built 2026-11-13)

Jonathan (2026-11-11): "I mean local files on my phone such as the
downloads folder." Verification showed phone local files had ZERO
coverage — the S24+'s 43 paired-device commands include no file
command, the shell allowlist had no file template, the app
reported storage totals only, and no automation touched phone
Downloads. He chose "build it into Device Assistance."

**What shipped** (design: `docs/v1.24-phone-files-2026-11-13.md`):
seven NATIVE templates gated by `ShellAllowlist.checkFiles` and
executed by `FileRouter` via `java.io.File` — typed values, never
shell text (real names carry spaces the shell hygiene bans).
Folder slots are TOKENS code-mapped in the pure-Java `FilePlan`
(`download|documents|dcim|pictures|movies|music` — a path can
never be named); name slots are word args joined into ONE
direct-child name (separators / parent refs / dotfiles / controls
refused).

- `files_access` — All-Files-Access state + per-folder
  exists/readable. The ONLY op allowed without the grant.
- `files_summary` — per-folder recursive files/dirs/bytes/newest
  (scan cap 4,000 entries/folder, flagged when hit).
- `files_list <folder> [offset]` — direct children, newest first,
  pages of 40: `F|D | size | mtimeMs | name`.
- `files_hash <folder> <name…>` — streaming SHA-256 (≤1 GB).
- `files_move <folder> <dest-folder> <name…>` — rename, never
  overwrites, size-verified.
- `files_trash <folder> <name…>` — MOVE to
  `/storage/emulated/0/.muse-trash/<folder>/` + ledger line in
  `.muse-trash/ledger.txt` (collision → ` (2)` suffix). **There
  is no delete template; nothing is ever permanently deleted.**
  Restore = `files_move` back.
- `files_organize <folder>` — files loose direct children into
  Images / Documents / Archives / APKs / Audio / Video subfolders
  by extension (table in `FilePlan`). download/documents ONLY;
  files <1 h old never touched (in-progress downloads); dotfiles
  and crdownload/part/tmp never filed; cap 100 moves/run; returns
  per-category counts + skips + the moved list.

**Access gate — the grant IS the switch:** manifest declares
`MANAGE_EXTERNAL_STORAGE`; every op except `files_access`
requires `Environment.isExternalStorageManager()`, granted ONCE
by Jonathan (MainActivity's new **Phone files** section → "Grant
All Files Access", which also carries an "Inventory Downloads
now" button + last-op state). No Store toggle, no scoped-storage
fallback — a partial view would silently under-report, the exact
defect this feature fixes. Without the grant, ops refuse typed
`files_access_not_granted`. Drive via the usual driver, singly:
`phone-bridge-cmd.sh enqueue files_list download`.

**Report:** `shell_bridge.files {access_granted, last_op,
last_detail, last_at}` (state only, never file names) — whitelisted
in BOTH report sanitizers in the same handling (mode-sync Worker
deployed 2026-11-13 with bindings preserved; box device-relay
patched + restarted), per the v1.21 lesson.

**Build record:** versionCode 35 / 1.24.0 on the v1.23.0 tree; gate
suite ALL_PASS incl. ~35 new v1.24 cases (gates + FilePlan filing
rules); full build guards green (75 app classes); APK
`app/Muse-Device-Assistance-1.24.0.apk`, 6,113,445 bytes, SHA-256
`23c69c93f410a5daa5ec58155d8e10063284cc80ae290cbc9a51756d5f7ac316`,
family cert unchanged (4dcb45fa…7236). Delivery: rsynced to the
box serve dir (sha verified on-box), `self_update` enqueued
cmd-501ed435 with a 6 h TTL — the phone was not fetching commands
at ship time (overnight doze; earlier commands also pending), so
the install lands on its next fetch cycle; verification = a fresh
report at 1.24.0 + a `files_access` result from the new build,
then `files_list download` goes live the moment Jonathan makes
the one-time grant.

**Live verification + first management pass (2026-11-13, later):**
Jonathan granted All Files Access in-app ("Done"); `self_update`
cmd-501ed435 delivered 04:34 EST on the phone's next fetch cycle.
`files_access` (cmd-7277371b) returned **granted=true** with all
six folders present + readable — v1.24.0 LIVE-VERIFIED. First
inventory (cmd-acae6ff8): download 572 files / 25 dirs / 4.88 GB ·
documents 6 files · dcim 2,375 files / 54.4 GB · pictures 3,985
files / 2.17 GB (scan capped) · movies 383 / 401 MB · music 4 /
20 MB. First pass: five `files_organize download` runs filed
**474 files** (Documents ~326, APKs ~78, Images ~50, Archives ~19,
Video 1) into category subfolders + 2 in documents; Download went
from 536 loose children to **67** (category dirs + genuinely
ambiguous loose files: extensionless APKs, .apkm, .macro/.mdr,
.ics). Loose sensitive files noted in the first list page
(Google Passwords.csv, github-recovery-codes.txt) were filed
on-device by category like everything else — never read, never
uploaded.

**Standing automation (2026-11-13):** cron `phone-files-weekly`
(owner goal:drive-and-onedrive-organization, Sundays 07:22 ET,
right after drive-organizer-weekly) runs the weekly pass —
files_access gate → files_summary → organize download+documents
→ verify — under hard rules: media folders inventory-only,
never trash/delete, nothing leaves the phone; it reports as the
Phone Files line alongside the Drive+OneDrive weekly report.
