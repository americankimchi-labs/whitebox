package com.americankimchilabs.whitebox.domain

import com.americankimchilabs.whitebox.domain.EngineOutput.Append
import com.americankimchilabs.whitebox.domain.EngineOutput.IncidentPrompt
import com.americankimchilabs.whitebox.domain.EngineOutput.Seal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Speed traces at the measured 10 Hz, one test per row of the trigger trade-off table (see [Tuning]). */
class EscalationEngineTest {

    private class Trace {
        val engine = EscalationEngine()
        val out = mutableListOf<EngineOutput>()
        var seq = 0L
        var t = 0L
        val seqAt = mutableMapOf<Long, Long>()

        private fun at(ms: Long) = Timestamp(ms * 1_000_000, 1_700_000_000_000 + ms)

        fun feed(entry: ChainEntry) {
            seqAt.putIfAbsent(entry.at.elapsedNanos / 1_000_000, seq)
            out += engine.onEvent(DriveEvent(seq++, entry))
        }

        fun signal(s: VehicleSignal) = feed(ChainEntry.Signal(SignalSample(s, at(t))))
        fun brake(on: Boolean) = signal(VehicleSignal.BrakePedal(on))
        fun speed(kmh: Float) = signal(VehicleSignal.Speed(kmh / 3.6f))
        fun steer(degrees: Float) = signal(VehicleSignal.SteeringAngle(degrees))

        /** Samples every 100 ms from the current time for [ms], linearly from [from] to [to]. */
        fun ramp(ms: Long, from: Float, to: Float = from) {
            val steps = ms / 100
            for (i in 0 until steps) {
                speed(from + (to - from) * i / steps)
                t += 100
            }
        }

        fun step(kmh: Float, gapMs: Long = 100) {
            speed(kmh)
            t += gapMs
        }

        fun restart() = feed(
            ChainEntry.Recorder(RecorderEvent.RecordingStarted(StartReason.STICKY_RESTART, null, 1), at(t)),
        )

        val triggers get() = out.filterIsInstance<Append>().map { it.event }.filterIsInstance<EscalationEvent.Triggered>()
        val severe get() = triggers.filter { it.trigger.severe }
        val levels get() = out.filterIsInstance<Append>().map { it.event }.filterIsInstance<EscalationEvent.LevelChanged>().map { it.level }
        val seals get() = out.filterIsInstance<Seal>()
        val prompts get() = out.filterIsInstance<IncidentPrompt>()
    }

    private fun Trace.stop(fromKmh: Float, kmhPerSecond: Float) {
        brake(true)
        var v = fromKmh
        while (v > 0f) {
            step(v)
            v -= kmhPerSecond / 10
        }
        ramp(11_000, 0f) // stillness
    }

    @Test
    fun emergencyStopAt1gIsANearMissCapsuleKeptWithoutAsking() {
        val tr = Trace()
        tr.ramp(130_000, 80f)
        tr.stop(80f, kmhPerSecond = 35f)
        assertEquals(listOf(CapsuleTrigger.HARD_BRAKE), tr.triggers.map { it.trigger })
        assertEquals(listOf(EscalationLevel.ALERT, EscalationLevel.NORMAL), tr.levels)
        assertEquals(listOf(false, true), tr.seals.map { it.final })
        // ALERT fires at 130.3 s (the first sample with a 300 ms span of 35 km/h/s), so the pre-window starts at 10.3 s.
        assertTrue(tr.seals.all { it.firstSeq == tr.seqAt[10_300] && it.trigger == CapsuleTrigger.HARD_BRAKE })
        assertTrue(tr.prompts.isEmpty()) // the car stopped: nothing to ask while driving
        assertEquals(EscalationLevel.NORMAL, tr.engine.level)
    }

    @Test
    fun maxBrakingAt42KmhPerSecondNeverTriggersACrash() {
        val tr = Trace()
        tr.ramp(5_000, 100f)
        tr.stop(100f, kmhPerSecond = 42f)
        assertTrue(tr.severe.isEmpty())
        assertEquals(listOf(CapsuleTrigger.HARD_BRAKE), tr.triggers.map { it.trigger })
    }

