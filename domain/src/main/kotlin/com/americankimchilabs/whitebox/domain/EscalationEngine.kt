package com.americankimchilabs.whitebox.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** What the engine asks the recorder to do after an entry. */
sealed interface EngineOutput {
    /** Append to the chain (before any seal from the same step, so the seal covers it). */
    data class Append(val event: EscalationEvent) : EngineOutput

    /**
     * Seal [capsuleId] from [firstSeq] up to the chain head at the moment of sealing. The first seal of a capsule is
     * provisional (durability first: the pre-window is sealed at once); the final one comes when the capsule closes.
     */
    data class Seal(val capsuleId: String, val trigger: CapsuleTrigger, val firstSeq: Long, val final: Boolean) : EngineOutput

    /**
     * A near-miss capsule (already sealed) whose car is still driving [Tuning.KEEP_PROMPT_DELAY_S] later: ask
     * "방금 상황을 보관할까요?" by voice. The answer only labels the capsule; silence keeps it.
     */
    data class IncidentPrompt(val capsule: IncidentCapsule) : EngineOutput

    /** Speak [line] (post-crash guidance). */
    data class Say(val line: VoiceLine) : EngineOutput
}

/**
 * NORMAL → ALERT → TRIGGERED (constants and rationale in [Tuning]).
 *
 * Every dangerous pattern opens a capsule at once, without asking: a near-miss pattern raises ALERT, a crash
 * signature ([CapsuleTrigger.crash]) raises TRIGGERED. A graver trigger inside an open capsule always counts and
 * takes it over (a near-miss that became a crash, a crash whose airbag then fired). Near-miss capsules close at
 * stillness or [Tuning.CAPSULE_POST_WINDOW_S] after their last trigger; crash ones only at stillness (or
 * [Tuning.CAPSULE_MAX_OPEN_S]).
 *
 * Pure and deterministic: fed the same chain it makes the same decisions, so a replay reproduces a capsule exactly.
 * All speed conditions use |speed| (AAOS reports reverse as negative) and the VHAL source timestamp when present.
 */
class EscalationEngine {

    private class SpeedPoint(val tNs: Long, val kmh: Float, val seq: Long)
    private class Mark(val seq: Long, val tNs: Long)
    private class OpenCapsule(val id: String, var trigger: CapsuleTrigger, val firstSeq: Long, val openedNs: Long, var lastTriggerNs: Long) {
        var stoppedSinceOpen = false
        var promptDecided = false
    }
    private class SpikeCandidate(val seq: Long, val kmh: Float, val dropKmh: Float)
    private class SteerPoint(val tNs: Long, val degrees: Float)

    /** Estimated heading (°), heading rate (°/s), position (m, in the frame where tracking began) and speed. */
    private class HeadPoint(val tNs: Long, val headingDeg: Float, val yawDps: Float, val x: Float, val y: Float, val kmh: Float) {
        /** How far [to] lies to the side of this point's direction of travel (m). */
        fun sideways(to: HeadPoint): Float {
            val h = Math.toRadians(headingDeg.toDouble())
            return ((to.y - y) * cos(h) - (to.x - x) * sin(h)).toFloat()
        }
    }

    var level = EscalationLevel.NORMAL
        private set

    private val speeds = ArrayDeque<SpeedPoint>()
    private val timeline = ArrayDeque<Mark>()
    private var lastSpeedKmh: Float? = null
    private var lastSpeedReceivedNs = 0L
    private var brakeOn = false
    private var brakeOnSinceNs = 0L
    private var brakeReleasedNs: Long? = null
    private val brakePresses = ArrayDeque<Long>()
    private var riseFired = false
    private var noDecelFired = false
    private var lastGradePct: Float? = null
    private var lastGradeNs = 0L
    private var holdMinGradePct: Float? = null
    private var holdGradeSinceNs: Long? = null
    private var downhillCaution = false
    private var lurchFired = false
    private var steer: SteerPoint? = null
    private var steerOver = false
    private val heading = ArrayDeque<HeadPoint>()
    private var wheelDeg: Float? = null
    private var evasiveNs: Long? = null
    private var turnSignal: TurnSignalState? = null
    private var signalSeenNs: Long? = null
    private var hazard = false
    private var gear: GearPosition? = null
    private var parkingBrake: Boolean? = null
    /** Warning lamps as last reported; a lamp never seen is unknown, so its first report is a baseline, not a change. */
    private val lamps = HashMap<String, TelltaleState>()
    private var lastDangerNs: Long? = null
    private var lastSevereNs: Long? = null
    private var guided = false
    private var stillSinceNs: Long? = null
    private var calmSinceNs: Long? = null
    private var capsule: OpenCapsule? = null
    private var spike: SpikeCandidate? = null

