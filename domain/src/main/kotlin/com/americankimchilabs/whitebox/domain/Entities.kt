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
 * a decision of the escalation engine ([EscalationEvent]), so the evidence states why a capsule exists, what the car
 * said and heard ([AudioEvent]), the course of a handover trip ([HandoverEvent]), or the driver's library and plan
 * ([LibraryEvent]).
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

    data class Audio(val event: AudioEvent, override val at: Timestamp) : ChainEntry

    data class Handover(val event: HandoverEvent, override val at: Timestamp) : ChainEntry

    data class Library(val event: LibraryEvent, override val at: Timestamp) : ChainEntry
}

enum class EscalationLevel { NORMAL, ALERT, TRIGGERED }

/** Engine decisions, written into the chain right after the entry that caused them. */
sealed interface EscalationEvent {
    data class LevelChanged(val level: EscalationLevel, val causeSeq: Long) : EscalationEvent

    /**
     * [measured] is the value that met the condition, in [CapsuleTrigger.unit]: the speed change for ΔV triggers
     * (negative = drop), the speed for gear and parking-brake triggers, the steering rate, the press count.
     */
    data class Triggered(val trigger: CapsuleTrigger, val causeSeq: Long, val measured: Float) : EscalationEvent

    /**
     * The voice question "방금 상황을 보관할까요?" was asked about an already-sealed near-miss capsule (the car was still
     * driving after it). Older chains used it for the parked prompt that sealed only on consent.
     */
    data class IncidentOffered(val capsule: IncidentCapsule) : EscalationEvent

    /**
     * The driver's answer. The capsule stays sealed in the chain either way; [keep] = false releases it from the
     * driver's library (no slot), and the answer becomes the capsule's label. [answered] is false for silence (no
     * answer in the window, or the question could not be asked): kept, and labelled as unanswered.
     */
    data class IncidentAnswered(val capsuleId: String, val keep: Boolean, val answered: Boolean = true) : EscalationEvent
}

/**
 * Why a capsule exists. Telltales are unreadable without PRIVATE_CAR_SAFETY, so there is no
 * telltale trigger.
 *
 * [crash]: a crash-level event, answered as a crash (sealed at once, burst and priority upload, the server's seal if
 * the car goes silent, no keep question). [severe]: an airbag crash (큰 사고) — kept ten years free, never counts
 * against the library, and the only kind an emergency contact can open. A crash-level speed change without an
 * airbag (충돌) is a crash but not severe.
 */
enum class CapsuleTrigger(val crash: Boolean, val unit: MeasureUnit = MeasureUnit.DELTA_KMH) {
    AIRBAG(true, MeasureUnit.NONE),

    /**
     * An airbag signal injected on a test build (the emulator has no airbag property). Recorded as a test everywhere,
     * so it can never pass for a real deployment; a release build has no way to make one.
     */
    TEST_AIRBAG(true, MeasureUnit.NONE),

    /** Primary: ΔV over the sliding 300 ms window. */
    DELTA_V(true),

    /** S1: ΔV over a 150–250 ms span. */
    SHORT_DELTA_V(true),

    /** S2: brake-corroborated short spike, rebound-checked. */
    BRAKED_SPIKE(true),

    /** S3: speed increase while braked (struck from behind / 추돌). */
    REAR_SHOVE(true),

    /** Older chains only: ALERT that ended in stillness, sealed on the driver's consent. Superseded by [HARD_BRAKE]. */
    ALERT_RESOLVED(false),

    /** Voice or Gleo bookmark ("방금 저장해"). */
    MANUAL(false, MeasureUnit.NONE),

    // Near-miss and 급발진 patterns: kept without asking (see Tuning).

    /** Brake on and slowing hard (the ALERT precursor). */
    HARD_BRAKE(false),

    /** Steering-wheel rate at speed above the evasive threshold. */
    EVASIVE_STEER(false, MeasureUnit.DEG_PER_S),

    /** Evasive steering with the brake on (the classic avoidance move). */
    BRAKE_SWERVE(false, MeasureUnit.DEG_PER_S),

    /** Gear to N while moving. */
    SHIFT_TO_NEUTRAL(false, MeasureUnit.KMH),

    /** Speed rising while the brake has been held. */
    BRAKE_SPEED_RISE(false),

    /**
     * Brake held without slowing down on a road known not to be downhill. On speed alone it is indistinguishable
     * from drag braking downhill, so it fires only while a road grade is recorded (see Tuning) — on a real vehicle with
     * a head-unit accelerometer, never on the emulator.
     */
    BRAKE_NO_DECEL(false),

    /** Repeated brake presses while moving. */
    BRAKE_PUMPING(false, MeasureUnit.COUNT),

    /** Parking brake engaged while moving. */
    PARKING_BRAKE_MOVING(false, MeasureUnit.KMH),

    /** Sudden jump from standstill. */
    LURCH(false),

    /** A door opened while stopped shortly after a dangerous moment. */
    POST_EVENT_EXIT(false, MeasureUnit.NONE),

    /** A whole handover trip (대리 운전), sealed as its report when it ends. */
    HANDOVER(false, MeasureUnit.NONE),
    ;

    val severe: Boolean get() = this == AIRBAG || this == TEST_AIRBAG
}

/** What [EscalationEvent.Triggered.measured] holds: a speed change, the speed at the moment, a steering rate, a count. */
enum class MeasureUnit { DELTA_KMH, KMH, DEG_PER_S, COUNT, NONE }

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
