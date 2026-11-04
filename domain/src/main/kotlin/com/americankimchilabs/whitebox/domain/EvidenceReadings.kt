package com.americankimchilabs.whitebox.domain

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One plain sentence about the moment, from recorded values only; [note] says what it likely means and how sure that is.
 * [weight] ranks how much it tells about the moment (the car shows the top few; the phone shows all, in order).
 */
data class Reading(val text: String, val note: String? = null, val weight: Int = 1)

/**
 * What the records say about a detected moment, in plain sentences: speed, braking, steering, signals, gear, the road,
 * the weather and light, the people in the car, and what happened right after. Rules only, never a guess. The phone's
 * evidence summary and accident account run the same rules (report.js) and must produce the same sentences.
 */
object EvidenceReadings {
    private val AREA = mapOf(
        "ROW_1_LEFT" to "운전석", "ROW_1_RIGHT" to "조수석", "ROW_2_LEFT" to "뒷좌석 왼쪽", "ROW_2_RIGHT" to "뒷좌석 오른쪽",
        "ROW_2_CENTER" to "뒷좌석 가운데", "HOOD" to "후드", "REAR" to "트렁크", "FRONT_WINDSHIELD" to "앞 유리", "REAR_WINDSHIELD" to "뒤 유리",
    )
    private val GEAR = mapOf("PARK" to "P(주차)", "REVERSE" to "R(후진)", "NEUTRAL" to "N(중립)", "DRIVE" to "D(주행)", "MANUAL" to "M(수동)")

    private fun area(a: String) = AREA[a] ?: a
    /** Seats as people name them: front first, driver first. */
    private val SEAT_ORDER = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_CENTER", "ROW_2_RIGHT")
    /** As JavaScript's toFixed: the exact binary value rounded half up, so the car and the phone print the same digits. */
    private fun fixed(x: Double, digits: Int) = BigDecimal(x).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    private fun sec(ms: Long) = fixed(abs(ms) / 1000.0, 1)
    private fun f0(x: Double) = fixed(x, 0)
    private fun kmh(mps: Double) = mps * 3.6
    private fun dur(ms: Long): String {
        val s = (ms / 1000.0).roundToInt()
        return if (s >= 60) "${s / 60}분 ${s % 60}초" else "${s}초"
    }
    /** How long ago a window began, in whole minutes when it is whole ("2분", "1분 30초", "45초"). */
    private fun ago(ms: Long): String {
        val s = (ms / 1000.0).roundToInt()
        return if (s >= 60) "${s / 60}분" + (if (s % 60 != 0) " ${s % 60}초" else "") else "${s}초"
    }

    private fun degrees(d: Double): String {
        val n = d.roundToInt()
        return if (n == 0) "0°" else "${if (n > 0) "+" else ""}$n°"
    }
    private fun fast(mode: String) = Regex("HIGH|CONTINUOUS_2|FAST").containsMatchIn(mode)

    private class Point<T>(val wall: Long, val v: T)

    /** The value of a state signal at [wall] and since when it held. */
    private class State<T>(val v: T, val since: Long)

    private fun <T, K> stateAt(list: List<Point<T>>, wall: Long, key: (T) -> K): State<T>? {
        var cur: T? = null
        var curKey: K? = null
        var since = 0L
        var seen = false
        for (r in list) {
            if (r.wall > wall) break
            val k = key(r.v)
            if (!seen || curKey != k) {
                cur = r.v
                curKey = k
                since = r.wall
                seen = true
            }
        }
        @Suppress("UNCHECKED_CAST")
        return if (seen) State(cur as T, since) else null
    }