    fun onEvent(event: DriveEvent): List<EngineOutput> {
        val entry = event.entry
        if (entry is ChainEntry.Escalation) return emptyList() // our own markers
        val nowNs = entry.at.elapsedNanos
        val out = mutableListOf<EngineOutput>()

        if (entry is ChainEntry.Recorder && entry.event is RecorderEvent.RecordingStarted) {
            // Never difference across a restart; across a boot the monotonic clock itself restarted.
            speeds.clear()
            spike = null
            steer = null
            heading.clear()
            wheelDeg = null
            lastSpeedKmh = null
            lamps.clear()
            val gap = (entry.event as RecorderEvent.RecordingStarted).gap
            if (gap == null || gap.acrossBoots) timeline.clear()
        }
        timeline.addLast(Mark(event.seq, nowNs))
        while (nowNs - timeline.first().tNs > Tuning.CAPSULE_PRE_WINDOW_S * 1_000_000_000L) timeline.removeFirst()

        if (entry is ChainEntry.Audio && entry.event is AudioEvent.Bookmark) trigger(CapsuleTrigger.MANUAL, event.seq, 0f, nowNs, out)
        if (entry is ChainEntry.Signal) {
            when (val s = entry.sample.signal) {
                is VehicleSignal.BrakePedal -> onBrake(s.pressed, event.seq, nowNs, out)
                is VehicleSignal.AirbagDeployed -> if (s.deployed) trigger(if (s.test) CapsuleTrigger.TEST_AIRBAG else CapsuleTrigger.AIRBAG, event.seq, 0f, nowNs, out)
                is VehicleSignal.Speed -> onSpeed(event.seq, entry.sample, out)
                is VehicleSignal.SteeringAngle -> onSteering(s.degrees, event.seq, entry.sample, out)
                is VehicleSignal.TurnSignal -> {
                    if (s.state != TurnSignalState.OFF || turnSignal.let { it != null && it != TurnSignalState.OFF }) signalSeenNs = nowNs
                    turnSignal = s.state
                }
                is VehicleSignal.Light -> if (s.lamp == Lamp.HAZARD) {
                    if (s.on || hazard) signalSeenNs = nowNs
                    hazard = s.on
                }
                is VehicleSignal.Gear -> {
                    val kmh = currentKmh(nowNs)
                    val severe = lastSevereNs
                    if (s.position == GearPosition.PARK && gear != GearPosition.PARK && !guided && severe != null &&
                        ms(nowNs - severe) <= Tuning.GUIDANCE_WINDOW_S * 1_000L
                    ) {
                        guided = true // once per crash
                        out += EngineOutput.Say(VoiceLine.CRASH_GUIDANCE)
                    }
                    if (s.position == GearPosition.NEUTRAL && gear != GearPosition.NEUTRAL && kmh != null && kmh >= Tuning.NEUTRAL_MIN_SPEED_KMH) {
                        trigger(CapsuleTrigger.SHIFT_TO_NEUTRAL, event.seq, kmh, nowNs, out)
                    }
                    gear = s.position
                }
                is VehicleSignal.ParkingBrake -> {
                    val kmh = currentKmh(nowNs)
                    if (s.engaged && parkingBrake != true && kmh != null && kmh >= Tuning.PARKING_BRAKE_MIN_KMH) {
                        trigger(CapsuleTrigger.PARKING_BRAKE_MOVING, event.seq, kmh, nowNs, out)
                    }
                    parkingBrake = s.engaged
                }
                is VehicleSignal.Telltale -> if (s.name in Tuning.WARNING_LAMPS) {
                    val kmh = currentKmh(nowNs)
                    val before = lamps.put(s.name, s.state)
                    if (before == TelltaleState.OFF && s.state != TelltaleState.OFF && kmh != null && kmh >= Tuning.WARNING_LAMP_MIN_KMH) {
                        trigger(CapsuleTrigger.WARNING_LAMP, event.seq, kmh, nowNs, out)
                    }
                }
                is VehicleSignal.RoadGrade -> {
                    lastGradePct = s.percent
                    lastGradeNs = nowNs
                    if (brakeOn) {
                        holdMinGradePct = minOf(holdMinGradePct ?: s.percent, s.percent)
                        if (holdGradeSinceNs == null) holdGradeSinceNs = nowNs
                    }
                }
                is VehicleSignal.RoadContext -> if (s.type == RoadContextType.CAUTION_POINT) {
                    if (s.value == DOWNHILL_CAUTION) downhillCaution = true else if (s.value == "NONE") downhillCaution = false
                }
                is VehicleSignal.Door -> {
                    val kmh = currentKmh(nowNs)
                    val danger = lastDangerNs
                    if (s.open && kmh != null && kmh < Tuning.STILL_SPEED_KMH && danger != null &&
                        ms(nowNs - danger) <= Tuning.EXIT_DANGER_LOOKBACK_S * 1_000L
                    ) {
                        lastDangerNs = null // once per dangerous moment
                        trigger(CapsuleTrigger.POST_EVENT_EXIT, event.seq, 0f, nowNs, out)
                    }
                }
                else -> Unit
            }
        }
        return out
    }

