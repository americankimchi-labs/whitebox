package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.MirrorProtocol.Record
import java.io.File
import java.util.TreeMap

/** What one put did. */
data class PutResult(
    val stored: Int,
    /** Records that contradict what is already held (another record at a held seq, or a broken link): refused. */
    val conflicts: List<String>,
)

/**
 * Durable storage for mirrored chains. Writes are idempotent by (device, seq): a record already held is skipped,
 * so resending after a reconnect is always safe. The first record held for a seq wins; a contradicting one is
 * refused and reported (a forked or rewritten chain is evidence in itself).
 */
interface StreamStore {
    fun put(stream: StreamId, records: List<Record>, receivedAtMs: Long): PutResult

    /**
     * Highest seq such that every record from the stream's start up to it is held or accounted for (removed on
     * purpose, or declared lost by the car); -1 if none.
     */
    fun contiguousHead(stream: StreamId): Long

    /**
     * Removes held records at or before [throughSeq] that lie outside [keep] (the retention window has passed them);
     * they stay accounted for. Returns how many were removed.
     */
    fun remove(stream: StreamId, throughSeq: Long, keep: List<LongRange>, reason: String, atMs: Long): Int

    /** Records [fromSeq]..[toSeq] will never arrive (the car removed them before they were sent): accounted for. */
    fun declareGap(stream: StreamId, fromSeq: Long, toSeq: Long, reason: String, atMs: Long)

    /** Ranges accounted for without being held: (from, to, reason). */
    fun removed(stream: StreamId): List<Triple<Long, Long, String>>

    fun highestSeq(stream: StreamId): Long

    fun count(stream: StreamId): Int

    fun hashAt(stream: StreamId, seq: Long): String?

    /** Records held in the inclusive range, in seq order. */
    fun records(stream: StreamId, fromSeq: Long, toSeq: Long): List<String>

    /**
     * Appends to one of the stream's logs (device seals, anchors, attestations, notices). With [unique], a line
     * already present is not appended again and false is returned (seals are resent until acknowledged).
     */
    fun appendLog(stream: StreamId, log: String, line: String, unique: Boolean = false): Boolean

    fun log(stream: StreamId, log: String): List<String>

    fun streams(): List<StreamId>

    /** A pseudonymous capsule window (already encrypted at rest); not tied to any stream. */
    fun putLabeled(pseudonym: String, stored: String)

    fun labeledCount(): Int

    /** The driver withdrew consent: their window is deleted (true also if it was already gone). */
    fun deleteLabeled(pseudonym: String): Boolean

    fun conflicts(stream: StreamId): Int
}

/** [StreamStore] on the local disk (the layout under [root] is `streams/<device>/<stream>/<log>.log`). */
class FileStreamStore(root: File) : StreamStore by DurableStreamStore(FileDurable(root))

/**
 * [StreamStore] over a [Durable]: per stream an append-only `records` log (each record once, in arrival order, one
 * append per received batch) and small logs beside it; an in-memory index of seq → record, rebuilt on start (arrival
 * times stay in the log). One process at a time (Cloud Run runs a single instance, so the dead-man timers agree).
 */
class DurableStreamStore(private val durable: Durable) : StreamStore {

    private class Index {
        val removed = TreeMap<Long, Long>()
        val hashes = TreeMap<Long, String>()
        val lines = HashMap<Long, String>()
        var conflicts = 0
        var contiguousFrom = -1L
        var contiguous = -1L
    }

    private val indexes = HashMap<StreamId, Index>()

    private fun path(stream: StreamId, log: String) = "streams/${stream.deviceId}/${stream.stream}/$log"

    init {
        streams().forEach(::load)
    }

    /** A stream only exists once a car wrote to it: reads never create one. */
    private fun existing(stream: StreamId): Index? = indexes[stream] ?: if (durable.exists(path(stream, "records"))) load(stream) else null

    private fun load(stream: StreamId): Index = indexes.getOrPut(stream) {
        Index().also { idx ->
            // records lines: receivedAtMs \t record (the record itself contains tabs)
            durable.lines(path(stream, "records")).forEach { l ->
                Record.parse(l.substringAfter('\t'))?.let { r ->
                    idx.hashes[r.seq] = r.hash
                    idx.lines[r.seq] = r.line
                }
            }
            durable.lines(path(stream, "removed")).forEach { l ->
                val f = l.split("|")
                if (f.size >= 2) idx.removed.merge(f[0].toLong(), f[1].toLong(), ::maxOf)
            }
            idx.conflicts = durable.lines(path(stream, "conflicts")).size
        }
    }

    @Synchronized
    override fun put(stream: StreamId, records: List<Record>, receivedAtMs: Long): PutResult {
        val idx = load(stream)
        var stored = 0
        val conflicts = mutableListOf<String>()
        val lines = mutableListOf<String>()
        for (r in records.sortedBy { it.seq }) {
            val held = idx.hashes[r.seq]
            when {
                held == r.hash -> continue
                held != null -> conflicts += "#${r.seq}: a different record is already held"
                idx.hashes[r.seq - 1]?.let { it != r.prevHash } == true -> conflicts += "#${r.seq}: does not link to #${r.seq - 1}"
                idx.lines[r.seq + 1]?.let { Record.parse(it)?.prevHash != r.hash } == true -> conflicts += "#${r.seq}: #${r.seq + 1} does not link to it"
                else -> {
                    idx.hashes[r.seq] = r.hash
                    idx.lines[r.seq] = r.line
                    lines += "$receivedAtMs\t${r.line}"
                    stored++
                }
            }
        }
        durable.append(path(stream, "records"), lines)
        if (conflicts.isNotEmpty()) {
            idx.conflicts += conflicts.size
            durable.append(path(stream, "conflicts"), conflicts)
        }
        return PutResult(stored, conflicts)
    }

