package com.americankimchilabs.whitebox.domain

import kotlin.math.abs

/** A value from [atMs] on, relative to the cause instant (negative = before). */
data class LaneStep<T>(val atMs: Long, val value: T)

/** One continuous reading relative to the cause instant. */
data class LanePoint(val atMs: Long, val value: Float)

/**
 * What the driver did before the cause instant: [beforeMs] is how long before it (≥ 0). Null [beforeMs] means the
 * control did not change within the capsule; the state then held throughout.
 */
sealed interface ControlLine {
    data class Brake(val pressed: Boolean, val beforeMs: Long?) : ControlLine
    data class Turn(val state: TurnSignalState, val beforeMs: Long?) : ControlLine
    data class Gear(val position: GearPosition, val beforeMs: Long?) : ControlLine

    /** The largest steering angle (by magnitude) in the lanes' pre-window. */
    data class SteeringPeak(val degrees: Float, val beforeMs: Long) : ControlLine

    /** High beam switched on and off within a moment: a flash, often a warning to another driver. */
    data class HighBeamFlash(val beforeMs: Long) : ControlLine
}

/** The driving context at the cause instant, as last recorded before it. Absent maps = not reported by this car. */
data class DrivingContext(
    val seatBelts: Map<String, Boolean> = emptyMap(),
    val occupancy: Map<String, Boolean> = emptyMap(),
    val doors: Map<String, Boolean> = emptyMap(),
    val windows: Map<String, Int> = emptyMap(),
    val lamps: Map<Lamp, Boolean> = emptyMap(),
    val wipers: Map<String, String> = emptyMap(),
    val cruise: CruiseState? = null,
    val cruiseTargetMps: Float? = null,
    val parkingBrake: Boolean? = null,
    /** km/h; null when unknown (the navigation reports 0 then). */
    val speedLimitKmh: Int? = null,
    /** Caution points and enforcement cameras announced and not yet cleared, in announcement order. */
    val roadAlerts: List<Pair<RoadContextType, String>> = emptyList(),
)

/**
 * The evidence packet's time axis: speed, steering, brake, turn signal and gear lanes over [pre] before to [post]
 * after the cause ([EvidenceTimeline.span]), the control lines ("N s before"),
 * and the driving context at the cause. Built from the sealed range alone, so it shows only what the chain proves.
 */
