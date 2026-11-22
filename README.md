# Muse Device Assistance

The phone-side control plane for Jonathan's Galaxy S24+ — the custom
app that **complements MacroDroid** by owning the truth, the
verification, and the reconciliation logic Samsung's gated mode
surfaces leave to everyone else, now being extended from bridge
access to **full device control** as the engine of the Device
Assistance connector.

**Renamed 2026-11-03 (Jonathan's order): Muse Phone Bridge → Muse
Device Assistance.** (An interim name applied earlier the same evening
was superseded minutes later; Muse Device Assistance is the final
name.) The rename of the directories, skill, connector,
and docs is executed; the installed app's label and Android package
are unchanged until the staged APK rename build ships (a package
rename is a new app identity — see `RENAME-PLAN.md`, which carries
the full execution record, the staged steps, and Jonathan's one
manual step: the GitHub repo rename).

## Where things live

- Canonical local directory: `~/workspace/muse-device-assistance/`
  (this directory). The old `~/workspace/muse-phone-bridge/` path no
  longer exists.
- GitHub repo: `jonathancruzate/muse-phone-bridge` until Jonathan's
  rename step lands, then `jonathancruzate/muse-device-assistance`.
  Snapshot source: `github-export/` here; sync record:
  `github-export.muse-github-snapshot-sync.json` (its `repo` field
  says which repo name is live for pushes).
- Companion skill (per the APK companion-skill rule): `SKILL.md` at
  this root, identical installed copy at
  `~/workspace/custom-skills/local-muse-device-assistance/SKILL.md`.
  **The skill and `github-export/README.md` govern current version
  and capability** — currently app v1.3.2 (versionCode 6), package
  `com.jonathancruzate.metamusephonebridge`, label "Muse Phone
  Bridge".
- Connector: `~/workspace/custom-connectors/custom.device-assistance/`
  — the Device Assistance connector fronts this app; its doc carries
  the command table (EXISTS in the APK today vs the full-device-
  control EXTENSION roadmap).
- Pre-rename project README, preserved verbatim:
  `docs/project-readme-pre-rename-2026-11-03.md`. Build records and
  full version history: `github-export/README.md` and
  `github-export/docs/`.

## Safety bounds (summary; the skill governs)

- Phase 2 actuation stays DISARMED unless Jonathan arms it in the
  app — the rename does not change that, and nothing here arms,
  links, or test-fires a routine.
- Wire names are a live contract, not branding: Worker endpoint
  `/phone-bridge/report`, KV key `phonebridge:report`, report
  `device:"phone-bridge"` stay as they are in the installed build.
- Samsung Routines stays the actuator for modes; MacroDroid keeps
  its non-mode jobs (spoken announcements and the rest of the
  phone-automations surface).

## Self-test sweep note (Muse, 2026-11-21, Surface Duo test device)
Overnight sweep per the android-selftest workflow: package com.jonathancruzate.metamusedeviceassistance versionName 1.26.0 installed on the Duo; BridgeService has a live ServiceRecord; MainActivity launches without error. The Duo wake ritual fired during the test and a Muse voice session came to the foreground, so a deeper in-app UI pass was deferred rather than fought over the live session. No bugs found in what was exercised. The other Muse-built apps (Wear OS, Wear Installer, Meta Muse Widget, Android Auto, agentring, Galaxy Buds plugin) are not installed on the Duo and were not covered by this sweep; they need an S24+ / Watch session.

## v1.30.1 (2026-11-21) — unique Muse app icon

Icon-only release (versionCode 42). Launcher icon is now this app's own
from the approved unique Muse app icon set (`~/workspace/muse-app-logos/`):
original Muse blue M on a light tile plus an orange phone badge,
superseding the 2026-11-01 all-same-icon rule. Legacy mipmaps + round +
adaptive icon (background #F0F2F5, foreground at 76% centred). Verified:
icon extracted from the built APK matches the approved art (pixel-identical
to the regenerated clean set), apksigner verify passed in build. APK:
app/Muse-Device-Assistance-1.30.1.apk, SHA-256 `4fe9c85a6fb56fd5663406ae788cceb8081bbb7299f4a1f6113648e01bb3197a` (file re-signed at
03:46 by a parallel handling at identical size/version/icon; this is the
current on-disk hash). Not installed by this handling; ready to install.
