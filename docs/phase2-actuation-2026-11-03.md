# Phase 2 actuation — runbook (v1.3.0, 2026-11-03)

Phase 2 moves firing from MacroDroid's macros into the bridge, one mode
at a time, night first (the investigation's Phase 2, 2026-11-01). v1.3.0
ships the machinery DISARMED. Nothing about the live house changes until
every gate below is passed, in order.

## What v1.3.0 contains

- **RoutineFirer** — replays launch-shortcut intents that Samsung itself
  minted. The RoutineLaunchActivity extras contract was never decompiled
  (investigation §6.3), so the bridge never hand-builds an intent:
  linking runs Samsung's own CREATE_SHORTCUT picker flow and stores the
  returned shortcut intent (URI form) in on-device prefs only.
  Evidence the flow exists on the live build (Modes and Routines
  5.1.02.9): `cmd package query-activities -a
  android.intent.action.CREATE_SHORTCUT` resolves
  `com.samsung.android.app.routines/.ui.shortcut.ShortcutCreationActivity`.
  Whether the picker's result carries EXTRA_SHORTCUT_INTENT is proven by
  the link step itself (gate 1) — if it does not, the app logs it loudly
  and Phase 2 stops there.
- **Actuation gate** — fires only when ALL hold: arm switch ON (default
  OFF) · desired=night · verdict=drift on a fresh, parse-verified dump ·
  desired sequence not yet fired for · Away latch not running (the
  latch-clear sequence is a later rung) · Night routine linked. A fire
  that fails to engage surfaces as continued drift in the next report;
  it is never retried automatically for the same sequence.
- **UI** — Phase 2 section in the app: arm checkbox, link buttons per
  mode, a test-fire button for Night, and live linked/armed status.

## Gates to live night actuation

1. **Link Night (Jonathan, ~30 s at the phone).** In the app: "Link
   routine: Night (Switch Sleep mode)" → in Samsung's picker choose the
   **Switch Sleep mode** routine. Success = the app shows it linked with
   the routine's name. Then "Test fire: Night routine" once — Sleep must
   visibly engage; exit Sleep via the usual path. (Link Home/Morning/
   Away the same way, whenever; only Night is in the actuation scope.)
2. **Shadow-diff nights.** Let the bridge's verdicts ride alongside the
   incumbent system for several nights; the verdicts must agree with the
   census (the Phase 1 calibration doc is the format for recording
   disagreements).
3. **Arm (Jonathan, in the app).** Check the Phase 2 arm box. From that
   moment the bridge fires Night on drift, once per Worker request,
   verified by the following dump. MacroDroid's mode macros stay
   installed and untouched the whole time — unarming (one checkbox) is
   the full rollback, and the macros remain the fallback for a month
   per the investigation plan.

## Known operational notes

- The bridge reports verdicts, not fire events; a fire is visible in the
  on-device bridge log immediately and server-side in the next
  change report (when the post-fire dump lands).
- adb-ensure's fixed local forward port (14253) intermittently wedges
  ("connect failed" while the phone is fine); a forwarder on a fresh
  local port (14263) connects immediately. Workaround recorded
  2026-11-03; a port-rotation patch to adb-ensure is a daytime change.