class EvidenceTimeline(
    val speedKmh: List<LanePoint>,
    val steering: List<LanePoint>,
    val brake: List<LaneStep<Boolean>>,
    val turn: List<LaneStep<TurnSignalState>>,
    val gear: List<LaneStep<GearPosition>>,
    val lines: List<ControlLine>,
    val context: DrivingContext,
    /** The part of the window the sealed range covers (a capsule sealed at the cause has nothing after it). */
    val startMs: Long,
    val endMs: Long,
    /** The lanes' span: this long before the cause to this long after it. */
    val pre: Long = PRE_MS,
    val post: Long = POST_MS,
) {
    companion object {
        const val PRE_MS = 10_000L
        const val POST_MS = 5_000L

        /** A record the driver asked for shows everything kept before the request, up to this long. */
        const val REQUEST_PRE_MAX_MS = 120_000L

        /**
         * The lanes' span for a record. A detection is read around its instant ([PRE_MS] before to [POST_MS] after).
         * A record saved on the driver's word ([request]) is about what came before the request, so its lanes reach
         * back to the first kept record: whole seconds, at least [PRE_MS], at most [REQUEST_PRE_MAX_MS].
         */
        fun span(events: List<DriveEvent>, causeSeq: Long, request: Boolean): Pair<Long, Long> {
            if (!request || events.isEmpty()) return PRE_MS to POST_MS
            val cause = events.lastOrNull { it.seq <= causeSeq } ?: events.first()
            val backMs = (cause.entry.at.elapsedNanos - events.first().entry.at.elapsedNanos) / 1_000_000
            return ((backMs + 999) / 1000 * 1000).coerceIn(PRE_MS, REQUEST_PRE_MAX_MS) to POST_MS
        }

        /** On and off again within this long counts as a flash. */
        const val FLASH_MAX_MS = 1_500L

        /** Sources re-send current values when recording starts; a first report this soon after is not a change. */
        const val SUBSCRIBE_MS = 3_000L

        /**
         * [events] is the sealed range in chain order; [causeSeq] the entry the capsule's trigger points at. Step
         * lanes start with the state carried into the window (at −[PRE_MS]) when one was recorded before it.
         */
        fun build(events: List<DriveEvent>, causeSeq: Long, pre: Long = PRE_MS, post: Long = POST_MS): EvidenceTimeline {
            val cause = events.lastOrNull { it.seq <= causeSeq } ?: events.first()
            val causeNs = cause.entry.at.elapsedNanos
            fun rel(e: DriveEvent) = (e.entry.at.elapsedNanos - causeNs) / 1_000_000
            val inLanes = { ms: Long -> ms in -pre..post }

            val speed = mutableListOf<LanePoint>()
            val steering = mutableListOf<LanePoint>()
            val brake = StepLane<Boolean>(pre, post)
            val turn = StepLane<TurnSignalState>(pre, post)
            val gear = StepLane<GearPosition>(pre, post)
            val brakeChange = LastChange<Boolean>()
            val turnChange = LastChange<TurnSignalState>()
            val gearChange = LastChange<GearPosition>()
            val highBeam = mutableListOf<LaneStep<Boolean>>()
            var context = DrivingContext()
            var startedMs: Long? = null
            // The last continuous value before the lanes begin: it still held when they did.
            var speedBefore: Float? = null
            var steeringBefore: Float? = null

            fun take(s: VehicleSignal, ms: Long, beforeCause: Boolean, initial: Boolean) {
                when (s) {
                    is VehicleSignal.Speed -> if (inLanes(ms)) speed += LanePoint(ms, s.metersPerSecond * 3.6f) else if (ms < -pre) speedBefore = s.metersPerSecond * 3.6f
                    is VehicleSignal.SteeringAngle -> if (inLanes(ms)) steering += LanePoint(ms, s.degrees) else if (ms < -pre) steeringBefore = s.degrees
                    is VehicleSignal.BrakePedal -> {
                        brake.add(ms, s.pressed)
                        if (beforeCause) brakeChange.report(ms, s.pressed, initial)
                    }
                    is VehicleSignal.TurnSignal -> {
                        turn.add(ms, s.state)
                        if (beforeCause) turnChange.report(ms, s.state, initial)
                    }
                    is VehicleSignal.Gear -> {
                        gear.add(ms, s.position)
                        if (beforeCause) gearChange.report(ms, s.position, initial)
                    }
                    else -> if (beforeCause) {
                        if (s is VehicleSignal.Light && s.lamp == Lamp.HIGH_BEAM && highBeam.lastOrNull()?.value != s.on) highBeam += LaneStep(ms, s.on)
                        context = context.with(s)
                    }
                }
            }

            // The states the window started with come first (written when the capsule opened, they stand for the
            // window's start): held throughout unless the window changes them.
            for (e in events) (e.entry as? ChainEntry.Carried)?.let { take(it.signal, -pre - 1, beforeCause = true, initial = true) }
            for (e in events) {
                val ms = rel(e)
                if ((e.entry as? ChainEntry.Recorder)?.event is RecorderEvent.RecordingStarted) startedMs = ms
                val s = (e.entry as? ChainEntry.Signal)?.sample?.signal ?: continue
                take(s, ms, beforeCause = e.seq <= cause.seq, initial = startedMs?.let { ms - it <= SUBSCRIBE_MS } ?: false)
            }

            val lines = buildList {
                brakeChange.value?.let { add(ControlLine.Brake(it, brakeChange.beforeMs)) }
                turnChange.value?.let { add(ControlLine.Turn(it, turnChange.beforeMs)) }
                gearChange.value?.let { add(ControlLine.Gear(it, gearChange.beforeMs)) }
                steering.filter { it.atMs <= 0 }.maxByOrNull { abs(it.value) }
                    ?.takeIf { it.value != 0f }
                    ?.let { add(ControlLine.SteeringPeak(it.value, -it.atMs)) }
                highBeam.zipWithNext()
                    .lastOrNull { (on, off) -> on.value && !off.value && off.atMs - on.atMs <= FLASH_MAX_MS && on.atMs >= -pre }
                    ?.let { (on, _) -> add(ControlLine.HighBeamFlash(-on.atMs)) }
            }
            val startMs = rel(events.first()).coerceIn(-pre, 0)
            val endMs = rel(events.last()).coerceIn(0, post)
            return EvidenceTimeline(
                held(speed, speedBefore, endMs, pre), held(steering, steeringBefore, endMs, pre), brake.steps(), turn.steps(), gear.steps(), lines, context,
                startMs = startMs, endMs = endMs, pre = pre, post = post,
            )
        }

        /**
         * A continuous lane across the whole kept span: values are written when they change, so the one before the lanes
         * begin still held at the lanes' start, and the last one holds to [endMs].
         */
        private fun held(points: List<LanePoint>, before: Float?, endMs: Long, pre: Long): List<LanePoint> = buildList {
            if (before != null && (points.firstOrNull()?.atMs ?: Long.MAX_VALUE) > -pre) add(LanePoint(-pre, before))
            addAll(points)
            lastOrNull()?.let { if (it.atMs < endMs) add(LanePoint(endMs, it.value)) }
        }
    }
}

