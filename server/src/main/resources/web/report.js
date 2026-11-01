// The accident account for insurers and the police (Pro): what the records say, in plain sentences with their numbers,
// then the blanks a person fills in, then every record of the capsule at the bottom. Rules only, never a guess: each
// sentence comes from recorded values, and a reading ("very likely raining") says how sure it is and why.
(function (root) {
  const P = root.WBPacket || (typeof require === 'function' ? require('./packet.js') : null);

  const sec = (ms) => (Math.abs(ms) / 1000).toFixed(1);
  const kmh = (mps) => mps * 3.6;
  const dur = (ms) => { const s = Math.round(ms / 1000); return s >= 60 ? `${Math.floor(s / 60)}분 ${s % 60}초` : `${s}초`; };
  const AREA = { ROW_1_LEFT: '운전석', ROW_1_RIGHT: '조수석', ROW_2_LEFT: '뒷좌석 왼쪽', ROW_2_RIGHT: '뒷좌석 오른쪽', ROW_2_CENTER: '뒷좌석 가운데', HOOD: '후드', REAR: '트렁크', FRONT_WINDSHIELD: '앞 유리', REAR_WINDSHIELD: '뒤 유리' };
  const area = (a) => AREA[a] || a;
  const LAMP = { LOW_BEAM: '전조등(하향등)', HIGH_BEAM: '상향등', TAIL_LAMP: '미등', HAZARD: '비상등' };
  const GEAR = { PARK: 'P(주차)', REVERSE: 'R(후진)', NEUTRAL: 'N(중립)', DRIVE: 'D(주행)', MANUAL: 'M(수동)' };
  // The words of each line the car speaks, as the car's own timeline quotes them.
  const VOICE = {
    KEEP_QUESTION: '방금 상황 보관할까요?', KEPT: '보관 완료', RELEASED: '보관 취소',
    CRASH_GUIDANCE: '사고 기록 완료. 과실 인정은 피하고, 현장을 촬영하세요.', BOOKMARKED: '보관 완료',
    HANDOVER_STARTED: '대리 운전 모드입니다. 주행이 기록됩니다.', HANDOVER_SET_DESTINATION: '목적지를 설정해 주세요.',
    HANDOVER_SLOW_DOWN: '속도를 줄여 주세요.', HANDOVER_DRIVE_SAFELY: '안전 운전 부탁드립니다.',
    HANDOVER_CHECK_ROUTE: '경로를 확인해 주세요.', LIBRARY_FULL: '보관함이 가득 차 기록 하나가 빠졌습니다.',
  };
  const wiperOn = (m) => m && m !== 'OFF';
  const fast = (m) => /HIGH|CONTINUOUS_2|FAST/.test(m || '');

  /** Signal records of [kind] as {wall, v}, in order. */
  function series(events, kind) {
    return events.filter((x) => x.e.type === 'S' && x.e.kind === kind && !x.e.locked).map((x) => ({ wall: x.e.at.wall, v: x.e.value, seq: x.seq }));
  }

  /** The value of a state signal at [wall] and since when it held (the record before it, if any). */
  function stateAt(list, wall, key) {
    let cur = null, since = null;
    for (const r of list) {
      if (r.wall > wall) break;
      const k = key(r.v);
      if (!cur || cur.k !== k) { cur = { k, v: r.v }; since = r.wall; }
    }
    return cur ? { ...cur, since } : null;
  }

  /**
   * Plain sentences about the moment: speed, braking, steering, signals, gear, the road, the weather and light, the
   * people in the car, and what happened right after. [causeWall] is the detection instant.
   */
  function interpret(events, causeWall, context = {}) {
    const out = [];
    const start = events[0].e.at.wall;
    const add = (text, note) => out.push(note ? { text, note } : { text });

    // Speed: ten seconds before, the start of slowing down, and at the moment.
    const speed = series(events, 'SPEED');
    const at = (w) => { let s = null; for (const r of speed) { if (r.wall > w) break; s = r; } return s; };
    const now = at(causeWall), before = at(causeWall - 10000);
    if (now) {
      const v = kmh(now.v);
      let slowFrom = null;
      for (let i = speed.length - 1; i > 0; i--) {
        if (speed[i].wall > causeWall) continue;
        if (speed[i].v < speed[i - 1].v - 0.05) slowFrom = speed[i - 1]; else if (slowFrom) break;
      }
      // The fastest in the ten seconds before, when it is clearly above both ends (sped up, then slowed down).
      const top = speed.filter((r) => r.wall >= causeWall - 10000 && r.wall <= causeWall).reduce((a, r) => (!a || r.v > a.v ? r : a), null);
      const peaked = top && before && kmh(top.v) - Math.max(kmh(before.v), v) >= 3;
      if (before && (Math.abs(kmh(before.v) - v) >= 3 || peaked)) {
        add(`감지 10초 전에는 시속 ${kmh(before.v).toFixed(0)} km였고, ` +
          (peaked ? `감지 ${sec(causeWall - top.wall)}초 전 최고 시속 ${kmh(top.v).toFixed(0)} km까지 올랐다가, ` : '') +
          `감지 순간에는 시속 ${v.toFixed(0)} km였습니다.` +
          (slowFrom && kmh(slowFrom.v) - v >= 3 ? ` 감지 ${sec(causeWall - slowFrom.wall)}초 전부터 속도가 줄기 시작했습니다.` : ''));
      } else {
        add(v < 1 ? '감지 순간 차는 멈춰 있었습니다 (속도 0 km/h).' : `감지 순간 시속 ${v.toFixed(0)} km로 달리고 있었습니다 (10초 전과 거의 같은 속도).`);
      }
      const after = speed.filter((r) => r.wall > causeWall);
      const stop = after.find((r) => r.v < 0.3);
      if (stop) add(`감지 ${sec(stop.wall - causeWall)}초 뒤 차가 멈췄습니다.`);
    }

    // Brake.
    const brake = series(events, 'BRAKE_PEDAL');
    const b = stateAt(brake, causeWall, (v) => v);
    const pressedBefore = brake.filter((r) => r.v && r.wall <= causeWall && r.wall >= causeWall - 10000);
    if (b && b.v) add(`브레이크는 감지 ${sec(causeWall - b.since)}초 전에 밟았고, 감지 순간에도 밟고 있었습니다.`);
    else if (pressedBefore.length) add(`감지 전 10초 사이에 브레이크를 ${pressedBefore.length}번 밟았고, 감지 순간에는 떼고 있었습니다.`);
    else if (brake.length) add('감지 전 10초 동안 브레이크를 밟은 기록이 없습니다.');

    // Steering: the fastest turn of the wheel before the moment.
    const steer = series(events, 'STEERING_ANGLE').filter((r) => r.wall >= causeWall - 10000 && r.wall <= causeWall + 1000);
    let peak = null;
    for (let i = 1; i < steer.length; i++) {
      const dt = (steer[i].wall - steer[i - 1].wall) / 1000;
      if (dt <= 0) continue;
      const rate = Math.abs(steer[i].v - steer[i - 1].v) / dt;
      if (!peak || rate > peak.rate) peak = { rate, wall: steer[i].wall, angle: steer[i].v };
    }
    if (peak && peak.rate >= 90) add(`감지 ${sec(causeWall - peak.wall)}초 ${peak.wall <= causeWall ? '전' : '뒤'} 핸들을 빠르게 돌렸습니다 (초당 ${peak.rate.toFixed(0)}°, 그때 조향각 ${P.degrees(peak.angle)}).`, '평소 운전에서 핸들을 초당 90° 넘게 돌리는 일은 드뭅니다. 피하려고 한 움직임일 가능성이 높습니다.');
    else if (steer.length) add('감지 전 10초 동안 핸들을 크게 돌린 기록이 없습니다.');

    // Turn signal.
    const turn = stateAt(series(events, 'TURN_SIGNAL'), causeWall, (v) => v);
    if (turn && turn.v !== 'OFF') add(`방향지시등(${turn.v === 'LEFT' ? '왼쪽' : '오른쪽'})은 감지 ${sec(causeWall - turn.since)}초 전부터 켜져 있었습니다.`);
    else if (turn) add('감지 순간 방향지시등은 꺼져 있었습니다.');

    // Gear.
    const gear = stateAt(series(events, 'GEAR'), causeWall, (v) => v);
    if (gear) add(`기어는 ${GEAR[gear.v] || gear.v}였습니다.`);

    // The road: speed limit and caution areas the navigation was announcing.
    if (context.speedLimitKmh && now) {
      const v = kmh(now.v), over = v - context.speedLimitKmh;
      add(`내비게이션이 알려 준 이 도로의 제한속도는 시속 ${context.speedLimitKmh} km였고, 감지 순간 속도는 ${over > 0 ? `제한속도보다 ${over.toFixed(0)} km/h 빨랐습니다` : '제한속도 이내였습니다'}.`);
    }
    if (context.roadAlerts && context.roadAlerts.length) add(`그때 내비게이션은 ${context.roadAlerts.map(([, v]) => v.includes('SCHOOL_ZONE') ? '어린이보호구역' : v.includes('SPEED') ? '과속 단속 구간' : v.includes('TRAFFIC_LIGHT') ? '신호 단속 구간' : v).join(', ')}을(를) 안내하고 있었습니다.`);

    // Weather, from the wipers: how long they had been on.
    const wipers = events.filter((x) => x.e.type === 'S' && x.e.kind === 'WIPER' && !x.e.locked && x.e.value.area !== 'REAR_WINDSHIELD')
      .map((x) => ({ wall: x.e.at.wall, v: x.e.value.mode }));
    const w = stateAt(wipers, causeWall, wiperOn);
    if (w && w.k) {
      const since = w.since <= start + 1000 ? null : w.since;
      const held = causeWall - (since ?? start);
      const how = fast(w.v) ? '빠르게' : /INTERMITTENT|AUTO/.test(w.v) ? '간헐적으로' : '';
      if (held >= 60000 || since == null) {
        add(`앞 유리 와이퍼가 감지 전 ${since == null ? '최소 ' : ''}${dur(held)} 동안 ${how ? how + ' ' : ''}작동하고 있었습니다.`,
          `와이퍼가 오래 작동했다는 것은 비나 눈이 오고 있었을 가능성이 높다는 뜻입니다${fast(w.v) ? ' (빠른 속도: 비가 많이 왔을 가능성)' : ''}. 노면이 젖어 제동 거리가 길어질 수 있습니다.`);
      } else {
        add(`앞 유리 와이퍼를 감지 ${dur(held)} 전에 켰습니다.`, '잠깐 켠 와이퍼는 비 때문일 수도, 유리를 닦기 위해서일 수도 있습니다.');
      }
    } else if (wipers.length) add('감지 순간 와이퍼는 꺼져 있었습니다.', '비나 눈은 오지 않았을 가능성이 높습니다.');

    // Light.
    const lamps = (name) => events.filter((x) => x.e.type === 'S' && x.e.kind === 'LIGHT' && !x.e.locked && x.e.value.lamp === name).map((x) => ({ wall: x.e.at.wall, v: x.e.value.on }));
    const low = stateAt(lamps('LOW_BEAM'), causeWall, (v) => v);
    if (low && low.k) add('전조등이 켜져 있었습니다.', '해가 진 뒤였거나 터널·비 등으로 어두웠을 가능성이 있습니다.');
    else if (low) add('전조등은 꺼져 있었습니다.', '낮이었을 가능성이 높습니다.');
    const flashes = lamps('HIGH_BEAM').filter((r) => r.v && r.wall <= causeWall && r.wall >= causeWall - 10000);
    if (flashes.length) add(`감지 ${sec(causeWall - flashes[0].wall)}초 전 상향등을 켰습니다.`, '상대에게 보내는 경고 신호였을 가능성이 있습니다.');
    const hazard = lamps('HAZARD').find((r) => r.v && r.wall > causeWall);
    if (hazard) add(`감지 ${sec(hazard.wall - causeWall)}초 뒤 비상등을 켰습니다.`);

    // People: belts and seats at the moment.
    const belts = {};
    for (const x of events) if (x.e.type === 'S' && x.e.kind === 'SEAT_BELT' && !x.e.locked && x.e.at.wall <= causeWall) belts[x.e.value.seat] = x.e.value.buckled;
    if ('ROW_1_LEFT' in belts) add(belts.ROW_1_LEFT ? '운전석 안전벨트를 매고 있었습니다.' : '운전석 안전벨트를 매지 않은 것으로 기록되어 있습니다.');
    const seats = {};
    for (const x of events) if (x.e.type === 'S' && x.e.kind === 'SEAT_OCCUPANCY' && !x.e.locked && x.e.at.wall <= causeWall) seats[x.e.value.seat] = x.e.value.occupied;
    const taken = Object.entries(seats).filter(([, v]) => v).map(([k]) => area(k));
    if (taken.length) add(`사람이 앉아 있던 좌석: ${taken.join(', ')}.`);

    // Cruise control.
    const cruise = stateAt(series(events, 'CRUISE_CONTROL'), causeWall, (v) => v);
    if (cruise && cruise.v === 'ACTIVATED') add('크루즈 컨트롤이 작동 중이었습니다.');

    // Right after: doors opening (someone got out).
    const door = events.find((x) => x.e.type === 'S' && x.e.kind === 'DOOR' && !x.e.locked && x.e.value.open && x.e.at.wall > causeWall);
    if (door) add(`감지 ${sec(door.e.at.wall - causeWall)}초 뒤 ${area(door.e.value.area)} 문이 열렸습니다.`, '사고 직후 차에서 내렸을 가능성이 높습니다.');
    return out;
  }

  /** Every record of the capsule as a row: time, seconds from the moment, what, value. */
  function log(events, causeWall) {
    const name = { SPEED: '속도', BRAKE_PEDAL: '브레이크', STEERING_ANGLE: '조향각', TURN_SIGNAL: '방향지시등', GEAR: '기어', WIPER: '와이퍼', LIGHT: '조명',
      DOOR: '문', SEAT_BELT: '안전벨트', SEAT_OCCUPANCY: '착석', WINDOW: '창문', CRUISE_CONTROL: '크루즈', CRUISE_TARGET_SPEED: '크루즈 설정', IGNITION: '시동',
      PARKING_BRAKE: '주차 브레이크', ODOMETER: '주행거리', ROAD_CONTEXT: '도로 정보', AIRBAG: '에어백', ROAD_GRADE: '도로 경사' };
    const value = (e) => {
      const v = e.value;
      switch (e.kind) {
        case 'SPEED': return `${kmh(v).toFixed(1)} km/h`;
        case 'BRAKE_PEDAL': case 'PARKING_BRAKE': return v ? '밟음' : '뗌';
        case 'STEERING_ANGLE': return P.degrees(v);
        case 'TURN_SIGNAL': return P.TURN[v] || v;
        case 'GEAR': return GEAR[v] || v;
        case 'WIPER': return `${area(v.area)} ${v.mode === 'OFF' ? '꺼짐' : v.mode}`;
        case 'LIGHT': return `${LAMP[v.lamp] || v.lamp} ${v.on ? '켜짐' : '꺼짐'}`;
        case 'DOOR': return `${area(v.area)} ${v.open ? '열림' : '닫힘'}`;
        case 'SEAT_BELT': return `${area(v.seat)} ${v.buckled ? '착용' : '해제'}`;
        case 'SEAT_OCCUPANCY': return `${area(v.seat)} ${v.occupied ? '있음' : '없음'}`;
        case 'WINDOW': return `${area(v.area)} ${v.position}`;
        case 'CRUISE_TARGET_SPEED': return `${kmh(v).toFixed(0)} km/h`;
        case 'ROAD_CONTEXT': return `${v.type} ${v.value}`;
        case 'AIRBAG': return `${v.seat} ${v.deployed ? (v.test ? '시험용 신호' : '전개') : '정상'}`;
        default: return typeof v === 'object' ? JSON.stringify(v) : String(v);
      }
    };
    const rows = [];
    for (const x of events) {
      const e = x.e;
      const rel = (e.at.wall - causeWall) / 1000;
      if (e.type === 'S') rows.push([x.seq, e.at.wall, rel, name[e.kind] || e.kind, e.locked ? '(잠김)' : value(e)]);
      else if (e.type === 'L') rows.push([x.seq, e.at.wall, rel, '위치', e.locked ? '차주만 볼 수 있음' : `${e.lat.toFixed(5)}, ${e.lon.toFixed(5)} (±${Math.round(e.acc || 0)} m)`]);
      else if (e.type === 'E' && e.event === 'TRIGGER') rows.push([x.seq, e.at.wall, rel, '감지', P.TRIGGER[e.trigger] || e.trigger]);
      else if (e.type === 'E' && e.event === 'LEVEL') rows.push([x.seq, e.at.wall, rel, '단계', { NORMAL: '정상', ALERT: '위험 신호', TRIGGERED: '사고 감지' }[e.level] || e.level]);
      else if (e.type === 'E' && e.event === 'ANSWER') rows.push([x.seq, e.at.wall, rel, '운전자 대답', !e.answered ? '대답 없음 (보관)' : e.keep ? '보관' : '보관 안 함']);
      else if (e.type === 'A' && e.event === 'SPOKE') rows.push([x.seq, e.at.wall, rel, '차의 음성 안내', `“${VOICE[e.line] || e.line}”${e.complete ? '' : ' (중단됨)'}`]);
      else if (e.type === 'A' && e.event === 'BEACON') rows.push([x.seq, e.at.wall, rel, '동기화 신호음', `#${e.chirpId}`]);
      else if (e.type === 'R' && e.event === 'SLEPT') rows.push([x.seq, e.at.wall, rel, '기록 공백', `차가 잠들어 있던 ${dur(e.sleptMs)} (시동 꺼짐)`]);
    }
    return rows;
  }

  const WBReport = { interpret, log, VOICE };
  if (typeof module === 'object' && module.exports) module.exports = WBReport; else root.WBReport = WBReport;
})(typeof self !== 'undefined' ? self : globalThis);
