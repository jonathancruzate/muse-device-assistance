# Rename build plan — Muse Phone Bridge → Muse Device Assistance (APK label + package)

**Status: PREP ONLY — prepared 2026-11-04 night. Nothing was installed,
uninstalled, or changed on the phone. Canonical sources in this repo
still carry the old package; the renamed APK below was built in an
isolated copy (see §6). The swap itself is a daytime job for
2026-11-05, after the morning alarms have fired, with Jonathan awake
and reachable and the ADB lane up.**

Companion documents: `RENAME-PLAN.md` (the staged rename decision and
sequence — this plan executes its step B), `SKILL.md` (both copies),
connector doc `~/workspace/custom-connectors/custom.device-assistance/CONNECTOR.md`.

---

## 1. Targets (exact, with source)

| Item | Current (live, v1.3.2) | Target |
|---|---|---|
| App label | `Muse Phone Bridge` | **`Muse Device Assistance`** |
| Package / applicationId | `com.jonathancruzate.metamusephonebridge` | **`com.jonathancruzate.metamusedeviceassistance`** |
| Version | versionName `1.3.2`, versionCode `6` | **Unchanged** (`1.3.2` / `6`) — this is a pure identity rename, no functional change, so no version bump. The next functional build (v1.4 extension) bumps normally. |
| Signing cert | SHA-256 `4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236` | **Unchanged** — same keystore (`app/keystore/musephonebridge.keystore`, file kept as-is; a keystore filename is not an identity). |
| minSdk / targetSdk | 29 / 35 | Unchanged |

