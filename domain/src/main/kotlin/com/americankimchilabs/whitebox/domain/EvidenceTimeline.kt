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
 * The evidence packet's time axis: speed, steering, brake, turn signal and gear lanes over
 * [EvidenceTimeline.PRE_MS] before to [EvidenceTimeline.POST_MS] after the cause, the control lines ("N s before"),
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
) {
    companion object {
        const val PRE_MS = 10_000L
        const val POST_MS = 5_000L

        /** On and off again within this long counts as a flash. */
        const val FLASH_MAX_MS = 1_500L

        /** Sources re-send current values when recording starts; a first report this soon after is not a change. */
        const val SUBSCRIBE_MS = 3_000L

        /**
         * [events] is the sealed range in chain order; [causeSeq] the entry the capsule's trigger points at. Step
         * lanes start with the state carried into the window (at −[PRE_MS]) when one was recorded before it.
         */
        fun build(events: List<DriveEvent>, causeSeq: Long): EvidenceTimeline {
            val cause = events.lastOrNull { it.seq <= causeSeq } ?: events.first()
            val causeNs = cause.entry.at.elapsedNanos
            fun rel(e: DriveEvent) = (e.entry.at.elapsedNanos - causeNs) / 1_000_000
            val inLanes = { ms: Long -> ms in -PRE_MS..POST_MS }

            val speed = mutableListOf<LanePoint>()
            val steering = mutableListOf<LanePoint>()
            val brake = StepLane<Boolean>()
            val turn = StepLane<TurnSignalState>()
            val gear = StepLane<GearPosition>()
            val brakeChange = LastChange<Boolean>()
            val turnChange = LastChange<TurnSignalState>()
            val gearChange = LastChange<GearPosition>()
            val highBeam = mutableListOf<LaneStep<Boolean>>()
            var context = DrivingContext()
            var startedMs: Long? = null

            for (e in events) {
                val ms = rel(e)
                if ((e.entry as? ChainEntry.Recorder)?.event is RecorderEvent.RecordingStarted) startedMs = ms
                val s = (e.entry as? ChainEntry.Signal)?.sample?.signal ?: continue
                val beforeCause = e.seq <= cause.seq
                val initial = startedMs?.let { ms - it <= SUBSCRIBE_MS } ?: false
                when (s) {
                    is VehicleSignal.Speed -> if (inLanes(ms)) speed += LanePoint(ms, s.metersPerSecond * 3.6f)
                    is VehicleSignal.SteeringAngle -> if (inLanes(ms)) steering += LanePoint(ms, s.degrees)
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

            val lines = buildList {
                brakeChange.value?.let { add(ControlLine.Brake(it, brakeChange.beforeMs)) }
                turnChange.value?.let { add(ControlLine.Turn(it, turnChange.beforeMs)) }
                gearChange.value?.let { add(ControlLine.Gear(it, gearChange.beforeMs)) }
                steering.filter { it.atMs <= 0 }.maxByOrNull { abs(it.value) }
                    ?.takeIf { it.value != 0f }
                    ?.let { add(ControlLine.SteeringPeak(it.value, -it.atMs)) }
                highBeam.zipWithNext()
                    .lastOrNull { (on, off) -> on.value && !off.value && off.atMs - on.atMs <= FLASH_MAX_MS && on.atMs >= -PRE_MS }
                    ?.let { (on, _) -> add(ControlLine.HighBeamFlash(-on.atMs)) }
            }
            return EvidenceTimeline(
                speed, steering, brake.steps(), turn.steps(), gear.steps(), lines, context,
                startMs = rel(events.first()).coerceIn(-PRE_MS, 0), endMs = rel(events.last()).coerceIn(0, POST_MS),
            )
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
private class StepLane<T> {
    private var carried: T? = null
    private val steps = mutableListOf<LaneStep<T>>()

    fun add(ms: Long, v: T) {
        when {
            ms < -EvidenceTimeline.PRE_MS -> carried = v
            ms > EvidenceTimeline.POST_MS -> Unit
            steps.lastOrNull()?.value == v -> Unit
            steps.isEmpty() && carried == v -> Unit
            else -> steps += LaneStep(ms, v)
        }
    }

    fun steps(): List<LaneStep<T>> {
        val start = carried ?: return steps
        return listOf(LaneStep<T>(-EvidenceTimeline.PRE_MS, start)) + steps
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
        s.type == RoadContextType.SPEED_LIMIT -> copy(speedLimitKmh = s.value.toIntOrNull()?.takeIf { it > 0 })
        s.value == "NONE" -> copy(roadAlerts = roadAlerts.filter { it.first != s.type })
        else -> copy(roadAlerts = roadAlerts + (s.type to s.value))
    }
    else -> this
}
