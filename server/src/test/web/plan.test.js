// The Pro accident account's readings and the saved-clip encryption, as the phone pages run them.
// Run with `node --test "src/test/web/*.test.js"` (the server's webTest task).
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const web = path.join(__dirname, '../../main/resources/web');
global.WBPacket = require(path.join(web, 'packet.js'));
const Report = require(path.join(web, 'report.js'));
const Clip = require(path.join(web, 'clip.js'));

/** A drive in the shape the codec decodes it to: [ms from the start, kind, value]. */
function drive(rows) {
  const t0 = 1_800_000_000_000;
  return rows.map(([ms, kind, value], i) => ({ seq: i, e: { type: 'S', kind, value, at: { wall: t0 + ms, ns: ms * 1e6 } } }));
}

test('the account reads rain from wipers that ran for minutes, and says how sure it is', () => {
  const t0 = 1_800_000_000_000;
  const rows = [[0, 'WIPER', { area: 'FRONT_WINDSHIELD', mode: 'OFF' }], [5000, 'WIPER', { area: 'FRONT_WINDSHIELD', mode: 'INTERMITTENT' }], [1000, 'LIGHT', { lamp: 'LOW_BEAM', on: true }],
    [1000, 'SEAT_BELT', { seat: 'ROW_1_LEFT', buckled: true }], [1000, 'GEAR', 'DRIVE'], [1000, 'TURN_SIGNAL', 'OFF']];
  for (let s = 2; s <= 250; s++) rows.push([s * 1000, 'SPEED', 60 / 3.6]);
  rows.push([248_500, 'BRAKE_PEDAL', true]);
  for (let i = 1; i <= 15; i++) rows.push([250_000 + i * 100, 'SPEED', (60 - i * 3) / 3.6]);
  rows.push([253_000, 'DOOR', { area: 'ROW_1_LEFT', open: true }]);
  rows.sort((a, b) => a[0] - b[0]);
  const events = drive(rows);
  const cause = t0 + 251_500;
  const text = Report.interpret(events, cause, {}).map((r) => r.text + ' ' + (r.note || '')).join('\n');
  assert.match(text, /와이퍼가 감지 전 4분 7초 동안 간헐적으로 작동/);
  assert.match(text, /비나 눈이 오고 있었을 가능성이 높다/);
  assert.match(text, /브레이크는 감지 3\.0초 전에 밟았고/);
  assert.match(text, /감지 10초 전에는 시속 60 km/);
  assert.match(text, /전조등이 켜져 있었습니다/);
  assert.match(text, /운전석 안전벨트를 매고 있었습니다/);
  assert.match(text, /운전석 문이 열렸습니다/);
  assert.match(text, /방향지시등은 꺼져/);

  const log = Report.log(events, cause);
  assert.equal(log.length, events.length); // every record, at the bottom
  assert.equal(log.find((r) => r[3] === '브레이크')[4], '밟음');
});

test('a clip opens only with its own key, and any change to it is refused', async () => {
  const minuteKey = new Uint8Array(32).fill(7);
  const id = Clip.newId();
  const k = await Clip.key(minuteKey, id);
  const bytes = new Uint8Array(Clip.PIECE + 1234).map((_, i) => i % 251); // two pieces
  const sealed = new Uint8Array(await (await Clip.encrypt(k, new Blob([bytes]))).arrayBuffer());
  assert.equal(sealed.length, bytes.length + 2 * 28);
  const back = new Uint8Array(await (await Clip.decrypt(k, sealed.buffer)).arrayBuffer());
  assert.deepEqual(back, bytes);

  await assert.rejects(Clip.decrypt(await Clip.key(minuteKey, Clip.newId()), sealed.buffer)); // another clip's key
  const tampered = sealed.slice();
  tampered[100] ^= 1;
  await assert.rejects(Clip.decrypt(k, tampered.buffer));
});

test('a clip the car encrypted itself opens on the phone with the same key derivation', async () => {
  const fs = require('node:fs');
  const V = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../build/web-vectors/vectors.json'), 'utf8'));
  const b = (s) => Uint8Array.from(Buffer.from(s, 'base64'));
  const k = await Clip.key(b(V.clip.minuteKey), V.clip.clipId);
  const plain = new Uint8Array(await (await Clip.decrypt(k, b(V.clip.sealed).buffer)).arrayBuffer());
  assert.deepEqual(plain, b(V.clip.plain));
});