    private fun onBrake(pressed: Boolean, seq: Long, nowNs: Long, out: MutableList<EngineOutput>) {
        if (brakeOn && !pressed) brakeReleasedNs = nowNs
        if (!brakeOn && pressed) {
            brakeOnSinceNs = nowNs
            riseFired = false
            noDecelFired = false
            // A grade from just before the press covers the start of the hold.
            val fresh = lastGradePct?.takeIf { ms(nowNs - lastGradeNs) <= Tuning.GRADE_STALE_MS }
            holdMinGradePct = fresh
            holdGradeSinceNs = fresh?.let { nowNs }
            val kmh = currentKmh(nowNs)
            if (kmh != null && kmh >= Tuning.BRAKE_PUMP_MIN_KMH) {
                brakePresses.addLast(nowNs)
                while (ms(nowNs - brakePresses.first()) > Tuning.BRAKE_PUMP_WINDOW_MS) brakePresses.removeFirst()
                if (brakePresses.size >= Tuning.BRAKE_PUMP_COUNT) {
                    val count = brakePresses.size
                    brakePresses.clear()
                    trigger(CapsuleTrigger.BRAKE_PUMPING, seq, count.toFloat(), nowNs, out)
                }
            }
        }
        brakeOn = pressed
    }

    private fun onSteering(degrees: Float, seq: Long, sample: SignalSample, out: MutableList<EngineOutput>) {
        val tNs = sample.sourceElapsedNanos ?: sample.receivedAt.elapsedNanos
        val prev = steer
        if (prev != null && tNs > prev.tNs && ms(tNs - prev.tNs) < MIN_STEER_GAP_MS) return // too close to difference
        steer = SteerPoint(tNs, degrees)
        onHeading(degrees, seq, sample.receivedAt.elapsedNanos, out)
        if (prev == null || tNs <= prev.tNs || ms(tNs - prev.tNs) > Tuning.STEER_MAX_SAMPLE_GAP_MS) {
            steerOver = false
            return
        }
        val rate = abs(degrees - prev.degrees) * 1_000_000_000f / (tNs - prev.tNs)
        val wasOver = steerOver
        steerOver = rate >= Tuning.STEER_RATE_DPS
        val kmh = currentKmh(sample.receivedAt.elapsedNanos) ?: return
        if (!steerOver || wasOver || kmh < Tuning.STEER_MIN_SPEED_KMH) return
        val braking = brakeOn || brakeReleasedNs?.let { ms(sample.receivedAt.elapsedNanos - it) <= Tuning.SWERVE_BRAKE_WINDOW_MS } ?: false
        evasiveNs = sample.receivedAt.elapsedNanos
        trigger(if (braking) CapsuleTrigger.BRAKE_SWERVE else CapsuleTrigger.EVASIVE_STEER, seq, rate, sample.receivedAt.elapsedNanos, out)
    }

