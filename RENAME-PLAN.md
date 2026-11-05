# Rename execution plan — Muse Phone Bridge → Muse Device Assistance

Ordered by Jonathan on a live voice call, 2026-11-03 (19:26 EST): the
Device Assistance project is not a new project — the Muse Phone Bridge
project is renamed to **Muse Device Assistance** and its APK is reused
and extended from bridge access to full device control. Companion
standing rules applied: the project-directory slug rule (repo +
canonical local directory + VPS build directory renamed in the same
handling, snapshot-sync records / build scripts / cron bodies / docs
updated with them) and the 2026-11-03 AGENTS.md rule that a rename
also renames the APK's label and Android package, sequenced around
the Android reality that a package rename is a **new app identity**
(fresh install; permissions, role holders, and app data do not carry
over).

## Final-name correction (same evening, 2026-11-03)

The rename was ordered twice on the same call: an interim name,
**Muse Device Controls**, was applied first, and Jonathan superseded
it minutes later with the final name, **Muse Device Assistance**
(slug `muse-device-assistance`). A final-name sweep the same evening
moved every surface the interim pass created or updated — project
directory, connector scaffold (`custom.device-assistance`), companion
skill (`local-muse-device-assistance`), docs, and the Galaxy Buds
plugin references — to the final name. Nothing about the staging
below changes: the APK label + package rename stays staged (target
label "Muse Device Assistance", target package
`com.jonathancruzate.metamusedeviceassistance`), Phase 2 actuation
stays DISARMED, and Jonathan's one manual step is now the GitHub repo
rename `muse-phone-bridge` → `muse-device-assistance` (staged step A).

## What the renamed app already is (v1.3.2, verified from the repo)

- 60 s bridge loop: fetch desired house mode from the mode-sync
  Worker, reconcile against ground truth, report on change +
  heartbeat at least every 15 min; restarts on boot.
- Ground truth from the Modes and Routines app's own status dumps
  (NotificationListenerService) with a verified-ack discipline:
  `ack=true` only when observed matches desired.
- A status set in every report: battery %, charging, power-save
  boolean, screen on/off, ringer mode, network kind, free storage.
- ADB endpoint discovery over mDNS + wireless-debugging self-heal
  (holds `WRITE_SECURE_SETTINGS`).
- Tailscale reviver (v1.3.2) with per-episode notifications.
- Phase 2 actuation machinery (capture-based routine firing, night
  only) shipped **DISARMED** — arm switch default OFF, Jonathan's
  in-app gates. The rename changes none of this.

## Executed in this pass (2026-11-03, local, verified)

1. **Canonical local directory renamed:**
   `~/workspace/muse-phone-bridge/` → merged into the existing
   `~/workspace/muse-device-assistance/` (the pass-1 project home).
   Contents moved: `app/`, `github-export/`, `SKILL.md`,
   `github-export.muse-github-snapshot-sync.json`. The pre-rename
   project README is preserved verbatim at
   `docs/project-readme-pre-rename-2026-11-03.md`. Verified: old path
   no longer exists; no script in `~/workspace/bin/` references the
   old path (grep-verified — the ADB lanes read state files, not the
   project dir), `app/build.sh` carries no self-path (only SDK/JDK
   paths), and no goal script references the old path.
2. **Companion skill renamed:**
   `~/workspace/custom-skills/local-muse-phone-bridge/` →
   `~/workspace/custom-skills/local-muse-device-assistance/`, retitled
   "Muse Device Assistance", with the rename status stated at the top:
   project/skill/connector renamed; installed app label, package, and
   wire names explicitly unchanged pending the staged build below.
   All three copies (project root, installed, `github-export/`) are
   byte-identical (md5-verified).
3. **Snapshot-sync record updated:** every `source_dir` in
   `github-export.muse-github-snapshot-sync.json` now points at
   `/home/hatch/workspace/muse-device-assistance/github-export`
   (JSON re-validated). The `repo` fields deliberately still say
   `muse-phone-bridge` — see staged step A.
4. **Connector doc reworked:** the Device Assistance connector now
   names this APK as its engine, with the command table split into
   EXISTS-in-APK / EXISTS-delegated / EXISTS-catalog / EXTENSION.
5. **Project README rewritten** for the merged project; a rename
   banner was prepended to `github-export/README.md` so the repo
   itself documents the rename before its own name changes.
