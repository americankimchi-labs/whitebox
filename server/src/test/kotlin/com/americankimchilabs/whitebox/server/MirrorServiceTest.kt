package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.AesGcmRecordCipher
import com.americankimchilabs.whitebox.data.CapsuleSeal
import com.americankimchilabs.whitebox.data.EntryCodec
import com.americankimchilabs.whitebox.data.EpochRecordCipher
import com.americankimchilabs.whitebox.data.MemoryKeyRing
import com.americankimchilabs.whitebox.data.SealedBox
import com.americankimchilabs.whitebox.data.FileEventStore
import com.americankimchilabs.whitebox.data.MirrorProtocol
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.data.SealStore
import com.americankimchilabs.whitebox.data.Signer
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.Tuning
import com.americankimchilabs.whitebox.domain.VehicleSignal
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import io.ktor.websocket.Frame as WsFrame

class MirrorServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Car : Signer {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val publicKey: ByteArray = pair.public.encoded
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(data); sign()
        }
        fun auth(challenge: String, stream: String = MirrorProtocol.STREAM_LIVE): String {
            val nonce = (MirrorProtocol.decode(challenge) as Frame.Challenge).nonce
            return MirrorProtocol.encode(Frame.Auth(stream, publicKey, sign(MirrorProtocol.authText(stream, nonce).toByteArray())))
        }
    }

    private inner class Rig {
        var now = 1_000_000L
        val dir = tmp.newFolder()
        val store = FileStreamStore(dir)
        val key = ServerKey(dir)
        val service = MirrorService(store, key, clock = { now })
        fun severeSilent(s: MirrorService.Session) = store.log(s.stream!!, SEVERE_SILENT)
        val car = Car()
        val chain = FileEventStore(tmp.newFolder())
        var t = 0L
        fun ts() = Timestamp(t * 1_000_000, 1_700_000_000_000 + t)
        fun speed(kmh: Float) = chain.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(kmh / 3.6f), ts())))
        fun level(l: EscalationLevel) = chain.append(ChainEntry.Escalation(EscalationEvent.LevelChanged(l, 0), ts()))
        fun records(from: Long, to: Long) = chain.records(from, to)

        fun connect(): MirrorService.Session {
            val s = service.session()
            val replies = s.onText(car.auth(s.greeting()))!!
            assertTrue(MirrorProtocol.decode(replies[0]) is Frame.Ready)
            return s
        }
    }

    private fun MirrorService.Session.send(f: Frame) = onText(MirrorProtocol.encode(f))!!.map { MirrorProtocol.decode(it) }

    @Test
    fun aCarMustProveItsKeyBeforeAnythingIsStored() {
        val r = Rig()
        val s = r.service.session()
        assertNull(s.onText(MirrorProtocol.encode(Frame.Batch(EscalationLevel.NORMAL, emptyList())))) // closes
        val other = Car()
        val forged = MirrorProtocol.encode(Frame.Auth("live", other.publicKey, r.car.sign("whitebox-mirror|live|x".toByteArray())))
        assertTrue(MirrorProtocol.decode(r.service.session().onText(forged)!!.single()) is Frame.Denied)
    }

    @Test
    fun batchesAreIdempotentAnchoredAndAcknowledged() {
        val r = Rig()
        repeat(10) { r.t += 100; r.speed(50f) }
        val s = r.connect()
        assertEquals(listOf(Frame.Ack(9)), s.send(Frame.Batch(EscalationLevel.NORMAL, r.records(0, 9))))
        assertEquals(listOf(Frame.Ack(9)), s.send(Frame.Batch(EscalationLevel.NORMAL, r.records(3, 9)))) // resent after a reconnect
        val id = s.stream!!
        assertEquals(10, r.store.count(id))
        assertTrue(r.store.log(id, "anchors").last().startsWith("ANCHOR|$id|9|${r.store.hashAt(id, 9)}|"))

        // A reconnect is told where to resume.
        val again = r.service.session()
        val replies = again.onText(r.car.auth(again.greeting()))!!.map { MirrorProtocol.decode(it) }
        assertEquals(Frame.Ack(9), replies[1])
    }

    @Test
    fun recordsThatContradictTheHeldChainAreRefused() {
        val r = Rig()
        repeat(5) { r.t += 100; r.speed(50f) }
        val s = r.connect()
        s.send(Frame.Batch(EscalationLevel.NORMAL, r.records(0, 4)))
        val id = s.stream!!
        val honest = r.records(2, 2).single().split("\t")
        val tampered = honest.toMutableList().also { it[2] = it[2].replace("13.888", "3.888") }.joinToString("\t")
        s.send(Frame.Batch(EscalationLevel.NORMAL, listOf(tampered))) // hash no longer covers the content: dropped
        // A fully re-hashed fork at #2: its own hash is right, but #1 → #2 → #3 no longer link.
        val body = "2\t${"0".repeat(64)}\t${honest[2]}"
        val fork = "$body\t" + java.security.MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        s.send(Frame.Batch(EscalationLevel.NORMAL, listOf(fork)))
        assertEquals(5, r.store.count(id))
        assertEquals(honest.joinToString("\t"), r.store.records(id, 2, 2).single())
        assertEquals(1, r.store.conflicts(id))
    }

    @Test
    fun silenceAfterAlertIsSealedByTheServerButAPlainHardBrakeNeverNotifies() {
        val r = Rig()
        val s = r.connect()
        repeat(5) { r.t += 100; r.speed(60f) }
        r.level(EscalationLevel.ALERT)
        for (seq in 0L..5L) s.send(Frame.Event(EscalationLevel.ALERT, r.records(seq, seq).single()))
        r.now += Tuning.DEADMAN_SEAL_SILENCE_S * 1_000L - 1
        r.service.tick()
        assertTrue(r.store.log(s.stream!!, "attested").isEmpty())
        r.now += 1
        r.service.tick()
        val attested = r.store.log(s.stream!!, "attested").single()
        assertTrue(attested.startsWith("ATTEST|${s.stream}|0|5|${r.store.hashAt(s.stream!!, 5)}|5|0|DEADMAN|"))
        r.now += Tuning.DEADMAN_NOTIFY_SILENCE_S * 1_000L
        r.service.tick()
        assertTrue(r.severeSilent(s).isEmpty()) // no severe evidence: nothing for the family's page
        assertEquals(1, r.store.log(s.stream!!, "attested").size) // one seal per silence
    }

    @Test
    fun theServerSealCoversTheEpisodeAndItsPreWindowByTheCarsClockNotByArrival() {
        val r = Rig()
        val s = r.connect()
        repeat(3_000) { r.t += 100; r.speed(60f) } // 300 s of driving, all arriving at once as a backlog
        r.level(EscalationLevel.ALERT)
        s.send(Frame.Batch(EscalationLevel.ALERT, r.records(0, 3_000)))
        r.now += Tuning.DEADMAN_SEAL_SILENCE_S * 1_000L
        r.service.tick()
        val f = r.store.log(s.stream!!, "attested").single().split("|")
        // The last record is at 300 s; the episode lasted 10 s of server time, plus the 120 s pre-window.
        val from = f[2].toLong()
        assertTrue("from $from", from in 1_690L..1_710L)
        assertEquals(3_000L, f[3].toLong())
    }

    @Test
    fun aCrashSignatureFollowedBySilenceIsRecordedOnceEvenWithoutTheCarsOwnTrigger() {
        val r = Rig()
        val s = r.connect()
        repeat(20) { r.t += 100; r.speed(60f) }
        r.level(EscalationLevel.ALERT)
        r.t += 100; r.speed(35f) // 25 km/h in 100 ms; the car died before writing its trigger
        s.send(Frame.Batch(EscalationLevel.NORMAL, r.records(0, 20)))
        s.send(Frame.Event(EscalationLevel.ALERT, r.records(21, 21).single()))
        r.now += Tuning.DEADMAN_NOTIFY_SILENCE_S * 1_000L
        r.service.tick()
        r.service.tick()
        // Recorded for the family's page (nobody is texted), with the server's seal over what arrived.
        assertEquals(1, r.severeSilent(s).size)
        assertTrue(r.severeSilent(s).single().substringAfter('|').startsWith("ATTEST|"))
        assertEquals("SERVER_SEAL", r.service.capsules(s.stream!!).single().trigger)

        // The all-clear ends the episode: later silence in NORMAL does nothing.
        r.level(EscalationLevel.NORMAL)
        s.send(Frame.Event(EscalationLevel.NORMAL, r.records(22, 22).single()))
        r.now += Tuning.DEADMAN_NOTIFY_SILENCE_S * 2_000L
        r.service.tick()
        assertEquals(1, r.severeSilent(s).size)
    }

    @Test
    fun aSealIsStoredOnlyWithTheCarsOwnValidSignature() {
        val r = Rig()
        repeat(3) { r.t += 100; r.speed(60f) }
        val s = r.connect()
        val capsule = IncidentCapsule("C2", CapsuleTrigger.DELTA_V, 0, 2)
        val seal = CapsuleSeal.create(capsule, r.chain.hashAt(2), true, 5, r.car)
        assertEquals(listOf(Frame.SealAck(MirrorProtocol.sealKey(seal))), s.send(Frame.Seal(SealStore.line(seal))))
        val stranger = CapsuleSeal.create(capsule, r.chain.hashAt(2), true, 6, Car())
        assertTrue(s.send(Frame.Seal(SealStore.line(stranger))).isEmpty())
        assertEquals(1, r.store.log(s.stream!!, "seals").size)
        assertEquals(EscalationLevel.TRIGGERED, r.service.statuses().single().level) // a crash seal starts the dead-man watch
    }

    @Test
    fun theWebSocketEndpointSpeaksTheProtocol() = testApplication {
        val r = Rig()
        repeat(3) { r.t += 100; r.speed(40f) }
        application { whiteBox(r.service) }
        val client = createClient { install(WebSockets) }
        client.webSocket("/v1/stream") {
            val challenge = (incoming.receive() as WsFrame.Text).readText()
            send(WsFrame.Text(r.car.auth(challenge)))
            assertTrue(MirrorProtocol.decode((incoming.receive() as WsFrame.Text).readText()) is Frame.Ready)
            assertEquals(Frame.Ack(-1), MirrorProtocol.decode((incoming.receive() as WsFrame.Text).readText()))
            send(WsFrame.Text(MirrorProtocol.encode(Frame.Batch(EscalationLevel.NORMAL, r.records(0, 2)))))
            assertEquals(Frame.Ack(2), MirrorProtocol.decode((incoming.receive() as WsFrame.Text).readText()))
        }
    }

    @Test
    fun anEncryptedChainStillShowsACrashThroughTheCopiesSealedToTheServer() {
        val r = Rig()
        val cipher = AesGcmRecordCipher(ByteArray(32) { 7 })
        val chain = FileEventStore(tmp.newFolder(), cipher = cipher)
        fun speed(kmh: Float) = chain.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(kmh / 3.6f), r.ts())))
        val s = r.connect()
        repeat(20) { r.t += 100; speed(60f) }
        r.t += 100; speed(35f) // crash; the car died before writing its trigger
        s.send(Frame.Batch(EscalationLevel.NORMAL, chain.records(0, 19)))
        val crash = chain.records(20, 20).single()
        assertTrue(EntryCodec.isSealed(crash.split("\t")[2])) // the server cannot read the record itself
        val plain = EntryCodec.encode(chain.read(20, 20).single().entry)
        val copy = SealedBox.seal(r.service.serverPublicKey(), plain.toByteArray(), SealedBox.INFO_SERVER)
        // A copy that does not match its record is ignored.
        val wrongCopy = SealedBox.seal(r.service.serverPublicKey(), EntryCodec.encode(chain.read(19, 19).single().entry).toByteArray(), SealedBox.INFO_SERVER)
        s.send(Frame.Event(EscalationLevel.ALERT, crash, wrongCopy))
        assertTrue(r.store.log(s.stream!!, "signal-copies").isEmpty())
        // The pre-crash speeds only reach the server as records it cannot read; the engine needs them too.
        for (seq in 0L..19L) {
            val p = EntryCodec.encode(chain.read(seq, seq).single().entry)
            s.send(Frame.Event(EscalationLevel.ALERT, chain.records(seq, seq).single(), SealedBox.seal(r.service.serverPublicKey(), p.toByteArray(), SealedBox.INFO_SERVER)))
        }
        s.send(Frame.Event(EscalationLevel.ALERT, crash, copy))
        assertTrue(r.store.log(s.stream!!, "signal-copies").none { "SPEED" in it }) // encrypted at rest
        r.now += Tuning.DEADMAN_NOTIFY_SILENCE_S * 1_000L
        r.service.tick()
        r.service.tick()
        assertEquals(1, r.severeSilent(s).size)
        // The family's page shows what the server could read of it: the crash-signal copies, not the locked records.
        val c = r.service.capsules(s.stream!!).single()
        assertTrue(r.service.signalCopies(s.stream!!, c.firstSeq, c.lastSeq).any { it.second.startsWith("S|SPEED|") })
    }

    @Test
    fun aCapsuleWindowIsStoredUnderItsPseudonymOnly() {
        val r = Rig()
        val s = r.connect()
        val window = "S|SPEED|16.6|1;2|-\nE|3;4|TRIGGER|DELTA_V|0|-25.0"
        val pseudonym = java.util.UUID.randomUUID().toString()
        val replies = s.send(Frame.Window(pseudonym, SealedBox.seal(r.service.serverPublicKey(), window.toByteArray(), SealedBox.INFO_SERVER)))
        assertEquals(listOf(Frame.WindowAck(pseudonym)), replies)
        assertEquals(1, r.store.labeledCount())
        val stored = r.dir.resolve("labeled/$pseudonym").readText()
        assertTrue("SPEED" !in stored && s.stream!!.deviceId !in stored)
    }

    @Test
    fun withdrawingConsentDeletesTheWindowOnTheServer() {
        val r = Rig()
        val s = r.connect()
        val pseudonym = java.util.UUID.randomUUID().toString()
        s.send(Frame.Window(pseudonym, SealedBox.seal(r.service.serverPublicKey(), "S|SPEED|1|1;2|-".toByteArray(), SealedBox.INFO_SERVER)))
        assertEquals(1, r.store.labeledCount())
        assertEquals(listOf(Frame.WindowDeleted(pseudonym)), s.send(Frame.WindowDelete(pseudonym)))
        assertEquals(0, r.store.labeledCount())
        assertEquals(listOf(Frame.WindowDeleted(pseudonym)), s.send(Frame.WindowDelete(pseudonym))) // already gone: still confirmed
    }

    @Test
    fun aSevereCapsulesMinuteKeysAreKeptOnlyIfTheyOpenAndAreReleasedForThatCapsule() {
        val r = Rig()
        val s = r.connect()
        val minuteKey = ByteArray(32) { 7 }
        val sealed = SealedBox.seal(r.service.serverPublicKey(), minuteKey, SealedBox.INFO_ESCROW)
        assertEquals(listOf(Frame.EscrowAck(0, 28_333_333)), s.send(Frame.Escrow("C1", 0, 28_333_333, sealed)))
        assertEquals(listOf(Frame.EscrowAck(0, 28_333_333)), s.send(Frame.Escrow("C1", 0, 28_333_333, sealed))) // resent: stored once
        assertEquals(1, r.store.log(s.stream!!, ESCROW).size)
        // Sealed for another purpose (a crash-signal copy), or garbage: not kept, not acknowledged.
        assertTrue(s.send(Frame.Escrow("C1", 0, 28_333_334, SealedBox.seal(r.service.serverPublicKey(), minuteKey, SealedBox.INFO_SERVER))).isEmpty())
        assertTrue(s.send(Frame.Escrow("C1", 0, 28_333_335, "e1.x.y")).isEmpty())
        val keys = r.service.minuteKeys(s.stream!!, "C1")
        assertEquals(listOf(Triple(0, 28_333_333L, java.util.Base64.getEncoder().encodeToString(minuteKey))), keys)
        assertTrue(r.service.minuteKeys(s.stream!!, "C2").isEmpty())
    }

    @Test
    fun drivesAreCutAtGapsByTheCarsOwnClockAndListedOnlyForTheirOwner() {
        val r = Rig()
        val s = r.connect()
        val ring = MemoryKeyRing()
        val chain = FileEventStore(tmp.newFolder(), cipher = EpochRecordCipher(ring) { 1_700_000_000_000 + r.t })
        fun speed(kmh: Float) = chain.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(kmh / 3.6f), r.ts())))
        repeat(10) { r.t += 1_000; speed(50f) }
        r.t += Tuning.DRIVE_GAP_MS // parked a while
        ring.rotate("buyer") // and sold
        repeat(5) { r.t += 1_000; speed(30f) }
        s.send(Frame.Batch(EscalationLevel.NORMAL, chain.records(0, 14)))
        val all = r.service.drives(s.stream!!) { true }
        assertEquals(listOf(10L..14L, 0L..9L), all.map { it.firstSeq..it.lastSeq }) // newest first
        assertEquals(1_700_000_001_000L, all.last().startMs)
        assertEquals(listOf(0L..9L), r.service.drives(s.stream!!) { it == 0 }.map { it.firstSeq..it.lastSeq }) // the seller's own
    }
}