    /**
     * Sudden lane change without a signal ([Tuning.LANE_YAW_DPS]). The heading is integrated from the steering angle,
     * held between steering samples (the wheel may report only when it moves), and stepped on every steering and
     * speed sample: [degrees] is a new wheel angle, or null on a speed sample. Times are receive times, so the two
     * signals share one clock.
     */
    private fun onHeading(degrees: Float?, seq: Long, tNs: Long, out: MutableList<EngineOutput>) {
        val held = wheelDeg
        if (degrees != null) wheelDeg = degrees
        val kmh = currentKmh(tNs)
        if (held == null || kmh == null || kmh < Tuning.LANE_MIN_SPEED_KMH) {
            heading.clear()
            return
        }
        heading.lastOrNull()?.let { if (tNs < it.tNs || ms(tNs - it.tNs) > Tuning.LANE_MAX_GAP_MS) heading.clear() }
        val heldYaw = yawDps(held, kmh)
        val base = heading.lastOrNull() ?: HeadPoint(tNs, 0f, heldYaw, 0f, 0f, kmh).also { heading.addLast(it) }
        val dtS = (tNs - base.tNs) / 1e9f
        val headingDeg = base.headingDeg + heldYaw * dtS
        val mid = Math.toRadians(((base.headingDeg + headingDeg) / 2).toDouble())
        val x = base.x + kmh / 3.6f * cos(mid).toFloat() * dtS
        val y = base.y + kmh / 3.6f * sin(mid).toFloat() * dtS
        if (dtS > 0f) heading.addLast(HeadPoint(tNs, headingDeg, heldYaw, x, y, kmh)) // the old angle held up to now
        if (degrees != null) heading.addLast(HeadPoint(tNs, headingDeg, yawDps(degrees, kmh), x, y, kmh))
        while (ms(tNs - heading.first().tNs) > Tuning.LANE_WINDOW_MS) heading.removeFirst()
        if (abs(heading.last().yawDps) > Tuning.LANE_STRAIGHT_DPS) return

        // Evaluated once the car has driven straight for LANE_SETTLE_MS after the last sharp heading change. The
        // manoeuvre runs from a settled straight point before a sharp change to the first point of that straight run,
        // ends on (nearly) the heading it started on, and moved the car at least a lane over. Earlier sharp changes
        // in the window (a curve) are skipped.
        val straight = { k: Int -> abs(heading[k].yawDps) <= Tuning.LANE_STRAIGHT_DPS }
        var end = heading.size - 1
        while (end > 0 && straight(end - 1)) end--
        if (ms(tNs - heading[end].tNs) < Tuning.LANE_SETTLE_MS) return
        val settled = { k: Int ->
            straight(k) && (k downTo 0).takeWhile { ms(heading[k].tNs - heading[it].tNs) <= Tuning.LANE_SETTLE_MS }.all(straight)
        }
        val startNs = (0 until end).asSequence()
            .filter { abs(heading[it].yawDps) >= Tuning.LANE_YAW_DPS && (it == 0 || abs(heading[it - 1].yawDps) < Tuning.LANE_YAW_DPS) }
            .mapNotNull { sharp -> (sharp - 1 downTo 0).firstOrNull(settled) }
            .firstOrNull { start ->
                val from = heading[start]
                abs(heading[end].headingDeg - from.headingDeg) <= Tuning.LANE_NET_HEADING_DEG &&
                    abs(from.sideways(heading[end])) >= Tuning.LANE_MIN_SHIFT_M &&
                    (start..end).all { heading[it].kmh >= Tuning.LANE_MIN_SPEED_KMH }
            }
            ?.let { heading[it].tNs } ?: return
        heading.clear() // one manoeuvre, one detection
        // An evasive swerve-and-recover is already its own detection.
        if (evasiveNs?.let { it >= startNs } == true) return
        // Signalled, or the signal state is unknown: not a lane change "without a signal".
        val signalled = turnSignal == null || turnSignal != TurnSignalState.OFF || hazard ||
            signalSeenNs?.let { ms(startNs - it) <= Tuning.LANE_SIGNAL_LOOKBACK_MS } == true
        if (signalled) return
        trigger(CapsuleTrigger.LANE_CHANGE_NO_SIGNAL, seq, kmh, tNs, out)
    }

