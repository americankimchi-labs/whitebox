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
    return WBCodec.keyring({ roots: await ownerRoots(me, device) });
  }

  /** The car's root keys this phone holds for [device] (epoch → key), opened here; {} if none. */
  async function ownerRoots(me, device) {
    const key = me && await phoneKey('owner');
    const dev = me && key && key.publicKey === me.key ? me.devices.find((d) => d.id === device) : null;
    return dev ? unwrapAll(dev.wraps, 'owner') : {};
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
  /**
   * When a drive was actually driven: from the first shift out of P to the last shift back into P (or its end), read
   * from its gear records with this phone's keys. Null when it never left P (the car only sat with the power on).
   * A drive whose gears this phone cannot open counts whole.
   */
  async function driven(drive, ring) {
    const gears = [];
    for (const g of drive.gears || []) {
      const e = await WBCodec.decode(g.entry, ring).catch(() => null);
      if (!e || e.locked) return { startMs: drive.startMs, endMs: drive.endMs };
      gears.push({ at: e.at.wall, v: e.value });
    }
    const out = gears.find((g) => g.v !== 'PARK');
    if (!out) return gears.length ? null : { startMs: drive.startMs, endMs: drive.endMs };
    const back = gears.filter((g) => g.at > out.at && g.v === 'PARK').pop();
    return { startMs: out.at, endMs: back ? back.at : drive.endMs };
  }
  /** Distance driven by the car's odometer (Korean cars report km); null when it did not move or was not read. */
  async function odometerKm(drive, ring) {
    if (!drive.odometer || drive.odometer.length < 2) return null;
    const [a, b] = await Promise.all(drive.odometer.map((o) => WBCodec.decode(o.entry, ring).catch(() => null)));
    return a && b && !a.locked && !b.locked && b.value > a.value ? b.value - a.value : null;
  }
  /** A recent moment as people say it: "오늘 오후 10:46", "어제 오후 9:02", else "11월 4일 오후 10:46". */
  const recent = (ms, now = Date.now()) => {
    const d = new Date(ms), today = new Date(now);
    today.setHours(0, 0, 0, 0);
    const days = Math.floor((today.getTime() - new Date(d).setHours(0, 0, 0, 0)) / 86400000);
    const dayWord = days === 0 ? '오늘' : days === 1 ? '어제' : d.toLocaleDateString('ko-KR', { month: 'long', day: 'numeric' });
    return `${dayWord} ${clock(ms)}`;
  };
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  /** `#d=…&s=…&id=…` → {d, s, id}: page parameters travel in the fragment, which never reaches a server log. */
  const params = () => Object.fromEntries(new URLSearchParams(location.hash.slice(1)));
  /** Opens the place in Kakao Map, only when the person taps it (until then the position stays on this phone). */
  const mapLink = (lat, lon) => `https://map.kakao.com/link/map/${encodeURIComponent('기록 위치')},${lat.toFixed(6)},${lon.toFixed(6)}`;
  const secure = () => window.isSecureContext && !!crypto.subtle;
  const TIER = { FREE: '무료', PLUS: '플러스', PRO: '프로' };
  /** One button per plan above the owner's ("플러스로 업그레이드", "프로로 업그레이드"); none on Pro. */
  const upgrades = (tiers, href = '/me#plan') => (tiers || []).map((t) => `<a class="button-like secondary" href="${href}">${TIER[t]}로 업그레이드</a>`).join('');

  /** "a · b · c" with each part kept whole: a narrow screen wraps between parts, never inside one. */
  const parts = (...xs) => xs.filter(Boolean).map((x) => `<span class="nw">${x}</span>`).join(' · ');

  // Each sentence of a paragraph starts on its own line, as on the car: a sentence never begins at the end of
  // another's line. Only a stop followed by a space and a word ends a sentence (0.3초 and 1.5 km stay whole). Logs and
  // technical blocks keep their text as it is.
  const SENTENCE = /([.?!])[ \t]+(?=[^\s\d])/;
  // A sentence that ends a text node followed by more text (the next sentence in its own element) breaks there too.
  const TAIL = /[.?!][ \t]+$/;
  const SKIP = 'script,style,textarea,input,.log,.tech,code,pre,button,.nw,.badge,svg';
  const tail = (n) => TAIL.test(n.nodeValue) && n.nextSibling && n.nextSibling.nodeName !== 'BR';
  function bySentence(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => ((SENTENCE.test(n.nodeValue) || tail(n)) && !n.parentElement?.closest(SKIP) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT),
    });
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    for (const n of nodes) {
      const pieces = n.nodeValue.split(new RegExp(SENTENCE.source + '|$')).filter((x) => x !== undefined);
      const frag = document.createDocumentFragment();
      let text = '';
      for (let i = 0; i < pieces.length; i++) {
        text += pieces[i];
        if (/^[.?!]$/.test(pieces[i]) && i < pieces.length - 1) { frag.append(text, document.createElement('br')); text = ''; }
      }
      const ends = tail(n);
      frag.append(ends ? text.replace(/[ \t]+$/, '') : text);
      if (ends) frag.append(document.createElement('br'));
      n.replaceWith(frag);
    }
  }
  if (typeof document !== 'undefined' && typeof MutationObserver !== 'undefined') {
    const start = () => {
      bySentence(document.body);
      new MutationObserver((ms) => ms.forEach((m) => m.addedNodes.forEach((n) => (n.nodeType === 1 || n.nodeType === 3) && n.parentNode && bySentence(n.nodeType === 1 ? n : n.parentNode)))).observe(document.body, { childList: true, subtree: true });
    };
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
  }

  return { parts, newPhoneKey, phoneKey, unwrap, unwrapAll, ownerKeys, ownerRoots, claimMac, checkCode, post, get, $, show, say, ago, when, recent, driven, odometerKm, day, clock, esc, params, mapLink, secure, b64, unb64, TIER, upgrades };
})();