**Source for the targets:** `RENAME-PLAN.md`, "Final-name correction"
and staged step B, verbatim: target label "Muse Device Assistance",
target package `com.jonathancruzate.metamusedeviceassistance` ("same
naming convention as the current package"). The companion skill
(`SKILL.md`, Identity section) states the same pair. No target was
invented for this plan.

**Build system note:** there is **no Gradle file** in this project
(no `build.gradle` / `settings.gradle`). The build is the raw
framework-only toolchain in `app/build.sh` (aapt2 → javac → d8 →
zipalign → apksigner; build-tools 35.0.0, platform android-35,
Temurin JDK 21) with built-in guards (fails if any app class is
missing from the dex or the dex references any `androidx` class).
"applicationId" in this project = the manifest `package` attribute +
`PKG_PATH` in `build.sh`. All references below are to those files.

---

## 2. Why this is sequenced, not a file rename

A package rename is a **new Android app identity** (RENAME-PLAN.md,
Jonathan's 2026-11-03 rule): fresh install; permissions, role
holders, notification access, battery exemption, prefs, and Phase 2
link state do **not** carry over; app data does not migrate. The old
and new packages can coexist on the phone, which is what makes a
verified, reversible swap possible (§7 install-alongside, §9
rollback). Phase 2 stays **DISARMED** through the whole swap.

**Timing constraint for 2026-11-05:** Jonathan's morning alarms —
Vyvanse 05:30 and Wake up 06:30 — are set for tomorrow morning
through the live phone lanes. **Do not start the swap until both
have fired** and Jonathan is awake. The old package is not stopped
or uninstalled until every check in §8 passes.

---

## 3. Every file that changes (canonical source edits at swap time)

Apply these edits to the canonical tree at swap time, then rebuild
(§5). They are exactly the edits already proven by the isolated
build in §6 — diff the staged copy at
`/tmp/mda-rename-build/app` against `app/` if it still exists, but
the canonical tree is the source of truth; re-apply, don't copy
blindly.

### 3.1 `app/AndroidManifest.xml`
- `manifest package="com.jonathancruzate.metamusephonebridge"` →
  `package="com.jonathancruzate.metamusedeviceassistance"`
- `<application android:label="Muse Phone Bridge">` →
  `android:label="Muse Device Assistance"`
- `GroundTruthListener` service `android:label="Muse Phone Bridge ground truth"` →
  `android:label="Muse Device Assistance ground truth"`
- Comment block: the `pm grant` example naming the old package →
  new package (comment only).
- Nothing else: all `<uses-permission>` entries, component names
  (`.MainActivity`, `.GroundTruthListener`, `.BridgeService`,
  `.BootReceiver` — relative names, they follow the package), and
  the foreground-service type are unchanged.

### 3.2 Source package directory (all 16 Java files)
- Directory: `app/src/com/jonathancruzate/metamusephonebridge/` →
  `app/src/com/jonathancruzate/metamusedeviceassistance/`
  (files: Actuation, AdbBridge, BootReceiver, BridgeLog,
  BridgeService, DumpParser, GroundTruthListener, MainActivity,
  Probe, Reconciler, RoutineFirer, ShellAllowlist, StatusProducer,
  Store, TailscaleGuard, WorkerClient).
- In every file: `package com.jonathancruzate.metamusephonebridge;` →
  `package com.jonathancruzate.metamusedeviceassistance;`
- Package-qualified strings inside the sources:
  - `BridgeService.java`: `ACTION_CYCLE = "com.jonathancruzate.metamusephonebridge.CYCLE"` →
    `"com.jonathancruzate.metamusedeviceassistance.CYCLE"` (app-internal
    intent action; both sender and receiver are this app, so the
    rename is self-consistent).
  - `AdbBridge.java`: the `pm grant` example in the class comment →
    new package; multicast lock tag `"muse-phone-bridge"` →
    `"muse-device-assistance"` (cosmetic, app-internal).

### 3.3 User-visible label strings in code
- `MainActivity.java`: `title.setText("Muse Phone Bridge")` →
  `title.setText("Muse Device Assistance")`
- `BridgeService.java`: foreground notification
  `.setContentTitle("Muse Phone Bridge")` →
  `.setContentTitle("Muse Device Assistance")`
- `BridgeService.java` notification channel display name
  `"Phone Bridge"` → `"Muse Device Assistance"` (channel **id**
  `"bridge"` does NOT change — §4).

### 3.4 `app/build.sh`
- `PKG_PATH="com/jonathancruzate/metamusephonebridge"` →
  `PKG_PATH="com/jonathancruzate/metamusedeviceassistance"`
  (drives the class-count and dex guards).
- Output name: `--out "Muse-Phone-Bridge-$VERSION_NAME.apk"` and the
  two following verify lines → `Muse-Device-Assistance-$VERSION_NAME.apk`.
- Header comment naming the app → new name (cosmetic).
- **Unchanged in build.sh:** `VERSION_CODE=6`, `VERSION_NAME="1.3.2"`,
  keystore path/password handling, SDK/JDK paths, all guards.

### 3.5 Build outputs (generated, not hand-edited)
- New APK `app/Muse-Device-Assistance-1.3.2.apk` (+ its `.idsig`)
  appears alongside the old APKs. `app/build/` is regenerated
  (`rm -rf build` in build.sh) — the generated `R.java` lands under
  the new package automatically via aapt2 `--java build/gen` from
  the manifest package.
- Old APKs are **not** deleted or overwritten (§9 rollback).

---

## 4. What deliberately does NOT change

Per `RENAME-PLAN.md` step B.2 and §"Explicitly NOT renamed" — wire
names are a live contract with the mode-sync Worker, not branding:

- **Worker channel:** default base URL `https://jonathancruzate.com/mode-sync`;
  `GET {base}/mode-state`; `POST {base}/phone-bridge/report`;
  KV key `phonebridge:report`; report field `device:"phone-bridge"`;
  the `phonebridge` token in server-side consumers.
- **WorkerClient User-Agent** `"MusePhoneBridge/1.1"` — kept in the
  rename build to keep the wire surface byte-stable; it is not part
  of the Worker's keying. Revisit only in a coordinated release.
- **Prefs file name:** `Store.PREFS = "bridge"` (on-device file
  `bridge.xml`). Note the grounding correction: RENAME-PLAN.md says
  "the `phonebridge` prefs file" loosely — the code constant is
  `"bridge"`, and it stays `"bridge"`. Keeping the name changes
  nothing about migration: the new package gets its **own empty**
  `bridge.xml` regardless (§7 re-entry of settings).
- **All Store keys** (`K_*`: worker settings, ground truth, desired,
  ADB, verdict, Phase 2 `act_*`/`link_*`, Tailscale) — unchanged.
- **Log file name:** `BridgeLog.FILE = "bridge-log.txt"` — unchanged
  (the new package starts a fresh log in its own files dir).
- **Notification channel IDs:** `"bridge"` (BridgeService) and
  `"tailscale-guard"` (TailscaleGuard) — per-app namespaces, kept;
  only the bridge channel's display name changes (§3.3).
- **Signing keystore and cert** (§1). Across packages the cert is
  irrelevant to Android, kept identical for consistency and so the
  build's verify output stays comparable.
- **Phase 2 state machine:** arm switch default OFF, link model,
  night-only gate — code unchanged; on-device link state does not
  migrate and is **not** re-created during the swap (§7).
- **Historical names:** build records, runbooks, `github-export/`
  history, and dated docs keep the name they were written under;
  `docs/project-readme-pre-rename-2026-11-03.md` stays verbatim.
- **This app holds no Android role** (it is not a dialer / default
  handler / etc.) and its manifest requests **no** `READ_CALL_LOG`,
  alarm, or exact-alarm permission. Alarms (`alarm.set` /
  `alarm.list`) and call-log reads reach the phone through the
  Device Assistance connector's paired-device catalog / delegated
  lanes (see the connector doc: `alarm.*` are EXISTS-CATALOG), not
  through this APK's identity — the rename cannot revoke them, but
  §8 still verifies them end-to-end because Jonathan depends on
  them and they ride the phone/ADB health this app reports.

---

## 5. Build command (canonical, at swap time)

```bash
cd ~/workspace/muse-device-assistance/app && ./build.sh
```

Offline, no network fetches, no Gradle. Expected tail: `app classes: 23`,
`dex guards passed`, signer cert SHA-256
`4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236`,
and `Muse-Device-Assistance-1.3.2.apk` with its SHA-256 printed.
Then read back the identity before touching the phone:

```bash
BT=~/workspace/meta-muse-widget/tools/sdk/build-tools/35.0.0
$BT/aapt2 dump badging Muse-Device-Assistance-1.3.2.apk | head -5
# must show: package name='com.jonathancruzate.metamusedeviceassistance'
#            versionCode='6' versionName='1.3.2'
#            application-label:'Muse Device Assistance'
```

---

## 6. Build already proven tonight (isolated copy — prep artifact)

On 2026-11-04 night the §3 edits were applied to a **copy** of
`app/` at `/tmp/mda-rename-build/app` (canonical sources untouched,
verified after the build: `app/AndroidManifest.xml` and
`app/src/com/jonathancruzate/metamusephonebridge/` still carry the
old package) and built with the unmodified toolchain. Result:

- **APK:** `app/Muse-Device-Assistance-1.3.2.apk` (copied alongside
  the old APKs, plus its `.idsig`)
- **SHA-256:** `f0ac1616ab0c12085608a7efaf5f146c6529c7f01f03723fb630c726e2ed681c`
- **Size:** 148,725 bytes
- **Badging read-back:** package
  `com.jonathancruzate.metamusedeviceassistance`, versionCode 6,
  versionName 1.3.2, label `Muse Device Assistance`, minSdk 29,
  targetSdk 35, permission set identical to 1.3.2.
- **Guards:** 23 app classes compiled, dex guards passed, no
  `androidx` reference.
- **Signing:** same cert as 1.3.2 (SHA-256
  `4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236`,
  DN `CN=Muse Phone Bridge, O=JonathanCruzate, C=US` — the DN is
  inside the existing keystore and does not change).
- **Old APK intact:** `app/Muse-Phone-Bridge-1.3.2.apk` still
  SHA-256 `1f2055ca02987af63cfea50d9610200223b2ec73d3ba4a7601a2d9ea7cd45cae`.
- **Nothing was installed.** There was no device step tonight.

Tomorrow the swap may either install this artifact directly (after
re-verifying its hash) or rebuild from the canonical tree after
applying §3 — a rebuild is preferred for upstream cleanliness, and
its badging/cert/guards must match the values above (the APK hash
itself need not match if zip timestamps differ).

---

## 7. Install / reinstall sequence for 2026-11-05 (daytime)

Preconditions: both morning alarms (Vyvanse 05:30, Wake up 06:30)
have fired; Jonathan awake and reachable; ADB lane up (diagnose
from the old app's latest Worker report `adb`/`tailscale` objects
first, per the skill); Phase 2 confirmed DISARMED in the old app.

1. **Snapshot the old state.** Latest `phonebridge:report` (observed,
   desired, verdict, `adb`, `tailscale`, status), old app's
   installed version read-back
   (`dumpsys package com.jonathancruzate.metamusephonebridge`),
   and the on-device log tail / Phase 2 section state. Note the
   Worker base URL shown in the old app's setup screen (the URL is
   not secret; the secret is — see step 4).
2. **Apply §3 to the canonical tree and build** (§5), or re-verify
   the §6 artifact's SHA-256 and use it.
3. **Install ALONGSIDE the old package** (never uninstall first):
   `adb install Muse-Device-Assistance-1.3.2.apk`
   Verify: `dumpsys package com.jonathancruzate.metamusedeviceassistance`
   shows versionCode 6 and the launcher shows **Muse Device
   Assistance** next to the still-present **Muse Phone Bridge**.
4. **Muse's re-grants (ADB lane, automatable — §8 checklist).**
5. **Jonathan's taps (his alone, never automated):** open **Muse
   Device Assistance** → enter the Worker base URL + mode-state
   secret in the setup screen (the secret lives only in on-device
   prefs — Muse never extracts, carries, or re-enters it) →
   **Save worker settings** → **Start bridge** → confirm
   unrestricted battery in Samsung's UI if the ADB whitelist does
   not fully register. Run the Phase 0 probe from the new app.
6. **Verify end-to-end** (§8). The old package keeps running until
   every check passes — two bridges reporting to the same KV key
   is expected and harmless during this window (identical wire
   contract; reports are the same shape).
7. **Cutover — only after §8 fully passes:** stop the old app's
   bridge in its UI, then uninstall the old package
   (`adb uninstall com.jonathancruzate.metamusephonebridge`).
   Confirm the new app keeps reporting after the uninstall
   (one change report or heartbeat cycle).
8. **Same-handling surface updates** (§11) in the swap handling —
   not tonight.

---

## 8. Re-grant checklist — everything a fresh identity loses

The new package starts with none of these. Check each off by
read-back, not by the grant command's exit code.

**Automatable by Muse over ADB:**
- [ ] Notification-listener access for the new listener:
      `cmd notification allow_listener com.jonathancruzate.metamusedeviceassistance/com.jonathancruzate.metamusedeviceassistance.GroundTruthListener`
      Read-back: `settings get secure enabled_notification_listeners`
      contains the new component; new app's probe reports access held.
      (Without this there is no ground truth — reports go `unknown`.)
- [ ] `WRITE_SECURE_SETTINGS` (ADB self-heal):
      `adb shell pm grant com.jonathancruzate.metamusedeviceassistance android.permission.WRITE_SECURE_SETTINGS`
      Read-back: new report's `adb.secure_settings_granted=true`,
      `self_heal_armed=true`.
- [ ] Battery exemption:
      `dumpsys deviceidle whitelist +com.jonathancruzate.metamusedeviceassistance`
      Read-back: `dumpsys deviceidle whitelist` lists it AND the new
      report's `status.battery_unrestricted=true` (Samsung Device
      Care can still override — Jonathan confirms in UI, §7 step 5).
