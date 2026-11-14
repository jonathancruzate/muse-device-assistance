#!/bin/bash
# screen.sh — Muse side of the Device Assistance v1.25 screen
# sharing module (design: docs/v1.25-screen-sharing-design-2026-11-13.md).
#
# Two lanes, chosen per call:
#   - Bridge lane (~60 s): phone-bridge-cmd.sh enqueue of the
#     screen_* templates. Always available when the phone's bridge
#     loop runs; the only lane for shot/start/stop/status.
#   - Fast lane (~350 ms): POST /screen/input on the device relay,
#     drained by the phone ONLY while a stream is live. Used for
#     tap/swipe/key/text/find when the relay meta says streaming.
# The phone re-validates and re-gates every input on-device either
# way; this script is a transport, never an authority.
#
# Relay access rides the same box path as phone-bridge-cmd.sh
# (ssh + ~/device-relay/req.sh; the relay token never leaves the
# box) — except `view`, which needs the token in the URL for
# Jonathan's browser and reads it from the local token file.
#
# Usage:
#   screen.sh shot                 enqueue screen_shot, wait, download frame
#   screen.sh start                enqueue screen_start (consent tap is Jonathan's)
#   screen.sh stop                 fast stop + enqueue screen_stop
#   screen.sh status               enqueue screen_status + show relay meta
#   screen.sh tap <x> <y>          normalized coords 0..1000
#   screen.sh swipe <x1> <y1> <x2> <y2> [ms]
#   screen.sh key <back|home|recents|notifications|quick_settings|lock>
#   screen.sh text <words...>      type into the focused field
#   screen.sh find <label words...>  tap the exactly-labelled element
#   screen.sh fetch                download latest frame to screen-out/latest.jpg
#   screen.sh view                 print the live viewer URL
#
# Frames land in ~/workspace/muse-device-assistance/screen-out/.

set -u
cd "$(dirname "$0")/.."
OUT_DIR="$PWD/screen-out"
mkdir -p "$OUT_DIR"
BRIDGE="$HOME/workspace/cloudflare-workers/mode-sync/phone-bridge-cmd.sh"
TOKEN_FILE="$HOME/workspace/goals/manage-my-smart-home/hidden_files/device-relay-token.txt"
RELAY_TAILNET="100.85.53.70:8792"

relay_req() {
  ssh -i "$HOME/.ssh/id_ed25519" -o BatchMode=yes -o ConnectTimeout=15 \
    muse@167.233.41.12 "bash ~/device-relay/req.sh $1 '$2'"
}

# enqueue a bridge template and wait for its result; prints the
# status JSON. Returns non-zero on timeout.
enqueue_wait() {
  local template="$1"; shift
  local resp id state i
  resp="$("$BRIDGE" enqueue "$template" "$@")" || return 1
  id="$(printf '%s' "$resp" | python3 -c 'import json,sys
try:
  print(json.load(sys.stdin)["command"]["id"])
except Exception:
  print("")')"
  if [ -z "$id" ]; then
    echo "enqueue failed: $resp" >&2
    return 1
  fi
  for i in $(seq 1 30); do
    sleep 4
    state="$("$BRIDGE" status "$id")"
    case "$state" in
      *'"state": "done"'*|*'"state":"done"'*)
        printf '%s\n' "$state"
        return 0
        ;;
    esac
  done
  echo "timeout waiting for $template ($id); last: $state" >&2
  return 1
}

meta_json() {
  relay_req GET /screen/meta
}

is_streaming() {
  meta_json | python3 -c 'import json,sys,time
try:
  j=json.load(sys.stdin)
except Exception:
  print("no"); raise SystemExit
st=j.get("state") or {}
meta=j.get("meta") or {}
fresh = meta.get("received_ms",0) > (time.time()*1000 - 20000)
print("yes" if (st.get("streaming") or (meta.get("streaming") and fresh)) else "no")'
}

