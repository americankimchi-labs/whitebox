package com.americankimchilabs.whitebox.domain

import com.americankimchilabs.whitebox.domain.Retention.DAY_MS
import com.americankimchilabs.whitebox.domain.Retention.YEAR_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanTest {
    private val t0 = 1_800_000_000_000L

    @Test
    fun plansDifferOnlyInAmountsAndExtras() {
        assertEquals(listOf(7, 14, 30), Tier.entries.map { Entitlements(it).historyDays })
        assertEquals(listOf(3, 12, 120), Tier.entries.map { Entitlements(it).librarySlots })
        assertEquals(listOf(2, 4, 10), Tier.entries.map { Entitlements(it).clipSlots })
        assertEquals(listOf(listOf(Tier.PLUS, Tier.PRO), listOf(Tier.PRO), emptyList()), Tier.entries.map { Entitlements.above(it) })
        assertEquals(listOf(false, true, true), Tier.entries.map { Entitlements(it).handover })
        assertEquals(listOf(false, false, true), Tier.entries.map { Entitlements(it).accidentReport })
    }

    @Test
    fun airbagCrashesKeepTenYearsButTheirClipsThirtyDays() {
        assertEquals(t0 + 10 * YEAR_MS, Retention.recordsUntil(t0, severe = true, extendedUntil = null))
        assertEquals(t0 + 30 * DAY_MS, Retention.recordsUntil(t0, severe = false, extendedUntil = null))
        assertEquals(t0 + 30 * DAY_MS, Retention.clipUntil(t0, null))
    }

    @Test
    fun anExtensionCountsFromTheEndOfTheFreeThirtyDaysWhenEverItIsBought() {
        val bought = Retention.extend(t0, null, Extension.ONE_YEAR)
        assertEquals(t0 + 30 * DAY_MS + YEAR_MS, bought) // bought on day 1 or day 29: the same end
        assertEquals(bought + 3 * YEAR_MS, Retention.extend(t0, bought, Extension.THREE_YEARS)) // stacks
        assertEquals(listOf(9_900, 19_900), Extension.entries.map { it.priceWon })
    }

    @Test
    fun aSmallerPlanKeepsTheLongerHistoryForTheNoticePeriod() {
        assertEquals(30, Retention.historyDays(Tier.FREE, Tier.PRO, changedAtWall = t0, now = t0 + 6 * DAY_MS))
        assertEquals(7, Retention.historyDays(Tier.FREE, Tier.PRO, changedAtWall = t0, now = t0 + 7 * DAY_MS))
        assertEquals(30, Retention.historyDays(Tier.PRO, Tier.FREE, changedAtWall = t0, now = t0)) // a bigger one at once
    }

    @Test
    fun statusFollowsSeverityAnswersSlotsExtensionsAndTime() {
        val item = LibraryItem("c", CapsuleTrigger.HARD_BRAKE, t0)
        assertEquals(LibraryStatus.KEPT, item.status(t0))
        assertEquals(LibraryStatus.EXPIRED, item.status(t0 + 30 * DAY_MS))
        assertEquals(LibraryStatus.RELEASED, item.copy(released = true).status(t0))
        assertEquals(LibraryStatus.LEFT, item.copy(left = true).status(t0))
        val extended = item.copy(extendedUntil = Retention.extend(t0, null, Extension.ONE_YEAR))
        assertEquals(LibraryStatus.EXTENDED, extended.status(t0 + 200 * DAY_MS))
        assertTrue(extended.usesSlot(t0 + 200 * DAY_MS)) // it still uses its slot
        assertEquals(LibraryStatus.SEVERE, LibraryItem("s", CapsuleTrigger.AIRBAG, t0).status(t0 + 9 * YEAR_MS))
        assertEquals(LibraryStatus.SEVERE, LibraryItem("t", CapsuleTrigger.TEST_AIRBAG, t0).status(t0 + 9 * YEAR_MS))
        // A crash-level hit without an airbag is kept like any moment; the server's seal of a silent car is a 큰 사고.
        assertEquals(LibraryStatus.EXPIRED, LibraryItem("d", CapsuleTrigger.DELTA_V, t0).status(t0 + 30 * DAY_MS))
        assertEquals(LibraryStatus.SEVERE, LibraryItem("x", CapsuleTrigger.DELTA_V, t0, severe = true).status(t0 + 9 * YEAR_MS))
        assertTrue(Retention.warn(item.recordsUntil, t0 + 23 * DAY_MS))
        assertFalse(Retention.warn(item.recordsUntil, t0 + 22 * DAY_MS))
    }

    @Test
    fun aFullLibraryLetsTheOldestGoOneInOneOut() {
        val items = (0 until 4).map { LibraryItem("c$it", CapsuleTrigger.EVASIVE_STEER, t0 + it * 1_000L) } +
            LibraryItem("airbag", CapsuleTrigger.AIRBAG, t0 - 5_000) + // never counts, never leaves
            LibraryItem("trip", CapsuleTrigger.HANDOVER, t0 + 5_000) // a handover report shares the slots
        assertEquals("c0", LibraryItem.leaving(items, newId = "trip", slots = 4, now = t0 + 6_000)?.id)
        assertNull(LibraryItem.leaving(items, newId = "trip", slots = 5, now = t0 + 6_000))

        // After a move from Pro to Free, ten kept moments stay; the next one in still lets only one go.
        val many = (0 until 10).map { LibraryItem("m$it", CapsuleTrigger.LURCH, t0 + it) } + LibraryItem("new", CapsuleTrigger.LURCH, t0 + 100)
        assertEquals("m0", LibraryItem.leaving(many, newId = "new", slots = 3, now = t0 + 200)?.id)
    }

    @Test
    fun nearMissesLeaveFirstThenCrashesAndAPaidRecordNever() {
        val paid = Retention.extend(t0, null, Extension.ONE_YEAR)
        val lib = listOf(
            LibraryItem("crash", CapsuleTrigger.DELTA_V, t0), // oldest, but a crash-level hit
            LibraryItem("brake", CapsuleTrigger.HARD_BRAKE, t0 + 1_000),
            LibraryItem("new", CapsuleTrigger.LURCH, t0 + 2_000),
        )
        assertEquals("brake", LibraryItem.leaving(lib, "new", slots = 2, now = t0 + 3_000)?.id)
        // No unpaid near-miss left: the oldest unpaid crash goes.
        val noNearMiss = lib.map { if (it.id == "brake") it.copy(extendedUntil = paid) else it }
        assertEquals("crash", LibraryItem.leaving(noNearMiss, "new", slots = 2, now = t0 + 3_000)?.id)
        // Every other slot paid: a paid record stays to its paid end, so the new moment itself is held.
        val allPaid = noNearMiss.map { if (it.id == "crash") it.copy(extendedUntil = paid) else it }
        assertEquals("new", LibraryItem.leaving(allPaid, "new", slots = 2, now = t0 + 3_000)?.id)
    }

    @Test
    fun anItemThatLeftIsHeldThirtyDaysFromItsEventAndComesBackWithRoom() {
        val left = LibraryItem("a", CapsuleTrigger.HARD_BRAKE, t0, left = true)
        assertEquals(LibraryStatus.LEFT, left.status(t0 + 29 * DAY_MS))
        assertEquals(LibraryStatus.EXPIRED, left.status(t0 + 30 * DAY_MS))
        assertFalse(left.usesSlot(t0 + 5 * DAY_MS))

        // An upgrade makes room: held ones come back newest first, as room allows; expired ones never do.
        val now = t0 + 10 * DAY_MS
        val lib = listOf(
            LibraryItem("k", CapsuleTrigger.LURCH, t0 + 5 * DAY_MS),
            LibraryItem("old", CapsuleTrigger.LURCH, t0 + DAY_MS, left = true),
            LibraryItem("newer", CapsuleTrigger.LURCH, t0 + 2 * DAY_MS, left = true),
            LibraryItem("gone", CapsuleTrigger.LURCH, t0 - 40 * DAY_MS, left = true),
        )
        assertEquals(listOf("newer"), LibraryItem.returning(lib, slots = 2, now = now).map { it.id })
        assertEquals(listOf("newer", "old"), LibraryItem.returning(lib, slots = 12, now = now).map { it.id })
    }
}
