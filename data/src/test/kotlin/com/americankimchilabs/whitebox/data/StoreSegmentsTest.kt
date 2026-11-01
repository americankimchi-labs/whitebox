package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.SignalSample
import com.americankimchilabs.whitebox.domain.Timestamp
import com.americankimchilabs.whitebox.domain.VehicleSignal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The chain in closed, compressed files: nothing changes for readers, and removing old files keeps what must stay. */
class StoreSegmentsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun entry(i: Int) = ChainEntry.Signal(SignalSample(VehicleSignal.Speed(i.toFloat()), Timestamp(i * 100_000_000L, 1_800_000_000_000 + i * 100L)))

    private fun FileEventStore.fill(n: Int, from: Int = 0) = repeat(n) { append(entry(from + it)) }

    @Test
    fun closedFilesReadVerifyAndReopenLikeOneChain() {
        val dir = tmp.newFolder()
        val store = FileEventStore(dir, segmentRecords = 100)
        store.fill(350)
        assertEquals(3, dir.listFiles()!!.count { it.name.startsWith("seg-") })
        assertEquals(349L, store.head()!!.lastSeq)
        assertTrue(store.verify(0, 349) is ChainCheck.Intact)
        assertEquals((95..105).map { it.toLong() }, store.read(95, 105).map { it.seq }) // across a file boundary
        val head = store.head()!!.headHash
        store.close()

        val reopened = FileEventStore(dir, segmentRecords = 100)
        assertArrayEquals(head, reopened.head()!!.headHash)
        reopened.fill(10, 350)
        assertTrue(reopened.verify(0, 359) is ChainCheck.Intact)
    }

    @Test
    fun aCloseInterruptedBeforeTheOpenFileWasEmptiedIsFinishedOnOpen() {
        val dir = tmp.newFolder()
        val store = FileEventStore(dir, segmentRecords = 100)
        store.fill(100)
        val before = File(dir, "chain.log").readBytes()
        store.fill(1) // closes the first 100 into a compressed file
        store.close()
        // As if the process died after the compressed file was written but before chain.log was emptied.
        File(dir, "chain.log").writeBytes(before)

        val reopened = FileEventStore(dir, segmentRecords = 100)
        assertEquals(99L, reopened.head()!!.lastSeq)
        reopened.fill(5, 100)
        assertTrue(reopened.verify(0, 104) is ChainCheck.Intact)
    }

    @Test
    fun removingOldFilesKeepsCapsulesVerifiableAndRecordsTheCheckpoint() {
        val dir = tmp.newFolder()
        val store = FileEventStore(dir, segmentRecords = 100)
        store.fill(450)
        val capsuleHash = store.hashAt(160)
        val sizeBefore = store.sizeOnDisk()

        val cp = store.prune(throughSeq = 299, keep = listOf(120L..160L))!!
        assertEquals(299L, cp.seq)
        assertTrue(store.sizeOnDisk() < sizeBefore)
        assertEquals(300L, store.oldestSeq())
        assertTrue(store.read(0, 119).isEmpty())
        // The kept capsule still reads and verifies, ending in the hash its seal signed.
        val check = store.verify(120, 160)
        assertTrue(check is ChainCheck.Intact)
        assertArrayEquals(capsuleHash, (check as ChainCheck.Intact).headHash)
        // The remaining chain links to the checkpoint.
        assertTrue(store.verify(300, 449) is ChainCheck.Intact)
        assertTrue(store.verify(100, 200) is ChainCheck.Broken)
        store.close()

        // Reopened: same answers. Then the capsule leaves the library and its records go too.
        val reopened = FileEventStore(dir, segmentRecords = 100)
        assertTrue(reopened.verify(120, 160) is ChainCheck.Intact)
        reopened.prune(throughSeq = 299, keep = emptyList())
        assertTrue(reopened.read(120, 160).isEmpty())
        assertEquals(0, dir.listFiles()!!.count { it.name.startsWith("kept-") })
    }

    @Test
    fun aLongChainFromBeforeClosedFilesIsSplitOnItsFirstNewRecordOnly() {
        val dir = tmp.newFolder()
        FileEventStore(dir, segmentRecords = 1_000_000).apply { fill(250); close() }
        val original = File(dir, "chain.log").readBytes()

        // Opening to read (the verification tool on a sample chain) changes nothing.
        val readOnly = FileEventStore(dir, segmentRecords = 100)
        assertTrue(readOnly.verify(0, 249) is ChainCheck.Intact)
        readOnly.close()
        assertArrayEquals(original, File(dir, "chain.log").readBytes())

        val store = FileEventStore(dir, segmentRecords = 100)
        store.fill(1, 250)
        assertEquals(2, dir.listFiles()!!.count { it.name.startsWith("seg-") })
        assertTrue(store.verify(0, 250) is ChainCheck.Intact)
        assertEquals(250L, store.head()!!.lastSeq)
    }
}