- [ ] `POST_NOTIFICATIONS` runtime grant (Android 13+):
      `adb shell pm grant com.jonathancruzate.metamusedeviceassistance android.permission.POST_NOTIFICATIONS`
      Read-back: `dumpsys package …` shows it granted; the
      foreground-service notification is visible.
- [ ] Install-time (normal) permissions present in
      `dumpsys package com.jonathancruzate.metamusedeviceassistance`:
      INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE,
      FOREGROUND_SERVICE_DATA_SYNC, RECEIVE_BOOT_COMPLETED,
      REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, QUERY_ALL_PACKAGES,
      ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE,
      NEARBY_WIFI_DEVICES. (Granted at install; verify, don't assume.)
- [ ] Boot restart armed: `service_on` will be set by Start bridge
      (§7 step 5); verify `BootReceiver` is in the new package's
      receiver list in the dumpsys output.

**Jonathan's taps only:**
- [ ] Worker base URL + mode-state secret entered in the new app;
      worker settings saved (secret never leaves the phone).
- [ ] **Start bridge** pressed; foreground notification shows
      "Muse Device Assistance".
- [ ] Unrestricted battery confirmed in Samsung UI if prompted.
- [ ] Phase 2: **nothing** — no linking, no arming in the new app
      during the swap. It stays DISARMED with no routines linked,
      exactly as a fresh identity ships.

**Explicitly not lost / not applicable:**
- No Android role holders to restore — this app holds no role.
- MacroDroid, Samsung Routines, Tailscale, and the Clock alarms
  are other apps/packages; the rename grants them nothing and
  revokes nothing. Tailscale's own battery exemption is Tailscale's,
  untouched.

---

## 9. End-to-end verification checklist (all must pass before cutover)

Bridge / mode truth (the app's own surfaces):
- [ ] New package reporting: fresh reports from the new install land
      in KV `phonebridge:report` — `kind` change/heartbeat/manual,
      `device:"phone-bridge"` (wire name unchanged by design),
      report age < 15 min.
- [ ] Mode ground truth: `observed.mode` / `raw_mode` / `running`
      populated from a fresh Modes and Routines dump (not stale —
      ground truth older than 30 min reads `unknown`), `parse_ok=true`,
      no `PARSE_FAIL` in the new app's log tail.
- [ ] Verified ack: with desired state as published, a report shows
      `verdict=in_sync`, `ack=true`. (Drift with a correct `plan`
      is also a pass for the truth pipeline; `unknown` is not.)
- [ ] Status set present in the report: battery %, charging,
      power-save boolean, screen, ringer, network, storage.
- [ ] ADB object: `adb.endpoint` present with a fresh port,
      `wireless_debugging=true`, `secure_settings_granted=true`;
      VM-side ADB still connects using the reported port at the
      tailnet host.
- [ ] Tailscale object: `tailscale.up=true`, tailnet IP present,
      `reviver_armed=true`.
- [ ] New app's log tail shows `SERVICE` / `WORKER` / `RECONCILE`
      cycles and no recurring error kinds.
- [ ] One full heartbeat cycle observed from the new package, and
      one `Sync now` manual report posted successfully (HTTP 200 in
      the UI's last-report code).

Live lanes Jonathan depends on (via the Device Assistance
connector / paired-device catalog — verified because they are his
morning-critical lanes, even though they are not this APK's code):
- [ ] `alarm.set` a test alarm a few minutes out, `alarm.list`
      read-back shows it (and the standing Vyvanse / Wake up alarms
      for the next morning are listed intact), test alarm fires,
      then `alarm.dismiss` and read-back confirms removal.
- [ ] Call-log read succeeds through the same lane used today
      (paired-device catalog read) and returns recent entries —
      proving the phone/ADB path the swap touched is healthy.
- [ ] MacroDroid webhook lane: one harmless delegated read/fire
      with read-back (e.g. `device.deep_status` via the universal
      macro, or `routine-control.sh` status path) — proves the
      MacroDroid surface the bridge complements is unaffected.
- [ ] `house_mode` sanity: `mode-sync.sh` desired state and the new
      bridge's observed state agree after the swap window.

Cutover gate: every box above checked → §7 step 7. Any failure →
§10 rollback, old package still in place.

---

## 10. Rollback

The swap is reversible at every point before §7 step 7 because the
old package is never touched first:

- **Before cutover:** stop the new app's bridge (or uninstall the
  new package: `adb uninstall com.jonathancruzate.metamusedeviceassistance`).
  The old **Muse Phone Bridge** v1.3.2 is still installed, still
  holds every grant, its prefs/secret intact, still reporting.
  Nothing else to restore.
- **After cutover, if a late failure appears:** reinstall the old
  build — `app/Muse-Phone-Bridge-1.3.2.apk` (SHA-256
  `1f2055ca02987af63cfea50d9610200223b2ec73d3ba4a7601a2d9ea7cd45cae`)
  is kept installable in `app/` and must not be deleted until the
  new package has run clean through at least one full day/night
  cycle. Its grants were removed by its uninstall, so re-run the
  §8 grants for the **old** package and have Jonathan re-enter the
  Worker secret + Start bridge — the same fresh-identity cost,
  which is why cutover waits for the full §9 pass.
- Phase 2 needs no rollback step: it was never armed or linked in
  either package during the swap.

---

## 11. Surfaces to update AT SWAP TIME (not tonight — noted per prep scope)

In the same handling as the verified cutover, and only then:
- `SKILL.md` — **both copies** (`~/workspace/muse-device-assistance/SKILL.md`
  and `~/workspace/custom-skills/local-muse-device-assistance/SKILL.md`,
  byte-identical): Identity section package/label, the `pm grant`
  example, the source-map path
  (`app/src/com/jonathancruzate/metamusedeviceassistance/`), and the
  rename-status paragraphs.
- Connector doc `~/workspace/custom-connectors/custom.device-assistance/CONNECTOR.md`:
  the "label still Muse Phone Bridge" staging notes.
- `RENAME-PLAN.md`: mark step B executed with the build/verification
  evidence; staged step A (GitHub repo rename
  `muse-phone-bridge` → `muse-device-assistance`) remains
  Jonathan's one manual step and is independent of this swap.
- `~/workspace/phone-toolkit/` audit baselines and the cron bodies
  that name the app (nightly-automations-review §13 census;
  adb-tooling-backlog build run item 3) — per RENAME-PLAN.md step C,
  they flip when the renamed APK ships, not before.
- `github-export/` snapshot + Release for the renamed build
  (owned by the push lane — no pushes were made in this prep).
- VPS build-directory check from RENAME-PLAN.md step D
  (`ls -d ~/muse-phone-bridge` on the box) — still unverified;
  do it in a session with a working box lane before assuming there
  is nothing to rename there.

## Blockers / open items for tomorrow

- None for the build: the renamed APK exists and is verified (§6).
- Swap requires Jonathan awake (secret entry + Start bridge +
  battery confirmation are his taps) and both morning alarms fired
  — it cannot be done unattended or overnight.
- GitHub repo rename (staged step A) is Jonathan's manual step;
  it does not block the APK swap.