fetch_frame() {
  local dest="${1:-$OUT_DIR/latest.jpg}"
  # Primary: the relay's raw frame route through the box helper.
  relay_req GET /screen/frame.jpg > "$dest.tmp" 2>/dev/null
  if [ -s "$dest.tmp" ] && [ "$(head -c 2 "$dest.tmp" | od -An -tx1 | tr -d ' \n')" = "ffd8" ]; then
    mv "$dest.tmp" "$dest"
    echo "$dest"
    return 0
  fi
  rm -f "$dest.tmp"
  # Fallback: the stored frame file on the box, byte-exact over ssh.
  ssh -i "$HOME/.ssh/id_ed25519" -o BatchMode=yes -o ConnectTimeout=15 \
    muse@167.233.41.12 "cat ~/device-relay/store/screen_latest.jpg" > "$dest.tmp" 2>/dev/null
  if [ -s "$dest.tmp" ] && [ "$(head -c 2 "$dest.tmp" | od -An -tx1 | tr -d ' \n')" = "ffd8" ]; then
    mv "$dest.tmp" "$dest"
    echo "$dest"
    return 0
  fi
  rm -f "$dest.tmp"
  echo "no frame available (has a shot or stream run?)" >&2
  return 1
}

fast_input() {
  local payload="$1"
  printf '%s' "$payload" | relay_req POST /screen/input
  echo
}

cmd="${1:-}"
case "$cmd" in
  shot)
    enqueue_wait screen_shot || exit 1
    fetch_frame "$OUT_DIR/latest.jpg"
    ;;
  start)
    "$BRIDGE" enqueue screen_start
    echo
    echo "NOTE: the stream begins only after Jonathan taps Android's"
    echo "screen-capture consent dialog (a 'Start screen sharing'"
    echo "notification is posted on the phone). Poll: screen.sh status"
    ;;
  stop)
    fast_input '{"type":"stop"}' >/dev/null 2>&1 || true
    "$BRIDGE" enqueue screen_stop
    echo
    ;;
  status)
    meta_json
    echo
    enqueue_wait screen_status || true
    ;;
  tap)
    x="${2:?usage: screen.sh tap <x> <y>}"; y="${3:?usage: screen.sh tap <x> <y>}"
    if [ "$(is_streaming)" = "yes" ]; then
      fast_input "{\"type\":\"tap\",\"x\":$x,\"y\":$y}"
    else
      enqueue_wait screen_tap "$x" "$y"
    fi
    ;;
  swipe)
    x1="${2:?}"; y1="${3:?}"; x2="${4:?}"; y2="${5:?}"; ms="${6:-300}"
    if [ "$(is_streaming)" = "yes" ]; then
      fast_input "{\"type\":\"swipe\",\"x1\":$x1,\"y1\":$y1,\"x2\":$x2,\"y2\":$y2,\"ms\":$ms}"
    else
      enqueue_wait screen_swipe "$x1" "$y1" "$x2" "$y2" "$ms"
    fi
    ;;
  key)
    k="${2:?usage: screen.sh key <back|home|recents|notifications|quick_settings|lock>}"
    if [ "$(is_streaming)" = "yes" ]; then
      fast_input "{\"type\":\"key\",\"key\":\"$k\"}"
    else
      enqueue_wait screen_key "$k"
    fi
    ;;
  text)
    shift
    [ $# -ge 1 ] || { echo "usage: screen.sh text <words...>" >&2; exit 2; }
    if [ "$(is_streaming)" = "yes" ]; then
      TEXT="$*" python3 -c 'import json,os; print(json.dumps({"type":"text","text":os.environ["TEXT"]}))' \
        | relay_req POST /screen/input
      echo
    else
      enqueue_wait screen_text "$@"
    fi
    ;;
  find)
    shift
    [ $# -ge 1 ] || { echo "usage: screen.sh find <label words...>" >&2; exit 2; }
    if [ "$(is_streaming)" = "yes" ]; then
      TEXT="$*" python3 -c 'import json,os; print(json.dumps({"type":"find_tap","label":os.environ["TEXT"]}))' \
        | relay_req POST /screen/input
      echo
    else
      enqueue_wait screen_find_tap "$@"
    fi
    ;;
  fetch)
    fetch_frame "$OUT_DIR/latest.jpg"
    ;;
  view)
    if [ ! -r "$TOKEN_FILE" ]; then
      echo "relay token file not readable: $TOKEN_FILE" >&2
      exit 1
    fi
    token="$(tr -d ' \n\r' < "$TOKEN_FILE")"
    echo "http://$RELAY_TAILNET/screen/view?secret=$token"
    ;;
  *)
    echo "usage: screen.sh {shot|start|stop|status|tap|swipe|key|text|find|fetch|view}" >&2
    exit 2
    ;;
esac
