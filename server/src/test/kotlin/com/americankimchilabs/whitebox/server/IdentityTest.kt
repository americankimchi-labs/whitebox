package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.MirrorProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class IdentityTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Texts : SmsSender {
        val sent = mutableListOf<Pair<String, String>>()
        override fun send(phone: String, text: String) { sent += phone to text }
        fun code(phone: String) = sent.last { it.first == phone }.second.let { Regex("인증번호 (\\d{6})").find(it)!!.groupValues[1] }
        fun last(phone: String) = sent.last { it.first == phone }.second
    }

    private inner class Rig {
        var now = 1_000_000L
        val dir = tmp.newFolder()
        val key = ServerKey(dir)
        val texts = Texts()
        fun identity() = Identity(dir, key, texts) { now }
        val id = identity()
        val car = StreamId("car1", "live")
        val owner = "01012345678"
        val mom = "01022223333"
        val buyer = "01077778888"

        /** A phone scanning the car's QR and proving [phone]. */
        fun claim(phone: String = owner, phoneKey: String = "KEY-${phone.hashCode()}"): Identity.ClaimResult {
            now += 31_000
            val code = id.claimCode(car)
            assertNull(id.sendOtp(phone, "claim", "c-$phone"))
            return id.claim(code, phone, texts.code(phone), phoneKey, "MAC")
        }

        fun ownerId() = (claim() as Identity.ClaimResult.Claimed).ownerId

        fun mom(ownerId: String): Identity.Contact = id.addContact(ownerId, "엄마", mom)
    }

    @Test
    fun oneTimeCodesAreSingleUseLimitedAndExpire() {
        val r = Rig()
        assertNull(r.id.sendOtp(r.owner, "claim", "phone-a"))
        assertNotNull(r.id.sendOtp(r.owner, "claim", "phone-a")) // again within 30 s
        val code = r.texts.code(r.owner)
        assertTrue(r.id.checkOtp(r.owner, code))
        assertFalse(r.id.checkOtp(r.owner, code)) // used up

        r.now += 31_000
        r.id.sendOtp(r.owner, "claim", "phone-a")
        val second = r.texts.code(r.owner)
        repeat(5) { assertFalse(r.id.checkOtp(r.owner, if (second == "000000") "111111" else "000000")) }
        assertFalse(r.id.checkOtp(r.owner, second)) // five wrong tries voided it

        r.now += 31_000
        r.id.sendOtp(r.owner, "claim", "phone-a")
        val third = r.texts.code(r.owner)
        r.now += 5 * 60_000 + 1
        assertFalse(r.id.checkOtp(r.owner, third)) // expired
    }

    @Test
    fun nobodyCanUseTheServiceToFloodNumbersWithTexts() {
        val r = Rig()
        // One device: five codes to five numbers in ten minutes, then a pause.
        repeat(Identity.OTP_PER_CLIENT) { assertNull(r.id.sendOtp("0101111000$it", "claim", "script")) }
        assertNotNull(r.id.sendOtp("01011119999", "claim", "script"))
        r.now += 10 * 60_000 + 1
        assertNull(r.id.sendOtp("01011119999", "claim", "script"))
        // Everyone together: a ceiling per minute.
        r.now += 60_001
        repeat(Identity.OTP_GLOBAL_PER_MIN) { assertNull(r.id.sendOtp("0102222%04d".format(it), "claim", "client-$it")) }
        assertNotNull(r.id.sendOtp("01033330000", "claim", "someone-else"))
    }

    @Test
    fun phoneNumbersAreStoredEncryptedAndTheirLookupKeyIsSecret() {
        val r = Rig()
        val ownerId = r.ownerId()
        val log = r.dir.resolve("identity.log").readText()
        assertFalse(log.contains(r.owner))
        // The lookup hash is not something anyone could compute from public information.
        val unkeyed = MessageDigest.getInstance("SHA-256").digest(r.owner.toByteArray()).joinToString("") { "%02x".format(it) }
        val publicKeyed = r.key.publicKey.joinToString("") { "%02x".format(it) }
        assertFalse(log.contains(unkeyed))
        assertNotEquals(r.id.owner(ownerId)!!.phoneHash, publicKeyed)
        assertEquals(r.owner, r.id.ownerPhone(r.id.owner(ownerId)!!))
    }

    @Test
    fun theSameNumberOnANewPhoneReconnectsAtOnce() {
        val r = Rig()
        val ownerId = r.ownerId()
        val again = r.claim(phoneKey = "NEW-PHONE") as Identity.ClaimResult.Claimed
        assertEquals(ownerId, again.ownerId)
        assertEquals("NEW-PHONE", r.id.owner(ownerId)!!.ecdhKey)
        assertTrue(r.texts.sent.none { "연결을 요청" in it.second }) // the owner is not bothered
    }

    @Test
    fun aDifferentNumberAsksTheOwnerWhoCanDecline() {
        val r = Rig()
        val ownerId = r.ownerId()
        val pending = r.claim(r.buyer) as Identity.ClaimResult.Pending
        assertEquals(ownerId, r.id.ownerOf("car1")!!.id) // nothing changed yet
        assertTrue("연결을 요청" in r.texts.last(r.owner))
        val t = r.id.transfersFrom(ownerId).single()
        assertEquals(pending.transfer.id, t.id)
        assertEquals(t.createdMs + 3 * 24 * 3600_000L, t.autoAtMs)
        assertTrue("3일 안에" in r.texts.last(r.owner))
        // The car hears of the request at once, to record under a key nobody holds yet; until it acks, on every reconnect.
        val asked = MirrorProtocol.decode(r.id.transferFrame("car1")!!) as MirrorProtocol.Frame.ClaimPending
        assertEquals(t.id to t.phoneKey, asked.transferId to asked.phoneKey)
        r.id.transferAcked("car1", t.id)
        assertNull(r.id.transferFrame("car1"))

        assertNull(r.id.answerTransfer(ownerId, t.id, approve = false))
        assertEquals(ownerId, r.id.ownerOf("car1")!!.id)
        assertTrue("거절" in r.texts.last(r.buyer))
        // The car gives what it recorded in the meantime back to the owner; that survives a restart until acked.
        assertEquals(MirrorProtocol.Frame.ClaimDeclined(t.id), MirrorProtocol.decode(r.identity().transferFrame("car1")!!))
        r.now += Identity.TRANSFER_WAIT_MS
        assertTrue(r.id.dueTransfers().isEmpty()) // a declined request never goes through
    }

    @Test
    fun withoutAnAnswerTheCarMovesAfterThreeDaysOrAtOnceWhenApproved() {
        val r = Rig()
        val ownerId = r.ownerId()
        val buyerId = (r.claim(r.buyer) as Identity.ClaimResult.Pending).ownerId
        r.now += Identity.TRANSFER_WAIT_MS - 1
        assertTrue(r.id.dueTransfers().isEmpty())
        r.now += 1
        assertNotNull(r.id.transferFrame("car1")) // never acked: the car is offline
        val moved = r.id.dueTransfers().single()
        assertNull(r.id.transferFrame("car1")) // the CLAIMED frame replaces it
        assertEquals(buyerId, moved.toOwnerId)
        assertEquals(buyerId, r.id.ownerOf("car1")!!.id)
        assertTrue("넘어갔습니다" in r.texts.last(r.owner))
        assertTrue(r.id.dueTransfers().isEmpty()) // once

        // Approving moves it at once.
        val third = "01055556666"
        val t = (r.claim(third) as Identity.ClaimResult.Pending).transfer
        assertNotNull(r.id.answerTransfer(buyerId, t.id, approve = true))
        assertNotEquals(ownerId, r.id.ownerOf("car1")!!.id)
        assertEquals(t.toOwnerId, r.id.ownerOf("car1")!!.id)
    }

    @Test
    fun anOwnerAddsAContactByNameAndNumberAndNobodyIsTexted() {
        val r = Rig()
        val owner = r.ownerId()
        r.texts.sent.clear()
        val mom = r.mom(owner)
        assertTrue(r.texts.sent.isEmpty()) // like a hospital form: not asked, not told
        assertEquals("엄마", r.id.contactName(mom))
        assertEquals(mom.id, r.mom(owner).id) // the same number twice is one entry
        assertTrue(r.id.isContactNumber(r.mom))
        assertFalse(r.id.isContactNumber(r.buyer))
        assertFalse(r.dir.resolve("identity.log").readText().contains(r.mom)) // encrypted at rest
        assertEquals("엄마", r.identity().contactName(r.identity().contact(mom.id)!!)) // survives a restart
    }

    @Test
    fun aRemovedContactOrOneWhoLeftCanNoLongerSignIn() {
        val r = Rig()
        val owner = r.ownerId()
        val mom = r.mom(owner)
        r.texts.sent.clear()
        assertTrue(r.id.removeContact(mom.id, byContact = true))
        assertTrue(r.texts.sent.isEmpty()) // shown on the owner's page, never texted
        assertEquals(listOf(mom.id), r.id.contactsWhoLeft(owner).map { it.id })
        assertTrue(r.id.contactsOf(owner).isEmpty())
        assertFalse(r.id.removeContact(mom.id, byContact = false)) // once
        r.now += 31_000
        r.id.sendOtp(r.mom, "contact", "m")
        assertNull(r.id.login(Identity.CONTACT, r.mom, r.texts.code(r.mom)))
    }

    @Test
    fun ownersAndContactsSignInAgainWithTheirNumber() {
        val r = Rig()
        val owner = r.ownerId()
        r.mom(owner)
        r.now += 31_000
        r.id.sendOtp(r.owner, "login", "o")
        assertNotNull(r.id.session(r.id.login(Identity.OWNER, r.owner, r.texts.code(r.owner))))
        r.now += 31_000
        r.id.sendOtp(r.mom, "contact", "m")
        assertNotNull(r.id.login(Identity.CONTACT, r.mom, r.texts.code(r.mom)))
        r.now += 31_000
        r.id.sendOtp(r.buyer, "login", "b")
        assertNull(r.id.login(Identity.OWNER, r.buyer, r.texts.code(r.buyer))) // not an owner
    }

    @Test
    fun aNewPhoneOfTheOwnerReconnectsByScanningTheCarAgain() {
        val r = Rig()
        val owner = r.ownerId()
        val again = r.claim(phoneKey = "PHONE2") as Identity.ClaimResult.Claimed
        assertEquals(owner, again.ownerId)
        assertEquals("PHONE2", r.id.owner(owner)!!.ecdhKey) // the car wraps every key of theirs to it
        assertTrue(r.id.transfersFrom(owner).isEmpty()) // their own number: no takeover
    }

    @Test
    fun aRestartSignsNobodyOut() {
        val r = Rig()
        val owner = r.ownerId()
        r.now += 31_000
        r.id.sendOtp(r.owner, "login", "o")
        val token = r.id.login(Identity.OWNER, r.owner, r.texts.code(r.owner))!!
        assertEquals(owner, r.identity().session(token)!!.id) // after a restart
        assertFalse(r.dir.resolve("identity.log").readText().contains(token)) // only a hash is stored
    }
}
