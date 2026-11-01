// The phone pages' JavaScript against what the car's own code produced (written by WebVectorsTest): decoding every
// record format, opening a crash with its minute keys alone, the evidence timeline, signatures, and the sync chirp.
// Run with `node --test "src/test/web/*.test.js"` after the Kotlin tests (the server's webTest task does both).
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const web = path.join(__dirname, '../../main/resources/web');
const Codec = require(path.join(web, 'codec.js'));
const Packet = require(path.join(web, 'packet.js'));
const Verify = require(path.join(web, 'verify.js'));
const Sync = require(path.join(web, 'sync.js'));
const V = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../build/web-vectors/vectors.json'), 'utf8'));

const roots = Object.fromEntries(Object.entries(V.roots).map(([e, k]) => [e, Codec.unb64(k)]));

/** Deep equality where numbers agree to float precision (the car writes Kotlin floats as decimal text). */
function close(actual, expected, where = '') {
  if (typeof expected === 'number' && typeof actual === 'number') {
    assert.ok(Math.abs(actual - expected) <= 1e-5 * Math.max(1, Math.abs(expected)), `${where}: ${actual} ≠ ${expected}`);
  } else if (Array.isArray(expected)) {
    assert.ok(Array.isArray(actual), `${where}: not a list`);
    assert.equal(actual.length, expected.length, `${where}: length`);
    expected.forEach((v, i) => close(actual[i], v, `${where}[${i}]`));
  } else if (expected && typeof expected === 'object') {
    assert.ok(actual && typeof actual === 'object', `${where}: not an object`);
    assert.deepEqual(Object.keys(actual).sort(), Object.keys(expected).sort(), `${where}: keys`);
    for (const k of Object.keys(expected)) close(actual[k], expected[k], `${where}.${k}`);
  } else {
    assert.equal(actual, expected, where);
  }
}

test('every record the car writes decodes to the same values', async () => {
  const ring = Codec.keyring({ roots });
  for (let i = 0; i < V.lines.length; i++) {
    const r = Codec.parseLine(V.lines[i]);
    assert.equal(r.seq, i);
    close(await Codec.decode(r.entry, ring), V.decoded[i], `record ${i}`);
  }
});

test('older record formats still open', async () => {
  const ring = Codec.keyring({ roots });
  for (let i = 0; i < V.legacy.length; i++) {
    const d = await Codec.decode(V.legacy[i], ring);
    close({ kind: d.kind, value: d.value }, V.legacyDecoded[i], `legacy ${i}`);
  }
});

test('without a key the values stay locked, and only markers read', async () => {
  for (const line of V.lines) {
    const r = Codec.parseLine(line);
    const d = await Codec.decode(r.entry, Codec.keyring());
    if (d.type === 'S' || d.type === 'L') assert.equal(d.locked, true);
    else assert.equal(d.locked, undefined);
  }
});

test('the minute key is the one the car derives, and opens that minute only', async () => {
  const derived = await Codec.minuteKey(roots[V.minute.epoch], V.minute.minute);
  assert.equal(Buffer.from(derived).toString('base64'), V.minute.key);
  const family = Codec.keyring({ minutes: { [`${V.minute.epoch}:${V.minute.minute}`]: Codec.unb64(V.minute.key) } });
  let opened = 0, locked = 0;
  for (const line of V.lines) {
    const r = Codec.parseLine(line);
    const minutes = Codec.minutesOf([r.entry]);
    const d = await Codec.decode(r.entry, family);
    if (!minutes.length) continue;
    if (minutes[0] === `${V.minute.epoch}:${V.minute.minute}`) { assert.ok(!d.locked); opened++; } else { assert.equal(d.locked, true); locked++; }
  }
  assert.ok(opened > 0 && locked > 0, `opened ${opened}, locked ${locked}`);
});

test('an altered value or a value moved to another record does not open', async () => {
  const ring = Codec.keyring({ roots });
  const brake = V.lines.map(Codec.parseLine).find((r) => r.entry.startsWith('S|BRAKE_PEDAL|'));
  const f = brake.entry.split('|');
  const flipped = [...f]; flipped[2] = f[2].slice(0, -4) + (f[2].endsWith('AAAA') ? 'BBBB' : 'AAAA');
  await assert.rejects(Codec.decode(flipped.join('|'), ring));
  const moved = [...f]; moved[3] = '1;1';
  await assert.rejects(Codec.decode(moved.join('|'), ring));
});

test('the evidence timeline is the car\'s, lane for lane', async () => {
  const ring = Codec.keyring({ roots });
  const events = [];
  for (const line of V.capsule.records) { const r = Codec.parseLine(line); events.push({ seq: r.seq, e: await Codec.decode(r.entry, ring) }); }
  const t = Packet.build(events, V.capsule.causeSeq);
  const { pre, post, causeSeq, causeWall, ...mine } = t;
  close(mine, V.capsule.timeline, 'timeline');
  assert.ok(t.lines.length >= 4);
  assert.match(Packet.svg(t), /^<svg/);
  for (const l of t.lines) assert.match(Packet.lineText(l), /초 전|그대로/);
});

