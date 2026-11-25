'use strict';
// device-relay — local phone <-> VM transport relay (Device Assistance).
// Ported VERBATIM from the Cloudflare Worker `mode-sync`
// (~/workspace/cloudflare-workers/mode-sync/worker.js): same handlers,
// same sanitizers, same field caps, same semantics. The only transport
// changes are: KV -> file-backed store (one JSON file per key, atomic
// tmp+rename), Fetch Request/Response -> node:http shims, and the
// two Worker secrets -> the single relay token in token.txt.
// The ONE deliberate behavioral addition is marked ADDITION below
// (mode:actual derivation from phone reports).
//
// Bind: 100.85.53.70:8792 ONLY (box tailnet IP), mirroring voice-relay.
// Auth: EVERY route requires ?secret=<relay token>, constant-time
// compared; otherwise 403 {"error":"forbidden"}.

const http = require('node:http');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');

const BIND = '100.85.53.70';
const PORT = 8792;
const BASE_DIR = __dirname;
const STORE_DIR = path.join(BASE_DIR, 'store');
const TOKEN = fs.readFileSync(path.join(BASE_DIR, 'token.txt'), 'utf8').trim();

// ---- File-backed key store (KV shim) ----
// One JSON file per key; ':' and '/' in keys map to '_'
// (e.g. mode:desired -> store/mode_desired.json,
//  phonebridge:cmd:<id> -> store/phonebridge_cmd_<id>.json).
// Writes are atomic: write tmp file in the same dir, then rename.
function keyToFile(key) {
  return path.join(STORE_DIR, key.replace(/[:/]/g, '_') + '.json');
}
const MODE_SYNC = {
  async get(key) {
    try { return await fsp.readFile(keyToFile(key), 'utf8'); } catch (_) { return null; }
  },
  async put(key, value) {
    const file = keyToFile(key);
    const tmp = file + '.tmp-' + process.pid + '-' + crypto.randomBytes(6).toString('hex');
    await fsp.writeFile(tmp, value, 'utf8');
    await fsp.rename(tmp, file);
  },
};
const env = { MODE_SYNC };

// ---- Response shim (mirrors the Worker's json() helper) ----
function json(obj, status = 200, extraHeaders = {}) {
  return { __relayJson: true, obj, status, headers: extraHeaders };
}

// Raw-response shim (v1.25 screen sharing): the frame download and
// the viewer page cannot ride the JSON shim. Handlers return this
// shape instead; the plumbing below sends body as-is.
function raw(body, contentType, status = 200, extraHeaders = {}) {
  return { __relayRaw: true, body, contentType, status, headers: extraHeaders };
}

