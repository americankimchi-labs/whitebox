package com.americankimchilabs.whitebox.domain

/**
 * Gap honesty: the recorder writes its own outages and degradations into the chain as first-class entries,
 * so the timeline shows when it was not recording (or recording without some input) instead of a silent gap.
 */
sealed interface RecorderEvent {

    /** Logging (re)started. [gap] is null only for the very first session on this install. */
    data class RecordingStarted(
        val reason: StartReason,
        val gap: Gap?,
        /** Consecutive process deaths in this boot that the system restarted (Android backs off 1 s → 4 s → 16 s). */
        val restartCount: Int,
    ) : RecorderEvent

    /** Logging stopped on purpose. The next start's gap is [GapCause.CLEAN_STOP]. */
    data object RecordingStopped : RecorderEvent

    data class PermissionDegraded(val permission: RecorderPermission) : RecorderEvent

    data class PermissionRestored(val permission: RecorderPermission) : RecorderEvent
}

enum class StartReason {
    /** BOOT_COMPLETED for the driver's user. */
    BOOT,

    /** The user opened the app. */
    APP_OPENED,

    /** The system restarted the service after the process died (START_STICKY, null intent). */
    STICKY_RESTART,
}

enum class GapCause {
    /** The previous session ended with [RecorderEvent.RecordingStopped]. */
    CLEAN_STOP,

    /** Different boot and no clean stop: ignition off, shutdown or power loss. */
    POWER_CYCLE,

    /** Same boot, the process died and the system restarted it. */
    PROCESS_DIED,

    /** Same boot, no clean stop, and no system restart: force-stop (or a kill the system did not undo); the user had to reopen the app. */
    STOPPED_WITHOUT_RESTART,
}

/**
 * The span with no recording. [durationMillis] uses the monotonic clock within one boot; across boots only wall time
 * exists, so it may be off by any wall-clock correction in between ([acrossBoots]).
 */
data class Gap(
    val cause: GapCause,
    val fromWallMillis: Long,
    val untilWallMillis: Long,
    val durationMillis: Long,
    val acrossBoots: Boolean,
)

/** What the recorder persists about its current session so the next start can describe the gap. */
data class SessionRecord(
    val bootCount: Int,
    /** Last moment the recorder was known alive (its last durable write). */
    val lastAlive: Timestamp,
    val cleanStop: Boolean,
    val restartCount: Int,
)

/** Classifies a start against the previous session. Pure, so every path is unit-tested. */
fun classifyStart(previous: SessionRecord?, reason: StartReason, bootCount: Int, now: Timestamp): RecorderEvent.RecordingStarted {
    if (previous == null) return RecorderEvent.RecordingStarted(reason, gap = null, restartCount = 0)
    val sameBoot = previous.bootCount == bootCount
    val cause = when {
        previous.cleanStop -> GapCause.CLEAN_STOP
        !sameBoot -> GapCause.POWER_CYCLE
        reason == StartReason.STICKY_RESTART -> GapCause.PROCESS_DIED
        else -> GapCause.STOPPED_WITHOUT_RESTART
    }
    val duration = if (sameBoot) {
        (now.elapsedNanos - previous.lastAlive.elapsedNanos) / 1_000_000
    } else {
        now.wallMillis - previous.lastAlive.wallMillis
    }
    val restarts = if (cause == GapCause.PROCESS_DIED) previous.restartCount + 1 else 0
    return RecorderEvent.RecordingStarted(
        reason = reason,
        gap = Gap(cause, previous.lastAlive.wallMillis, now.wallMillis, duration, acrossBoots = !sameBoot),
        restartCount = restarts,
    )
}

/** Runtime grants the recorder depends on. All are granted once, parked, at first run. */
enum class RecorderPermission {
    /** ACCESS_FINE_LOCATION ("while in use"). */
    LOCATION,

    /** ACCESS_BACKGROUND_LOCATION: needed for a location-type FGS started from BOOT_COMPLETED. */
    BACKGROUND_LOCATION,

    /** android.car.permission.CAR_SPEED (dangerous). */
    CAR_SPEED,
}

data class GrantCheck(
    val events: List<RecorderEvent>,
    /** Non-empty → a re-grant prompt is queued; it may only be shown while parked ([regrantPromptAllowed]). */
    val missing: Set<RecorderPermission>,
)

/**
 * Grant guard. [previous] is null at service start: every missing grant is recorded again, so each session's
 * timeline states what it lacked. While running, only changes are recorded.
 *
 * Known degradations: the Pleos Settings location toggle flips only "while in use"; off revokes
 * it and the system kills the process, so the outage shows as a restart gap. Background location can be lost
 * (AOSP permission page, revocation) but the Pleos page cannot grant it back; only the standard request can.
 */
fun checkGrants(previous: Set<RecorderPermission>?, granted: Set<RecorderPermission>): GrantCheck {
    val missing = RecorderPermission.entries.toSet() - granted
    val events = if (previous == null) {
        missing.map { RecorderEvent.PermissionDegraded(it) }
    } else {
        RecorderPermission.entries.mapNotNull {
            when {
                it in previous && it !in granted -> RecorderEvent.PermissionDegraded(it)
                it !in previous && it in granted -> RecorderEvent.PermissionRestored(it)
                else -> null
            }
        }
    }
    return GrantCheck(events, missing)
}

/** Permission prompts are a parked-only interaction (driving restrictions). */
fun regrantPromptAllowed(missing: Set<RecorderPermission>, gear: GearPosition?): Boolean =
    missing.isNotEmpty() && gear == GearPosition.PARK
