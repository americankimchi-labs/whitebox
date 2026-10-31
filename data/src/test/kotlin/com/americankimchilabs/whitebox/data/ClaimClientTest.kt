package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.MirrorChannel
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class ClaimClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Channel : MirrorChannel {
        override val connected = MutableStateFlow(true)
        override val incoming = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val serverKey: ByteArray? = null
        val sent = mutableListOf<Frame>()
        override fun send(frame: String): Boolean = sent.add(MirrorProtocol.decode(frame)!!)
        val wraps get() = sent.filterIsInstance<Frame.Wrap>()
    }

    private fun phone(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun KeyPair.b64() = Base64.getEncoder().encodeToString(public.encoded)

    private class Car(val ch: Channel, val ring: KeyRing, val claim: ClaimClient)

    private fun TestScope.car(ring: KeyRing = MemoryKeyRing()): Car {
        val ch = Channel()
        val claim = ClaimClient(ch, ring, tmp.newFolder())
        claim.start(backgroundScope)
        runCurrent()
        return Car(ch, ring, claim)
    }

    /** What a phone does after scanning the QR; returns the CLAIMED frame the server would forward. */
    private suspend fun TestScope.scan(car: Car, ownerId: String, phone: KeyPair, code: String = "ABCD2345"): Frame.Claimed {
        car.claim.request()
        car.ch.incoming.emit(MirrorProtocol.encode(Frame.ClaimCode(code)))
        runCurrent()
        val secret = (car.claim.state.value as ClaimClient.State.Waiting).secret
        return Frame.Claimed(ownerId, phone.b64(), MirrorProtocol.claimMac(Base64.getUrlDecoder().decode(secret), code, phone.b64()), "010-****-5678")
    }

    private suspend fun TestScope.deliver(car: Car, f: Frame) {
        car.ch.incoming.emit(MirrorProtocol.encode(f))
        runCurrent()
    }

    @Test
    fun theCarWrapsItsKeysOnlyForThePhoneThatSawItsScreen() = runTest {
        val car = car()
        val owner = phone()
        val claimed = scan(car, "o1", owner)
        // A key substituted by someone who never saw the QR: its MAC cannot be right, nothing is wrapped.
        deliver(car, claimed.copy(phoneKey = phone().b64()))
        assertTrue(car.ch.wraps.isEmpty())

        deliver(car, claimed)
        val wrap = car.ch.wraps.single()
        assertEquals("o1", wrap.recipientId)
        assertArrayEquals(car.ring.key(0), SealedBox.open(owner.private, wrap.sealed, SealedBox.INFO_WRAP))
        assertEquals(ClaimClient.State.Claimed("010-****-5678"), car.claim.state.value)

        deliver(car, claimed) // the server re-sends until it holds the wraps: answered again
        assertEquals(2, car.ch.wraps.size)
    }

    @Test
    fun aNewOwnerGetsOnlyANewKeyWhileTheSameOwnerOnANewPhoneGetsAllOfTheirs() = runTest {
        val car = car()
        deliver(car, scan(car, "o1", phone()))
        car.ring.rotate("o1") // an earlier owner change back, say: two epochs of theirs
        car.ch.sent.clear()

        // The same owner with a new phone (the old one lost): every epoch of theirs, to the new phone.
        val newPhone = phone()
        deliver(car, scan(car, "o1", newPhone, "SAME2345"))
        assertEquals(1, car.ring.current) // no new epoch
        assertEquals(listOf(0, 1), car.ch.wraps.map { it.epoch })
        assertArrayEquals(car.ring.key(1), SealedBox.open(newPhone.private, car.ch.wraps.last().sealed, SealedBox.INFO_WRAP))
        car.ch.sent.clear()

        // The car was sold: the new owner gets a fresh epoch and nothing older.
        val buyer = phone()
        deliver(car, scan(car, "o2", buyer, "SOLD2345"))
        assertEquals(2, car.ring.current)
        val w = car.ch.wraps.single()
        assertEquals(2 to "o2", w.epoch to w.recipientId)
        assertEquals(mapOf(0 to "o1", 1 to "o1", 2 to "o2"), car.ring.owners())
    }

    /** A different number asked; the server forwards its key and MAC while the owner decides. */
    private fun pending(claimed: Frame.Claimed, transferId: String = "t1") =
        Frame.ClaimPending(transferId, claimed.phoneKey, claimed.mac, "010-****-9999")

    @Test
    fun duringATakeoverTheCarRecordsUnderAKeyNobodyHoldsThatGoesToTheBuyerWhenApproved() = runTest {
        val car = car()
        val owner = phone()
        deliver(car, scan(car, "o1", owner))
        val buyer = phone()
        val buyerClaim = scan(car, "o2", buyer, "SOLD2345")
        deliver(car, pending(buyerClaim).copy(mac = "forged"))
        assertEquals(0, car.ring.current) // a request that never saw the screen changes nothing
        car.ch.sent.clear()

        deliver(car, pending(buyerClaim))
        assertEquals(1, car.ring.current)
        assertEquals("pending:t1", car.ring.owners()[1])
        assertTrue(car.ch.wraps.isEmpty()) // nobody can open it yet
        assertEquals(listOf(Frame.TransferAck("t1")), car.ch.sent)
        assertEquals(ClaimClient.State.TransferPending("010-****-9999"), car.claim.state.value)
        car.ch.sent.clear()

        deliver(car, buyerClaim) // approved, or three days without an answer
        assertEquals(1, car.ring.current) // the key started at the request is theirs: nothing in between is lost
        val w = car.ch.wraps.single()
        assertEquals(1 to "o2", w.epoch to w.recipientId)
        assertArrayEquals(car.ring.key(1), SealedBox.open(buyer.private, w.sealed, SealedBox.INFO_WRAP))
        assertEquals(mapOf(0 to "o1", 1 to "o2"), car.ring.owners())
    }

    @Test
    fun aDeclinedTakeoverGivesTheWaitingRecordsBackToTheOwner() = runTest {
        val dir = tmp.newFolder()
        val ch = Channel()
        val ring = MemoryKeyRing()
        val claim = ClaimClient(ch, ring, dir)
        claim.start(backgroundScope)
        runCurrent()
        var car = Car(ch, ring, claim)
        val owner = phone()
        deliver(car, scan(car, "o1", owner))
        val stranger = scan(car, "o2", phone(), "VALET234")
        deliver(car, pending(stranger))
        assertEquals("pending:t1", ring.owners()[1])

        // The head unit restarts during the wait.
        val ch2 = Channel()
        val claim2 = ClaimClient(ch2, ring, dir)
        assertEquals(ClaimClient.State.TransferPending("010-****-9999"), claim2.state.value)
        claim2.start(backgroundScope)
        runCurrent()
        car = Car(ch2, ring, claim2)
        deliver(car, pending(stranger)) // re-sent on reconnect: acked again, no second key
        assertEquals(1, ring.current)

        deliver(car, Frame.ClaimDeclined("t1"))
        assertEquals(mapOf(0 to "o1", 1 to "o1"), ring.owners())
        assertEquals(listOf(0, 1), ch2.wraps.map { it.epoch })
        assertArrayEquals(ring.key(1), SealedBox.open(owner.private, ch2.wraps.last().sealed, SealedBox.INFO_WRAP))
        assertEquals(Frame.TransferAck("t1"), ch2.sent.last())
        assertEquals(ClaimClient.State.Claimed("010-****-5678"), claim2.state.value)
        ch2.sent.clear()
        deliver(car, stranger) // a late CLAIMED for the declined phone (should never come): the MAC's QR is used up
        assertTrue(ch2.wraps.none { it.recipientId == "o2" })
    }

    @Test
    fun theClaimSurvivesARestart() = runTest {
        val dir = tmp.newFolder()
        val ch = Channel()
        val ring = MemoryKeyRing()
        val claim = ClaimClient(ch, ring, dir)
        claim.start(backgroundScope)
        runCurrent()
        val car = Car(ch, ring, claim)
        deliver(car, scan(car, "o1", phone()))
        assertEquals(ClaimClient.State.Claimed("010-****-5678"), ClaimClient(Channel(), ring, dir).state.value)
    }
}
