# TV banner bitmap fix — wired in tree 2026-11-22, ships with the next build

## Why

The 2026-11-22 Google TV template adoption (v1.30.3) made
`android:banner` resolve to `res/drawable-anydpi-v26/tv_banner.xml`, an
`<adaptive-icon>` wrapping `tv_banner_foreground` /
`tv_banner_background`. Projectivy renders an adaptive banner
drawable with the AdaptiveIconDrawable safe-zone crop: agy's measured
consult (`~/workspace/muse-app-logos/agy-banner-consult/agy-report.md`)
found an isotropic ~1.50x center-zoom losing ~16.7% on every edge,
clipping the M's left peak in the favorites row. The stock launcher
does not crop, and the pre-adoption flat-PNG banner (v1.30.2) rendered
uncropped in the identical slot.

Controlled confirmation on the TV (2026-11-22): after Muse Gadget
0.11.7 and Muse Ambient 0.4.3 shipped with bitmap banners and the
launcher cache was refreshed (`pm trim-caches` + reboot), both render
complete in the same row where DA 1.30.3, still on the adaptive
banner, still shows the M sliced at the card's left edge
(`~/workspace/muse-app-logos/banner-fix-2026-11-22/bitmap-fix-da-focused.png`).

## What changed in this tree (res only, no version/build change)

- Deleted `app/res/drawable-anydpi-v26/tv_banner.xml`.
- Deleted `app/res/drawable-xxxhdpi/tv_banner_foreground.png` and
  `app/res/drawable-xxxhdpi/tv_banner_background.png` (referenced only
  by that XML).
- Kept: the full flat ladder (`drawable/tv_banner.png` +
  drawable-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi), pixel-identical to the
  muse-app-logos exports. Launcher adaptive icons
  (mipmap-anydpi-v26) untouched. Banner artwork unchanged.

With these deletions, `android:banner` resolves to the bitmap ladder
on every API level, exactly as in v1.30.2.

## Why no build here

This tree is the v1.31.0 line (build.sh already at
VERSION_CODE=45 / VERSION_NAME 1.31.0, Duo plugin extraction), owned
by that handling; the banner task's v1.30.4 slot no longer exists.
The fix is therefore wired in-tree only: **the next DA build from
this tree (1.31.0) carries the bitmap banner automatically.** Until
that build is installed on the Shield, the TV keeps v1.30.3 (vc44)
and its banner still crops in Projectivy. The phone is unaffected
(stays on 1.30.1 under the owning handling; banner is TV-only).

## Follow-up 2026-11-23: shipped and verified on the Shield

Jonathan reported at 23:50 EST that the DA banner was still broken.
Live probe showed why, in two parts:

1. The Shield never received 1.31.0: it was still on 1.30.3 (vc44),
   lastUpdateTime 2026-11-22 00:26:02, and the pulled base.apk's
   banner still resolved to `res/drawable-anydpi-v26/tv_banner.xml`.
   The crop was re-confirmed on screen with DA focused
   (`~/workspace/muse-app-logos/banner-fix-2026-11-22/da-state-2026-11-23.png`).
2. The 1.31.0 APK in this workspace would not have fixed it anyway:
   built 2026-11-22 07:54, before the wire-in above landed, its
   badging still shows the adaptive banner and the fg/bg entries.

The first build carrying this fix is 1.33.0 (vc48): badging banner =
`res/drawable-mdpi-v4/tv_banner.png`, no anydpi banner XML or fg/bg
entries, xxxhdpi banner pixel-identical to the master export. It
was installed on the Shield only (`install -r`; streamed install
after incremental was refused). Read-back: 1.33.0/vc48,
lastUpdateTime 2026-11-23 23:57:41; installed base.apk SHA-256
`c2fa1dd6beb2ed2569f2e6ff9a2b9251894d6be575880294654b45efb88b06f5`,
byte-identical to the workspace APK. After `pm trim-caches` +
reboot (GadgetService relaunched post-reboot per procedure; DA
BridgeService running), rendering is verified: focused and
unfocused cards both show the complete M, the full badge, and
"Muse DA" with margins
(`da-fixed-focused-2026-11-23.png`, `da-fixed-unfocused-2026-11-23.png`
in the banner-fix folder). The banner item is closed; the phone was
not touched.
