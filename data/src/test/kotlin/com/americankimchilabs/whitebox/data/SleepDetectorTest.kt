package com.americankimchilabs.whitebox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A car that slept says so once it wakes; a running one, or a short doze, says nothing. */
class SleepDetectorTest {
    @Test
    fun theTimeAsleepIsWhereTheTwoClocksPartWays() {
        val d = SleepDetector(minMillis = 10_000)
        assertNull(d.check(1_000, 1_000))
        assertNull(d.check(6_000, 6_000)) // awake: both clocks advanced together
        assertNull(d.check(16_000, 7_000)) // dozed 4 s: too short to record
        assertEquals(754_000L, d.check(775_000, 12_000)) // slept 12 min 34 s, then 5 s awake
        assertNull(d.check(780_000, 17_000))
    }
}
