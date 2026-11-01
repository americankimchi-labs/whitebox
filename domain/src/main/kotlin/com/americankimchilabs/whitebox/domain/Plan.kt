package com.americankimchilabs.whitebox.domain

/**
 * The owner's plan. Recording, sealing, every evidence summary and airbag crashes kept ten years are the same on every
 * plan; plans differ in how much is kept and in a few extras. Who is on which plan is set on the server (an admin
 * switch, or a purchase through its payment port); the car learns it and records each change in the chain.
 */
enum class Tier { FREE, PLUS, PRO }

/** What a [tier] includes. */
data class Entitlements(val tier: Tier) {
    /** Monthly price in won (Free is free). */
    val monthlyWon: Int get() = when (tier) { Tier.FREE -> 0; Tier.PLUS -> 2_000; Tier.PRO -> 4_000 }

    /** Days of everyday driving the cloud keeps and the phone shows. */
    val historyDays: Int get() = when (tier) { Tier.FREE -> 7; Tier.PLUS -> 14; Tier.PRO -> 30 }

    /** Kept moments, crashes without an airbag and handover reports in the library at once (extended ones count). */
    val librarySlots: Int get() = when (tier) { Tier.FREE -> 3; Tier.PLUS -> 12; Tier.PRO -> 120 }

    /** Saved dashcam clips at once (extended ones count): clips are what storage costs. */
    val clipSlots: Int get() = when (tier) { Tier.FREE -> 2; Tier.PLUS -> 4; Tier.PRO -> 10 }

    /** Handover mode (대리 운전). */
    val handover: Boolean get() = tier != Tier.FREE

    /** The printable accident account for insurers and the police. */
    val accidentReport: Boolean get() = tier == Tier.PRO

    /**
     * Clips the car attaches by itself from a built-in camera. No head unit gives apps camera access today, so this
     * only marks who gets it once it exists; dashcam clips picked on the phone work the same on every plan.
     */
    val builtInCameraClips: Boolean get() = tier != Tier.FREE

    companion object {
        /** The plan of a car whose owner has none on record (and of a car without the cloud): for this build, Pro. */
        val DEFAULT = Tier.PRO

        /** The plans above [tier], for the phone's upgrade buttons ("플러스로 업그레이드"); none above Pro. */
        fun above(tier: Tier): List<Tier> = Tier.entries.filter { it > tier }
    }
}

/**
 * A one-off purchase that keeps one capsule (and its clip) longer, on any plan, past any cancellation. It still uses
 * its slot: the limits are the limits.
 */
enum class Extension(val years: Int, val priceWon: Int) {
    ONE_YEAR(1, 9_900),
    THREE_YEARS(3, 19_900),
}

/** How long things are kept. Times are wall-clock milliseconds. */
object Retention {
    const val DAY_MS = 86_400_000L
    const val YEAR_MS = 365 * DAY_MS

    /** Everything but an airbag crash (kept moments, crashes without an airbag, handover reports, every clip): from the event. */
    const val KEEP_DAYS = 30

    /** Airbag crashes (and a crash whose car went silent, sealed by the server), free on every plan. */
    const val SEVERE_YEARS = 10

    /** The phone warns this long before something is deleted (and offers the evidence file for free). */
    const val WARN_DAYS = 7

    /** After a move to a smaller plan, the longer history window holds this long (with a notice) before it shrinks. */
    const val DOWNGRADE_NOTICE_DAYS = 7

    /** Until when a capsule's records are kept. An extension never shortens anything. */
    fun recordsUntil(eventWall: Long, severe: Boolean, extendedUntil: Long?): Long =
        maxOf(if (severe) eventWall + SEVERE_YEARS * YEAR_MS else eventWall + KEEP_DAYS * DAY_MS, extendedUntil ?: 0)

    /** Until when a capsule's clip is kept: 30 days for every clip, an airbag crash's included, unless extended. */
    fun clipUntil(eventWall: Long, extendedUntil: Long?): Long = maxOf(eventWall + KEEP_DAYS * DAY_MS, extendedUntil ?: 0)

    /**
     * The new end after buying [e]: counted from the end of the free 30 days, or from the current extended end if
     * later, so buying early never loses time.
     */
    fun extend(eventWall: Long, extendedUntil: Long?, e: Extension): Long =
        maxOf(eventWall + KEEP_DAYS * DAY_MS, extendedUntil ?: 0) + e.years * YEAR_MS

    /** Within the warning period before [until]. */
    fun warn(until: Long, now: Long): Boolean = until - now <= WARN_DAYS * DAY_MS

    /**
     * Days of everyday driving to keep now. After a move to a smaller plan at [changedAtWall] the previous window
     * holds for [DOWNGRADE_NOTICE_DAYS] (the phone says so), then the new one applies.
     */
    fun historyDays(current: Tier, previous: Tier?, changedAtWall: Long, now: Long): Int {
        val days = Entitlements(current).historyDays
        if (previous == null || now >= changedAtWall + DOWNGRADE_NOTICE_DAYS * DAY_MS) return days
        return maxOf(days, Entitlements(previous).historyDays)
    }

