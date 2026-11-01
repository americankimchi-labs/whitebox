// A saved dashcam clip, encrypted on the phone before it leaves. Its key comes from the capsule's own first minute
// key (HKDF-SHA256, info "whitebox-clip-v1|<clip id>"), so whoever may open the capsule — its owner, an emergency
// contact for a crash, whoever holds its share link — may watch its clip, and nobody else, the server included.
// The file is cut into 4 MiB pieces, each sealed on its own (12-byte random IV, then AES-GCM ciphertext and tag), so
// a phone never holds a whole long clip twice in memory.
(function (root) {
  const subtle = (root.crypto || require('crypto').webcrypto).subtle;
  const getRandom = (n) => (root.crypto || require('crypto').webcrypto).getRandomValues(new Uint8Array(n));
  const PIECE = 4 << 20;
  const SEALED = PIECE + 12 + 16;

  async function key(minuteKey, clipId) {
    const base = await subtle.importKey('raw', minuteKey, 'HKDF', false, ['deriveKey']);
    return subtle.deriveKey(
      { name: 'HKDF', hash: 'SHA-256', salt: new Uint8Array(0), info: new TextEncoder().encode('whitebox-clip-v1|' + clipId) },
      base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'],
    );
  }

  /** A new clip id: random, URL-safe, part of the key derivation. */
  function newId() {
    let s = '';
    for (const b of getRandom(18)) s += 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'[b & 63];
    return s;
  }

  /** [blob] encrypted piece by piece; [progress] gets 0…1. */
  async function encrypt(k, blob, progress = () => {}) {
    const parts = [];
    for (let at = 0; at < blob.size; at += PIECE) {
      const iv = getRandom(12);
      const plain = new Uint8Array(await blob.slice(at, at + PIECE).arrayBuffer());
      parts.push(iv, new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv }, k, plain)));
      progress(Math.min(1, (at + PIECE) / blob.size));
    }
    return new Blob(parts);
  }

  /** The clip back from its encrypted bytes (an ArrayBuffer); throws if any piece was altered or the key is wrong. */
  async function decrypt(k, buf, type = 'video/mp4') {
    const bytes = new Uint8Array(buf);
    const parts = [];
    for (let at = 0; at < bytes.length; at += SEALED) {
      const piece = bytes.subarray(at, Math.min(bytes.length, at + SEALED));
      parts.push(new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: piece.subarray(0, 12) }, k, piece.subarray(12))));
    }
    return new Blob(parts, { type });
  }

  const WBClip = { key, newId, encrypt, decrypt, PIECE };
  if (typeof module === 'object' && module.exports) module.exports = WBClip; else root.WBClip = WBClip;
})(typeof self !== 'undefined' ? self : globalThis);
