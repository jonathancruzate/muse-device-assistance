# Muse Device Assistance — ground truth, verified acks, ADB + Tailscale self-heal

The phone-side control plane for Jonathan's Galaxy S24+ mode-sync
system: the custom app that **complements MacroDroid** by owning the
truth, the verification, and the reconciliation logic Samsung's gated
mode surfaces leave to everyone else. Built from the 2026-11-01
investigation *"Custom app vs MacroDroid as the phone-control plane"*.
Project: `~/workspace/muse-device-assistance/` (repo
`jonathancruzate/muse-phone-bridge` until Jonathan's GitHub rename
step lands, then `jonathancruzate/muse-device-assistance`; the GitHub
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
**not** changed yet, deliberately: the installed app's label ("Muse
Phone Bridge"), its Android package
(`com.jonathancruzate.metamusephonebridge`), and the wire names
(Worker endpoint `/phone-bridge/report`, KV key `phonebridge:report`,
report `device:"phone-bridge"`). A package rename is a new app
identity — fresh install, grants and prefs do not carry — so it ships
only as the staged, verified build in the rename plan (target:
label "Muse Device Assistance", package
`com.jonathancruzate.metamusedeviceassistance`, same naming convention
as the current package), never as a silent swap under the live
lanes. Until that build lands, on-device
strings, UI labels, and prefs in this skill still say "Phone Bridge"
because that is what the installed app says.

This skill ships with the app (Jonathan's standing rule, 2026-11-03:
every APK we build carries a skill teaching Muse how to interact with
and use it). The in-repo copy is
`~/workspace/muse-device-assistance/SKILL.md`; the installed copy is
`~/workspace/custom-skills/local-muse-device-assistance/SKILL.md`. They
are the same document — update both in the same handling, always.

## Identity and version source of truth

- **Package:** `com.jonathancruzate.metamusephonebridge`
  (label "Muse Phone Bridge" — the installed app's identity is
  unchanged by the 2026-11-03 project rename; the label + package
  rename is the staged build in `RENAME-PLAN.md`, not yet shipped)
- **Version source of truth:** `app/build.sh` — `VERSION_NAME` /
  `VERSION_CODE` in that script, backed by the build records in
  `github-export/README.md`. At this writing: **v1.3.2, versionCode 6**
  (APK `app/Muse-Phone-Bridge-1.3.2.apk`, SHA-256
  `1f2055ca02987af63cfea50d9610200223b2ec73d3ba4a7601a2d9ea7cd45cae`).
  The project-root `README.md` is **stale** (it stops at v1.1.0) — never
  cite it for current version or capability; `github-export/README.md`
  and `build.sh` govern.
- minSdk 29 / targetSdk 35. Framework-only Android (no AndroidX), raw
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
  `BridgeService` (foreground, `dataSync`, not exported),
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
  `adb shell pm grant com.jonathancruzate.metamusephonebridge android.permission.WRITE_SECURE_SETTINGS`.
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
**Muse Phone Bridge** → **Run Phase 0 probe** → **Grant notification
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

- Anything MacroDroid still owns: spoken announcements and the rest
  of the non-mode phone-automations surface. The bridge has no
  announcement surface.
- Direct mode writes. There is no mode API here — outside armed
  Phase 2 night firing, the bridge observes, verifies, plans, and
  reports. A `plan` is a proposal, not an action taken.
- Power-saving **tier** decisions (Standard vs Maximum) — the app
  reports the boolean only; the tier lane lives elsewhere.
- Pairing or opening ADB connections from the app — v1.1 discovery
  never connects, and the in-app local shell is a **future design
  (v1.4 target), not built**. Phase 3 (MacroDroid mode macros retired,
  mode-sync repointed to publish-and-converge) is likewise **not in
  any shipped version**. Never present either as available.

## Device Assistance extension (roadmap — not built in any shipped version)

The renamed app is the engine for the **Device Assistance** connector:
Jonathan's direction (2026-11-03) is to extend it from bridge access
to full device control. The extension commands — launch an app by
name (Google Maps first), open a navigation intent, media
play/pause/skip and volume, flashlight, native Do Not Disturb, a
foreground-app read — are defined command-by-command in the connector
doc (`~/workspace/custom-connectors/custom.device-assistance/CONNECTOR.md`),
each marked EXTENSION until it ships. Their transport is this app's
command channel (today's v1.4 target: in-app pairing with the phone's
own wireless debugging, loopback ADB client, allowlisted commands
only, results over the existing Worker channel — the same channel the
2026-11-04 backlog build run is scoped to build). App launches and
intents can also be executed on-device directly inside the app once a
command channel exists; either way the result is only real when a
Worker report or read-back verifies it. **None of the extension
commands exist in v1.3.2** — never present one as available, and the
extension does not change the Phase 2 gates: actuation stays DISARMED
unless Jonathan arms it in-app. Device-specific control is not core
scope either: per the plugin architecture (Jonathan, 2026-11-03),
this app is a slim host and device support plugs in as device
connectors registered against it — Galaxy Buds first (namespace
`buds`, its own plugin and connector), more devices later the same
way. The core never interprets device semantics.

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
  repo is `jonathancruzate/muse-phone-bridge` until Jonathan's GitHub
  rename step lands and `jonathancruzate/muse-device-assistance` after
  it; check the snapshot record's `repo` field for which is live
  before pushing — a GitHub Release where the project ships one (asset SHA
  verified against the build), the findings/runbook doc, and this
  skill if the procedure changed — both copies, in the same handling.

## Unverified / not in the repo

- The on-device armed/linked state and installed version **right
  now**: the repo verifies v1.3.2 installed and reporting (report at
  2026-11-03T07:43:06Z, verdict `in_sync`, `reviver_armed=true`), and
  verifies v1.3.0 shipped disarmed with nothing linked. Anything
  between that install and a fresh read is unverified.
- The deliberate Tailscale off/on episode test for the v1.3.2 reviver
  was **pending at snapshot time** — revival is designed and
  report-verified in shape, not yet episode-proven on record.
- The full local shell (in-app ADB pairing, loopback connection,
  allowlisted shell commands pulled down the Worker channel) is
  designed only — now the v1.4 target, not built in any shipped APK.
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
  all under `app/src/com/jonathancruzate/metamusephonebridge/`.
