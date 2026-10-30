package com.americankimchilabs.whitebox.domain

/** A location fix; [gpsTimeMillis] is the satellite time used for first-pass dashcam alignment. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val gpsTimeMillis: Long,
    val receivedAt: Timestamp,
)

/** One durable entry in the append-only log. [seq] is gapless and starts at 0. */
data class DriveEvent(
    val seq: Long,
    val entry: ChainEntry,
)

/**
 * What a chain entry holds: a vehicle or location reading, the recorder's own state (gap honesty, [RecorderEvent]),
 * or a decision of the escalation engine ([EscalationEvent]), so the evidence states why a capsule exists.
 */
sealed interface ChainEntry {
    val at: Timestamp

    data class Signal(val sample: SignalSample) : ChainEntry {
        override val at get() = sample.receivedAt
    }

    data class Location(val fix: LocationFix) : ChainEntry {
        override val at get() = fix.receivedAt
    }

    data class Recorder(val event: RecorderEvent, override val at: Timestamp) : ChainEntry

    data class Escalation(val event: EscalationEvent, override val at: Timestamp) : ChainEntry
}

enum class EscalationLevel { NORMAL, ALERT, TRIGGERED }

/** Engine decisions, written into the chain right after the entry that caused them. */
sealed interface EscalationEvent {
    data class LevelChanged(val level: EscalationLevel, val causeSeq: Long) : EscalationEvent

    /** [measuredKmh] is the speed change that met the condition (negative = drop). */
    data class Triggered(val trigger: CapsuleTrigger, val causeSeq: Long, val measuredKmh: Float) : EscalationEvent

    /** The parked prompt "방금 상황을 보관할까요?" was queued; it survives process death because it is in the chain. */
    data class IncidentOffered(val capsule: IncidentCapsule) : EscalationEvent

    /** The driver's answer; [keep] seals the offered capsule. */
    data class IncidentAnswered(val capsuleId: String, val keep: Boolean) : EscalationEvent
}

/**
 * Why a capsule exists. Telltales are unreadable without PRIVATE_CAR_SAFETY, so there is no
 * telltale trigger.
 */
enum class CapsuleTrigger(val severe: Boolean) {
    AIRBAG(true),

    /** Primary: ΔV over the sliding 300 ms window. */
    DELTA_V(true),

    /** S1: ΔV over a 150–250 ms span. */
    SHORT_DELTA_V(true),

    /** S2: brake-corroborated short spike, rebound-checked. */
    BRAKED_SPIKE(true),

    /** S3: speed increase while braked (struck from behind / 추돌). */
    REAR_SHOVE(true),

    /** ALERT that ended in stillness without a trigger; sealed only if the driver agrees (parked prompt). */
    ALERT_RESOLVED(false),

    MANUAL(false),
}

/** A sealed excerpt of the event stream. Seq range is inclusive. */
data class IncidentCapsule(
    val id: String,
    val trigger: CapsuleTrigger,
    val firstSeq: Long,
    val lastSeq: Long,
)

/** Head of the SHA-256 hash chain after [lastSeq]. */
class SealedChain(
    val lastSeq: Long,
    val headHash: ByteArray,
)
