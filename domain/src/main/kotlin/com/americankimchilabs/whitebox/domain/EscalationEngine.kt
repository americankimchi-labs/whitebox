package com.americankimchilabs.whitebox.domain

import kotlin.math.abs

/** What the engine asks the recorder to do after an entry. */
sealed interface EngineOutput {
    /** Append to the chain (before any seal from the same step, so the seal covers it). */
    data class Append(val event: EscalationEvent) : EngineOutput

    /**
     * Seal [capsuleId] from [firstSeq] up to the chain head at the moment of sealing. The first seal of a TRIGGERED
     * capsule is provisional (durability first: the pre-window is sealed at once); the final one comes at stillness.
     */
    data class Seal(val capsuleId: String, val trigger: CapsuleTrigger, val firstSeq: Long, val final: Boolean) : EngineOutput

    /** ALERT ended in stillness without a trigger: offer "방금 상황을 보관할까요?" (parked only); sealed on consent. */
    data class IncidentPrompt(val capsule: IncidentCapsule) : EngineOutput
}

/**
 * NORMAL → ALERT → TRIGGERED (constants and rationale in [Tuning]).
 *
 * Pure and deterministic: fed the same chain it makes the same decisions, so a replay reproduces a capsule exactly.
 * All speed conditions use |speed| (AAOS reports reverse as negative) and the VHAL source timestamp when present.
 */
class EscalationEngine {

    private class SpeedPoint(val tNs: Long, val kmh: Float, val seq: Long)
    private class Mark(val seq: Long, val tNs: Long)
    private class OpenCapsule(val id: String, val trigger: CapsuleTrigger, val firstSeq: Long, val openedNs: Long, var lastTriggerNs: Long)
    private class PendingIncident(val firstSeq: Long, val alertNs: Long, var lastSeq: Long)
    private class SpikeCandidate(val seq: Long, val kmh: Float, val dropKmh: Float)

    var level = EscalationLevel.NORMAL
        private set

