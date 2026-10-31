package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.LocationFix
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.VehicleSignal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec

class CryptoTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val cipher = AesGcmRecordCipher(key)

    @Test
    fun everyEntryShapeRoundTripsEncryptedAndOnlyValuesAreHidden() {
        for (e in everyEntryShape()) {
            val text = EntryCodec.encode(e, cipher)
            assertEquals(e, EntryCodec.decode(text, cipher))
            assertEquals(e.at, EntryCodec.timestamp(text)) // readable without the key
            val personal = e is ChainEntry.Signal || e is ChainEntry.Location
            assertEquals(text, personal, EntryCodec.isSealed(text))
            if (personal) assertTrue(runCatching { EntryCodec.decode(text) }.exceptionOrNull() is LockedRecordException)
            else assertEquals(e, EntryCodec.decode(text)) // markers stay plain for the server
        }
        val fix = ChainEntry.Location(LocationFix(37.5665, 126.978, 5f, 1_700_000_000_000, ts(9)))
        val text = EntryCodec.encode(fix, cipher)
        assertFalse("37.5665" in text || "126.978" in text)
    }

    @Test
    fun aValueCannotBeAlteredOrMovedToAnotherRecord() {
        val a = EntryCodec.encode(ChainEntry.Signal(SignalSample(VehicleSignal.BrakePedal(true), ts(1))), cipher)
        val b = EntryCodec.encode(ChainEntry.Signal(SignalSample(VehicleSignal.BrakePedal(false), ts(2))), cipher)
        val moved = b.split("|").toMutableList().also { it[2] = a.split("|")[2] }.joinToString("|")
        assertTrue(runCatching { EntryCodec.decode(moved, cipher) }.isFailure) // bound to its own time
        val flipped = a.split("|").toMutableList().also { f -> f[2] = f[2].dropLast(4) + (if (f[2].endsWith("AAAA")) "BBBB" else "AAAA") }.joinToString("|")
        assertTrue(runCatching { EntryCodec.decode(flipped, cipher) }.isFailure)
        assertTrue(runCatching { EntryCodec.decode(a, AesGcmRecordCipher(ByteArray(32))) }.isFailure) // wrong key
    }

    @Test
    fun anEncryptedChainVerifiesWithoutTheKeyAndReadsWithIt() {
        val dir = tmp.newFolder()
        val store = FileEventStore(dir, cipher = cipher)
        val entries = everyEntryShape()
        entries.forEach { store.append(it) }
        assertEquals(entries, store.read(0, entries.size - 1L).map { it.entry })
        store.close()
        val noKey = FileEventStore(dir)
        assertTrue(noKey.verify(0, entries.size - 1L) is ChainCheck.Intact) // the mirror's view: checkable, not readable
        assertTrue(runCatching { noKey.read(0, 0) }.exceptionOrNull() is LockedRecordException)
        assertFalse(dir.resolve("chain.log").readText().contains("ROW_1_LEFT;true")) // encrypted at rest
    }

    @Test
    fun aSealedBoxOpensOnlyForItsRecipient() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val owner = gen.generateKeyPair()
        val sealed = SealedBox.seal(owner.public.encoded, key, SealedBox.INFO_WRAP)
        assertArrayEquals(key, SealedBox.open(owner.private, sealed, SealedBox.INFO_WRAP))
        assertTrue(runCatching { SealedBox.open(gen.generateKeyPair().private, sealed, SealedBox.INFO_WRAP) }.isFailure)
        assertTrue(runCatching { SealedBox.open(owner.private, sealed, SealedBox.INFO_SERVER) }.isFailure) // purpose-bound
    }

    @Test
    fun hkdfMatchesRfc5869TestCase3() {
        val okm = SealedBox.hkdf(ByteArray(22) { 0x0b }, ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", okm.toHex())
    }

    @Test
    fun anEpochCipherReadsEveryEpochItHoldsAndWritesTheNewest() {
        val ring = MemoryKeyRing()
        val cipher = EpochRecordCipher(ring)
        val old = EntryCodec.encode(ChainEntry.Signal(SignalSample(VehicleSignal.BrakePedal(true), ts(1))), cipher)
        assertTrue(old.split("|")[2].startsWith("v2.0."))
        ring.rotate("o2")
        val new = EntryCodec.encode(ChainEntry.Signal(SignalSample(VehicleSignal.BrakePedal(false), ts(2))), cipher)
        assertTrue(new.split("|")[2].startsWith("v2.1."))
        assertEquals(VehicleSignal.BrakePedal(true), (EntryCodec.decode(old, cipher) as ChainEntry.Signal).sample.signal)
        assertEquals(VehicleSignal.BrakePedal(false), (EntryCodec.decode(new, cipher) as ChainEntry.Signal).sample.signal)
        // Someone holding only the new epoch (a new owner) cannot read the old record.
        val onlyNew = object : KeyRing by ring {
            override fun key(epoch: Int) = if (epoch == 1) ring.key(1) else null
        }
        assertTrue(runCatching { EntryCodec.decode(old, EpochRecordCipher(onlyNew)) }.exceptionOrNull() is LockedRecordException)
        // Records written by the single-key cipher of earlier versions are epoch 0.
        val legacy = EntryCodec.encode(ChainEntry.Signal(SignalSample(VehicleSignal.BrakePedal(true), ts(3))), AesGcmRecordCipher(ring.key(0)!!))
        assertEquals(VehicleSignal.BrakePedal(true), (EntryCodec.decode(legacy, cipher) as ChainEntry.Signal).sample.signal)
    }
}