    @Synchronized
    override fun contiguousHead(stream: StreamId): Long {
        val idx = existing(stream) ?: return -1
        val first = listOfNotNull(idx.hashes.firstKeyOrNull(), idx.removed.firstKeyOrNull()).minOrNull() ?: return -1
        var seq = if (idx.contiguousFrom == first) idx.contiguous else first - 1
        while (true) {
            val next = seq + 1
            if (idx.hashes.containsKey(next)) { seq = next; continue }
            val r = idx.removed.floorEntry(next)
            if (r != null && r.value >= next) { seq = r.value; continue }
            break
        }
        idx.contiguousFrom = first
        idx.contiguous = seq
        return seq
    }

    @Synchronized
    override fun remove(stream: StreamId, throughSeq: Long, keep: List<LongRange>, reason: String, atMs: Long): Int {
        val idx = existing(stream) ?: return 0
        val first = idx.hashes.firstKeyOrNull() ?: return 0
        if (first > throughSeq) return 0
        val gone = idx.hashes.headMap(throughSeq, true).keys.filter { seq -> keep.none { seq in it } }
        if (gone.isEmpty()) return 0
        gone.forEach { idx.hashes.remove(it); idx.lines.remove(it) }
        // The log without them, replaced whole (a crash leaves the old or the new one).
        val kept = durable.lines(path(stream, "records")).filter { l -> Record.parse(l.substringAfter('\t'))?.let { idx.hashes[it.seq] == it.hash } == true }
        durable.replace(path(stream, "records"), kept)
        declareGap(stream, first, throughSeq, reason, atMs)
        return gone.size
    }

    @Synchronized
    override fun declareGap(stream: StreamId, fromSeq: Long, toSeq: Long, reason: String, atMs: Long) {
        val idx = load(stream)
        if (toSeq < fromSeq) return
        idx.removed.merge(fromSeq, toSeq, ::maxOf)
        idx.contiguousFrom = -1
        appendLog(stream, "removed", "$fromSeq|$toSeq|$reason|$atMs")
    }

    @Synchronized
    override fun removed(stream: StreamId): List<Triple<Long, Long, String>> =
        log(stream, "removed").mapNotNull { l -> l.split("|").takeIf { it.size >= 3 }?.let { Triple(it[0].toLong(), it[1].toLong(), it[2]) } }

    @Synchronized
    override fun highestSeq(stream: StreamId) = existing(stream)?.hashes?.lastKeyOrNull() ?: -1

    @Synchronized
    override fun count(stream: StreamId) = existing(stream)?.hashes?.size ?: 0

    @Synchronized
    override fun hashAt(stream: StreamId, seq: Long) = existing(stream)?.hashes?.get(seq)

    @Synchronized
    override fun records(stream: StreamId, fromSeq: Long, toSeq: Long): List<String> {
        val idx = existing(stream) ?: return emptyList()
        return idx.hashes.subMap(fromSeq, true, toSeq, true).keys.mapNotNull { idx.lines[it] }
    }

    @Synchronized
    override fun appendLog(stream: StreamId, log: String, line: String, unique: Boolean): Boolean {
        if (unique && durable.lines(path(stream, log)).contains(line)) return false
        durable.append(path(stream, log), line)
        return true
    }

    @Synchronized
    override fun log(stream: StreamId, log: String): List<String> = durable.lines(path(stream, log))

    @Synchronized
    override fun streams(): List<StreamId> = (
        indexes.keys + durable.logs("streams/").mapNotNull { name ->
            name.replace('\\', '/').split("/").takeIf { it.size >= 4 }?.let { StreamId(it[1], it[2]) }
        }
        ).distinct().sortedBy { it.toString() }

    @Synchronized
    override fun putLabeled(pseudonym: String, stored: String) {
        if (!pseudonym.matches(Regex("[0-9a-f-]{36}"))) return
        durable.putDoc("labeled/$pseudonym", stored)
    }

    @Synchronized
    override fun labeledCount() = durable.countDocs("labeled")

    @Synchronized
    override fun deleteLabeled(pseudonym: String): Boolean {
        if (!pseudonym.matches(Regex("[0-9a-f-]{36}"))) return false
        durable.deleteDoc("labeled/$pseudonym")
        return true
    }

    @Synchronized
    override fun conflicts(stream: StreamId) = existing(stream)?.conflicts ?: 0

    private fun <K> TreeMap<K, *>.firstKeyOrNull(): K? = if (isEmpty()) null else firstKey()
    private fun <K> TreeMap<K, *>.lastKeyOrNull(): K? = if (isEmpty()) null else lastKey()
}
