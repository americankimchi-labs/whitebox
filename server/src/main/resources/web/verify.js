// Checks a capsule in the phone's browser, the same way the car and the server do: every record matches its
// SHA-256 hash and links to the one before, the car's seal is signed by its device key over the last record's hash,
// and the server's signed anchors of the chain head agree. Nothing here trusts the server's word for it.
(function (root) {
  const subtle = globalThis.crypto.subtle;
  const enc = new TextEncoder();
  const Codec = (typeof module === 'object' && module.exports) ? require('./codec.js') : root.WBCodec;
  const ZERO = '0'.repeat(64);

  /** A DER ECDSA signature (what the JDK and Android produce) as the 64-byte r‖s WebCrypto verifies. */
  function derToRaw(der) {
    let i = 2;
    if (der[0] !== 0x30) throw new Error('not a DER signature');
    if (der[1] & 0x80) i = 2 + (der[1] & 0x7f);
    const int = () => {
      if (der[i++] !== 0x02) throw new Error('not a DER integer');
      const len = der[i++];
      let v = der.slice(i, i + len);
      i += len;
      while (v.length > 32 && v[0] === 0) v = v.slice(1);
      const out = new Uint8Array(32);
      out.set(v, 32 - v.length);
      return out;
    };
    const r = int(), s = int();
    const raw = new Uint8Array(64);
    raw.set(r); raw.set(s, 32);
    return raw;
  }

  async function ecdsaValid(spki, signatureDer, text) {
    try {
      const key = await subtle.importKey('spki', spki, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
      return await subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, derToRaw(signatureDer), enc.encode(text));
    } catch (e) { return false; }
  }

  /**
   * [lines]: consecutive chain lines; the first may be the record just before the range (its hash anchors the first
   * link). Returns {ok, checked, brokenAt, reason, lastHash}.
   */
  async function chain(lines, { fromSeq } = {}) {
    const recs = lines.map(Codec.parseLine);
    if (recs.some((r) => !r)) return { ok: false, checked: 0, reason: '형식이 맞지 않는 기록' };
    let prev = null;
    let checked = 0;
    for (let k = 0; k < recs.length; k++) {
      const r = recs[k];
      if (k > 0 && r.seq !== recs[k - 1].seq + 1) return { ok: false, checked, brokenAt: r.seq, reason: '빠진 기록' };
      if (prev === null) prev = r.seq === 0 ? ZERO : null;
      if (prev !== null && r.prev !== prev) return { ok: false, checked, brokenAt: r.seq, reason: '앞 기록과의 연결이 끊김' };
      const h = await Codec.sha256Hex(`${r.seq}\t${r.prev}\t${r.entry}`);
      if (h !== r.hash) return { ok: false, checked, brokenAt: r.seq, reason: '내용이 해시와 다름' };
      prev = h;
      if (fromSeq == null || r.seq >= fromSeq) checked++;
    }
    return { ok: true, checked, lastHash: prev };
  }

  /** A seal line from the car: `id|trigger|first|last|headHex|final|at|publicKeyB64|signatureB64`. */
  function parseSeal(line) {
    const f = line.split('|');
    if (f.length !== 9) return null;
    return { id: f[0], trigger: f[1], firstSeq: Number(f[2]), lastSeq: Number(f[3]), head: f[4], final: f[5] === 'true', at: Number(f[6]), publicKey: f[7], signature: f[8], text: f.slice(0, 7).join('|') };
  }

  /** The seal is signed by the car's key and names the hash the chain really has at its last record. */
  async function seal(line, hashAt) {
    const s = parseSeal(line);
    if (!s) return { ok: false, reason: '봉인 형식 오류' };
    const signed = await ecdsaValid(Codec.unb64(s.publicKey), Codec.unb64(s.signature), s.text);
    if (!signed) return { ok: false, seal: s, reason: '차량 서명이 맞지 않음' };
    const held = hashAt(s.lastSeq);
    if (held && held !== s.head) return { ok: false, seal: s, reason: '봉인한 해시와 기록이 다름' };
    return { ok: true, seal: s, matched: !!held };
  }

  /** A server line `…|sig=<DER b64>` (anchor or attestation) signed by the server key [serverKeyB64]. */
  async function serverSigned(line, serverKeyB64) {
    const i = line.lastIndexOf('|sig=');
    if (i < 0) return false;
    return ecdsaValid(Codec.unb64(serverKeyB64), Codec.unb64(line.slice(i + 5)), line.slice(0, i));
  }

  /** `ANCHOR|stream|head|hash|serverMillis|sig=…` → {head, hash, at}. */
  function parseAnchor(line) {
    const f = line.split('|');
    return f[0] === 'ANCHOR' ? { head: Number(f[f.length - 4]), hash: f[f.length - 3], at: Number(f[f.length - 2]) } : null;
  }

  /**
   * Everything about one capsule as the capsule route returns it: {records, capsule: {seals, attestation, lastSeq},
   * anchors, serverKey}. Returns {ok, chain, seals: [...], anchors: [...], attestation} for the "자세히" view.
   */
  async function capsule(data) {
    const first = data.capsule.firstSeq;
    const c = await chain(data.records, { fromSeq: first });
    const hashes = new Map(data.records.map(Codec.parseLine).filter(Boolean).map((r) => [r.seq, r.hash]));
    const seals = [];
    for (const line of data.capsule.seals || []) seals.push(await seal(line, (seq) => hashes.get(seq)));
    const anchors = [];
    for (const line of data.anchors || []) {
      const a = parseAnchor(line);
      const signed = await serverSigned(line, data.serverKey);
      anchors.push({ ...a, signed, agrees: a && hashes.has(a.head) ? hashes.get(a.head) === a.hash : null });
    }
    const attestation = data.capsule.attestation ? { signed: await serverSigned(data.capsule.attestation, data.serverKey) } : null;
    const ok = c.ok && seals.every((s) => s.ok) && anchors.every((a) => a.signed && a.agrees !== false) && (!attestation || attestation.signed);
    return { ok, chain: c, seals, anchors, attestation };
  }

  const WBVerify = { chain, seal, parseSeal, serverSigned, capsule, derToRaw };
  if (typeof module === 'object' && module.exports) module.exports = WBVerify; else root.WBVerify = WBVerify;
})(typeof self !== 'undefined' ? self : globalThis);