    // On the car the cloud is the store; the car keeps only what it needs.

    /** Records the cloud has confirmed stay on the car this long (a capsule needs the minutes before its event). */
    const val CAR_SENT_DAYS = 3

    /** Records not yet in the cloud stay at most this long; older ones go, and the chain records the gap. */
    const val CAR_UNSENT_MAX_DAYS = 14

    /** A car without the cloud keeps this much of its own history. */
    const val CAR_ONLY_DAYS = 7
}

/** Library changes, recorded in the chain so the evidence states why something left or stayed. */
sealed interface LibraryEvent {
    /** The car learned its plan ([previous] is null the first time). */
    data class PlanChanged(val tier: Tier, val previous: Tier?) : LibraryEvent

    /** [capsuleId] left the library: the library was full and a newer moment came in. */
    data class Left(val capsuleId: String) : LibraryEvent

    /** [capsuleId] came back into the library (an upgrade made room while it was held). */
    data class Returned(val capsuleId: String) : LibraryEvent

    /** The owner extended [capsuleId] (records and clip) until [untilWall]. */
    data class Extended(val capsuleId: String, val untilWall: Long) : LibraryEvent
}

/** Where a capsule stands in the driver's library. */
enum class LibraryStatus {
    /** An airbag crash (큰 사고): kept ten years, never counts, never leaves. */
    SEVERE,

    /** In the library, using one of the plan's slots. */
    KEPT,

    /** Extended by a purchase: kept until its extended end, still using its slot, never made to leave. */
    EXTENDED,

    /** The driver answered "아니" to the keep question. */
    RELEASED,

    /**
     * Out of a full library (an older unpaid one making room, or a new moment when every slot is paid): hidden, held
     * until 30 days after its event, and back in the library if an upgrade makes room before then.
     */
    LEFT,

    /** Past its end. */
    EXPIRED,
}

/** One capsule (or handover report) as the library sees it: from its seals, its answer and its library records. */
data class LibraryItem(
    val id: String,
    val trigger: CapsuleTrigger,
    /** When it was first sealed (the event; a handover report: the end of the trip). */
    val eventWall: Long,
    val released: Boolean = false,
    val left: Boolean = false,
    val extendedUntil: Long? = null,
    /** Kept ten years outside the slots: an airbag crash, or the server's seal of a crash whose car went silent. */
    val severe: Boolean = trigger.severe,
) {
    fun status(now: Long): LibraryStatus = when {
        severe -> LibraryStatus.SEVERE
        left -> if (now < recordsUntil) LibraryStatus.LEFT else LibraryStatus.EXPIRED
        extendedUntil != null && now < extendedUntil -> LibraryStatus.EXTENDED
        now >= Retention.recordsUntil(eventWall, false, extendedUntil) -> LibraryStatus.EXPIRED
        released -> LibraryStatus.RELEASED
        else -> LibraryStatus.KEPT
    }

    /** Until when its records are kept (an item that left: 30 days from its event). */
    val recordsUntil: Long get() =
        if (left && !severe) eventWall + Retention.KEEP_DAYS * Retention.DAY_MS else Retention.recordsUntil(eventWall, severe, extendedUntil)

    /** Still openable by the owner (in the library, extended or an airbag crash). */
    fun visible(now: Long) = status(now).let { it == LibraryStatus.SEVERE || it == LibraryStatus.KEPT || it == LibraryStatus.EXTENDED }

    /** Uses one of the plan's slots now (kept or extended). */
    fun usesSlot(now: Long) = status(now).let { it == LibraryStatus.KEPT || it == LibraryStatus.EXTENDED }

    companion object {
        /**
         * The item that leaves when [newId] comes into a library holding more than [slots] slot-using items (one in,
         * one out — a move to a smaller plan never empties the library at once; older items above the new limit stay
         * until their own end): the oldest unpaid near-miss, then the oldest unpaid crash without an airbag. A paid
         * (extended) record never leaves before the end that was paid for, so when only paid ones are left the new
         * moment itself is held instead — hidden, and back in the library if an upgrade makes room in time.
         */
        fun leaving(items: List<LibraryItem>, newId: String, slots: Int, now: Long): LibraryItem? {
            val using = items.filter { it.usesSlot(now) }
            val new = using.firstOrNull { it.id == newId } ?: return null
            if (using.size <= slots) return null
            return using.filter { it.id != newId && it.status(now) == LibraryStatus.KEPT }
                .sortedWith(compareBy({ it.trigger.crash }, { it.eventWall })).firstOrNull() ?: new
        }

        /** Items that come back when the library has room again (an upgrade): held ones, newest first, as room allows. */
        fun returning(items: List<LibraryItem>, slots: Int, now: Long): List<LibraryItem> {
            val room = slots - items.count { it.usesSlot(now) }
            if (room <= 0) return emptyList()
            return items.filter { it.status(now) == LibraryStatus.LEFT }.sortedByDescending { it.eventWall }.take(room)
        }
    }
}
