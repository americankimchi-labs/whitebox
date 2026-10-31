package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.EscalationLevel
import java.security.MessageDigest
import java.util.Base64

/**
 * The car ↔ server mirror protocol, shared by both ends. One always-open WebSocket carries text frames; every frame
 * is self-contained, so any frame that gets out survives on its own (there is no file to finish).
 *
 * A chain record travels exactly as the car stores it (`seq \t prevHash \t entry \t hash`), so the server checks
 * the same hashes the car wrote. Every frame header carries the car's current escalation level in plain text:
 * the server's dead-man switch needs it even where the record body is not readable to the server.
 *
 * Car → server
 * - `AUTH|stream|publicKey|signature` — answers [Frame.Challenge]; signature over [authText] with the device key.
 * - `EV|level|record` — one record, sent the moment it is recorded while the car is in ALERT or TRIGGERED (twice,
 *   without waiting for an acknowledgement).
 * - `BATCH|level` + one record per line — store-and-forward, acknowledged.
 * - `SEAL|sealLine` — a capsule seal (the car's seal log line), sent first after a trigger.
 *
 * Server → car
 * - `CHALLENGE|nonce`, `READY|deviceId`, `DENIED|reason`
 * - `ACK|seq` — the server holds every record up to and including seq.
 * - `SEALACK|sealKey`
 */
object MirrorProtocol {
    /** The car's chain of evidence, and the separate chain of the replayed demo drive. */
    const val STREAM_LIVE = "live"
    const val STREAM_REPLAY = "replay"

    fun authText(stream: String, nonce: String) = "whitebox-mirror|$stream|$nonce"

    /** A short stable id for a device key: the first 16 bytes of SHA-256 over its X.509 encoding, in hex. */
    fun deviceId(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(16).toHex()

    /** What identifies one seal (a capsule is sealed several times: provisional, re-sealed, final). */
    fun sealKey(seal: CapsuleSeal) = "${seal.capsule.id}:${seal.final}:${seal.sealedAtWallMillis}"

    sealed interface Frame {
        data class Challenge(val nonce: String) : Frame
        data class Auth(val stream: String, val publicKey: ByteArray, val signature: ByteArray) : Frame
        data class Ready(val deviceId: String) : Frame
        data class Denied(val reason: String) : Frame
        data class Event(val level: EscalationLevel, val record: String) : Frame
        data class Batch(val level: EscalationLevel, val records: List<String>) : Frame
        data class Seal(val sealLine: String) : Frame
        data class Ack(val seq: Long) : Frame
        data class SealAck(val sealKey: String) : Frame
    }

    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun encode(f: Frame): String = when (f) {
        is Frame.Challenge -> "CHALLENGE|${f.nonce}"
        is Frame.Auth -> "AUTH|${f.stream}|${b64e.encodeToString(f.publicKey)}|${b64e.encodeToString(f.signature)}"
        is Frame.Ready -> "READY|${f.deviceId}"
        is Frame.Denied -> "DENIED|${f.reason}"
        is Frame.Event -> "EV|${f.level}|${f.record}"
        is Frame.Batch -> (listOf("BATCH|${f.level}") + f.records).joinToString("\n")
        is Frame.Seal -> "SEAL|${f.sealLine}"
        is Frame.Ack -> "ACK|${f.seq}"
        is Frame.SealAck -> "SEALACK|${f.sealKey}"
    }

    /** Null for anything malformed: a bad frame is dropped, never allowed to break the channel. */
    fun decode(s: String): Frame? = runCatching {
        val type = s.substringBefore('|')
        val rest = s.substringAfter('|', "")
        when (type) {
            "CHALLENGE" -> Frame.Challenge(rest)
            "AUTH" -> rest.split("|").let { Frame.Auth(it[0], b64d.decode(it[1]), b64d.decode(it[2])) }
            "READY" -> Frame.Ready(rest)
            "DENIED" -> Frame.Denied(rest)
            "EV" -> Frame.Event(EscalationLevel.valueOf(rest.substringBefore('|')), rest.substringAfter('|'))
            "BATCH" -> {
                val lines = s.split("\n")
                Frame.Batch(EscalationLevel.valueOf(lines.first().substringAfter('|')), lines.drop(1).filter { it.isNotEmpty() })
            }
            "SEAL" -> Frame.Seal(rest)
            "ACK" -> Frame.Ack(rest.toLong())
            "SEALACK" -> Frame.SealAck(rest)
            else -> null
        }
    }.getOrNull()

    /** A stored chain record, split and checked against its own hash. */
    class Record(val seq: Long, val prevHash: String, val entry: String, val hash: String, val line: String) {
        companion object {
            /** Null if malformed or if the hash does not cover the record's content. */
            fun parse(line: String): Record? {
                val f = line.split("\t")
                if (f.size != 4) return null
                val seq = f[0].toLongOrNull() ?: return null
                if (sha256Hex("${f[0]}\t${f[1]}\t${f[2]}") != f[3]) return null
                return Record(seq, f[1], f[2], f[3], line)
            }
        }
    }
}
