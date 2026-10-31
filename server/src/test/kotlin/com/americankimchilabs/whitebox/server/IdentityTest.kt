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

        fun activeMom(ownerId: String): Identity.Contact {
            val c = id.invite(ownerId, "엄마", mom, "http://x")
            now += 31_000
            id.sendOtp(mom, "contact", "c-mom")
            assertNotNull(id.accept(c.inviteToken, mom, texts.code(mom), "MOMKEY"))
            id.putWrap("car1", c.id, 0, "W0-mom")
            assertTrue(id.activate(ownerId, c.id))
            return id.contact(c.id)!!
        }
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
    fun aContactMustAcceptWithTheInvitedNumberAndBecomesActiveOnlyWithTheOwnersWrap() {
        val r = Rig()
        val owner = r.ownerId()
        val c = r.id.invite(owner, "엄마", r.mom, "http://localhost:8080")
        val token = Regex("contact#(\\S+)").find(r.texts.last(r.mom))!!.groupValues[1]
        assertEquals(c.id, r.id.contactByInvite(token)!!.id)

        val stranger = "01099998888"
        r.id.sendOtp(stranger, "contact", "x")
        assertNull(r.id.accept(token, stranger, r.texts.code(stranger), "STRANGERKEY")) // not the invited number
        assertFalse(r.id.activate(owner, c.id)) // nothing accepted yet

        r.now += 31_000
        r.id.sendOtp(r.mom, "contact", "m")
        val session = r.id.accept(token, r.mom, r.texts.code(r.mom), "MOMKEY")!!
        assertEquals(listOf(c.id), r.id.contactsByPhoneHash(session).map { it.id })
        assertNull(r.id.contactByInvite(token)) // used
        assertTrue(r.id.activate(owner, c.id))
        assertFalse(r.id.activate("someone-else", c.id))
    }

    @Test
    fun removingAContactDeletesTheirKeysAndAsksTheCarForANewOne() {
        val r = Rig()
        val owner = r.ownerId()
        r.id.putWrap("car1", owner, 0, "W0-owner")
        val mom = r.activeMom(owner)
        assertTrue(r.id.removeContact(mom.id, byContact = true))
        assertTrue(r.id.wraps("car1", mom.id).isEmpty())
        assertTrue(r.id.contactsOf(owner).isEmpty())
        assertTrue("해제" in r.texts.last(r.owner)) // the owner is told when the contact withdraws
        assertTrue(r.id.pendingRotation("car1"))
        r.id.putWrap("car1", owner, 0, "W0-again") // not newer: still pending
        assertTrue(r.id.pendingRotation("car1"))
        r.id.putWrap("car1", owner, 1, "W1-owner") // the car's new epoch arrived
        assertFalse(r.id.pendingRotation("car1"))
        assertFalse(r.id.removeContact(mom.id, byContact = false)) // once
        assertNull(r.id.login(Identity.CONTACT, r.mom, "000000")) // a former contact cannot sign in
    }

    @Test
    fun ownersAndContactsSignInAgainWithTheirNumber() {
        val r = Rig()
        val owner = r.ownerId()
        r.activeMom(owner)
        r.now += 31_000
        r.id.sendOtp(r.owner, "login", "o")
        assertNotNull(r.id.session(r.id.login(Identity.OWNER, r.owner, r.texts.code(r.owner))))
        r.now += 31_000
        r.id.sendOtp(r.mom, "login", "m")
        assertNotNull(r.id.login(Identity.CONTACT, r.mom, r.texts.code(r.mom)))
        r.now += 31_000
        r.id.sendOtp(r.buyer, "login", "b")
        assertNull(r.id.login(Identity.OWNER, r.buyer, r.texts.code(r.buyer))) // not an owner
    }

    @Test
    fun recoveryNeedsTheOwnersNumberAndEveryKeyTheContactHolds() {
        val r = Rig()
        val owner = r.ownerId()
        r.id.putWrap("car1", owner, 0, "OLD0")
        val mom = r.activeMom(owner)
        r.id.putWrap("car1", mom.id, 1, "W1-mom")

        r.now += 31_000
        r.id.sendOtp(r.owner, "recover", "o")
        val (rec, _) = r.id.startRecovery(r.owner, r.texts.code(r.owner), "PHONE2", "http://x")!!
        assertTrue("복구" in r.texts.last(r.mom)) // the contact is asked
        assertFalse(r.id.completeRecovery(rec.id, mom.id, mapOf(("car1" to 0) to "NEW0"))) // epoch 1 missing
        assertTrue(r.id.completeRecovery(rec.id, mom.id, mapOf(("car1" to 0) to "NEW0", ("car1" to 1) to "NEW1")))
        assertEquals(listOf(0 to "NEW0", 1 to "NEW1"), r.id.wraps("car1", owner).map { it.epoch to it.sealed })
        assertEquals("PHONE2", r.id.owner(owner)!!.ecdhKey)
        assertFalse(r.id.completeRecovery(rec.id, mom.id, mapOf(("car1" to 0) to "AGAIN", ("car1" to 1) to "AGAIN"))) // once

        // Everything survives a restart (sessions and codes do not: the phone just verifies again).
        val reloaded = r.identity()
        assertEquals("NEW1", reloaded.wraps("car1", owner).last().sealed)
        assertEquals(Identity.ACTIVE, reloaded.contact(mom.id)!!.state)
        assertEquals("PHONE2", reloaded.owner(owner)!!.ecdhKey)
    }

    @Test
    fun emergencyNoticesGoOnlyToActiveContacts() {
        val r = Rig()
        val owner = r.ownerId()
        r.activeMom(owner)
        r.id.invite(owner, "아빠", "01044445555", "http://x") // invited, never accepted
        r.texts.sent.clear()
        val logged = mutableListOf<EmergencyNotice>()
        ContactsNotifier(r.id, r.texts, object : ContactNotifier { override fun notify(notice: EmergencyNotice) { logged += notice } })
            .notify(EmergencyNotice(r.car, "ATTEST|…", "silent after a severe event", r.now))
        assertEquals(1, logged.size)
        assertEquals(listOf(r.mom), r.texts.sent.map { it.first })
    }

    @Test
    fun anOwnerWhoSoldTheCarCanStillRecoverThatHistoryThroughAContact() {
        val r = Rig()
        val owner = r.ownerId()
        r.id.putWrap("car1", owner, 0, "OLD0")
        val mom = r.activeMom(owner)
        val t = (r.claim(r.buyer) as Identity.ClaimResult.Pending).transfer
        r.id.answerTransfer(owner, t.id, approve = true)
        assertTrue(r.id.devicesOf(owner).isEmpty())

        r.now += 31_000
        r.id.sendOtp(r.owner, "recover", "o")
        val (rec, _) = r.id.startRecovery(r.owner, r.texts.code(r.owner), "PHONE2", "http://x")!!
        assertTrue(r.id.completeRecovery(rec.id, mom.id, mapOf(("car1" to 0) to "NEW0")))
        assertEquals(listOf("car1"), r.id.devicesHeldBy(owner))
        assertEquals("NEW0", r.id.wraps("car1", owner).single().sealed)
    }

    @Test
    fun aRestartSignsNobodyOutAndARepeatedRecoveryResumes() {
        val r = Rig()
        val owner = r.ownerId()
        r.now += 31_000
        r.id.sendOtp(r.owner, "login", "o")
        val token = r.id.login(Identity.OWNER, r.owner, r.texts.code(r.owner))!!
        assertEquals(owner, r.identity().session(token)!!.id) // after a restart
        assertFalse(r.dir.resolve("identity.log").readText().contains(token)) // only a hash is stored

        r.activeMom(owner)
        r.now += 31_000
        r.id.sendOtp(r.owner, "recover", "o")
        val (first, _) = r.id.startRecovery(r.owner, r.texts.code(r.owner), "PHONE2", "http://x")!!
        val textsToMom = r.texts.sent.count { it.first == r.mom }
        r.now += 31_000
        r.id.sendOtp(r.owner, "recover", "o")
        val (again, _) = r.id.startRecovery(r.owner, r.texts.code(r.owner), "PHONE2", "http://x")!!
        assertEquals(first.id, again.id)
        assertEquals(textsToMom, r.texts.sent.count { it.first == r.mom })
        assertEquals(1, r.id.pendingRecoveries(owner).size)
    }
}

