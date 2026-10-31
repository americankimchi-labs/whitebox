package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.HandoverEndReason
import com.americankimchilabs.whitebox.domain.HandoverEvent
import com.americankimchilabs.whitebox.domain.HandoverPhase
import com.americankimchilabs.whitebox.domain.HandoverSource
import com.americankimchilabs.whitebox.domain.IgnitionState
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.Tuning
import com.americankimchilabs.whitebox.domain.VehicleSignal
import com.americankimchilabs.whitebox.domain.VoiceLine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class HandoverControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Channel : MirrorChannel {
        override val connected = MutableStateFlow(true)
        override val incoming = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val serverKey: ByteArray? = null
        val sent = mutableListOf<Frame>()
        override fun send(frame: String): Boolean = sent.add(MirrorProtocol.decode(frame)!!)
    }

    private inner class Car(val dir: File = tmp.newFolder(), val ch: Channel = Channel()) {
        var wall = 1_700_000_000_000L
        val clock = Clock { Timestamp(wall * 1_000_000, wall) }
        val store = FileEventStore(dir)
        val recorder = Recorder(store, SealStore(dir), JdkSigner(), clock)
        val handover = HandoverController(recorder, store, dir, clock, ch)
        val said = mutableListOf<VoiceLine>()

        fun TestScope.go() {
            handover.start(backgroundScope)
            backgroundScope.launch { handover.say.collect { said += it } }
            runCurrent()
        }

        suspend fun TestScope.signal(s: VehicleSignal) {
            recorder.record(ChainEntry.Signal(SignalSample(s, clock.now())))
            runCurrent()
        }

        fun markers() = store.read(0, store.head()!!.lastSeq).mapNotNull { (it.entry as? ChainEntry.Handover)?.event }
    }

    @Test
    fun aTripArmedOnTheCarIsRecordedSealedAndFinalAfterTheGracePeriod() = runTest {
        val car = Car()
        with(car) {
            go()
            signal(VehicleSignal.Gear(GearPosition.PARK))
            handover.armOnCar()
            assertEquals(HandoverPhase.ARMED, handover.phase.value)
            signal(VehicleSignal.Gear(GearPosition.DRIVE))
            assertEquals(HandoverPhase.ACTIVE, handover.phase.value)
            assertFalse(handover.cancelOnCar()) // the driver cannot stop it from the car
            assertEquals(listOf(VoiceLine.HANDOVER_STARTED, VoiceLine.HANDOVER_SET_DESTINATION), said)

            wall += 15 * 60_000
            signal(VehicleSignal.Gear(GearPosition.PARK))
            signal(VehicleSignal.Ignition(IgnitionState.OFF))
            assertEquals(HandoverPhase.ENDING, handover.phase.value)
            val provisional = recorder.capsules.value.single()
            assertEquals(CapsuleTrigger.HANDOVER to false, provisional.capsule.trigger to provisional.final)

            wall += Tuning.HANDOVER_GRACE_MS
            advanceTimeBy(1_500)
            runCurrent()
            assertEquals(HandoverPhase.OFF, handover.phase.value)
            val final = recorder.capsules.value.last()
            assertTrue(final.final)
            assertEquals(provisional.capsule.firstSeq, final.capsule.firstSeq) // the whole trip, one report
            assertTrue(markers().last() is HandoverEvent.Ended)
            assertTrue(EntryCodec.decode(EntryCodec.encode(ChainEntry.Handover(markers().last(), clock.now()))) is ChainEntry.Handover)
        }
    }

    @Test
    fun aRestartMidTripKeepsTheTripAndTheCarDrivingOnResumesIt() = runTest {
        val dir = tmp.newFolder()
        val before = Car(dir)
        with(before) {
            go()
            signal(VehicleSignal.Gear(GearPosition.PARK))
            handover.armOnCar()
            signal(VehicleSignal.Gear(GearPosition.DRIVE))
        }
        val after = Car(dir)
        after.wall = before.wall + 30_000
        with(after) {
            go()
            assertEquals(HandoverPhase.ENDING, handover.phase.value)
            assertEquals(HandoverEvent.Ending(HandoverEndReason.RESTART), markers().last())
            signal(VehicleSignal.Gear(GearPosition.DRIVE))
            assertEquals(HandoverPhase.ACTIVE, handover.phase.value)
        }
    }

    @Test
    fun phoneCommandsAreCarriedOutOnceAcknowledgedAndTheStatusReported() = runTest {
        val car = Car()
        with(car) {
            go()
            signal(VehicleSignal.Gear(GearPosition.PARK))
            ch.incoming.emit(MirrorProtocol.encode(Frame.HandoverCommand("c1", arm = true, requestedAtWall = wall)))
            runCurrent()
            assertEquals(HandoverPhase.ARMED, handover.phase.value)
            assertEquals(Frame.HandoverAck("c1"), ch.sent.filterIsInstance<Frame.HandoverAck>().single())
            assertEquals("ARMED", ch.sent.filterIsInstance<Frame.HandoverStatus>().last().phase)
            assertEquals(HandoverSource.PHONE, (markers().single() as HandoverEvent.Armed).by)

            // Resent after a lost acknowledgement: acknowledged again, not armed twice.
            ch.incoming.emit(MirrorProtocol.encode(Frame.HandoverCommand("c1", arm = true, requestedAtWall = wall)))
            runCurrent()
            assertEquals(2, ch.sent.filterIsInstance<Frame.HandoverAck>().size)
            assertEquals(1, markers().size)

            ch.incoming.emit(MirrorProtocol.encode(Frame.HandoverCommand("c2", arm = false, requestedAtWall = 0)))
            runCurrent()
            assertEquals(HandoverPhase.OFF, handover.phase.value)
            assertEquals(HandoverEvent.Cancelled(HandoverSource.PHONE, HandoverEndReason.OWNER), markers().last())
        }
    }
}
