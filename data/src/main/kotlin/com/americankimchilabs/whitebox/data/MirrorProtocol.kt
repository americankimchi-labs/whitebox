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
 *   without waiting for an acknowledgement). A core-signal record carries, on a second line, a copy of its content
 *   sealed to the server's key: the record itself is end-to-end encrypted, and the dead-man switch needs to read a
 *   crash signature.
 * - `BATCH|level` + one record per line — store-and-forward, acknowledged.
 * - `SEAL|sealLine` — a capsule seal (the car's seal log line), sent first after a trigger.
 * - `CLAIMREQ` — the owner asked, on the parked car's screen, to connect a phone.
 * - `WRAP|recipientId|epoch|sealed` — one epoch of the car's root key sealed to the owner's phone key (after
 *   checking the claim's MAC); every epoch that belongs to that owner is sent.
 * - `WINDOWDEL|pseudonym` — the driver withdrew consent: delete a capsule window sent earlier.
 * - `TRANSFERACK|transferId` — the car handled a pending or declined takeover.
 * - `ESCROW|capsuleId|epoch|minute|sealed` — after each seal of a severe capsule, the key of each minute it covers,
 *   sealed to the server's key: the owner or a registered emergency contact can open that crash (and only that)
 *   without the car or the owner's phone.
 * - `SHARE|capsuleId|codeHash|expiresMs` — the driver showed a capsule's QR: the link's code (its hash here) lets
 *   anyone holding the link fetch that capsule's encrypted records; the keys travel only in the link.
 * - `HANDOVER|phase|requestedAtWall|sinceWall|tripId` — handover mode on the car (OFF/ARMED/ACTIVE/ENDING), on each
 *   change and on connecting; `HANDOVERACK|commandId` — a command from the owner's phone was carried out.
 * - `WINDOW|pseudonym|sealed` — a closed capsule's signal window (core signals, triggers, the driver's answer),
 *   sealed to the server's key under a random pseudonym, with no device id: the labeled data the terms allow.
 *
 * Server → car
 * - `CHALLENGE|nonce`, `READY|deviceId|serverPublicKey`, `DENIED|reason`
 * - `ACK|seq` — the server holds every record up to and including seq.
 * - `SEALACK|sealKey`, `WINDOWACK|pseudonym`
 * - `CLAIMCODE|code` — the code for the car's QR (the QR adds a secret of the car's own that the server never sees).
 * - `CLAIMED|ownerId|phoneKey|mac|maskedPhone` — a phone verified its number with that code; [mac] is the phone's
 *   proof, made with the QR secret, that it saw the car's screen. A different owner than before starts a new key
 *   epoch, so the new owner can read only what is recorded from then on.
 * - `CLAIMPENDING|transferId|phoneKey|mac|maskedPhone` — a different owner's phone asked for the car (MAC checked
 *   by the car as for a claim); the current owner is asked. The car starts a new key epoch at once that belongs to
 *   nobody yet: whoever ends up owning the car gets it, so nothing recorded during the wait goes to the wrong person.
 * - `CLAIMDECLINED|transferId` — the owner declined: the pending epoch is the owner's.
 * - `ESCROWACK|epoch|minute`, `SHAREACK|capsuleId`
 * - `HANDOVERCMD|commandId|ARM|requestedAtWall` / `HANDOVERCMD|commandId|END|0` — the owner's phone turned handover
 *   mode on or off; resent on every connection until the car acknowledges it.
 * - `WINDOWDELACK|pseudonym`
 */
object MirrorProtocol {
    /** The car's chain of evidence, and the separate chain of the replayed demo drive. */
    const val STREAM_LIVE = "live"
    const val STREAM_REPLAY = "replay"

    fun authText(stream: String, nonce: String) = "whitebox-mirror|$stream|$nonce"