6. **Cross-references updated:** the Galaxy Buds connector doc's
   sibling-skill link now points at `local-muse-device-assistance`.

## Staged items — status updated at the 2026-11-05 rename execution

### A. GitHub repo rename — EXECUTED 2026-11-04

The repo is now `jonathancruzate/muse-device-assistance` (verified
2026-11-04/05: pushes addressed to the old name redirected and landed
in the renamed repo, contents intact). The manual-step instructions
below are kept for the record.

The Muse GitHub App cannot create or rename repositories, so this
is his single step, precisely:

1. Open https://github.com/jonathancruzate/muse-phone-bridge
2. **Settings** tab → **General** → Repository name → change
   `muse-phone-bridge` to `muse-device-assistance` → **Rename**.

(GitHub redirects the old URL, so nothing breaks in the interim.)
The moment it lands, Muse flips, in one follow-up handling: the
`repo` fields in the snapshot-sync record, the repo line in the
skill's identity section and this plan, and pushes the next snapshot
+ Release under the new repo name. Until then, pushes keep targeting
`muse-phone-bridge` — flipping the record early would aim the next
snapshot push at a repo that does not exist yet.

### B. APK label + package rename — EXECUTED 2026-11-05

**Executed 2026-11-05, verified end-to-end** (build/sequence detail:
`RENAME-BUILD-PLAN.md` §§6–9). Evidence: the renamed APK was
installed alongside the old package; every re-grant was applied and
read back (notification-listener access for the new
`GroundTruthListener` — including removal of a stray user-95 clone
listener entry, `WRITE_SECURE_SETTINGS`, deviceidle whitelist,
`POST_NOTIFICATIONS`, `NEARBY_WIFI_DEVICES`); Jonathan entered the
Worker base URL + secret in the new app and pressed Start bridge;
fresh reports from the new package landed in `phonebridge:report`
with `parse_ok=true` ground truth; the alarm lanes were verified
end-to-end (standing Vyvanse 05:30 / Wake up 06:30 alarms intact,
test alarm set/fired/dismissed); the old package was then
uninstalled and the new app kept reporting after cutover. The
canonical sources were renamed in the same handling
(`RENAME-BUILD-PLAN.md` §3) and rebuilt: 23 app classes, dex guards
passed, signer cert SHA-256
`4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236`
unchanged, badging package
`com.jonathancruzate.metamusedeviceassistance` versionCode 6 /
versionName 1.3.2, label "Muse Device Assistance", APK SHA-256
`3b2adc07d64eb000369f316bfa6f713a376e49c357109aba842166d82ab06166`.
The staged text below is kept for the record.

Per Jonathan's rule the APK's label becomes "Muse Device Assistance"
and the package becomes `com.jonathancruzate.metamusedeviceassistance`.
This was staged, not executed 2026-11-03, because it is a new app
identity and swapping it under tonight's live lanes (bridge reports,
Tailscale reviver, ADB self-heal) without the full sequence would
silence the bridge: the new package starts with no notification
access, no battery exemption, no `WRITE_SECURE_SETTINGS` grant, no
Worker secret, and no Phase 2 state. Sequence when it is built
(daytime, ADB lane up, Jonathan reachable):

1. Build the renamed APK from this project's sources (label +
   applicationId + source package path renamed; signing key
   unchanged — irrelevant across packages, kept for consistency).
2. **Keep the wire contract identical** in the renamed build: Worker
   base URL, `GET /mode-state`, `POST /phone-bridge/report`, KV key
   `phonebridge:report`, report `device:"phone-bridge"`, prefs and
   log names. Wire names are a live contract with the Worker, not
   branding; rename them only in a later coordinated app+Worker
   release, or never.
