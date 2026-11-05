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