/**
 * A control's state at the cause and how long before it was set. A first report right after recording started is
 * the source re-sending its current value, so the state counts as held throughout (null [beforeMs]); any other
 * first report in the range is a change from an unrecorded prior state.
 */
private class LastChange<T> {
    var value: T? = null
        private set
    var beforeMs: Long? = null
        private set

    fun report(ms: Long, v: T, initial: Boolean) {
        if (v == value) return
        beforeMs = if (value == null && initial) null else -ms
        value = v
    }
}

/** A step lane clipped to the window, starting with the state carried in from before it. */
private class StepLane<T>(private val pre: Long, private val post: Long) {
    private var carried: T? = null
    private val steps = mutableListOf<LaneStep<T>>()

    fun add(ms: Long, v: T) {
        when {
            ms < -pre -> carried = v
            ms > post -> Unit
            steps.lastOrNull()?.value == v -> Unit
            steps.isEmpty() && carried == v -> Unit
            else -> steps += LaneStep(ms, v)
        }
    }

    fun steps(): List<LaneStep<T>> {
        val start = carried ?: return steps
        return listOf(LaneStep<T>(-pre, start)) + steps
    }
}

private fun DrivingContext.with(s: VehicleSignal): DrivingContext = when (s) {
    is VehicleSignal.SeatBelt -> copy(seatBelts = seatBelts + (s.seat to s.buckled))
    is VehicleSignal.SeatOccupancy -> copy(occupancy = occupancy + (s.seat to s.occupied))
    is VehicleSignal.Door -> copy(doors = doors + (s.area to s.open))
    is VehicleSignal.Window -> copy(windows = windows + (s.area to s.position))
    is VehicleSignal.Light -> copy(lamps = lamps + (s.lamp to s.on))
    is VehicleSignal.Wiper -> copy(wipers = wipers + (s.area to s.mode))
    is VehicleSignal.CruiseControl -> copy(cruise = s.state)
    is VehicleSignal.CruiseTargetSpeed -> copy(cruiseTargetMps = s.metersPerSecond)
    is VehicleSignal.ParkingBrake -> copy(parkingBrake = s.engaged)
    is VehicleSignal.RoadContext -> when {
        // Handover mode's inputs (enforcement distance, route state), not context of the moment.
        s.type == RoadContextType.SPEED_ENFORCEMENT || s.type == RoadContextType.ROUTE -> this
        s.type == RoadContextType.SPEED_LIMIT -> copy(speedLimitKmh = s.value.toIntOrNull()?.takeIf { it > 0 })
        s.value == "NONE" -> copy(roadAlerts = roadAlerts.filter { it.first != s.type })
        else -> copy(roadAlerts = roadAlerts + (s.type to s.value))
    }
    else -> this
}
