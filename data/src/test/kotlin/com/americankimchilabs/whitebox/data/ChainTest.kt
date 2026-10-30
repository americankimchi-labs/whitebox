package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.random.Random

class ChainTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun codecRoundTripsEveryEntryShape() {
        for (e in everyEntryShape()) assertEquals(e, EntryCodec.decode(EntryCodec.encode(e)))
    }

    @Test
    fun separatorsInFreeTextAreNeutralised() {
        val e = com.americankimchilabs.whitebox.domain.ChainEntry.Signal(
            com.americankimchilabs.whitebox.domain.SignalSample(
                com.americankimchilabs.whitebox.domain.VehicleSignal.AirbagDeployed("a|b;c\td", true), ts(1),
            ),
        )
        val decoded = EntryCodec.decode(EntryCodec.encode(e)) as com.americankimchilabs.whitebox.domain.ChainEntry.Signal
        assertEquals("a_b_c d", (decoded.sample.signal as com.americankimchilabs.whitebox.domain.VehicleSignal.AirbagDeployed).seat)
    }

    @Test
    fun separatorsInContextLabelsAreNeutralised() {
        val dirty = "x|y;z\tw"
        val signals = listOf(
            com.americankimchilabs.whitebox.domain.VehicleSignal.Door(dirty, true),
            com.americankimchilabs.whitebox.domain.VehicleSignal.Wiper(dirty, dirty),
            com.americankimchilabs.whitebox.domain.VehicleSignal.RoadContext(com.americankimchilabs.whitebox.domain.RoadContextType.CAUTION_POINT, dirty),
        )
        for (s in signals) {
            val encoded = EntryCodec.encode(com.americankimchilabs.whitebox.domain.ChainEntry.Signal(com.americankimchilabs.whitebox.domain.SignalSample(s, ts(1))))
            val decoded = (EntryCodec.decode(encoded) as com.americankimchilabs.whitebox.domain.ChainEntry.Signal).sample.signal
            assertEquals(s.kind, decoded.kind)
            assertEquals(false, encoded.contains('\t'))
            assertEquals(true, decoded.toString().contains("x_y_z w"))
        }
    }

    @Test
    fun appendIsDurableAcrossInstances() {
        val dir = tmp.newFolder()
        val a = FileEventStore(dir)
        everyEntryShape().forEach(a::append)
        val head = a.head()!!
        a.close()

        val b = FileEventStore(dir) // a new process opening the same files
        assertEquals(head.lastSeq, b.head()!!.lastSeq)
        assertArrayEquals(head.headHash, b.head()!!.headHash)
        assertEquals(everyEntryShape(), b.read(0, head.lastSeq).map { it.entry })
        assertTrue(b.verify(0, head.lastSeq) is ChainCheck.Intact)
    }

    @Test
    fun processDeathMidWriteLosesOnlyTheTornRecord() {
        val dir = tmp.newFolder()
        val r = Random(1)
        val a = FileEventStore(dir)
        repeat(20) { a.append(randomEntry(r, it * 100L)) }
        val before = a.head()!!
        a.close()
        // The process dies halfway through writing record #20: half its bytes reach the file, no newline.
        val torn = "20\t${before.headHash.toHex()}\t${EntryCodec.encode(randomEntry(r, 2_000))}\tdeadbeef\n".toByteArray()
        FileOutputStream(File(dir, "chain.log"), true).use { it.write(torn, 0, torn.size / 2) }

        val b = FileEventStore(dir)
        assertEquals(19L, b.head()!!.lastSeq)
        assertArrayEquals(before.headHash, b.head()!!.headHash)
        assertEquals(20L, b.append(randomEntry(r, 2_100)).seq) // continues cleanly
        assertTrue(b.verify(0, 20) is ChainCheck.Intact)
    }

    @Test
    fun failedWriteInARunningProcessLeavesNoHalfRecord() {
        val dir = tmp.newFolder()
        var failNext = false
        val store = FileEventStore(dir) { out, bytes ->
            if (failNext) {
                out.write(bytes, 0, bytes.size / 2)
                throw IOException("disk yanked")
            }
            out.write(bytes)
        }
        val r = Random(2)
        repeat(5) { store.append(randomEntry(r, it * 100L)) }
        failNext = true
        try {
            store.append(randomEntry(r, 600))
            fail("expected IOException")
        } catch (_: IOException) {
        }
        failNext = false
        assertEquals(5L, store.append(randomEntry(r, 700)).seq)
        assertTrue(store.verify(0, 5) is ChainCheck.Intact)
        store.close()
        assertTrue(FileEventStore(dir).verify(0, 5) is ChainCheck.Intact)
    }

    @Test
    fun emptyStoreHasNoHead() {
        assertNull(FileEventStore(tmp.newFolder()).head())
    }

    /**
     * Property: any modification of any record is detected. Edits that don't re-hash break the chain at (or right
     * after) the edited record; an attacker who re-hashes everything after the edit gets a self-consistent chain,
     * which the signed seal still rejects.
     */
    @Test
    fun anyModifiedRecordIsDetected() {
        val signer = JdkSigner()
        repeat(300) { round ->
            val r = Random(round)
            val dir = tmp.newFolder()
            val store = FileEventStore(dir)
            val n = r.nextInt(5, 60)
            repeat(n) { store.append(randomEntry(r, it * 100L)) }
            val capsule = IncidentCapsule("C$round", CapsuleTrigger.DELTA_V, 0, n - 1L)
            val seal = CapsuleSeal.create(capsule, store.hashAt(n - 1L), true, 0, signer)
            store.close()

            val file = File(dir, "chain.log")
            val lines = file.readLines().toMutableList()
            val target = r.nextInt(n)
            val kind = round % 6
            when (kind) {
                0 -> lines[target] = mutateChar(lines[target], r, field = 2) // payload byte
                1 -> lines[target] = lines[target].replaceFirst(Regex("^\\d+"), "${target + 1}") // seq
                2 -> lines[target] = mutateChar(lines[target], r, field = 1) // prev link
                3 -> if (n > 1) lines.removeAt(target) else lines[target] = mutateChar(lines[target], r, field = 2) // deletion
                4 -> { // swap with a neighbour
                    val other = if (target + 1 < n) target + 1 else target - 1
                    if (other >= 0) lines[target] = lines[other].also { lines[other] = lines[target] }
                    else lines[target] = mutateChar(lines[target], r, field = 2)
                }
                5 -> rehashFrom(lines, target, r) // edit + recompute every later hash
            }
            file.writeText(lines.joinToString("\n", postfix = "\n"))

            val reopened = FileEventStore(dir)
            assertTrue("round $round kind $kind target $target not detected", verifyCapsule(seal, reopened) is CapsuleCheck.Failed)
            // Without re-hashing, the chain itself is broken — except dropping the last record, which only the seal sees.
            val last = reopened.head()?.lastSeq ?: -1
            val tailDrop = kind == 3 && target == n - 1 && n > 1
            if (kind != 5 && !tailDrop && last >= 0) assertTrue("round $round kind $kind", reopened.verify(0, last) is ChainCheck.Broken)
        }
    }

    private fun mutateChar(line: String, r: Random, field: Int): String {
        val f = line.split("\t").toMutableList()
        val chars = f[field].toCharArray()
        val i = r.nextInt(chars.size)
        chars[i] = if (chars[i] == '1') '2' else '1'
        f[field] = String(chars)
        return f.joinToString("\t")
    }

    private fun rehashFrom(lines: MutableList<String>, target: Int, r: Random) {
        lines[target] = mutateChar(lines[target], r, field = 2)
        var prev = if (target == 0) FileEventStore.ZERO else lines[target - 1].split("\t")[3]
        for (i in target until lines.size) {
            val f = lines[i].split("\t")
            val body = "${f[0]}\t$prev\t${f[2]}"
            prev = sha256Hex(body)
            lines[i] = "$body\t$prev"
        }
    }
}
