package com.americankimchilabs.whitebox.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceTimelineTest {

    private class Range {
        val events = mutableListOf<DriveEvent>()
        var t = 0L

        private fun add(entry: ChainEntry): Long {
            events += DriveEvent(events.size.toLong(), entry)
            return events.last().seq
        }

        private fun at() = Timestamp(t * 1_000_000, 1_700_000_000_000 + t)
        fun started() = add(ChainEntry.Recorder(RecorderEvent.RecordingStarted(StartReason.STICKY_RESTART, null, 1), at()))
        fun signal(s: VehicleSignal) = add(ChainEntry.Signal(SignalSample(s, at())))
        fun speed(kmh: Float) = signal(VehicleSignal.Speed(kmh / 3.6f))
    }

    private fun EvidenceTimeline.line(type: Class<out ControlLine>) = lines.single { type.isInstance(it) }

    @Test
    fun lanesCoverTheWindowAndStepLanesCarryTheStateIn() {
        val r = Range()
        r.started()
        r.signal(VehicleSignal.Gear(GearPosition.DRIVE))
        r.signal(VehicleSignal.BrakePedal(false))
        r.t = 5_000; r.signal(VehicleSignal.TurnSignal(TurnSignalState.LEFT))
        var cause = -1L
        for (i in 0..300) { // 6 s … 36 s at 10 Hz; brake at 28 s, cause at 30 s
            r.t = 6_000L + i * 100
            if (r.t == 28_000L) r.signal(VehicleSignal.BrakePedal(true))
            val seq = r.speed(if (r.t < 30_000) 60f else 20f)
            if (r.t == 30_000L) cause = seq
        }

        val tl = EvidenceTimeline.build(r.events, cause)
        assertTrue(tl.speedKmh.all { it.atMs in -10_000..5_000 })
        assertEquals(-10_000L, tl.speedKmh.first().atMs)
        assertEquals(5_000L, tl.speedKmh.last().atMs)
        // Carried in from before the window, then the press 2 s before the cause.
        assertEquals(listOf(LaneStep(-10_000L, false), LaneStep(-2_000L, true)), tl.brake)
        assertEquals(listOf(LaneStep(-10_000L, TurnSignalState.LEFT)), tl.turn)
        assertEquals(listOf(LaneStep(-10_000L, GearPosition.DRIVE)), tl.gear)
        assertEquals(-10_000L, tl.startMs)
        assertEquals(5_000L, tl.endMs)
    }

    @Test
    fun controlLinesSayHowLongBeforeTheCause() {
        val r = Range()
        r.started()
        r.signal(VehicleSignal.Gear(GearPosition.DRIVE)) // re-sent on subscribe: held throughout
        r.signal(VehicleSignal.BrakePedal(false))
        r.t = 20_000; r.signal(VehicleSignal.TurnSignal(TurnSignalState.RIGHT)) // first report long after start: a change
        r.t = 26_000; r.signal(VehicleSignal.SteeringAngle(-40f))
        r.t = 27_500; r.signal(VehicleSignal.SteeringAngle(-120f))
        r.t = 28_000; r.signal(VehicleSignal.SteeringAngle(15f))
        r.t = 28_600; r.signal(VehicleSignal.BrakePedal(true))
        r.t = 30_000; val cause = r.speed(20f)
        r.t = 31_000; r.signal(VehicleSignal.BrakePedal(false)) // after the cause: not a "before" line

        val tl = EvidenceTimeline.build(r.events, cause)
        assertEquals(ControlLine.Brake(true, 1_400), tl.line(ControlLine.Brake::class.java))
        assertEquals(ControlLine.Turn(TurnSignalState.RIGHT, 10_000), tl.line(ControlLine.Turn::class.java))
        assertEquals(ControlLine.Gear(GearPosition.DRIVE, null), tl.line(ControlLine.Gear::class.java))
        assertEquals(ControlLine.SteeringPeak(-120f, 2_500), tl.line(ControlLine.SteeringPeak::class.java))
    }

    @Test
    fun aHighBeamFlashIsALineButAHighBeamLeftOnIsContext() {
        val r = Range()
        r.started()
        r.signal(VehicleSignal.Light(Lamp.HIGH_BEAM, false))
        r.t = 26_000; r.signal(VehicleSignal.Light(Lamp.HIGH_BEAM, true))
        r.t = 26_400; r.signal(VehicleSignal.Light(Lamp.HIGH_BEAM, false))
        r.t = 30_000; val cause = r.speed(40f)
        val flash = EvidenceTimeline.build(r.events, cause)
        assertEquals(ControlLine.HighBeamFlash(4_000), flash.line(ControlLine.HighBeamFlash::class.java))
        assertEquals(false, flash.context.lamps[Lamp.HIGH_BEAM])

        val held = Range()
        held.started()
        held.t = 20_000; held.signal(VehicleSignal.Light(Lamp.HIGH_BEAM, true))
        held.t = 30_000; val c2 = held.speed(40f)
        val tl = EvidenceTimeline.build(held.events, c2)
        assertTrue(tl.lines.none { it is ControlLine.HighBeamFlash })
        assertEquals(true, tl.context.lamps[Lamp.HIGH_BEAM])
    }

    @Test
    fun contextIsTheLastRecordedStateAtTheCause() {
        val r = Range()
        r.started()
        r.signal(VehicleSignal.SeatBelt("ROW_1_LEFT", true))
        r.signal(VehicleSignal.Door("ROW_1_LEFT", false))
        r.signal(VehicleSignal.Wiper("FRONT_WINDSHIELD", "OFF"))
        r.signal(VehicleSignal.RoadContext(RoadContextType.SPEED_LIMIT, "0"))
        r.t = 5_000; r.signal(VehicleSignal.Wiper("FRONT_WINDSHIELD", "CONTINUOUS_LEVEL_2"))
        r.signal(VehicleSignal.RoadContext(RoadContextType.SPEED_LIMIT, "50"))
        r.signal(VehicleSignal.RoadContext(RoadContextType.CAUTION_POINT, "SCHOOL_ZONE"))
        r.signal(VehicleSignal.RoadContext(RoadContextType.ENFORCEMENT_CAMERA, "SPEED_CAMERA"))
        r.t = 8_000; r.signal(VehicleSignal.RoadContext(RoadContextType.ENFORCEMENT_CAMERA, "NONE"))
        r.t = 10_000; val cause = r.speed(30f)
        r.t = 11_000; r.signal(VehicleSignal.Door("ROW_1_LEFT", true)) // after the cause

        val c = EvidenceTimeline.build(r.events, cause).context
        assertEquals(mapOf("ROW_1_LEFT" to true), c.seatBelts)
        assertEquals(mapOf("ROW_1_LEFT" to false), c.doors)
        assertEquals("CONTINUOUS_LEVEL_2", c.wipers["FRONT_WINDSHIELD"])
        assertEquals(50, c.speedLimitKmh)
        assertEquals(listOf(RoadContextType.CAUTION_POINT to "SCHOOL_ZONE"), c.roadAlerts)
        assertNull(c.cruise)
    }

    @Test
    fun anUnknownSpeedLimitIsNull() {
        val r = Range()
        r.started()
        r.signal(VehicleSignal.RoadContext(RoadContextType.SPEED_LIMIT, "0"))
        val cause = r.speed(30f)
        val tl = EvidenceTimeline.build(r.events, cause)
        assertNull(tl.context.speedLimitKmh)
        assertEquals(0L, tl.startMs) // the range starts at the cause and ends there
        assertEquals(0L, tl.endMs)
    }
}
