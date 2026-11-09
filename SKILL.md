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
  `github-export/README.md`. At this writing: **v1.5.1, versionCode 13**
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

## Phase 2 actuation — shipped DISARMED (v1.3.0 machinery, in 1.3.2)

The first rung of replacing MacroDroid's mode macros: the bridge can
fire a routine itself — behind gates, **night mode only, OFF by
default**. The shipped state was verified disarmed at v1.3.0 install:
`armed=false` and no routines linked, so no fire was possible. The
current on-device armed/linked state in v1.3.2 is **unverified from
the repo alone** — read it from the app's Phase 2 section or a fresh
situation check before assuming it; never assume it changed, and
never assume it didn't.

- **Firing is capture-based (`RoutineFirer`).** The
  `RoutineLaunchActivity` extras contract was never decompiled, so the
  app **never hand-builds intents**. Linking runs Samsung's own
  `CREATE_SHORTCUT` picker once per routine (verified resolvable on
  Routines 5.1.02.9:
  `com.samsung.android.app.routines/.ui.shortcut.ShortcutCreationActivity`)
  and stores the minted launch intent (URI form) in on-device prefs
  only; firing replays exactly that intent. If the picker returns no
  shortcut intent, linking logs loudly and Phase 2 stops there.
- **Actuation gate (`Actuation`) — fires only when ALL hold:** the UI
  arm switch is ON (Store `act_armed`, **default OFF**) · desired is
  `night` · verdict is `drift` on a fresh, parse-verified dump · that
  desired `seq` has not already been fired for · the Away latch is NOT
  running (the latch-clear sequence is out of v1.3 scope — logs
  throttled, does not fire) · the Night (Switch Sleep mode) routine is
  linked. A fire that fails to engage shows as continued drift in the
  next report; it is **never auto-retried for the same sequence**.
  Home / Morning / Away links exist in the UI, but they stay shadow —
  night is the only actuation scope.
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
`(Phase 2 ARMED — night only)`.

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
  in-app state: read it from the app's Phase 2 section or a fresh
  situation check before assuming it, and never change it from
  Muse's side.
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
