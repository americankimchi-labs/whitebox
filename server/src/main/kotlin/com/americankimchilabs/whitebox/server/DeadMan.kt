package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.Tuning

/** One car's mirrored chain: a device and which of its chains (live evidence or the replayed demo). */
data class StreamId(val deviceId: String, val stream: String) {
    override fun toString() = "$deviceId/$stream"
}

/**
 * The server-side dead-man switch, as a pure state machine over frame arrivals and clock ticks.
 *
 * An episode starts when a frame says the car left NORMAL and ends with a frame saying NORMAL again (the car's
 * explicit all-clear). If the stream goes silent during an episode — power loss, a destroyed head unit, a dead
 * antenna — the server seals what it received after [Tuning.DEADMAN_SEAL_SILENCE_S] (a server-attested seal; a
 * false alarm in a tunnel is harmless: the resumed stream simply appends, and the seal stands as an extra
 * checkpoint, so each new silence seals again). After [Tuning.DEADMAN_NOTIFY_SILENCE_S] the emergency notice is
 * considered once per episode; it goes out only with evidence of a crash-level event (decided by the caller), so a
 * hard brake in a tunnel never alarms the family.
 */
class DeadMan(
    private val sealSilenceMs: Long = Tuning.DEADMAN_SEAL_SILENCE_S * 1_000L,
    private val notifySilenceMs: Long = Tuning.DEADMAN_NOTIFY_SILENCE_S * 1_000L,
) {
    sealed interface Action {
        /** Seal what was received of this episode (it began at [episodeStartMs]). */
        data class Attest(val stream: StreamId, val episodeStartMs: Long) : Action

        /** Long silence: record it for the family's page if the episode shows a crash-level event ([severeSeen] is the frame evidence). */
        data class ConsiderNotice(val stream: StreamId, val episodeStartMs: Long, val severeSeen: Boolean) : Action
    }

    private class State(var level: EscalationLevel, var lastMs: Long) {
        var episodeStartMs: Long? = null
        var attestedSinceFrame = false
        var consideredSinceFrame = false
        var severe = false
        var notified = false
    }

    private val streams = HashMap<StreamId, State>()

    @Synchronized
    fun onFrame(stream: StreamId, level: EscalationLevel, severeEvidence: Boolean, nowMs: Long) {
        val s = streams.getOrPut(stream) { State(level, nowMs) }
        s.level = level
        s.lastMs = nowMs
        s.attestedSinceFrame = false
        s.consideredSinceFrame = false
        if (level == EscalationLevel.NORMAL) {
            s.episodeStartMs = null // all-clear
            s.severe = false
            s.notified = false
            return
        }
        if (s.episodeStartMs == null) s.episodeStartMs = nowMs
        s.severe = s.severe || severeEvidence || level == EscalationLevel.TRIGGERED
    }

    /** The caller sent the notice for this episode. */
    @Synchronized
    fun notified(stream: StreamId) {
        streams[stream]?.notified = true
    }

    @Synchronized
    fun tick(nowMs: Long): List<Action> = buildList {
        for ((id, s) in streams) {
            val start = s.episodeStartMs ?: continue
            val silence = nowMs - s.lastMs
            if (silence >= sealSilenceMs && !s.attestedSinceFrame) {
                s.attestedSinceFrame = true
                add(Action.Attest(id, start))
            }
            if (silence >= notifySilenceMs && !s.notified && !s.consideredSinceFrame) {
                s.consideredSinceFrame = true
                add(Action.ConsiderNotice(id, start, s.severe))
            }
        }
    }

    @Synchronized
    fun level(stream: StreamId): EscalationLevel? = streams[stream]?.level

    @Synchronized
    fun lastFrameMs(stream: StreamId): Long? = streams[stream]?.lastMs
}
