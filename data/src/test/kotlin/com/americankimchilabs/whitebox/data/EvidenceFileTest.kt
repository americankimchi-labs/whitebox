package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.VehicleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** The evidence file a phone downloads checks without White Box, and stops checking if anything in it changes. */
class EvidenceFileTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(): String {
        val ring = MemoryKeyRing()
        var wall = 1_700_000_000_000L
        val store = FileEventStore(tmp.newFolder(), cipher = EpochRecordCipher(ring) { wall })
        repeat(30) { wall += 1_000; store.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(10f + it), Timestamp(wall * 1_000, wall)))) }
        val seal = CapsuleSeal.create(IncidentCapsule("C1", CapsuleTrigger.HARD_BRAKE, 10, 25), store.hashAt(25), true, wall, JdkSigner())
        val server = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val anchorText = "ANCHOR|car1/live|25|${store.hashAt(25).toHex()}|$wall"
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(server.private); update(anchorText.toByteArray()); sign() }
        val records = store.records(9, 25)
        val minutes = records.mapNotNull { MirrorProtocol.Record.parse(it)?.entry }.flatMap { it.split("|") }
            .filter { it.startsWith(RecordCipher.PREFIX_V3) }.map { RecordCipher.epochOf(it) to RecordCipher.minuteOf(it)!! }.distinct()
        val b64 = Base64.getEncoder()
        return (
            listOf("WHITEBOX-EVIDENCE|1", "CAPSULE|C1|HARD_BRAKE|10|25|car1|live", "SEAL|" + SealStore.line(seal), "ANCHOR|$anchorText|sig=${b64.encodeToString(sig)}", "SERVERKEY|" + b64.encodeToString(server.public.encoded)) +
                minutes.map { (e, m) -> "KEY|$e|$m|" + b64.encodeToString(RecordCipher.minuteKey(ring.key(e)!!, m)) } +
                records.map { "RECORD|$it" }
            ).joinToString("\n")
    }

    @Test
    fun anUntouchedFileChecksAndOpens() {
        val r = EvidenceFile.verify(file())
        assertTrue(r.ok)
        assertEquals(16, r.recordsChecked)
        assertEquals(1 to 1, r.sealsValid to r.anchorsValid)
        assertEquals(16, r.opened)
        assertEquals(10f + 10, (r.entries.first().second as ChainEntry.Signal).sample.signal.let { (it as VehicleSignal.Speed).metersPerSecond }, 0.001f)
    }

    @Test
    fun aChangedRecordOrAForgedAnchorIsCaught() {
        val text = file()
        val record = text.lines().first { it.startsWith("RECORD|15\t") }
        val edited = record.replaceRange(record.length - 3, record.length - 2, if (record[record.length - 3] == 'a') "b" else "a") // one hash digit
        assertFalse(EvidenceFile.verify(text.replace(record, edited)).ok)
        val anchor = text.lines().first { it.startsWith("ANCHOR|") }
        assertFalse(EvidenceFile.verify(text.replace(anchor, anchor.replace("|25|", "|24|"))).ok)
    }
}
