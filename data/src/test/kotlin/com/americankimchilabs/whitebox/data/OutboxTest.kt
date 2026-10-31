package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.Clock
import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.Tuning
import com.americankimchilabs.whitebox.domain.VehicleSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class OutboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeChannel : MirrorChannel {
        override val connected = MutableStateFlow(false)
        override val incoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val sent = mutableListOf<Frame>()
        override fun send(frame: String): Boolean {
            if (!connected.value) return false
            sent += MirrorProtocol.decode(frame)!!
            return true
        }
        fun ack(seq: Long) = incoming.tryEmit(MirrorProtocol.encode(Frame.Ack(seq)))
    }

    private class Rig(dir: java.io.File) {
        var t = 0L
        val clock = Clock { Timestamp(t * 1_000_000, 1_700_000_000_000 + t) }
        val store = FileEventStore(dir)
        val seals = SealStore(dir)
        val channel = FakeChannel()
        val outbox = Outbox(store, seals, channel, dir)
        val recorder = Recorder(store, seals, JdkSigner(), clock, outbox::onSeal)

        suspend fun signal(s: VehicleSignal) = recorder.record(ChainEntry.Signal(SignalSample(s, clock.now())))
        suspend fun speed(kmh: Float) = signal(VehicleSignal.Speed(kmh / 3.6f))
    }

    private fun TestScope.rig(dir: java.io.File = tmp.newFolder()) = Rig(dir).also {
        it.outbox.start(backgroundScope, it.recorder)
        runCurrent()
    }

    @Test
    fun normalDrivingGoesInBatchesAndResumesAfterTheLastAcknowledgement() = runTest {
        val dir = tmp.newFolder()
        val r = rig(dir)
        repeat(20) { r.t += 100; r.speed(50f) }
        r.channel.connected.value = true
        runCurrent()
        val first = r.channel.sent.filterIsInstance<Frame.Batch>().single()
        assertEquals(20, first.records.size)
        assertEquals(EscalationLevel.NORMAL, first.level)
        assertTrue(r.channel.sent.none { it is Frame.Event }) // nothing immediate while NORMAL

        r.channel.ack(19)
        runCurrent()
        repeat(5) { r.t += 100; r.speed(50f) }
        advanceTimeBy(Tuning.NORMAL_BATCH_INTERVAL_S * 1_000L + 1)
        assertEquals(listOf(20L, 24L), r.channel.sent.filterIsInstance<Frame.Batch>().last().records.map { MirrorProtocol.Record.parse(it)!!.seq }.let { listOf(it.first(), it.last()) })

        // A restart resumes after the persisted acknowledgement.
        r.store.close()
        assertEquals(19L, Outbox(FileEventStore(dir), SealStore(dir), FakeChannel(), dir).acked)
    }

    @Test
    fun inAlertTheCoreTupleGoesOutAtOnceTwiceAndTheAllClearFollows() = runTest {
        val r = rig()
        r.channel.connected.value = true
        runCurrent()
        repeat(30) { r.t += 100; r.speed(60f) }
        r.channel.sent.clear()
        r.signal(VehicleSignal.BrakePedal(true))
        for (v in listOf(57f, 53f, 49f, 45f, 41f, 37f)) { r.t += 100; r.speed(v) } // ≈ 40 km/h/s: ALERT
        runCurrent()
        val events = r.channel.sent.filterIsInstance<Frame.Event>()
        val levels = events.mapNotNull { MirrorProtocol.Record.parse(it.record)?.entry?.takeIf { e -> "LEVEL" in e } }
        assertTrue(levels.any { "ALERT" in it })
        // Headers follow the chain: nothing before the change to ALERT is labelled ALERT, except the record that
        // caused it, which goes out with the change.
        val change = events.first { "LEVEL|ALERT" in it.record }.let { MirrorProtocol.Record.parse(it.record)!! }
        val cause = change.entry.split("|")[4].toLong()
        assertTrue(events.any { MirrorProtocol.Record.parse(it.record)!!.seq == cause })
        assertTrue(events.filter { it.level == EscalationLevel.ALERT }.all { MirrorProtocol.Record.parse(it.record)!!.seq >= cause })
        val speeds = events.filter { "SPEED" in it.record && it.level == EscalationLevel.ALERT }
        assertTrue(speeds.isNotEmpty())
        assertEquals(0, speeds.groupBy { it.record }.values.count { it.size != 2 }) // each record exactly twice

        // Calm driving: ALERT ends, and the change back to NORMAL goes out at once (the all-clear).
        r.signal(VehicleSignal.BrakePedal(false))
        repeat(Tuning.ALERT_CLEAR_S * 10 + 5) { r.t += 100; r.speed(37f) }
        runCurrent()
        val clear = r.channel.sent.filterIsInstance<Frame.Event>().last { "LEVEL" in it.record }
        assertEquals(EscalationLevel.NORMAL, clear.level)
    }

    @Test
    fun aSealGoesFirstAndIsResentUntilAcknowledged() = runTest {
        val r = rig()
        repeat(10) { r.t += 100; r.speed(60f) }
        r.signal(VehicleSignal.BrakePedal(true))
        r.t += 100; r.speed(60f)
        r.t += 100; r.speed(35f) // crash: sealed while offline
        runCurrent()
        assertTrue(r.channel.sent.isEmpty())

        r.channel.connected.value = true
        runCurrent()
        val first = r.channel.sent.first()
        assertTrue(first is Frame.Seal) // the seal before any batch
        val key = MirrorProtocol.sealKey(SealStore.parse((first as Frame.Seal).sealLine))

        // Reconnect without an acknowledgement: sent again. After SEALACK: not again.
        r.channel.connected.value = false; runCurrent()
        r.channel.sent.clear()
        r.channel.connected.value = true; runCurrent()
        assertTrue(r.channel.sent.any { it is Frame.Seal })
        r.channel.incoming.tryEmit(MirrorProtocol.encode(Frame.SealAck(key)))
        runCurrent()
        r.channel.connected.value = false; runCurrent()
        r.channel.sent.clear()
        r.channel.connected.value = true; runCurrent()
        assertTrue(r.channel.sent.filterIsInstance<Frame.Seal>().none { MirrorProtocol.sealKey(SealStore.parse(it.sealLine)) == key })
    }

    @Test
    fun framesRoundTrip() {
        val record = "7\t${"a".repeat(64)}\tS|SPEED|16.6|1;2|-\t${"b".repeat(64)}"
        for (f in listOf(
            Frame.Challenge("n0nce"), Frame.Ready("abc"), Frame.Denied("bad signature"),
            Frame.Event(EscalationLevel.ALERT, record), Frame.Batch(EscalationLevel.NORMAL, listOf(record, record)),
            Frame.Seal("C1|DELTA_V|0|9|ff|true|5|k|s"), Frame.Ack(41), Frame.SealAck("C1:true:5"),
        )) assertEquals(f, MirrorProtocol.decode(MirrorProtocol.encode(f)))
        val auth = MirrorProtocol.decode(MirrorProtocol.encode(Frame.Auth("live", byteArrayOf(1, 2), byteArrayOf(3)))) as Frame.Auth
        assertEquals("live", auth.stream)
        assertTrue(auth.publicKey.contentEquals(byteArrayOf(1, 2)))
        assertEquals(null, MirrorProtocol.decode("EV|NOPE|x"))
    }
}