    private val speeds = ArrayDeque<SpeedPoint>()
    private val timeline = ArrayDeque<Mark>()
    private var brakeOn = false
    private var brakeReleasedNs: Long? = null
    private var stillSinceNs: Long? = null
    private var calmSinceNs: Long? = null
    private var capsule: OpenCapsule? = null
    private var incident: PendingIncident? = null
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
            val gap = (entry.event as RecorderEvent.RecordingStarted).gap
            if (gap == null || gap.acrossBoots) timeline.clear()
        }
        timeline.addLast(Mark(event.seq, nowNs))
        while (nowNs - timeline.first().tNs > Tuning.CAPSULE_PRE_WINDOW_S * 1_000_000_000L) timeline.removeFirst()

        if (entry is ChainEntry.Signal) {
            when (val s = entry.sample.signal) {
                is VehicleSignal.BrakePedal -> {
                    if (brakeOn && !s.pressed) brakeReleasedNs = nowNs
                    brakeOn = s.pressed
                }
                is VehicleSignal.AirbagDeployed -> if (s.deployed) trigger(CapsuleTrigger.AIRBAG, event.seq, 0f, nowNs, out)
                is VehicleSignal.Speed -> onSpeed(event.seq, entry.sample, out)
                else -> Unit
            }
        }
        incident?.let { if (nowNs - it.alertNs <= Tuning.CAPSULE_MAX_OPEN_S * 1_000_000_000L) it.lastSeq = event.seq }
        return out
    }

    private fun onSpeed(seq: Long, sample: SignalSample, out: MutableList<EngineOutput>) {
        val tNs = sample.sourceElapsedNanos ?: sample.receivedAt.elapsedNanos
        val kmh = abs((sample.signal as VehicleSignal.Speed).metersPerSecond) * 3.6f
        speeds.lastOrNull()?.let { last ->
            if (tNs <= last.tNs || ms(tNs - last.tNs) > Tuning.TRIGGER_MAX_SAMPLE_GAP_MS) {
                speeds.clear()
                spike = null
            }
        }
        val now = SpeedPoint(tNs, kmh, seq)
        speeds.addLast(now)
        while (ms(tNs - speeds.first().tNs) > 1_000) speeds.removeFirst()

        spike?.let { c ->
            spike = null
            if (kmh <= c.kmh + Tuning.TRIGGER_REBOUND_KMH) trigger(CapsuleTrigger.BRAKED_SPIKE, c.seq, -c.dropKmh, tNs, out)
        }
        evaluateTriggers(now, out)
        if (level == EscalationLevel.NORMAL) evaluateAlert(now, out)
        if (level == EscalationLevel.ALERT) evaluateCalm(now, out)
        evaluateStillness(now, out)
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

    private fun evaluateAlert(now: SpeedPoint, out: MutableList<EngineOutput>) {
        if (!brakeOn) return
        val oldest = speeds.firstOrNull { ms(now.tNs - it.tNs) <= Tuning.ALERT_DECEL_WINDOW_MS } ?: return
        val spanMs = ms(now.tNs - oldest.tNs)
        if (spanMs < Tuning.ALERT_DECEL_WINDOW_MS * 3 / 5 || oldest.kmh < Tuning.ALERT_MIN_SPEED_KMH) return
        if ((oldest.kmh - now.kmh) * 1000f / spanMs < Tuning.ALERT_DECEL_KMHPS) return
        setLevel(EscalationLevel.ALERT, now.seq, out)
        calmSinceNs = null
        if (incident == null) incident = PendingIncident(preWindowFirstSeq(), now.tNs, now.seq)
    }

    /** ALERT drops back to NORMAL after calm driving; the incident stays pending until the next stillness. */
    private fun evaluateCalm(now: SpeedPoint, out: MutableList<EngineOutput>) {
        val calm = !brakeOn && now.kmh >= Tuning.STILL_SPEED_KMH
        calmSinceNs = if (calm) calmSinceNs ?: now.tNs else null
        if (calmSinceNs != null && ms(now.tNs - calmSinceNs!!) >= Tuning.ALERT_CLEAR_S * 1_000L) {
            setLevel(EscalationLevel.NORMAL, now.seq, out)
        }
    }

    private fun evaluateStillness(now: SpeedPoint, out: MutableList<EngineOutput>) {
        stillSinceNs = if (now.kmh < Tuning.STILL_SPEED_KMH) stillSinceNs ?: now.tNs else null
        val still = stillSinceNs?.let { ms(now.tNs - it) >= Tuning.CAPSULE_STILLNESS_CLOSE_S * 1_000L } ?: false
        val open = capsule
        if (open != null && (still || ms(now.tNs - open.openedNs) >= Tuning.CAPSULE_MAX_OPEN_S * 1_000L)) {
            setLevel(EscalationLevel.NORMAL, now.seq, out) // marker first, so the final seal covers it
            out += EngineOutput.Seal(open.id, open.trigger, open.firstSeq, final = true)
            capsule = null
            incident = null
        } else if (open == null && still) {
            incident?.let {
                out += EngineOutput.IncidentPrompt(IncidentCapsule("I${it.firstSeq}", CapsuleTrigger.ALERT_RESOLVED, it.firstSeq, it.lastSeq))
                incident = null
            }
            if (level == EscalationLevel.ALERT) setLevel(EscalationLevel.NORMAL, now.seq, out)
        }
    }

    /**
     * A trigger opens a capsule. Inside an open capsule (chain collision, 연쇄 추돌) a later trigger is still recorded
     * and re-sealed at once, after a refractory period so one impact's consecutive samples count once.
     */
    private fun trigger(trigger: CapsuleTrigger, causeSeq: Long, measuredKmh: Float, nowNs: Long, out: MutableList<EngineOutput>) {
        capsule?.let { open ->
            if (ms(nowNs - open.lastTriggerNs) < Tuning.TRIGGER_REFRACTORY_MS) return
            open.lastTriggerNs = nowNs
            out += EngineOutput.Append(EscalationEvent.Triggered(trigger, causeSeq, measuredKmh))
            out += EngineOutput.Seal(open.id, open.trigger, open.firstSeq, final = false)
            stillSinceNs = null
            return
        }
        setLevel(EscalationLevel.TRIGGERED, causeSeq, out)
        out += EngineOutput.Append(EscalationEvent.Triggered(trigger, causeSeq, measuredKmh))
        val open = OpenCapsule("C$causeSeq", trigger, incident?.firstSeq ?: preWindowFirstSeq(), nowNs, nowNs)
        capsule = open
        incident = null
        stillSinceNs = null
        out += EngineOutput.Seal(open.id, trigger, open.firstSeq, final = false)
    }

    private fun setLevel(next: EscalationLevel, causeSeq: Long, out: MutableList<EngineOutput>) {
        if (level == next) return
        level = next
        out += EngineOutput.Append(EscalationEvent.LevelChanged(next, causeSeq))
    }

    private fun steadyBefore(base: SpeedPoint) = speeds
        .filter { it.tNs < base.tNs && ms(base.tNs - it.tNs) <= Tuning.TRIGGER_SHOVE_BASELINE_MS }
        .all { it.kmh - base.kmh <= Tuning.TRIGGER_REBOUND_KMH }

    private fun brakeRecentlyOn(nowNs: Long) =
        brakeOn || brakeReleasedNs?.let { ms(nowNs - it) <= Tuning.TRIGGER_BRAKE_LOOKBACK_MS } ?: false

    private fun preWindowFirstSeq() = timeline.first().seq

    private fun ms(ns: Long) = ns / 1_000_000
}