    /** A short stable id for a device key: the first 16 bytes of SHA-256 over its X.509 encoding, in hex. */
    /** The phone's proof that it saw the car's QR: HMAC-SHA256 with the QR secret over the code and its key. */
    fun claimMac(secret: ByteArray, code: String, phoneKey: String): String = b64(
        javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"))
            doFinal("claim|$code|$phoneKey".toByteArray())
        },
    )

    fun deviceId(publicKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publicKey).copyOf(16).toHex()

    /** What identifies one seal (a capsule is sealed several times: provisional, re-sealed, final). */
    fun sealKey(seal: CapsuleSeal) = "${seal.capsule.id}:${seal.final}:${seal.sealedAtWallMillis}"

    sealed interface Frame {
        data class Challenge(val nonce: String) : Frame
        data class Auth(val stream: String, val publicKey: ByteArray, val signature: ByteArray) : Frame
        class Ready(val deviceId: String, val serverKey: ByteArray) : Frame {
            override fun equals(other: Any?) = other is Ready && other.deviceId == deviceId && other.serverKey.contentEquals(serverKey)
            override fun hashCode() = deviceId.hashCode()
        }
        data class Denied(val reason: String) : Frame
        data class Event(val level: EscalationLevel, val record: String, val serverCopy: String? = null) : Frame
        data class Batch(val level: EscalationLevel, val records: List<String>) : Frame
        data class Seal(val sealLine: String) : Frame
        data class Window(val pseudonym: String, val sealed: String) : Frame
        data object ClaimRequest : Frame
        data class ClaimCode(val code: String) : Frame
        data class Claimed(val ownerId: String, val phoneKey: String, val mac: String, val maskedPhone: String) : Frame
        data class Wrap(val recipientId: String, val epoch: Int, val sealed: String) : Frame
        data class ClaimPending(val transferId: String, val phoneKey: String, val mac: String, val maskedPhone: String) : Frame
        data class ClaimDeclined(val transferId: String) : Frame
        data class TransferAck(val transferId: String) : Frame
        data class WindowDelete(val pseudonym: String) : Frame
        data class WindowDeleted(val pseudonym: String) : Frame
        data class WindowAck(val pseudonym: String) : Frame
        data class Ack(val seq: Long) : Frame
        data class SealAck(val sealKey: String) : Frame

        /**
         * The key of one minute of a severe capsule, sealed to the server's key, so the owner or a registered
         * emergency contact can open that crash without the car or the owner's phone. Everyday minutes never leave.
         */
        data class Escrow(val capsuleId: String, val epoch: Int, val minute: Long, val sealed: String) : Frame
        data class EscrowAck(val epoch: Int, val minute: Long) : Frame

        /** The driver showed a capsule's QR: whoever presents the code whose hash this is may fetch that capsule. */
        data class Share(val capsuleId: String, val codeHash: String, val expiresMs: Long) : Frame
        data class ShareAck(val capsuleId: String) : Frame

        /** The owner's phone turned handover mode on ([arm], at [requestedAtWall]) or off; resent until acknowledged. */
        data class HandoverCommand(val commandId: String, val arm: Boolean, val requestedAtWall: Long) : Frame
        data class HandoverAck(val commandId: String) : Frame

        /** Where handover mode stands on the car, for the owner's phone: sent on every change and on connecting. */
        data class HandoverStatus(val phase: String, val requestedAtWall: Long, val sinceWall: Long, val tripId: String) : Frame
    }

    private val b64e = Base64.getEncoder()
    private val b64d = Base64.getDecoder()

    fun encode(f: Frame): String = when (f) {
        is Frame.Challenge -> "CHALLENGE|${f.nonce}"
        is Frame.Auth -> "AUTH|${f.stream}|${b64e.encodeToString(f.publicKey)}|${b64e.encodeToString(f.signature)}"
        is Frame.Ready -> "READY|${f.deviceId}|${b64e.encodeToString(f.serverKey)}"
        is Frame.Denied -> "DENIED|${f.reason}"
        is Frame.Event -> "EV|${f.level}|${f.record}" + (f.serverCopy?.let { "\n$it" } ?: "")
        is Frame.Batch -> (listOf("BATCH|${f.level}") + f.records).joinToString("\n")
        is Frame.Seal -> "SEAL|${f.sealLine}"
        is Frame.Window -> "WINDOW|${f.pseudonym}|${f.sealed}"
        is Frame.WindowAck -> "WINDOWACK|${f.pseudonym}"
        Frame.ClaimRequest -> "CLAIMREQ"
        is Frame.ClaimCode -> "CLAIMCODE|${f.code}"
        is Frame.Claimed -> "CLAIMED|${f.ownerId}|${f.phoneKey}|${f.mac}|${f.maskedPhone}"
        is Frame.Wrap -> "WRAP|${f.recipientId}|${f.epoch}|${f.sealed}"
        is Frame.ClaimPending -> "CLAIMPENDING|${f.transferId}|${f.phoneKey}|${f.mac}|${f.maskedPhone}"
        is Frame.ClaimDeclined -> "CLAIMDECLINED|${f.transferId}"
        is Frame.TransferAck -> "TRANSFERACK|${f.transferId}"
        is Frame.WindowDelete -> "WINDOWDEL|${f.pseudonym}"
        is Frame.WindowDeleted -> "WINDOWDELACK|${f.pseudonym}"
        is Frame.Ack -> "ACK|${f.seq}"
        is Frame.SealAck -> "SEALACK|${f.sealKey}"
        is Frame.Escrow -> "ESCROW|${f.capsuleId}|${f.epoch}|${f.minute}|${f.sealed}"
        is Frame.EscrowAck -> "ESCROWACK|${f.epoch}|${f.minute}"
        is Frame.Share -> "SHARE|${f.capsuleId}|${f.codeHash}|${f.expiresMs}"
        is Frame.ShareAck -> "SHAREACK|${f.capsuleId}"
        is Frame.HandoverCommand -> "HANDOVERCMD|${f.commandId}|${if (f.arm) "ARM" else "END"}|${f.requestedAtWall}"
        is Frame.HandoverAck -> "HANDOVERACK|${f.commandId}"
        is Frame.HandoverStatus -> "HANDOVER|${f.phase}|${f.requestedAtWall}|${f.sinceWall}|${f.tripId}"
    }

    /** Null for anything malformed: a bad frame is dropped, never allowed to break the channel. */
    fun decode(s: String): Frame? = runCatching {
        val type = s.substringBefore('|')
        val rest = s.substringAfter('|', "")
        when (type) {
            "CHALLENGE" -> Frame.Challenge(rest)
            "AUTH" -> rest.split("|").let { Frame.Auth(it[0], b64d.decode(it[1]), b64d.decode(it[2])) }
            "READY" -> rest.split("|").let { Frame.Ready(it[0], b64d.decode(it[1])) }
            "DENIED" -> Frame.Denied(rest)
            "EV" -> {
                val lines = rest.split("\n")
                Frame.Event(EscalationLevel.valueOf(lines[0].substringBefore('|')), lines[0].substringAfter('|'), lines.getOrNull(1))
            }
            "BATCH" -> {
                val lines = s.split("\n")
                Frame.Batch(EscalationLevel.valueOf(lines.first().substringAfter('|')), lines.drop(1).filter { it.isNotEmpty() })
            }
            "SEAL" -> Frame.Seal(rest)
            "WINDOW" -> Frame.Window(rest.substringBefore('|'), rest.substringAfter('|'))
            "WINDOWACK" -> Frame.WindowAck(rest)
            "CLAIMREQ" -> Frame.ClaimRequest
            "CLAIMCODE" -> Frame.ClaimCode(rest)
            "CLAIMED" -> rest.split("|").let { Frame.Claimed(it[0], it[1], it[2], it[3]) }
            "WRAP" -> rest.split("|", limit = 3).let { Frame.Wrap(it[0], it[1].toInt(), it[2]) }
            "CLAIMPENDING" -> rest.split("|").let { Frame.ClaimPending(it[0], it[1], it[2], it[3]) }
            "CLAIMDECLINED" -> Frame.ClaimDeclined(rest)
            "TRANSFERACK" -> Frame.TransferAck(rest)
            "WINDOWDEL" -> Frame.WindowDelete(rest)
            "WINDOWDELACK" -> Frame.WindowDeleted(rest)
            "ACK" -> Frame.Ack(rest.toLong())
            "SEALACK" -> Frame.SealAck(rest)
            "ESCROW" -> rest.split("|", limit = 4).let { Frame.Escrow(it[0], it[1].toInt(), it[2].toLong(), it[3]) }
            "ESCROWACK" -> rest.split("|").let { Frame.EscrowAck(it[0].toInt(), it[1].toLong()) }
            "SHARE" -> rest.split("|").let { Frame.Share(it[0], it[1], it[2].toLong()) }
            "SHAREACK" -> Frame.ShareAck(rest)
            "HANDOVERCMD" -> rest.split("|").let { Frame.HandoverCommand(it[0], it[1] == "ARM", it[2].toLong()) }
            "HANDOVERACK" -> Frame.HandoverAck(rest)
            "HANDOVER" -> rest.split("|").let { Frame.HandoverStatus(it[0], it[1].toLong(), it[2].toLong(), it[3]) }
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
