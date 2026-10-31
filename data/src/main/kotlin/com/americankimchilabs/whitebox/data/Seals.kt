package com.americankimchilabs.whitebox.data

import com.americankimchilabs.whitebox.domain.CapsuleTrigger
import com.americankimchilabs.whitebox.domain.IncidentCapsule
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * A signed statement "chain records [IncidentCapsule.firstSeq]..[IncidentCapsule.lastSeq] end in [headHash]".
 * Re-hashing a modified chain is possible for anyone with the file; forging this signature is not, so a rewritten
 * tail is caught here even when every link checks out (see the chain property test).
 */
class CapsuleSeal(
    val capsule: IncidentCapsule,
    val headHash: ByteArray,
    /** False for the immediate seal at trigger time; the final seal at stillness supersedes it. */
    val final: Boolean,
    val sealedAtWallMillis: Long,
    val publicKey: ByteArray,
    val signature: ByteArray,
) {
    /** The exact bytes the signature covers. */
    val signedBytes: ByteArray get() = signedText(capsule, headHash, final, sealedAtWallMillis).toByteArray()

    fun signatureValid(): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(signedBytes)
            verify(signature)
        }
    }.getOrDefault(false)

    companion object {
        fun signedText(c: IncidentCapsule, head: ByteArray, final: Boolean, at: Long) =
            "${c.id}|${c.trigger}|${c.firstSeq}|${c.lastSeq}|${head.toHex()}|$final|$at"

        fun create(c: IncidentCapsule, head: ByteArray, final: Boolean, at: Long, signer: Signer) =
            CapsuleSeal(c, head, final, at, signer.publicKey, signer.sign(signedText(c, head, final, at).toByteArray()))
    }
}

sealed interface CapsuleCheck {
    data object Verified : CapsuleCheck

    data class Failed(val reason: String) : CapsuleCheck
}

/** Chain intact over the capsule range, its head equals the sealed hash, and the seal signature is valid. */
fun verifyCapsule(seal: CapsuleSeal, store: EventStore): CapsuleCheck {
    if (!seal.signatureValid()) return CapsuleCheck.Failed("seal signature invalid")
    return when (val c = store.verify(seal.capsule.firstSeq, seal.capsule.lastSeq)) {
        is ChainCheck.Broken -> CapsuleCheck.Failed("chain broken at #${c.seq}: ${c.reason}")
        is ChainCheck.Intact ->
            if (c.headHash.contentEquals(seal.headHash)) CapsuleCheck.Verified
            else CapsuleCheck.Failed("chain was rewritten: head differs from the signed seal")
    }
}

/** Append-only seal log, one fsync'd line per seal; the latest seal per capsule id wins. Torn tail is dropped. */
class SealStore(dir: File) {
    private val file = File(dir.apply { mkdirs() }, "seals.log")

    init {
        if (file.exists()) {
            val bytes = file.readBytes()
            val complete = bytes.lastIndexOf('\n'.code.toByte()) + 1
            if (complete < bytes.size) RandomAccessFile(file, "rw").use { it.setLength(complete.toLong()) }
        }
    }

    @Synchronized
    fun append(seal: CapsuleSeal) {
        FileOutputStream(file, true).use {
            it.write((line(seal) + "\n").toByteArray())
            it.fd.sync()
        }
    }

    /** Latest seal per capsule, oldest capsule first. */
    @Synchronized
    fun all(): List<CapsuleSeal> {
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.map(::parse).associateBy { it.capsule.id }.values.toList()
    }

    companion object {
        /** One seal as a log line (also how the mirror carries it). */
        fun line(s: CapsuleSeal): String {
            val b64 = Base64.getEncoder()
            return CapsuleSeal.signedText(s.capsule, s.headHash, s.final, s.sealedAtWallMillis) + "|" +
                b64.encodeToString(s.publicKey) + "|" + b64.encodeToString(s.signature)
        }

        fun parse(line: String): CapsuleSeal {
            val b64 = Base64.getDecoder()
            val f = line.split("|")
            return CapsuleSeal(
                IncidentCapsule(f[0], CapsuleTrigger.valueOf(f[1]), f[2].toLong(), f[3].toLong()),
                f[4].hexToBytes(), f[5].toBooleanStrict(), f[6].toLong(), b64.decode(f[7]), b64.decode(f[8]),
            )
        }
    }
}
