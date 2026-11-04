// The evidence packet on the phone: the same time axis the car's packet screen shows — speed, steering, brake, turn
// signal and gear lanes around the moment of detection, what the driver did "N s before", and the driving context —
// built from the sealed records alone. `build` follows the car's timeline construction step for step; the tests
// check it against timelines the car's own code produced.
(function (root) {
  const PRE_MS = 10000;
  /** The lanes' drawing size in viewBox units: narrow enough that the text stays legible on a phone. */
  const LAYOUT = { width: 300, label: 60 };
  const POST_MS = 5000;
  const FLASH_MAX_MS = 1500;
  const SUBSCRIBE_MS = 3000;

  /** A control's state at the cause and how long before it was set (null: held throughout the range). */
  function lastChange() {
    const c = { value: null, beforeMs: null };
    c.report = (ms, v, initial) => {
      if (v === c.value) return;
      c.beforeMs = c.value === null && initial ? null : -ms;
      c.value = v;
    };
    return c;
  }

  /** A step lane clipped to [pre, post], starting with the state carried in from before it. */
  function stepLane(pre, post) {
    let carried = null;
    const steps = [];
    return {
      add(ms, v) {
        if (ms < -pre) carried = v;
        else if (ms > post) { /* after the window */ }
        else if (steps.length && steps[steps.length - 1].value === v) { /* unchanged */ }
        else if (!steps.length && carried === v) { /* same as carried in */ }
        else steps.push({ atMs: ms, value: v });
      },
      steps: () => (carried === null ? steps : [{ atMs: -pre, value: carried }, ...steps]),
    };
  }

  function withContext(c, kind, v) {
    switch (kind) {
      case 'SEAT_BELT': return { ...c, seatBelts: { ...c.seatBelts, [v.seat]: v.buckled } };
      case 'SEAT_OCCUPANCY': return { ...c, occupancy: { ...c.occupancy, [v.seat]: v.occupied } };
      case 'DOOR': return { ...c, doors: { ...c.doors, [v.area]: v.open } };
      case 'WINDOW': return { ...c, windows: { ...c.windows, [v.area]: v.position } };
      case 'LIGHT': return { ...c, lamps: { ...c.lamps, [v.lamp]: v.on } };
      case 'WIPER': return { ...c, wipers: { ...c.wipers, [v.area]: v.mode } };
      case 'CRUISE_CONTROL': return { ...c, cruise: v };
      case 'CRUISE_TARGET_SPEED': return { ...c, cruiseTargetMps: v };
      case 'PARKING_BRAKE': return { ...c, parkingBrake: v };
      case 'ROAD_CONTEXT':
        // Handover mode's inputs (enforcement distance, route state), not context of the moment.
        if (v.type === 'SPEED_ENFORCEMENT' || v.type === 'ROUTE') return c;
        if (v.type === 'SPEED_LIMIT') { const n = parseInt(v.value, 10); return { ...c, speedLimitKmh: n > 0 ? n : null }; }
        if (v.value === 'NONE') return { ...c, roadAlerts: c.roadAlerts.filter((a) => a[0] !== v.type) };
        return { ...c, roadAlerts: [...c.roadAlerts, [v.type, v.value]] };
      default: return c;
    }
  }

  /**
   * [events]: the sealed range in chain order as {seq, e} (e from WBCodec.decode); [causeSeq]: the entry the trigger
   * points at. Times are ms relative to the cause (negative = before). [pre]/[post] widen the lanes (the dashcam view
   * shows the whole capsule); the packet uses the car's −10 s … +5 s.
   */
  function build(events, causeSeq, { pre = PRE_MS, post = POST_MS } = {}) {
    let cause = null;
    for (const ev of events) if (ev.seq <= causeSeq) cause = ev;
    cause = cause || events[0];
    const causeNs = cause.e.at.ns;
    const rel = (ev) => Math.trunc((ev.e.at.ns - causeNs) / 1e6);
    const inLanes = (ms) => ms >= -pre && ms <= post;

    const speed = [], steering = [], highBeam = [];
    const brake = stepLane(pre, post), turn = stepLane(pre, post), gear = stepLane(pre, post);
    const brakeChange = lastChange(), turnChange = lastChange(), gearChange = lastChange();
    let context = { seatBelts: {}, occupancy: {}, doors: {}, windows: {}, lamps: {}, wipers: {}, cruise: null, cruiseTargetMps: null, parkingBrake: null, speedLimitKmh: null, roadAlerts: [] };
    let startedMs = null;
    // The last continuous value before the lanes begin: it still held when they did.
    let speedBefore = null, steeringBefore = null;

    const take = (kind, v, ms, beforeCause, initial) => {
      switch (kind) {
        case 'SPEED': if (inLanes(ms)) speed.push({ atMs: ms, value: v * 3.6 }); else if (ms < -pre) speedBefore = v * 3.6; break;
        case 'STEERING_ANGLE': if (inLanes(ms)) steering.push({ atMs: ms, value: v }); else if (ms < -pre) steeringBefore = v; break;
        case 'BRAKE_PEDAL': brake.add(ms, v); if (beforeCause) brakeChange.report(ms, v, initial); break;
        case 'TURN_SIGNAL': turn.add(ms, v); if (beforeCause) turnChange.report(ms, v, initial); break;
        case 'GEAR': gear.add(ms, v); if (beforeCause) gearChange.report(ms, v, initial); break;
        default:
          if (beforeCause) {
            if (kind === 'LIGHT' && v.lamp === 'HIGH_BEAM' && (highBeam.length ? highBeam[highBeam.length - 1].value : null) !== v.on) highBeam.push({ atMs: ms, value: v.on });
            context = withContext(context, kind, v);
          }
      }
    };
    // The states the window started with come first (written when the capsule opened, they stand for the window's
    // start): held throughout unless the window changes them.
    for (const ev of events) if (ev.e.type === 'C' && !ev.e.locked) take(ev.e.kind, ev.e.value, -pre - 1, true, true);
    for (const ev of events) {
      const ms = rel(ev);
      if (ev.e.type === 'R' && ev.e.event === 'STARTED') startedMs = ms;
      if (ev.e.type !== 'S' || ev.e.locked) continue;
      take(ev.e.kind, ev.e.value, ms, ev.seq <= cause.seq, startedMs !== null && ms - startedMs <= SUBSCRIBE_MS);
    }

    const lines = [];
    if (brakeChange.value !== null) lines.push({ type: 'BRAKE', value: brakeChange.value, beforeMs: brakeChange.beforeMs });
    if (turnChange.value !== null) lines.push({ type: 'TURN', value: turnChange.value, beforeMs: turnChange.beforeMs });
    if (gearChange.value !== null) lines.push({ type: 'GEAR', value: gearChange.value, beforeMs: gearChange.beforeMs });
    let peak = null;
    for (const p of steering) if (p.atMs <= 0 && (peak === null || Math.abs(p.value) > Math.abs(peak.value))) peak = p;
    if (peak && peak.value !== 0) lines.push({ type: 'STEERING_PEAK', value: peak.value, beforeMs: -peak.atMs });
    let flash = null;
    for (let i = 0; i + 1 < highBeam.length; i++) {
      const on = highBeam[i], off = highBeam[i + 1];
      if (on.value && !off.value && off.atMs - on.atMs <= FLASH_MAX_MS && on.atMs >= -pre) flash = on;
    }
    if (flash) lines.push({ type: 'HIGH_BEAM_FLASH', beforeMs: -flash.atMs });

    const clamp = (x, lo, hi) => Math.min(hi, Math.max(lo, x));
    const endMs = clamp(rel(events[events.length - 1]), 0, post);
    // A continuous lane across the whole kept span: values are written when they change, so the one before the
    // lanes begin still held at −pre, and the last one holds to the end.
    const held = (points, before) => {
      const out = before !== null && (points.length ? points[0].atMs : Infinity) > -pre ? [{ atMs: -pre, value: before }] : [];
      out.push(...points);
      const last = out[out.length - 1];
      if (last && last.atMs < endMs) out.push({ atMs: endMs, value: last.value });
      return out;
    };
    return {
      speedKmh: held(speed, speedBefore), steering: held(steering, steeringBefore), brake: brake.steps(), turn: turn.steps(), gear: gear.steps(), lines, context,
      startMs: clamp(rel(events[0]), -pre, 0), endMs, pre, post,
      causeSeq: cause.seq, causeWall: cause.e.at.wall,
    };
  }

  // ---- words (the car's own) ----

  const TRIGGER = {
    AIRBAG: '에어백 전개', TEST_AIRBAG: '시험용 에어백 신호', DELTA_V: '충격 감지', SHORT_DELTA_V: '충격 감지', BRAKED_SPIKE: '제동 중 충격 감지',
    REAR_SHOVE: '후방 추돌 (정차·제동 중)', ALERT_RESOLVED: '급제동 후 정차', MANUAL: '음성으로 보관', HARD_BRAKE: '급제동',
    EVASIVE_STEER: '급핸들', BRAKE_SWERVE: '급제동하며 급핸들', SHIFT_TO_NEUTRAL: '주행 중 기어 중립(N)', BRAKE_SPEED_RISE: '브레이크를 밟았는데 속도 증가',
    BRAKE_NO_DECEL: '브레이크를 밟아도 속도가 안 줄어듦', BRAKE_PUMPING: '브레이크를 여러 번 밟음', PARKING_BRAKE_MOVING: '주행 중 주차 브레이크 작동', LURCH: '급출발',
    POST_EVENT_EXIT: '위험 순간 직후 하차', SERVER_SEAL: '충격 뒤 연결 끊김 (서버 봉인)', HANDOVER: '대리 운전 리포트',
  };
  const GEAR = { PARK: 'P', REVERSE: 'R', NEUTRAL: 'N', DRIVE: 'D', MANUAL: 'M', UNKNOWN: '?' };
  const TURN = { OFF: '꺼짐', LEFT: '좌측 점멸', RIGHT: '우측 점멸' };
  const AREA = { ROW_1_LEFT: '운전석', ROW_1_RIGHT: '조수석', ROW_2_LEFT: '뒷좌석 왼쪽', ROW_2_RIGHT: '뒷좌석 오른쪽', ROW_2_CENTER: '뒷좌석 가운데', HOOD: '후드', REAR: '트렁크', FRONT_WINDSHIELD: '앞', REAR_WINDSHIELD: '뒤' };
  const LAMP = { LOW_BEAM: '하향등', HIGH_BEAM: '상향등', TAIL_LAMP: '미등', HAZARD: '비상등' };
  const CRUISE = { ENABLED: '대기', ACTIVATED: '작동', USER_OVERRIDE: '운전자 개입', SUSPENDED: '일시 해제', FORCED_DEACTIVATION_WARNING: '강제 해제 경고', OTHER: '꺼짐', UNKNOWN: '꺼짐' };
  const wiper = (m) => m === 'OFF' ? '꺼짐' : m === 'AUTO' ? '자동' : (m === 'ON' || m.startsWith('CONTINUOUS') || m.startsWith('INTERMITTENT') || m === 'LOW' || m === 'HIGH') ? '작동' : m;
  const road = (v) => v.includes('SCHOOL_ZONE') ? '어린이보호구역' : v.includes('WRONG_WAY') ? '역주행 주의' : v.includes('TRAFFIC_LIGHT') ? '신호 위반 단속'
    : (v.includes('CUT_IN') || v.includes('LANE_CHANGE')) ? '끼어들기·차선 변경 단속' : (v.includes('SPEED_CAMERA') || v.includes('SECTION_SPEED')) ? '과속 단속' : v;
  /** Whole degrees with a sign, as on the car (−0.4 and −0 both read "0°"). */
  const degrees = (d) => { const n = Math.round(d); return n === 0 ? '0°' : `${n > 0 ? '+' : ''}${n}°`; };
  const area = (a) => AREA[a] || a;
  const sec = (ms) => (ms / 1000).toFixed(1);

  function lineText(l) {
    const what = l.type === 'BRAKE' ? (l.value ? '브레이크 밟음' : '브레이크 뗌')
      : l.type === 'TURN' ? `방향지시등 ${TURN[l.value] || l.value}`
      : l.type === 'GEAR' ? `기어 ${GEAR[l.value] || l.value}`
      : l.type === 'STEERING_PEAK' ? `최대 조향각 ${degrees(l.value)}`
      : '상향등 깜빡임';
    return l.beforeMs == null ? `${what} · 기록 내내 그대로` : `${what} · 감지 ${sec(l.beforeMs)}초 전`;
  }

  /** Context rows as [label, text]; rows this car never reported are left out. Same rows and words as the car. */
  function contextRows(c) {
    const rows = [];
    const items = (m, f) => Object.entries(m).map(([k, v]) => `${area(k)} ${f(v)}`).join(' · ');
    if (Object.keys(c.seatBelts).length) rows.push(['안전벨트', items(c.seatBelts, (v) => v ? '착용' : '해제')]);
    if (Object.keys(c.occupancy).length) rows.push(['착석', Object.entries(c.occupancy).filter(([, v]) => v).map(([k]) => area(k)).join(' · ') || '없음']);
    if (Object.keys(c.doors).length) { const open = Object.entries(c.doors).filter(([, v]) => v).map(([k]) => area(k)); rows.push(['문', open.length ? `열림: ${open.join(' · ')}` : '모두 닫힘']); }
    // Raw positions: the vehicle's range per window is not documented, so no open/closed reading is claimed.
    if (Object.keys(c.windows).length) rows.push(['창문 위치', items(c.windows, String)]);
    if (Object.keys(c.lamps).length) rows.push(['조명', ['LOW_BEAM', 'HIGH_BEAM', 'TAIL_LAMP', 'HAZARD'].filter((k) => k in c.lamps).map((k) => `${LAMP[k] || k} ${c.lamps[k] ? '켜짐' : '꺼짐'}`).join(' · ')]);
    if (Object.keys(c.wipers).length) rows.push(['와이퍼', items(c.wipers, wiper)]);
    if (c.cruise) rows.push(['크루즈', (CRUISE[c.cruise] || c.cruise) + (c.cruiseTargetMps != null ? ` · 설정 ${(c.cruiseTargetMps * 3.6).toFixed(0)} km/h` : '')]);
    if (c.parkingBrake != null) rows.push(['주차 브레이크', c.parkingBrake ? '체결' : '해제']);
    if (c.speedLimitKmh != null || c.roadAlerts.length) rows.push(['제한속도', c.speedLimitKmh != null ? `${c.speedLimitKmh} km/h` : '정보 없음']);
    if (c.roadAlerts.length) rows.push(['주의 구간', c.roadAlerts.map(([, v]) => road(v)).join(' · ')]);
    return rows;
  }

  // ---- the lanes, drawn ----

  const esc = (s) => String(s).replace(/[&<>"]/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[ch]);

  /**
   * The lanes as an SVG string, [width] wide (viewBox units). One time axis for all lanes; the detection line at 0;
   * the "N s before" marks of [t.lines]. [cursorMs] draws a playhead (the dashcam view).
   */
  /** [lane]: each lane's height in svg units (taller to fill a full-screen view; the width keeps the same scale). */
  function svg(t, { width = LAYOUT.width, cursorMs = null, label = LAYOUT.label, lane = 30 } = {}) {
    const gap = 5, top = 8, plotW = width - label - 6;
    const x = (ms) => label + ((ms + t.pre) / (t.pre + t.post)) * plotW;
    const names = ['속도', '조향', '브레이크', '방향지시등', '기어'];
    const h = top + names.length * (lane + gap) + 18;
    const out = [`<svg viewBox="0 0 ${width} ${h}" width="100%" role="img" aria-label="감지 전후 조작 그래프" xmlns="http://www.w3.org/2000/svg" style="font:11px system-ui,sans-serif">`];
    const y0 = (i) => top + i * (lane + gap);
    names.forEach((n, i) => {
      out.push(`<rect x="${label}" y="${y0(i)}" width="${plotW}" height="${lane}" fill="var(--lane,#f3f4f6)" rx="3"/>`);
      out.push(`<text x="0" y="${y0(i) + lane / 2 + 3}" fill="var(--muted,#555)">${n}</text>`);
    });
    // Recorded part of the window (a capsule sealed at the impact has nothing after it).
    const blank = (a, b) => b > a && out.push(`<rect x="${x(a)}" y="${top}" width="${x(b) - x(a)}" height="${names.length * (lane + gap) - gap}" fill="url(#none)" opacity=".35"/>`);
    out.push('<defs><pattern id="none" width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)"><rect width="3" height="6" fill="#bbb"/></pattern></defs>');
    blank(-t.pre, t.startMs);
    blank(t.endMs, t.post);

    const line = (pts, i, lo, hi, color) => {
      if (!pts.length) return;
      const y = (v) => y0(i) + lane - 3 - ((v - lo) / (hi - lo || 1)) * (lane - 6);
      out.push(`<polyline fill="none" stroke="${color}" stroke-width="1.6" points="${pts.map((p) => `${x(p.atMs).toFixed(1)},${y(p.value).toFixed(1)}`).join(' ')}"/>`);
    };
    const maxSpeed = Math.max(20, ...t.speedKmh.map((p) => p.value));
    // Lane colours stay clear of the red detection line and the purple playhead, and apart from each other for
    // colour-blind eyes: speed blue, steering bluish green, brake slate, turn signal amber (as a blinker).
    line(t.speedKmh, 0, 0, maxSpeed, 'var(--lane-speed,#0072b2)');
    out.push(`<text x="${label + 2}" y="${y0(0) + 10}" fill="var(--muted,#555)">${maxSpeed.toFixed(0)} km/h</text>`);
    const maxSteer = Math.max(30, ...t.steering.map((p) => Math.abs(p.value)));
    out.push(`<line x1="${label}" x2="${label + plotW}" y1="${y0(1) + lane / 2}" y2="${y0(1) + lane / 2}" stroke="#ccc"/>`);
    line(t.steering, 1, -maxSteer, maxSteer, 'var(--lane-steer,#009e73)');
    out.push(`<text x="${label + 2}" y="${y0(1) + 10}" fill="var(--muted,#555)">±${maxSteer.toFixed(0)}°</text>`);

    const steps = (s, i, on, text, ink = '#fff') => s.forEach((st, k) => {
      const a = x(st.atMs), b = x(k + 1 < s.length ? s[k + 1].atMs : Math.min(t.endMs, t.post));
      if (b <= a) return;
      if (on(st.value)) out.push(`<rect x="${a.toFixed(1)}" y="${y0(i) + 6}" width="${(b - a).toFixed(1)}" height="${lane - 12}" fill="${on(st.value)}" rx="2"/>`);
      const tx = text(st.value);
      if (tx && b - a > 14) out.push(`<text x="${(a + 3).toFixed(1)}" y="${y0(i) + lane / 2 + 3}" fill="${on(st.value) ? ink : 'var(--fg,#111)'}">${esc(tx)}</text>`);
    });
    steps(t.brake, 2, (v) => v && 'var(--lane-brake,#37474f)', (v) => v ? '밟음' : '', 'var(--lane-brake-fg,#fff)');
    steps(t.turn, 3, (v) => v !== 'OFF' && 'var(--lane-turn,#e69f00)', (v) => v === 'LEFT' ? '좌' : v === 'RIGHT' ? '우' : '', 'var(--lane-turn-fg,#111)');
    steps(t.gear, 4, () => null, (v) => GEAR[v] || v);

    // The detection line and the "N s before" marks.
    const all = names.length * (lane + gap) - gap;
    for (const l of t.lines) if (l.beforeMs != null && l.beforeMs <= t.pre) out.push(`<line x1="${x(-l.beforeMs)}" x2="${x(-l.beforeMs)}" y1="${top}" y2="${top + all}" stroke="var(--muted,#555)" stroke-dasharray="2 2"/>`);
    out.push(`<line x1="${x(0)}" x2="${x(0)}" y1="${top - 4}" y2="${top + all}" stroke="var(--bad,#c62828)" stroke-width="2"/>`);
    out.push(`<text x="${x(0)}" y="${top + all + 12}" text-anchor="middle" fill="var(--bad,#c62828)">감지</text>`);
    // Time marks, leaving out any that would sit on top of the detection label or of each other.
    const marks = [];
    for (const ms of [-t.pre, Math.round(-t.pre / 2 / 1000) * 1000, t.post]) {
      if (Math.abs(x(ms) - x(0)) < 26 || marks.some((m) => Math.abs(x(m) - x(ms)) < 34)) continue;
      marks.push(ms);
      const anchor = ms === -t.pre ? 'start' : ms === t.post ? 'end' : 'middle';
      out.push(`<text x="${x(ms)}" y="${top + all + 12}" text-anchor="${anchor}" fill="var(--muted,#555)">${ms > 0 ? '+' : ''}${(ms / 1000).toFixed(0)}초</text>`);
    }
    if (cursorMs != null && cursorMs >= -t.pre && cursorMs <= t.post) out.push(`<line class="cursor" x1="${x(cursorMs)}" x2="${x(cursorMs)}" y1="0" y2="${h}" stroke="var(--fg,#111)" stroke-width="1.5"/>`);
    out.push('</svg>');
    return out.join('');
  }

  /** Where a ms offset sits horizontally, as a fraction of the svg's width (for a playhead over the lanes). */
  function xFraction(t, ms, { width = LAYOUT.width, label = LAYOUT.label } = {}) {
    return (label + ((ms + t.pre) / (t.pre + t.post)) * (width - label - 6)) / width;
  }

  /** The inverse: the ms offset under a horizontal fraction of the svg (a tap on the lanes). */
  function msAtFraction(t, frac, { width = LAYOUT.width, label = LAYOUT.label } = {}) {
    return ((frac * width - label) / (width - label - 6)) * (t.pre + t.post) - t.pre;
  }

  const WBPacket = { build, svg, xFraction, msAtFraction, lineText, contextRows, degrees, TRIGGER, GEAR, TURN, PRE_MS, POST_MS };
  if (typeof module === 'object' && module.exports) module.exports = WBPacket; else root.WBPacket = WBPacket;
})(typeof self !== 'undefined' ? self : globalThis);
