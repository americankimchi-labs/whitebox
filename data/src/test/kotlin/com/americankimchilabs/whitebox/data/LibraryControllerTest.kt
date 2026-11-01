package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.LibraryEvent
import com.americankimchilabs.whitebox.domain.LibraryStatus
import com.americankimchilabs.whitebox.domain.RecorderEvent
import com.americankimchilabs.whitebox.domain.Retention.DAY_MS
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Tier
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Channel : MirrorChannel {
        override val connected = MutableStateFlow(true)
        override val incoming = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val serverKey: ByteArray? = null
        val sent = mutableListOf<Frame>()
        override fun send(frame: String): Boolean = sent.add(MirrorProtocol.decode(frame)!!)
    }

    private inner class Car(withCloud: Boolean = true, tier: Tier = Tier.PRO) {
        var wall = 1_800_000_000_000L
        var acked = -1L
        val clock = Clock { Timestamp(wall * 1_000_000, wall) }
        val dir = tmp.newFolder()
        val store = FileEventStore(dir, segmentRecords = 50)
        val recorder = Recorder(store, SealStore(dir), JdkSigner(), clock)
        val ch = Channel()
        val library = LibraryController(recorder, store, dir, clock, ch.takeIf { withCloud }, acked = { acked }, defaultTier = tier)

        fun TestScope.go() {
            library.start(backgroundScope)
            runCurrent()
        }

        suspend fun drive(n: Int) = repeat(n) {
            wall += 100
            recorder.record(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(10f + (it % 2) * 0.1f), clock.now())))
        }

        suspend fun TestScope.moment(id: String, trigger: CapsuleTrigger = CapsuleTrigger.EVASIVE_STEER) {
            drive(5)
            recorder.sealRange(id, trigger, store.head()!!.lastSeq - 4, final = true)
            runCurrent()
        }

        fun status(id: String) = library.items.value.single { it.id == id }.status(wall)
        fun libraryEvents() = store.read(0, store.head()!!.lastSeq).mapNotNull { (it.entry as? ChainEntry.Library)?.event }
    }

    @Test
    fun aFullLibraryLetsTheOldestGoAndSaysSo() = runTest {
        val car = Car(tier = Tier.FREE)
        with(car) {
            go()
            moment("crash", CapsuleTrigger.DELTA_V); moment("a"); moment("airbag", CapsuleTrigger.AIRBAG); moment("b")
            assertEquals(3, library.slotsUsed()) // a crash without an airbag takes a slot, an airbag crash does not
            moment("trip", CapsuleTrigger.HANDOVER) // a handover report shares the slots
            assertEquals(LibraryStatus.LEFT, status("a")) // the oldest near-miss goes before the older crash
            assertEquals(LibraryStatus.KEPT, status("crash"))
            assertEquals(LibraryStatus.SEVERE, status("airbag"))
            assertEquals(3, library.slotsUsed())
            assertEquals("a", library.notices.replayCache.single().id)
            assertTrue(libraryEvents().contains(LibraryEvent.Left("a")))
            assertEquals(LibraryEvent.PlanChanged(Tier.FREE, null), libraryEvents().first())
        }
    }

    @Test
    fun withOnlyPaidMomentsTheNewOneIsHeldAndAnUpgradeBringsItBack() = runTest {
        val car = Car(tier = Tier.FREE)
        with(car) {
            go()
            moment("a"); moment("b"); moment("c")
            listOf("a", "b", "c").forEachIndexed { i, id -> ch.incoming.emit(MirrorProtocol.encode(Frame.Extend("x$i", id, wall + 400 * DAY_MS))) }
            runCurrent()
            moment("d")
            // Paid ones stay to their paid end: the new moment is held instead, and the driver is told.
            assertEquals(listOf(LibraryStatus.EXTENDED, LibraryStatus.EXTENDED, LibraryStatus.EXTENDED, LibraryStatus.LEFT), listOf("a", "b", "c", "d").map(::status))
            assertEquals(3, library.slotsUsed()) // the limits are the limits
            assertTrue(libraryEvents().contains(LibraryEvent.Left("d")))
            assertEquals("d", library.notices.replayCache.single().id)

            // Moving up within 30 days of its event makes room: it comes back, with its own end.
            ch.incoming.emit(MirrorProtocol.encode(Frame.Plan("p1", "PLUS", "FREE", wall)))
            runCurrent()
            assertEquals(LibraryStatus.KEPT, status("d"))
            assertTrue(libraryEvents().contains(LibraryEvent.Returned("d")))
            assertEquals(4, library.slotsUsed())
        }
    }
    @Test
    fun planAndExtensionsFromTheCloudAreRecordedOnceAndAcknowledged() = runTest {
        val car = Car(tier = Tier.FREE)
        with(car) {
            go()
            moment("a")
            ch.incoming.emit(MirrorProtocol.encode(Frame.Plan("p1", "PRO", "FREE", wall)))
            runCurrent()
            assertEquals(Tier.PRO, library.tier.value)
            val until = wall + 400 * DAY_MS
            repeat(2) { ch.incoming.emit(MirrorProtocol.encode(Frame.Extend("x1", "a", until))) } // resent after a lost ack
            runCurrent()
            assertEquals(LibraryStatus.EXTENDED, status("a"))
            assertEquals(1, library.slotsUsed()) // an extended moment still uses its slot
            assertEquals(1, libraryEvents().count { it is LibraryEvent.Extended })
            assertEquals(listOf("p1", "x1", "x1"), ch.sent.filterIsInstance<Frame.LibraryAck>().map { it.commandId })

            wall += 31 * DAY_MS
            assertEquals(LibraryStatus.EXTENDED, status("a"))
            wall += 400 * DAY_MS
            assertEquals(LibraryStatus.EXPIRED, status("a"))
        }
    }

    @Test
    fun theCarKeepsThreeDaysOfWhatTheCloudHasAndTheLibraryStays() = runTest {
        val car = Car()
        with(car) {
            go()
            drive(120)
            moment("kept")
            val kept = recorder.capsules.value.single().capsule
            drive(200)
            val sizeBefore = store.sizeOnDisk()

            wall += 2 * DAY_MS
            acked = store.head()!!.lastSeq
            assertNull(library.housekeep()) // not three days yet

            wall += 2 * DAY_MS
            val through = library.housekeep()!!
            assertTrue(through >= 100)
            assertTrue(store.sizeOnDisk() < sizeBefore)
            assertTrue(store.verify(kept.firstSeq, kept.lastSeq) is ChainCheck.Intact) // the library's records stay
            val pruned = store.read(store.head()!!.lastSeq, store.head()!!.lastSeq).single().entry as ChainEntry.Recorder
            assertEquals(RecorderEvent.Pruned(through, (pruned.event as RecorderEvent.Pruned).throughWall, unsent = false), pruned.event)
        }
    }

    @Test
    fun unsentRecordsGoOnlyAfterFourteenDaysAndTheLossIsRecorded() = runTest {
        val car = Car()
        with(car) {
            go()
            drive(200)
            wall += 10 * DAY_MS
            assertNull(library.housekeep()) // never sent: kept
            wall += 5 * DAY_MS
            library.housekeep()!!
            val pruned = store.read(0, store.head()!!.lastSeq).mapNotNull { ((it.entry as? ChainEntry.Recorder)?.event as? RecorderEvent.Pruned) }.single()
            assertTrue(pruned.unsent)
            assertTrue(store.checkpoint() != null)
        }
    }

    @Test
    fun aCarWithoutTheCloudKeepsSevenDays() = runTest {
        val car = Car(withCloud = false)
        with(car) {
            go()
            drive(200)
            wall += 6 * DAY_MS
            assertNull(library.housekeep())
            wall += 2 * DAY_MS
            assertTrue(library.housekeep() != null)
        }
    }
}
