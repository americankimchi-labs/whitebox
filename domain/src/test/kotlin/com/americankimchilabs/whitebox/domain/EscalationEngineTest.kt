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
    fun emergencyStopAt1gIsAlertThenIncidentPromptNoTrigger() {
        val tr = Trace()
        tr.ramp(130_000, 80f)
        tr.stop(80f, kmhPerSecond = 35f)
        assertTrue(tr.triggers.isEmpty())
        assertEquals(listOf(EscalationLevel.ALERT, EscalationLevel.NORMAL), tr.levels)
        val prompt = tr.prompts.single().capsule
        assertEquals(CapsuleTrigger.ALERT_RESOLVED, prompt.trigger)
        // ALERT fires at 130.3 s (the first sample with a 300 ms span of 35 km/h/s), so the pre-window starts at 10.3 s.
        assertEquals(tr.seqAt[10_300], prompt.firstSeq)
        assertEquals(EscalationLevel.NORMAL, tr.engine.level)
    }

    @Test
    fun maxBrakingAt42KmhPerSecondNeverTriggers() {
        val tr = Trace()
        tr.ramp(5_000, 100f)
        tr.stop(100f, kmhPerSecond = 42f)
        assertTrue(tr.triggers.isEmpty())
        assertEquals(1, tr.prompts.size)
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
        val trig = tr.triggers.single()
        assertEquals(CapsuleTrigger.DELTA_V, trig.trigger)
        assertEquals(-25f, trig.measuredKmh, 0.01f)
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
        assertEquals(listOf(CapsuleTrigger.BRAKED_SPIKE), tr.triggers.map { it.trigger })
    }

    @Test
    fun s2RejectsASpikeThatRebounds() {
        val tr = Trace()
        tr.ramp(3_000, 40f)
        tr.brake(true)
        tr.step(40f)
        tr.step(31f)
        tr.step(38f)
        assertTrue(tr.triggers.isEmpty())
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
        assertEquals(7f, tr.triggers.single().measuredKmh, 0.01f)
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
    fun alertClearsAfterCalmDrivingButThePromptWaitsForStillness() {
        val tr = Trace()
        tr.ramp(3_000, 60f)
        tr.brake(true)
        tr.ramp(600, 60f, 45f) // 25 km/h/s → ALERT
        tr.brake(false)
        tr.ramp(6_000, 45f) // calm → NORMAL
        assertEquals(listOf(EscalationLevel.ALERT, EscalationLevel.NORMAL), tr.levels)
        assertTrue(tr.prompts.isEmpty())
        tr.ramp(3_000, 45f, 0f)
        tr.ramp(11_000, 0f)
        assertEquals(1, tr.prompts.size)
    }
}
