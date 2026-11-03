# Muse Phone Bridge

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
