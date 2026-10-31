package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.MirrorProtocol.Record
import java.io.File
import java.io.FileOutputStream
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

    /** Highest seq such that every record from the stream's first held seq up to it is held; -1 if none. */
    fun contiguousHead(stream: StreamId): Long

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

/**
 * [StreamStore] on the local disk: per stream, an append-only `records.log` (each record once, in arrival order,
 * fsync'd) and small logs beside it; an in-memory index of seq → record, rebuilt on start (arrival times stay in the file).
 * One process, modest scale: a deployment swaps in a managed store behind the same interface.
 */
class FileStreamStore(private val root: File) : StreamStore {

    private class Index(dir: File) {
        val file = File(dir.apply { mkdirs() }, "records.log")
        val out = FileOutputStream(file, true)
        val hashes = TreeMap<Long, String>()
        val lines = HashMap<Long, String>()
        var conflicts = 0
        var contiguousFrom = -1L
        var contiguous = -1L
    }

    private val indexes = HashMap<StreamId, Index>()

    init {
        root.resolve("streams").listFiles()?.forEach { device ->
            device.listFiles()?.forEach { s -> load(StreamId(device.name, s.name)) }
        }
    }

    private fun dir(stream: StreamId) = root.resolve("streams/${stream.deviceId}/${stream.stream}")

    /** A stream only exists once a car wrote to it: reads never create one. */
    private fun existing(stream: StreamId): Index? = indexes[stream] ?: if (dir(stream).resolve("records.log").exists()) load(stream) else null

    private fun load(stream: StreamId): Index = indexes.getOrPut(stream) {
        Index(dir(stream)).also { idx ->
            // records.log lines: receivedAtMs \t record (the record itself contains tabs)
            if (idx.file.exists()) idx.file.readLines().filter { it.isNotBlank() }.forEach { l ->
                Record.parse(l.substringAfter('\t'))?.let { r ->
                    idx.hashes[r.seq] = r.hash
                    idx.lines[r.seq] = r.line
                }
            }
        }
    }

    @Synchronized
    override fun put(stream: StreamId, records: List<Record>, receivedAtMs: Long): PutResult {
        val idx = load(stream)
        var stored = 0
        val conflicts = mutableListOf<String>()
        val buf = StringBuilder()
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
                    buf.append(receivedAtMs).append('\t').append(r.line).append('\n')
                    stored++
                }
            }
        }
        if (buf.isNotEmpty()) {
            idx.out.write(buf.toString().toByteArray())
            idx.out.fd.sync()
        }
        if (conflicts.isNotEmpty()) {
            idx.conflicts += conflicts.size
            appendLog(stream, "conflicts", conflicts.joinToString("\n"))
        }
        return PutResult(stored, conflicts)
    }

    @Synchronized
    override fun contiguousHead(stream: StreamId): Long {
        val idx = existing(stream) ?: return -1
        val first = idx.hashes.firstKeyOrNull() ?: return -1
        var seq = if (idx.contiguousFrom == first) idx.contiguous else first
        while (idx.hashes.containsKey(seq + 1)) seq++
        idx.contiguousFrom = first
        idx.contiguous = seq
        return seq
    }

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
        val f = dir(stream).apply { mkdirs() }.resolve("$log.log")
        if (unique && f.exists() && f.readLines().contains(line)) return false
        FileOutputStream(f, true).use {
            it.write((line + "\n").toByteArray())
            it.fd.sync()
        }
        return true
    }

    @Synchronized
    override fun log(stream: StreamId, log: String): List<String> =
        dir(stream).resolve("$log.log").takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()

    @Synchronized
    override fun streams(): List<StreamId> = (
        indexes.keys + (root.resolve("streams").listFiles()?.flatMap { d -> d.listFiles()?.map { StreamId(d.name, it.name) } ?: emptyList() } ?: emptyList())
        ).distinct().sortedBy { it.toString() }

    @Synchronized
    override fun putLabeled(pseudonym: String, stored: String) {
        val dir = root.resolve("labeled").apply { mkdirs() }
        if (!pseudonym.matches(Regex("[0-9a-f-]{36}"))) return
        dir.resolve(pseudonym).writeText(stored)
    }

    @Synchronized
    override fun labeledCount() = root.resolve("labeled").listFiles()?.size ?: 0

    @Synchronized
    override fun deleteLabeled(pseudonym: String): Boolean {
        if (!pseudonym.matches(Regex("[0-9a-f-]{36}"))) return false
        root.resolve("labeled/$pseudonym").delete()
        return true
    }

    @Synchronized
    override fun conflicts(stream: StreamId) = existing(stream)?.conflicts ?: 0

    private fun <K> TreeMap<K, *>.firstKeyOrNull(): K? = if (isEmpty()) null else firstKey()
    private fun <K> TreeMap<K, *>.lastKeyOrNull(): K? = if (isEmpty()) null else lastKey()
}
