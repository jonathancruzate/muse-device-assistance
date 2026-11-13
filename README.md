# Muse Device Assistance

> **Project renamed 2026-11-03 (Jonathan's order): Muse Phone Bridge
> -> Muse Device Assistance.** Canonical local home is
> `~/workspace/muse-device-assistance/`; the companion skill is
> `local-muse-device-assistance`. This repo was renamed on GitHub
> 2026-11-04 to `muse-device-assistance` (the old URL redirects).
> The installed app's label and package rename was **executed
> 2026-11-05**: the app is now label "Muse Device Assistance",
> package `com.jonathancruzate.metamusedeviceassistance` — see the
> final build record below and `RENAME-PLAN.md` / 
> `RENAME-BUILD-PLAN.md` in this repo. The build records below keep
> the name they were written under.


The phone-side control plane for Jonathan's Galaxy S24+ mode-sync system —
the custom app that **complements MacroDroid**, built from the 2026-11-01
investigation *"Custom app vs MacroDroid as the phone-control plane"*
(`~/workspace/goals/manage-my-smart-home/hidden_files/custom-app-vs-macrodroid-20261101.md`).

## Why it exists

Samsung gates every programmatic mode surface in the Modes and Routines
app (`READ/WRITE_MODE_INFO`, `EXECUTE_ACTION`, the `ModeInfoContentProvider`)
behind `signatureOrSystem` permissions no owner-built app can hold (REA
evidence on Routines builds 5.0.01.52 and 5.1.03.1). A full MacroDroid
*replacement* is therefore impossible for anyone. What a custom app can own
is everything that actually failed on 2026-11-01: **the truth, the
verification, and the reconciliation logic.**

- MacroDroid's command path is a third-party cloud relay + FCM, and its
  "ack" is a macro action step that fires because the macro ran —
  unconditional by construction.
- This app reads ground truth on-device from the Modes and Routines app's
  own status notifications (the same dump text the server-side system
  already treats as ground truth), pulls desired state directly from
  Muse's own Cloudflare Worker, and generates an ack **only when observed
  state matches desired state**.

Samsung Routines stays the actuator. MacroDroid keeps its non-mode jobs
(spoken announcements and the rest of the phone-automations surface).

## v1.0.0 — Phase 0 probe + Phase 1 shadow control plane

Per the investigation's phased plan:

**Phase 0 probe (built in, read-only):** reports the installed Modes and
Routines version; attempts the `RoutineInfoProvider` query and records the
result (expect `SecurityException` on 5.1.x — settling the one version
assumption the REA pass could not); checks `RoutineLaunchActivity`
presence/exported state; reports MacroDroid's version and whether this app
holds notification-listener access.

**Phase 1 shadow control plane (observes and reports only — never fires):**

- `GroundTruthListener` (NotificationListenerService) parses the Routines
  app's "Device Status" dump notifications into current mode + running
  routines. Parsing is defensive; a format change is a **loud** parse
  failure in the log, never a silent wrong state.
- `WorkerClient` speaks to the mode-sync Worker:
  - `GET {base}/mode-state?secret=…` → consumes `desired {mode, seq}`
    (the existing Worker shape).
  - `POST {base}/phone-bridge/report?secret=…` → `{observed, desired,
    status, verdict, ack, plan}` where `ack` is true **only** on a
    verified match.
  - The secret is entered on-device in the app's setup screen and stored
    only in the app's private preferences. **No secret is hardcoded in
    this source or shipped in the APK.**
- `Reconciler` encodes Jonathan's rules as a state machine, in shadow:
  matched Modes clear each other (fire target directly); Away is a routine
  latch, not a Mode (toggle it off before a non-away target, never blind);
  a mode active without its switch routine running is a drifted latch
  (toggle on→off to clear, then start the target). On drift it logs the
  exact plan Phase 2 would execute.
- `StatusProducer` emits the API-readable status set (battery, charging,
  power-save boolean, ringer, network, storage, screen). Known partial
  from the report: Samsung's power-saving *tier* (Standard vs Maximum)
  has no stock API.
- `BridgeService` (foreground, dataSync) runs the 60 s loop, reports on
  change and heartbeats every 15 min, restarts on boot if left enabled.
  Silence is itself a signal the server can alarm on.
- `BridgeLog` is the append-only on-device log — the artifact the
  2026-11-01 diagnosis had to reconstruct by hand from five surfaces.

**Phase 2 (not in this version):** actuation cutover — night mode first —
firing manual-routine shortcut intents through the exported
`RoutineLaunchActivity` surface, with the reconciler's sequences executed
and verified. Gated on Phase 0/1 evidence from the phone.
**Phase 3:** MacroDroid's mode macros retired; mode-sync's phone leg
repointed to "publish desired state; the phone converges and reports."

## Build

Framework-only Android (no AndroidX), raw javac/d8/aapt2 flow — the same
toolchain as Muse Voicemail / Muse for Android Auto:

```bash
cd app && ./build.sh
```

Toolchain: `~/workspace/meta-muse-widget/tools/sdk` (build-tools 35.0.0,
platform android-35, used read-only) + Temurin JDK 21
(`~/workspace/custom-skills/local-rea/tools/jdk`). The build **fails** if
any app class is missing from the dex or if the dex references any
`androidx` class (the KMP empty-jar guard from AGENTS.md). Signing key is
generated on first build into `app/keystore/` (local only, never
committed).

- Package: `com.jonathancruzate.metamusephonebridge`
- minSdk 29 / targetSdk 35
- Craft Anything note: per Jonathan's standing order, personal tools are
  built with Craft Anything in Deep mode; the CLI is still clone-only
  under its adoption gate, so — as with Muse Voicemail — the loop ran
  manually with the REA investigation as the Deep study.

## On-device setup (v1.0.0)

1. Install the APK.
2. Open **Muse Phone Bridge** → **Run Phase 0 probe** (record the
   Routines version + provider result).
3. **Grant notification access** → enable Muse Phone Bridge.
4. **Allow unrestricted battery** (Samsung Device Care is the known
   killer; parity with MacroDroid — nobody wins that one).
5. Enter the Worker base URL (`https://jonathancruzate.com/mode-sync`)
   and the mode-state secret on-device → **Save worker settings**.
6. **Start bridge**. Trigger a Device Status dump (the universal macro's
   `?do=status_full`) and watch ground truth land on the status screen.

## Build record — v1.0.0 (2026-11-03)

- APK: `app/Muse-Phone-Bridge-1.0.0.apk`, 136,437 bytes,
  SHA-256 `2b8105a6de0e069d07b78e5901df46c675415cb0a95914f16eee6991299610de`
- Signing cert SHA-256 `4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236`
  (CN=Muse Phone Bridge; keystore local only, never committed)
- Verified: apksigner; aapt2 badging (package/version/label/permissions);
  manifest component dump (listener service carries
  `BIND_NOTIFICATION_LISTENER_SERVICE`); build guards — all 15 app classes
  defined in the root `classes.dex`, zero `androidx` references;
  secrets scan — no hardcoded secret or MacroDroid relay strings (the
  Worker secret is a runtime value entered on-device).
- Built at Jonathan's go-ahead (2026-11-03 00:13 EST) in the side chat
  "Request voicemail and Macrodroid APKs".

## v1.1.0 — ADB endpoint discovery + self-heal (2026-11-03)

Jonathan's addition: use the app as the ADB bridge. The VM-side adb
channel's killers were all phone-side — the wireless-debugging pairing
lapsed on toggles, the adbd port changed (41531 → 40983) so stored-port
consumers dialed a dead endpoint, and every recovery needed manual
phone-side steps. An on-device app owns all three:

- **Discovery (`AdbBridge`):** wireless debugging advertises
  `_adb-tls-connect._tcp` (and `_adb-tls-pairing._tcp` while a pairing
  window is open) over mDNS. The app discovers both via `NsdManager`
  (multicast lock held), resolves them to host:port, flags the endpoint
  whose host is the phone's own Wi-Fi address, stores it, includes it in
  every Worker report (`adb` object), and logs an endpoint **change** as
  a first-class event.
- **Self-heal:** if the app holds `WRITE_SECURE_SETTINGS`, it re-enables
  wireless debugging (`Settings.Global adb_wifi_enabled`) whenever a
  bridge cycle finds it off. The grant is one-time, done while ADB is up:
  `adb shell pm grant com.jonathancruzate.metamusephonebridge android.permission.WRITE_SECURE_SETTINGS`.
  Heals are logged and reported; a UI switch can disarm self-heal.
  Without the grant, the app logs the need (at most every 6 h) and
  surfaces it on the status screen.
- The server-side consumer (`adb-ensure.sh`, which today reads a static
  `adb_port` from `adb-keepalive.state.json`) is to be repointed at the
  latest reported endpoint — tracked with the `/phone-bridge/report`
  Worker endpoint work.
- The full local shell (designed, not built; renumbered twice — it is
  now the v1.4 target, after v1.2.0 took the calibration fix and
  v1.3.0 took Phase 2 actuation): the app pairs once in-app (LADB
  pattern) and holds a loopback ADB connection, pulling allowlisted
  shell commands down the Worker channel.

### Build record — v1.1.0 (2026-11-03)

- APK: `app/Muse-Phone-Bridge-1.1.0.apk`, 140,533 bytes,
  SHA-256 `e7dbf5e194a93750ff73b5d54327a662338461bb019f5443c68b243fe67856fb`
- Same signing cert as v1.0.0 (`4dcb45fa…`) — installs over it in place.
- Verified: apksigner; aapt2 badging (versionCode 2 / 1.1.0, new
  permissions incl. `NEARBY_WIFI_DEVICES` with `neverForLocation`);
  build guards — 18 app classes in the root dex, zero `androidx`;
  `_adb-tls-connect` present in dex strings; no secrets, no MacroDroid
  relay strings.

## v1.2.0 — Resolved observed mode (Phase 1 calibration, 2026-11-03)

The first live shadow capture (2026-11-03) proved the ground-truth lane
end to end — and exposed a vocabulary gap: with desired=home, the dump's
field 1 read "None" while field 2's running list named "At Home". The
incumbent mode-sync system treats that signature as the engaged home
mode (verified aligned 2026-11-02): on this phone the house modes are
largely routine-held, so the mode name lives in the running list while
field 1 stays "None". The v1.1.0 reconciler scored it "drift".

- **`Reconciler.resolveObservedMode`** is now the single resolution
  layer for verdicts, the status notification, and Worker reports:
  field 1 wins when it names a real Mode ("At Home" canonicalizes to
  "Home"); when field 1 is "None", the running list resolves the mode
  ("At Home"/"Switch Home" → Home, "Sleep" → Sleep, "Morning" →
  Morning). The Away latch deliberately never resolves a mode — a
  latch is not an engagement.
- Reports now carry `observed.raw_mode` alongside the resolved
  `observed.mode`, so field 1 is never hidden by the resolution.
- Full evidence: `docs/phase1-calibration-2026-11-03.md`.

### Build record — v1.2.0 (2026-11-03)

- APK: `app/Muse-Phone-Bridge-1.2.0.apk`, 140,533 bytes,
  SHA-256 `f7731dbf0a4eba3fe1b2ca49e8a3a745daa71f41736d4dc3b77e8b730659cf01`
- Same signing cert as v1.0.0/v1.1.0 (`4dcb45fa…`) — installs over
  v1.1.0 in place (done over ADB, dumpsys-verified versionCode 3).
- **Live verification:** after a fresh `status_full` dump, the bridge
  reported observed.mode "Home" (raw_mode "None", running "Auto rotate,
  At Home, or 2 others"), verdict **in_sync**, **ack true** — the first
  verified ack the bridge has produced.

## v1.3.0 — Phase 2 actuation machinery, shipped DISARMED (2026-11-03)

The first rung of replacing MacroDroid's mode macros: the bridge can
now fire a routine itself — behind gates, night mode only, and OFF by
default. The firing mechanism is capture-based: RoutineLaunchActivity's
extras contract was never decompiled, so the app never hand-builds
intents. Linking runs Samsung's own CREATE_SHORTCUT picker once per
routine (the same capture MacroDroid's shortcut action performs) and
stores the minted launch intent on-device; firing replays exactly that
intent. On the live build (Routines 5.1.02.9), `ShortcutCreationActivity`
resolves for CREATE_SHORTCUT — verified over ADB.

- **Actuation gate (`Actuation`)**: fires only when the UI arm switch is
  ON (default OFF), desired=night, the verdict is drift on a fresh
  parse-verified dump, and that desired sequence has not been fired for.
  The Away latch co-running or an unlinked routine logs (throttled) and
  does not fire. Verification is the loop: the next dump recomputes the
  verdict, so a fire that fails to engage shows as continued drift.
- **UI**: Phase 2 section — arm checkbox, per-mode link buttons, a
  test-fire button for Night, linked/armed status.
- Gates to live firing (link → shadow-diff nights → arm) and the
  rollback story: `docs/phase2-actuation-2026-11-03.md`.

### Build record — v1.3.0 (2026-11-03)

- APK: `app/Muse-Phone-Bridge-1.3.0.apk`, 144,629 bytes,
  SHA-256 `3db4853404be6e29936267bf7b87ab065cdd98585a6085de97ea5485e2af1db3`
- Same signing cert (`4dcb45fa…`); installed over v1.2.0 via ADB
  (versionCode 4 verified).
- Build guards: 20 app classes in the root dex, zero `androidx`.
- **Disarmed verification:** post-install report from the new build —
  observed "Home" (raw "None"), verdict **in_sync**, **ack true**, no
  plan; actuation armed=false and no routines linked, so no fire is
  possible in the shipped state.

## v1.3.1 — Tailscale guardian (2026-11-03)

Jonathan's idea the same night ("We can self heal tailscale with the
app"), after the phone vanished from the tailnet mid-session and every
VM-side heal step worked except the last hop. `TailscaleGuard` asserts
Android's `always_on_vpn_app` secure setting for Tailscale each cycle
(the bridge already holds the WRITE_SECURE_SETTINGS grant), making the
OS itself the reviver; detects the tailnet interface (100.64.0.0/10)
and Tailscale's battery-exemption state; reports a `tailscale` object
in every Worker report; and falls back to launching Tailscale's UI if
the interface stays down 10+ minutes. Design + verification protocol:
`docs/tailscale-guardian-2026-11-03.md`.

### Build record — v1.3.1 (2026-11-03)

- APK: `app/Muse-Phone-Bridge-1.3.1.apk`, 144,629 bytes,
  SHA-256 `556bf75f157a50a2e701d03906d3e8953761913f474067846aca8fc0356ad6d2`
- Same signing cert (`4dcb45fa…`) — installs over v1.3.0 in place.
- Build guards: 21 app classes in the root dex, zero `androidx`;
  `TS_GUARD` / `always_on_vpn_app` present in dex strings.
- Worker: mode-sync sanitizer extended for the `tailscale` object and
  redeployed (bindings preserved).
- Install note: the ADB lane was down at build time (the very failure
  this version answers), so the APK was delivered in chat for on-device
  install; the kill-test verification follows the guardian doc.

## v1.3.2 — Tailscale reviver (2026-11-03)

The v1.3.1 guardian's always-on assertion failed its live test: after
~12 minutes of assertion cycles the Settings UI still showed Always-on
OFF (Jonathan checked and switched it on manually; the 02:36 EST
heartbeat report confirmed `battery_exempt: true`). Research verdict:
the only programmatic always-on read/write is
`DevicePolicyManager.get/setAlwaysOnVpnPackage`, restricted to
device-owner / profile-owner (MDM) apps — the legacy
`Settings.Secure` key `always_on_vpn_app` is not a live store on
modern Android, so a regular app cannot set always-on for another
app. v1.3.2 removes the assertion entirely and makes the guardian the
reviver itself: 60-second tailnet detection (an up interface holding a
100.64.0.0/10 address), an immediate Tailscale relaunch attempt on the
first down cycle, backoff retries at 2/5/15/30 minutes, one tappable
"Tailscale is down" notification per episode after 5 minutes, and
recovery logging. Reports carry
`{installed, up, ip, battery_exempt, reviver_armed, down_since, down_count, last_recovered_ms}`.
The OS always-on toggle, set manually by the user, remains
complementary and user-owned. Full record:
`docs/tailscale-reviver-2026-11-03.md`.

### Build record — v1.3.2 (2026-11-03)

- APK: `app/Muse-Phone-Bridge-1.3.2.apk`, 144,629 bytes,
  SHA-256 `1f2055ca02987af63cfea50d9610200223b2ec73d3ba4a7601a2d9ea7cd45cae`
- Same signing cert (`4dcb45fa…`) — installs over v1.3.1 in place.
- Build guards: zero `always_on_vpn_app` references in the dex (the
  assertion is gone); `reviver_armed` present in dex strings.
- Worker: mode-sync sanitizer updated for the reviver report shape
  and redeployed (bindings preserved).
- Live verification: the report at 2026-11-03T07:43:06Z carried the
  new shape with `reviver_armed=true`, `up=true`,
  `battery_exempt=true`, verdict **in_sync**. The off/on episode test
  was pending at snapshot time.

## v1.3.2 (renamed) — Muse Device Assistance package rename (2026-11-05)

Pure identity rename of the v1.3.2 build — no functional change, no
version bump. Label "Muse Phone Bridge" -> "Muse Device
Assistance"; package `com.jonathancruzate.metamusephonebridge` ->
`com.jonathancruzate.metamusedeviceassistance` (manifest, source
package path, `build.sh` `PKG_PATH` + output name). The wire contract
is deliberately unchanged: Worker base URL, `GET /mode-state`,
`POST /phone-bridge/report`, KV key `phonebridge:report`, report
`device:"phone-bridge"`, WorkerClient User-Agent, prefs name
`bridge`, Store keys, log filename `bridge-log.txt`, and the
notification channel IDs are all exactly as v1.3.2 shipped them.

### Build record — v1.3.2 renamed (2026-11-05)

- APK: `app/Muse-Device-Assistance-1.3.2.apk`, 148,725 bytes,
  SHA-256 `3b2adc07d64eb000369f316bfa6f713a376e49c357109aba842166d82ab06166`
  (rebuilt from the renamed canonical tree; an identical-identity
  prep build from the isolated rename copy, SHA-256
  `f0ac1616ab0c12085608a7efaf5f146c6529c7f01f03723fb630c726e2ed681c`,
  is the artifact that was installed on the phone).
- Same signing cert (`4dcb45fa...`, CN=Muse Phone Bridge — the DN
  lives in the unchanged keystore, local only, never committed).
- Verified: apksigner cert read-back; aapt2 badging (package
  `com.jonathancruzate.metamusedeviceassistance`, versionCode 6,
  versionName 1.3.2, label "Muse Device Assistance", minSdk 29,
  targetSdk 35, permission set identical to v1.3.2); build guards —
  23 app classes defined in the dex, zero `androidx` references.
- Swap executed 2026-11-05: installed alongside the old package,
  re-grants applied and read back (notification listener,
  `WRITE_SECURE_SETTINGS`, deviceidle whitelist, `POST_NOTIFICATIONS`,
  `NEARBY_WIFI_DEVICES`), Worker settings re-entered on-device,
  bridge started, fresh reports with `parse_ok=true` ground truth
  verified, alarm lanes verified, then the old package was
  uninstalled and the new app kept reporting. The retired
  `Muse-Phone-Bridge-1.3.2.apk` is kept locally as the rollback
  build.

## v1.4.0 — Loopback shell bridge (2026-11-05)

The v1.4 design (`docs/loopback-shell-bridge-v1.4-design-2026-11-04.md`)
built via Route B: the app pairs once in-app with the phone's own
wireless debugging (SPAKE2 + TLS 1.3 via libadb-android 3.1.0,
conscrypt-android 2.5.3, sun-security-android 1.1, spake2-android
2.2.0, bcprov 1.81 — vendored under `app/libs/` as local-only build
input, hashes in the design doc), holds a loopback ADB connection to
itself, pulls ONE allowlisted structured command per bridge cycle
from the Worker (`GET /phone-bridge/commands`), gates it through
`ShellAllowlist`, executes it over a `shell:` stream (20 s timeout,
16 KB output tail), and posts the result back
(`POST /phone-bridge/command-result`); every report now also carries
a `shell_bridge` state object. The executor stays inert until
pairing completes and the post-pairing `battery` self-test passes
(UI "Local shell" switch can disarm it at any time). Phase 2
actuation is untouched and still defaults DISARMED.

### Build record — v1.4.0 (2026-11-05)

- APK: `app/Muse-Device-Assistance-1.4.0.apk`, 5,687,299 bytes,
  SHA-256 `b422fb71b214ed009060428aa7f9ed104496b0e41cdcae85a6cdbcb8d4fa3535`
- Same signing cert (`4dcb45fa...`) — installs over v1.3.2 in place.
- Verified: apksigner cert read-back; aapt2 badging (package
  `com.jonathancruzate.metamusedeviceassistance`, versionCode 7,
  versionName 1.4.0, label "Muse Device Assistance", minSdk 29,
  targetSdk 35, `extractNativeLibs=true`); build guards — 28 app
  classes, all five library stacks present in the dex, no banned
  androidx (annotation-only exception, see the design doc), native
  libs (libconscrypt_jni, libspake2 × 4 ABIs) packaged; allowlist
  gate re-tested on a plain JVM: 13/13 pass.
- **NOT installed, NOT paired.** The installed version of record
  remains the renamed v1.3.2. Pairing + self-test + one real
  template end-to-end (and the Worker-side command endpoints, not
  yet deployed) are the remaining steps — see the design doc.


## v1.4.1 / v1.4.2 — Self-test negotiation fix; dataSync crash-loop fix (2026-11-08)

- v1.4.1 (versionCode 8): `LoopbackAdb.setApi(Build.VERSION.SDK_INT)`
  — v1.4.0 negotiated as a legacy feature-less ADB client and adbd
  closed every `shell:` stream after OKAY. (This root-cause theory
  was DISPROVEN 2026-11-08 — see the v1.4.3 record below; the real
  cause was a libadb end-of-stream race. The setApi change is
  correct and kept.) APK
  `app/Muse-Device-Assistance-1.4.1.apk`, 5,687,299 bytes, SHA-256
  `19a9ad627a8aa7d823718981faeb3f2b0d0a9a0a4f3f4481b50cb2887f27181e`,
  same cert. Installed 2026-11-08 04:02:59 EST; pairing survived.
  Full record: `docs/execution-2026-11-08.md` section (e).
- v1.4.2 (versionCode 9): BridgeService FGS type dataSync →
  specialUse (dataSync's ~6 h platform budget crash-looped the
  24/7 service since 2026-11-05: DidNotStopInTime on timeout, then
  StartNotAllowed in onCreate on every restart), guarded
  startForeground, and an `onTimeout` clean-stop. APK
  `app/Muse-Device-Assistance-1.4.2.apk`, 5,687,299 bytes, SHA-256
  `f9cbb9379a1ddb32e8a33a161fd8f1958571ac095d3fbfdd70898483b036a3d7`,
  same cert; allowlist gate ALL_PASS; installed 2026-11-08
  10:26:56 EST, verified stable with no new crash records.
  Full record: `docs/execution-2026-11-08.md` section (f).


## v1.4.3 — Loopback shell bridge LIVE: libadb end-of-stream race fix (2026-11-08)

- v1.4.3 (versionCode 11): the `battery` self-test kept failing
  (~40–95 ms, `IOException: Stream closed.`) even after the fresh
  17:07 EST pairing and v1.4.1's modern negotiation. An
  instrumented diagnostic build (v1.4.3-diag1, versionCode 10, APK
  SHA-256
  `58af89c498ad123967adaa7250616e518ccfe22b25114a614314ec949abca42e`)
  proved the app was receiving the FULL command output (13,069
  bytes for `dumpsys battery`) and then throwing on the next read:
  libadb-android 3.1.0's CLSE handling marks a stream hard-closed
  whenever its read queue is momentarily empty — including with
  the last payload undrained in the reader's buffer — so
  `AdbStream.read()` throws "Stream closed." instead of returning
  -1 at end-of-stream. A raw wire probe had already cleared the
  daemon and the CNXN-negotiation theory (feature-less CNXN got
  full payload from the same adbd). Fix in `LoopbackAdb`: a
  `"Stream closed."` throw while the connection is alive is
  treated as EOF. APK `app/Muse-Device-Assistance-1.4.3.apk`,
  5,687,299 bytes, SHA-256
  `b40b70827b6b03380be05983c01e28ad2dd1fb64ad2853c3d6a982ffbaec4d6b`,
  same cert; allowlist gate ALL_PASS; installed 2026-11-08
  17:39:35 EST; pairing survived. **Proven live the same
  handling**: Worker `battery` → `ok:true` (full output, 75 ms)
  and `getprop_model` → `ok:true` — the first end-to-end Worker
  command completions. The report's `self_test_ok` flag still
  awaits Jonathan's one in-app self-test tap (same execute path).
  Full record: `docs/execution-2026-11-08.md` section (g).

## v1.5.0 — Extension pack + voice-mode module (+ TV profile) (2026-11-08)

- v1.5.0 (versionCode 12): the connector's EXTENSION verbs built
  as allowlist templates — 11 shell templates (`app_launch`,
  `nav_open`, `media_play_pause`, `media_next`, `media_previous`,
  `media_volume`, `media_volume_set`, `media_volume_get`,
  `dnd_on`, `dnd_off`, `screen_foreground`) + 3 gated native
  templates (`flashlight`, `flashlight_status`, `voice_mode`;
  CameraManager torch and the voice module have no shell form on
  this build). The voice-mode self-activation module (the Muse
  Voice Mode Shortcut's accessibility mechanism folded in) ships
  OFF by default behind a two-key gate. The same binary carries
  the ratified TV profile (`DeviceProfile`, `docs/tv-profile-
  v1.5.0-2026-11-08.md`). Every verb was ADB-lane-proven on the
  phone before encoding, then live-verified via the Worker
  channel with independent read-backs (section (h) of the
  execution record): all pack verbs LIVE except `voice_mode`,
  which is built-but-not-live (gate refusal proven; full toggle
  awaits Jonathan's in-app module enable + accessibility grant).
  Gate tests ALL_PASS; all build guards pass. APK
  `app/Muse-Device-Assistance-1.5.0.apk`, 5,699,668 bytes,
  SHA-256
  `8dfbba37bc998e476de64732b945bad8db9096af8e6a91fef845a928e20666e9`,
  same cert; installed 2026-11-08 20:53:57 EST in place; pairing
  + `self_test_ok:true` survived. Operating rule learned: enqueue
  Worker commands singly (one execution per fetch, newest
  first). Full record: `docs/execution-2026-11-08.md` section (h).

## v1.5.1 — Rename fix + Galaxy Buds host routing; voice_mode LIVE (2026-11-08)

- v1.5.1 (versionCode 13): the accessibility services' user-visible
  labels are now exactly "Muse Device Assistance" (component names
  unchanged; the accessibility grant survived the in-place
  install). `BudsRouter` + `BudsResultReceiver` route the new
  allowlisted `buds_*` native templates to the separate Muse
  Galaxy Buds plugin APK (v1.0.1) and return its typed results —
  the core stays slim. Gate tests extended, ALL_PASS. APK
  `app/Muse-Device-Assistance-1.5.1.apk`, 5,699,668 bytes,
  SHA-256
  `84ed5b9d49c978efc8fa92adb5cb7cc5bdfe5e661292e2f1605db43ded0f4872`,
  same cert; installed 2026-11-08 22:55:37 EST in place; pairing +
  `self_test_ok:true` survived (post-install report verified). The
  voice-mode module went LIVE the same night (Jonathan enabled it;
  full toggle pair verified on this build) under his 2026-11-08
  discretion grant + the SPEAK-FIRST rule (skill voice section).
  Buds: routing + gates proven live (typed `buds_not_connected`
  refusals), volume verbs live-verified via the plugin; SPP verbs
  built, not yet live-verified (his Buds2 were disconnected).
  Full record: `docs/execution-2026-11-08.md` section (i).

## v1.5.2 — Voice state-desync fix: belief reconciliation (2026-11-08)

- v1.5.2 (versionCode 14): the voice module's persisted
  `voice_active` belief is reconciled against observed reality.
  On v1.5.1 a session ended on the Muse side (hang-up inside
  Muse) never cleared the flag, and the next `voice_mode on`
  returned "already active, no-op" against the dead session
  (proven live 2026-11-08 23:28). The accessibility service now
  observes Muse's session UI (live-verified markers:
  `hatch-voice-end-call-button`, `hatch-voice-call-connected`,
  `hatch-floating-call-controls`, "End voice call" / "Mute voice
  call"), clears the flag when the session UI leaves, and every
  `voice_mode` op re-checks the flag against that observation
  before trusting it (15 s grace for in-flight transitions).
  Live-verified after install: from the reproduced stale state
  (report `voice_active:true`, no session on screen), `on`
  dispatched a real start three times running; the belief
  converged to false on its own after externally ended sessions;
  off->on dispatched cleanly. Both voice gates unchanged; gate
  tests ALL_PASS; all build guards pass. APK
  `app/Muse-Device-Assistance-1.5.2.apk`, 5,703,764 bytes,
  SHA-256
  `dfced7cd1d89ae3f129725b92dd504ed4a52b695262b70e79b708c43aa82631c`,
  same cert; installed 2026-11-08 23:40:49 EST in place; pairing
  + self-test survived (post-install report verified). Full
  record: `docs/execution-2026-11-08.md` section (k).

## v1.6.0 — Local push channels, first slice (2026-11-09)

- v1.6.0 (versionCode 15): nine user-controlled push channels —
  Store keys + a `PushChannels` registry, a MainActivity toggle
  section ("you control each one"), and `PushClient` posting
  `{base}/device-push` on the mode-sync Worker (same base + secret
  the app already holds; the Worker gained POST/GET /device-push
  with sanitized per-source KV queues + a `push_channels` report
  passthrough, deployed in the same handling). LIVE observers:
  Notifications forwarding in GroundTruthListener (every package,
  posted AND removed, source phone-notify — replaces MacroDroid
  Notify Push) and the Low Battery 20% latch in BatteryPush
  (source phone-battery — replaces Low Battery Alert); both ship
  with their toggles OFF (Jonathan's to flip). Calendar / Contacts
  / Messages / Dialer / Call Log / Health Connect are toggle +
  permission-ask stubs; their observers are v1.7. Reports carry
  `push_channels {on, last_push_at, last_error}` per channel.
  Build guards + ShellAllowlist gate tests pass. APK
  `app/Muse-Device-Assistance-1.6.0.apk`, 5,711,956 bytes,
  SHA-256
  `fd84d5fd46525ce8226dd5ed739cf19c505355a603e879b8d1c3597d5d97827e`,
  same cert; installed 2026-11-09 09:49:00 EST in place; pairing,
  self-test, voice state and grants survived; post-install report
  verified push_channels at defaults, BridgeService foreground,
  no post-install crashes. Full record:
  `docs/v1.6-push-channels-2026-11-09.md`.

## v1.7.0 — Push observers: the v1.6 stubs made real (2026-11-09)

- v1.7.0 (versionCode 16): the six stubbed channels get real
  observers behind the existing toggles. New `PushObservers`
  (BridgeService-owned; ContentObservers mark channels dirty, the
  60-s cycle syncs): Calendar (Events query at/after cursor, cap
  50, source phone-calendar), Contacts (last-updated cursor,
  phone-contacts), Messages (separate SMS/MMS row-id cursors
  seeded to the current max — no historical dump, phone-messages),
  Call Log (row-id cursor, phone-calllog), Dialer
  (TelephonyCallback registered only while ON, phone-dialer —
  platform limit: the API-35 listener delivers state only, so
  `number` is currently ""). Health Connect is LIVE in new
  `HealthPush`: the android-35 platform jar already carries the
  framework HC change-log APIs, so no new dependency — one token
  for Sleep/Steps/HeartRate/Exercise, 15-min throttle, token
  stored only on a 2xx push, and no HC permissions granted ->
  the channel `last_error` says so (source phone-health).
  Universal rules: toggle OFF = unregistered / not read / not
  pushed; cursors advance only on PushClient 2xx; failures land
  in the channel `last_error` + BridgeLog; missing permission at
  registration sets `last_error`, never crashes. No Worker change
  (the v1.6 sanitizer already accepts these sources). Build
  guards + ShellAllowlist gate tests pass. APK
  `app/Muse-Device-Assistance-1.7.0.apk`, 5,716,052 bytes,
  SHA-256
  `89147ee36961e139a95f178f7a424a6fa433def55322daec35dc7768f1e1b5bd`,
  same cert; installed 2026-11-09 10:10:55 EST in place; pairing,
  self-test, voice state and grants survived; post-install report
  in_sync with `shell_bridge` unchanged and no crashes. Queue
  read-backs: dialer pushed its live registration event, calendar
  pushed its first sync (50 items, the cycle cap), messages /
  call-log / contacts empty (cursor seeding), health empty with
  the permissions `last_error`. Channel state: Jonathan flipped
  Notifications ON in-app ~10:04 EST 2026-11-09 — live-verified
  pushing posted AND removed events from 15:04:43Z (111 items
  queued at read-back); MacroDroid's Notify Push macro is
  superseded by the app channel and retired — Jonathan disabled
  it on-phone ~10:16 EST 2026-11-09 (last MacroDroid push
  15:14:11Z, app pushes continued past 15:16Z), and the legacy
  `phone-notify-drain` lane retires with it. Every other observer
  channel stays OFF until Jonathan flips it. Phase 2 DISARMED.
  Full record: `docs/v1.7-push-observers-2026-11-09.md`.

## v1.20.0 — Muse Launcher Pack local channel (2026-11-12)

- **LocalChannel (ContentProvider, call()-only):** the Muse
  Launcher Pack's on-device command channel (Launcher Pack
  design Section 7.2, as amended at execution on the swarm
  Skeptic's dissent: a broadcast cannot authenticate its
  sender, call() can). Every call verifies the calling
  package is the Muse Widget app AND its signing certificate
  SHA-256 matches that app's known fingerprint; execute()
  additionally requires the pairing token (generated on this
  phone when Jonathan opens the pairing window in
  MainActivity, claimable once inside five minutes by the
  cert-verified pack, revocable from either app, never
  logged, never in a build artifact).
- Execution reuses `ShellBridge.executeGated` (extracted from
  the Worker channel's tick): identical ShellAllowlist gates
  and dispatch, no new verbs. wear_install is refused
  locally. Shell templates keep the Worker channel's rule:
  loopback bridge paired + switched on. Rate limit 30 calls
  per minute; refusals counted and logged. The synchronous
  return Bundle is the acknowledgement the pack renders from.
- **ContextBroadcasts:** package-scoped CONTEXT_MODE
  broadcast to the pack when ground truth observes a mode
  change (pokes only; the pack treats broadcasts as
  re-render hints, never commands).
- Report gains `local_channel` (paired / counts / last,
  never the token). Phase 2 actuation state untouched.
- Build record: versionCode 31 / 1.20.0; all dex guards
  pass; ShellAllowlist gate tests ALL_PASS; signer cert
  SHA-256 `4dcb45fa…` unchanged. APK
  `app/Muse-Device-Assistance-1.20.0.apk`, 6,109,349 bytes,
  SHA-256
  `a935fdf62c02afe98e16cf2eb71627016ac0b562d1b0d271c61a28c4af7be9c5`.
  Install attempted 2026-11-12 04:41 EST; phone unreachable
  (wireless debugging endpoint refused), so this build is
  staged, not installed.

## v1.22.0 (2026-11-13)

Muse-app theme: new MuseTheme carrying the VDS palette
decomposed from the official Muse app (accent #0064D4 /
#007FFD, grounds #FCFCFC / #050505, text #111112 / #F5F5F5).
Theme-only release; no behavior changes.
