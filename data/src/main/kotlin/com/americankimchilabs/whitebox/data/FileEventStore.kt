package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.SealedChain
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * [EventStore] as one append-only text file, one record per line:
 *
 *     seq \t prevHashHex \t EntryCodec \t hashHex \n      hash = SHA-256("seq \t prevHashHex \t EntryCodec")
 *
 * With a [RecordCipher] the entry's values are encrypted before hashing, so the file is encrypted at rest and the
 * chain (and anyone holding a copy, the mirror included) can be verified without the key.
 *
 * Every append is one write plus fsync before it returns (one durable write per event).
 * Recovery on open: a process killed mid-write leaves an unterminated last line; that tail (and only that) is
 * truncated. Anything wrong in a complete line is left in place for [verify] to report — the store never repairs
 * evidence silently.
 *
 * Index: line offsets in memory (8 B per record). Fine for the rolling 7-day stream at prototype scale.
 *
 * [writeRecord] exists for durability tests that kill the writer mid-record.
 */
class FileEventStore(
    dir: File,
    /** Encrypts record values before they are written (and decrypts on read); null keeps them plain. */
    private val cipher: RecordCipher? = null,
    private val writeRecord: (FileOutputStream, ByteArray) -> Unit = { out, bytes -> out.write(bytes) },
) : EventStore {

    private val file = File(dir.apply { mkdirs() }, "chain.log")
    private val offsets = ArrayList<Long>()
    private var end = 0L
    private var lastHash = ZERO
    private val out: FileOutputStream

    init {
        recover()
        out = FileOutputStream(file, true)
    }

    @Synchronized
    override fun append(entry: ChainEntry): DriveEvent {
        val seq = offsets.size.toLong()
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
        return DriveEvent(seq, entry)
    }

    @Synchronized
    override fun read(fromSeq: Long, toSeq: Long): List<DriveEvent> =
        lines(fromSeq, toSeq).map { (seq, line) -> DriveEvent(seq, EntryCodec.decode(line.split("\t")[2], cipher)) }

    @Synchronized
    override fun head(): SealedChain? =
        if (offsets.isEmpty()) null else SealedChain(offsets.size - 1L, lastHash.hexToBytes())

    @Synchronized
    override fun verify(fromSeq: Long, toSeq: Long): ChainCheck {
        if (fromSeq < 0 || toSeq >= offsets.size || fromSeq > toSeq) return ChainCheck.Broken(fromSeq, "range outside the log")
        var prev = if (fromSeq == 0L) ZERO else lines(fromSeq - 1, fromSeq - 1).single().second.split("\t").getOrNull(3)
        var hash = ""
        for ((seq, line) in lines(fromSeq, toSeq)) {
            val f = line.split("\t")
            if (f.size != 4) return ChainCheck.Broken(seq, "malformed record")
            if (f[0] != seq.toString()) return ChainCheck.Broken(seq, "seq ${f[0]} out of place")
            if (f[1] != prev) return ChainCheck.Broken(seq, "link to the previous record broken")
            hash = sha256Hex("${f[0]}\t${f[1]}\t${f[2]}")
            if (hash != f[3]) return ChainCheck.Broken(seq, "record content does not match its hash")
            prev = hash
        }
        return ChainCheck.Intact(hash.hexToBytes())
    }

    /** Hash stored for [seq] (what a seal signs). */
    @Synchronized
    fun hashAt(seq: Long): ByteArray = lines(seq, seq).single().second.split("\t")[3].hexToBytes()

    /** Records exactly as stored (what the mirror sends), inclusive range. */
    @Synchronized
    fun records(fromSeq: Long, toSeq: Long): List<String> = lines(fromSeq, toSeq).map { it.second }

    fun close() = out.close()

    private fun lines(fromSeq: Long, toSeq: Long): List<Pair<Long, String>> {
        if (fromSeq > toSeq) return emptyList()
        val start = offsets[fromSeq.toInt()]
        val stop = if (toSeq + 1 < offsets.size) offsets[(toSeq + 1).toInt()] else end
        val buf = ByteArray((stop - start).toInt())
        RandomAccessFile(file, "r").use { it.seek(start); it.readFully(buf) }
        return String(buf).removeSuffix("\n").split("\n").mapIndexed { i, l -> fromSeq + i to l }
    }

    private fun recover() {
        if (!file.exists()) return
        val bytes = file.readBytes()
        val complete = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (complete < bytes.size) RandomAccessFile(file, "rw").use { it.setLength(complete.toLong()) }
        var pos = 0
        while (pos < complete) {
            offsets += pos.toLong()
            val nl = bytes.indexOf('\n'.code.toByte(), pos)
            lastHash = String(bytes, pos, nl - pos).split("\t").getOrNull(3) ?: lastHash
            pos = nl + 1
        }
        end = complete.toLong()
    }

    private fun ByteArray.indexOf(b: Byte, from: Int): Int {
        for (i in from until size) if (this[i] == b) return i
        return -1
    }

    companion object {
        val ZERO = "0".repeat(64)
    }
}

internal fun sha256Hex(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
