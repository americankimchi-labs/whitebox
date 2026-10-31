// White Box record codec for the phone pages: the same chain lines and record formats the car writes, decoded in
// the browser. Encrypted values open with the car's root keys (the owner's phone) or with the minute keys of one
// severe crash (handed to the family through the server). Runs in the browser and in Node (for its tests).
(function (root) {
  const subtle = globalThis.crypto.subtle;
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  const unb64 = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
  const hex = (bytes) => Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, '0')).join('');

  /** `seq\tprev\tentry\thash` → {seq, prev, entry, hash, line}; null if malformed. */
  function parseLine(line) {
    const f = line.split('\t');
    if (f.length !== 4) return null;
    const seq = Number(f[0]);
    if (!Number.isInteger(seq)) return null;
    return { seq, prev: f[1], entry: f[2], hash: f[3], line };
  }

  async function sha256Hex(text) { return hex(await subtle.digest('SHA-256', enc.encode(text))); }

  const isSealed = (s) => /^v[123]\./.test(s || '');

  /** The key epoch and minute an encrypted field names, and its body. */
  function fieldInfo(s) {
    if (s.startsWith('v3.')) { const [, e, m, ...b] = s.split('.'); return { epoch: Number(e), minute: Number(m), body: b.join('.') }; }
    if (s.startsWith('v2.')) { const [, e, ...b] = s.split('.'); return { epoch: Number(e), minute: null, body: b.join('.') }; }
    return { epoch: 0, minute: null, body: s.slice(3) };
  }

  /** The key of one minute: HKDF-SHA256 of the root key, empty salt, info `whitebox-minute-v1|<minute>`. */
  async function minuteKey(rootKey, minute) {
    const k = await subtle.importKey('raw', rootKey, 'HKDF', false, ['deriveBits']);
    return new Uint8Array(await subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: enc.encode(`whitebox-minute-v1|${minute}`) }, k, 256));
  }

  /**
   * The keys a reader holds: [roots] epoch → root key (the owner), and/or [minutes] "epoch:minute" → key (a crash's
   * keys for the family). Imported AES keys are cached per field key.
   */
  function keyring({ roots = {}, minutes = {} } = {}) {
    const cache = new Map();
    async function aesFor(info) {
      const id = `${info.epoch}:${info.minute}`;
      if (cache.has(id)) return cache.get(id);
      let raw = null;
      if (info.minute != null) raw = minutes[`${info.epoch}:${info.minute}`] || (roots[info.epoch] && await minuteKey(roots[info.epoch], info.minute));
      else raw = roots[info.epoch] || null;
      const key = raw ? await subtle.importKey('raw', raw, 'AES-GCM', false, ['decrypt']) : null;
      cache.set(id, key);
      return key;
    }
    return { aesFor, isEmpty: () => !Object.keys(roots).length && !Object.keys(minutes).length };
  }

  /** Decrypts one encrypted field bound to [aad]; null without the key; throws if it was altered. */
  async function openField(ring, sealed, aad) {
    const info = fieldInfo(sealed);
    const key = ring && await ring.aesFor(info);
    if (!key) return null;
    const bytes = unb64(info.body);
    return dec.decode(await subtle.decrypt({ name: 'AES-GCM', iv: bytes.slice(0, 12), additionalData: enc.encode(aad) }, key, bytes.slice(12)));
  }

  const ts = (s) => { const [ns, wall] = s.split(';'); return { ns: Number(ns), wall: Number(wall) }; };
  const bool = (s) => { if (s !== 'true' && s !== 'false') throw new Error('not a boolean: ' + s); return s === 'true'; };
  const pair = (s) => { const i = s.indexOf(';'); return [s.slice(0, i), s.slice(i + 1)]; };

  /** A signal value as written by the car, per kind. */
  function signalValue(kind, v) {
    switch (kind) {
      case 'BRAKE_PEDAL': case 'PARKING_BRAKE': return bool(v);
      case 'TURN_SIGNAL': case 'GEAR': case 'IGNITION': case 'CRUISE_CONTROL': return v;
      case 'STEERING_ANGLE': case 'SPEED': case 'CRUISE_TARGET_SPEED': case 'ODOMETER': case 'ROAD_GRADE':
      case 'EV_EFFICIENCY': case 'ACCELERATOR_PEDAL': case 'BRAKE_PRESSURE': return Number(v);
      case 'AIRBAG': { const [seat, d] = pair(v); return { seat, deployed: bool(d) }; }
      case 'TELLTALE': { const [name, state] = pair(v); return { name, state }; }
      case 'DOOR': { const [area, o] = pair(v); return { area, open: bool(o) }; }
      case 'SEAT_BELT': { const [seat, b] = pair(v); return { seat, buckled: bool(b) }; }
      case 'SEAT_OCCUPANCY': { const [seat, o] = pair(v); return { seat, occupied: bool(o) }; }
      case 'LIGHT': { const [lamp, o] = pair(v); return { lamp, on: bool(o) }; }
      case 'WIPER': { const [area, mode] = pair(v); return { area, mode }; }
      case 'WINDOW': { const [area, p] = pair(v); return { area, position: Number(p) }; }
      case 'ROAD_CONTEXT': { const [type, value] = pair(v); return { type, value }; }
      default: throw new Error('unknown signal kind ' + kind);
    }
  }

  /**
   * One record's entry decoded: {type:'S', kind, value, at, src} (signal), {type:'L', lat, lon, acc, gps, at},
   * {type:'R'|'E'|'A', event, …, at}. An encrypted value without its key decodes as {…, locked: true}.
   */
  async function decode(entry, ring) {
    const f = entry.split('|');
    switch (f[0]) {
      case 'S': {
        const at = ts(f[3]);
        const src = f[4] === '-' ? null : Number(f[4]);
        if (!isSealed(f[2])) return { type: 'S', kind: f[1], value: signalValue(f[1], f[2]), at, src };
        const plain = await openField(ring, f[2], `S|${f[1]}|${f[3]}`);
        return plain == null ? { type: 'S', kind: f[1], locked: true, at, src } : { type: 'S', kind: f[1], value: signalValue(f[1], plain), at, src };
      }
      case 'L': {
        if (!isSealed(f[1])) return { type: 'L', lat: Number(f[1]), lon: Number(f[2]), acc: f[3] === '-' ? null : Number(f[3]), gps: Number(f[4]), at: ts(f[5]) };
        const at = ts(f[2]);
        const plain = await openField(ring, f[1], `L|${f[2]}`);
        if (plain == null) return { type: 'L', locked: true, at };
        const v = plain.split(';');
        return { type: 'L', lat: Number(v[0]), lon: Number(v[1]), acc: v[2] === '-' ? null : Number(v[2]), gps: Number(v[3]), at };
      }
      case 'R': {
        const at = ts(f[1]);
        switch (f[2]) {
          case 'STARTED': return {
            type: 'R', event: 'STARTED', reason: f[3], restartCount: Number(f[4]), at,
            gap: f[5] === '-' ? null : { cause: f[5], fromWall: Number(f[6]), untilWall: Number(f[7]), durationMs: Number(f[8]), acrossBoots: bool(f[9]) },
          };
          case 'STOPPED': return { type: 'R', event: 'STOPPED', at };
          case 'DEGRADED': case 'RESTORED': return { type: 'R', event: f[2], permission: f[3], at };
          default: throw new Error('unknown recorder event ' + f[2]);
        }
      }
      case 'E': {
        const at = ts(f[1]);
        switch (f[2]) {
          case 'LEVEL': return { type: 'E', event: 'LEVEL', level: f[3], causeSeq: Number(f[4]), at };
          case 'TRIGGER': return { type: 'E', event: 'TRIGGER', trigger: f[3], causeSeq: Number(f[4]), measured: Number(f[5]), at };
          case 'OFFER': return { type: 'E', event: 'OFFER', capsule: { id: f[3], trigger: f[4], firstSeq: Number(f[5]), lastSeq: Number(f[6]) }, at };
          case 'ANSWER': return { type: 'E', event: 'ANSWER', capsuleId: f[3], keep: bool(f[4]), answered: f[5] == null ? true : bool(f[5]), at };
          default: throw new Error('unknown escalation ' + f[2]);
        }
      }
      case 'A': {
        const at = ts(f[1]);
        switch (f[2]) {
          case 'BOOKMARK': return { type: 'A', event: 'BOOKMARK', source: f[3], at };
          case 'SPOKE': return { type: 'A', event: 'SPOKE', line: f[3], complete: bool(f[4]), at };
          case 'BEACON': return { type: 'A', event: 'BEACON', chirpId: Number(f[3]), playedAt: ts(f[4]), at };
          default: throw new Error('unknown audio event ' + f[2]);
        }
      }
      default: throw new Error('unknown entry type ' + f[0]);
    }
  }

  /** The (epoch, minute) keys a list of entries needs; for asking which minutes a capsule covers. */
  function minutesOf(entries) {
    const out = new Set();
    for (const e of entries) for (const field of e.split('|')) if (field.startsWith('v3.')) { const i = fieldInfo(field); out.add(`${i.epoch}:${i.minute}`); }
    return [...out];
  }

  const WBCodec = { parseLine, sha256Hex, isSealed, fieldInfo, minuteKey, keyring, openField, decode, minutesOf, unb64, hex };
  if (typeof module === 'object' && module.exports) module.exports = WBCodec; else root.WBCodec = WBCodec;
})(typeof self !== 'undefined' ? self : globalThis);