    /** All readings, in the order the phone shows them; [causeSeq] is the detection, [context] the packet's. */
    fun of(events: List<DriveEvent>, causeSeq: Long, context: DrivingContext = DrivingContext()): List<Reading> {
        if (events.isEmpty()) return emptyList()
        val cause = events.lastOrNull { it.seq <= causeSeq } ?: events.first()
        val causeWall = cause.entry.at.wallMillis
        val start = events.first().entry.at.wallMillis
        val out = mutableListOf<Reading>()
        fun add(text: String, note: String? = null, weight: Int = 1) { out += Reading(text, note, weight) }
        // The states the window started with (written when the capsule opened) count as signals just before its start.
        // Steering is left out: a reading compares its angles over time, and a carried angle has no time of its own.
        val carried = events.mapNotNull { e -> (e.entry as? ChainEntry.Carried)?.signal?.takeIf { it !is VehicleSignal.SteeringAngle }?.let { start - 1 to it } }
        val signals = carried + events.mapNotNull { e -> (e.entry as? ChainEntry.Signal)?.let { it.at.wallMillis to it.sample.signal } }
        fun <T> series(pick: (VehicleSignal) -> T?): List<Point<T>> = signals.mapNotNull { (w, s) -> pick(s)?.let { Point(w, it) } }

        // Speed: ten seconds before, the start of slowing down, and at the moment.
        val speed = series { (it as? VehicleSignal.Speed)?.metersPerSecond?.toDouble() }
        fun at(w: Long): Point<Double>? = speed.takeWhile { it.wall <= w }.lastOrNull()
        val now = at(causeWall)
        val before = at(causeWall - 10_000)
        if (now != null) {
            val v = kmh(now.v)
            // Back from the moment while the speed was falling: a repeated value inside the fall (samples arrive
            // faster than the speed changes) does not end it; rising, or holding for over a second, does.
            var slowFrom: Point<Double>? = null
            var flatMs = 0L
            for (i in speed.indices.reversed()) {
                if (i == 0) break
                if (speed[i].wall > causeWall) continue
                val earlier = speed[i - 1]
                val later = speed[i]
                if (earlier.v > later.v + 0.05) { slowFrom = earlier; flatMs = 0 }
                else if (slowFrom == null) continue
                else if (earlier.v < later.v - 0.05) break
                else { flatMs += later.wall - earlier.wall; if (flatMs > 1_000) break }
            }
            // The fastest in the ten seconds before, when it is clearly above both ends (sped up, then slowed down).
            val top = speed.filter { it.wall in (causeWall - 10_000)..causeWall }.fold<Point<Double>, Point<Double>?>(null) { a, r -> if (a == null || r.v > a.v) r else a }
            val peaked = top != null && before != null && kmh(top.v) - maxOf(kmh(before.v), v) >= 3
            if (before != null && (abs(kmh(before.v) - v) >= 3 || peaked)) {
                add(
                    "감지 10초 전에는 시속 ${f0(kmh(before.v))} km였고, " +
                        (if (peaked) "감지 ${sec(causeWall - top!!.wall)}초 전 최고 시속 ${f0(kmh(top.v))} km까지 올랐다가, " else "") +
                        "감지 순간에는 시속 ${f0(v)} km였습니다." +
                        (if (slowFrom != null && kmh(slowFrom.v) - v >= 3) " 감지 ${sec(causeWall - slowFrom.wall)}초 전부터 속도가 줄기 시작했습니다." else ""),
                    weight = 3,
                )
            } else {
                add(if (v < 1) "감지 순간 차는 멈춰 있었습니다 (속도 0 km/h)." else "감지 순간 시속 ${f0(v)} km로 달리고 있었습니다 (10초 전과 거의 같은 속도).", weight = 2)
            }
            speed.firstOrNull { it.wall > causeWall && it.v < 0.3 }?.let { add("감지 ${sec(it.wall - causeWall)}초 뒤 차가 멈췄습니다.", weight = 1) }
        }

        // Brake.
        val brake = series { (it as? VehicleSignal.BrakePedal)?.pressed }
        val b = stateAt(brake, causeWall) { it }
        val pressedBefore = brake.filter { it.v && it.wall <= causeWall && it.wall >= causeWall - 10_000 }
        when {
            b != null && b.v -> add(
                if (b.since < start) "브레이크는 감지 ${ago(causeWall - start)} 전에 이미 밟고 있었고, 감지 순간에도 밟고 있었습니다."
                else "브레이크는 감지 ${sec(causeWall - b.since)}초 전에 밟았고, 감지 순간에도 밟고 있었습니다.",
                weight = 3,
            )
            pressedBefore.isNotEmpty() -> add("감지 전 10초 사이에 브레이크를 ${pressedBefore.size}번 밟았고, 감지 순간에는 떼고 있었습니다.", weight = 2)
            brake.isNotEmpty() -> add("감지 전 10초 동안 브레이크를 밟은 기록이 없습니다.", weight = 2)
        }

        // Steering: the fastest turn of the wheel before the moment.
        val steer = series { (it as? VehicleSignal.SteeringAngle)?.degrees?.toDouble() }.filter { it.wall in (causeWall - 10_000)..(causeWall + 1_000) }
        var peakRate = -1.0
        var peakWall = 0L
        var peakAngle = 0.0
        for (i in 1 until steer.size) {
            val dt = (steer[i].wall - steer[i - 1].wall) / 1000.0
            if (dt <= 0) continue
            val rate = abs(steer[i].v - steer[i - 1].v) / dt
            if (rate > peakRate) { peakRate = rate; peakWall = steer[i].wall; peakAngle = steer[i].v }
        }
        if (peakRate >= 90) {
            add(
                "감지 ${sec(causeWall - peakWall)}초 ${if (peakWall <= causeWall) "전" else "뒤"} 핸들을 빠르게 돌렸습니다 (초당 ${f0(peakRate)}°, 그때 조향각 ${degrees(peakAngle)}).",
                "평소 운전에서 핸들을 초당 90° 넘게 돌리는 일은 드뭅니다. 피하려고 한 움직임일 가능성이 높습니다.",
                weight = 3,
            )
        } else if (steer.isNotEmpty()) {
            add("감지 전 10초 동안 핸들을 크게 돌린 기록이 없습니다.")
        }

        // Turn signal.
        val turn = stateAt(series { (it as? VehicleSignal.TurnSignal)?.state?.name }, causeWall) { it }
        if (turn != null && turn.v != "OFF") {
            val since = if (turn.since < start) "감지 ${ago(causeWall - start)} 전에 이미" else "감지 ${sec(causeWall - turn.since)}초 전부터"
            add("방향지시등(${if (turn.v == "LEFT") "왼쪽" else "오른쪽"})은 $since 켜져 있었습니다.", weight = 3)
        } else if (turn != null) {
            add("감지 순간 방향지시등은 꺼져 있었습니다.")
        }

        // Gear.
        stateAt(series { (it as? VehicleSignal.Gear)?.position?.name }, causeWall) { it }?.let { add("기어는 ${GEAR[it.v] ?: it.v}였습니다.") }

        // The road: speed limit and caution areas the navigation was announcing.
        val limit = context.speedLimitKmh
        if (limit != null && limit > 0 && now != null) {
            val over = kmh(now.v) - limit
            add(
                "내비게이션이 알려 준 이 도로의 제한속도는 시속 $limit km였고, 감지 순간 속도는 ${if (over > 0) "제한속도보다 ${f0(over)} km/h 빨랐습니다" else "제한속도 이내였습니다"}.",
                weight = if (over > 0) 3 else 1,
            )
        }
        if (context.roadAlerts.isNotEmpty()) {
            val names = context.roadAlerts.joinToString(", ") { (_, v) ->
                when {
                    "SCHOOL_ZONE" in v -> "어린이보호구역"
                    "SPEED" in v -> "과속 단속 구간"
                    "TRAFFIC_LIGHT" in v -> "신호 단속 구간"
                    else -> v
                }
            }
            add("그때 내비게이션은 ${names}을(를) 안내하고 있었습니다.", weight = 2)
        }

        // Weather, from the wipers: how long they had been on.
        val wipers = signals.mapNotNull { (w, s) -> (s as? VehicleSignal.Wiper)?.takeIf { it.area != "REAR_WINDSHIELD" }?.let { Point(w, it.mode) } }
        val wiper = stateAt(wipers, causeWall) { it != "OFF" }
        if (wiper != null && wiper.v != "OFF") {
            val since = if (wiper.since <= start + 1_000) null else wiper.since
            val held = causeWall - (since ?: start)
            val how = if (fast(wiper.v)) "빠르게" else if (Regex("INTERMITTENT|AUTO").containsMatchIn(wiper.v)) "간헐적으로" else ""
            if (held >= 60_000 || since == null) {
                add(
                    "앞 유리 와이퍼가 감지 전 ${if (since == null) "최소 " else ""}${dur(held)} 동안 ${if (how.isNotEmpty()) "$how " else ""}작동하고 있었습니다.",
                    "와이퍼가 오래 작동했다는 것은 비나 눈이 오고 있었을 가능성이 높다는 뜻입니다${if (fast(wiper.v)) " (빠른 속도: 비가 많이 왔을 가능성)" else ""}. 노면이 젖어 제동 거리가 길어질 수 있습니다.",
                    weight = 3,
                )
            } else {
                add("앞 유리 와이퍼를 감지 ${dur(held)} 전에 켰습니다.", "잠깐 켠 와이퍼는 비 때문일 수도, 유리를 닦기 위해서일 수도 있습니다.", weight = 2)
            }
        } else if (wipers.isNotEmpty()) {
            add("감지 순간 와이퍼는 꺼져 있었습니다.", "비나 눈은 오지 않았을 가능성이 높습니다.")
        }

        // Light.
        fun lamps(lamp: Lamp) = series { s -> (s as? VehicleSignal.Light)?.takeIf { it.lamp == lamp }?.on }
        val low = stateAt(lamps(Lamp.LOW_BEAM), causeWall) { it }
        if (low != null && low.v) add("전조등이 켜져 있었습니다.", "해가 진 뒤였거나 터널·비 등으로 어두웠을 가능성이 있습니다.")
        else if (low != null) add("전조등은 꺼져 있었습니다.")
        lamps(Lamp.HIGH_BEAM).firstOrNull { it.v && it.wall <= causeWall && it.wall >= causeWall - 10_000 }
            ?.let { add("감지 ${sec(causeWall - it.wall)}초 전 상향등을 켰습니다.", "상대에게 보내는 경고 신호였을 가능성이 있습니다.", weight = 3) }
        lamps(Lamp.HAZARD).firstOrNull { it.v && it.wall > causeWall }?.let { add("감지 ${sec(it.wall - causeWall)}초 뒤 비상등을 켰습니다.", weight = 2) }

        // People: belts and seats at the moment.
        val belts = LinkedHashMap<String, Boolean>()
        val seats = LinkedHashMap<String, Boolean>()
        for ((w, s) in signals) {
            if (w > causeWall) continue
            if (s is VehicleSignal.SeatBelt) belts[s.seat] = s.buckled
            if (s is VehicleSignal.SeatOccupancy) seats[s.seat] = s.occupied
        }
        belts["ROW_1_LEFT"]?.let { add(if (it) "운전석 안전벨트를 매고 있었습니다." else "운전석 안전벨트를 매지 않은 것으로 기록되어 있습니다.", weight = if (it) 1 else 2) }
        val taken = seats.filter { it.value }.keys.sortedBy { SEAT_ORDER.indexOf(it).let { i -> if (i < 0) SEAT_ORDER.size else i } }.map(::area)
        if (taken.isNotEmpty()) add("사람이 앉아 있던 좌석: ${taken.joinToString(", ")}.")

        // Cruise control.
        stateAt(series { (it as? VehicleSignal.CruiseControl)?.state?.name }, causeWall) { it }
            ?.takeIf { it.v == "ACTIVATED" }?.let { add("크루즈 컨트롤이 작동 중이었습니다.", weight = 2) }

        // Right after: doors opening (someone got out).
        signals.firstOrNull { (w, s) -> s is VehicleSignal.Door && s.open && w > causeWall }?.let { (w, s) ->
            add("감지 ${sec(w - causeWall)}초 뒤 ${area((s as VehicleSignal.Door).area)} 문이 열렸습니다.", "사고 직후 차에서 내렸을 가능성이 높습니다.", weight = 2)
        }
        return out
    }

    /** The [n] most telling readings, in their original order (the car's short summary). */
    fun top(readings: List<Reading>, n: Int = 3): List<Reading> =
        readings.withIndex().sortedWith(compareBy({ -it.value.weight }, { it.index })).take(n).sortedBy { it.index }.map { it.value }
}
