package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.SealedChain
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.TreeMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * [EventStore] as append-only text, one record per line:
 *
 *     seq \t prevHashHex \t EntryCodec \t hashHex \n      hash = SHA-256("seq \t prevHashHex \t EntryCodec")
 *
 * With a [RecordCipher] the entry's values are encrypted before hashing, so the files are encrypted at rest and the
 * chain (and anyone holding a copy, the mirror included) can be verified without the key.
 *
 * Every append is one write plus fsync before it returns (one durable write per event), always to `chain.log`. When
 * that file holds [segmentRecords] records it is closed: compressed, byte for byte, into `seg-<first>-<last>-<wall>.gz`
 * and started afresh, so the car never holds more than one open file's index in memory. [prune] removes whole closed
 * files the car no longer needs (the cloud holds them) and keeps the records of the capsules still in the library in
 * `kept-<first>-<last>.gz`; the link from the last removed record to the first remaining one is kept as the checkpoint.
 *
 * Recovery on open: a process killed mid-write leaves an unterminated last line; that tail (and only that) is
 * truncated. A close interrupted before `chain.log` was emptied is finished. Anything wrong in a complete line is left
 * in place for [verify] to report — the store never repairs evidence silently.
 *
 * [writeRecord] exists for durability tests that kill the writer mid-record.
 */
