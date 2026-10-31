// White Box phone pages: keys and decryption happen here, in the phone's browser. The server sees public keys,
// wrapped keys and encrypted records (and, for a severe crash only, the minute keys the car handed it for the
// family). Constructions match the car's (P-256 ECDH + HKDF-SHA256 + AES-256-GCM).
const WB = (() => {
  const enc = new TextEncoder();
  const dec = new TextDecoder();
  const subtle = crypto.subtle;

  const b64 = (bytes) => btoa(String.fromCharCode(...new Uint8Array(bytes)));
  const unb64 = (s) => Uint8Array.from(atob(s.replace(/-/g, '+').replace(/_/g, '/')), (c) => c.charCodeAt(0));

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

  /** Makes a new key pair for this phone as the car's owner and keeps it; returns the public key (SPKI, base64). */
  async function newPhoneKey(role) {
    const pair = await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    const pub = b64(await subtle.exportKey('spki', pair.publicKey));
    await store('ecdh-' + role, { privateKey: pair.privateKey, publicKey: pub });
    return pub;
  }
  async function phoneKey(role) { return stored('ecdh-' + role); }

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

  /** Opens every car key in [wraps] ({epoch, sealed}) with this phone's [role] key: epoch → key bytes. */
  async function unwrapAll(wraps, role) {
    const roots = {};
    for (const w of wraps || []) { try { roots[w.epoch] = await unwrap(w.sealed, role); } catch (e) { /* not for this key */ } }
    return roots;
  }

  /**
   * The keys this phone holds for [device] (its owner's wraps, opened with this phone's key) as a WBCodec keyring;
   * empty if this phone has none (a new phone, or the family's).
   */
  async function ownerKeys(me, device) {
    const key = me && await phoneKey('owner');
    const dev = me && key && key.publicKey === me.key ? me.devices.find((d) => d.id === device) : null;
    return WBCodec.keyring({ roots: dev ? await unwrapAll(dev.wraps, 'owner') : {} });
  }

  /** Proof for the car that this phone saw its screen: HMAC-SHA256 with the QR secret. */
  async function claimMac(secretB64url, code, keyB64) {
    const k = await subtle.importKey('raw', unb64(secretB64url), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
    return b64(await subtle.sign('HMAC', k, enc.encode(`claim|${code}|${keyB64}`)));
  }

  /** A short fingerprint of a key, for the "자세히" view. */
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
  const $ = (id) => document.getElementById(id);
  const show = (id, on = true) => { $(id).hidden = !on; };
  const say = (id, text, bad = false) => { const e = $(id); e.textContent = text; e.className = bad ? 'msg bad' : 'msg'; };
  const ago = (ms) => ms == null ? '' : ms < 60000 ? `${Math.round(ms / 1000)}초 전` : `${Math.round(ms / 60000)}분 전`;
  const when = (ms) => new Date(ms).toLocaleString('ko-KR');
  const day = (ms) => new Date(ms).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric', weekday: 'short' });
  const clock = (ms) => new Date(ms).toLocaleTimeString('ko-KR', { hour: 'numeric', minute: '2-digit' });
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  /** `#d=…&s=…&id=…` → {d, s, id}: page parameters travel in the fragment, which never reaches a server log. */
  const params = () => Object.fromEntries(new URLSearchParams(location.hash.slice(1)));
  /** Opens the place in Kakao Map, only when the person taps it (until then the position stays on this phone). */
  const mapLink = (lat, lon) => `https://map.kakao.com/link/map/${encodeURIComponent('기록 위치')},${lat.toFixed(6)},${lon.toFixed(6)}`;
  const secure = () => window.isSecureContext && !!crypto.subtle;

  return { newPhoneKey, phoneKey, unwrap, unwrapAll, ownerKeys, claimMac, checkCode, post, get, $, show, say, ago, when, day, clock, esc, params, mapLink, secure, b64, unb64 };
})();
