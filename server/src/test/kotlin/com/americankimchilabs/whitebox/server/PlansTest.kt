package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.CapsuleSeal
import com.americankimchilabs.whitebox.data.FileEventStore
import com.americankimchilabs.whitebox.data.MirrorProtocol
import com.americankimchilabs.whitebox.data.SealedBox
import com.americankimchilabs.whitebox.data.EntryCodec
import com.americankimchilabs.whitebox.data.MirrorProtocol.Frame
import com.americankimchilabs.whitebox.data.SealStore
import com.americankimchilabs.whitebox.data.Signer
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.Extension
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.LibraryStatus
import com.americankimchilabs.whitebox.domain.Retention.DAY_MS
import com.americankimchilabs.whitebox.domain.Retention.YEAR_MS
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Tier
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Plans, extensions and retention on the server: what is kept, for how long, and what the car is told. */
class PlansTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Car : Signer {
        private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        override val publicKey: ByteArray = pair.public.encoded
        override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
        fun auth(challenge: String): String {
            val nonce = (MirrorProtocol.decode(challenge) as Frame.Challenge).nonce
            return MirrorProtocol.encode(Frame.Auth("live", publicKey, sign(MirrorProtocol.authText("live", nonce).toByteArray())))
        }
    }

    private inner class Rig(tier: Tier = Tier.FREE) {
        val start = 1_800_000_000_000L
        var now = start
        val dir = tmp.newFolder()
        val store = FileStreamStore(dir)
        val plans = Plans(dir, tier, DemoPayment()) { now }
        val service = MirrorService(store, ServerKey(dir), clock = { now }, plans = plans)
        val car = Car()
        val chain = FileEventStore(tmp.newFolder())
        var wall = start

        /** One record an hour for [hours] hours. */
        fun drive(hours: Int) = repeat(hours) {
            wall += 3_600_000
            chain.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(10f + it % 3), Timestamp(wall * 1_000_000, wall))))
        }

        fun connect(): Pair<MirrorService.Session, List<Frame?>> {
            val s = service.session()
            return s to s.onText(car.auth(s.greeting()))!!.map { MirrorProtocol.decode(it) }
        }

        fun seal(id: String, trigger: CapsuleTrigger, from: Long, to: Long, at: Long) =
            Frame.Seal(SealStore.line(CapsuleSeal.create(IncidentCapsule(id, trigger, from, to), chain.hashAt(to), true, at, car)))
    }

    private fun MirrorService.Session.send(f: Frame) = onText(MirrorProtocol.encode(f))!!.map { MirrorProtocol.decode(it) }

    @Test
    fun oldDrivingGoesAfterThePlansWindowButKeptCapsulesStay() {
        val r = Rig(Tier.FREE)
        r.drive(240) // ten days, one record an hour
        val (s, _) = r.connect()
        s.send(Frame.Batch(EscalationLevel.NORMAL, r.chain.records(0, 239)))
        s.send(r.seal("moment", CapsuleTrigger.EVASIVE_STEER, 20, 25, r.start + 21 * 3_600_000))
        s.send(r.seal("airbag", CapsuleTrigger.AIRBAG, 30, 31, r.start + 31 * 3_600_000))
        r.now = r.wall + 60_000

        val removed = r.service.retain(s.stream!!)
        // Free keeps seven days: the first three days go, except the two capsules.
        assertEquals(72 - 6 - 2, removed)
        assertEquals(239L, r.store.contiguousHead(s.stream!!)) // removed on purpose still counts as accounted for
        assertEquals(6, r.store.records(s.stream!!, 20, 25).size)
        assertEquals(2, r.store.records(s.stream!!, 30, 31).size)
        // The car carries on as before.
        r.drive(1)
        assertEquals(listOf(Frame.Ack(240)), s.send(Frame.Batch(EscalationLevel.NORMAL, r.chain.records(240, 240))))

        // Past thirty days the moment expires; the airbag crash stays (ten years).
        r.now = r.start + 60 * DAY_MS
        r.service.retain(s.stream!!)
        assertTrue(r.store.records(s.stream!!, 20, 25).isEmpty())
        assertEquals(2, r.store.records(s.stream!!, 30, 31).size)
    }

    @Test
    fun theServersReadableCopiesGoWithTheRecordsTheyBelongTo() {
        val r = Rig(Tier.FREE)
        r.drive(240) // ten days, one record an hour
        val (s, _) = r.connect()
        s.send(Frame.Batch(EscalationLevel.NORMAL, r.chain.records(0, 239)))
        // Danger moments on the first day (10..12), inside a moment that is kept (22) and yesterday (230): each
        // record came with a copy this server can read.
        for (seq in listOf(10L, 11L, 12L, 22L, 230L)) {
            val plain = EntryCodec.encode(r.chain.read(seq, seq).single().entry)
            s.send(Frame.Event(EscalationLevel.ALERT, r.chain.records(seq, seq).single(), SealedBox.seal(r.service.serverPublicKey(), plain.toByteArray(), SealedBox.INFO_SERVER)))
        }
        s.send(r.seal("moment", CapsuleTrigger.EVASIVE_STEER, 20, 25, r.start + 21 * 3_600_000))
        assertEquals(listOf(10L, 11L, 12L, 22L, 230L), r.service.signalCopies(s.stream!!, 0, 239).map { it.first })
        r.now = r.wall + 60_000

        r.service.retain(s.stream!!)
        // Free keeps seven days: the first day's copies go with its records; the kept moment's and the recent one stay.
        assertEquals(listOf(22L, 230L), r.service.signalCopies(s.stream!!, 0, 239).map { it.first })
        // Past thirty days the moment expires, and its copy with it.
        r.now = r.start + 60 * DAY_MS
        r.service.retain(s.stream!!)
        assertTrue(r.service.signalCopies(s.stream!!, 0, 239).isEmpty())
    }

    @Test
    fun theCarsOwnGapIsNotedAndAcknowledgementCarriesOn() {
        val r = Rig()
        r.drive(30)
        val (s, _) = r.connect()
        s.send(Frame.Batch(EscalationLevel.NORMAL, r.chain.records(0, 9)))
        assertEquals(listOf(Frame.Ack(19)), s.send(Frame.Checkpoint(19, r.chain.records(19, 19).single().split("\t")[3])))
        assertEquals(listOf(Frame.Ack(29)), s.send(Frame.Batch(EscalationLevel.NORMAL, r.chain.records(20, 29))))
        assertEquals("car", r.store.removed(s.stream!!).single().third)
    }

    @Test
    fun theCarIsToldItsPlanAndEachExtensionUntilItAcknowledges() {
        val r = Rig(Tier.PRO)
        r.drive(5)
        val (s, hello) = r.connect()
        val plan = hello.filterIsInstance<Frame.Plan>().single()
        assertEquals("PRO", plan.tier)
        s.send(Frame.LibraryAck(plan.commandId))
        val (latest, again) = r.connect()
        assertTrue(again.none { it is Frame.Plan }) // acknowledged: not told again

        val device = s.stream!!.deviceId
        r.plans.setTier("owner-1", Tier.FREE, listOf(device), "admin")
        r.service.libraryChanged(device) // the car's current connection hears it at once
        assertEquals("FREE", (MirrorProtocol.decode(latest.pushes.tryReceive().getOrThrow()) as Frame.Plan).tier)

        s.send(Frame.Batch(EscalationLevel.NORMAL, r.chain.records(0, 4)))
        s.send(r.seal("m", CapsuleTrigger.LURCH, 1, 3, r.start + 3_600_000))
        val c = r.service.capsules(s.stream!!).single()
        val until = r.plans.extend("owner-1", device, "m", c.eventMs, Extension.ONE_YEAR).getOrThrow()
        assertEquals(c.eventMs + 30 * DAY_MS + YEAR_MS, until)
        assertTrue(r.plans.pending(device).any { (MirrorProtocol.decode(it) as? Frame.Extend)?.untilWall == until })
        assertEquals(LibraryStatus.EXTENDED, r.service.library(s.stream!!, c).status(r.start + 100 * DAY_MS))
        assertEquals(until, r.service.clipUntil(s.stream!!, c))
    }

    @Test
    fun aSmallerPlanKeepsTheLongerWindowForSevenDays() {
        val r = Rig(Tier.PRO)
        r.plans.setTier("o", Tier.FREE, emptyList(), "admin")
        r.now += 3 * DAY_MS
        assertEquals(30, r.plans.historyDays("o"))
        r.now += 5 * DAY_MS
        assertEquals(7, r.plans.historyDays("o"))
        assertEquals(30, r.plans.historyDays("someone-without-a-plan").let { if (it == 30) 30 else it }) // the default, Pro
    }
}
