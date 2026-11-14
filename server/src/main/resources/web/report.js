// The accident account for insurers and the police (Pro): what the records say, in plain sentences with their numbers,
// then the blanks a person fills in, then every record of the capsule at the bottom. Rules only, never a guess: each
// sentence comes from recorded values, and a reading ("very likely raining") says how sure it is and why.
(function (root) {
  const P = root.WBPacket || (typeof require === 'function' ? require('./packet.js') : null);

  const sec = (ms) => (Math.abs(ms) / 1000).toFixed(1);
  const kmh = (mps) => mps * 3.6;
  const dur = (ms) => { const s = Math.round(ms / 1000); return s >= 60 ? `${Math.floor(s / 60)}분 ${s % 60}초` : `${s}초`; };
  /** How long ago a window began, in whole minutes when it is whole ("2분", "1분 30초", "45초"). */
  const ago = (ms) => { const s = Math.round(ms / 1000); return s >= 60 ? `${Math.floor(s / 60)}분${s % 60 ? ` ${s % 60}초` : ''}` : `${s}초`; };
  const AREA = { ROW_1_LEFT: '운전석', ROW_1_RIGHT: '조수석', ROW_2_LEFT: '뒷좌석 왼쪽', ROW_2_RIGHT: '뒷좌석 오른쪽', ROW_2_CENTER: '뒷좌석 가운데', HOOD: '후드', REAR: '트렁크', FRONT_WINDSHIELD: '앞 유리', REAR_WINDSHIELD: '뒤 유리' };
  const area = (a) => AREA[a] || a;
  /** Seats as people name them: front first, driver first. */
  const SEAT_ORDER = ['ROW_1_LEFT', 'ROW_1_RIGHT', 'ROW_2_LEFT', 'ROW_2_CENTER', 'ROW_2_RIGHT'];
  const LAMP = { LOW_BEAM: '전조등(하향등)', HIGH_BEAM: '상향등', TAIL_LAMP: '미등', HAZARD: '비상등' };
  const GEAR = { PARK: 'P(주차)', REVERSE: 'R(후진)', NEUTRAL: 'N(중립)', DRIVE: 'D(주행)', MANUAL: 'M(수동)' };
  // The words of each line the car speaks, as the car's own timeline quotes them.
  const VOICE = {
    KEEP_QUESTION: '방금 상황 보관할까요?', KEPT: '보관 완료', RELEASED: '보관 취소',
    CRASH_GUIDANCE: '기록 보관 완료. 과실 인정은 피하고, 현장을 촬영하세요.', BOOKMARKED: '보관 완료',
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
   *
   * [request]: the record was saved on the driver's word, not by a detection. The moment is then the request (요청한
   * 순간), nothing happened at it, and what matters is everything kept before it: speed, braking, steering and the
   * turn signal are read over the whole kept stretch instead of the last ten seconds.
   */
  function interpret(events, causeWall, context = {}, request = false) {
    const out = [];
    const start = events[0].e.at.wall;
    // The moment's name in a sentence: "감지 3.2초 전", "감지 순간"; for a request "요청 3.2초 전", "요청한 순간".
    const m = request ? '요청' : '감지', mAt = request ? '요청한 순간' : '감지 순간';
    // The states the window started with (written when the capsule opened) count as signals just before its start.
    // Steering is left out: a reading compares its angles over time, and a carried angle has no time of its own.
    events = events.filter((x) => x.e.type === 'C' && !x.e.locked && x.e.kind !== 'STEERING_ANGLE')
      .map((x) => ({ seq: x.seq, e: { ...x.e, type: 'S', at: { ...x.e.at, wall: start - 1 } } }))
      .concat(events.filter((x) => x.e.type !== 'C'));
    const add = (text, note) => out.push(note ? { text, note } : { text });

    // Speed: ten seconds before, the start of slowing down, and at the moment.
    const speed = series(events, 'SPEED');
    const at = (w) => { let s = null; for (const r of speed) { if (r.wall > w) break; s = r; } return s; };
    const now = at(causeWall), before = at(causeWall - 10000);
    if (request) {
      add(`보관을 요청하기 ${ago(causeWall - start)} 전부터의 기록입니다.`);
      if (now) {
        const v = kmh(now.v);
        // The speed the stretch began with (carried in) and every speed up to the request.
        const first = at(start);
        const all = (first ? [first] : []).concat(speed.filter((r) => r.wall >= start && r.wall <= causeWall)).map((r) => kmh(r.v));
        const lo = Math.min(...all), hi = Math.max(...all);
        if (hi - lo < 3) add(v < 1 ? '이 구간 내내 차는 멈춰 있었습니다 (속도 0 km/h).' : `이 구간 내내 시속 약 ${v.toFixed(0)} km로 달렸습니다.`);
        else add(`이 구간의 속도는 시속 ${lo.toFixed(0)}~${hi.toFixed(0)} km였고, ${mAt}에는 시속 ${v.toFixed(0)} km였습니다.`);
        const stop = speed.find((r) => r.wall > causeWall && r.v < 0.3);
        if (stop) add(`요청 ${sec(stop.wall - causeWall)}초 뒤 차가 멈췄습니다.`);
      }
    } else if (now) {
      const v = kmh(now.v);
      // Back from the moment while the speed was falling: a repeated value inside the fall (samples arrive faster
      // than the speed changes) does not end it; rising, or holding for over a second, does.
      let slowFrom = null, flatMs = 0;
      for (let i = speed.length - 1; i > 0; i--) {
        if (speed[i].wall > causeWall) continue;
        const earlier = speed[i - 1], later = speed[i];
        if (earlier.v > later.v + 0.05) { slowFrom = earlier; flatMs = 0; }
        else if (!slowFrom) continue;
        else if (earlier.v < later.v - 0.05) break;
        else { flatMs += later.wall - earlier.wall; if (flatMs > 1000) break; }
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
    if (request) {
      // Every time the pedal went down in the stretch (one held from before it counts from the stretch's start).
      let presses = 0, lastDown = start, down = false;
      for (const r of brake) {
        if (r.wall > causeWall) break;
        if (r.v && !down) { presses++; lastDown = Math.max(r.wall, start); }
        down = r.v;
      }
      if (!brake.length) { /* this car reported no brake */ }
      else if (presses === 0) add('이 구간에서 브레이크를 밟은 기록이 없습니다.');
      else if (down) add(`이 구간에서 브레이크를 ${presses}번 밟았고, ${mAt}에도 밟고 있었습니다 (요청 ${sec(causeWall - lastDown)}초 전부터).`);
      else add(`이 구간에서 브레이크를 ${presses}번 밟았습니다. 마지막으로 밟은 것은 요청 ${sec(causeWall - lastDown)}초 전입니다.`);
    } else if (b && b.v) add(b.since < start ? `브레이크는 감지 ${ago(causeWall - start)} 전에 이미 밟고 있었고, 감지 순간에도 밟고 있었습니다.` : `브레이크는 감지 ${sec(causeWall - b.since)}초 전에 밟았고, 감지 순간에도 밟고 있었습니다.`);
    else if (pressedBefore.length) add(`감지 전 10초 사이에 브레이크를 ${pressedBefore.length}번 밟았고, 감지 순간에는 떼고 있었습니다.`);
    else if (brake.length) add('감지 전 10초 동안 브레이크를 밟은 기록이 없습니다.');

    // Steering: the fastest turn of the wheel before the moment; for a request, the widest angle in the stretch.
    const wheel = series(events, 'STEERING_ANGLE');
    if (request) {
      let widest = null;
      for (const r of wheel) if (r.wall >= start && r.wall <= causeWall && (!widest || Math.abs(r.v) > Math.abs(widest.v))) widest = r;
      if (widest && Math.abs(widest.v) >= 10) add(`핸들은 요청 ${sec(causeWall - widest.wall)}초 전에 가장 크게 돌렸습니다 (조향각 ${P.degrees(widest.v)}).`);
      else if (widest) add('이 구간에서 핸들을 크게 돌린 기록이 없습니다.');
    }
    const steer = request ? [] : wheel.filter((r) => r.wall >= causeWall - 10000 && r.wall <= causeWall + 1000);
    let peak = null;
    for (let i = 1; i < steer.length; i++) {
      const dt = (steer[i].wall - steer[i - 1].wall) / 1000;
      if (dt <= 0) continue;
      const rate = Math.abs(steer[i].v - steer[i - 1].v) / dt;
      if (!peak || rate > peak.rate) peak = { rate, wall: steer[i].wall, angle: steer[i].v };
    }
    if (peak && peak.rate >= 90) add(`감지 ${sec(causeWall - peak.wall)}초 ${peak.wall <= causeWall ? '전' : '뒤'} 핸들을 빠르게 돌렸습니다 (초당 ${peak.rate.toFixed(0)}°, 그때 조향각 ${P.degrees(peak.angle)}).`, '평소 운전에서 핸들을 초당 90° 넘게 돌리는 일은 드뭅니다. 피하려고 한 움직임일 가능성이 높습니다.');
    else if (steer.length) add('감지 전 10초 동안 핸들을 크게 돌린 기록이 없습니다.');

    // Turn signal; for a request, when it was last put on in the stretch and for how long.
    const turns = series(events, 'TURN_SIGNAL');
    if (request) {
      let times = 0, on = start, off = null, side = 'OFF', prev = 'OFF';
      for (const r of turns) {
        if (r.wall > causeWall) break;
        if (r.v !== 'OFF' && r.v !== prev) { times++; on = Math.max(r.wall, start); off = null; side = r.v; }
        else if (r.v === 'OFF' && prev !== 'OFF') off = r.wall;
        prev = r.v;
      }
      const name = side === 'LEFT' ? '왼쪽' : '오른쪽';
      if (!turns.length) { /* this car reported no turn signal */ }
      else if (times === 0) add('이 구간에서 방향지시등을 켠 기록이 없습니다.');
      else if (off === null) add(`방향지시등(${name})은 요청 ${sec(causeWall - on)}초 전부터 켜져 있었습니다.`);
      else add(`방향지시등(${name})을 요청 ${sec(causeWall - on)}초 전에 켜서 ${sec(off - on)}초 동안 켜 두었습니다.` + (times > 1 ? ` 이 구간에서 방향지시등을 모두 ${times}번 켰습니다.` : ''));
    }
    const turn = request ? null : stateAt(turns, causeWall, (v) => v);
    if (turn && turn.v !== 'OFF') add(`방향지시등(${turn.v === 'LEFT' ? '왼쪽' : '오른쪽'})은 ${turn.since < start ? `감지 ${ago(causeWall - start)} 전에 이미` : `감지 ${sec(causeWall - turn.since)}초 전부터`} 켜져 있었습니다.`);
    else if (turn) add('감지 순간 방향지시등은 꺼져 있었습니다.');

    // Gear.
    const gear = stateAt(series(events, 'GEAR'), causeWall, (v) => v);
    if (gear) add(`기어는 ${GEAR[gear.v] || gear.v}였습니다.`);

    // The road: speed limit and caution areas the navigation was announcing.
    if (context.speedLimitKmh && now) {
      const v = kmh(now.v), over = v - context.speedLimitKmh;
      add(`내비게이션이 알려 준 이 도로의 제한속도는 시속 ${context.speedLimitKmh} km였고, ${mAt} 속도는 ${over > 0 ? `제한속도보다 ${over.toFixed(0)} km/h 빨랐습니다` : '제한속도 이내였습니다'}.`);
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
        add(`앞 유리 와이퍼가 ${m} 전 ${since == null ? '최소 ' : ''}${dur(held)} 동안 ${how ? how + ' ' : ''}작동하고 있었습니다.`,
          `와이퍼가 오래 작동했다는 것은 비나 눈이 오고 있었을 가능성이 높다는 뜻입니다${fast(w.v) ? ' (빠른 속도: 비가 많이 왔을 가능성)' : ''}. 노면이 젖어 제동 거리가 길어질 수 있습니다.`);
      } else {
        add(`앞 유리 와이퍼를 ${m} ${dur(held)} 전에 켰습니다.`, '잠깐 켠 와이퍼는 비 때문일 수도, 유리를 닦기 위해서일 수도 있습니다.');
      }
    } else if (wipers.length) add(`${mAt} 와이퍼는 꺼져 있었습니다.`, '비나 눈은 오지 않았을 가능성이 높습니다.');

    // Light.
    const lamps = (name) => events.filter((x) => x.e.type === 'S' && x.e.kind === 'LIGHT' && !x.e.locked && x.e.value.lamp === name).map((x) => ({ wall: x.e.at.wall, v: x.e.value.on }));
    const low = stateAt(lamps('LOW_BEAM'), causeWall, (v) => v);
    if (low && low.k) add('전조등이 켜져 있었습니다.', '해가 진 뒤였거나 터널·비 등으로 어두웠을 가능성이 있습니다.');
    else if (low) add('전조등은 꺼져 있었습니다.');
    const flashes = lamps('HIGH_BEAM').filter((r) => r.v && r.wall <= causeWall && r.wall >= causeWall - 10000);
    if (flashes.length) add(`${m} ${sec(causeWall - flashes[0].wall)}초 전 상향등을 켰습니다.`, '상대에게 보내는 경고 신호였을 가능성이 있습니다.');
    const hazard = lamps('HAZARD').find((r) => r.v && r.wall > causeWall);
    if (hazard) add(`${m} ${sec(hazard.wall - causeWall)}초 뒤 비상등을 켰습니다.`);

    // Warning lamps (brake and stability systems) that came on up to the moment.
    const lampOn = new Map();
    for (const x of events) {
      if (x.e.type !== 'S' || x.e.kind !== 'TELLTALE' || x.e.locked || x.e.at.wall > causeWall) continue;
      const { name, state } = x.e.value;
      if (state === 'OFF') lampOn.delete(name); else if (!lampOn.has(name)) lampOn.set(name, { wall: x.e.at.wall, state });
    }
    for (const [name, on] of lampOn) {
      const since = on.wall < start ? `${m} ${ago(causeWall - start)} 전에 이미` : `${m} ${sec(causeWall - on.wall)}초 전부터`;
      add(`${name} 경고등이 ${since} ${on.state === 'BLINK' ? '깜빡이고' : '켜져'} 있었습니다.`, 'ABS·ESC 경고등은 제동이나 자세 제어 장치가 개입했거나 이상이 있을 때 켜집니다.');
    }

    // People: belts and seats at the moment.
    const belts = {};
    for (const x of events) if (x.e.type === 'S' && x.e.kind === 'SEAT_BELT' && !x.e.locked && x.e.at.wall <= causeWall) belts[x.e.value.seat] = x.e.value.buckled;
    if ('ROW_1_LEFT' in belts) add(belts.ROW_1_LEFT ? '운전석 안전벨트를 매고 있었습니다.' : '운전석 안전벨트를 매지 않은 것으로 기록되어 있습니다.');
    const seats = {};
    for (const x of events) if (x.e.type === 'S' && x.e.kind === 'SEAT_OCCUPANCY' && !x.e.locked && x.e.at.wall <= causeWall) seats[x.e.value.seat] = x.e.value.occupied;
    const rank = (k) => (SEAT_ORDER.indexOf(k) < 0 ? SEAT_ORDER.length : SEAT_ORDER.indexOf(k));
    const taken = Object.entries(seats).filter(([, v]) => v).map(([k]) => k).sort((a, b) => rank(a) - rank(b)).map(area);
    if (P.SEATS_READ && taken.length) add(`사람이 앉아 있던 좌석: ${taken.join(', ')}.`);

    // Cruise control.
    const cruise = stateAt(series(events, 'CRUISE_CONTROL'), causeWall, (v) => v);
    if (cruise && cruise.v === 'ACTIVATED') add('크루즈 컨트롤이 작동 중이었습니다.');

    // Right after: doors opening (someone got out).
    const door = events.find((x) => x.e.type === 'S' && x.e.kind === 'DOOR' && !x.e.locked && x.e.value.open && x.e.at.wall > causeWall);
    if (door) add(`${m} ${sec(door.e.at.wall - causeWall)}초 뒤 ${area(door.e.value.area)} 문이 열렸습니다.`, request ? undefined : '사고 직후 차에서 내렸을 가능성이 높습니다.');
    return out;
  }

  // The recorder's own entries, as rows of the full list.
  const OTHER = { R: '기록 상태', E: '기록 처리', A: '소리', H: '대리 운전', K: '보관함' };
  const OTHER_EVENT = { STARTED: '기록 시작', STOPPED: '기록 멈춤', DEGRADED: '권한이 빠짐', RESTORED: '권한이 돌아옴', PRUNED: '오래된 기록 정리',
    ARMED: '켜짐 (대기)', CANCELLED: '취소', ENDING: '끝나는 중', RESUMED: '이어짐', ENDED: '끝남', EVENT: '기록된 운전',
    PLAN: '요금제', LEFT: '보관함에서 빠짐', RETURNED: '보관함으로 돌아옴', EXTENDED: '보관 연장' };

  /** Every record of the capsule as a row: time, seconds from the moment, what, value. */
  function log(events, causeWall) {
    const name = { SPEED: '속도', BRAKE_PEDAL: '브레이크', STEERING_ANGLE: '조향각', TURN_SIGNAL: '방향지시등', GEAR: '기어', WIPER: '와이퍼', LIGHT: '조명',
      DOOR: '문', SEAT_BELT: '안전벨트', SEAT_OCCUPANCY: '착석', WINDOW: '창문', CRUISE_CONTROL: '크루즈', CRUISE_TARGET_SPEED: '크루즈 설정', IGNITION: '시동',
      PARKING_BRAKE: '주차 브레이크', TELLTALE: '경고등', ODOMETER: '주행거리', ROAD_CONTEXT: '도로 정보', AIRBAG: '에어백', ROAD_GRADE: '도로 경사' };
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
        case 'TELLTALE': return `${v.name} ${v.state === 'OFF' ? '꺼짐' : v.state === 'BLINK' ? '깜빡임' : '켜짐'}`;
        case 'AIRBAG': return `${area(v.seat)} ${v.deployed ? (v.test ? '시험용 신호' : '전개') : '정상'}`;
        default: return typeof v === 'object' ? JSON.stringify(v) : String(v);
      }
    };
    const rows = [];
    for (const x of events) {
      const e = x.e;
      const rel = (e.at.wall - causeWall) / 1000;
      if (e.type === 'S') rows.push([x.seq, e.at.wall, rel, name[e.kind] || e.kind, e.locked ? '(잠김)' : value(e)]);
      else if (e.type === 'C') rows.push([x.seq, e.at.wall, rel, `${name[e.kind] || e.kind} (구간 시작 때)`, e.locked ? '(잠김)' : value(e)]);
      else if (e.type === 'L') rows.push([x.seq, e.at.wall, rel, '위치', e.locked ? '차주만 볼 수 있음' : `${e.lat.toFixed(5)}, ${e.lon.toFixed(5)} (±${Math.round(e.acc || 0)} m)`]);
      else if (e.type === 'E' && e.event === 'TRIGGER') rows.push([x.seq, e.at.wall, rel, e.trigger === 'MANUAL' ? '요청' : '감지', P.TRIGGER[e.trigger] || e.trigger]);
      else if (e.type === 'E' && e.event === 'LEVEL') rows.push([x.seq, e.at.wall, rel, '단계', { NORMAL: '기록 중', ALERT: '위험 감지', TRIGGERED: '기록 보관' }[e.level] || e.level]);
      else if (e.type === 'E' && e.event === 'ANSWER') rows.push([x.seq, e.at.wall, rel, '운전자 대답', !e.answered ? '대답 없음 (보관)' : e.keep ? '보관' : '보관 안 함']);
      else if (e.type === 'A' && e.event === 'SPOKE') rows.push([x.seq, e.at.wall, rel, '차의 음성 안내', `“${VOICE[e.line] || e.line}”${e.complete ? '' : ' (중단됨)'}`]);
      else if (e.type === 'A' && e.event === 'BEACON') rows.push([x.seq, e.at.wall, rel, '동기화 신호음', `#${e.chirpId}`]);
      else if (e.type === 'R' && e.event === 'SLEPT') rows.push([x.seq, e.at.wall, rel, '기록 공백', `차가 잠들어 있던 ${dur(e.sleptMs)} (시동 꺼짐)`]);
      else if (e.type === 'A' && e.event === 'BOOKMARK') rows.push([x.seq, e.at.wall, rel, '음성 보관 요청', e.source === 'OFFLINE_LISTENER' ? '통신 없이 인식' : 'Gleo']);
      else if (e.type === 'E' && e.event === 'OFFER') rows.push([x.seq, e.at.wall, rel, '보관 질문', `“${VOICE.KEEP_QUESTION}”`]);
      // Every record has its row, so the list's count is the count that was verified.
      else rows.push([x.seq, e.at.wall, rel, OTHER[e.type] || '기록', OTHER_EVENT[e.event] || e.event || '']);
    }
    return rows;
  }

  // ---- the two documents a driver hands in (Pro): the police statement and an insurance accident report ----

  const esc = (x) => String(x).replace(/[&<>"]/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[ch]);
  const two = (n) => String(n).padStart(2, '0');
  /** The date and time of [wall] as the forms' blanks take them (the phone's own time zone). */
  const stamp = (wall) => { const d = new Date(wall); return { y: d.getFullYear(), mo: d.getMonth() + 1, d: d.getDate(), h: two(d.getHours()), mi: two(d.getMinutes()) }; };
  const blank = (em) => `<span class="fill" style="min-width:${em}em"></span>`;
  /** What the car recorded, as a numbered draft the person checks and corrects: never a statement made for them. */
  const draft = (m) => m.readings.length
    ? `<ol class="draft">${m.readings.map((r) => `<li>${esc(r.text)}</li>`).join('')}</ol>`
    : '<p class="hint">옮길 기록이 없습니다.</p>';
  const where = (m) => m.place
    ? `차량 기록의 위치: 위도 ${m.place.lat.toFixed(5)}, 경도 ${m.place.lon.toFixed(5)} (±${Math.round(m.place.acc || 0)} m)`
    : '차량 기록에 위치가 없습니다';

  /**
   * 「교통사고 발생상황 진술서」: the statement a driver writes for the police, laid out with that form's own items
   * (who, the pre-printed sentence with the date, time and place, the free statement, the sketch, date and signature).
   * The date and time come from the record; the statement starts as a draft of what the car recorded, under the points
   * an investigator asks about, for the person to check, correct and continue in their own words. Names, numbers and
   * addresses are never known to the app: those stay blank.
   */
  function statement(m) {
    const t = stamp(m.causeWall);
    return `<section class="form police">
      <h1>교통사고 발생상황 진술서</h1>
      <p class="hint noprint">경찰에 내는 진술서의 항목 그대로입니다. 날짜와 시각, 차량 기록에서 옮긴 내용은 채워 두었습니다. 확인하고 고친 뒤 나머지를 직접 적어 주세요.</p>
      <table class="grid">
        <tr><th>구분</th><td colspan="3">□ 가해자 &nbsp; □ 피해자 &nbsp; □ 목격자 &nbsp; □ 참고인 &nbsp; □ 기타관계자</td></tr>
        <tr><th>성명(한자)</th><td></td><th>주민등록번호</th><td></td></tr>
        <tr><th>주소</th><td colspan="3"></td></tr>
        <tr><th>전화번호</th><td>(휴대) &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; (자택)</td><th>성별</th><td>□ 남 &nbsp; □ 여</td></tr>
        <tr><th>운전면허번호</th><td></td><th>직업</th><td></td></tr>
      </table>
      <p class="sentence">위 본인은 <b>${t.y}. ${t.mo}. ${t.d}. ${t.h}:${t.mi}</b>경 ${blank(5)}시(구) ${blank(5)}동 ${blank(4)}번지 앞 지점에서 발생한 교통사고에 대하여 다음과 같이 임의로 진술합니다.</p>
      <p class="hint">${esc(where(m))}</p>
      <h2>진술 내용</h2>
      <p class="hint">차량 기록에서 옮긴 내용 (화이트박스가 차에서 기록하고 봉인한 값. 사실과 다르면 고쳐 주세요)</p>
      ${draft(m)}
      <p class="hint">이어서 직접 적어 주세요: 진로와 속도, 상대를 발견한 때와 위치, 급제동·감속 등 사고를 피하려고 한 조치, 부딪힌 지점과 멈춘 지점.</p>
      <div class="lines">${'<div></div>'.repeat(8)}</div>
      <h2>사고 모습 (직접 그려 주세요)</h2>
      <div class="sketch"></div>
      <table class="grid sign">
        <tr><th>진술일시</th><td>20${blank(2)}. ${blank(2)}. ${blank(2)}. ${blank(2)}:${blank(2)}</td><th>진술인</th><td>${blank(8)} (서명 또는 날인)</td></tr>
        <tr><th>조사경찰관</th><td colspan="3">소속 ${blank(7)} 계급 ${blank(5)} 성명 ${blank(6)} (경찰 기재)</td></tr>
      </table>
      <p class="hint">첨부: 차량 운행 기록 (화이트박스, 다음 장)</p>
    </section>`;
  }

  /**
   * An accident report for the driver's own insurer. Insurers have no common form, so this one carries the items the
   * policy's 사고발생 시 의무 clause says must be told (when, where, what happened, the damage; both parties; witnesses;
   * any claim received) together with what insurers' own claim forms ask (policy, car, driver, police report, sketch).
   * The time and the record's reading of what happened are filled in; the rest is the person's.
   */
  function claim(m) {
    const t = stamp(m.causeWall);
    return `<section class="form insurer">
      <h1>자동차 사고 접수서</h1>
      <p class="sub">보험회사 제출용 · 화이트박스 양식 (자동차보험 약관 「사고발생 시 의무」에서 알리도록 한 사항)</p>
      <p class="hint noprint">보험회사마다 청구서 양식이 달라, 약관이 공통으로 알리도록 한 사항을 한 장에 모았습니다. 그대로 내거나 보험회사 양식에 옮겨 적을 때 쓰세요.</p>
      <h2>① 계약과 차량</h2>
      <table class="grid">
        <tr><th>보험회사</th><td></td><th>증권번호</th><td></td></tr>
        <tr><th>계약자(피보험자)</th><td></td><th>연락처</th><td></td></tr>
        <tr><th>차량번호</th><td></td><th>운전자 (관계)</th><td></td></tr>
        <tr><th>운전면허번호</th><td colspan="3"></td></tr>
      </table>
      <h2>② 사고가 발생한 때, 곳, 상황, 손해의 정도</h2>
      <table class="grid">
        <tr><th>사고 일시</th><td colspan="3"><b>${t.y}년 ${t.mo}월 ${t.d}일 ${t.h}시 ${t.mi}분</b> (차량 기록)</td></tr>
        <tr><th>사고 장소</th><td colspan="3">${blank(18)}<br><span class="hint">${esc(where(m))}</span></td></tr>
        <tr><th>사고 상황</th><td colspan="3"><span class="hint">차량 기록에서 옮긴 내용 (사실과 다르면 고쳐 주세요)</span>${draft(m)}<span class="hint">누가, 언제, 어디서, 무엇을, 어떻게, 왜: 이어서 직접 적어 주세요.</span><div class="lines">${'<div></div>'.repeat(4)}</div></td></tr>
        <tr><th>손해의 정도</th><td colspan="3">내 차 파손 부위: ${blank(12)}<br>상대 차·물건: ${blank(13)}<br>다친 사람: ${blank(14)}</td></tr>
      </table>
      <h2>③ 피해자와 가해자</h2>
      <table class="grid">
        <tr><th>구분</th><th>성명</th><th>주소</th><th>전화번호</th><th>차량번호 · 보험회사</th></tr>
        <tr><td>□ 피해자 □ 가해자</td><td></td><td></td><td></td><td></td></tr>
        <tr><td>□ 피해자 □ 가해자</td><td></td><td></td><td></td><td></td></tr>
      </table>
      <h2>④ 증인</h2>
      <table class="grid"><tr><th>성명</th><td></td><th>전화번호</th><td></td></tr><tr><th>주소</th><td colspan="3"></td></tr></table>
      <h2>⑤ 손해배상 청구를 받은 내용</h2>
      <div class="lines">${'<div></div>'.repeat(2)}</div>
      <h2>⑥ 경찰 신고</h2>
      <table class="grid"><tr><th>신고 여부</th><td>□ 신고함 &nbsp; □ 신고하지 않음</td><th>관할 경찰서</th><td></td></tr></table>
      <h2>⑦ 사고 약도와 파손 부위 (직접 그려 주세요)</h2>
      <div class="sketch small"></div>
      <h2>⑧ 함께 내는 자료</h2>
      <p>☑ 차량 운행 기록 (화이트박스, 다음 장) &nbsp; □ 블랙박스 영상 &nbsp; □ 현장 사진 &nbsp; □ 기타 ${blank(8)}</p>
      <table class="grid sign"><tr><th>작성일</th><td>20${blank(2)}. ${blank(2)}. ${blank(2)}.</td><th>작성자</th><td>${blank(8)} (서명)</td></tr></table>
    </section>`;
  }

  const WBReport = { interpret, log, statement, claim, VOICE };
  if (typeof module === 'object' && module.exports) module.exports = WBReport; else root.WBReport = WBReport;
})(typeof self !== 'undefined' ? self : globalThis);
