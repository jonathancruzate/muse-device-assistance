# Relay files/fetch format-sniff fix (2026-11-24)

**Type:** relay-only fix (device-relay on the box). No app change, no
version bump: the app was sending correct bytes and a correct hash;
the defect was entirely in the relay's accept step.

**Trigger:** the first phone-local ABG audit pass (2026-11-24) fetched
and SHA-256-verified 255 images through the v1.36 lane, but 30 files
in Pictures/Reddit failed reproducibly with `files_upload_failed`.

## Root cause (diagnosed with evidence, not theory)

A temporary diagnostic line in the box relay's `/files/fetch` handler
logged the claimed ext, size, SHA match, magic verdict, and the first
16 bytes of one failing upload (RDT_20250811_211919976537852691228719.jpg):

```
FILESFETCH-DIAG ext=jpg len=45542 shaok=true magicok=false
head=52494646deb100005745425056503820
```

The head bytes are `RIFF` + size + `WEBPVP8 `: the file is WebP,
named .jpg. The v1.36 relay verified magic bytes against the CLAIMED
extension, so every mislabeled file was refused as a magic mismatch
even though its SHA-256 verified and its bytes were a genuine image.
Across the 30 failures, the real formats were 27 WebP and 3 PNG, all
named .jpg (Reddit's app saves downloaded images in their served
format under a .jpg name).

## Fix

`relay/server.js`, `handleFilesFetchPost`:

- New `filesSniffExt(buf)`: sniffs the real format from magic bytes
  (JPEG FF D8 FF; PNG 8-byte signature; GIF `GIF8`; WebP
  `RIFF....WEBP`) and returns the canonical extension, or null.
- The handler now accepts when the sniffed format is a supported
  image, whatever the claimed extension said, and stores the binary
  under the REAL format's extension (`files_fetch_latest.<realext>`).
  Bytes matching no supported image format are still refused with
  `magic bytes mismatch`. The claimed extension must still be one of
  the supported image types (the app's extension gate is unchanged).
- The `files:fetch` meta record now carries `ext` (real, used by the
  bin route and MIME), `claimed_ext`, and `format_mismatch`; the POST
  response also returns `ext` and `claimed_ext`.
- Security posture preserved: SHA-256 end-to-end verification still
  runs before acceptance and still refuses mismatches; images-only
  still enforced, now by verified bytes rather than by name; the
  2,500,000-byte cap is unchanged.

## Deployment

- Box backup before deploy: `~/device-relay/server.js.bak-20261124-pre-filesfetch-sniff`
  (the diagnostic build was backed up separately as
  `server.js.bak-20261124-pre-filesfetch-diag` and is fully removed:
  the deployed file contains zero diagnostic lines).
- Deployed from the project copy (verified identical to the box copy
  apart from the diagnostic line before the fix), `node --check`
  clean, `device-relay.service` restarted and verified active.

## Verification

- The exact file that failed in diagnosis was re-fetched after the
  fix: command `ok`, stored as `files_fetch_latest.webp`, and the
  pulled file's SHA-256 (`0d089c227773...`) matched the phone-reported
  hash byte for byte.
- All 30 previously failed Pictures/Reddit files were then fetched,
  hash-verified (30 of 30 matches, zero errors), and visually judged
  for the ABG audit: every one is a meme or reaction image, zero
  Tier 1, zero removals. Per-file verdicts are recorded in the
  addendum to `~/workspace/abg-audit-inventory-2026-11-24/phone-pass-2026-11-24.md`.
- The 2 over-cap Reddit GIFs (3,552,279 and 4,593,043 bytes) were NOT
  fetched: the cap is unchanged, and their base64 payloads would also
  exceed the relay's 4 MB request-body limit. They remain listed as
  unverified by design, not by defect.
