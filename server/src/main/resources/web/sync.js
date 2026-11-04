// Dashcam sync on the phone: the driver picks their dashcam's video; it never leaves the phone. The car plays a short
// inaudible sweep (20–22 kHz) every few seconds and records the instant it played; finding that sweep in the video's
// sound pins the video to the record within a millisecond. Without it (a dashcam whose microphone or file cannot
// carry that band), the file's own clock or name places it to about a second, and the driver can nudge it by eye.
// The view stacks like a video editor: the video on top, the signal lanes below on one playhead.
(function (root) {
  const LOW_HZ = 20000, HIGH_HZ = 22000, CHIRP_MS = 100, FADE_MS = 15, AMPLITUDE = 0.3;
  const RATE = 48000;

  /** The sweep, generated exactly as the car generates it. */
  function chirp(sampleRate) {
    const n = Math.floor(sampleRate * CHIRP_MS / 1000);
    const fade = Math.floor(sampleRate * FADE_MS / 1000);
    const k = (HIGH_HZ - LOW_HZ) / (CHIRP_MS / 1000);
    const out = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      const t = i / sampleRate;
      const env = i < fade ? 0.5 * (1 - Math.cos(Math.PI * i / fade)) : i >= n - fade ? 0.5 * (1 - Math.cos(Math.PI * (n - 1 - i) / fade)) : 1;
      out[i] = AMPLITUDE * env * Math.sin(2 * Math.PI * (LOW_HZ * t + k * t * t / 2));
    }
    return out;
  }

  // In-place radix-2 FFT over separate real and imaginary arrays (length a power of two).
  function fft(re, im, inverse) {
    const n = re.length;
    for (let i = 1, j = 0; i < n; i++) {
      let bit = n >> 1;
      for (; j & bit; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) { [re[i], re[j]] = [re[j], re[i]]; [im[i], im[j]] = [im[j], im[i]]; }
    }
    for (let len = 2; len <= n; len <<= 1) {
      const ang = (inverse ? 2 : -2) * Math.PI / len;
      const wr = Math.cos(ang), wi = Math.sin(ang);
      for (let i = 0; i < n; i += len) {
        let cr = 1, ci = 0;
        for (let j = 0; j < len / 2; j++) {
          const a = i + j, b = a + len / 2;
          const tr = re[b] * cr - im[b] * ci, ti = re[b] * ci + im[b] * cr;
          re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
          const nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
        }
      }
    }
    if (inverse) for (let i = 0; i < n; i++) { re[i] /= n; im[i] /= n; }
  }

  /**
   * Every sweep in [audio] (mono, [sampleRate]) as {atSeconds, strength}: matched filter by FFT in blocks (a phone can
   * hold a minute of audio this way), normalised by the local energy so loud cabin noise does not pass for a sweep.
   * Sweeps closer than a second count once. [minStrength] is the normalised correlation needed (0–1).
   */
  function findAll(audio, sampleRate, { minStrength = 0.35 } = {}) {
    if (sampleRate < 2 * HIGH_HZ) return []; // the file cannot carry the band
    const tpl = chirp(sampleRate);
    const m = tpl.length;
    const block = 1 << 16;
    const step = block - m;
    const tRe = new Float64Array(block), tIm = new Float64Array(block);
    tpl.forEach((v, i) => { tRe[i] = v; });
    fft(tRe, tIm, false);
    let tplEnergy = 0;
    for (const v of tpl) tplEnergy += v * v;
    const tplNorm = Math.sqrt(tplEnergy);
    // Running energy of the audio over a template-long window, for normalisation.
    const prefix = new Float64Array(audio.length + 1);
    for (let i = 0; i < audio.length; i++) prefix[i + 1] = prefix[i] + audio[i] * audio[i];
    const hits = [];
    for (let start = 0; start + m <= audio.length; start += step) {
      const re = new Float64Array(block), im = new Float64Array(block);
      for (let i = 0; i < block && start + i < audio.length; i++) re[i] = audio[start + i];
      fft(re, im, false);
      for (let i = 0; i < block; i++) { // multiply by the template's conjugate spectrum: correlation
        const r = re[i] * tRe[i] + im[i] * tIm[i];
        const q = im[i] * tRe[i] - re[i] * tIm[i];
        re[i] = r; im[i] = q;
      }
      fft(re, im, true);
      for (let lag = 0; lag < step && start + lag + m <= audio.length; lag++) {
        const energy = prefix[start + lag + m] - prefix[start + lag];
        if (energy <= 0) continue;
        const s = re[lag] / (tplNorm * Math.sqrt(energy));
        if (s >= minStrength) hits.push({ at: start + lag, strength: s });
      }
    }
    const out = [];
    for (const h of hits.sort((a, b) => b.strength - a.strength)) {
      if (out.every((o) => Math.abs(o.at - h.at) > sampleRate)) out.push(h);
    }
    return out.sort((a, b) => a.at - b.at).map((h) => ({ atSeconds: h.at / sampleRate, strength: h.strength }));
  }

  /** The strongest sweep, or null. */
  function find(audio, sampleRate, opts) {
    const all = findAll(audio, sampleRate, opts);
    return all.length ? all.reduce((a, b) => (b.strength > a.strength ? b : a)) : null;
  }

  /**
   * When the video starts, as the dashcam knows it (to about a second): the MP4 header's creation time, or a time in
   * the file name (20261031_153012, 2026-10-31-15h30m12s …, Korean dashcams' local time). Null if neither.
   */
  function videoStartGuess(name, bytes) {
    const m = /(20\d\d)[-_.]?(\d\d)[-_.]?(\d\d)[-_.T ]?(\d\d)[-_.:h]?(\d\d)[-_.:m]?(\d\d)/.exec(name || '');
    if (m) {
      const [, y, mo, d, h, mi, s] = m.map(Number);
      const local = new Date(y, mo - 1, d, h, mi, s).getTime();
      if (!Number.isNaN(local)) return { wall: local, source: 'name' };
    }
    if (bytes) {
      // The mvhd box: version, flags, then creation time in seconds since 1904-01-01 UTC.
      const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
      for (let i = 4; i + 16 < bytes.length; i++) {
        if (bytes[i] === 0x6d && bytes[i + 1] === 0x76 && bytes[i + 2] === 0x68 && bytes[i + 3] === 0x64) {
          const version = bytes[i + 4];
          const secs = version === 1 ? Number(view.getBigUint64(i + 8)) : view.getUint32(i + 8);
          const wall = (secs - 2082844800) * 1000;
          if (secs > 0 && wall > Date.UTC(2015, 0, 1)) return { wall, source: 'header' };
          break;
        }
      }
    }
    return null;
  }

  /**
   * How far the file's own clock may be from where a sweep places the video and still pick that sweep: just under
   * half the car's sweep period (15 s). Sweeps repeat, so a clock off by more could pick the wrong one, and a video of
   * another moment would snap onto whichever sweep of this record is nearest.
   */
  const CLOCK_TRUST_MS = 7000;

  /**
   * The video's offset from the record: [found] sweeps in the video (seconds from its start), [beacons] the car's
   * recorded playback instants within this record (wall ms), [guessWall] the file's own start time (or null),
   * [span] the record's time span {fromWall, toWall} and [durationMs] the video's length (both optional).
   * With a sweep, the beacon nearest to where the clock puts it wins, if within [CLOCK_TRUST_MS]; otherwise the clock
   * places it, unless the video then misses the record altogether ('elsewhere': a video of another moment, or a
   * clock far off — to be placed by hand). Returns {startWall, how: 'chirp'|'clock'|'elsewhere'|'none', error}.
   */
  function align(found, beacons, guessWall, { span = null, durationMs = 0 } = {}) {
    if (found.length && beacons.length) {
      let best = null;
      for (const f of found) {
        for (const b of beacons) {
          const startWall = b - f.atSeconds * 1000;
          const miss = guessWall == null ? 0 : Math.abs(startWall - guessWall);
          if (!best || miss < best.miss || (miss === best.miss && f.strength > best.strength)) best = { startWall, miss, strength: f.strength };
        }
      }
      // Without the file's clock, one sweep alone could be any of them: only certain if just one was played.
      if ((guessWall != null && best.miss <= CLOCK_TRUST_MS) || (guessWall == null && beacons.length === 1)) return { startWall: best.startWall, how: 'chirp', error: 1 };
    }
    if (guessWall != null) {
      const misses = span && (guessWall + durationMs < span.fromWall || guessWall > span.toWall);
      return misses ? { startWall: null, how: 'elsewhere', error: null } : { startWall: guessWall, how: 'clock', error: 1000 };
    }
    return { startWall: null, how: 'none', error: null };
  }

  /** The video's sound as mono samples at 48 kHz (decoding resamples), or null if the browser cannot read it. */
  async function audioOf(file) {
    const Ctx = root.OfflineAudioContext || root.webkitOfflineAudioContext;
    if (!Ctx) return null;
    try {
      const buf = await file.arrayBuffer();
      // Decoding takes the buffer over (detaches it): keep the header (for the MP4 clock) first.
      const bytes = new Uint8Array(buf.slice(0, Math.min(buf.byteLength, 4 << 20)));
      const ctx = new Ctx(1, RATE, RATE);
      const decoded = await ctx.decodeAudioData(buf);
      return { samples: decoded.getChannelData(0), rate: decoded.sampleRate, bytes };
    } catch (e) { return null; }
  }

  /**
   * The editor-style view in [el]: video on top, lanes below. [timeline] is WBPacket.build over the whole capsule;
   * [causeWall] its detection instant (wall ms); [beacons] the car's sweep times (wall ms). [onSynced] hears each
   * picked file once it is placed ({file, offsetMs: its first frame from the detection, how}); [pickNote] replaces the
   * line under the button. Returns {open(blob, name, offsetMs)} for a clip saved earlier.
   */
  /**
   * The lanes' span for a clip, as Packet.build's pre/post around the cause: from the clip's first frame to its last
   * (a full-screen view follows the clip, so its playhead crosses the whole width while it plays).
   */
  function clipSpan(startWall, durationMs, causeWall) {
    const pre = causeWall - startWall, post = startWall + durationMs - causeWall;
    return pre + post >= 1000 ? { pre, post } : { pre: pre + 500, post: post + 500 };
  }

  const FULL_CSS = `
    .sync-stage.full{position:fixed;inset:0;z-index:1000;margin:0;background:var(--bg,#fff);display:flex;flex-direction:column;overflow:hidden}
    .sync-stage.full video{border-radius:0!important;flex:0 0 auto;max-height:50vh;object-fit:contain}
    .sync-stage.full .sync-lanes{flex:1 1 auto;margin:12px 12px 0;min-height:0}
    .sync-stage.full .sync-status,.sync-stage.full .sync-nudge,.sync-stage.full .sync-enter{display:none}
    .sync-exit{display:none}
    .sync-stage.full .sync-exit{display:block;margin:10px 12px 14px}
    @media (orientation: landscape){.sync-stage.full video{max-height:58vh}.sync-stage.full .sync-exit{margin:6px 12px 8px}}`;

  function mount(el, { timeline, causeWall, beacons, Packet, onSynced = null, pickNote = null, span = null }) {
    if (typeof document !== 'undefined' && !document.getElementById('sync-full-css')) {
      const st = document.createElement('style'); st.id = 'sync-full-css'; st.textContent = FULL_CSS; document.head.appendChild(st);
    }
    el.innerHTML = `
      <div class="sync-pick"><label class="button-like">블랙박스 영상 선택<input type="file" accept="video/*" hidden></label>
        <p class="msg sync-note"></p></div>
      <div class="sync-stage" hidden>
        <video playsinline controls preload="metadata" style="width:100%;border-radius:8px;background:#000"></video>
        <div class="sync-lanes" style="position:relative;touch-action:none"><div class="sync-svg"></div>
          <div class="sync-head" style="position:absolute;top:0;bottom:0;width:3px;margin-left:-1px;border-radius:2px;background:var(--accent,#6750a4);pointer-events:none"></div></div>
        <button class="wide secondary sync-enter">전체 화면</button>
        <button class="wide secondary sync-exit">← 돌아가기</button>
        <p class="sync-status msg"></p>
        <div class="sync-nudge"><span class="msg">영상과 그래프가 어긋나 보이면:</span>
          <button class="plain" data-nudge="-1000">−1초</button><button class="plain" data-nudge="-100">−0.1초</button>
          <button class="plain" data-nudge="100">+0.1초</button><button class="plain" data-nudge="1000">+1초</button></div>
      </div>`;
    el.querySelector('.sync-note').textContent = pickNote || '영상은 이 휴대폰 안에서만 열리고, 어디에도 올라가지 않습니다.';
    const input = el.querySelector('input'), stage = el.querySelector('.sync-stage'), video = el.querySelector('video');
    const lanes = el.querySelector('.sync-lanes'), head = el.querySelector('.sync-head'), status = el.querySelector('.sync-status');
    const svgEl = el.querySelector('.sync-svg');
    let tl = timeline; // the lanes shown: the whole capsule, or the clip's own span full screen
    let opts = {};     // how they are drawn (full screen: taller lanes, or a wider scale when the view is short)
    svgEl.innerHTML = Packet.svg(tl);
    let startWall = null; // the video's first frame on the car's clock
    const msAt = () => (startWall == null ? null : startWall + video.currentTime * 1000 - causeWall);
    const place = () => {
      const ms = msAt();
      head.hidden = ms == null || ms < -tl.pre || ms > tl.post;
      if (!head.hidden) head.style.left = `${(Packet.xFraction(tl, ms, opts) * 100).toFixed(2)}%`;
    };
    // Full screen: the video on top, the lanes of the clip's own span below, filling what is left.
    const draw = () => {
      if (!stage.classList.contains('full')) { opts = {}; svgEl.innerHTML = Packet.svg(tl); return place(); }
      const w = lanes.clientWidth || 1, h = lanes.clientHeight || 1;
      // Fill the height with taller lanes; a short, wide view (a phone held sideways) widens the scale instead.
      let lane = ((h * 300) / w - 51) / 5, width = 300;
      if (lane < 18) { lane = 18; width = Math.round(((51 + 5 * lane) * w) / h); }
      opts = { lane: Math.min(140, lane), width };
      svgEl.innerHTML = Packet.svg(tl, opts);
      place();
    };
    // The clip's own span once its length is known (a tap before the metadata loads follows as soon as it does).
    const clipLanes = () => {
      if (!stage.classList.contains('full') || !span || startWall == null || !(video.duration > 0)) return;
      const s = clipSpan(startWall, video.duration * 1000, causeWall);
      tl = span(s.pre, s.post);
      draw();
    };
    video.addEventListener('loadedmetadata', clipLanes);
    // Full screen lives on the page itself: a card's own layout would otherwise hold a fixed view to its width.
    const home = typeof document !== 'undefined' ? document.createComment('sync stage') : null;
    const enterFull = () => {
      stage.before(home);
      document.body.appendChild(stage);
      stage.classList.add('full');
      clipLanes();
      // Native full screen where the browser allows it (a phone hides its own bars and turns sideways, where it can);
      // the layout is the same without.
      if (stage.requestFullscreen) {
        stage.requestFullscreen().then(() => screen.orientation && screen.orientation.lock && screen.orientation.lock('landscape')).catch(() => {});
      }
      requestAnimationFrame(draw);
    };
    const exitFull = () => {
      if (!stage.classList.contains('full')) return;
      stage.classList.remove('full');
      home.replaceWith(stage);
      tl = timeline;
      if (typeof screen !== 'undefined' && screen.orientation && screen.orientation.unlock) try { screen.orientation.unlock(); } catch (e) { /* not locked */ }
      if (typeof document !== 'undefined' && document.fullscreenElement) document.exitFullscreen().catch(() => {});
      draw();
    };
    el.querySelector('.sync-enter').onclick = enterFull;
    el.querySelector('.sync-exit').onclick = exitFull;
    if (typeof document !== 'undefined') document.addEventListener('fullscreenchange', () => { if (!document.fullscreenElement) exitFull(); });
    if (typeof window !== 'undefined') window.addEventListener('resize', () => { if (stage.classList.contains('full')) draw(); });
    video.addEventListener('timeupdate', place);
    video.addEventListener('seeked', place);
    const seekTo = (clientX) => {
      if (startWall == null) return;
      const r = lanes.getBoundingClientRect();
      const ms = Packet.msAtFraction(tl, (clientX - r.left) / r.width, opts);
      const t = (causeWall + ms - startWall) / 1000;
      if (t >= 0 && (!video.duration || t <= video.duration)) video.currentTime = t;
    };
    lanes.addEventListener('pointerdown', (e) => { seekTo(e.clientX); lanes.setPointerCapture(e.pointerId); });
    lanes.addEventListener('pointermove', (e) => { if (e.buttons) seekTo(e.clientX); });
    el.querySelectorAll('[data-nudge]').forEach((b) => b.onclick = () => {
      if (startWall == null) startWall = causeWall - video.currentTime * 1000;
      startWall -= Number(b.dataset.nudge);
      status.textContent = '직접 맞춘 위치입니다. 저장되지 않으며, 증거 기록은 바뀌지 않습니다.';
      place();
    });
    input.onchange = async () => {
      const file = input.files[0];
      if (!file) return;
      stage.hidden = false;
      video.src = URL.createObjectURL(file);
      status.textContent = '영상의 소리에서 동기화 신호음을 찾는 중…';
      const audio = await audioOf(file);
      const guess = videoStartGuess(file.name, audio && audio.bytes);
      const found = audio ? findAll(audio.samples, audio.rate) : [];
      const span = { fromWall: causeWall - timeline.pre, toWall: causeWall + timeline.post };
      const a = align(found, beacons, guess && guess.wall, { span, durationMs: audio ? (audio.samples.length / audio.rate) * 1000 : 0 });
      startWall = a.startWall;
      status.textContent = a.how === 'chirp' ? '동기화 신호음으로 정확히 맞췄습니다 (오차 약 0.001초).'
        : a.how === 'clock' ? `${guess.source === 'name' ? '파일 이름' : '영상 정보'}의 시각으로 맞췄습니다 (오차 약 1초). 아래 버튼으로 다듬을 수 있습니다.`
        : a.how === 'elsewhere' ? `${guess.source === 'name' ? '파일 이름' : '영상 정보'}의 시각(${new Date(guess.wall).toLocaleString('ko-KR')})이 이 기록과 맞지 않습니다. 이 순간을 찍은 영상인지 확인하거나, 영상을 사고 순간에 멈추고 아래 버튼으로 맞춰 주세요.`
        : '영상에서 시각을 찾지 못했습니다. 영상을 사고 순간에 멈추고 아래 버튼으로 맞춰 주세요.';
      stage.dataset.startWall = startWall ?? '';
      toCause();
      if (onSynced) onSynced({ file, offsetMs: startWall == null ? null : startWall - causeWall, how: a.how, current: () => (startWall == null ? null : startWall - causeWall) });
    };
    // Open at the moment of detection (the metadata may have loaded while the sound was being searched).
    function toCause() {
      if (startWall == null) return;
      const go = () => { const t = (causeWall - startWall) / 1000; if (t > 0 && t < video.duration) video.currentTime = t; place(); };
      if (video.readyState >= 1) go(); else video.addEventListener('loadedmetadata', go, { once: true });
    }
    return {
      /** A clip saved earlier, already placed: no search, the stored position. */
      open(blob, name, offsetMs) {
        stage.hidden = false;
        video.src = URL.createObjectURL(blob);
        startWall = causeWall + offsetMs;
        status.textContent = `저장된 영상입니다 (${name}). 저장할 때 맞춘 위치 그대로 보여 줍니다.`;
        toCause();
      },
    };
  }

  const WBSync = { chirp, findAll, find, videoStartGuess, align, audioOf, mount, fft, clipSpan };
  if (typeof module === 'object' && module.exports) module.exports = WBSync; else root.WBSync = WBSync;
})(typeof self !== 'undefined' ? self : globalThis);
