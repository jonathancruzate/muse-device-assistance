# Samsung Routines template exports (for Device Assistance routine_fire setup)

**Purpose:** setup help for re-linking Muse Device Assistance's `routine_fire`
keys after a phone reset or on a new phone. Device Assistance v1.35.0+ fires
Samsung routines through shortcut intents captured in-app via Samsung's own
CREATE_SHORTCUT picker (one Link tap per key, in the app's "Routine firing"
section). These exports document which Samsung routines each key maps to, and
the structure/names to recreate or pick, so a fresh setup can be rebuilt by
matching the names in the table below in Samsung's picker.

The JSON files in `2026-10-12/` are Samsung Modes and Routines exports dated
2026-10-12: 15 primary files for the 16 `routine_fire` keys (one key has no
matching export, see the table) plus 15 companion routines that the primaries
work alongside.

## Mapping: routine_fire key -> live linked routine -> export file

Live linked names verified from the phone's Device Assistance relay report,
2026-11-26T05:00:00Z (all 16 keys linked).

| Key | Live linked routine (2026-11-26) | Export file in `2026-10-12/` |
|---|---|---|
| sleep | Switch Sleep mode | `Turn on Sleep mode.json` |
| home | Switch Home mode | `Turn on Home mode.json` |
| away | Switch Away mode | `Turn on Away mode.json` |
| morning | Turn on Wake-up time | `Turn on Wake-up time.json` |
| theater | Switch Theater mode | `Turn on Theater mode.json` |
| driving | Switch Driving mode | `Turn on Driving mode.json` |
| exercise | Switch Exercise mode | `Turn on Exercise mode.json` |
| work | Switch Work mode | `Turn on Work mode.json` |
| relax | Switch Relax mode | `Turn on Relax mode.json` |
| before_bed | Turn on Before bed | No matching export |
| commute_home | Turn on Commute to Home | `Commute to home.json` |
| commute_work | Turn on Commute to work | `Commute to work.json` |
| meeting | Meeting | `Meeting.json` |
| power_standard | Switch Power saving standard | `Muse power saving standard.json` |
| power_max | Switch Power saving standard | `Muse power saving maximum.json` |
| status_full | Device status full | `Device status full.json` |

Notes on the table:

- `power_max` intentionally reads back the same linked routine as
  `power_standard` ("Switch Power saving standard"). That is deliberate, not a
  mislink: Maximum power saving is manual-only by the device owner's standing
  order, so the automated leg never fires Maximum. The maximum template export
  is included for completeness/manual reference.
- `before_bed` has no matching export in the 2026-10-12 set. The closest
  companions are included instead: `Before bed.json` and
  `Muse - Before bed.json`, which share the action set but are neither the
  linked "Turn on Before bed" routine.
- Companion files also included: `Muse - Home.json`, `Muse - Theater.json`,
  `Muse - Driving.json`, `Muse - Exercise.json`, `Muse - Work.json`,
  `Muse - Relax.json`, `Muse - Commute to Home.json`,
  `Muse - Commute to work.json`, `Muse - Meeting.json`, `Home.json`,
  `Away.json`, `Wake-up time.json`, and `Muse power saving off.json`.

## Privacy audit (why these are safe in a public repo)

Re-verified 2026-11-26 before pushing, across the full 75-file 2026-10-12
export corpus (the 30 files here are a subset):

- Every file carries only these keys: `document_id`, `version`, `name`,
  `conditions[]`, `actions[]`. The `document_id` is the generic value
  `com.samsung.android.app.routines/routine` in every file, not a unique id.
  Each condition/action carries only `package`, `tag`, `intent_param`,
  `is_negative`.
- Secret/URL scan across all 75 files: 0 hits. There are no URLs at all.
- Only 12 non-empty `intent_param` values exist in the whole corpus, all
  generic: Dolby preset codes (11111111 / 22222222 / 33333333), wear toggles
  ("Raise wrist to wake", "Power saving" on/off labels), and a Routine+
  shortcut fileName "Work Profile Toggle" (only in the two Toggle Work
  Profile files, which are not in this push set).
- No locations or coordinates (GPS-tagged steps carry empty `intent_param`),
  no Wi-Fi SSIDs, no device IDs, no contacts, no addresses, and no names of
  people appear anywhere in the corpus.

## Fidelity warning: templates, not import-and-go backups

Samsung's export strips the operative values: `intent_param` is empty for
most steps, mode targets survive only in the routines' names, and most
settings values are absent. These files are structure and naming templates:
they show which conditions/actions a routine is built from, in order, so it
can be recreated or re-picked accurately. They are NOT faithful
import-and-restore backups, and import fidelity has never been proven.

Several linked "Switch * mode" routines were renamed after this 2026-10-12
export, which is why most primary files carry the older "Turn on * mode"
names. Match on the table above, not on filename alone.
