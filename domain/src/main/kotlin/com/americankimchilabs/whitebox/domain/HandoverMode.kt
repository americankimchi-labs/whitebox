package com.americankimchilabs.whitebox.domain

/** What handover mode asks the car to do. */
sealed interface HandoverOutput {
    /** Record this in the chain. */
    data class Append(val event: HandoverEvent) : HandoverOutput

    /** Say this to the driver. */
    data class Say(val line: VoiceLine) : HandoverOutput

    /** Bring the navigation (maps) app forward so a destination can be set: only while the car is still standing. */
    data object OpenMaps : HandoverOutput

    /** Seal the trip from [fromSeq] to the head as its report, [final] once it is over (provisional while ending). */
    data class SealTrip(val capsuleId: String, val fromSeq: Long, val final: Boolean) : HandoverOutput
}

/**
 * Handover mode's state, kept by the car across restarts. Times are wall-clock milliseconds: the trip may span a
 * restart (or a suspend) of the head unit, where the monotonic clock starts over.
 */
data class HandoverState(
    val phase: HandoverPhase = HandoverPhase.OFF,
    val armedBy: HandoverSource? = null,
    /** When the owner asked (on the phone, possibly before the car heard of it). */
    val requestedAtWall: Long = 0,
    val startSeq: Long? = null,
    val endingReason: HandoverEndReason? = null,
    val endingSinceWall: Long = 0,
)

/**
 * Handover mode (대리 운전) for one trip, as a pure state machine fed with every chain event in order and a clock tick.
 *
 * The life of a trip, as it really goes:
 * - **Armed** on the car screen (parked) or from the owner's phone, usually before the 대리기사 arrives; nothing
 *   ends while armed. A request from the phone that reaches the car only after the drive began (the car had no
 *   network yet) starts the trip retroactively at that drive's departure. Armed for [Tuning.HANDOVER_ARM_EXPIRY_MS]
 *   with no trip: cancelled.
 * - **Active** from the first departure from Park after arming. Without a route in the navigation, the car asks for
 *   a destination and brings the maps app forward (only while still standing).
 * - **Ending** when the car is parked and switched off, parked right after arriving, or parked with the engine on
 *   for [Tuning.HANDOVER_PARKED_END_MS]. The report is sealed provisionally. Driving off within
 *   [Tuning.HANDOVER_GRACE_MS] resumes the same trip (a stop on the way, moving to the parking spot).
 * - **Over** after the grace period, or at once when the owner ends it on the phone; the report is sealed final.
 *
 * Rules while active: speeding only where it matters on Korean roads and as it is enforced there: over a camera's
 * limit by more than [Tuning.HANDOVER_SPEEDING_MARGIN_KMH] when passing it (not when it is first announced, while the
 * driver is still slowing down), and over an enforced section's limit on its average when the section ends; hard braking and sharp steering as
 * the escalation engine detects them; hard acceleration from the speed itself; leaving the route when the navigation
 * re-routes; arrival. Each is recorded, and a short polite line is spoken, the same line at most once a minute.
 */
