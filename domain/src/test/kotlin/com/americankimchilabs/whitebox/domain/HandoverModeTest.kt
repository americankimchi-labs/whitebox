package com.americankimchilabs.whitebox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoverModeTest {
    /** A car feeding handover mode: one clock in ms, gapless seqs. */
    private class Car(val mode: HandoverMode = HandoverMode()) {
        var ms = 1_700_000_000_000L
        var seq = 0L
        val out = mutableListOf<HandoverOutput>()

        private fun feed(entry: ChainEntry) = mode.onEvent(DriveEvent(seq++, entry)).also { out += it }
        private fun ts() = Timestamp(ms * 1_000_000, ms)
        fun signal(s: VehicleSignal) = feed(ChainEntry.Signal(SignalSample(s, ts())))
        fun gear(g: GearPosition) = signal(VehicleSignal.Gear(g))
        fun engine(on: Boolean) = signal(VehicleSignal.Ignition(if (on) IgnitionState.ON else IgnitionState.OFF))
        fun speed(kmh: Float) = signal(VehicleSignal.Speed(kmh / 3.6f))
        fun road(type: RoadContextType, value: String) = signal(VehicleSignal.RoadContext(type, value))
        fun trigger(t: CapsuleTrigger, measured: Float) = feed(ChainEntry.Escalation(EscalationEvent.Triggered(t, seq - 1, measured), ts()))
        fun later(dms: Long) { ms += dms }
        fun tick() = mode.tick(ms).also { out += it }

        fun events() = out.filterIsInstance<HandoverOutput.Append>().map { it.event }
        fun said() = out.filterIsInstance<HandoverOutput.Say>().map { it.line }
        fun seals() = out.filterIsInstance<HandoverOutput.SealTrip>()
        fun observed(kind: HandoverEventKind) = events().filterIsInstance<HandoverEvent.Observed>().filter { it.kind == kind }
    }

    @Test
    fun armedOnTheCarItWaitsThroughAnythingParkedAndStartsWhenTheDriverLeavesPark() {
        val car = Car()
        car.gear(GearPosition.PARK)
        car.mode.arm(HandoverSource.CAR, car.ms, car.ms).also { car.out += it }
        car.later(20 * 60_000) // the 대리기사 takes 20 minutes; the owner switched the engine off and on meanwhile
        car.engine(false); car.engine(true); car.tick()
        assertEquals(HandoverPhase.ARMED, car.mode.phase)
        assertTrue(car.mode.carMayCancel())

        car.gear(GearPosition.DRIVE)
        assertEquals(HandoverPhase.ACTIVE, car.mode.phase)
        assertFalse(car.mode.carMayCancel()) // the driver cannot turn it off from the car
        assertEquals(3L, (car.events().filterIsInstance<HandoverEvent.Started>().single()).fromSeq)
        // No route in the navigation: recorded, asked for, and the maps app brought forward while still standing.
        assertEquals(1, car.observed(HandoverEventKind.NO_ROUTE).size)
        assertEquals(listOf(VoiceLine.HANDOVER_STARTED, VoiceLine.HANDOVER_SET_DESTINATION), car.said())
        assertTrue(HandoverOutput.OpenMaps in car.out)
    }

    @Test
    fun aDestinationSetBeforehandMeansNoPromptAtTheStart() {
        val car = Car()
        car.gear(GearPosition.PARK)
        car.road(RoadContextType.ROUTE, "GUIDING") // the owner set it while handing over the keys
        car.mode.arm(HandoverSource.CAR, car.ms, car.ms)
        car.gear(GearPosition.DRIVE)
        assertEquals(listOf(VoiceLine.HANDOVER_STARTED), car.said())
        assertTrue(car.observed(HandoverEventKind.NO_ROUTE).isEmpty())
        assertFalse(HandoverOutput.OpenMaps in car.out)
    }

    @Test
    fun aPhoneRequestThatArrivesAfterTheDriveBeganStartsTheTripAtThatDeparture() {
        val car = Car()
        car.gear(GearPosition.PARK)
        val requested = car.ms // the owner tapped on the phone; the car had no network yet
        car.later(5 * 60_000)
        car.gear(GearPosition.DRIVE) // seq 1
        car.speed(30f)
        car.later(2 * 60_000)
        car.mode.arm(HandoverSource.PHONE, requested, car.ms).also { car.out += it }
        assertEquals(HandoverPhase.ACTIVE, car.mode.phase)
        assertEquals(1L, car.events().filterIsInstance<HandoverEvent.Started>().single().fromSeq)
        assertFalse(HandoverOutput.OpenMaps in car.out) // already moving: no app pops up
    }

    @Test
    fun aPhoneRequestMadeAfterThisDriveBeganWaitsForTheNextDeparture() {
        val car = Car()
        car.gear(GearPosition.DRIVE) // the owner is still driving to the restaurant
        car.later(60 * 60_000)
        car.mode.arm(HandoverSource.PHONE, car.ms, car.ms)
        assertEquals(HandoverPhase.ARMED, car.mode.phase)
        car.gear(GearPosition.PARK)
        car.later(2 * 3_600_000)
        car.gear(GearPosition.DRIVE)
        assertEquals(HandoverPhase.ACTIVE, car.mode.phase)
    }

    @Test
    fun aStopOnTheWayResumesTheSameTripAndOnlyTheGracePeriodEndsIt() {
        val car = Car()
        car.gear(GearPosition.PARK)
        car.mode.arm(HandoverSource.CAR, car.ms, car.ms)
        car.gear(GearPosition.DRIVE)
        car.later(10 * 60_000)
        car.gear(GearPosition.PARK)
        car.engine(false) // at the apartment entrance
        assertEquals(HandoverPhase.ENDING, car.mode.phase)
        assertEquals(HandoverOutput.SealTrip("H1", 1, final = false), car.seals().single())
        car.later(3 * 60_000)
        car.engine(true)
        car.gear(GearPosition.REVERSE) // down to the parking spot
        assertEquals(HandoverPhase.ACTIVE, car.mode.phase)
        assertTrue(HandoverEvent.Resumed in car.events())
        car.later(60_000)
        car.gear(GearPosition.PARK)
        car.engine(false)
        car.later(Tuning.HANDOVER_GRACE_MS - 1)
        car.tick()
        assertEquals(HandoverPhase.ENDING, car.mode.phase)
        car.later(1)
        car.tick()
        assertEquals(HandoverPhase.OFF, car.mode.phase)
        assertEquals(HandoverEvent.Ended(HandoverEndReason.IGNITION_OFF, "H1"), car.events().last())
        assertEquals(HandoverOutput.SealTrip("H1", 1, final = true), car.seals().last()) // one report for the whole trip
    }

    @Test
    fun parkingRightAfterArrivalEndsAndALongParkWithTheEngineOnToo() {
        val arrived = Car()
        arrived.gear(GearPosition.PARK)
        arrived.road(RoadContextType.ROUTE, "GUIDING")
        arrived.mode.arm(HandoverSource.CAR, arrived.ms, arrived.ms)
        arrived.gear(GearPosition.DRIVE)
        arrived.road(RoadContextType.ROUTE, "ARRIVED")
        arrived.later(30_000)
        arrived.gear(GearPosition.PARK)
        assertEquals(HandoverEvent.Ending(HandoverEndReason.ARRIVED), arrived.events().last())

        val parked = Car()
        parked.gear(GearPosition.PARK)
        parked.mode.arm(HandoverSource.CAR, parked.ms, parked.ms)
        parked.gear(GearPosition.DRIVE)
        parked.gear(GearPosition.PARK)
        parked.later(Tuning.HANDOVER_PARKED_END_MS - 1)
        parked.tick()
        assertEquals(HandoverPhase.ACTIVE, parked.mode.phase) // a short stop is not the end
        parked.later(1)
        parked.tick()
        assertEquals(HandoverEvent.Ending(HandoverEndReason.PARKED), parked.events().last())
    }

    @Test
    fun theOwnerEndsItFromThePhoneAtAnyTimeAndAnUnusedArmingExpires() {
        val car = Car()
        car.gear(GearPosition.PARK)
        car.mode.arm(HandoverSource.PHONE, car.ms, car.ms)
        car.gear(GearPosition.DRIVE)
        car.mode.end(HandoverSource.PHONE, car.ms).also { car.out += it }
        assertEquals(HandoverEvent.Ended(HandoverEndReason.OWNER, "H1"), car.events().last())
        assertEquals(HandoverOutput.SealTrip("H1", 1, final = true), car.seals().single())

        val idle = Car()
        idle.mode.arm(HandoverSource.PHONE, idle.ms, idle.ms)
        idle.later(Tuning.HANDOVER_ARM_EXPIRY_MS)
        idle.tick()
        assertEquals(HandoverEvent.Cancelled(HandoverSource.PHONE, HandoverEndReason.EXPIRED), idle.events().last())
        assertEquals(HandoverPhase.OFF, idle.mode.phase)
    }

    @Test
    fun aRestartMidTripEndsItUnlessTheCarDrivesOn() {
        val car = Car()
        car.gear(GearPosition.PARK)
        car.mode.arm(HandoverSource.CAR, car.ms, car.ms)
        car.gear(GearPosition.DRIVE)
        val saved = car.mode.state

        val after = Car(HandoverMode(saved)).also { it.ms = car.ms + 60_000; it.seq = 50 }
        after.mode.restored(after.ms).also { after.out += it }
        assertEquals(HandoverEvent.Ending(HandoverEndReason.RESTART), after.events().single())
        after.gear(GearPosition.DRIVE) // the app came back while still driving
        assertEquals(HandoverPhase.ACTIVE, after.mode.phase)
        assertTrue(HandoverEvent.Resumed in after.events())
    }

    @Test
    fun speedingCountsOnlyWhenPassingAnEnforcementCameraOverTheLimitByMoreThanTheMargin() {
        val car = Car()
        car.gear(GearPosition.PARK)
        car.road(RoadContextType.ROUTE, "GUIDING")
        car.mode.arm(HandoverSource.CAR, car.ms, car.ms)
        car.gear(GearPosition.DRIVE)
        car.speed(130f) // fast, but no camera: not what Korean roads enforce
        car.road(RoadContextType.SPEED_ENFORCEMENT, "POINT;80;0;600") // announced 600 m ahead, still slowing down
        assertTrue(car.observed(HandoverEventKind.SPEEDING).isEmpty())
        car.later(1_000); car.speed(89f)
        car.road(RoadContextType.SPEED_ENFORCEMENT, "POINT;80;0;80") // passing it, within the margin
        assertTrue(car.observed(HandoverEventKind.SPEEDING).isEmpty())
        car.later(100); car.speed(95f)
        car.later(100); car.speed(97f)
        assertEquals(listOf(95f to 80f), car.observed(HandoverEventKind.SPEEDING).map { it.value to it.limit })
        assertEquals(1, car.said().count { it == VoiceLine.HANDOVER_SLOW_DOWN })
        // The next camera counts again, but the same reminder is not repeated within a minute.
        car.road(RoadContextType.SPEED_ENFORCEMENT, "NONE")
        car.later(20_000)
        car.speed(75f)
        car.road(RoadContextType.SPEED_ENFORCEMENT, "POINT;60;0;50")
        assertEquals(2, car.observed(HandoverEventKind.SPEEDING).size)
        assertEquals(1, car.said().count { it == VoiceLine.HANDOVER_SLOW_DOWN })
        // An enforced section is judged on its average when it ends, not on the way through it.
        car.road(RoadContextType.SPEED_ENFORCEMENT, "NONE")
        car.later(60_000)
        car.road(RoadContextType.SPEED_ENFORCEMENT, "SECTION;100;115;5000")
        assertTrue(car.observed(HandoverEventKind.SECTION_SPEEDING).isEmpty())
        car.road(RoadContextType.SPEED_ENFORCEMENT, "SECTION;100;112;40")
        assertEquals(listOf(112f to 100f), car.observed(HandoverEventKind.SECTION_SPEEDING).map { it.value to it.limit })
        // Within the margin on average: nothing, even when the section is dropped.
        car.road(RoadContextType.SPEED_ENFORCEMENT, "NONE")
        car.road(RoadContextType.SPEED_ENFORCEMENT, "SECTION;80;88;3000")
        car.road(RoadContextType.SPEED_ENFORCEMENT, "NONE")
        assertEquals(1, car.observed(HandoverEventKind.SECTION_SPEEDING).size)
    }

    @Test
    fun hardAccelerationBrakingSteeringAndLeavingTheRouteAreRecordedWithShortPoliteLines() {
        val car = Car()
        car.gear(GearPosition.PARK)
        car.road(RoadContextType.ROUTE, "GUIDING")
        car.mode.arm(HandoverSource.CAR, car.ms, car.ms)
        car.gear(GearPosition.DRIVE)
        car.speed(0f); car.later(500); car.speed(12f) // from standstill: the escalation engine's business, not this
        assertTrue(car.observed(HandoverEventKind.HARD_ACCEL).isEmpty())
        car.later(2_000); car.speed(30f); car.later(400); car.speed(36f); car.later(400); car.speed(42f)
        assertEquals(12f, car.observed(HandoverEventKind.HARD_ACCEL).single().value, 0.01f)
        car.later(100); car.speed(44f) // the same acceleration, not a second one
        assertEquals(1, car.observed(HandoverEventKind.HARD_ACCEL).size)

        car.later(10_000)
        car.trigger(CapsuleTrigger.HARD_BRAKE, -28f)
        car.trigger(CapsuleTrigger.EVASIVE_STEER, 320f)
        assertEquals(-28f, car.observed(HandoverEventKind.HARD_BRAKE).single().value)
        assertEquals(320f, car.observed(HandoverEventKind.SHARP_STEER).single().value)
        assertEquals(1, car.said().count { it == VoiceLine.HANDOVER_DRIVE_SAFELY }) // once a minute
        car.trigger(CapsuleTrigger.DELTA_V, -25f)
        assertEquals(-25f, car.observed(HandoverEventKind.CRASH).single().value) // in the report; no extra line spoken

        car.road(RoadContextType.ROUTE, "REROUTED")
        assertEquals(1, car.observed(HandoverEventKind.ROUTE_LEFT).size)
        assertTrue(VoiceLine.HANDOVER_CHECK_ROUTE in car.said())
    }

    @Test
    fun nothingIsObservedWhileOff() {
        val car = Car()
        car.gear(GearPosition.DRIVE)
        car.road(RoadContextType.SPEED_ENFORCEMENT, "POINT;50;0;10")
        car.speed(90f)
        car.trigger(CapsuleTrigger.HARD_BRAKE, -30f)
        assertTrue(car.out.isEmpty())
    }
}