    @Test
    fun frontalCrashTriggersDeltaVWithProvisionalThenFinalSeal() {
        val tr = Trace()
        tr.ramp(130_000, 60f)
        tr.brake(true)
        tr.step(60f)
        tr.step(35f) // 25 km/h in 100 ms
        tr.ramp(2_000, 35f, 0f)
        tr.ramp(11_000, 0f)
        val trig = tr.severe.single()
        assertEquals(CapsuleTrigger.DELTA_V, trig.trigger)
        assertEquals(-25f, trig.measured, 0.01f)
        assertEquals(listOf(false, true), tr.seals.map { it.final })
        assertTrue(tr.seals.all { it.firstSeq == tr.seqAt[10_100] })
        assertEquals(EscalationLevel.NORMAL, tr.engine.level)
        assertTrue(tr.prompts.isEmpty()) // the capsule subsumes the incident
    }

    @Test
    fun s1CatchesTwelveKmhOverTwoHundredMilliseconds() {
        val tr = Trace()
        tr.ramp(3_000, 60f)
        tr.step(54f)
        tr.step(48f)
        tr.ramp(2_000, 48f)
        assertEquals(listOf(CapsuleTrigger.SHORT_DELTA_V), tr.triggers.map { it.trigger })
    }

    @Test
    fun s2CatchesBrakedShortSpikeWhenItDoesNotRebound() {
        val tr = Trace()
        tr.ramp(3_000, 40f)
        tr.brake(true)
        tr.step(40f)
        tr.step(31f) // 9 km/h in 100 ms
        tr.step(31f)
        assertEquals(listOf(CapsuleTrigger.BRAKED_SPIKE), tr.severe.map { it.trigger })
    }

    @Test
    fun s2RejectsASpikeThatRebounds() {
        val tr = Trace()
        tr.ramp(3_000, 40f)
        tr.brake(true)
        tr.step(40f)
        tr.step(31f)
        tr.step(38f)
        assertTrue(tr.severe.isEmpty())
    }

    @Test
    fun s3RequiresASteadyBaselineSoABrakingReboundIsNotAShove() {
        val tr = Trace()
        tr.ramp(3_000, 30f)
        tr.brake(true)
        tr.step(30f)
        tr.step(22f)
        tr.step(29f) // +7 within 100 ms, but right after a drop
        assertTrue(tr.triggers.none { it.trigger == CapsuleTrigger.REAR_SHOVE })
    }

