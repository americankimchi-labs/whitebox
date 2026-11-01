package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.CapsuleSeal
import com.americankimchilabs.whitebox.data.EpochRecordCipher
import com.americankimchilabs.whitebox.data.FileEventStore
import com.americankimchilabs.whitebox.data.MemoryKeyRing
import com.americankimchilabs.whitebox.data.MirrorProtocol.Record
import com.americankimchilabs.whitebox.data.SealStore
import com.americankimchilabs.whitebox.data.Signer
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.VehicleSignal
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.parameters
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** The phone routes as a browser calls them, for what each person may and may not see of a car that was sold. */
class WebRoutesTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Texts : SmsSender {
        val sent = mutableListOf<Pair<String, String>>()
        override fun send(phone: String, text: String) { sent += phone to text }
        fun code(phone: String) = Regex("인증번호 \\[(\\d{6})]").find(sent.last { it.first == phone }.second)!!.groupValues[1]
    }

    private class Car : Signer {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val publicKey: ByteArray = pair.public.encoded
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
    }

    private val seller = "01011112222"
    private val buyer = "01033334444"
    private val family = "01055556666"

    /**
     * A car driven by its first owner (a hard brake and a crash), then sold: the buyer drives with a crash too. Both
     * owners listed the same family member. Returns the service, identity and each person's session token.
     */
    private inner class SoldCar {
        var now = 1_000_000L
        val dir = tmp.newFolder()
        val key = ServerKey(dir)
        val texts = Texts()
        val identity = Identity(dir, key, texts) { now }
        val store = FileStreamStore(dir)
        val service = MirrorService(store, key, identity, clock = { now })
        val device = "car1"
        val stream = StreamId(device, "live")

        private fun otp(phone: String, purpose: String): String {
            now += 31_000
            identity.sendOtp(phone, purpose, phone)
            return texts.code(phone)
        }

        fun claim(phone: String): Identity.ClaimResult = identity.claim(identity.claimCode(stream), phone, otp(phone, "claim"), "KEY-$phone", "MAC")
        fun login(role: String, phone: String) = identity.login(role, phone, otp(phone, if (role == Identity.CONTACT) "contact" else "login"))!!

        init {
            val ring = MemoryKeyRing()
            var t = 0L
            val chain = FileEventStore(tmp.newFolder(), cipher = EpochRecordCipher(ring) { 1_700_000_000_000 + t })
            val car = Car()
            fun ts() = Timestamp(t * 1_000_000, 1_700_000_000_000 + t)
            fun drive(from: Long) {
                repeat(20) { t += 100; chain.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(15f), ts()))) }
                chain.append(ChainEntry.Escalation(EscalationEvent.Triggered(CapsuleTrigger.AIRBAG, from + 10, 0f), ts()))
                val last = chain.head()!!.lastSeq
                SealStore.line(CapsuleSeal.create(IncidentCapsule("C$from", CapsuleTrigger.AIRBAG, from, last), chain.hashAt(last), true, 1_700_000_000_000 + t, car))
                    .let { store.appendLog(stream, "seals", it) }
            }
            val sellerId = (claim(seller) as Identity.ClaimResult.Claimed).ownerId
            identity.putWrap(device, sellerId, 0, "W0")
            identity.addContact(sellerId, "엄마", family)
            drive(0)
            t += 3_600_000 // sold the next hour
            val transfer = (claim(buyer) as Identity.ClaimResult.Pending).transfer
            identity.answerTransfer(sellerId, transfer.id, approve = true)
            ring.rotate(transfer.toOwnerId)
            identity.putWrap(device, transfer.toOwnerId, 1, "W1")
            identity.addContact(transfer.toOwnerId, "엄마", family)
            drive(chain.head()!!.lastSeq + 1)
            store.put(stream, chain.records(0, chain.head()!!.lastSeq).map { Record.parse(it)!! }, now)
        }
    }

    @Test
    fun aSellerSeesNothingOfTheBuyersDrivingNotEvenWhenAskingForItsRecordNumbers() = testApplication {
        val car = SoldCar()
        application { whiteBox(car.service, tickMs = 60_000) }
        val token = car.login(Identity.OWNER, seller)
        val body = client.get("/v1/records?device=car1&stream=live&from=0&to=100") { header("Cookie", "wb_owner=$token") }.bodyAsText()
        val seqs = Regex("\"seq\":(\\d+)").findAll(body).map { it.groupValues[1].toLong() }.toList()
        assertEquals((0L..20L).toList(), seqs) // the seller's own drive and its crash marker, nothing after the sale
        assertFalse("TRIGGER|DELTA_V|31" in body) // the buyer's crash marker stays out
        val drives = client.get("/v1/drives?device=car1&stream=live") { header("Cookie", "wb_owner=$token") }.bodyAsText()
        assertEquals(1, Regex("\"firstSeq\"").findAll(drives).count())
        val capsules = client.get("/v1/capsules?device=car1&stream=live") { header("Cookie", "wb_owner=$token") }.bodyAsText()
        assertTrue("\"C0\"" in capsules && "\"C21\"" !in capsules)
    }

    @Test
    fun familyListedByBothOwnersOpensEachOwnersCrashAndNoEverydayRecords() = testApplication {
        val car = SoldCar()
        application { whiteBox(car.service, tickMs = 60_000) }
        val token = car.login(Identity.CONTACT, family)
        val capsules = client.get("/v1/capsules?device=car1&stream=live") { header("Cookie", "wb_contact=$token") }.bodyAsText()
        assertTrue(capsules, "\"C0\"" in capsules && "\"C21\"" in capsules)
        for (id in listOf("C0", "C21")) {
            val one = client.get("/v1/capsule?device=car1&stream=live&id=$id") { header("Cookie", "wb_contact=$token") }.bodyAsText()
            assertTrue(one, "\"records\"" in one)
        }
        assertEquals(401, client.get("/v1/records?device=car1&stream=live&kind=L&last=5") { header("Cookie", "wb_contact=$token") }.status.value)
        assertEquals(401, client.get("/v1/drives?device=car1&stream=live") { header("Cookie", "wb_contact=$token") }.status.value)
    }

    @Test
    fun aQrShareOpensThatCapsuleForAnyoneHoldingTheCodeUntilItExpires() = testApplication {
        val car = SoldCar()
        application { whiteBox(car.service, tickMs = 60_000) }
        val code = "c0de-c0de-c0de-c0de"
        car.store.appendLog(car.stream, SHARES, "C21|${com.americankimchilabs.whitebox.data.CapsuleShares.codeHash(code)}|${car.now + 1_000}")
        suspend fun open(id: String, t: String) = client.submitForm("/v1/shared", parameters { append("device", "car1"); append("stream", "live"); append("id", id); append("t", t) })
        val ok = open("C21", code)
        assertEquals(200, ok.status.value)
        assertTrue("\"records\"" in ok.bodyAsText())
        assertEquals(400, open("C21", "wrong").status.value)
        assertEquals(400, open("C0", code).status.value) // the code is for one capsule only
        // The owner sees that someone opened it through the QR.
        val buyer = car.login(Identity.OWNER, buyer)
        assertTrue("\"shared\":true" in client.get("/v1/me") { header("Cookie", "wb_owner=$buyer") }.bodyAsText())
        car.now += 2_000 // expired
        assertEquals(400, open("C21", code).status.value)
    }

    @Test
    fun handoverFromThePhoneReachesTheCarUntilAcknowledgedAndItsStatusShows() = testApplication {
        val car = SoldCar()
        application { whiteBox(car.service, tickMs = 60_000) }
        val buyer = car.login(Identity.OWNER, buyer)
        val seller = car.login(Identity.OWNER, seller)
        suspend fun post(path: String, token: String, vararg p: Pair<String, String>) = client.submitForm(path, parameters { p.forEach { (k, v) -> append(k, v) } }) { header("Cookie", "wb_owner=$token") }
        assertEquals(400, post("/v1/handover", seller, "device" to "car1", "action" to "arm").status.value) // sold: not theirs any more
        assertEquals(200, post("/v1/handover", buyer, "device" to "car1", "action" to "arm").status.value)
        val cmd = com.americankimchilabs.whitebox.data.MirrorProtocol.decode(car.identity.handoverFrame("car1")!!) as com.americankimchilabs.whitebox.data.MirrorProtocol.Frame.HandoverCommand
        assertTrue(cmd.arm)
        assertTrue("\"pending\":true" in client.get("/v1/me") { header("Cookie", "wb_owner=$buyer") }.bodyAsText())
        // The car carries it out and reports back.
        car.identity.handoverAcked("car1", cmd.commandId)
        car.identity.setHandoverStatus("car1", com.americankimchilabs.whitebox.data.MirrorProtocol.encode(com.americankimchilabs.whitebox.data.MirrorProtocol.Frame.HandoverStatus("ARMED", car.now, car.now, "-")))
        val me = client.get("/v1/me") { header("Cookie", "wb_owner=$buyer") }.bodyAsText()
        assertTrue(me, "\"phase\":\"ARMED\"" in me && "\"pending\":null" in me)
        assertEquals(null, car.identity.handoverFrame("car1"))
    }

    @Test
    fun aShareLinkMadeOnThePhoneOpensThatCapsuleOnly() = testApplication {
        val car = SoldCar()
        application { whiteBox(car.service, tickMs = 60_000) }
        val buyer = car.login(Identity.OWNER, buyer)
        val code = "phone-made-code"
        val hash = com.americankimchilabs.whitebox.data.CapsuleShares.codeHash(code)
        val made = client.submitForm("/v1/share", parameters { append("device", "car1"); append("stream", "live"); append("id", "C21"); append("hash", hash) }) { header("Cookie", "wb_owner=$buyer") }
        assertEquals(200, made.status.value)
        // Not for a capsule of the seller's time.
        assertEquals(400, client.submitForm("/v1/share", parameters { append("device", "car1"); append("stream", "live"); append("id", "C0"); append("hash", hash) }) { header("Cookie", "wb_owner=$buyer") }.status.value)
        val open = client.submitForm("/v1/shared", parameters { append("device", "car1"); append("stream", "live"); append("id", "C21"); append("t", code) })
        assertEquals(200, open.status.value)
        assertEquals(400, client.submitForm("/v1/shared", parameters { append("device", "car1"); append("stream", "live"); append("id", "C0"); append("t", code) }).status.value)
    }
}