test('the plain readings are the car\'s, sentence for sentence', async () => {
  const Report = require(path.join(web, 'report.js'));
  const ring = Codec.keyring({ roots });
  const events = [];
  for (const line of V.capsule.records) { const r = Codec.parseLine(line); events.push({ seq: r.seq, e: await Codec.decode(r.entry, ring) }); }
  const t = Packet.build(events, V.capsule.causeSeq);
  const mine = Report.interpret(events, t.causeWall, t.context).map((r) => ({ text: r.text, note: r.note ?? null }));
  assert.deepEqual(mine, V.capsule.readings);
  assert.ok(mine.length >= 10, `only ${mine.length} readings`);
});

test('a capsule checks: links, hashes, the car\'s seal and the server\'s anchor', async () => {
  const data = { records: V.capsule.records, capsule: { firstSeq: 0, lastSeq: V.capsule.records.length - 1, seals: [V.capsule.seal] }, anchors: [V.capsule.anchor], serverKey: V.capsule.serverKey };
  const ok = await Verify.capsule(data);
  assert.equal(ok.ok, true, JSON.stringify(ok));
  assert.equal(ok.chain.checked, V.capsule.records.length);

  // One record changed: its hash no longer matches.
  const tampered = [...V.capsule.records];
  const f = tampered[20].split('\t'); f[2] = f[2].replace('S|', 'S|') + 'x'; tampered[20] = f.join('\t');
  const bad = await Verify.capsule({ ...data, records: tampered });
  assert.equal(bad.ok, false);
  assert.equal(bad.chain.brokenAt, 20);

  // A seal over a different head, or not signed by the car.
  const s = V.capsule.seal.split('|'); s[4] = '0'.repeat(64);
  assert.equal((await Verify.capsule({ ...data, capsule: { ...data.capsule, seals: [s.join('|')] } })).ok, false);
  const forged = V.capsule.anchor.replace(/\|(\d+)\|sig=/, (m, t) => `|${Number(t) + 1}|sig=`);
  assert.equal((await Verify.capsule({ ...data, anchors: [forged] })).ok, false);
});

test('the sync chirp template is the car\'s, sample for sample', () => {
  const c = Sync.chirp(V.chirp.sampleRate);
  assert.equal(c.length, V.chirp.length);
  V.chirp.head.forEach((v, i) => assert.ok(Math.abs(c[i] - v) < 1e-6, `sample ${i}`));
});

test('the chirp is found in a noisy recording to within a millisecond', () => {
  const rate = 48000;
  const c = Sync.chirp(rate);
  const audio = new Float32Array(rate * 3);
  let seed = 7;
  const noise = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return (seed / 0x7fffffff - 0.5) * 0.2; };
  for (let i = 0; i < audio.length; i++) audio[i] = noise();
  const at = Math.round(rate * 1.2345);
  for (let i = 0; i < c.length; i++) audio[at + i] += c[i] * 0.4; // quieter than it was played, as a cabin mic hears it
  const found = Sync.find(audio, rate);
  assert.ok(found, 'not found');
  assert.ok(Math.abs(found.atSeconds - at / rate) < 0.001, `found at ${found.atSeconds}`);
});

test('a sweep far from where the file\'s own clock puts it is not trusted', () => {
  const beacons = [1_700_000_015_000, 1_700_000_030_000];
  // The file says it started at …010 s; a sweep 5 s in matches the beacon at …015 s.
  assert.deepEqual(Sync.align([{ atSeconds: 5, strength: 0.8 }], beacons, 1_700_000_010_400), { startWall: 1_700_000_010_000, how: 'chirp', error: 1 });
  // A video from another day: the nearest sweep is hours away from the file's clock, so the clock is used instead.
  assert.equal(Sync.align([{ atSeconds: 5, strength: 0.8 }], beacons, 1_700_100_000_000).how, 'clock');
  // A clock up to 7 s off still picks its sweep; farther, the sweeps (every 15 s) could be confused, so the clock places it.
  assert.equal(Sync.align([{ atSeconds: 5, strength: 0.8 }], beacons, 1_700_000_017_000).how, 'chirp');
  assert.equal(Sync.align([{ atSeconds: 5, strength: 0.8 }], beacons, 1_700_000_017_500).how, 'clock'); // halfway between two sweeps
  // No clock and several sweeps played: cannot tell which one this is.
  assert.equal(Sync.align([{ atSeconds: 5, strength: 0.8 }], beacons, null).how, 'none');
});

test('a video of another moment is not snapped onto this record\'s sweeps', () => {
  // This record: …000 s to …140 s, sweeps every 15 s. The video's own clock says it began two minutes after the record.
  const beacons = [15, 30, 45, 60, 75, 90, 105, 120].map((s) => 1_700_000_000_000 + s * 1000);
  const span = { fromWall: 1_700_000_000_000, toWall: 1_700_000_140_000 };
  const later = 1_700_000_260_000;
  const a = Sync.align([{ atSeconds: 3.2, strength: 0.9 }], beacons, later, { span, durationMs: 8_300 });
  assert.deepEqual(a, { startWall: null, how: 'elsewhere', error: null }); // not "exact": it is not this moment
  // A clock inside the record but halfway between two sweeps: which one is unclear, so the clock places it (about 1 s).
  assert.equal(Sync.align([{ atSeconds: 3.2, strength: 0.9 }], beacons, 1_700_000_049_300, { span, durationMs: 8_300 }).how, 'clock');
});
