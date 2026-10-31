// White Box phone pages: keys and decryption happen here, in the phone's browser. The server only ever sees public
// keys, wrapped keys and encrypted records. Constructions match the car's (P-256 ECDH + HKDF-SHA256 + AES-256-GCM).
const WB = (() => {
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  const subtle = crypto.subtle;

  const b64 = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)));
  const unb64 = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));
  const concat = (a, b) => { const out = new Uint8Array(a.length + b.length); out.set(a); out.set(b, a.length); return out; };

  // ---- this phone's key, kept in the browser (the private half cannot be exported) ----
  function db() {
    return new Promise((resolve, reject) => {
      const r = indexedDB.open('whitebox', 1);
      r.onupgradeneeded = () => r.result.createObjectStore('keys');
      r.onsuccess = () => resolve(r.result);
      r.onerror = () => reject(r.error);
    });
  }
  async function stored(name) {
    const d = await db();
    return new Promise((resolve) => {
      const r = d.transaction('keys').objectStore('keys').get(name);
      r.onsuccess = () => resolve(r.result || null);
      r.onerror = () => resolve(null);
    });
  }
  async function store(name, value) {
    const d = await db();
    return new Promise((resolve, reject) => {
      const t = d.transaction('keys', 'readwrite');
      t.objectStore('keys').put(value, name);
      t.oncomplete = () => resolve();
      t.onerror = () => reject(t.error);
    });
  }

  // One key per role (owner / contact / recovering phone): on a real phone only one is used; on one test computer
  // all three can live side by side in the same browser.
  /** Makes a new key pair for this phone in [role] and keeps it; returns the public key (SPKI, base64). */
  async function newPhoneKey(role) {
    const pair = await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    const pub = b64(await subtle.exportKey('spki', pair.publicKey));
    await store('ecdh-' + role, { privateKey: pair.privateKey, publicKey: pub });
    return pub;
  }
  async function phoneKey(role) { return stored('ecdh-' + role); }
  async function moveKey(from, to) { const k = await stored('ecdh-' + from); if (k) await store('ecdh-' + to, k); }

  async function aesFrom(own, otherSpki, info) {
    const other = await subtle.importKey('spki', otherSpki, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
    const shared = await subtle.deriveBits({ name: 'ECDH', public: other }, own, 256);
    const hk = await subtle.importKey('raw', shared, 'HKDF', false, ['deriveBits']);
    const bits = await subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: enc.encode(info) }, hk, 256);
    return subtle.importKey('raw', bits, 'AES-GCM', false, ['encrypt', 'decrypt']);
  }

  /** Opens a sealed box addressed to this phone's [role] key (the car's root key). */
  async function unwrap(sealed, role) {
    const k = await phoneKey(role);
    if (!k) throw new Error('no key on this phone');
    const [v, eph, body] = sealed.split('.');
    if (v !== 'e1') throw new Error('not a sealed box');
    const key = await aesFrom(k.privateKey, unb64(eph), 'whitebox-wrap-v1');
    const bytes = unb64(body);
    return new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: bytes.slice(0, 12) }, key, bytes.slice(12)));
  }

  /** Seals [bytes] for another phone's public key (wrapping the root key for a contact or a recovered phone). */
  async function seal(recipientSpkiB64, bytes) {
    const eph = await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
    const key = await aesFrom(eph.privateKey, unb64(recipientSpkiB64), 'whitebox-wrap-v1');
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ct = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv }, key, bytes));
    return 'e1.' + b64(await subtle.exportKey('spki', eph.publicKey)) + '.' + b64(concat(iv, ct));
  }

  const isSealed = (s) => s.startsWith('v1.') || s.startsWith('v2.');
  // `v2.<epoch>.<body>` names the car key it was made with; `v1.<body>` is epoch 0.
  const epochOf = (s) => s.startsWith('v2.') ? Number(s.split('.')[1]) : 0;
  const bodyOf = (s) => s.startsWith('v2.') ? s.split('.').slice(2).join('.') : s.slice(3);

  /** Opens every car key in [wraps] ({epoch, sealed}) with this phone's [role] key: epoch → key bytes. */
  async function unwrapAll(wraps, role) {
    const roots = {};
    for (const w of wraps || []) { try { roots[w.epoch] = await unwrap(w.sealed, role); } catch (e) { /* not for this key */ } }
    return roots;
  }

  /** Decrypts one chain record with the car key of its epoch; null if this phone holds no such key. */
  async function openRecord(roots, entry) {
    const f = entry.split('|');
    let sealed, aad;
    if (f[0] === 'S' && isSealed(f[2])) { sealed = f[2]; aad = `S|${f[1]}|${f[3]}`; }
    else if (f[0] === 'L' && isSealed(f[1])) { sealed = f[1]; aad = `L|${f[2]}`; }
    else return null;
    const root = roots[epochOf(sealed)];
    if (!root) return null;
    const key = await subtle.importKey('raw', root, 'AES-GCM', false, ['decrypt']);
    const bytes = unb64(bodyOf(sealed));
    const plain = dec.decode(await subtle.decrypt({ name: 'AES-GCM', iv: bytes.slice(0, 12), additionalData: enc.encode(aad) }, key, bytes.slice(12)));
    const time = Number((f[0] === 'S' ? f[3] : f[2]).split(';')[1]);
    return { type: f[0], kind: f[0] === 'S' ? f[1] : 'LOCATION', value: plain, time };
  }

  /** Proof for the car that this phone saw its screen: HMAC-SHA256 with the QR secret. */
  async function claimMac(secretB64url, code, keyB64) {
    const k = await subtle.importKey('raw', unb64(secretB64url), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    return b64(await subtle.sign('HMAC', k, enc.encode(`claim|${code}|${keyB64}`)));
  }

  /** A short code two people read to each other to be sure a key is really the other phone's (not swapped). */
  async function checkCode(keyB64) {
    const h = new Uint8Array(await subtle.digest('SHA-256', unb64(keyB64)));
    const n = ((h[0] << 24) | (h[1] << 16) | (h[2] << 8) | h[3]) >>> 0;
    const s = String(n % 1000000).padStart(6, '0');
    return s.slice(0, 3) + ' ' + s.slice(3);
  }

  async function post(path, params) {
    const r = await fetch(path, { method: 'POST', body: new URLSearchParams(params), credentials: 'same-origin' });
    const j = await r.json().catch(() => ({ ok: false, error: '서버에 연결할 수 없습니다.' }));
    if (!r.ok || j.ok === false) throw new Error(j.error || '실패했습니다.');
    return j;
  }
  async function get(path) {
    const r = await fetch(path, { credentials: 'same-origin' });
    if (r.status === 401) return null;
    return r.json();
  }
  async function postPairs(path, pairs) {
    const body = new URLSearchParams();
    for (const [k, v] of pairs) body.append(k, v);
    const r = await fetch(path, { method: 'POST', body, credentials: 'same-origin' });
    const j = await r.json();
    if (!r.ok || j.ok === false) throw new Error(j.error || '실패했습니다.');
    return j;
  }

  const $ = (id) => document.getElementById(id);
  const show = (id, on = true) => { $(id).hidden = !on; };
  const say = (id, text, bad = false) => { const e = $(id); e.textContent = text; e.className = bad ? 'msg bad' : 'msg'; };
  const ago = (ms) => ms == null ? '' : ms < 60000 ? `${Math.round(ms / 1000)}초 전` : `${Math.round(ms / 60000)}분 전`;
  const when = (ms) => new Date(ms).toLocaleString('ko-KR');
  const secure = () => window.isSecureContext && !!crypto.subtle;

  return { newPhoneKey, phoneKey, moveKey, unwrap, unwrapAll, seal, openRecord, claimMac, checkCode, post, get, postPairs, $, show, say, ago, when, secure, b64, unb64 };
})();