class FileEventStore(
    private val dir: File,
    /** Encrypts record values before they are written (and decrypts on read); null keeps them plain. */
    private val cipher: RecordCipher? = null,
    /** Records per file before it is closed and compressed. */
    private val segmentRecords: Int = SEGMENT_RECORDS,
    private val writeRecord: (FileOutputStream, ByteArray) -> Unit = { out, bytes -> out.write(bytes) },
) : EventStore {

    private class Segment(val first: Long, val last: Long, val lastWall: Long, val file: File)

    /** The last removed record: seq and hash (the first remaining record links to it). */
    data class Checkpoint(val seq: Long, val hash: String, val wall: Long)

    private val file = File(dir.apply { mkdirs() }, "chain.log")
    private val checkpointFile = File(dir, "checkpoint")
    private val segments = TreeMap<Long, Segment>()
    private val kept = TreeMap<Long, Segment>()
    private val offsets = ArrayList<Long>()
    private var base = 0L
    private var end = 0L
    private var lastHash = ZERO
    private var lastWall = 0L
    private var out: FileOutputStream

    /** Decompressed closed files, the last few read (a capsule spans at most a couple). */
    private val cache = object : LinkedHashMap<File, List<String>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<File, List<String>>?) = size > 3
    }

    init {
        index()
        recover()
        out = FileOutputStream(file, true)
    }

    @Synchronized
    override fun append(entry: ChainEntry): DriveEvent {
        // A chain written before files were closed is split on its first new record (opening to read changes nothing).
        if (offsets.size > segmentRecords) split()
        if (offsets.size >= segmentRecords) rotate()
        val seq = base + offsets.size
        val body = "$seq\t$lastHash\t${EntryCodec.encode(entry, cipher)}"
        val hash = sha256Hex(body)
        val bytes = "$body\t$hash\n".toByteArray()
        try {
            writeRecord(out, bytes)
            out.fd.sync()
        } catch (e: Exception) {
            // A failed write must not leave half a record for the next append to build on.
            RandomAccessFile(file, "rw").use { it.setLength(end) }
            throw e
        }
        offsets += end
        end += bytes.size
        lastHash = hash
        lastWall = entry.at.wallMillis
        return DriveEvent(seq, entry)
    }

    /** Records in the inclusive range that are still on this car, in seq order (removed ones are skipped). */
    @Synchronized
    override fun read(fromSeq: Long, toSeq: Long): List<DriveEvent> =
        lines(fromSeq, toSeq).map { (seq, line) -> DriveEvent(seq, EntryCodec.decode(line.split("\t")[2], cipher)) }

    @Synchronized
    override fun head(): SealedChain? {
        val last = base + offsets.size - 1
        return if (last < 0) null else SealedChain(last, lastHash.hexToBytes())
    }

    @Synchronized
    override fun verify(fromSeq: Long, toSeq: Long): ChainCheck {
        val head = base + offsets.size - 1
        if (fromSeq < 0 || toSeq > head || fromSeq > toSeq) return ChainCheck.Broken(fromSeq, "range outside the log")
        val records = lines(fromSeq, toSeq)
        if (records.isEmpty()) return ChainCheck.Broken(fromSeq, "removed from this car")
        // The link into the range: the record before it, else the checkpoint; a range whose predecessor was removed
        // (a kept capsule) starts from its own first record, whose link is part of its hash and covered by its seal.
        var prev = when {
            fromSeq == 0L -> ZERO
            else -> lines(fromSeq - 1, fromSeq - 1).singleOrNull()?.second?.split("\t")?.getOrNull(3)
                ?: checkpoint()?.takeIf { it.seq == fromSeq - 1 }?.hash
                ?: records.first().second.split("\t").getOrNull(1)
        }
        var hash = ""
        var expected = fromSeq
        for ((seq, line) in records) {
            if (seq != expected) return ChainCheck.Broken(expected, "removed from this car")
            val f = line.split("\t")
            if (f.size != 4) return ChainCheck.Broken(seq, "malformed record")
            if (f[0] != seq.toString()) return ChainCheck.Broken(seq, "seq ${f[0]} out of place")
            if (f[1] != prev) return ChainCheck.Broken(seq, "link to the previous record broken")
            hash = sha256Hex("${f[0]}\t${f[1]}\t${f[2]}")
            if (hash != f[3]) return ChainCheck.Broken(seq, "record content does not match its hash")
            prev = hash
            expected++
        }
        if (expected != toSeq + 1) return ChainCheck.Broken(expected, "removed from this car")
        return ChainCheck.Intact(hash.hexToBytes())
    }

    /** Hash stored for [seq] (what a seal signs). */
    @Synchronized
    fun hashAt(seq: Long): ByteArray =
        (lines(seq, seq).singleOrNull() ?: throw NoSuchElementException("record $seq is not on this car")).second.split("\t")[3].hexToBytes()

    /** Records exactly as stored (what the mirror sends), inclusive range; removed ones are skipped. */
    @Synchronized
    fun records(fromSeq: Long, toSeq: Long): List<String> = lines(fromSeq, toSeq).map { it.second }

    /** The oldest record still on this car in the continuous chain (kept capsules aside). */
    @Synchronized
    fun oldestSeq(): Long = segments.firstEntry()?.value?.first ?: base

    /** The last removed record, if any was removed. */
    @Synchronized
    fun checkpoint(): Checkpoint? = runCatching {
        checkpointFile.readText().trim().split("|").let { Checkpoint(it[0].toLong(), it[1], it[2].toLong()) }
    }.getOrNull()

    /** Closed files, oldest first: their range and when their last record was made. For deciding what to remove. */
    @Synchronized
    fun closedRanges(): List<Triple<Long, Long, Long>> = segments.values.map { Triple(it.first, it.last, it.lastWall) }

    /**
     * Removes the closed files whose records all lie at or before [throughSeq], keeping the records inside [keep]
     * (capsules still in the library) in their own small files. Kept files from earlier removals are cut down the
     * same way. Returns the new checkpoint, or null if nothing was removed.
     */
    @Synchronized
    fun prune(throughSeq: Long, keep: List<LongRange>): Checkpoint? {
        fun inKeep(seq: Long) = keep.any { seq in it }
        var cp: Checkpoint? = null
        for (seg in segments.values.filter { it.last <= throughSeq }) {
            val lines = load(seg.file)
            writeKept(lines.filter { inKeep(it.substringBefore('\t').toLong()) })
            cp = Checkpoint(seg.last, lines.last().split("\t")[3], seg.lastWall)
            seg.file.delete()
            segments.remove(seg.first)
            cache.remove(seg.file)
        }
        for (k in kept.values.toList()) {
            val lines = load(k.file)
            val still = lines.filter { inKeep(it.substringBefore('\t').toLong()) }
            if (still.size == lines.size) continue
            k.file.delete()
            kept.remove(k.first)
            cache.remove(k.file)
            writeKept(still)
        }
        cp?.let { c ->
            val tmp = File(dir, "checkpoint.tmp")
            tmp.writeText("${c.seq}|${c.hash}|${c.wall}")
            tmp.renameTo(checkpointFile)
        }
        return cp
    }

    /** Bytes on disk for the chain (open file, closed files, kept files). */
    @Synchronized
    fun sizeOnDisk(): Long = end + segments.values.sumOf { it.file.length() } + kept.values.sumOf { it.file.length() }

    fun close() = out.close()

    private fun lines(fromSeq: Long, toSeq: Long): List<Pair<Long, String>> {
        if (fromSeq > toSeq) return emptyList()
        val result = ArrayList<Pair<Long, String>>()
        // Kept capsule records from removed files.
        if (fromSeq < oldestSeq()) {
            for (k in kept.values) if (k.last >= fromSeq && k.first <= toSeq) {
                for (l in load(k.file)) l.substringBefore('\t').toLong().let { if (it in fromSeq..toSeq) result += it to l }
            }
        }
        // Closed files.
        val floor = if (segments.isEmpty()) null else segments.floorKey(fromSeq) ?: segments.firstKey()
        if (floor != null) for (seg in segments.tailMap(floor).values) {
            if (seg.first > toSeq) break
            if (seg.last < fromSeq) continue
            val ls = load(seg.file)
            for (seq in maxOf(fromSeq, seg.first)..minOf(toSeq, seg.last)) result += seq to ls[(seq - seg.first).toInt()]
        }
        // The open file.
        val from = maxOf(fromSeq, base)
        val to = minOf(toSeq, base + offsets.size - 1)
        if (from <= to) {
            val start = offsets[(from - base).toInt()]
            val stop = if (to + 1 - base < offsets.size) offsets[(to + 1 - base).toInt()] else end
            val buf = ByteArray((stop - start).toInt())
            RandomAccessFile(file, "r").use { it.seek(start); it.readFully(buf) }
            String(buf).removeSuffix("\n").split("\n").forEachIndexed { i, l -> result += from + i to l }
        }
        result.sortBy { it.first }
        return result
    }

    private fun load(f: File): List<String> = cache.getOrPut(f) {
        GZIPInputStream(f.inputStream()).use { String(it.readBytes()) }.removeSuffix("\n").split("\n").filter { it.isNotEmpty() }
    }

    private fun writeGz(target: File, lines: List<String>) {
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { fos ->
            GZIPOutputStream(fos).use { gz -> lines.forEach { gz.write((it + "\n").toByteArray()) }; gz.finish(); fos.fd.sync() }
        }
        tmp.renameTo(target)
    }

    private fun writeKept(lines: List<String>) {
        if (lines.isEmpty()) return
        val first = lines.first().substringBefore('\t').toLong()
        val last = lines.last().substringBefore('\t').toLong()
        val f = File(dir, "kept-$first-$last.gz")
        writeGz(f, lines)
        kept[first] = Segment(first, last, 0, f)
    }

    /** Closes the open file into a compressed one and starts it afresh. */
    private fun rotate() {
        if (offsets.isEmpty()) return
        val bytes = RandomAccessFile(file, "r").use { r -> ByteArray(end.toInt()).also { r.readFully(it) } }
        val lines = String(bytes).removeSuffix("\n").split("\n")
        val last = base + offsets.size - 1
        val seg = Segment(base, last, lastWall, File(dir, "seg-$base-$last-$lastWall.gz"))
        writeGz(seg.file, lines)
        segments[seg.first] = seg
        cache[seg.file] = lines
        out.channel.truncate(0)
        out.fd.sync()
        offsets.clear()
        end = 0
        base = last + 1
    }

    /** One oversized open file (from before files were closed) becomes closed files plus a short open one. */
    private fun split() {
        val bytes = file.readBytes()
        val all = String(bytes).removeSuffix("\n").split("\n")
        val full = (all.size / segmentRecords) * segmentRecords
        for (i in 0 until full step segmentRecords) {
            val chunk = all.subList(i, i + segmentRecords)
            val first = chunk.first().substringBefore('\t').toLong()
            val last = chunk.last().substringBefore('\t').toLong()
            val wall = EntryCodec.timestamp(chunk.last().split("\t")[2]).wallMillis
            val seg = Segment(first, last, wall, File(dir, "seg-$first-$last-$wall.gz"))
            writeGz(seg.file, chunk)
            segments[first] = seg
        }
        val rest = all.subList(full, all.size)
        val tmp = File(dir, "chain.log.tmp")
        tmp.writeText(if (rest.isEmpty()) "" else rest.joinToString("\n", postfix = "\n"))
        out.close()
        tmp.renameTo(file)
        offsets.clear()
        end = 0
        recover()
        out = FileOutputStream(file, true)
    }

    private fun index() {
        dir.listFiles()?.forEach { f ->
            SEG.matchEntire(f.name)?.let { m ->
                val (a, b, w) = m.destructured
                segments[a.toLong()] = Segment(a.toLong(), b.toLong(), w.toLong(), f)
            }
            KEPT.matchEntire(f.name)?.let { m ->
                val (a, b) = m.destructured
                kept[a.toLong()] = Segment(a.toLong(), b.toLong(), 0, f)
            }
            if (f.name.endsWith(".tmp")) f.delete() // an unfinished write: the original is intact
        }
    }

    private fun recover() {
        val closedLast = segments.lastEntry()?.value?.last
        base = (closedLast ?: checkpoint()?.seq ?: -1) + 1
        if (closedLast != null) {
            val tail = load(segments.lastEntry().value.file).last().split("\t")
            lastHash = tail[3]
            lastWall = segments.lastEntry().value.lastWall
        } else {
            lastHash = checkpoint()?.hash ?: ZERO
        }
        if (!file.exists()) return
        val bytes = file.readBytes()
        val complete = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (complete < bytes.size) RandomAccessFile(file, "rw").use { it.setLength(complete.toLong()) }
        if (complete == 0) return
        val firstSeq = String(bytes, 0, minOf(complete, 32)).substringBefore('\t').toLongOrNull() ?: 0
        if (closedLast != null && firstSeq <= closedLast) {
            // Closed and compressed, but the open file was not emptied before the process died: finish that.
            RandomAccessFile(file, "rw").use { it.setLength(0) }
            return
        }
        base = firstSeq
        var pos = 0
        while (pos < complete) {
            offsets += pos.toLong()
            val nl = bytes.indexOf('\n'.code.toByte(), pos)
            val f = String(bytes, pos, nl - pos).split("\t")
            lastHash = f.getOrNull(3) ?: lastHash
            pos = nl + 1
        }
        lastWall = runCatching { EntryCodec.timestamp(String(bytes, offsets.last().toInt(), complete - offsets.last().toInt()).split("\t")[2]).wallMillis }.getOrDefault(lastWall)
        end = complete.toLong()
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }

    companion object {
        val ZERO = "0".repeat(64)

        /** About 15–30 minutes of driving per closed file (≈ 1 MB compressed). */
        const val SEGMENT_RECORDS = 10_000

        private val SEG = Regex("seg-(\\d+)-(\\d+)-(\\d+)\\.gz")
        private val KEPT = Regex("kept-(\\d+)-(\\d+)\\.gz")
    }
}

internal fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
