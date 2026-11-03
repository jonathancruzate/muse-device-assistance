# Phase 1 calibration findings — 2026-11-03

Live-verified on the phone (Galaxy S24+, Modes and Routines 5.1.02.9) during the
first end-to-end shadow run, 2026-11-03 ~01:15 EST.

## How ground truth actually arrives

- The "Device Status" dump is a **transient** notification from
  `com.samsung.android.app.routines`, posted when the status macro fires
  (`status_full`, and on its own ~3–8 min cadence while the phone is active).
  It is NOT a persistent shade card. Between fires, the only Routines cards are
  the per-item "X running" cards and their group summary.
- Dump format (bigText): zero-width-space-prefixed, pipe-separated fields.
  Field 1 = current Samsung Mode; field 2 = running list in Samsung's
  rendering ("At Home, Performance profile, or 1 other").
- The bridge listener captures and parses it correctly (parse_ok=true on the
  first live capture, report kind "change").

## Calibration finding: "None" + "At Home" running == home

First live capture: desired=home, observed field 1 = **"None"**, field 2 =
"At Home, Performance profile, or 1 other" → bridge verdict **drift**,
ack=false, plan "fire Home directly".

The incumbent mode-sync system treats this exact signature as the aligned
home state (verified 2026-11-02: Worker/SmartThings/Home Assistant/phone all
aligned with the dump reading Mode=None and "At Home" in the running list).
On this phone the house modes are largely routine-held: the mode name lives
in the running list (field 2), while field 1 stays "None".

**Rule for the next build (v1.2):** when field 1 is "None" and field 2 names
a known house mode's routine ("At Home", "Switch Sleep mode"/Sleep,
"Morning"), the observed mode is that mode. Verdict/ack must be computed
against the resolved observed mode, not field 1 alone. Until then, shadow
verdicts of "drift" against desired=home with this signature are expected
and are NOT real drift.

## ADB endpoint note

mDNS discovery reports the Wi-Fi LAN address (e.g. 192.168.1.153:38865);
the VM reaches the phone on its tailnet address. Consumers of the report's
`adb.endpoint` must take the reported port and dial the tailnet host
(adb-ensure.sh already does exactly this, hardened 2026-11-03).
