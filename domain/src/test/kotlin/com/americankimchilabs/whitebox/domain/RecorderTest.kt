package com.americankimchilabs.whitebox.domain

import com.americankimchilabs.whitebox.domain.RecorderEvent.PermissionDegraded
import com.americankimchilabs.whitebox.domain.RecorderEvent.PermissionRestored
import com.americankimchilabs.whitebox.domain.RecorderPermission.BACKGROUND_LOCATION
import com.americankimchilabs.whitebox.domain.RecorderPermission.CAR_SPEED
import com.americankimchilabs.whitebox.domain.RecorderPermission.LOCATION
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecorderTest {

    private fun at(elapsedMs: Long, wallMs: Long) = Timestamp(elapsedMs * 1_000_000, wallMs)

    private fun session(boot: Int = 7, clean: Boolean = false, restarts: Int = 0) =
        SessionRecord(bootCount = boot, lastAlive = at(10_000, 1_000_000), cleanStop = clean, restartCount = restarts)

    @Test
    fun firstEverStartHasNoGap() {
        val e = classifyStart(null, StartReason.APP_OPENED, bootCount = 7, now = at(5_000, 900_000))
        assertNull(e.gap)
        assertEquals(0, e.restartCount)
    }

    @Test
    fun stickyRestartInSameBootIsProcessDiedWithMonotonicDurationAndCount() {
        val e = classifyStart(session(restarts = 1), StartReason.STICKY_RESTART, bootCount = 7, now = at(14_500, 1_009_999))
        val gap = e.gap!!
        assertEquals(GapCause.PROCESS_DIED, gap.cause)
        assertEquals(4_500, gap.durationMillis) // elapsed clock, not the (jumped) wall clock
        assertFalse(gap.acrossBoots)
        assertEquals(2, e.restartCount)
    }

    @Test
    fun reopenAfterUncleanEndInSameBootIsStoppedWithoutRestart() {
        val e = classifyStart(session(restarts = 3), StartReason.APP_OPENED, bootCount = 7, now = at(70_000, 1_060_000))
        assertEquals(GapCause.STOPPED_WITHOUT_RESTART, e.gap!!.cause)
        assertEquals(0, e.restartCount)
    }

    @Test
    fun newBootWithoutCleanStopIsPowerCycleMeasuredOnWallClock() {
        val e = classifyStart(session(), StartReason.BOOT, bootCount = 8, now = at(13_500, 4_600_000))
        val gap = e.gap!!
        assertEquals(GapCause.POWER_CYCLE, gap.cause)
        assertEquals(3_600_000, gap.durationMillis)
        assertTrue(gap.acrossBoots)
    }

    @Test
    fun cleanStopWinsOverBootChange() {
        val e = classifyStart(session(clean = true), StartReason.BOOT, bootCount = 8, now = at(13_500, 4_600_000))
        assertEquals(GapCause.CLEAN_STOP, e.gap!!.cause)
    }

    @Test
    fun startRecordsEveryMissingGrantEvenIfAlreadyMissingBefore() {
        val check = checkGrants(previous = null, granted = setOf(LOCATION, CAR_SPEED))
        assertEquals(listOf(PermissionDegraded(BACKGROUND_LOCATION)), check.events)
        assertEquals(setOf(BACKGROUND_LOCATION), check.missing)
    }

    @Test
    fun lostGrantsAreDegradedThenPartialRestoreKeepsTheRestQueued() {
        val all = RecorderPermission.entries.toSet()
        val off = checkGrants(previous = all, granted = setOf(CAR_SPEED))
        assertEquals(listOf(PermissionDegraded(LOCATION), PermissionDegraded(BACKGROUND_LOCATION)), off.events)
        // Only "while in use" comes back (e.g. the Pleos toggle); background stays missing and stays queued.
        val on = checkGrants(previous = setOf(CAR_SPEED), granted = setOf(LOCATION, CAR_SPEED))
        assertEquals(listOf(PermissionRestored(LOCATION)), on.events)
        assertEquals(setOf(BACKGROUND_LOCATION), on.missing)
    }

    @Test
    fun noChangeNoEvents() {
        val all = RecorderPermission.entries.toSet()
        assertTrue(checkGrants(all, all).events.isEmpty())
    }

    @Test
    fun regrantPromptOnlyWhileParked() {
        val missing = setOf(BACKGROUND_LOCATION)
        assertTrue(regrantPromptAllowed(missing, GearPosition.PARK))
        assertFalse(regrantPromptAllowed(missing, GearPosition.DRIVE))
        assertFalse(regrantPromptAllowed(missing, null))
        assertFalse(regrantPromptAllowed(emptySet(), GearPosition.PARK))
    }

    @Test
    fun theFullScreenIsShownOnlyWhenSomethingSaysTheCarIsParked() {
        assertTrue(screenParked(GearPosition.PARK, 0f))
        assertTrue(screenParked(GearPosition.PARK, null))
        // Any other gear is driving, even standing at a light.
        for (gear in listOf(GearPosition.DRIVE, GearPosition.REVERSE, GearPosition.NEUTRAL, GearPosition.MANUAL)) {
            assertFalse(screenParked(gear, 0f))
            assertFalse(screenParked(gear, 13.9f))
            assertFalse(screenParked(gear, null))
        }
        // Gear not known: a moving car is never treated as parked.
        assertFalse(screenParked(null, 40f))
        assertFalse(screenParked(null, 1f))
        assertFalse(screenParked(GearPosition.UNKNOWN, 40f))
        assertTrue(screenParked(null, 0f))
        assertTrue(screenParked(GearPosition.UNKNOWN, 0.5f))
        // Neither known (first run, before the speed grant): the setup must be reachable.
        assertTrue(screenParked(null, null))
    }
}
