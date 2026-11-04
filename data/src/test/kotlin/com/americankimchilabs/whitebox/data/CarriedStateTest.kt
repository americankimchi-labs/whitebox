package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EvidenceTimeline
import com.americankimchilabs.whitebox.domain.GearPosition
import com.americankimchilabs.whitebox.domain.Lamp
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.TurnSignalState
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** States recorded long before a capsule's window reach its evidence through the carried-in records. */
class CarriedStateTest {

    @get:Rule val tmp = TemporaryFolder()

    private var nowMs = 0L
    private val clock = Clock { Timestamp(nowMs * 1_000_000, 1_793_000_000_000 + nowMs) }

    private suspend fun Recorder.at(ms: Long, signal: VehicleSignal) {
        nowMs = ms
        record(ChainEntry.Signal(SignalSample(signal, clock.now())))
    }

    @Test
    fun aCapsuleCarriesInTheStatesItsWindowStartedWith() = runTest {
        val dir = tmp.newFolder()
        val store = FileEventStore(dir)
        val recorder = Recorder(store, SealStore(dir), JdkSigner(), clock)

        // Set at the start of a five-minute drive, long before the crash's two-minute window.
        recorder.at(0, VehicleSignal.SeatBelt("ROW_1_LEFT", true))
        recorder.at(10, VehicleSignal.Gear(GearPosition.DRIVE))
        recorder.at(20, VehicleSignal.BrakePedal(false))
        recorder.at(30, VehicleSignal.Light(Lamp.LOW_BEAM, true))
        var ms = 1_000L
        while (ms < 300_000) {
            if (ms == 150_000L) recorder.at(ms, VehicleSignal.Light(Lamp.LOW_BEAM, false)) // changed before the window too
            if (ms == 250_000L) recorder.at(ms, VehicleSignal.TurnSignal(TurnSignalState.LEFT)) // inside the window
            recorder.at(ms, VehicleSignal.Speed(60f / 3.6f))
            ms += 100
        }
        // The impact: 60 → 30 km/h in 300 ms, then to a stop.
        for ((i, kmh) in listOf(50f, 40f, 30f, 20f, 10f, 0f).withIndex()) recorder.at(300_000 + i * 100L, VehicleSignal.Speed(kmh / 3.6f))
        ms = 300_600
        while (ms < 315_000) { recorder.at(ms, VehicleSignal.Speed(0f)); ms += 100 }

        val seal = recorder.capsules.value.single()
        assertEquals(CapsuleCheck.Verified, verifyCapsule(seal, store))
        val events = store.read(seal.capsule.firstSeq, seal.capsule.lastSeq)
        val carried = events.mapNotNull { it.entry as? ChainEntry.Carried }
        // As they stood when the window began: the lamp's later value, nothing for the turn signal set inside it.
        assertEquals(
            setOf(VehicleSignal.SeatBelt("ROW_1_LEFT", true), VehicleSignal.Gear(GearPosition.DRIVE), VehicleSignal.BrakePedal(false), VehicleSignal.Light(Lamp.LOW_BEAM, false)),
            carried.map { it.signal }.toSet(),
        )
        assertEquals(4, carried.size) // once, though the capsule was sealed again at stillness
        assertTrue(carried.all { it.asOfSeq == seal.capsule.firstSeq })
        assertTrue(store.read(0, seal.capsule.firstSeq - 1).none { it.entry is ChainEntry.Carried })

        // Durable like any record: read back from disk, the evidence starts from them.
        val reread = FileEventStore(dir).read(seal.capsule.firstSeq, seal.capsule.lastSeq)
        val cause = reread.first { (it.entry as? ChainEntry.Signal)?.sample?.signal.let { s -> s is VehicleSignal.Speed && s.metersPerSecond * 3.6f < 45f } }.seq
        val t = EvidenceTimeline.build(reread, cause)
        assertEquals(GearPosition.DRIVE, t.gear.first().value)
        assertEquals(-EvidenceTimeline.PRE_MS, t.gear.first().atMs)
        assertEquals(mapOf("ROW_1_LEFT" to true), t.context.seatBelts)
        assertEquals(mapOf(Lamp.LOW_BEAM to false), t.context.lamps)
    }

    @Test
    fun aCarriedStateRoundTripsEncryptedAndPlain() {
        val entry = ChainEntry.Carried(VehicleSignal.Door("ROW_1_LEFT", false), 42, Timestamp(5_000_000, 1_793_000_000_005))
        assertEquals(entry, EntryCodec.decode(EntryCodec.encode(entry)))
        val ring = MemoryKeyRing().apply { rotate("o1") }
        val cipher = EpochRecordCipher(ring) { 1_793_000_000_005 }
        val sealed = EntryCodec.encode(entry, cipher)
        assertTrue(EntryCodec.isSealed(sealed))
        assertEquals(entry, EntryCodec.decode(sealed, cipher))
        assertEquals(entry.at, EntryCodec.timestamp(sealed))
    }
}