3. Install the new package **alongside** the old one, then run the
   reinstall + re-grant sequence. Split by who performs each step:
   - **Automatable by Muse (build + ADB lane, no taps):** build the
     renamed APK; install it alongside the old package
     (`adb install`); grant notification-listener access for the new
     package (`cmd notification allow_listener`); add the battery
     exemption (`dumpsys deviceidle whitelist +<new package>`);
     `pm grant` `WRITE_SECURE_SETTINGS` for the **new** package —
     the same one-time grant pattern the current package received.
   - **Jonathan's taps (his alone, never automated):** enter the
     Worker base URL + mode-state secret in the new app's setup
     screen (the secret lives only in on-device prefs — Muse never
     extracts, carries, or re-enters it); press **Start bridge**;
     confirm unrestricted battery in Samsung's UI if the ADB
     whitelist does not fully register it. If Phase 2 is ever armed
     in future, re-linking routines and arming in the new app are
     likewise his taps only — the arm/link gates never move.
   - Nothing carries over by itself: the new package starts with no
     notification access, no battery exemption, no secure-settings
     grant, no Worker secret, no prefs, and no Phase 2 state —
     every item above exists precisely because a package rename is
     a new app identity.
4. Verify end-to-end before any cutover: fresh reports from the new
   package landing in `phonebridge:report` with `in_sync` verdicts,
   ADB endpoint + Tailscale objects present, one heartbeat cycle.
5. Only then: stop + uninstall the old package, and in the same
   handling update the surfaces that key on the old identity —
   `~/workspace/phone-toolkit/` audit baselines (the Tending §13
   census names Phone Bridge / its package), the cron bodies that
   name the app (nightly-automations-review §13; the
   adb-tooling-backlog build run's item 3), and this skill's
   identity section.
6. Phase 2 stays DISARMED through the whole swap: nothing is linked
   in either package until Jonathan runs the link/arm gates in the
   new app himself.

### C. Cron bodies carrying the old name — staged with step B

Two saved job bodies name "Phone Bridge": nightly-automations-review
§13 (the phone readiness audit keys its census on the installed app)
and adb-tooling-backlog-build-run item 3 (tomorrow 2026-11-04
15:22 — "the repo README's v1.4 target"). Neither carries the old
filesystem path (grep-verified), so tonight's directory rename does
not break them, and their prose still accurately names the installed
app. Their name references flip when step B ships the renamed APK —
flipping them tonight would make the audit look for an app identity
that does not exist on the phone yet.

### D. VPS build directory — CHECKED 2026-11-05, nothing to rename

Checked over the tailnet SSH lane 2026-11-05: neither
`~/muse-phone-bridge` nor `~/muse-device-assistance` exists on the
box — there is no box-side build checkout for this project, so no
rename was needed. The staged text below is kept for the record.

Builds for this project run on this VM with the local toolchain (the
fleet APK pattern), and no build script points at a box-side
checkout. However, this pass could **not** verify the box directly:
SSH to `muse@167.233.41.12` from the rename session was refused
(publickey denied), so a `muse-phone-bridge` directory on the box —
if one exists — was not inspected or renamed. Staged check for the
next session with a working box lane: `ls -d ~/muse-phone-bridge`
on the box; if present and it is a build checkout (not a live
service's working directory), rename it to `muse-device-assistance`
and grep the box's scripts for the old path in the same handling. Do
not rename anything on the box that a running service reads from
without tracing its consumers first.

### E. Full-device-control extension — roadmap, owned by the v1.4 build

The command set (app launch incl. Google Maps, navigation intents,
media + volume, flashlight, foreground read, native DND) is defined
in the connector doc as EXTENSION rows. Transport is the app's
command channel — the v1.4 target (in-app pairing, loopback ADB
client, allowlisted commands over the Worker channel), which the
2026-11-04 backlog build run is already scoped to design + build.
No extension command is claimed working until it is verified on the
S24+ by report/read-back. Device-specific support is **not** part of
this core extension scope: per Jonathan's plugin architecture
decision (2026-11-03), the core APK stays sleek and slim and device
support plugs in as device connectors registered against the host —
Galaxy Buds first (namespace `buds`; its verb contract, e.g.
`buds.SET_VOLUME`, is a plugin interface defined in the Buds design
note, not core-APK scope), more devices later the same way. The
plugin model is described in the connector doc.

## Explicitly NOT renamed in this pass

- Wire names: Worker endpoint `/phone-bridge/report`, KV key
  `phonebridge:report`, report field `device:"phone-bridge"`, the
  `phonebridge` token in server-side consumers — live contract
  between the installed app and the Worker (see step B.2).
- The `phonebridge` prefs file and `bridge-log.txt` on-device names.
- Historical records: build records, runbooks, and dated docs keep
  the name they were written under, with the rename banner carrying
  the present truth.
