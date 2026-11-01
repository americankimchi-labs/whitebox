package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.ChainEntry
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * One capsule as a single text file, made on the owner's phone ("증거 파일 내려받기"): the records exactly as stored,
 * the car's seals, the server's signed anchors and its public key, and the keys of the capsule's own minutes.
 * Anyone can check it without White Box: every hash and link, each seal's signature over the head, each anchor's
 * signature, and that the values open with the keys inside.
 *
 *     WHITEBOX-EVIDENCE|1
 *     CAPSULE|id|trigger|firstSeq|lastSeq|device|stream
 *     SEAL|<seal line>              (one per seal)
 *     ATTESTATION|<server line>     (a server seal, when the car went silent)
 *     ANCHOR|<server line>          (one per anchor)
 *     SERVERKEY|<base64 SPKI>
 *     KEY|epoch|minute|<base64>     (one per minute)
 *     RECORD|<chain line>           (the record before the capsule, then the capsule's)
 */
object EvidenceFile {
    data class Result(
        val capsuleId: String,
        val trigger: String,
        val firstSeq: Long,
        val lastSeq: Long,
        /** Records in the capsule whose hash and link check out (all of them, or the check failed). */
        val recordsChecked: Int,
        val chainOk: Boolean,
        val chainProblem: String?,
        /** Car seals whose signature is valid and whose head is the capsule's last record. */
        val sealsValid: Int,
        val seals: Int,
        /** Server anchors with a valid signature that agree with a record's hash. */
        val anchorsValid: Int,
        val anchors: Int,
        /** Records whose values opened with the keys in the file. */
        val opened: Int,
        val entries: List<Pair<Long, ChainEntry>>,
    ) {
        val ok: Boolean get() = chainOk && seals > 0 && sealsValid == seals && anchorsValid == anchors
    }

    fun verify(text: String): Result {
        val lines = text.lines().filter { it.isNotBlank() }
        require(lines.firstOrNull()?.startsWith("WHITEBOX-EVIDENCE|") == true) { "not a White Box evidence file" }
        val cap = lines.first { it.startsWith("CAPSULE|") }.split("|")
        val first = cap[3].toLong()
        val last = cap[4].toLong()
        fun body(prefix: String) = lines.filter { it.startsWith("$prefix|") }.map { it.substringAfter('|') }
        val records = body("RECORD").mapNotNull { MirrorProtocol.Record.parse(it) }.sortedBy { it.seq }
        val serverKey = body("SERVERKEY").firstOrNull()?.let { Base64.getDecoder().decode(it) }
        val keys = body("KEY").associate { l -> l.split("|").let { (it[0].toInt() to it[1].toLong()) to Base64.getDecoder().decode(it[2]) } }

        // The chain: from the record before the capsule (if present) through its last, every hash and every link.
        val hashes = HashMap<Long, String>()
        var prev: String? = records.firstOrNull { it.seq == first - 1 }?.hash
        var checked = 0
        var problem: String? = null
        for (seq in first..last) {
            val r = records.firstOrNull { it.seq == seq }
            if (r == null) { problem = "#$seq is missing"; break }
            if (sha256Hex("${r.seq}\t${r.prevHash}\t${r.entry}") != r.hash) { problem = "#$seq does not match its hash"; break }
            if (prev != null && r.prevHash != prev) { problem = "#$seq does not link to #${seq - 1}"; break }
            hashes[seq] = r.hash
            prev = r.hash
            checked++
        }

        val seals = body("SEAL").mapNotNull { runCatching { SealStore.parse(it) }.getOrNull() }
        val sealsValid = seals.count { s ->
            s.signatureValid() && s.capsule.id == cap[1] && hashes[s.capsule.lastSeq]?.let { it == s.headHash.toHex() } == true
        }
        val anchorLines = body("ANCHOR")
        val anchorsValid = anchorLines.count { line ->
            val f = line.substringBefore("|sig=").split("|")
            val head = f.getOrNull(f.size - 3)?.toLongOrNull()
            serverKey != null && serverSigned(serverKey, line) && (head == null || hashes[head] == null || hashes[head] == f[f.size - 2])
        }

        val cipher = MinuteKeysCipher(keys)
        val entries = records.filter { it.seq in first..last }.mapNotNull { r -> runCatching { r.seq to EntryCodec.decode(r.entry, cipher) }.getOrNull() }
        return Result(
            cap[1], cap[2], first, last, checked, problem == null, problem, sealsValid, seals.size, anchorsValid, anchorLines.size,
            entries.size, entries,
        )
    }

    private fun serverSigned(key: ByteArray, line: String): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key)))
            update(line.substringBefore("|sig=").toByteArray())
            verify(Base64.getDecoder().decode(line.substringAfter("|sig=")))
        }
    }.getOrDefault(false)
}
