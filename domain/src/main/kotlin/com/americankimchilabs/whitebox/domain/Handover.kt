package com.americankimchilabs.whitebox.domain

/**
 * Handover mode (대리 운전): the owner hands the car to a driver for one trip. Recorded in the chain so the report is
 * as tamper-evident as everything else: when it was armed and by whom, when the trip started and ended and why, and
 * every event the rules noticed on the way.
 */
sealed interface HandoverEvent {
    /** Turned on (from the car screen while parked, or from the owner's phone at [requestedAtWall]). */
    data class Armed(val by: HandoverSource, val requestedAtWall: Long) : HandoverEvent

    /** Turned off before any trip started: by the owner, or because no trip came in time ([reason]). */
    data class Cancelled(val by: HandoverSource, val reason: HandoverEndReason) : HandoverEvent

    /**
     * The trip began at [fromSeq]: the first record of the drive that left Park after arming ([by], at
     * [requestedAtWall], repeated here so the trip's report holds it).
     */
    data class Started(val fromSeq: Long, val by: HandoverSource, val requestedAtWall: Long) : HandoverEvent

    /** Something the rules noticed. [value] and [limit] are in [HandoverEventKind.unit]; [limit] is null when none applies. */
    data class Observed(val kind: HandoverEventKind, val value: Float, val limit: Float?) : HandoverEvent

    /** The trip looks finished ([reason]); it is final unless the car drives off again within the grace period. */
    data class Ending(val reason: HandoverEndReason) : HandoverEvent

    /** The car drove off again within the grace period: the same trip goes on (a stop on the way). */
    data object Resumed : HandoverEvent

    /** The trip is over; its report is the capsule [capsuleId]. */
    data class Ended(val reason: HandoverEndReason, val capsuleId: String) : HandoverEvent
}

enum class HandoverSource { CAR, PHONE }

/** What a handover event measured: speed (km/h), a speed change (km/h within a second), or nothing. */
enum class HandoverEventKind(val unit: MeasureUnit) {
    /** Over an enforcement camera's limit by more than the margin (the only speeding that matters on Korean roads). */
    SPEEDING(MeasureUnit.KMH),

    /** Average speed over an enforced section above its limit by more than the margin. */
    SECTION_SPEEDING(MeasureUnit.KMH),
    HARD_BRAKE(MeasureUnit.DELTA_KMH),

    /** A crash-level detection during the trip (its own capsule holds the evidence; the report must show it). */
    CRASH(MeasureUnit.DELTA_KMH),
    HARD_ACCEL(MeasureUnit.DELTA_KMH),
    SHARP_STEER(MeasureUnit.DEG_PER_S),

    /** The navigation re-routed while guiding: the driver left the route. */
    ROUTE_LEFT(MeasureUnit.NONE),

    /** The navigation announced arrival at the destination. */
    ARRIVED(MeasureUnit.NONE),

    /** The trip started without a route in the navigation (route leaving cannot be checked). */
    NO_ROUTE(MeasureUnit.NONE),
}

enum class HandoverEndReason {
    /** The engine was switched off (or the head unit suspended) while parked. */
    IGNITION_OFF,

    /** Parked with the engine running for a long while. */
    PARKED,

    /** Parked right after the navigation announced arrival. */
    ARRIVED,

    /** The owner ended it on their phone (or, before any trip, cancelled it on the car). */
    OWNER,

    /** Armed, but no trip started in time. */
    EXPIRED,

    /** The recorder found an unfinished trip after a restart whose power-off ended it. */
    RESTART,
}

/** Where handover mode stands, as the car shows it and the phone sees it. */
enum class HandoverPhase { OFF, ARMED, ACTIVE, ENDING }
