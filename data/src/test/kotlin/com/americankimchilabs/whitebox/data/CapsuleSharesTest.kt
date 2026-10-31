package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.LocationFix
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class CapsuleSharesTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Channel : MirrorChannel {
        override val connected = MutableStateFlow(false)
        override val incoming = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val serverKey: ByteArray? = null
        val sent = mutableListOf<Frame>()
        override fun send(frame: String): Boolean = sent.add(MirrorProtocol.decode(frame)!!)
    }

    @Test
    fun theQrLinkOpensItsCapsuleAloneAndTheServerLearnsOnlyAHashOfItsCode() = runTest {
        val ring = MemoryKeyRing()
        var wall = 1_700_000_000_000L
        val store = FileEventStore(tmp.newFolder(), cipher = EpochRecordCipher(ring) { wall })
        fun ts() = Timestamp(wall * 1_000, wall)
        store.append(ChainEntry.Location(LocationFix(37.5, 127.0, 5f, 1, ts()))) // home, an hour before
        wall += 3_600_000
        repeat(5) { wall += 1_000; store.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(10f), ts()))) }
        val capsule = IncidentCapsule("C1", CapsuleTrigger.HARD_BRAKE, 1, 5)
        val ch = Channel()
        var now = 0L
        val shares = CapsuleShares(store, ch, tmp.newFolder(), "car1", "live", { e, m -> ring.key(e)?.let { RecordCipher.minuteKey(it, m) } }) { now }
        shares.start(backgroundScope)
        runCurrent()

        val link = shares.link(capsule, "https://x")!!
        val fragment = link.substringAfter('#').split("&").associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(listOf("d", "s", "id", "t", "k"), fragment.keys.toList())
        assertEquals("car1" to "C1", fragment["d"] to fragment["id"])
        // The keys in the link open the capsule's records, not the location an hour earlier.
        val keys = fragment["k"]!!.split(",").associate { k -> k.split(".").let { (it[0].toInt() to it[1].toLong()) to Base64.getUrlDecoder().decode(it[2]) } }
        val reader = MinuteKeysCipher(keys)
        for (r in store.records(1, 5)) EntryCodec.decode(MirrorProtocol.Record.parse(r)!!.entry, reader)
        assertTrue(runCatching { EntryCodec.decode(MirrorProtocol.Record.parse(store.records(0, 0).single())!!.entry, reader) }.exceptionOrNull() is LockedRecordException)

        // The server is told the code's hash, never the code or the keys; again on reconnect until acknowledged.
        val share = ch.sent.filterIsInstance<Frame.Share>().single()
        assertEquals(CapsuleShares.codeHash(fragment["t"]!!), share.codeHash)
        assertFalse(ch.sent.joinToString().contains(fragment["t"]!!))
        ch.connected.value = true
        runCurrent()
        assertEquals(2, ch.sent.filterIsInstance<Frame.Share>().size)
        ch.incoming.emit(MirrorProtocol.encode(Frame.ShareAck("C1")))
        runCurrent()
        ch.connected.value = false; runCurrent(); ch.connected.value = true; runCurrent()
        assertEquals(2, ch.sent.filterIsInstance<Frame.Share>().size)

        // The same QR until it nears its end; then a new code.
        assertEquals(link, shares.link(capsule, "https://x"))
        now += CapsuleShares.VALID_MS - 1
        assertNotEquals(link, shares.link(capsule, "https://x"))
    }

    @Test
    fun noLinkForRecordsWithoutMinuteKeys() {
        val store = FileEventStore(tmp.newFolder(), cipher = AesGcmRecordCipher(ByteArray(32))) // an older chain
        repeat(3) { store.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(10f), Timestamp(it * 1_000_000L, 1_700_000_000_000 + it)))) }
        val shares = CapsuleShares(store, Channel(), tmp.newFolder(), "car1", "live", { _, _ -> ByteArray(32) })
        assertNull(shares.link(IncidentCapsule("C1", CapsuleTrigger.HARD_BRAKE, 0, 2), "https://x"))
    }
}
