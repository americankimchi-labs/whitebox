package com.americankimchilabs.whitebox.vehicle.pleos

import com.americankimchilabs.whitebox.domain.RoadContextType.CAUTION_POINT
import com.americankimchilabs.whitebox.domain.RoadContextType.SPEED_LIMIT
import com.americankimchilabs.whitebox.domain.VehicleSignal.RoadContext
import org.junit.Assert.assertEquals
import org.junit.Test

class RoadContextChangesTest {

    @Test
    fun theSpeedLimitIsReportedOnlyWhenItChanges() {
        val c = RoadContextChanges()
        assertEquals(listOf(RoadContext(SPEED_LIMIT, "0")), c.speedLimit(0))
        assertEquals(emptyList<RoadContext>(), c.speedLimit(0)) // the ≈ 1 Hz repeat
        assertEquals(listOf(RoadContext(SPEED_LIMIT, "50")), c.speedLimit(50))
    }

    @Test
    fun cautionPointsAreAnnouncedOnceEachAndClearOnce() {
        val c = RoadContextChanges()
        assertEquals(emptyList<RoadContext>(), c.list(CAUTION_POINT, emptyMap())) // nothing to clear yet
        assertEquals(listOf(RoadContext(CAUTION_POINT, "SCHOOL_ZONE")), c.list(CAUTION_POINT, mapOf("a" to "SCHOOL_ZONE")))
        assertEquals(emptyList<RoadContext>(), c.list(CAUTION_POINT, mapOf("a" to "SCHOOL_ZONE"))) // distance update
        assertEquals(
            listOf(RoadContext(CAUTION_POINT, "DOWNHILL_CAUTION_AREA")),
            c.list(CAUTION_POINT, mapOf("a" to "SCHOOL_ZONE", "b" to "DOWNHILL_CAUTION_AREA")),
        )
        assertEquals(listOf(RoadContext(CAUTION_POINT, "NONE")), c.list(CAUTION_POINT, emptyMap()))
        assertEquals(emptyList<RoadContext>(), c.list(CAUTION_POINT, emptyMap()))
    }
}