    /** Kinematic bicycle model: heading rate (°/s) from the steering-wheel angle at [kmh]. */
    private fun yawDps(wheelDeg: Float, kmh: Float): Float {
        val roadWheel = Math.toRadians((wheelDeg / Tuning.STEERING_RATIO).toDouble())
        return Math.toDegrees(kmh / 3.6 * tan(roadWheel) / Tuning.WHEELBASE_M).toFloat()
    }

    private fun onSpeed(seq: Long, sample: SignalSample, out: MutableList<EngineOutput>) {
        val tNs = sample.sourceElapsedNanos ?: sample.receivedAt.elapsedNanos
        val kmh = abs((sample.signal as VehicleSignal.Speed).metersPerSecond) * 3.6f
        lastSpeedKmh = kmh
        lastSpeedReceivedNs = sample.receivedAt.elapsedNanos
        onHeading(null, seq, sample.receivedAt.elapsedNanos, out)
        speeds.lastOrNull()?.let { last ->
            if (tNs <= last.tNs || ms(tNs - last.tNs) > Tuning.TRIGGER_MAX_SAMPLE_GAP_MS) {
                speeds.clear()
                spike = null
            }
        }
        val now = SpeedPoint(tNs, kmh, seq)
        speeds.addLast(now)
        while (ms(tNs - speeds.first().tNs) > SPEED_HISTORY_MS) speeds.removeFirst()

        spike?.let { c ->
            spike = null
            if (kmh <= c.kmh + Tuning.TRIGGER_REBOUND_KMH) trigger(CapsuleTrigger.BRAKED_SPIKE, c.seq, -c.dropKmh, tNs, out)
        }
        evaluateTriggers(now, out)
        if (level == EscalationLevel.NORMAL) evaluateHardBrake(now, out)
        evaluateBrakeRise(now, out)
        evaluateNoDecel(now, out)
        evaluateLurch(now, out)
        if (level == EscalationLevel.ALERT) evaluateCalm(now, out)
        evaluateKeepPrompt(now, out)
        evaluateClose(now, out)
    }

    private fun evaluateTriggers(now: SpeedPoint, out: MutableList<EngineOutput>) {
        val earlier = speeds.filter { it !== now }
        val primary = earlier.firstOrNull { ms(now.tNs - it.tNs) <= Tuning.TRIGGER_DV_WINDOW_MS }
        if (primary != null && primary.kmh - now.kmh >= Tuning.TRIGGER_DV_KMH) {
            return trigger(CapsuleTrigger.DELTA_V, now.seq, now.kmh - primary.kmh, now.tNs, out)
        }
        val s1 = earlier
            .filter { ms(now.tNs - it.tNs) in Tuning.TRIGGER_DV2_SPAN_MIN_MS..Tuning.TRIGGER_DV2_SPAN_MAX_MS }
            .minByOrNull { abs(ms(now.tNs - it.tNs) - Tuning.TRIGGER_DV2_WINDOW_MS) }
        if (s1 != null && s1.kmh - now.kmh >= Tuning.TRIGGER_DV2_KMH) {
            return trigger(CapsuleTrigger.SHORT_DELTA_V, now.seq, now.kmh - s1.kmh, now.tNs, out)
        }
        if (brakeOn) {
            val rise = earlier.filter { ms(now.tNs - it.tNs) <= Tuning.TRIGGER_SHOVE_SPAN_MAX_MS && steadyBefore(it) }
                .maxOfOrNull { now.kmh - it.kmh }
            if (rise != null && rise >= Tuning.TRIGGER_SHOVE_DV_KMH) {
                return trigger(CapsuleTrigger.REAR_SHOVE, now.seq, rise, now.tNs, out)
            }
        }
        if (brakeRecentlyOn(now.tNs)) {
            val drop = earlier.filter { ms(now.tNs - it.tNs) <= Tuning.TRIGGER_BRAKED_SPAN_MAX_MS }
                .maxOfOrNull { it.kmh - now.kmh }
            if (drop != null && drop >= Tuning.TRIGGER_BRAKED_DV_KMH) spike = SpikeCandidate(now.seq, now.kmh, drop)
        }
    }