// Constant-time-ish string compare for the poll secret.
// (Verbatim from worker.js.)
function safeEqual(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function capStr(v, n) {
  return typeof v === 'string' ? v.slice(0, n) : '';
}

// ==== Everything below this line is ported from worker.js ====
// (handler bodies verbatim; only env access goes through the shim
// above, and modeAuthed checks the single relay token)

const MODE_VALUES = ['home', 'away', 'night', 'morning'];

function modeAuthed(request, env) {
  const url = new URL(request.url);
  const got = url.searchParams.get('secret') || '';
  return safeEqual(got, TOKEN);
}

async function handleModeDesired(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let st = { mode: null, seq: 0, updated_at: null };
  try { st = JSON.parse((await env.MODE_SYNC.get('mode:desired')) || JSON.stringify(st)); } catch (_) {}
  return json(st);
}

async function handleModeSet(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let body;
  try { body = await request.json(); } catch (_) { return json({ error: 'bad request' }, 400); }
  const mode = body && body.mode;
  if (!MODE_VALUES.includes(mode)) return json({ error: 'bad mode' }, 400);
  let cur = { mode: null, seq: 0 };
  try { cur = JSON.parse((await env.MODE_SYNC.get('mode:desired')) || JSON.stringify(cur)); } catch (_) {}
  const next = {
    mode,
    source: typeof body.source === 'string' ? body.source.slice(0, 64) : 'unknown',
    seq: (cur.seq || 0) + 1,
    updated_at: new Date().toISOString(),
  };
  await env.MODE_SYNC.put('mode:desired', JSON.stringify(next));
  return json({ ok: true, ...next });
}

async function handleModeAck(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  // Accept JSON or MacroDroid plain-lines (line1=current MODE, line2=note).
  let current = null;
  let note = '';
  const ctype = request.headers.get('content-type') || '';
  if (ctype.includes('text/plain')) {
    const lines = (await request.text()).split('\n');
    current = (lines[0] || '').trim().toLowerCase();
    note = (lines[1] || '').trim().slice(0, 200);
  } else {
    let body;
    try { body = await request.json(); } catch (_) { return json({ error: 'bad request' }, 400); }
    current = typeof body.current === 'string' ? body.current.toLowerCase() : null;
    note = typeof body.note === 'string' ? body.note.slice(0, 200) : '';
  }
  if (!MODE_VALUES.includes(current)) return json({ error: 'bad mode' }, 400);
  let desired = { mode: null, seq: 0 };
  try { desired = JSON.parse((await env.MODE_SYNC.get('mode:desired')) || JSON.stringify(desired)); } catch (_) {}
  const ack = {
    current,
    matches_desired: desired.mode === current,
    desired_seq: desired.seq || 0,
    note,
    reported_at: new Date().toISOString(),
  };
  await env.MODE_SYNC.put('mode:actual', JSON.stringify(ack));
  return json({ ok: true, ...ack });
}

async function handleModeState(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let desired = null;
  let actual = null;
  try { desired = JSON.parse((await env.MODE_SYNC.get('mode:desired')) || 'null'); } catch (_) {}
  try { actual = JSON.parse((await env.MODE_SYNC.get('mode:actual')) || 'null'); } catch (_) {}
  const inSync = !!(desired && actual && actual.current === desired.mode && actual.desired_seq === desired.seq);
  return json({ desired, actual, in_sync: inSync });
}

async function handlePhoneBridgeReportPost(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let body;
  try { body = await request.json(); } catch (_) { return json({ error: 'bad request' }, 400); }
  if (!body || typeof body !== 'object') return json({ error: 'bad request' }, 400);
  const adbIn = (body.adb && typeof body.adb === 'object') ? body.adb : {};
  const tsIn = (body.tailscale && typeof body.tailscale === 'object') ? body.tailscale : {};
  const sbIn = (body.shell_bridge && typeof body.shell_bridge === 'object') ? body.shell_bridge : null;
  const obsIn = (body.observed && typeof body.observed === 'object') ? body.observed : {};
  const desIn = (body.desired && typeof body.desired === 'object') ? body.desired : {};
  const endpoints = [];
  if (Array.isArray(adbIn.endpoints)) {
    for (const e of adbIn.endpoints.slice(0, 8)) {
      if (!e || typeof e !== 'object') continue;
      endpoints.push({
        host: typeof e.host === 'string' ? e.host.slice(0, 45) : '',
        port: Number.isFinite(e.port) ? Math.floor(e.port) : 0,
        own: e.own === true,
      });
    }
  }
  const report = {
    device: capStr(body.device, 32) || 'phone-bridge',
    profile: capStr(body.profile, 16),
    kind: capStr(body.kind, 32),
    ts: Number.isFinite(body.ts) ? Math.floor(body.ts) : null,
    observed: {
      mode: capStr(obsIn.mode, 32),
      raw_mode: capStr(obsIn.raw_mode, 32),
      running: capStr(obsIn.running, 128),
      at: Number.isFinite(obsIn.at) ? Math.floor(obsIn.at) : 0,
      parse_ok: obsIn.parse_ok === true,
    },
    desired: {
      mode: capStr(desIn.mode, 32),
      seq: Number.isFinite(desIn.seq) ? Math.floor(desIn.seq) : -1,
    },
    verdict: capStr(body.verdict, 32),
    ack: body.ack === true,
    plan: capStr(body.plan, 200),
    status: capStr(body.status, 500),
    adb: {
      wireless_debugging: adbIn.wireless_debugging === true,
      secure_settings_granted: adbIn.secure_settings_granted === true,
      self_heal_armed: adbIn.self_heal_armed === true,
      endpoint: capStr(adbIn.endpoint, 64),
      endpoint_at: Number.isFinite(adbIn.endpoint_at) ? Math.floor(adbIn.endpoint_at) : 0,
      pairing_open: adbIn.pairing_open === true,
      wifi_ip: capStr(adbIn.wifi_ip, 45),
      endpoints,
    },
    tailscale: {
      installed: tsIn.installed === true,
      up: tsIn.up === true,
      ip: capStr(tsIn.ip, 45),
      battery_exempt: tsIn.battery_exempt === true,
      reviver_armed: tsIn.reviver_armed === true,
      down_since: Number.isFinite(tsIn.down_since) ? Math.floor(tsIn.down_since) : 0,
      down_count: Number.isFinite(tsIn.down_count) ? Math.floor(tsIn.down_count) : 0,
      last_recovered_ms: Number.isFinite(tsIn.last_recovered_ms) ? Math.floor(tsIn.last_recovered_ms) : 0,
    },
    received_at: new Date().toISOString(),
  };
  if (sbIn) {
    const sb = {
      paired: sbIn.paired === true,
      self_test_ok: sbIn.self_test_ok === true,
      on: sbIn.on === true,
      voice_enabled: sbIn.voice_enabled === true,
      voice_active: sbIn.voice_active === true,
    };
    if (sbIn.last && typeof sbIn.last === 'object') {
      sb.last = {
        id: capStr(sbIn.last.id, 64),
        template: capStr(sbIn.last.template, 64),
        ok: sbIn.last.ok === true,
        ms: Number.isFinite(sbIn.last.ms) ? Math.floor(sbIn.last.ms) : 0,
        at: Number.isFinite(sbIn.last.at) ? Math.floor(sbIn.last.at) : 0,
      };
    }
    // v1.21 self-update state (added 2026-11-13): pass the app's
    // shell_bridge.self_update {version_name, version_code,
    // last_update_time, stage?, sha?, detail?, from_code?, at?}
    // through sanitized — the v1.21 deploy initially missed this
    // whitelist in BOTH report sanitizers (Worker + this relay).
    if (sbIn.self_update && typeof sbIn.self_update === 'object') {
      const su = sbIn.self_update;
      sb.self_update = {
        version_name: capStr(su.version_name, 32),
        version_code: Number.isFinite(su.version_code) ? Math.floor(su.version_code) : 0,
        last_update_time: Number.isFinite(su.last_update_time) ? Math.floor(su.last_update_time) : 0,
        stage: capStr(su.stage, 32),
        sha: capStr(su.sha, 64),
        detail: capStr(su.detail, 256),
        from_code: Number.isFinite(su.from_code) ? Math.floor(su.from_code) : -1,
        at: Number.isFinite(su.at) ? Math.floor(su.at) : 0,
      };
    }
    // v1.24 phone file management state (added 2026-11-13): pass
    // shell_bridge.files {access_granted, last_op?, last_detail?,
    // last_at?} through sanitized (state only, never file names) —
    // the v1.21 lesson: whitelist in BOTH sanitizers or it never
    // arrives.
    if (sbIn.files && typeof sbIn.files === 'object') {
      const fl = sbIn.files;
      sb.files = {
        access_granted: fl.access_granted === true,
        last_op: capStr(fl.last_op, 32),
        last_detail: capStr(fl.last_detail, 256),
        last_at: Number.isFinite(fl.last_at) ? Math.floor(fl.last_at) : 0,
      };
    }
    // v1.25 screen sharing state (added 2026-11-13): pass
    // shell_bridge.screen {enabled, accessibility, streaming,
    // started_at, frames, last_frame_at, last_w, last_h,
    // last_error?, last_input?, last_input_at?} through sanitized
    // (state only — frame data rides /screen/frame, never reports).
    if (sbIn.screen && typeof sbIn.screen === 'object') {
      const sc = sbIn.screen;
      sb.screen = {
        enabled: sc.enabled === true,
        accessibility: sc.accessibility === true,
        streaming: sc.streaming === true,
        started_at: Number.isFinite(sc.started_at) ? Math.floor(sc.started_at) : 0,
        frames: Number.isFinite(sc.frames) ? Math.floor(sc.frames) : 0,
        last_frame_at: Number.isFinite(sc.last_frame_at) ? Math.floor(sc.last_frame_at) : 0,
        last_w: Number.isFinite(sc.last_w) ? Math.floor(sc.last_w) : 0,
        last_h: Number.isFinite(sc.last_h) ? Math.floor(sc.last_h) : 0,
        last_error: capStr(sc.last_error, 200),
        last_input: capStr(sc.last_input, 200),
        last_input_at: Number.isFinite(sc.last_input_at) ? Math.floor(sc.last_input_at) : 0,
      };
    }
    report.shell_bridge = sb;
  }
  // v1.35 routine_fire state (added 2026-11-24): Device Assistance
  // sends routine_fire {linked: {<key>: <routine name or ''>},
  // last_fire: {key, at, result}} in phone-profile reports; keep a
  // sanitized form (known registry keys only, strings capped) so
  // linking + the last command-driven fire verify server-side.
  const rfIn = (body.routine_fire && typeof body.routine_fire === 'object')
    ? body.routine_fire : null;
  if (rfIn) {
    const rfKeys = ['sleep', 'home', 'away', 'morning', 'theater',
      'driving', 'exercise', 'work', 'relax', 'before_bed',
      'commute_home', 'commute_work', 'meeting', 'power_standard',
      'power_max', 'status_full'];
    const linked = {};
    const lkIn = (rfIn.linked && typeof rfIn.linked === 'object') ? rfIn.linked : {};
    for (const k of rfKeys) linked[k] = capStr(lkIn[k], 64);
    const lfIn = (rfIn.last_fire && typeof rfIn.last_fire === 'object') ? rfIn.last_fire : {};
    report.routine_fire = {
      linked,
      last_fire: {
        key: capStr(lfIn.key, 32),
        at: Number.isFinite(lfIn.at) ? Math.floor(lfIn.at) : 0,
        result: capStr(lfIn.result, 128),
      },
    };
  }
  const pcIn = (body.push_channels && typeof body.push_channels === 'object')
    ? body.push_channels : null;
  if (pcIn) {
    const ids = ['notifications', 'calendar', 'contacts', 'messages',
      'dialer', 'call_log', 'health', 'battery', 'mode_reconcile'];
    const pc = {};
    for (const id of ids) {
      const ch = pcIn[id];
      if (!ch || typeof ch !== 'object') continue;
      pc[id] = {
        on: ch.on === true,
        last_push_at: Number.isFinite(ch.last_push_at) ? Math.floor(ch.last_push_at) : 0,
        last_error: capStr(ch.last_error, 128),
      };
    }
    report.push_channels = pc;
  }
  // v1.26 Duo companion state: pass report.duo {ritual_enabled,
  // panels_prop, posture_source, last_fire_at, last_at, last_result,
  // fires} through sanitized (state only — the v1.21 whitelist
  // lesson: whitelist here or it never arrives).
  // v1.31.0: the Duo feature set moved to the Muse Duo Companion
  // plugin; its snapshot adds debug state, plugin presence/version
  // and wake-pair state. Same whitelist treatment, same caps style.
  const duoIn = (body.duo && typeof body.duo === 'object') ? body.duo : null;
  if (duoIn) {
    report.duo = {
      ritual_enabled: duoIn.ritual_enabled === true,
      panels_prop: capStr(duoIn.panels_prop, 16),
      posture_source: capStr(duoIn.posture_source, 16),
      last_fire_at: Number.isFinite(duoIn.last_fire_at) ? Math.floor(duoIn.last_fire_at) : 0,
      last_at: Number.isFinite(duoIn.last_at) ? Math.floor(duoIn.last_at) : 0,
      last_result: capStr(duoIn.last_result, 200),
      fires: Number.isFinite(duoIn.fires) ? Math.floor(duoIn.fires) : 0,
      debug: duoIn.debug === true,
      debug_set_at: Number.isFinite(duoIn.debug_set_at) ? Math.floor(duoIn.debug_set_at) : 0,
      suppressions: Number.isFinite(duoIn.suppressions) ? Math.floor(duoIn.suppressions) : 0,
      plugin_version: capStr(duoIn.plugin_version, 32),
      plugin_present: duoIn.plugin_present === true,
      wake_pair_enabled: duoIn.wake_pair_enabled === true,
      wake_pair_mode: capStr(duoIn.wake_pair_mode, 16),
      wake_pair_last_at: Number.isFinite(duoIn.wake_pair_last_at) ? Math.floor(duoIn.wake_pair_last_at) : 0,
      wake_pair_last_result: capStr(duoIn.wake_pair_last_result, 200),
    };
  }
  const actIn = (body.actuation && typeof body.actuation === 'object')
    ? body.actuation : null;
  if (actIn) {
    const actModes = ['home', 'away', 'night', 'morning'];
    const linkedIn = (actIn.linked && typeof actIn.linked === 'object')
      ? actIn.linked : {};
    const linked = {};
    for (const m of actModes) linked[m] = capStr(linkedIn[m], 64);
    const decFields = (o) => (o && typeof o === 'object') ? {
      at: Number.isFinite(o.at) ? Math.floor(o.at) : 0,
      seq: Number.isFinite(o.seq) ? Math.floor(o.seq) : -1,
      desired: capStr(o.desired, 32),
      observed: capStr(o.observed, 32),
      verdict: capStr(o.verdict, 32),
      armed: o.armed === true,
      action: capStr(o.action, 32),
      step: capStr(o.step, 32),
      target: capStr(o.target, 32),
      routine: capStr(o.routine, 64),
      reason: capStr(o.reason, 200),
    } : null;
    const lfIn = (actIn.last_fire && typeof actIn.last_fire === 'object')
      ? actIn.last_fire : null;
    const hist = [];
    if (Array.isArray(actIn.history)) {
      for (const h of actIn.history.slice(-12)) {
        if (!h || typeof h !== 'object') continue;
        hist.push({
          at: Number.isFinite(h.at) ? Math.floor(h.at) : 0,
          seq: Number.isFinite(h.seq) ? Math.floor(h.seq) : -1,
          desired: capStr(h.desired, 32),
          action: capStr(h.action, 32),
          step: capStr(h.step, 32),
          target: capStr(h.target, 32),
        });
      }
    }
    report.actuation = {
      armed: actIn.armed === true,
      linked,
      shadow: decFields(actIn.shadow),
      last_fire: lfIn ? {
        seq: Number.isFinite(lfIn.seq) ? Math.floor(lfIn.seq) : -1,
        desired: capStr(lfIn.desired, 32),
        step: capStr(lfIn.step, 32),
        target: capStr(lfIn.target, 32),
        routine: capStr(lfIn.routine, 64),
        at: Number.isFinite(lfIn.at) ? Math.floor(lfIn.at) : 0,
        gt_at: Number.isFinite(lfIn.gt_at) ? Math.floor(lfIn.gt_at) : 0,
        outcome: capStr(lfIn.outcome, 32),
        outcome_at: Number.isFinite(lfIn.outcome_at) ? Math.floor(lfIn.outcome_at) : 0,
      } : null,
      history: hist,
    };
  }
  if (report.profile === 'tv' && body.status && typeof body.status === 'object') {
    const st = body.status;
    report.tv_status = {
      model: capStr(st.model, 64),
      manufacturer: capStr(st.manufacturer, 64),
      sdk: Number.isFinite(st.sdk) ? Math.floor(st.sdk) : 0,
      network: capStr(st.network, 16),
      screen_on: st.screen_on === true,
      storage_total_gb: Number.isFinite(st.storage_total_gb) ? Math.floor(st.storage_total_gb) : 0,
      storage_free_gb: Number.isFinite(st.storage_free_gb) ? Math.floor(st.storage_free_gb) : 0,
    };
    if (body.adb && typeof body.adb === 'object') {
      report.adb.heal_mode = capStr(body.adb.heal_mode, 32);
    }
  }
  const reportKey = report.device === 'shield-bridge'
    ? 'shieldbridge:report'
    : report.device === 'duo-bridge' ? 'duobridge:report' : 'phonebridge:report';
  await env.MODE_SYNC.put(reportKey, JSON.stringify(report));
  // ADDITION (the one deliberate deviation from worker.js): after
  // storing a PHONE report whose observed.parse_ok is true and whose
  // observed.mode is a valid house mode, derive mode:actual from it so
  // the local mode-state is self-contained (the Worker got mode:actual
  // only from /mode-ack; the local app reports carry the same ground
  // truth). desired_seq comes from the report's desired.seq when the
  // raw report carried a finite one, else the stored desired seq.
  if (reportKey === 'phonebridge:report'
      && report.observed.parse_ok === true
      && MODE_VALUES.includes(report.observed.mode)) {
    let storedDesired = { mode: null, seq: 0 };
    try { storedDesired = JSON.parse((await env.MODE_SYNC.get('mode:desired')) || JSON.stringify(storedDesired)); } catch (_) {}
    const rawSeq = (body.desired && typeof body.desired === 'object') ? body.desired.seq : undefined;
    const derived = {
      current: report.observed.mode,
      matches_desired: storedDesired.mode === report.observed.mode,
      desired_seq: Number.isFinite(rawSeq) ? Math.floor(rawSeq) : (storedDesired.seq || 0),
      note: 'da-report',
      reported_at: new Date().toISOString(),
    };
    await env.MODE_SYNC.put('mode:actual', JSON.stringify(derived));
  }
  return json({ ok: true, received_at: report.received_at, stored_as: reportKey });
}

async function handlePhoneBridgeReportGet(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  const dev = new URL(request.url).searchParams.get('device') || '';
  const key = dev === 'shield-bridge' ? 'shieldbridge:report'
    : dev === 'duo-bridge' ? 'duobridge:report' : 'phonebridge:report';
  let report = null;
  try { report = JSON.parse((await env.MODE_SYNC.get(key)) || 'null'); } catch (_) {}
  if (!report) return json({ error: 'no report yet' }, 404);
  return json(report, 200, { 'Cache-Control': 'no-store' });
}

const CMD_DEFAULT_TTL_MS = 10 * 60 * 1000;
const CMD_MAX_TTL_MS = 24 * 60 * 60 * 1000;
const CMD_MAX_ARGS = 16;
const CMD_MAX_QUEUE = 32;

function cmdPrefix(device) {
  if (device === 'shield-bridge') return 'shieldbridge';
  if (device === 'duo-bridge') return 'duobridge';
  return 'phonebridge';
}

async function loadCmdQueue(env, prefix = 'phonebridge') {
  let q = [];
  try { q = JSON.parse((await env.MODE_SYNC.get(prefix + ':queue')) || '[]'); } catch (_) {}
  return Array.isArray(q) ? q.filter(x => typeof x === 'string') : [];
}

async function loadCmd(env, id, prefix = 'phonebridge') {
  let rec = null;
  try { rec = JSON.parse((await env.MODE_SYNC.get(prefix + ':cmd:' + id)) || 'null'); } catch (_) {}
  return rec && typeof rec === 'object' ? rec : null;
}

async function findCmd(env, id) {
  const phone = await loadCmd(env, id, 'phonebridge');
  if (phone) return { rec: phone, prefix: 'phonebridge' };
  const shield = await loadCmd(env, id, 'shieldbridge');
  if (shield) return { rec: shield, prefix: 'shieldbridge' };
  const duo = await loadCmd(env, id, 'duobridge');
  if (duo) return { rec: duo, prefix: 'duobridge' };
  return { rec: null, prefix: 'phonebridge' };
}

async function handlePhoneBridgeEnqueue(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let body;
  try { body = await request.json(); } catch (_) { return json({ error: 'bad request' }, 400); }
  if (!body || typeof body !== 'object') return json({ error: 'bad request' }, 400);
  const template = capStr(body.template, 64);
  if (!template) return json({ error: 'bad template' }, 400);
  const args = [];
  if (Array.isArray(body.args)) {
    for (const a of body.args.slice(0, CMD_MAX_ARGS)) args.push(capStr(a, 256));
  }
  let ttl = Number.isFinite(body.ttl_ms) ? Math.floor(body.ttl_ms) : CMD_DEFAULT_TTL_MS;
  if (ttl < 1000) ttl = 1000;
  if (ttl > CMD_MAX_TTL_MS) ttl = CMD_MAX_TTL_MS;
  let id = capStr(body.id, 64);
  if (id && !/^[A-Za-z0-9._-]+$/.test(id)) return json({ error: 'bad id' }, 400);
  if (!id) id = 'cmd-' + crypto.randomUUID();
  const prefix = cmdPrefix(capStr(body.device, 32));
  if ((await findCmd(env, id)).rec) return json({ error: 'duplicate id' }, 409);
  const queue = await loadCmdQueue(env, prefix);
  if (queue.length >= CMD_MAX_QUEUE) return json({ error: 'queue full' }, 429);
  const now = Date.now();
  const cmd = { id, template, args, expires_at: now + ttl };
  queue.push(id);
  await env.MODE_SYNC.put(prefix + ':cmd:' + id, JSON.stringify({
    ...cmd, state: 'pending', enqueued_at: new Date(now).toISOString(),
  }));
  await env.MODE_SYNC.put(prefix + ':queue', JSON.stringify(queue));
  return json({ ok: true, command: cmd });
}

async function handlePhoneBridgeCommands(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  const prefix = cmdPrefix(new URL(request.url).searchParams.get('device') || '');
  const now = Date.now();
  const queue = await loadCmdQueue(env, prefix);
  let dirty = false;
  let picked = null;
  const rest = [];
  for (const id of queue) {
    if (picked) { rest.push(id); continue; }
    const rec = await loadCmd(env, id, prefix);
    if (!rec || rec.state !== 'pending') { dirty = true; continue; }
    if (Number.isFinite(rec.expires_at) && rec.expires_at > 0 && now > rec.expires_at) {
      rec.state = 'expired';
      await env.MODE_SYNC.put(prefix + ':cmd:' + id, JSON.stringify(rec));
      dirty = true;
      continue;
    }
    rec.state = 'delivered';
    rec.delivered_at = new Date(now).toISOString();
    await env.MODE_SYNC.put(prefix + ':cmd:' + id, JSON.stringify(rec));
    picked = {
      id: rec.id,
      template: rec.template,
      args: Array.isArray(rec.args) ? rec.args : [],
      expires_at: rec.expires_at,
    };
    dirty = true;
  }
  if (dirty) await env.MODE_SYNC.put(prefix + ':queue', JSON.stringify(rest));
  if (!picked) return json({ command: null });
  return json({ command: picked }, 200, { 'Cache-Control': 'no-store' });
}

async function handlePhoneBridgeCommandResult(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let body;
  try { body = await request.json(); } catch (_) { return json({ error: 'bad request' }, 400); }
  if (!body || typeof body !== 'object') return json({ error: 'bad request' }, 400);
  const id = capStr(body.id, 64);
  if (!id) return json({ error: 'bad id' }, 400);
  const result = {
    ok: body.ok === true,
    stdout_tail: capStr(body.stdout_tail, 16384),
    ms: Number.isFinite(body.ms) ? Math.floor(body.ms) : 0,
    received_at: new Date().toISOString(),
  };
  const err = capStr(body.error, 256);
  if (err) result.error = err;
  const found = await findCmd(env, id);
  let rec = found.rec;
  const hintedPrefix = cmdPrefix(capStr(body.device, 32));
  const prefix = !found.rec && hintedPrefix !== 'phonebridge'
    ? hintedPrefix : found.prefix;
  if (!rec) {
    rec = { id, template: '', args: [], expires_at: 0, enqueued_at: null };
  }
  rec.state = 'done';
  rec.result = result;
  await env.MODE_SYNC.put(prefix + ':cmd:' + id, JSON.stringify(rec));
  const queue = await loadCmdQueue(env, prefix);
  if (queue.includes(id)) {
    await env.MODE_SYNC.put(prefix + ':queue',
      JSON.stringify(queue.filter(x => x !== id)));
  }
  return json({ ok: true, id });
}

async function handlePhoneBridgeCommandStatus(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  const url = new URL(request.url);
  const id = (url.searchParams.get('id') || '').slice(0, 64);
  if (!id) {
    const queue = await loadCmdQueue(env);
    const shieldQueue = await loadCmdQueue(env, 'shieldbridge');
    const duoQueue = await loadCmdQueue(env, 'duobridge');
    return json({ pending: queue, shield_pending: shieldQueue, duo_pending: duoQueue },
      200, { 'Cache-Control': 'no-store' });
  }
  const rec = (await findCmd(env, id)).rec;
  if (!rec) return json({ error: 'unknown command' }, 404);
  return json(rec, 200, { 'Cache-Control': 'no-store' });
}

const DEVICE_PUSH_SOURCES = ['phone-notify', 'phone-calendar', 'phone-contacts',
  'phone-messages', 'phone-dialer', 'phone-calllog', 'phone-health', 'phone-battery'];
const DEVICE_PUSH_MAX_ITEMS = 50;
const DEVICE_PUSH_MAX_QUEUE = 500;

function sanitizePushItem(source, item) {
  if (!item || typeof item !== 'object') return null;
  const out = {};
  if (source === 'phone-notify') {
    out.package = capStr(item.package, 128);
    out.title = capStr(item.title, 256);
    out.text = capStr(item.text, 2048);
    out.big_text = capStr(item.big_text, 4096);
    out.posted_at = Number.isFinite(item.posted_at) ? Math.floor(item.posted_at) : 0;
    out.event = (item.event === 'removed') ? 'removed' : 'posted';
    out.key = capStr(item.key, 256);
    return out;
  }
  if (source === 'phone-battery') {
    out.battery = Number.isFinite(item.battery) ? Math.floor(item.battery) : -1;
    out.charging = item.charging === true;
    out.wifi_ssid = capStr(item.wifi_ssid, 64);
    out.ringer_mode = capStr(item.ringer_mode, 32);
    out.screen_on = item.screen_on === true;
    return out;
  }
  let n = 0;
  for (const k of Object.keys(item)) {
    if (n >= 20) break;
    if (!/^[A-Za-z0-9_]{1,32}$/.test(k)) continue;
    const v = item[k];
    if (typeof v === 'string') out[k] = v.slice(0, 1024);
    else if (typeof v === 'number' && Number.isFinite(v)) out[k] = v;
    else if (typeof v === 'boolean') out[k] = v;
    else continue;
    n++;
  }
  return out;
}

async function handleDevicePushPost(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let body;
  try { body = await request.json(); } catch (_) { return json({ error: 'bad request' }, 400); }
  if (!body || typeof body !== 'object') return json({ error: 'bad request' }, 400);
  const source = capStr(body.source, 32);
  if (!DEVICE_PUSH_SOURCES.includes(source)) return json({ error: 'bad source' }, 400);
  const itemsIn = Array.isArray(body.items) ? body.items.slice(0, DEVICE_PUSH_MAX_ITEMS) : [];
  const receivedAt = new Date().toISOString();
  const items = [];
  for (const raw of itemsIn) {
    const clean = sanitizePushItem(source, raw);
    if (clean) items.push({ ...clean, received_at: receivedAt });
  }
  const key = 'devicepush:' + source;
  let queue = [];
  try { queue = JSON.parse((await env.MODE_SYNC.get(key)) || '[]'); } catch (_) {}
  if (!Array.isArray(queue)) queue = [];
  queue.push(...items);
  if (queue.length > DEVICE_PUSH_MAX_QUEUE) {
    queue = queue.slice(queue.length - DEVICE_PUSH_MAX_QUEUE);
  }
  await env.MODE_SYNC.put(key, JSON.stringify(queue));
  return json({ ok: true, source, stored: items.length, queue: queue.length, received_at: receivedAt });
}

async function handleDevicePushGet(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  const source = (new URL(request.url).searchParams.get('source') || '').slice(0, 32);
  if (!DEVICE_PUSH_SOURCES.includes(source)) return json({ error: 'bad source' }, 400);
  let queue = [];
  try { queue = JSON.parse((await env.MODE_SYNC.get('devicepush:' + source)) || '[]'); } catch (_) {}
  if (!Array.isArray(queue)) queue = [];
  return json({ source, items: queue }, 200, { 'Cache-Control': 'no-store' });
}

// ==== Screen sharing (v1.25; app design docs/v1.25-screen-sharing-design-2026-11-13.md) ====
// The phone uploads JPEG frames (base64-in-JSON, the relay's body
// discipline) and stream state; this side stores the latest frame
// as a BINARY file next to the JSON store (JSON cannot hold it),
// serves it raw, and carries a validated fast-input queue the
// phone drains while streaming. Every input is re-validated
// on-device before it executes — this validation is shape, the
// device is the authority.
const SCREEN_FRAME_FILE = path.join(STORE_DIR, 'screen_latest.jpg');
const SCREEN_INPUT_QUEUE_CAP = 50;
const SCREEN_MAX_FRAME_BYTES = 2 * 1024 * 1024;
const SCREEN_KEYS = ['back', 'home', 'recents', 'notifications', 'quick_settings', 'lock'];

function screenInt(v, lo, hi) {
  return Number.isInteger(v) && v >= lo && v <= hi;
}

async function handleScreenFramePost(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let body;
  try { body = await request.json(); } catch (_) { return json({ error: 'bad request' }, 400); }
  if (!body || typeof body.frame_b64 !== 'string' || !body.frame_b64) {
    return json({ error: 'bad frame' }, 400);
  }
  let buf;
  try { buf = Buffer.from(body.frame_b64, 'base64'); } catch (_) {
    return json({ error: 'bad frame encoding' }, 400);
  }
  if (buf.length === 0 || buf.length > SCREEN_MAX_FRAME_BYTES) {
    return json({ error: 'bad frame size' }, 400);
  }
  if (buf.length < 3 || buf[0] !== 0xFF || buf[1] !== 0xD8) {
    return json({ error: 'not a jpeg' }, 400);
  }
  const tmp = SCREEN_FRAME_FILE + '.tmp-' + process.pid + '-' + crypto.randomBytes(6).toString('hex');
  await fsp.writeFile(tmp, buf);
  await fsp.rename(tmp, SCREEN_FRAME_FILE);
  const meta = {
    w: screenInt(body.w, 1, 10000) ? body.w : 0,
    h: screenInt(body.h, 1, 10000) ? body.h : 0,
    device_ts: Number.isFinite(body.ts) ? body.ts : 0,
    streaming: body.streaming === true,
    bytes: buf.length,
    received_at: new Date().toISOString(),
    received_ms: Date.now(),
  };
  await env.MODE_SYNC.put('screen:meta', JSON.stringify(meta));
  return json({ ok: true, bytes: buf.length });
}

async function handleScreenFrameGet(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let buf;
  try { buf = await fsp.readFile(SCREEN_FRAME_FILE); } catch (_) {
    return json({ error: 'no frame yet' }, 404);
  }
  return raw(buf, 'image/jpeg', 200, { 'Cache-Control': 'no-store' });
}

async function handleScreenMetaGet(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let meta = null;
  let state = null;
  try { meta = JSON.parse((await env.MODE_SYNC.get('screen:meta')) || 'null'); } catch (_) {}
  try { state = JSON.parse((await env.MODE_SYNC.get('screen:state')) || 'null'); } catch (_) {}
  return json({ meta, state }, 200, { 'Cache-Control': 'no-store' });
}

async function handleScreenStatePost(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let body;
  try { body = await request.json(); } catch (_) { return json({ error: 'bad request' }, 400); }
  if (!body || typeof body !== 'object') return json({ error: 'bad request' }, 400);
  const state = {
    streaming: body.streaming === true,
    frames: Number.isFinite(body.frames) ? Math.floor(body.frames) : 0,
    started_at: Number.isFinite(body.started_at) ? body.started_at : 0,
    error: typeof body.error === 'string' ? body.error.slice(0, 200) : null,
    received_at: new Date().toISOString(),
    received_ms: Date.now(),
  };
  await env.MODE_SYNC.put('screen:state', JSON.stringify(state));
  return json({ ok: true });
}

function validateScreenInput(body) {
  if (!body || typeof body !== 'object') return null;
  const t = body.type;
  if (t === 'tap') {
    if (!screenInt(body.x, 0, 1000) || !screenInt(body.y, 0, 1000)) return null;
    return { type: 'tap', x: body.x, y: body.y };
  }
  if (t === 'swipe') {
    const ms = body.ms === undefined ? 300 : body.ms;
    if (!screenInt(body.x1, 0, 1000) || !screenInt(body.y1, 0, 1000)
        || !screenInt(body.x2, 0, 1000) || !screenInt(body.y2, 0, 1000)
        || !screenInt(ms, 50, 2000)) return null;
    return { type: 'swipe', x1: body.x1, y1: body.y1, x2: body.x2, y2: body.y2, ms };
  }
  if (t === 'key') {
    if (!SCREEN_KEYS.includes(body.key)) return null;
    return { type: 'key', key: body.key };
  }
  if (t === 'text') {
    if (typeof body.text !== 'string' || !body.text || body.text.length > 1000) return null;
    return { type: 'text', text: body.text };
  }
  if (t === 'find_tap') {
    if (typeof body.label !== 'string' || !body.label || body.label.length > 200) return null;
    return { type: 'find_tap', label: body.label };
  }
  if (t === 'stop') return { type: 'stop' };
  return null;
}

async function loadScreenInputQueue(env) {
  let queue = [];
  try { queue = JSON.parse((await env.MODE_SYNC.get('screen:inputq')) || '[]'); } catch (_) {}
  return Array.isArray(queue) ? queue : [];
}

async function handleScreenInputPost(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let body;
  try { body = await request.json(); } catch (_) { return json({ error: 'bad request' }, 400); }
  const input = validateScreenInput(body);
  if (!input) return json({ error: 'bad input' }, 400);
  const queue = await loadScreenInputQueue(env);
  while (queue.length >= SCREEN_INPUT_QUEUE_CAP) queue.shift();
  queue.push({ ...input, queued_at: Date.now() });
  await env.MODE_SYNC.put('screen:inputq', JSON.stringify(queue));
  return json({ ok: true, queue: queue.length });
}

async function handleScreenInputGet(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  const queue = await loadScreenInputQueue(env);
  if (queue.length > 0) await env.MODE_SYNC.put('screen:inputq', '[]');
  return json({ inputs: queue }, 200, { 'Cache-Control': 'no-store' });
}

function screenViewHtml(secret) {
  return '<!DOCTYPE html>\n<html><head><meta charset="utf-8">'
    + '<meta name="viewport" content="width=device-width, initial-scale=1">'
    + '<title>Muse — Phone Screen</title><style>'
    + 'body{background:#111;color:#eee;font-family:system-ui,sans-serif;margin:0;padding:12px;text-align:center}'
    + 'img{max-width:min(96vw,480px);max-height:72vh;background:#000;border:1px solid #333;touch-action:none;cursor:pointer}'
    + 'button{background:#222;color:#eee;border:1px solid #444;border-radius:8px;padding:8px 12px;margin:3px;cursor:pointer}'
    + 'button:active{background:#444}input{background:#1a1a1a;color:#eee;border:1px solid #444;border-radius:8px;padding:8px}'
    + '#status{color:#9c9;font-size:13px;min-height:18px;margin:6px}#hint{color:#888;font-size:12px}'
    + '</style></head><body>'
    + '<div id="status">connecting…</div>'
    + '<img id="scr" alt="phone screen"><div id="hint">click = tap · drag = swipe (live only while a stream runs or after a screenshot)</div>'
    + '<div>'
    + '<button onclick="key(\'back\')">Back</button><button onclick="key(\'home\')">Home</button>'
    + '<button onclick="key(\'recents\')">Recents</button><button onclick="key(\'notifications\')">Notifications</button>'
    + '<button onclick="key(\'lock\')">Lock</button></div>'
    + '<div><input id="txt" size="26" placeholder="text to type"> <button onclick="sendText()">Type</button></div>'
    + '<div><input id="lbl" size="26" placeholder="button label"> <button onclick="sendFind()">Find &amp; tap</button></div>'
    + '<div><button onclick="enqueue(\'screen_shot\')">Screenshot now</button>'
    + '<button onclick="enqueue(\'screen_start\')">Start stream</button>'
    + '<button onclick="stopStream()">Stop stream</button></div>'
    + '<script>var S=' + JSON.stringify(secret) + ';'
    + 'function qs(){return "?secret="+encodeURIComponent(S);}'
    + 'function post(path,body){return fetch(path+qs(),{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});}'
    + 'function input(o){post("/screen/input",o);}'
    + 'function key(k){input({type:"key",key:k});}'
    + 'function sendText(){var v=document.getElementById("txt").value;if(v)input({type:"text",text:v});}'
    + 'function sendFind(){var v=document.getElementById("lbl").value;if(v)input({type:"find_tap",label:v});}'
    + 'function enqueue(t){post("/phone-bridge/enqueue",{template:t,args:[]});}'
    + 'function stopStream(){input({type:"stop"});enqueue("screen_stop");}'
    + 'var img=document.getElementById("scr");'
    + 'function refresh(){img.src="/screen/frame.jpg"+qs()+"&t="+Date.now();}'
    + 'img.onload=function(){setTimeout(refresh,600);};'
    + 'img.onerror=function(){setTimeout(refresh,1500);};'
    + 'refresh();'
    + 'function norm(e){var r=img.getBoundingClientRect();'
    + 'return [Math.round((e.clientX-r.left)/r.width*1000),Math.round((e.clientY-r.top)/r.height*1000)];}'
    + 'var down=null;'
    + 'img.addEventListener("pointerdown",function(e){down={p:norm(e),t:Date.now()};});'
    + 'img.addEventListener("pointerup",function(e){if(!down)return;var q=norm(e);'
    + 'var dx=Math.abs(q[0]-down.p[0]),dy=Math.abs(q[1]-down.p[1]);'
    + 'if(dx<15&&dy<15){input({type:"tap",x:q[0],y:q[1]});}'
    + 'else{var ms=Math.max(50,Math.min(2000,Date.now()-down.t));'
    + 'input({type:"swipe",x1:down.p[0],y1:down.p[1],x2:q[0],y2:q[1],ms:ms});}'
    + 'down=null;});'
    + 'function meta(){fetch("/screen/meta"+qs()).then(function(r){return r.json();}).then(function(j){'
    + 'var el=document.getElementById("status");if(!j.meta){el.textContent="no frame yet";return;}'
    + 'var age=Math.round((Date.now()-j.meta.received_ms)/1000);'
    + 'var st=j.state&&j.state.streaming?"STREAMING":"not streaming";'
    + 'el.textContent=st+" · "+j.meta.w+"x"+j.meta.h+" · frame "+age+"s old";'
    + '}).catch(function(){});setTimeout(meta,1000);}'
    + 'meta();'
    + '</' + 'script></body></html>';
}

async function handleScreenViewGet(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  const secret = new URL(request.url).searchParams.get('secret') || '';
  return raw(screenViewHtml(secret), 'text/html; charset=utf-8', 200,
      { 'Cache-Control': 'no-store' });
}

// ==== File fetch (v1.36; app design docs/v1.36-files-subfolders-fetch-2026-11-24.md) ====
// The phone uploads ONE image (base64-in-JSON, the screen-frame
// discipline) from a files_get command; this side verifies the
// SHA-256 against the decoded bytes and sniffs the real image
// format from the magic bytes (the claimed extension is NOT
// trusted: a mislabeled genuine image is stored under its real
// format's extension, bytes matching no image format are refused),
// then stores the binary beside the JSON
// store (JSON cannot hold it) and serves it raw. One fetch slot:
// a new fetch replaces the previous one, whatever its extension.
const FILES_FETCH_MAX_BYTES = 2500000;
const FILES_FETCH_EXTS = ['jpg', 'jpeg', 'png', 'webp', 'gif'];
const FILES_FETCH_MIME = {
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png',
  webp: 'image/webp', gif: 'image/gif',
};

function filesFetchPath(ext) {
  return path.join(STORE_DIR, 'files_fetch_latest.' + ext);
}

function filesMagicOk(ext, buf) {
  return filesSniffExt(buf) === filesCanonExt(ext);
}

// Canonical extension for a claimed image type ('jpeg' folds to 'jpg').
function filesCanonExt(ext) {
  return ext === 'jpeg' ? 'jpg' : ext;
}

// Sniff the REAL image format from magic bytes, independent of the
// claimed extension. Returns the canonical extension or null when the
// bytes are not a supported image. Added 2026-11-24 after the ABG
// phone pass found 30 Pictures/Reddit files named .jpg whose bytes
// are WebP (RIFF....WEBP): the relay had trusted the claimed ext and
// rejected them all as magic mismatches. Verified magic bytes are
// the ground truth; a mislabeled extension is corrected, not refused.
function filesSniffExt(buf) {
  if (buf.length >= 3 && buf[0] === 0xFF && buf[1] === 0xD8 && buf[2] === 0xFF) {
    return 'jpg';
  }
  if (buf.length >= 8 && buf[0] === 0x89 && buf[1] === 0x50
    && buf[2] === 0x4E && buf[3] === 0x47 && buf[4] === 0x0D
    && buf[5] === 0x0A && buf[6] === 0x1A && buf[7] === 0x0A) {
    return 'png';
  }
  if (buf.length >= 6 && buf.toString('latin1', 0, 4) === 'GIF8') {
    return 'gif';
  }
  if (buf.length >= 12 && buf.toString('latin1', 0, 4) === 'RIFF'
    && buf.toString('latin1', 8, 12) === 'WEBP') {
    return 'webp';
  }
  return null;
}

async function handleFilesFetchPost(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let body;
  try { body = await request.json(); } catch (_) { return json({ error: 'bad request' }, 400); }
  if (!body || typeof body.data_b64 !== 'string' || !body.data_b64) {
    return json({ error: 'bad fetch' }, 400);
  }
  const ext = typeof body.ext === 'string' ? body.ext.toLowerCase() : '';
  if (!FILES_FETCH_EXTS.includes(ext)) return json({ error: 'not an image type' }, 400);
  const sha = typeof body.sha256 === 'string' ? body.sha256.toLowerCase() : '';
  if (!/^[0-9a-f]{64}$/.test(sha)) return json({ error: 'bad sha256' }, 400);
  let buf;
  try { buf = Buffer.from(body.data_b64, 'base64'); } catch (_) {
    return json({ error: 'bad fetch encoding' }, 400);
  }
  if (buf.length === 0 || buf.length > FILES_FETCH_MAX_BYTES) {
    return json({ error: 'bad fetch size' }, 400);
  }
  if (Number.isFinite(body.size) && body.size !== buf.length) {
    return json({ error: 'size mismatch' }, 400);
  }
  const digest = crypto.createHash('sha256').update(buf).digest('hex');
  if (digest !== sha) return json({ error: 'sha256 mismatch' }, 400);
  // Trust verified magic bytes over the claimed extension: a genuine
  // image whose name mislabels its format is stored under the REAL
  // format's extension; bytes matching no supported image are still
  // refused outright.
  const realExt = filesSniffExt(buf);
  if (!realExt) return json({ error: 'magic bytes mismatch' }, 400);
  const claimedExt = ext;
  const storeExt = realExt;
  for (const e of FILES_FETCH_EXTS) {
    if (e !== storeExt) { try { await fsp.unlink(filesFetchPath(e)); } catch (_) {} }
  }
  const tmp = filesFetchPath(storeExt) + '.tmp-' + process.pid + '-' + crypto.randomBytes(6).toString('hex');
  await fsp.writeFile(tmp, buf);
  await fsp.rename(tmp, filesFetchPath(storeExt));
  const meta = {
    name: capStr(body.name, 256),
    folder: capStr(body.folder, 64),
    sub: capStr(body.sub, 256),
    ext: storeExt,
    claimed_ext: claimedExt,
    format_mismatch: filesCanonExt(claimedExt) !== realExt,
    size: buf.length,
    sha256: digest,
    device_ts: Number.isFinite(body.ts) ? body.ts : 0,
    received_at: new Date().toISOString(),
    received_ms: Date.now(),
  };
  await env.MODE_SYNC.put('files:fetch', JSON.stringify(meta));
  return json({ ok: true, bytes: buf.length, sha256: digest, ext: storeExt, claimed_ext: claimedExt });
}

async function handleFilesFetchMetaGet(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let meta = null;
  try { meta = JSON.parse((await env.MODE_SYNC.get('files:fetch')) || 'null'); } catch (_) {}
  if (!meta) return json({ error: 'no fetch yet' }, 404);
  return json(meta, 200, { 'Cache-Control': 'no-store' });
}

async function handleFilesFetchBinGet(request, env) {
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  let meta = null;
  try { meta = JSON.parse((await env.MODE_SYNC.get('files:fetch')) || 'null'); } catch (_) {}
  if (!meta || !FILES_FETCH_EXTS.includes(meta.ext)) {
    return json({ error: 'no fetch yet' }, 404);
  }
  let buf;
  try { buf = await fsp.readFile(filesFetchPath(meta.ext)); } catch (_) {
    return json({ error: 'no fetch yet' }, 404);
  }
  return raw(buf, FILES_FETCH_MIME[meta.ext] || 'application/octet-stream', 200,
    { 'Cache-Control': 'no-store' });
}

// ==== Router (paths WITHOUT any /mode-sync prefix; mirrors the ====
// ==== Worker's fetch dispatch, minus panel lanes)               ====
async function route(request, env) {
  const url = new URL(request.url);
  const path = url.pathname;
  const method = request.method;
  // Auth on EVERY route, checked before dispatch (handlers re-check
  // it themselves as well, verbatim from worker.js).
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  if (path === '/mode-desired') {
    return handleModeDesired(request, env);
  }
  if (path === '/mode-set') {
    if (method !== 'POST') return json({ error: 'method not allowed' }, 405);
    return handleModeSet(request, env);
  }
  if (path === '/mode-ack') {
    if (method !== 'POST') return json({ error: 'method not allowed' }, 405);
    return handleModeAck(request, env);
  }
  if (path === '/mode-state') {
    return handleModeState(request, env);
  }
  if (path === '/phone-bridge/report') {
    if (method === 'POST') return handlePhoneBridgeReportPost(request, env);
    if (method === 'GET') return handlePhoneBridgeReportGet(request, env);
    return json({ error: 'method not allowed' }, 405);
  }
  if (path === '/phone-bridge/enqueue') {
    if (method !== 'POST') return json({ error: 'method not allowed' }, 405);
    return handlePhoneBridgeEnqueue(request, env);
  }
  if (path === '/phone-bridge/commands') {
    if (method !== 'GET') return json({ error: 'method not allowed' }, 405);
    return handlePhoneBridgeCommands(request, env);
  }
  if (path === '/phone-bridge/command-result') {
    if (method !== 'POST') return json({ error: 'method not allowed' }, 405);
    return handlePhoneBridgeCommandResult(request, env);
  }
  if (path === '/device-push') {
    if (method === 'POST') return handleDevicePushPost(request, env);
    if (method === 'GET') return handleDevicePushGet(request, env);
    return json({ error: 'method not allowed' }, 405);
  }
  if (path === '/phone-bridge/command-status') {
    if (method !== 'GET') return json({ error: 'method not allowed' }, 405);
    return handlePhoneBridgeCommandStatus(request, env);
  }
  if (path === '/screen/frame') {
    if (method !== 'POST') return json({ error: 'method not allowed' }, 405);
    return handleScreenFramePost(request, env);
  }
  if (path === '/screen/frame.jpg') {
    if (method !== 'GET') return json({ error: 'method not allowed' }, 405);
    return handleScreenFrameGet(request, env);
  }
  if (path === '/screen/meta') {
    if (method !== 'GET') return json({ error: 'method not allowed' }, 405);
    return handleScreenMetaGet(request, env);
  }
  if (path === '/screen/state') {
    if (method !== 'POST') return json({ error: 'method not allowed' }, 405);
    return handleScreenStatePost(request, env);
  }
  if (path === '/screen/input') {
    if (method === 'POST') return handleScreenInputPost(request, env);
    if (method === 'GET') return handleScreenInputGet(request, env);
    return json({ error: 'method not allowed' }, 405);
  }
  if (path === '/screen/view') {
    if (method !== 'GET') return json({ error: 'method not allowed' }, 405);
    return handleScreenViewGet(request, env);
  }
  if (path === '/files/fetch') {
    if (method !== 'POST') return json({ error: 'method not allowed' }, 405);
    return handleFilesFetchPost(request, env);
  }
  if (path === '/files/fetch.bin') {
    if (method !== 'GET') return json({ error: 'method not allowed' }, 405);
    return handleFilesFetchBinGet(request, env);
  }
  if (path === '/files/fetch-meta') {
    if (method !== 'GET') return json({ error: 'method not allowed' }, 405);
    return handleFilesFetchMetaGet(request, env);
  }
  // Auth is checked before the 404 fallthrough so an unauthenticated
  // probe of ANY path gets 403 (auth on every route).
  if (!modeAuthed(request, env)) return json({ error: 'forbidden' }, 403);
  return json({ error: 'not found' }, 404);
}

// ---- node:http plumbing ----
// Requests are serialized through a promise chain so the store's
// read-modify-write sequences (mode seq, queues) stay atomic per
// request, matching the single-threaded semantics consumers expect.
let chain = Promise.resolve();

const server = http.createServer((req, res) => {
  const chunks = [];
  let size = 0;
  req.on('data', (c) => {
    size += c.length;
    if (size > 4 * 1024 * 1024) { req.destroy(); return; }
    chunks.push(c);
  });
  req.on('end', () => {
    const raw = Buffer.concat(chunks).toString('utf8');
    const headersMap = req.headers || {};
    const request = {
      url: 'http://' + BIND + ':' + PORT + (req.url || '/'),
      method: req.method || 'GET',
      headers: { get: (name) => headersMap[String(name).toLowerCase()] || null },
      json: async () => JSON.parse(raw),
      text: async () => raw,
    };
    const run = chain.then(() => route(request, env));
    chain = run.catch(() => {});
    run.then((out) => {
      if (out && out.__relayRaw) {
        res.writeHead(out.status, { 'Content-Type': out.contentType, ...(out.headers || {}) });
        res.end(out.body);
        return;
      }
      const body = JSON.stringify(out.obj);
      res.writeHead(out.status, { 'Content-Type': 'application/json', ...(out.headers || {}) });
      res.end(body);
    }).catch((err) => {
      console.error('device-relay handler error:', err && err.stack || err);
      if (!res.headersSent) res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'internal' }));
    });
  });
  req.on('error', () => { try { res.destroy(); } catch (_) {} });
});

fs.mkdirSync(STORE_DIR, { recursive: true });
server.listen(PORT, BIND, () => {
  console.log('device-relay listening on ' + BIND + ':' + PORT);
});
