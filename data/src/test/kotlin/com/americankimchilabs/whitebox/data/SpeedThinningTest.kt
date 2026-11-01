package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** An unchanged speed is written once a second; nothing a crash or a 급발진 needs is lost. */
class SpeedThinningTest {
    @get:Rule val tmp = TemporaryFolder()

    private inner class Car {
        var ms = 0L
        val clock = Clock { Timestamp(ms * 1_000_000, 1_800_000_000_000 + ms) }
        val dir = tmp.newFolder()
        val store = FileEventStore(dir)
        val recorder = Recorder(store, SealStore(dir), JdkSigner(), clock)

        suspend fun signal(s: VehicleSignal) = recorder.record(ChainEntry.Signal(SignalSample(s, clock.now())))

        /** Speed every 100 ms from [fromKmh] to [toKmh] over [durationMs]. */
        suspend fun speed(durationMs: Long, fromKmh: Float, toKmh: Float = fromKmh) {
            val steps = durationMs / 100
            for (i in 1..steps) {
                ms += 100
                signal(VehicleSignal.Speed((fromKmh + (toKmh - fromKmh) * i / steps) / 3.6f))
            }
        }

        fun speeds() = store.read(0, store.head()!!.lastSeq).mapNotNull { e ->
            ((e.entry as? ChainEntry.Signal)?.sample?.signal as? VehicleSignal.Speed)?.let { e.entry.at.elapsedNanos / 1_000_000 to it.metersPerSecond * 3.6f }
        }

        fun triggers() = store.read(0, store.head()!!.lastSeq).mapNotNull { (it.entry as? ChainEntry.Escalation)?.event as? EscalationEvent.Triggered }
    }

    @Test
    fun standingStillIsWrittenOnceASecondAndTheShoveInFull() = runTest {
        val car = Car()
        with(car) {
            signal(VehicleSignal.Gear(GearPosition.DRIVE))
            speed(10_000, 0f) // 100 samples at a standstill
            signal(VehicleSignal.BrakePedal(true))
            speed(100, 0f)
            ms += 100
            signal(VehicleSignal.Speed(9f / 3.6f)) // struck from behind, no warning
            speed(1_000, 9f, 0f)

            val standing = speeds().filter { it.first <= 10_100 }
            assertTrue("written ${standing.size} of 101", standing.size in 10..13)
            // The last standstill value before the jump is there to the 100 ms, and the jump itself.
            assertTrue(speeds().any { it.first == 10_100L && it.second == 0f })
            assertTrue(speeds().any { it.first == 10_200L && it.second == 9f })

            val shove = triggers().single { it.trigger == CapsuleTrigger.REAR_SHOVE }
            val cause = store.read(shove.causeSeq, shove.causeSeq).single().entry as ChainEntry.Signal
            assertEquals(9f, (cause.sample.signal as VehicleSignal.Speed).metersPerSecond * 3.6f, 0.01f)
            // After the trigger every sample is written (the alert/crash levels are never thinned).
            assertEquals(10, speeds().count { it.first > 10_200 })
            assertTrue(store.verify(0, store.head()!!.lastSeq) is ChainCheck.Intact)
        }
    }

    @Test
    fun aSpeedRisingUnderTheBrakeIsWrittenSampleBySample() = runTest {
        val car = Car()
        with(car) {
            signal(VehicleSignal.Gear(GearPosition.DRIVE))
            speed(5_000, 30f) // cruising on a constant reading
            signal(VehicleSignal.BrakePedal(true))
            speed(4_000, 30f, 48f) // 급발진: the brake held while the speed rises
            assertEquals(40, speeds().count { it.first > 5_000 })
            assertTrue(speeds().any { it.first == 5_000L }) // the last cruise value before the brake
            assertTrue(triggers().any { it.trigger == CapsuleTrigger.BRAKE_SPEED_RISE })
        }
    }

    @Test
    fun theLastUnchangedValueBeforeAChangeKeepsItsTime() = runTest {
        val car = Car()
        with(car) {
            speed(3_550, 60f)
            speed(500, 60f, 55f)
            assertTrue(speeds().any { it.first == 3_500L && kotlin.math.abs(it.second - 60f) < 0.01f })
            assertTrue(speeds().none { it.first == 3_400L })
        }
    }
}
