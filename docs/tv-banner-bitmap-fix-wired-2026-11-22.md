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