class HandoverMode(
    initial: HandoverState = HandoverState(),
    /** [Tuning.HANDOVER_GRACE_MS]; shorter only for a demonstration. */
    private val graceMs: Long = Tuning.HANDOVER_GRACE_MS,
) {
    var state: HandoverState = initial
        private set

    // What the car is doing, tracked in every phase (arming can happen mid-drive, the route before the trip).
    private var gear: GearPosition? = null
    private var departureSeq: Long? = null
    private var departureWall: Long = 0
    private var parkedSinceWall: Long? = null
    private var engineOff = false
    private var routeGuiding = false
    private var arrivedAtWall: Long? = null
    private var speedKmh = 0f
    private val recentSpeeds = ArrayDeque<Pair<Long, Float>>() // (elapsed ns, km/h) over the last second and a bit
    private var lastAccelNs: Long? = null
    private var enforcementSection = false
    private var enforcementLimit = 0
    private var enforcementAverage = 0
    private var enforcementRemainingM = Int.MAX_VALUE
    private var speedingReported = false
    private var sectionReported = false
    private val lastSaid = HashMap<VoiceLine, Long>()

    val phase get() = state.phase

    /** Turned on. Before or during a drive; nothing happens while the car is in Park except waiting. */
    fun arm(by: HandoverSource, requestedAtWall: Long, nowWall: Long): List<HandoverOutput> {
        if (state.phase != HandoverPhase.OFF) return emptyList()
        state = HandoverState(HandoverPhase.ARMED, by, requestedAtWall)
        val out = mutableListOf<HandoverOutput>(HandoverOutput.Append(HandoverEvent.Armed(by, requestedAtWall)))
        // The phone's request reached the car only after this drive began: the trip is this drive.
        val dep = departureSeq
        if (gear != null && gear != GearPosition.PARK && dep != null && departureWall >= requestedAtWall - CLOCK_SKEW_MS) {
            out += start(dep, nowWall)
        }
        return out
    }

    /** The owner ended it on the phone (any phase), or cancelled it on the car before the trip started. */
    fun end(by: HandoverSource, nowWall: Long): List<HandoverOutput> = when (state.phase) {
        HandoverPhase.OFF -> emptyList()
        HandoverPhase.ARMED -> cancel(by, HandoverEndReason.OWNER)
        HandoverPhase.ACTIVE, HandoverPhase.ENDING -> finish(HandoverEndReason.OWNER)
    }

    /** Whether the car screen may turn it off now: only before the trip started (the driver must not stop it). */
    fun carMayCancel() = state.phase == HandoverPhase.ARMED

    /**
     * Learns what the car was doing (gear, departure, route, enforcement) from recent records, without acting on them:
     * after a restart, so a request from the phone can still be matched to a drive that began before it.
     */
    fun warm(events: List<DriveEvent>) {
        val kept = state
        state = HandoverState()
        events.forEach { onEvent(it) }
        state = kept
    }

    /** A recorder restart found the trip unfinished: the power went off, so it is ending unless the car drives on. */
    fun restored(nowWall: Long): List<HandoverOutput> {
        if (state.phase != HandoverPhase.ACTIVE) return emptyList()
        // The first gear the car reports after the restart decides: still driving resumes, Park lets it end.
        gear = null
        parkedSinceWall = null
        return ending(HandoverEndReason.RESTART, nowWall)
    }

    fun onEvent(e: DriveEvent): List<HandoverOutput> {
        val out = mutableListOf<HandoverOutput>()
        val wall = e.entry.at.wallMillis
        when (val entry = e.entry) {
            is ChainEntry.Signal -> when (val s = entry.sample.signal) {
                is VehicleSignal.Gear -> {
                    val was = gear
                    gear = s.position
                    if (s.position == GearPosition.PARK) {
                        if (was != GearPosition.PARK) parkedSinceWall = wall
                    } else if (was == null || was == GearPosition.PARK) {
                        departureSeq = e.seq
                        departureWall = wall
                        parkedSinceWall = null
                        out += departed(e.seq, wall)
                    }
                }
                is VehicleSignal.Ignition -> {
                    engineOff = s.state == IgnitionState.OFF || s.state == IgnitionState.LOCK || s.state == IgnitionState.ACC
                    if (engineOff && state.phase == HandoverPhase.ACTIVE && gear == GearPosition.PARK) {
                        out += ending(HandoverEndReason.IGNITION_OFF, wall)
                    }
                }
                is VehicleSignal.Speed -> out += speed(s.metersPerSecond * 3.6f, entry.at.elapsedNanos, wall)
                is VehicleSignal.RoadContext -> out += road(s, entry.at.elapsedNanos, wall)
                else -> Unit
            }
            is ChainEntry.Escalation -> {
                val t = entry.event as? EscalationEvent.Triggered
                if (t != null && state.phase == HandoverPhase.ACTIVE) {
                    when (t.trigger) {
                        CapsuleTrigger.HARD_BRAKE -> out += observed(HandoverEventKind.HARD_BRAKE, t.measured, null, VoiceLine.HANDOVER_DRIVE_SAFELY, entry.at.elapsedNanos)
                        CapsuleTrigger.EVASIVE_STEER, CapsuleTrigger.BRAKE_SWERVE ->
                            out += observed(HandoverEventKind.SHARP_STEER, t.measured, null, VoiceLine.HANDOVER_DRIVE_SAFELY, entry.at.elapsedNanos)
                        // A crash: recorded for the report; the car's own post-crash guidance does the talking.
                        else -> if (t.trigger.crash) out += HandoverOutput.Append(HandoverEvent.Observed(HandoverEventKind.CRASH, t.measured, null))
                    }
                }
            }
            else -> Unit
        }
        if (state.phase == HandoverPhase.ACTIVE && gear == GearPosition.PARK) out += parkedChecks(wall)
        return out
    }

    /** Timers that run while nothing is recorded (parked, engine off): ending, grace and arming expiry. */
    fun tick(nowWall: Long): List<HandoverOutput> = when (state.phase) {
        HandoverPhase.ARMED -> if (nowWall - state.requestedAtWall >= Tuning.HANDOVER_ARM_EXPIRY_MS) cancel(state.armedBy!!, HandoverEndReason.EXPIRED) else emptyList()
        HandoverPhase.ACTIVE -> if (gear == GearPosition.PARK) parkedChecks(nowWall) else emptyList()
        HandoverPhase.ENDING -> if (nowWall - state.endingSinceWall >= graceMs) finish(state.endingReason!!) else emptyList()
        HandoverPhase.OFF -> emptyList()
    }

    private fun departed(seq: Long, wall: Long): List<HandoverOutput> = when (state.phase) {
        HandoverPhase.ARMED -> start(seq, wall)
        HandoverPhase.ENDING -> {
            state = state.copy(phase = HandoverPhase.ACTIVE, endingReason = null, endingSinceWall = 0)
            listOf(HandoverOutput.Append(HandoverEvent.Resumed))
        }
        else -> emptyList()
    }

    private fun start(fromSeq: Long, wall: Long): List<HandoverOutput> {
        state = state.copy(phase = HandoverPhase.ACTIVE, startSeq = fromSeq)
        speedingReported = false
        sectionReported = false
        val out = mutableListOf(
            HandoverOutput.Append(HandoverEvent.Started(fromSeq, state.armedBy!!, state.requestedAtWall)),
            HandoverOutput.Say(VoiceLine.HANDOVER_STARTED),
        )
        if (!routeGuiding) {
            out += HandoverOutput.Append(HandoverEvent.Observed(HandoverEventKind.NO_ROUTE, 0f, null))
            // A destination is set before moving off, not while driving.
            if (speedKmh < 1f) {
                out += HandoverOutput.Say(VoiceLine.HANDOVER_SET_DESTINATION)
                out += HandoverOutput.OpenMaps
            }
        }
        return out
    }

    private fun parkedChecks(wall: Long): List<HandoverOutput> {
        val since = parkedSinceWall ?: return emptyList()
        return when {
            engineOff -> ending(HandoverEndReason.IGNITION_OFF, wall)
            arrivedAtWall?.let { since - it in 0..ARRIVAL_PARK_MS } == true -> ending(HandoverEndReason.ARRIVED, wall)
            wall - since >= Tuning.HANDOVER_PARKED_END_MS -> ending(HandoverEndReason.PARKED, wall)
            else -> emptyList()
        }
    }

    private fun ending(reason: HandoverEndReason, wall: Long): List<HandoverOutput> {
        if (state.phase != HandoverPhase.ACTIVE) return emptyList()
        state = state.copy(phase = HandoverPhase.ENDING, endingReason = reason, endingSinceWall = wall)
        return listOf(
            HandoverOutput.Append(HandoverEvent.Ending(reason)),
            HandoverOutput.SealTrip(capsuleId(), state.startSeq!!, final = false),
        )
    }

    private fun finish(reason: HandoverEndReason): List<HandoverOutput> {
        val id = capsuleId()
        val from = state.startSeq!!
        state = HandoverState()
        arrivedAtWall = null
        return listOf(HandoverOutput.Append(HandoverEvent.Ended(reason, id)), HandoverOutput.SealTrip(id, from, final = true))
    }

    private fun cancel(by: HandoverSource, reason: HandoverEndReason): List<HandoverOutput> {
        state = HandoverState()
        return listOf(HandoverOutput.Append(HandoverEvent.Cancelled(by, reason)))
    }

    private fun capsuleId() = "H${state.startSeq}"

    private fun speed(kmh: Float, ns: Long, wall: Long): List<HandoverOutput> {
        speedKmh = kmh
        recentSpeeds.addLast(ns to kmh)
        while (recentSpeeds.size > 1 && ns - recentSpeeds.first().first > ACCEL_WINDOW_NS) recentSpeeds.removeFirst()
        if (state.phase != HandoverPhase.ACTIVE) return emptyList()
        val out = mutableListOf<HandoverOutput>()
        // 급가속: up by the threshold within one second, from a rolling start (a start from standstill is the
        // escalation engine's lurch).
        val from = recentSpeeds.filter { ns - it.first <= ACCEL_WINDOW_NS && it.second >= Tuning.HARD_ACCEL_MIN_KMH }.minByOrNull { it.second }
        if (from != null && kmh - from.second >= Tuning.HARD_ACCEL_KMH_PER_S && lastAccelNs.let { it == null || ns - it > ACCEL_REFRACTORY_NS }) {
            lastAccelNs = ns
            out += observed(HandoverEventKind.HARD_ACCEL, kmh - from.second, null, VoiceLine.HANDOVER_DRIVE_SAFELY, ns)
        }
        out += cameraCheck(ns)
        return out
    }

    /** Over a camera's limit by more than the margin while passing it. */
    private fun cameraCheck(ns: Long): List<HandoverOutput> {
        if (state.phase != HandoverPhase.ACTIVE || enforcementSection || enforcementLimit <= 0 || speedingReported) return emptyList()
        if (enforcementRemainingM > CAMERA_ZONE_M || speedKmh <= enforcementLimit + Tuning.HANDOVER_SPEEDING_MARGIN_KMH) return emptyList()
        speedingReported = true
        return observed(HandoverEventKind.SPEEDING, speedKmh, enforcementLimit.toFloat(), VoiceLine.HANDOVER_SLOW_DOWN, ns)
    }

    /** An enforced section judged on its average as it ends (at its end camera, or when the navigation drops it). */
    private fun sectionCheck(ns: Long, ended: Boolean): List<HandoverOutput> {
        if (state.phase != HandoverPhase.ACTIVE || !enforcementSection || enforcementLimit <= 0 || sectionReported) return emptyList()
        if (!ended && enforcementRemainingM > CAMERA_ZONE_M) return emptyList()
        if (enforcementAverage <= enforcementLimit + Tuning.HANDOVER_SPEEDING_MARGIN_KMH) return emptyList()
        sectionReported = true
        return observed(HandoverEventKind.SECTION_SPEEDING, enforcementAverage.toFloat(), enforcementLimit.toFloat(), VoiceLine.HANDOVER_SLOW_DOWN, ns)
    }

    private fun road(s: VehicleSignal.RoadContext, ns: Long, wall: Long): List<HandoverOutput> {
        val out = mutableListOf<HandoverOutput>()
        when (s.type) {
            RoadContextType.SPEED_ENFORCEMENT -> {
                if (s.value == "NONE") {
                    out += sectionCheck(ns, ended = true) // left the section: its average stands
                    enforcementSection = false
                    enforcementLimit = 0
                    enforcementAverage = 0
                    enforcementRemainingM = Int.MAX_VALUE
                    speedingReported = false
                    sectionReported = false
                } else {
                    val f = s.value.split(";")
                    val section = f[0] == "SECTION"
                    val limit = f.getOrNull(1)?.toIntOrNull() ?: 0
                    // A different camera or section: judged afresh.
                    if (section != enforcementSection || limit != enforcementLimit) {
                        speedingReported = false
                        sectionReported = false
                    }
                    enforcementSection = section
                    enforcementLimit = limit
                    enforcementAverage = f.getOrNull(2)?.toIntOrNull() ?: 0
                    enforcementRemainingM = f.getOrNull(3)?.toIntOrNull() ?: Int.MAX_VALUE
                    out += cameraCheck(ns)
                    out += sectionCheck(ns, ended = false)
                }
            }
            RoadContextType.ROUTE -> when (s.value) {
                "GUIDING" -> routeGuiding = true
                "REROUTED" -> if (routeGuiding && state.phase == HandoverPhase.ACTIVE) {
                    out += observed(HandoverEventKind.ROUTE_LEFT, 0f, null, VoiceLine.HANDOVER_CHECK_ROUTE, ns)
                }
                "ARRIVED" -> {
                    routeGuiding = false
                    arrivedAtWall = wall
                    if (state.phase == HandoverPhase.ACTIVE) out += HandoverOutput.Append(HandoverEvent.Observed(HandoverEventKind.ARRIVED, 0f, null))
                }
                else -> routeGuiding = false
            }
            else -> Unit
        }
        return out
    }

    private fun observed(kind: HandoverEventKind, value: Float, limit: Float?, line: VoiceLine, ns: Long): List<HandoverOutput> {
        val out = mutableListOf<HandoverOutput>(HandoverOutput.Append(HandoverEvent.Observed(kind, value, limit)))
        val last = lastSaid[line]
        if (last == null || ns - last >= Tuning.HANDOVER_VOICE_COOLDOWN_MS * 1_000_000) {
            lastSaid[line] = ns
            out += HandoverOutput.Say(line)
        }
        return out
    }

    private companion object {
        /** The phone's clock and the car's may differ a little: a departure this much "before" the request still counts. */
        const val CLOCK_SKEW_MS = 60_000L

        /** Parked within this long after the navigation announced arrival: arrived. */
        const val ARRIVAL_PARK_MS = 5 * 60_000L

        const val ACCEL_WINDOW_NS = 1_000_000_000L

        /** Passing a camera: this close to it (the navigation reports the distance about once a second). */
        const val CAMERA_ZONE_M = 100

        /** One hard acceleration is reported once, not on every sample of it. */
        const val ACCEL_REFRACTORY_NS = 3_000_000_000L
    }
}
