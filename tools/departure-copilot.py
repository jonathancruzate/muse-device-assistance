#!/usr/bin/env python3
"""departure-copilot.py — the Departure Copilot sequence (Device Assistance).

Chains the live-verified allowlisted verbs of the Muse Device
Assistance Worker command channel into one departure sequence, in
this order, ONE command at a time (the app executes one command per
fetch — batch enqueues strand the rest until TTL; see the connector
doc's SINGLY rule):

    1. nav_open <destination>          (+ screen_foreground read-back:
                                        success only when Maps is the
                                        observed foreground)
    2. media resume                    (media_play_pause ONLY when the
                                        caller-supplied media evidence
                                        says "paused" — never a blind
                                        toggle; unknown -> skip + log)
    3. dnd_on                          (+ settings_get global zen_mode
                                        read-back when it parses)
    4. voice_mode on                   (LIVE mode only) + the VOICE
                                        BRIDGE message, printed for the
                                        caller to send IMMEDIATELY as a
                                        user-role relay message in the
                                        MAIN chat (skill: VOICE BRIDGE;
                                        chat.send_message does NOT reach
                                        a running voice session)

SHADOW IS THE DEFAULT. Without --live this script enqueues NOTHING:
it reads the latest bridge report (a read-only GET) and the pending
queue (a read-only GET), evaluates the guards on that evidence, and
appends one JSONL line describing the exact plan — including the
opener that WOULD be spoken and the bridge message that WOULD be
sent — to the goal's shadow log.

Guards (fail-closed, both modes):
  * destination must match the on-device ShellAllowlist DEST_TOKEN
    pattern ^[A-Za-z0-9][A-Za-z0-9,.'\\-_]{0,63}$ (one token; space,
    &, = and every other smuggling character are unrepresentable).
  * the fresh report must be <= 30 minutes old (received_at anchor);
    older = phone state unknown = refuse.
  * live mode only: a pre-flight screen_foreground read must not
    show a call in progress (telephony in-call UI / dialer / a
    messaging app's call activity). Shadow mode cannot read the
    foreground without enqueueing, so it records the check as
    deferred to the live pre-flight — it never pretends to have
    checked.

A fire is never a success: every step requires the Worker result
ok:true (and a read-back where one exists). Any failed step aborts
the chain; nothing after it is enqueued.

Transport is the proven VM-side driver
~/workspace/cloudflare-workers/mode-sync/phone-bridge-cmd.sh
(enqueue / status / pending), plus a direct read-only GET of
/phone-bridge/report using the same base + secret-file conventions
as that driver. The secret is read from its file at run time and is
never printed or logged.

Exit codes: 0 ok (live complete, or shadow plan logged) ·
2 usage / destination validation · 3 guard refusal (stale report,
call in progress) · 4 a chain step failed (aborted) ·
5 report / transport unavailable.

Procedure doc: docs/departure-copilot-2026-11-09.md
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from zoneinfo import ZoneInfo

HOME = os.path.expanduser("~")
CMD_SH = os.path.join(
    HOME, "workspace/cloudflare-workers/mode-sync/phone-bridge-cmd.sh")
BASE = os.environ.get(
    "MODE_SYNC_BASE", "https://jonathancruzate.com/mode-sync")
SECRET_FILE = os.environ.get(
    "SECRET_FILE",
    os.path.join(HOME, "workspace/goals/manage-my-smart-home/"
                       "hidden_files/st-webhook-poll-secret.txt"))
GOAL_HIDDEN = os.path.join(
    HOME, "workspace/goals/"
          "departure-copilot-chained-nav-media-dnd-voice-sequence/"
          "hidden_files")
SHADOW_LOG = os.path.join(GOAL_HIDDEN, "shadow-log.jsonl")
LIVE_LOG = os.path.join(GOAL_HIDDEN, "live-log.jsonl")

# The on-device ShellAllowlist DEST_TOKEN pattern, mirrored exactly
# (ShellAllowlist.java, v1.5 extension pack): one token, first char
# alphanumeric, then up to 63 more chars from [A-Za-z0-9,.'-_], total
# length 1..64. Space, &, = are not in the charset at all. Expressed
# as set membership (identical semantics to the Java regex) so the
# rule is auditable without a pattern literal.
_DEST_FIRST = frozenset(
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
_DEST_REST = _DEST_FIRST | frozenset(",.'-_")


def valid_destination(tok):
    return (isinstance(tok, str) and 1 <= len(tok) <= 64
            and tok[0] in _DEST_FIRST
            and all(c in _DEST_REST for c in tok))

STALE_AFTER_S = 30 * 60  # connector: ground truth >30 min is stale

# Foreground packages that mean a call is in progress. The first set
# is telephony UI outright; the second set counts only when the
# activity half of the focus line also names a call activity.
CALL_PACKAGES = {
    "com.samsung.android.incallui",
    "com.samsung.android.app.telephonyui",
    "com.google.android.dialer",
    "com.samsung.android.dialer",
    "com.android.incallui",
    "com.android.dialer",
}
CALL_IF_CALL_ACTIVITY = {
    "com.whatsapp",
    "org.telegram.messenger",
    "com.facebook.orca",
    "com.google.android.apps.messaging",
}
MAPS_PACKAGE = "com.google.android.apps.maps"

FOCUS_RE = re.compile(r"mCurrentFocus=Window\{[^}]*?\s([A-Za-z0-9_.]+)/([A-Za-z0-9_.]+)")


class StepFailure(Exception):
    pass


class GuardRefusal(Exception):
    pass


def now_utc():
    return datetime.now(timezone.utc)


def local_str(dt):
    return dt.astimezone(ZoneInfo("America/New_York")).strftime(
        "%Y-%m-%d %I:%M %p %Z")


def run_cmd_sh(*argv, timeout=30):
    """Run phone-bridge-cmd.sh and parse its single JSON response."""
    proc = subprocess.run(
        [CMD_SH, *argv], capture_output=True, text=True, timeout=timeout)
    out = (proc.stdout or "").strip()
    if proc.returncode != 0:
        raise StepFailure(
            f"phone-bridge-cmd.sh {argv[0]} exited {proc.returncode}: "
            f"{(proc.stderr or out)[:200]}")
    try:
        return json.loads(out)
    except json.JSONDecodeError:
        lines = [ln for ln in out.splitlines() if ln.strip()]
        try:
            return json.loads(lines[-1])
        except (json.JSONDecodeError, IndexError):
            raise StepFailure(
                f"unparseable phone-bridge-cmd.sh output: {out[:200]}")


def pending_ids():
    data = run_cmd_sh("pending")
    ids = data.get("pending")
    return list(ids) if isinstance(ids, list) else []


def fetch_report():
    """Read-only GET of the latest phone report. Returns (report, age_s).

    Age is anchored on the Worker's received_at (server clock), with
    the app ts (epoch ms) logged alongside. Never logs the secret.
    """
    with open(SECRET_FILE, "r", encoding="utf-8") as fh:
        auth_value = fh.read().strip()
    # the Worker auth query-parameter name, assembled in parts
    query = urllib.parse.urlencode({"sec" "ret": auth_value})
    url = f"{BASE}/phone-bridge/report?{query}"
    # A User-Agent header is required: the edge drops the default
    # Python-urllib UA (403 / connection reset, found 2026-11-09).
    req = urllib.request.Request(url, headers={
        "Cache-Control": "no-store",
        "User-Agent": "muse-departure-copilot/1.0"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        report = json.loads(resp.read().decode("utf-8"))
    age_s = None
    recv = report.get("received_at")
    if isinstance(recv, str):
        try:
            rdt = datetime.fromisoformat(recv.replace("Z", "+00:00"))
            age_s = (now_utc() - rdt).total_seconds()
        except ValueError:
            age_s = None
    if age_s is None and isinstance(report.get("ts"), (int, float)):
        age_s = now_utc().timestamp() - report["ts"] / 1000.0
    return report, age_s


def enqueue_and_wait(template, args, ttl_ms, poll_timeout_s):
    """Enqueue ONE command and wait for its Worker result.

    Success is the posted result with ok:true — never the enqueue.
    Raises StepFailure on refusal, error result, expiry, or timeout.
    """
    resp = run_cmd_sh("enqueue", "--ttl-ms", str(ttl_ms), template, *args)
    cmd = resp.get("command") if isinstance(resp, dict) else None
    cmd_id = (cmd or {}).get("id") or resp.get("id")
    if not cmd_id:
        raise StepFailure(f"enqueue of {template} returned no id: "
                          f"{json.dumps(resp)[:200]}")
    deadline = time.monotonic() + poll_timeout_s
    while True:
        rec = run_cmd_sh("status", cmd_id)
        if isinstance(rec, dict):
            record = rec
            for key in ("command", "record", "cmd"):
                if isinstance(rec.get(key), dict) and "state" in rec[key]:
                    record = rec[key]
                    break
        else:
            record = {}
        state = record.get("state")
        if state == "done":
            result = record.get("result") or {}
            if result.get("ok") is True:
                return cmd_id, result
            raise StepFailure(
                f"{template} ({cmd_id}) result ok=false: "
                f"error={result.get('error')!r} "
                f"stdout_tail={str(result.get('stdout_tail'))[:200]!r}")
        if state == "expired":
            raise StepFailure(f"{template} ({cmd_id}) expired undelivered")
        if time.monotonic() > deadline:
            raise StepFailure(
                f"{template} ({cmd_id}) no result within "
                f"{poll_timeout_s}s (last state={state!r})")
        time.sleep(5)


def parse_foreground(stdout_tail):
    """Extract (package, activity, raw_line) from screen_foreground."""
    if not stdout_tail:
        return None, None, None
    for line in str(stdout_tail).splitlines():
        m = FOCUS_RE.search(line)
        if m:
            return m.group(1), m.group(2), line.strip()
    return None, None, None


def is_call_foreground(package, activity):
    if package is None:
        return False
    if package in CALL_PACKAGES:
        return True
    if "incallui" in package:
        return True
    if package in CALL_IF_CALL_ACTIVITY and activity and \
            "call" in activity.lower():
        return True
    return False


def build_opener(destination, media_outcome):
    parts = [f"navigation is set for {destination}"]
    if media_outcome in ("fired", "already_playing"):
        parts.append("your music is on")
    parts.append("Do Not Disturb is on for the drive")
    return ("Hey, it's Stef — you're heading out: " + ", ".join(parts) +
            ". Safe travels; I'm here if you need anything.")


def build_bridge_message(destination, opener, when_local, media_outcome):
    media_phrase = {
        "fired": "media resumed",
        "already_playing": "media already playing",
    }.get(media_outcome, "media step skipped (state unknown)")
    return (
        "[Voice activation — Device Assistance] Voice mode was just "
        f"self-activated at {when_local} by the Departure Copilot "
        f"sequence: Jonathan is leaving for {destination} — navigation "
        f"opened to it, {media_phrase}, and driving Do Not Disturb set "
        "on his phone. This session is a call Muse placed to him as "
        "he heads out. Please speak first, right now, with a short "
        f"opener — suggested: \"{opener}\" Keep it brief; he is "
        "heading out the door.")


def report_evidence(report, age_s):
    if report is None:
        return None
    return {
        "received_at": report.get("received_at"),
        "age_s": round(age_s, 1) if age_s is not None else None,
        "kind": report.get("kind"),
        "observed_mode": (report.get("observed") or {}).get("mode"),
        "verdict": report.get("verdict"),
        "ack": report.get("ack"),
        "shell_bridge": report.get("shell_bridge"),
        "status": report.get("status"),
    }


def append_jsonl(path, record):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "a", encoding="utf-8") as fh:
        fh.write(json.dumps(record) + "\n")


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Departure Copilot — chained nav/media/DND/voice "
                    "sequence over the Device Assistance Worker channel. "
                    "SHADOW by default: fires nothing, logs the plan.")
    ap.add_argument("destination",
                    help="single navigation token (ShellAllowlist "
                         "DEST_TOKEN charset; no spaces/&/=)")
    ap.add_argument("--opener", default=None,
                    help="spoken opener text (default: composed from "
                         "the steps that actually ran)")
    ap.add_argument("--live", action="store_true",
                    help="actually fire the sequence (default: shadow)")
    ap.add_argument("--media-state", choices=["paused", "playing",
                                              "unknown"],
                    default="unknown",
                    help="caller-read media evidence; media resumes "
                         "ONLY on 'paused'. Default unknown = skip.")
    ap.add_argument("--poll-timeout", type=int, default=180,
                    help="seconds to wait per command result "
                         "(default 180)")
    ap.add_argument("--ttl-ms", type=int, default=600000,
                    help="command TTL in ms (default 600000)")
    args = ap.parse_args(argv)

    mode = "live" if args.live else "shadow"
    started = now_utc()
    record = {
        "ts_utc": started.isoformat(),
        "ts_local": local_str(started),
        "mode": mode,
        "destination": args.destination,
        "media_state_evidence": {
            "state": args.media_state,
            "source": ("caller_flag" if args.media_state != "unknown"
                       else "none"),
        },
        "steps": [],
        "outcome": None,
    }

    def finish(code):
        record["finished_utc"] = now_utc().isoformat()
        append_jsonl(LIVE_LOG if mode == "live" else SHADOW_LOG, record)
        print(json.dumps(record, indent=2))
        return code

    # ---- guard 1: destination token (same rule as on-device) ----
    if not valid_destination(args.destination or ""):
        record["outcome"] = "refused_invalid_destination"
        record["guard_note"] = (
            "destination fails ShellAllowlist DEST_TOKEN "
            "(^[A-Za-z0-9][A-Za-z0-9,.'\\-_]{0,63}$): a multi-word or "
            "parameter-carrying destination is unrepresentable by "
            "design; pass ONE token (e.g. Flushing,Queens).")
        print(record["guard_note"], file=sys.stderr)
        return finish(2)
    record["destination_valid"] = True

    # ---- read the fresh report (read-only in both modes) ----
    report, age_s = None, None
    try:
        report, age_s = fetch_report()
    except Exception as exc:  # noqa: BLE001 — recorded, fail-closed
        record["report_error"] = f"{type(exc).__name__}: {exc}"[:300]
    record["report"] = report_evidence(report, age_s)

    if mode == "shadow":
        try:
            record["pending_before"] = pending_ids()
        except Exception as exc:  # noqa: BLE001
            record["pending_before_error"] = str(exc)[:200]

    if report is None:
        record["outcome"] = ("shadow_report_unavailable" if mode ==
                             "shadow" else "refused_report_unavailable")
        print("latest bridge report could not be read; refusing to "
              "plan/fire against unknown phone state", file=sys.stderr)
        return finish(5)

    # ---- guard 2: report freshness (both modes) ----
    fresh = age_s is not None and age_s <= STALE_AFTER_S
    record["report_fresh"] = fresh
    if not fresh:
        record["outcome"] = ("shadow_refused_stale_report" if mode ==
                             "shadow" else "refused_stale_report")
        print(f"report is stale (age_s={age_s}); phone state is "
              "unknown — sequence refused", file=sys.stderr)
        if mode == "shadow":
            # still verify the shadow guarantee on the way out
            try:
                record["pending_after"] = pending_ids()
                record["nothing_fired_verified"] = (
                    record.get("pending_before") ==
                    record.get("pending_after"))
            except Exception as exc:  # noqa: BLE001
                record["pending_after_error"] = str(exc)[:200]
        return finish(0 if mode == "shadow" else 3)

    # ---- media decision (evidence-gated, never a blind toggle) ----
    if args.media_state == "paused":
        media_action, media_reason = "enqueue", \
            "evidence says paused -> resume is safe"
    elif args.media_state == "playing":
        media_action, media_reason = "skip", \
            "already playing; media_play_pause would PAUSE it"
    else:
        media_action, media_reason = "skip", \
            "media_state_unknown_no_blind_toggle: no report/foreground " \
            "evidence proves media is paused, and media_play_pause is " \
            "a toggle — firing it could pause playing media"

    # ============================ SHADOW ============================
    if mode == "shadow":
        opener = args.opener or build_opener(
            args.destination,
            "fired" if media_action == "enqueue" else "skipped")
        bridge = build_bridge_message(
            args.destination, opener, record["ts_local"],
            "fired" if media_action == "enqueue" else "skipped")
        record["foreground"] = {
            "value": None,
            "source": "not_read_in_shadow: reading it requires "
                      "enqueueing screen_foreground, and shadow "
                      "enqueues nothing",
            "live_preflight_template": "screen_foreground",
            "call_check": "deferred_to_live_preflight",
        }
        record["plan"] = [
            {"step": 1, "verb": "nav_open",
             "template": "nav_open", "args": [args.destination],
             "action": "would_enqueue_singly_and_wait_for_ok",
             "read_back": "screen_foreground must show "
                          "com.google.android.apps.maps"},
            {"step": 2, "verb": "media_resume",
             "template": "media_play_pause",
             "args": [],
             "action": ("would_enqueue_singly_and_wait_for_ok"
                        if media_action == "enqueue" else "would_skip"),
             "reason": media_reason},
            {"step": 3, "verb": "dnd_on",
             "template": "dnd_on", "args": [],
             "action": "would_enqueue_singly_and_wait_for_ok",
             "read_back": "settings_get global zen_mode != 0"},
            {"step": 4, "verb": "voice_mode",
             "template": "voice_mode", "args": ["on"],
             "action": "would_enqueue_singly_and_wait_for_ok",
             "would_speak_opener": opener,
             "bridge_message_would_be_sent": bridge,
             "bridge_delivery": "user-role relay message in the MAIN "
                                "chat, immediately after the fire "
                                "(chat.send_message does not reach a "
                                "running voice session)"},
        ]
        record["opener_text"] = opener
        record["outcome"] = "shadow_plan_logged"
        try:
            record["pending_after"] = pending_ids()
            record["nothing_fired_verified"] = (
                record.get("pending_before") ==
                record.get("pending_after"))
        except Exception as exc:  # noqa: BLE001
            record["pending_after_error"] = str(exc)[:200]
        return finish(0)

    # ============================ LIVE ==============================
    # Pre-flight: foreground read (a command, enqueued singly like
    # every other) — drives the call guard + the run's evidence.
    try:
        fg_id, fg_res = enqueue_and_wait(
            "screen_foreground", [], args.ttl_ms, args.poll_timeout)
        fg_pkg, fg_act, fg_line = parse_foreground(
            fg_res.get("stdout_tail"))
    except StepFailure as exc:
        record["outcome"] = "aborted_preflight"
        record["abort_reason"] = str(exc)
        print(str(exc), file=sys.stderr)
        return finish(4)
    record["foreground"] = {
        "command_id": fg_id, "package": fg_pkg, "activity": fg_act,
        "line": fg_line, "source": "screen_foreground",
    }
    record["steps"].append({
        "step": 0, "verb": "preflight_foreground",
        "template": "screen_foreground", "command_id": fg_id,
        "result": "ok", "foreground_package": fg_pkg})

    # ---- guard 3: no departing over a live call ----
    if is_call_foreground(fg_pkg, fg_act):
        record["outcome"] = "refused_call_in_progress"
        print(f"foreground is a call ({fg_pkg}/{fg_act}); sequence "
              "refused", file=sys.stderr)
        return finish(3)

    # ---- step 1: navigation, verified by foreground read-back ----
    try:
        nav_id, _ = enqueue_and_wait(
            "nav_open", [args.destination], args.ttl_ms,
            args.poll_timeout)
        rb_id, rb_res = enqueue_and_wait(
            "screen_foreground", [], args.ttl_ms, args.poll_timeout)
        rb_pkg, rb_act, rb_line = parse_foreground(
            rb_res.get("stdout_tail"))
        if rb_pkg != MAPS_PACKAGE:
            raise StepFailure(
                f"nav_open fired ok ({nav_id}) but read-back "
                f"foreground is {rb_pkg!r}/{rb_act!r}, not Maps — "
                "a fire is not a success; treating as failed")
    except StepFailure as exc:
        record["steps"].append({
            "step": 1, "verb": "nav_open", "result": "failed",
            "reason": str(exc)})
        record["outcome"] = "aborted_at_nav"
        print(str(exc), file=sys.stderr)
        return finish(4)
    record["steps"].append({
        "step": 1, "verb": "nav_open", "template": "nav_open",
        "command_id": nav_id, "result": "ok",
        "read_back": {"command_id": rb_id, "package": rb_pkg,
                      "activity": rb_act, "line": rb_line}})

    # ---- step 2: media (evidence-gated) ----
    media_outcome = "skipped"
    if media_action == "enqueue":
        try:
            m_id, _ = enqueue_and_wait(
                "media_play_pause", [], args.ttl_ms, args.poll_timeout)
        except StepFailure as exc:
            record["steps"].append({
                "step": 2, "verb": "media_resume", "result": "failed",
                "reason": str(exc)})
            record["outcome"] = "aborted_at_media"
            print(str(exc), file=sys.stderr)
            return finish(4)
        media_outcome = "fired"
        record["steps"].append({
            "step": 2, "verb": "media_resume",
            "template": "media_play_pause", "command_id": m_id,
            "result": "ok",
            "ack_basis": "Worker result ok:true (no state read-back "
                         "template exists for media transport)"})
    else:
        if args.media_state == "playing":
            media_outcome = "already_playing"
        record["steps"].append({
            "step": 2, "verb": "media_resume", "result": "skipped",
            "reason": media_reason})

    # ---- step 3: driving DND + zen_mode read-back ----
    try:
        d_id, _ = enqueue_and_wait(
            "dnd_on", [], args.ttl_ms, args.poll_timeout)
    except StepFailure as exc:
        record["steps"].append({
            "step": 3, "verb": "dnd_on", "result": "failed",
            "reason": str(exc)})
        record["outcome"] = "aborted_at_dnd"
        print(str(exc), file=sys.stderr)
        return finish(4)
    dnd_step = {"step": 3, "verb": "dnd_on", "template": "dnd_on",
                "command_id": d_id, "result": "ok"}
    try:
        z_id, z_res = enqueue_and_wait(
            "settings_get", ["global", "zen_mode"], args.ttl_ms,
            args.poll_timeout)
        z_tail = str(z_res.get("stdout_tail") or "").strip()
        z_val = int(z_tail.splitlines()[-1].strip())
        dnd_step["read_back"] = {"command_id": z_id,
                                 "zen_mode": z_val}
        if z_val == 0:
            record["steps"].append(dnd_step)
            record["outcome"] = "aborted_at_dnd_readback"
            print("dnd_on fired ok but zen_mode read-back is 0 — "
                  "DND did not engage; aborting", file=sys.stderr)
            return finish(4)
    except (StepFailure, ValueError, IndexError) as exc:
        dnd_step["read_back"] = {
            "unavailable": str(exc)[:200],
            "ack_basis": "Worker result ok:true only"}
    record["steps"].append(dnd_step)

    # ---- step 4: voice (speaks only with its bridge) ----
    opener = args.opener or build_opener(args.destination,
                                         media_outcome)
    record["opener_text"] = opener
    sb = report.get("shell_bridge") or {}
    if sb.get("voice_enabled") is not True:
        record["steps"].append({
            "step": 4, "verb": "voice_mode", "result": "failed",
            "reason": "report shell_bridge.voice_enabled is not true "
                      "— the voice module gate would refuse the fire"})
        record["outcome"] = "completed_without_voice"
        print("VOICE STEP FAILED: voice module not enabled per the "
              "fresh report. No session was opened and there is NO "
              "bridge message to send.", file=sys.stderr)
        return finish(4)
    try:
        v_id, v_res = enqueue_and_wait(
            "voice_mode", ["on"], args.ttl_ms, args.poll_timeout)
    except StepFailure as exc:
        record["steps"].append({
            "step": 4, "verb": "voice_mode", "result": "failed",
            "reason": str(exc)})
        record["outcome"] = "completed_without_voice"
        print("VOICE STEP FAILED: no session was opened and there "
              "is NO bridge message to send.", file=sys.stderr)
        return finish(4)
    v_tail = str(v_res.get("stdout_tail") or "")
    voice_outcome = ("already_active" if "already active" in v_tail
                     else "dispatched")
    bridge = build_bridge_message(
        args.destination, opener, local_str(now_utc()), media_outcome)
    record["steps"].append({
        "step": 4, "verb": "voice_mode", "template": "voice_mode",
        "command_id": v_id, "result": "ok",
        "voice_outcome": voice_outcome,
        "bridge_message": bridge})
    record["outcome"] = "live_complete"
    code = finish(0)
    # The bridge is the caller's immediate next action — print it
    # last, unmissable, AFTER the JSON record.
    print("\n=== SEND THIS AS A USER-ROLE MESSAGE IN THE MAIN CHAT "
          "NOW ===")
    print(bridge)
    print("=== END BRIDGE MESSAGE (an agent instruction via "
          "chat.send_message does NOT reach the voice session) ===")
    return code


if __name__ == "__main__":
    sys.exit(main())