    /** Brake on and slowing hard: the ALERT precursor, kept as a near-miss capsule. */
    private fun evaluateHardBrake(now: SpeedPoint, out: MutableList<EngineOutput>) {
        if (!brakeOn) return
        val oldest = speeds.firstOrNull { ms(now.tNs - it.tNs) <= Tuning.ALERT_DECEL_WINDOW_MS } ?: return
        val spanMs = ms(now.tNs - oldest.tNs)
        if (spanMs < Tuning.ALERT_DECEL_WINDOW_MS * 3 / 5 || oldest.kmh < Tuning.ALERT_MIN_SPEED_KMH) return
        if ((oldest.kmh - now.kmh) * 1000f / spanMs < Tuning.ALERT_DECEL_KMHPS) return
        trigger(CapsuleTrigger.HARD_BRAKE, now.seq, now.kmh - oldest.kmh, now.tNs, out)
    }

    /**
     * 급발진 signature: over the last [Tuning.BRAKE_RISE_HELD_MS] of a continuous brake hold, speed gained at least
     * [Tuning.BRAKE_RISE_KMH] and rose in every second of it. Fires once per hold.
     */
    private fun evaluateBrakeRise(now: SpeedPoint, out: MutableList<EngineOutput>) {
        if (!brakeOn || riseFired || ms(now.tNs - brakeOnSinceNs) < Tuning.BRAKE_RISE_HELD_MS) return
        val seconds = Tuning.BRAKE_RISE_HELD_MS / 1_000
        // Speed at each whole second back from now; the history must reach the start without a gap.
        val marks = (seconds downTo 0).map { k ->
            val target = now.tNs - k * 1_000_000_000L
            speeds.lastOrNull { it.tNs <= target }?.takeIf { ms(target - it.tNs) <= SAMPLE_SLACK_MS } ?: return
        }
        if (marks.first().tNs < brakeOnSinceNs - SAMPLE_SLACK_MS * 1_000_000L) return
        if (marks.zipWithNext().any { (a, b) -> b.kmh - a.kmh < Tuning.BRAKE_RISE_STEP_KMH }) return
        val gain = now.kmh - marks.first().kmh
        if (gain < Tuning.BRAKE_RISE_KMH) return
        riseFired = true
        trigger(CapsuleTrigger.BRAKE_SPEED_RISE, now.seq, gain, now.tNs, out)
    }

    /** Brake held [Tuning.BRAKE_NO_DECEL_HELD_MS] at speed without slowing, on a road known not to be downhill. */
    private fun evaluateNoDecel(now: SpeedPoint, out: MutableList<EngineOutput>) {
        if (!brakeOn || noDecelFired || downhillCaution || ms(now.tNs - brakeOnSinceNs) < Tuning.BRAKE_NO_DECEL_HELD_MS) return
        // Grade must cover the hold from its start up to now, and never be downhill.
        val since = holdGradeSinceNs ?: return
        val minGrade = holdMinGradePct ?: return
        if (ms(since - brakeOnSinceNs) > Tuning.GRADE_STALE_MS || ms(now.tNs - lastGradeNs) > Tuning.GRADE_STALE_MS) return
        if (minGrade < -Tuning.BRAKE_NO_DECEL_MIN_GRADE_PCT) return
        val sinceOn = speeds.filter { it.tNs >= brakeOnSinceNs }
        val first = sinceOn.firstOrNull() ?: return
        if (ms(now.tNs - first.tNs) < Tuning.BRAKE_NO_DECEL_HELD_MS - SAMPLE_SLACK_MS) return // history broken by a gap
        if (sinceOn.any { it.kmh < Tuning.BRAKE_NO_DECEL_MIN_KMH }) return
        if (first.kmh - now.kmh > Tuning.BRAKE_NO_DECEL_MAX_DROP_KMH) return
        noDecelFired = true
        trigger(CapsuleTrigger.BRAKE_NO_DECEL, now.seq, now.kmh - first.kmh, now.tNs, out)
    }

