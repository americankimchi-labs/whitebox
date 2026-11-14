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

    /** A crash whose car went silent: no seal from the car, the server's seal over what it received. */
    private fun serverSealedFile(): String {
        var wall = 1_700_000_000_000L
        val store = FileEventStore(tmp.newFolder())
        repeat(30) { wall += 1_000; store.append(ChainEntry.Signal(SignalSample(VehicleSignal.Speed(20f - it / 2f), Timestamp(wall * 1_000, wall)))) }
        val server = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        fun signed(text: String) = "$text|sig=" + Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run { initSign(server.private); update(text.toByteArray()); sign() })
        return (
            listOf(
                "WHITEBOX-EVIDENCE|1", "CAPSULE|server-$wall|SERVER_SEAL|10|25|car1|live",
                "ATTESTATION|" + signed("ATTEST|car1/live|10|25|${store.hashAt(25).toHex()}|25|0|DEADMAN|$wall"),
                "ANCHOR|" + signed("ANCHOR|car1/live|25|${store.hashAt(25).toHex()}|$wall"),
                "SERVERKEY|" + Base64.getEncoder().encodeToString(server.public.encoded),
            ) + store.records(9, 25).map { "RECORD|$it" }
            ).joinToString("\n")
    }

    @Test
    fun aRecordOnlyTheServerSealedChecksAgainstTheServersKey() {
        val text = serverSealedFile()
        val r = EvidenceFile.verify(text)
        assertTrue(r.ok)
        assertEquals(0 to 0, r.seals to r.sealsValid)
        assertEquals(1 to 1, r.serverSeals to r.serverSealsValid)
        assertEquals(16, r.recordsChecked)
        // A server seal moved to another record, or one whose signature does not match, is caught.
        val seal = text.lines().first { it.startsWith("ATTESTATION|") }
        assertFalse(EvidenceFile.verify(text.replace(seal, seal.replace("|10|25|", "|10|24|"))).ok)
        assertFalse(EvidenceFile.verify(text.replace(seal, seal.replace("|DEADMAN|", "|OTHER|"))).ok)
        // So is a changed record under it.
        val record = text.lines().first { it.startsWith("RECORD|15\t") }
        assertFalse(EvidenceFile.verify(text.replace(record, record.replaceRange(record.length - 3, record.length - 2, if (record[record.length - 3] == 'a') "b" else "a"))).ok)
        // With neither a car seal nor a server seal nothing vouches for the file.
        assertFalse(EvidenceFile.verify(text.lines().filterNot { it.startsWith("ATTESTATION|") }.joinToString("\n")).ok)
    }
}
