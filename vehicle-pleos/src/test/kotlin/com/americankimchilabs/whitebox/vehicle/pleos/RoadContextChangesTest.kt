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

    @Test
    fun enforcementIsReportedAsItChangesAndRouteEventsAsTheyHappen() {
        val c = RoadContextChanges()
        fun e(v: List<com.americankimchilabs.whitebox.domain.VehicleSignal.RoadContext>) = v.map { it.value }
        assertEquals(listOf("POINT;80;0;600"), e(c.enforcement(Enforcement(false, 80, 0, 604))))
        assertEquals(emptyList<String>(), e(c.enforcement(Enforcement(false, 80, 0, 601)))) // same 10 m step
        assertEquals(listOf("POINT;80;0;590"), e(c.enforcement(Enforcement(false, 80, 0, 590))))
        assertEquals(listOf("NONE"), e(c.enforcement(null)))
        assertEquals(emptyList<String>(), e(c.enforcement(null)))
        assertEquals(listOf("GUIDING"), e(c.route("GUIDING")))
        assertEquals(emptyList<String>(), e(c.route("GUIDING")))
        assertEquals(listOf("REROUTED"), e(c.route("REROUTED")))
        assertEquals(listOf("REROUTED"), e(c.route("REROUTED"))) // every re-route counts
        assertEquals(emptyList<String>(), e(c.route("GUIDING")))
        assertEquals(listOf("ARRIVED"), e(c.route("ARRIVED")))
    }
}
