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
    if (d.type === 'S' || d.type === 'C' || d.type === 'L') assert.equal(d.locked, true);
    else assert.equal(d.locked, undefined);
  }
});

test('a minute key opens the driving, not where the car was; the location key opens the place', async () => {
  const minutes = {}, places = {};
  for (const line of V.lines) {
    const entry = Codec.parseLine(line).entry;
    for (const em of Codec.minutesOf([entry])) { const [e, m] = em.split(':'); minutes[em] = await Codec.minuteKey(roots[e], Number(m)); }
    for (const em of Codec.placesOf([entry])) { const [e, m] = em.split(':'); places[em] = await Codec.placeKey(roots[e], Number(m)); }
  }
  assert.ok(Object.keys(places).length > 0, 'the vectors hold no location');
  const qr = Codec.keyring({ minutes }); // what the car's QR carries
  const owner = Codec.keyring({ minutes, places }); // what a link the owner made carries
  for (let i = 0; i < V.lines.length; i++) {
    const entry = Codec.parseLine(V.lines[i]).entry;
    const a = await Codec.decode(entry, qr);
    const b = await Codec.decode(entry, owner);
    if (a.type === 'L') { assert.equal(a.locked, true, `record ${i}: the QR opened a place`); close(b, V.decoded[i], `record ${i}`); }
    if (a.type === 'S') assert.notEqual(a.locked, true, `record ${i}: a signal stayed locked`);
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
  // Full screen draws taller lanes on the same time scale (the playhead's place does not move).
  const tall = Packet.svg(t, { lane: 90 }), h = (svg) => Number(/viewBox="0 0 \d+ (\d+)"/.exec(svg)[1]);
  assert.ok(h(tall) > h(Packet.svg(t)) * 2);
  assert.equal(/<line x1="([\d.]+)"[^>]*stroke="var\(--bad/.exec(tall)[1], /<line x1="([\d.]+)"[^>]*stroke="var\(--bad/.exec(Packet.svg(t))[1]);
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

test('states carried into a capsule\'s window read as held throughout, on the car and the phone alike', async () => {
  const Report = require(path.join(web, 'report.js'));
  const ring = Codec.keyring({ roots });
  const events = [];
  for (const line of V.carried.records) { const r = Codec.parseLine(line); events.push({ seq: r.seq, e: await Codec.decode(r.entry, ring) }); }
  const t = Packet.build(events, V.carried.causeSeq);
  const { pre, post, causeSeq, causeWall, ...mine } = t;
  close(mine, V.carried.timeline, 'carried timeline');
  assert.equal(t.gear[0].value, 'DRIVE');
  assert.equal(t.context.seatBelts.ROW_1_LEFT, true);
  assert.ok(Packet.contextRows(t.context).length >= 4, 'the state at the moment is shown');
  for (const l of t.lines.filter((l) => l.type !== 'STEERING_PEAK')) assert.match(Packet.lineText(l), /그대로/);
  const readings = Report.interpret(events, t.causeWall, t.context).map((r) => ({ text: r.text, note: r.note ?? null }));
  assert.deepEqual(readings, V.carried.readings);
  assert.ok(readings.some((r) => /이미 밟고/.test(r.text)), 'the held brake');
  assert.ok(readings.some((r) => /운전석 안전벨트를 매고/.test(r.text)), 'the belt buckled before the window');
  assert.ok(Report.log(events, t.causeWall).some((row) => /구간 시작 때/.test(row[3])), 'the log shows them as the starting state');
});

test('a fall with repeated speeds starts where the speed began to fall; lanes reach both edges; seats in order', async () => {
  const Report = require(path.join(web, 'report.js'));
  const ring = Codec.keyring({ roots });
  const events = [];
  for (const line of V.fall.records) { const r = Codec.parseLine(line); events.push({ seq: r.seq, e: await Codec.decode(r.entry, ring) }); }
  const t = Packet.build(events, V.fall.causeSeq);
  const { pre, post, causeSeq, causeWall, ...mine } = t;
  close(mine, V.fall.timeline, 'fall timeline');
  assert.equal(t.speedKmh[0].atMs, -10000, 'the speed lane starts at the window edge');
  assert.equal(t.speedKmh[t.speedKmh.length - 1].atMs, t.endMs, 'and runs to the end of what was kept');
  const readings = Report.interpret(events, t.causeWall, t.context).map((r) => ({ text: r.text, note: r.note ?? null }));
  assert.deepEqual(readings, V.fall.readings);
  assert.ok(readings.some((r) => /감지 1\.\d초 전부터 속도가 줄기 시작했습니다/.test(r.text)), readings.map((r) => r.text).join(' / '));
  assert.ok(readings.some((r) => r.text === '사람이 앉아 있던 좌석: 운전석, 조수석, 뒷좌석 오른쪽.'), 'seats front first, driver first');
});

test('the log quotes the car\'s spoken words, never a code', async () => {
  const Report = require(path.join(web, 'report.js'));
  for (const line of V.voiceLines) assert.ok(Report.VOICE[line], `no words for ${line}`);
  const at = (wall) => ({ ns: wall * 1e6, wall });
  const events = [
    { seq: 1, e: { type: 'A', event: 'SPOKE', line: 'KEEP_QUESTION', complete: true, at: at(1000) } },
    { seq: 2, e: { type: 'A', event: 'SPOKE', line: 'KEPT', complete: false, at: at(2000) } },
  ];
  const spoken = Report.log(events, 0).map((r) => r[4]);
  assert.deepEqual(spoken, ['“방금 상황 보관할까요?”', '“보관 완료” (중단됨)']);
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

test('full screen, the lanes span the clip itself, and the playhead crosses them', () => {
  // A 14 s clip that starts 4 s before the moment: the lanes run from −4 s to +10 s around it.
  const span = Sync.clipSpan(1_000_000 - 4000, 14_000, 1_000_000);
  assert.deepEqual(span, { pre: 4000, post: 10_000 });
  const t = { pre: span.pre, post: span.post };
  assert.ok(Packet.xFraction(t, -4000) < 0.25 && Packet.xFraction(t, 10_000) > 0.97, 'first and last frame at the edges');
  assert.ok(Math.abs(Packet.msAtFraction(t, Packet.xFraction(t, 2500)) - 2500) < 1e-6);
  // A clip shorter than a second still gets a readable span.
  const tiny = Sync.clipSpan(5000, 400, 5000);
  assert.ok(tiny.pre + tiny.post >= 1000);
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