    /** From standstill (at least [Tuning.LURCH_STILL_MS]) to [Tuning.LURCH_KMH] within [Tuning.LURCH_WINDOW_MS]. */
    private fun evaluateLurch(now: SpeedPoint, out: MutableList<EngineOutput>) {
        if (now.kmh < Tuning.STILL_SPEED_KMH) {
            lurchFired = false
            return
        }
        if (lurchFired || now.kmh < Tuning.LURCH_KMH) return
        val lastStill = speeds.lastOrNull { it.kmh < Tuning.STILL_SPEED_KMH && ms(now.tNs - it.tNs) <= Tuning.LURCH_WINDOW_MS } ?: return
        val runStartNs = lastStill.tNs - Tuning.LURCH_STILL_MS * 1_000_000L
        if (speeds.first().tNs > runStartNs + SAMPLE_SLACK_MS * 1_000_000L) return // history does not reach back
        if (speeds.any { it.tNs in runStartNs..lastStill.tNs && it.kmh >= Tuning.STILL_SPEED_KMH }) return
        lurchFired = true
        trigger(CapsuleTrigger.LURCH, now.seq, now.kmh - lastStill.kmh, now.tNs, out)
    }

    /** ALERT drops back to NORMAL after calm driving; the near-miss capsule stays open until it closes. */
    private fun evaluateCalm(now: SpeedPoint, out: MutableList<EngineOutput>) {
        val calm = !brakeOn && now.kmh >= Tuning.STILL_SPEED_KMH
        calmSinceNs = if (calm) calmSinceNs ?: now.tNs else null
        if (calmSinceNs != null && ms(now.tNs - calmSinceNs!!) >= Tuning.ALERT_CLEAR_S * 1_000L) {
            setLevel(EscalationLevel.NORMAL, now.seq, out)
        }
    }

    /** Asked once, [Tuning.KEEP_PROMPT_DELAY_S] after a near-miss, only if the car never stopped and is still driving. */
    private fun evaluateKeepPrompt(now: SpeedPoint, out: MutableList<EngineOutput>) {
        val open = capsule ?: return
        if (open.promptDecided) return
        if (now.kmh < Tuning.STILL_SPEED_KMH) open.stoppedSinceOpen = true
        if (ms(now.tNs - open.openedNs) < Tuning.KEEP_PROMPT_DELAY_S * 1_000L) return
        open.promptDecided = true
        if (!open.trigger.crash && !open.stoppedSinceOpen && now.kmh >= Tuning.KEEP_PROMPT_MIN_KMH) {
            out += EngineOutput.IncidentPrompt(IncidentCapsule(open.id, open.trigger, open.firstSeq, now.seq))
        }
    }

    private fun evaluateClose(now: SpeedPoint, out: MutableList<EngineOutput>) {
        stillSinceNs = if (now.kmh < Tuning.STILL_SPEED_KMH) stillSinceNs ?: now.tNs else null
        val still = stillSinceNs?.let { ms(now.tNs - it) >= Tuning.CAPSULE_STILLNESS_CLOSE_S * 1_000L } ?: false
        val open = capsule ?: return
        val drovePast = !open.trigger.crash && ms(now.tNs - open.lastTriggerNs) >= Tuning.CAPSULE_POST_WINDOW_S * 1_000L
        if (still || drovePast || ms(now.tNs - open.openedNs) >= Tuning.CAPSULE_MAX_OPEN_S * 1_000L) {
            setLevel(EscalationLevel.NORMAL, now.seq, out) // marker first, so the final seal covers it
            out += EngineOutput.Seal(open.id, open.trigger, open.firstSeq, final = true)
            capsule = null
        }
    }

