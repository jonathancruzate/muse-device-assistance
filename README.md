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

## v1.30.2 (2026-11-21) — Android TV banner, installed on the Shield TV

- **TV banner added** (Jonathan order, same night as the other Muse TV banners): `android:banner` on the application + MainActivity, art in `res/drawable/tv_banner.png` + `res/drawable-xhdpi/tv_banner.png` (320x180). House style: Muse M + orange phone badge, text reads **"Muse DA"** (shortened at his order so the type matches the other banners; the app name is unchanged). `LEANBACK_LAUNCHER` was already declared since the v1.5.0 TV profile, so no intent changes; phone behavior unchanged, no code changes.
- Built via `app/build.sh`, all guards green; banner extracted from the built APK is pixel-identical to the approved export (0 differing px, both densities); apksigner family cert `4dcb45fa...` unchanged. APK `Muse-Device-Assistance-1.30.2.apk`, SHA-256 `383140e2908b9e21b3287e1dcf37ec0759af990d7f2cad00e94e2fa122e94d77`.
- **Installed on the Shield TV** (`adb install -r` over v1.22.0): read-back 1.30.2 / vc43; installed base.apk hash-identical to the staged APK; leanback intent resolves; BridgeService live on the TV. Visual proof: Projectivy All apps renders "Muse Device Assistance" with the new "Muse DA" banner (`docs/v1.30.2-tv-banner-2026-11-21.md`, screenshot `docs/v1.30.2-tv-allapps-proof.png`). Not installed on the phone (its 1.30.1 install is owned by the parallel handling).

## v1.30.3 (2026-11-22) — TV banner adopts the Google TV template packaging, installed on the Shield TV

- **Banner packaging only** (Jonathan's "Adopt" order, 2026-11-22, against the official Google TV Figma template by @tv): the v1.30.2 flat drawable + drawable-xhdpi banner is restructured into the template's legacy ladder (drawable-mdpi 160x90, hdpi 240x135, xhdpi 320x180, xxhdpi 480x270, xxxhdpi 640x360, default drawable copy kept) plus the adaptive set: `res/drawable-anydpi-v26/tv_banner.xml` with separate `@drawable/tv_banner_background` (solid #F0F2F5) and `@drawable/tv_banner_foreground` (logo + name on transparent) layers in drawable-xxxhdpi. Art unchanged ("Muse DA"); no code changes; phone behavior unchanged.
- Built via `app/build.sh`, all guards green; every banner entry extracted from the built APK is pixel-identical to its export (0 differing px, all sizes + both layers); aapt2 badging shows the banner resolving to the anydpi adaptive XML; apksigner family cert `4dcb45fa...` unchanged. APK `Muse-Device-Assistance-1.30.3.apk`, SHA-256 `b9fb00c94a1cefe6051f9e58bba280688448cff90d5937e7a02521f645dca1f3`.
- **Installed on the Shield TV** (`adb install -r` over v1.30.2): read-back 1.30.3 / vc44; installed base.apk hash-identical to the staged APK; leanback query resolves MainActivity. Not installed on the phone (its 1.30.1 install is owned by the parallel handling). Record: `docs/v1.30.3-tv-template-banner-2026-11-22.md`.
