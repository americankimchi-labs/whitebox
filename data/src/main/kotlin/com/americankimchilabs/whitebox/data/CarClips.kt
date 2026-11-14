package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.core.CameraClipSource
import com.americankimchilabs.whitebox.core.ClipUpload
import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.ChainEntry
import com.americankimchilabs.whitebox.domain.DriveEvent
import com.americankimchilabs.whitebox.domain.EscalationEvent
import com.americankimchilabs.whitebox.domain.EscalationLevel
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Clips the car attaches by itself from a built-in camera (Plus and Pro), once a capsule's final seal is made.
 * Encrypted exactly as the phone encrypts a dashcam clip (a key from the capsule's first minute key, 4 MiB pieces of
 * IV + AES-GCM), so the same people may watch it; signed with the device key over what it uploads, so the cloud keeps
 * it only for this car's own capsule. The recording service calls [attach] for every final seal once it has a
 * [CameraClipSource]: no platform gives one today.
 */
class CarClips(
    private val store: FileEventStore,
    private val source: CameraClipSource,
    private val upload: ClipUpload,
    private val signer: Signer,
    private val minuteKey: (Int, Long) -> ByteArray?,
    private val dir: File,
    /** Whether the owner's plan includes clips from the car's own camera. */
    private val allowed: () -> Boolean,
) {
    private val random = SecureRandom()

    /**
     * [onSeal] with the detection instant read from the chain ([causeWall]), tried again while the cloud may not have
     * the seal yet (the clip is accepted only for a capsule it already holds); true once uploaded.
     */
    suspend fun attach(seal: CapsuleSeal, tries: Int = 3, retryMs: Long = 20_000): Boolean {
        if (!seal.final || seal.capsule.trigger == CapsuleTrigger.HANDOVER || !allowed()) return false
        val cause = causeWall(seal.capsule, store.read(seal.capsule.firstSeq, seal.capsule.lastSeq)) ?: return false
        repeat(tries) { n ->
            if (onSeal(seal, cause)) return true
            if (n < tries - 1) kotlinx.coroutines.delay(retryMs)
        }
        return false
    }

    /** Attaches the clip of [seal]'s capsule if the plan includes it and the camera has one; true if uploaded. */
    suspend fun onSeal(seal: CapsuleSeal, eventWall: Long): Boolean {
        if (!seal.final || seal.capsule.trigger == CapsuleTrigger.HANDOVER || !allowed()) return false
        val c = seal.capsule
        val records = store.records(c.firstSeq, c.lastSeq)
        val first = records.mapNotNull { MirrorProtocol.Record.parse(it)?.entry }.flatMap { it.split("|") }
            .firstOrNull { it.startsWith(RecordCipher.PREFIX_V3) } ?: return false
        val base = minuteKey(RecordCipher.epochOf(first), RecordCipher.minuteOf(first)!!) ?: return false
        val times = records.mapNotNull { MirrorProtocol.Record.parse(it)?.entry?.let { e -> runCatching { EntryCodec.timestamp(e).wallMillis }.getOrNull() } }
        val clip = source.clip(times.min(), times.max()) ?: return false
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also(random::nextBytes))
        val out = File(dir, "clip-$id.tmp")
        try {
            ClipFormat.encrypt(ClipFormat.key(base, id), clip.file, out)
            val digest = MessageDigest.getInstance("SHA-256").digest(out.readBytes()).toHex()
            val offset = (clip.firstFrameWall - eventWall).toDouble()
            val signature = signer.sign(ClipFormat.signedText(c.id, id, offset, digest).toByteArray())
            return upload.upload(c.id, id, offset, clip.name, out, signature)
        } finally {
            out.delete()
        }
    }

}

/**
 * The wall time of the detection a capsule is named after, from the capsule's own records [events]: the record its
 * trigger points at (the trigger that names the capsule, since a crash takes a near-miss capsule over; else the
 * first), or the first alert where there is no trigger. Every viewer places a clip relative to this instant, so the
 * car measures its own clip's offset from it too. Null for an empty range.
 */
fun causeWall(capsule: IncidentCapsule, events: List<DriveEvent>): Long? {
    val escalations = events.mapNotNull { (it.entry as? ChainEntry.Escalation)?.event }
    val triggers = escalations.filterIsInstance<EscalationEvent.Triggered>()
    val causeSeq = (triggers.lastOrNull { it.trigger == capsule.trigger } ?: triggers.firstOrNull())?.causeSeq
        ?: escalations.filterIsInstance<EscalationEvent.LevelChanged>().firstOrNull { it.level == EscalationLevel.ALERT }?.causeSeq
        ?: capsule.lastSeq
    return (events.lastOrNull { it.seq <= causeSeq } ?: events.firstOrNull())?.entry?.at?.wallMillis
}

/** The format of a saved clip, the same from the phone (clip.js) and from the car. */
object ClipFormat {
    /** Plaintext piece size; each piece is stored as a 12-byte IV followed by its AES-GCM ciphertext and tag. */
    const val PIECE = 4 shl 20

    /** The clip's key: HKDF-SHA256 of its capsule's first minute key (empty salt, info `whitebox-clip-v1|<clip id>`). */
    fun key(minuteKey: ByteArray, clipId: String): ByteArray = SealedBox.hkdf(minuteKey, "whitebox-clip-v1|$clipId".toByteArray(), 32)

    /** [from] encrypted into [to], piece by piece. */
    fun encrypt(key: ByteArray, from: File, to: File, random: SecureRandom = SecureRandom()) {
        FileOutputStream(to).use { o ->
            from.inputStream().use { i ->
                val buf = ByteArray(PIECE)
                while (true) {
                    val n = i.readNBytes(buf, 0, buf.size)
                    if (n <= 0) break
                    val iv = ByteArray(12).also(random::nextBytes)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
                    o.write(iv)
                    o.write(cipher.doFinal(buf, 0, n))
                }
            }
        }
    }

    /** What the car signs when it uploads a clip of its own capsule. */
    fun signedText(capsuleId: String, clipId: String, offsetMs: Double, sha256Hex: String) = "whitebox-car-clip|$capsuleId|$clipId|$offsetMs|$sha256Hex"
}