    /**
     * A trigger opens a capsule. Inside an open capsule (chain collision, 연쇄 추돌) a later trigger is still recorded
     * and re-sealed at once, after a refractory period so one moment's consecutive samples count once; a graver
     * trigger (near-miss → crash → airbag) always counts and takes the capsule over.
     */
    private fun gravity(t: CapsuleTrigger) = when {
        t.severe -> 2
        t.crash -> 1
        else -> 0
    }

    private fun trigger(trigger: CapsuleTrigger, causeSeq: Long, measured: Float, nowNs: Long, out: MutableList<EngineOutput>) {
        if (trigger != CapsuleTrigger.POST_EVENT_EXIT) lastDangerNs = nowNs
        if (trigger.crash) {
            lastSevereNs = nowNs
            guided = false
        }
        // A bookmark asks for the record, not for burst transmission: it keeps the current level.
        val target = when {
            trigger.crash -> EscalationLevel.TRIGGERED
            trigger == CapsuleTrigger.MANUAL -> level
            else -> EscalationLevel.ALERT
        }
        capsule?.let { open ->
            if (trigger == CapsuleTrigger.MANUAL) open.promptDecided = true
            val escalates = gravity(trigger) > gravity(open.trigger)
            if (!escalates && ms(nowNs - open.lastTriggerNs) < Tuning.TRIGGER_REFRACTORY_MS) return
            open.lastTriggerNs = nowNs
            if (escalates) open.trigger = trigger
            raise(target, causeSeq, out)
            out += EngineOutput.Append(EscalationEvent.Triggered(trigger, causeSeq, measured))
            out += EngineOutput.Seal(open.id, open.trigger, open.firstSeq, final = false)
            stillSinceNs = null
            return
        }
        raise(target, causeSeq, out)
        out += EngineOutput.Append(EscalationEvent.Triggered(trigger, causeSeq, measured))
        val open = OpenCapsule("C$causeSeq", trigger, preWindowFirstSeq(), nowNs, nowNs)
        open.promptDecided = trigger == CapsuleTrigger.MANUAL // the driver asked for it: nothing to ask back
        capsule = open
        stillSinceNs = null
        out += EngineOutput.Seal(open.id, trigger, open.firstSeq, final = false)
    }

    private fun raise(target: EscalationLevel, causeSeq: Long, out: MutableList<EngineOutput>) {
        if (target > level) setLevel(target, causeSeq, out)
        if (target == EscalationLevel.ALERT) calmSinceNs = null
    }

    private fun setLevel(next: EscalationLevel, causeSeq: Long, out: MutableList<EngineOutput>) {
        if (level == next) return
        level = next
        out += EngineOutput.Append(EscalationEvent.LevelChanged(next, causeSeq))
    }

    /** The last speed received within [Tuning.SPEED_STALE_MS], or null. */
    private fun currentKmh(nowNs: Long) = lastSpeedKmh?.takeIf { ms(nowNs - lastSpeedReceivedNs) <= Tuning.SPEED_STALE_MS }

    private fun steadyBefore(base: SpeedPoint) = speeds
        .filter { it.tNs < base.tNs && ms(base.tNs - it.tNs) <= Tuning.TRIGGER_SHOVE_BASELINE_MS }
        .all { it.kmh - base.kmh <= Tuning.TRIGGER_REBOUND_KMH }

    private fun brakeRecentlyOn(nowNs: Long) =
        brakeOn || brakeReleasedNs?.let { ms(nowNs - it) <= Tuning.TRIGGER_BRAKE_LOOKBACK_MS } ?: false

    private fun preWindowFirstSeq() = timeline.first().seq

    private fun ms(ns: Long) = ns / 1_000_000

    private companion object {
        /** Speed history kept for the brake-rise and lurch patterns (longest span plus slack). */
        const val SPEED_HISTORY_MS = 3_500

        /** Tolerance for one 10 Hz sampling step when checking that a history reaches back far enough. */
        const val SAMPLE_SLACK_MS = 150

        /** Steering samples closer than this are not differenced (rate would be noise). */
        const val MIN_STEER_GAP_MS = 20

        /** The navigation's downhill caution area: a known descent, where a held brake at steady speed is normal. */
        const val DOWNHILL_CAUTION = "DOWNHILL_CAUTION_AREA"
    }
}
