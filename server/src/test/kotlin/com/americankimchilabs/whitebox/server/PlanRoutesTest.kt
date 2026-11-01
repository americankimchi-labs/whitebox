package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.CapsuleSeal
import com.americankimchilabs.whitebox.data.FileEventStore
import com.americankimchilabs.whitebox.data.MirrorProtocol.Record
import com.americankimchilabs.whitebox.data.SealStore
import com.americankimchilabs.whitebox.data.Signer
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Tier
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.VehicleSignal
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** What a plan includes, as the owner's phone meets it: handover, saved clips, extensions and changing plan. */
class PlanRoutesTest {
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

    private val owner = "01011112222"

    /** A Free owner's car with two kept moments and a crash. */
    private inner class FreeCar {
        var now = 1_700_000_100_000L
        val dir = tmp.newFolder()
        val key = ServerKey(dir)
        val texts = Texts()
        val identity = Identity(dir, key, texts) { now }
        val store = FileStreamStore(dir)
        val plans = Plans(dir, Tier.FREE, DemoPayment()) { now }
        val service = MirrorService(store, key, identity, clock = { now }, plans = plans, clips = ClipStore(dir))
        val stream = StreamId("car1", "live")
        val token: String

        init {
            now += 31_000
            identity.sendOtp(owner, "claim", owner)
            val ownerId = (identity.claim(identity.claimCode(stream), owner, texts.code(owner), "KEY", "MAC") as Identity.ClaimResult.Claimed).ownerId
            identity.putWrap("car1", ownerId, 0, "W0")
            val chain = FileEventStore(tmp.newFolder())
            val car = Car()
            var t = 0L
            fun ts() = Timestamp(t * 1_000_000, 1_700_000_000_000 + t)
            repeat(30) { t += 100; chain.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(10f + it % 2), ts()))) }
            fun seal(id: String, trigger: CapsuleTrigger, from: Long, to: Long) = store.appendLog(
                stream, "seals", SealStore.line(CapsuleSeal.create(IncidentCapsule(id, trigger, from, to), chain.hashAt(to), true, 1_700_000_000_000 + to * 100, car)),
            )
            seal("A", CapsuleTrigger.EVASIVE_STEER, 0, 5)
            seal("B", CapsuleTrigger.LURCH, 10, 15)
            seal("CRASH", CapsuleTrigger.DELTA_V, 20, 25)
            store.put(stream, chain.records(0, 29).map { Record.parse(it)!! }, now)
            now += 31_000
            identity.sendOtp(owner, "login", owner)
            token = identity.login(Identity.OWNER, owner, texts.code(owner))!!
        }
    }

    @Test
    fun freeShowsWhatItIncludesAndUpgradingOpensTheRest() = testApplication {
        val car = FreeCar()
        application { whiteBox(car.service, tickMs = 60_000) }
        val cookie = "wb_owner=${car.token}"
        val me = client.get("/v1/me") { header("Cookie", cookie) }.bodyAsText()
        // A crash without an airbag (충돌) takes a slot like any moment.
        assertTrue(me, "\"tier\":\"FREE\"" in me && "\"handover\":false" in me && "\"librarySlots\":3" in me && "\"libraryUsed\":3" in me)
        assertTrue(me, "\"upgrades\":[\"PLUS\",\"PRO\"]" in me)
        val listed = client.get("/v1/capsules?device=car1&stream=live") { header("Cookie", cookie) }.bodyAsText()
        assertTrue(listed, Regex("\"id\":\"CRASH\",\"trigger\":\"DELTA_V\",\"severe\":false,\"crash\":true").containsMatchIn(listed))

        // Handover mode is not part of Free.
        val arm = client.submitForm("/v1/handover", parameters { append("device", "car1"); append("action", "arm") }) { header("Cookie", cookie) }
        assertEquals(400, arm.status.value)
        assertTrue("플러스 요금제부터" in arm.bodyAsText())

        // Saved clips: two on Free; a third asks to replace the oldest.
        val bytes = ByteArray(5_000) { (it % 251).toByte() }
        suspend fun save(id: String, clip: String, replace: String? = null) =
            client.post("/v1/clips?device=car1&stream=live&id=$id&clip=$clip&offsetMs=0&name=$clip.MP4${replace?.let { "&replace=$it" } ?: ""}") { header("Cookie", cookie); setBody(bytes) }.bodyAsText()
        assertTrue("\"ok\":true" in save("A", "clipAAAAAAAAAAAAAAAA"))
        assertTrue("\"ok\":true" in save("B", "clipBBBBBBBBBBBBBBBB"))
        val third = save("CRASH", "clipCCCCCCCCCCCCCCCC")
        assertTrue(third, "\"full\":true" in third && "\"replaceable\":[{\"id\":\"clipAAAAAAAAAAAAAAAA\"" in third)
        assertTrue("\"ok\":true" in save("CRASH", "clipCCCCCCCCCCCCCCCC", replace = "clipAAAAAAAAAAAAAAAA"))
        assertArrayEquals(bytes, client.get("/v1/clips/get?clip=clipCCCCCCCCCCCCCCCC") { header("Cookie", cookie) }.readRawBytes())

        // An extension keeps B (and its clip) longer, and both still use their slots.
        val ext = client.submitForm("/v1/extend", parameters { append("device", "car1"); append("id", "B"); append("years", "1") }) { header("Cookie", cookie) }.bodyAsText()
        assertTrue(ext, "\"ok\":true" in ext && "시연용 결제" in ext)
        val capsules = client.get("/v1/capsules?device=car1&stream=live") { header("Cookie", cookie) }.bodyAsText()
        assertTrue(capsules, Regex("\"id\":\"B\"[^}]*\"status\":\"EXTENDED\"").containsMatchIn(capsules))
        assertTrue(client.get("/v1/me") { header("Cookie", cookie) }.bodyAsText().let { "\"clipsUsed\":2" in it && "\"libraryUsed\":3" in it })

        // A paid clip is never replaced: only the unpaid one is offered, and with both paid only a bigger plan helps.
        val again = save("A", "clipDDDDDDDDDDDDDDDD")
        assertTrue(again, "\"replaceable\":[{\"id\":\"clipCCCCCCCCCCCCCCCC\"" in again && "clipBBBBBBBBBBBBBBBB\",\"savedAtMs" !in again.substringAfter("replaceable").substringBefore("saved\":"))
        assertTrue("\"ok\":false" in save("A", "clipDDDDDDDDDDDDDDDD", replace = "clipBBBBBBBBBBBBBBBB"))
        client.submitForm("/v1/extend", parameters { append("device", "car1"); append("id", "CRASH"); append("years", "1") }) { header("Cookie", cookie) }
        val allPaid = save("A", "clipDDDDDDDDDDDDDDDD")
        assertTrue(allPaid, "\"replaceable\":[]" in allPaid && "\"upgrades\":[\"PLUS\",\"PRO\"]" in allPaid && "\"paid\":true" in allPaid)

        // A paid record stays to the end that was paid for, well past its first 30 days.
        client.submitForm("/v1/extend", parameters { append("device", "car1"); append("id", "A"); append("years", "1") }) { header("Cookie", cookie) }
        car.now += 31 * 86_400_000L
        car.identity.sendOtp(owner, "login", owner) // a month later: sign in again
        val relogged = "wb_owner=${car.identity.login(Identity.OWNER, owner, car.texts.code(owner))!!}"
        val later = client.get("/v1/capsules?device=car1&stream=live") { header("Cookie", relogged) }.bodyAsText()
        assertTrue(later, Regex("\"id\":\"A\"[^}]*\"status\":\"EXTENDED\"").containsMatchIn(later))

        // Moving to Pro (the demonstration's payment) opens handover mode at once.
        val plan = client.submitForm("/v1/plan", parameters { append("tier", "PRO") }) { header("Cookie", relogged) }.bodyAsText()
        assertTrue(plan, "\"ok\":true" in plan)
        assertTrue(client.get("/v1/me") { header("Cookie", relogged) }.bodyAsText().let { "\"tier\":\"PRO\"" in it && "\"handover\":true" in it })
        assertEquals(200, client.submitForm("/v1/handover", parameters { append("device", "car1"); append("action", "arm") }) { header("Cookie", relogged) }.status.value)
    }
}