    @Test
    fun s2NeedsTheBrake() {
        val tr = Trace()
        tr.ramp(3_000, 40f)
        tr.step(31f)
        tr.step(31f)
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun s3CatchesAShoveWhileBraked() {
        val tr = Trace()
        tr.ramp(3_000, 0f)
        tr.brake(true)
        tr.step(0f)
        tr.step(7f)
        assertEquals(CapsuleTrigger.REAR_SHOVE, tr.triggers.single().trigger)
        assertEquals(7f, tr.triggers.single().measured, 0.01f)
    }

    @Test
    fun s3NeedsTheBrake() {
        val tr = Trace()
        tr.ramp(3_000, 0f)
        tr.step(0f)
        tr.step(7f)
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun brakingInReverseIsNotAShove() {
        val tr = Trace()
        tr.ramp(2_000, -10f)
        tr.brake(true)
        tr.ramp(400, -10f, 0f)
        tr.ramp(1_000, 0f)
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun singleSampleGlitchWithoutBrakeIsIgnored() {
        val tr = Trace()
        tr.ramp(3_000, 60f)
        tr.step(52f)
        tr.ramp(1_000, 60f)
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun noSpanAcrossASampleGap() {
        val tr = Trace()
        tr.ramp(3_000, 60f)
        tr.step(60f, gapMs = 400)
        tr.ramp(1_000, 30f)
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun noSpanAcrossARecorderRestart() {
        val tr = Trace()
        tr.ramp(3_000, 60f)
        tr.restart()
        tr.ramp(1_000, 30f)
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun airbagTriggersAtOnceAndTheSameImpactCountsOnce() {
        val tr = Trace()
        tr.ramp(2_000, 50f)
        tr.signal(VehicleSignal.AirbagDeployed("ROW_1_LEFT", deployed = true))
        tr.step(20f) // the same impact's speed drop, inside the refractory period
        assertEquals(listOf(CapsuleTrigger.AIRBAG), tr.triggers.map { it.trigger })
        assertFalse(tr.seals.single().final)
    }

    @Test
    fun aSecondImpactInsideAnOpenCapsuleIsRecordedAndResealed() {
        val tr = Trace()
        tr.ramp(3_000, 60f)
        tr.step(35f) // first impact
        tr.ramp(3_000, 35f) // still moving, capsule open
        tr.step(10f) // chain collision
        tr.ramp(11_000, 0f)
        assertEquals(listOf(CapsuleTrigger.DELTA_V, CapsuleTrigger.DELTA_V), tr.triggers.map { it.trigger })
        assertEquals(listOf(false, false, true), tr.seals.map { it.final })
        assertEquals(1, tr.seals.map { it.capsuleId }.distinct().size)
    }

    @Test
    fun hardBrakeThenDrivingOnAsksOnceAfterFiveSecondsAndClosesAfterThePostWindow() {
        val tr = Trace()
        tr.ramp(3_000, 60f)
        tr.brake(true)
        tr.ramp(600, 60f, 45f) // 25 km/h/s → ALERT + near-miss capsule
        tr.brake(false)
        tr.ramp(4_000, 45f)
        assertTrue(tr.prompts.isEmpty())
        tr.ramp(2_000, 45f) // calm → NORMAL; 5 s after the trigger, still driving → asked
        assertEquals(listOf(EscalationLevel.ALERT, EscalationLevel.NORMAL), tr.levels)
        val asked = tr.prompts.single().capsule
        assertEquals(CapsuleTrigger.HARD_BRAKE, asked.trigger)
        assertEquals(asked.id, tr.seals.single().capsuleId) // already sealed before asking
        tr.ramp(30_000, 45f)
        assertEquals(1, tr.prompts.size)
        assertEquals(listOf(false, true), tr.seals.map { it.final }) // closed by the post-window while driving
    }

    @Test
    fun aCrashInsideANearMissCapsuleEscalatesItAndIsNeverAsked() {
        val tr = Trace()
        tr.ramp(3_000, 60f)
        tr.brake(true)
        tr.ramp(600, 60f, 45f) // HARD_BRAKE
        tr.step(45f)
        tr.step(20f) // −25 km/h: inside the refractory period of the near-miss, still counts
        tr.ramp(6_000, 20f)
        assertEquals(listOf(CapsuleTrigger.HARD_BRAKE, CapsuleTrigger.DELTA_V), tr.triggers.map { it.trigger })
        assertEquals(listOf(EscalationLevel.ALERT, EscalationLevel.TRIGGERED), tr.levels)
        assertEquals(1, tr.seals.map { it.capsuleId }.distinct().size)
        assertEquals(CapsuleTrigger.DELTA_V, tr.seals.last().trigger)
        assertTrue(tr.prompts.isEmpty())
        tr.ramp(40_000, 20f)
        assertTrue(tr.seals.none { it.final }) // a severe capsule stays open while driving on
    }

    @Test
    fun evasiveSteeringAtSpeedIsANearMiss() {
        val tr = Trace()
        tr.ramp(3_000, 60f)
        tr.steer(0f)
        tr.t += 100
        tr.steer(45f) // 450°/s
        tr.t += 100
        tr.steer(90f) // same manoeuvre: counted once
        tr.ramp(500, 60f)
        val trig = tr.triggers.single()
        assertEquals(CapsuleTrigger.EVASIVE_STEER, trig.trigger)
        assertEquals(450f, trig.measured, 1f)
        assertEquals(listOf(EscalationLevel.ALERT), tr.levels)
    }

    @Test
    fun steeringWithTheBrakeIsBrakeAndSwerve() {
        val tr = Trace()
        tr.ramp(3_000, 60f)
        tr.brake(true)
        tr.steer(0f)
        tr.t += 100
        tr.steer(-40f)
        assertEquals(CapsuleTrigger.BRAKE_SWERVE, tr.triggers.single().trigger)
    }

    @Test
    fun laneChangeRateAndParkingManoeuvresAreNotEvasive() {
        val tr = Trace()
        tr.ramp(3_000, 60f)
        for (d in listOf(0f, 5f, 10f, 15f)) { tr.steer(d); tr.t += 100 } // 50°/s lane change
        tr.ramp(3_000, 60f, 10f)
        tr.ramp(1_000, 10f)
        for (d in listOf(0f, 60f, 120f)) { tr.steer(d); tr.t += 100 } // 600°/s, but at parking speed
        tr.steer(400f)
        tr.t += 400
        tr.steer(0f) // no rate across a 400 ms gap
        assertTrue(tr.triggers.none { it.trigger == CapsuleTrigger.EVASIVE_STEER || it.trigger == CapsuleTrigger.BRAKE_SWERVE })
    }

    @Test
    fun shiftToNeutralWhileMovingIsKept() {
        val tr = Trace()
        tr.ramp(3_000, 70f)
        tr.signal(VehicleSignal.Gear(GearPosition.DRIVE))
        tr.signal(VehicleSignal.Gear(GearPosition.NEUTRAL))
        tr.signal(VehicleSignal.Gear(GearPosition.NEUTRAL)) // repeated report
        assertEquals(CapsuleTrigger.SHIFT_TO_NEUTRAL, tr.triggers.single().trigger)
    }

    @Test
    fun neutralWhileStoppedOrWithStaleSpeedIsNot() {
        val tr = Trace()
        tr.ramp(3_000, 0f)
        tr.signal(VehicleSignal.Gear(GearPosition.NEUTRAL))
        tr.signal(VehicleSignal.Gear(GearPosition.DRIVE))
        tr.ramp(6_000, 0f, 50f)
        tr.t += 2_000 // speed went quiet
        tr.signal(VehicleSignal.Gear(GearPosition.NEUTRAL))
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun speedRisingThroughoutAThreeSecondBrakeHoldIsTheUnintendedAccelerationSignature() {
        val tr = Trace()
        tr.ramp(3_000, 30f)
        tr.brake(true)
        tr.ramp(5_000, 30f, 55f) // 5 km/h/s against the held brake
        val trig = tr.triggers.single()
        assertEquals(CapsuleTrigger.BRAKE_SPEED_RISE, trig.trigger)
        assertEquals(15f, trig.measured, 0.01f)
        assertEquals(tr.seqAt[6_000], trig.causeSeq) // the first sample 3 s into the hold
    }

    @Test
    fun downhillDragBrakingGainingSpeedSlowlyIsNothing() {
        val tr = Trace()
        tr.ramp(3_000, 50f)
        tr.brake(true)
        tr.ramp(6_000, 50f, 59f) // 1.5 km/h/s: gravity on a steep grade, light braking
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun aRiseThatStallsWithinTheHoldIsNothing() {
        val tr = Trace()
        tr.ramp(3_000, 30f)
        tr.brake(true)
        tr.ramp(2_000, 30f, 45f) // +15 in 2 s, then flat for over a second
        tr.ramp(1_500, 45f)
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun aRiseThatStartsBeforeTheBrakeDoesNotCount() {
        val tr = Trace()
        tr.ramp(3_000, 30f, 45f)
        tr.brake(true)
        tr.ramp(2_500, 45f, 47f) // the hold itself gains only 2 km/h
        assertTrue(tr.triggers.isEmpty())
    }

    /** [ms] at a steady [kmh] with the brake as it is, a road grade every second. */
    private fun Trace.steadyWithGrade(ms: Long, kmh: Float, gradePct: Float) {
        for (i in 0 until ms / 100) {
            if (i % 10 == 0L) signal(VehicleSignal.RoadGrade(gradePct))
            step(kmh)
        }
    }

    @Test
    fun brakeHeldWithoutSlowingOnAFlatRoadIsKept() {
        val tr = Trace()
        tr.steadyWithGrade(3_000, 80f, 0.5f)
        tr.brake(true)
        tr.steadyWithGrade(3_500, 80f, 0.5f)
        val trig = tr.triggers.single()
        assertEquals(CapsuleTrigger.BRAKE_NO_DECEL, trig.trigger)
        assertEquals(0f, trig.measured, 0.01f)
    }

    @Test
    fun brakeHeldWithoutSlowingUphillIsKept() {
        val tr = Trace()
        tr.steadyWithGrade(3_000, 60f, 6f)
        tr.brake(true)
        tr.steadyWithGrade(3_500, 60f, 6f)
        assertEquals(CapsuleTrigger.BRAKE_NO_DECEL, tr.triggers.single().trigger)
    }

    @Test
    fun dragBrakingDownhillAtSteadySpeedIsNothing() {
        val tr = Trace()
        tr.steadyWithGrade(3_000, 60f, -6f)
        tr.brake(true)
        tr.steadyWithGrade(6_000, 60f, -6f)
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun aDownhillCautionAreaSuppressesItEvenOnAMisreadGrade() {
        val tr = Trace()
        tr.signal(VehicleSignal.RoadContext(RoadContextType.CAUTION_POINT, "DOWNHILL_CAUTION_AREA"))
        tr.steadyWithGrade(3_000, 60f, 0f)
        tr.brake(true)
        tr.steadyWithGrade(4_000, 60f, 0f)
        assertTrue(tr.triggers.isEmpty())
        tr.signal(VehicleSignal.RoadContext(RoadContextType.CAUTION_POINT, "NONE"))
        tr.brake(false)
        tr.steadyWithGrade(1_000, 60f, 0f)
        tr.brake(true)
        tr.steadyWithGrade(3_500, 60f, 0f)
        assertEquals(CapsuleTrigger.BRAKE_NO_DECEL, tr.triggers.single().trigger)
    }

    @Test
    fun gradeThatStopsArrivingMidHoldDoesNotCount() {
        val tr = Trace()
        tr.steadyWithGrade(3_000, 80f, 0f)
        tr.brake(true)
        tr.steadyWithGrade(1_000, 80f, 0f)
        tr.ramp(3_000, 80f) // the grade source went quiet
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun brakeHeldAtSteadySpeedIsNotFlaggedWithoutAGradeSignal() {
        val tr = Trace()
        tr.ramp(3_000, 80f)
        tr.brake(true)
        tr.ramp(6_000, 80f) // indistinguishable from drag braking downhill
        tr.ramp(6_000, 80f, 50f) // and ordinary slowing
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun repeatedBrakePressesWhileMovingAreKept() {
        val tr = Trace()
        tr.ramp(3_000, 50f)
        repeat(3) {
            tr.brake(true)
            tr.ramp(400, 50f)
            tr.brake(false)
            tr.ramp(400, 50f)
        }
        val trig = tr.triggers.single()
        assertEquals(CapsuleTrigger.BRAKE_PUMPING, trig.trigger)
        assertEquals(3f, trig.measured, 0f)
    }

    @Test
    fun parkingBrakeWhileMovingIsKept() {
        val tr = Trace()
        tr.signal(VehicleSignal.ParkingBrake(false))
        tr.ramp(3_000, 40f)
        tr.signal(VehicleSignal.ParkingBrake(true))
        assertEquals(CapsuleTrigger.PARKING_BRAKE_MOVING, tr.triggers.single().trigger)
    }

    @Test
    fun aLurchFromStandstillIsKeptButBriskPullingAwayIsNot() {
        val brisk = Trace()
        brisk.ramp(3_000, 0f)
        brisk.ramp(4_000, 0f, 40f) // 10 km/h/s
        assertTrue(brisk.triggers.isEmpty())

        val lurch = Trace()
        lurch.ramp(3_000, 0f)
        lurch.ramp(1_000, 0f, 20f)
        lurch.ramp(500, 20f)
        assertEquals(CapsuleTrigger.LURCH, lurch.triggers.single().trigger)
    }

    @Test
    fun aDoorOpenedSoonAfterADangerousMomentMarksThePostEventStop() {
        val tr = Trace()
        tr.ramp(130_000, 80f)
        tr.stop(80f, kmhPerSecond = 35f) // HARD_BRAKE, capsule closes at stillness
        tr.ramp(30_000, 0f)
        tr.signal(VehicleSignal.Door("ROW_1_LEFT", open = true))
        tr.signal(VehicleSignal.Door("ROW_1_RIGHT", open = true)) // once per dangerous moment
        assertEquals(listOf(CapsuleTrigger.HARD_BRAKE, CapsuleTrigger.POST_EVENT_EXIT), tr.triggers.map { it.trigger })
        assertEquals(2, tr.seals.map { it.capsuleId }.distinct().size)
    }

    @Test
    fun aDoorOpenedOnAnOrdinaryStopIsNothing() {
        val tr = Trace()
        tr.ramp(3_000, 30f)
        tr.ramp(6_000, 30f, 0f)
        tr.ramp(2_000, 0f)
        tr.signal(VehicleSignal.Door("ROW_1_LEFT", open = true))
        assertTrue(tr.triggers.isEmpty())
    }

    @Test
    fun aVoiceBookmarkSealsTheLastMinutesWithoutRaisingTheLevelOrAskingBack() {
        val tr = Trace()
        tr.ramp(130_000, 50f)
        tr.feed(ChainEntry.Audio(AudioEvent.Bookmark(BookmarkSource.ASSISTANT), Timestamp(tr.t * 1_000_000, 1_700_000_000_000 + tr.t)))
        tr.ramp(40_000, 50f) // drives on past the keep-question delay and the post window
        assertEquals(listOf(CapsuleTrigger.MANUAL), tr.triggers.map { it.trigger })
        assertTrue(tr.levels.isEmpty()) // no burst for a bookmark
        assertEquals(listOf(false, true), tr.seals.map { it.final })
        assertTrue(tr.seals.all { it.firstSeq == tr.seqAt[10_000] }) // the full pre-window
        assertTrue(tr.prompts.isEmpty())
    }

    @Test
    fun aBookmarkDuringANearMissCancelsTheKeepQuestion() {
        val tr = Trace()
        tr.ramp(10_000, 60f)
        tr.brake(true)
        tr.ramp(1_000, 60f, 40f) // hard brake
        tr.brake(false)
        tr.feed(ChainEntry.Audio(AudioEvent.Bookmark(BookmarkSource.OFFLINE_LISTENER), Timestamp(tr.t * 1_000_000, 1_700_000_000_000 + tr.t)))
        tr.ramp(10_000, 40f)
        assertEquals(CapsuleTrigger.HARD_BRAKE, tr.triggers.first().trigger)
        assertTrue(tr.prompts.isEmpty())
    }

    @Test
    fun postCrashGuidanceIsSpokenOnceWhenTheCarIsPutInPark() {
        val tr = Trace()
        tr.signal(VehicleSignal.Gear(GearPosition.DRIVE))
        tr.ramp(5_000, 60f)
        tr.brake(true)
        tr.step(60f)
        tr.step(35f)
        tr.ramp(2_000, 35f, 0f)
        tr.ramp(3_000, 0f)
        assertTrue(tr.out.none { it is EngineOutput.Say })
        tr.signal(VehicleSignal.Gear(GearPosition.PARK))
        tr.signal(VehicleSignal.Gear(GearPosition.DRIVE))
        tr.signal(VehicleSignal.Gear(GearPosition.PARK))
        assertEquals(listOf(EngineOutput.Say(VoiceLine.CRASH_GUIDANCE)), tr.out.filterIsInstance<EngineOutput.Say>())
    }

    @Test
    fun parkingAfterANearMissOrLongAfterACrashSaysNothing() {
        val nearMiss = Trace()
        nearMiss.signal(VehicleSignal.Gear(GearPosition.DRIVE))
        nearMiss.ramp(5_000, 80f)
        nearMiss.stop(80f, kmhPerSecond = 35f)
        nearMiss.signal(VehicleSignal.Gear(GearPosition.PARK))
        assertTrue(nearMiss.out.none { it is EngineOutput.Say })

        val late = Trace()
        late.signal(VehicleSignal.Gear(GearPosition.DRIVE))
        late.ramp(5_000, 60f)
        late.brake(true)
        late.step(60f)
        late.step(35f)
        late.ramp(2_000, 35f, 0f)
        late.ramp(Tuning.GUIDANCE_WINDOW_S * 1_000L + 1_000, 0f)
        late.signal(VehicleSignal.Gear(GearPosition.PARK))
        assertTrue(late.out.none { it is EngineOutput.Say })
    }
}
