package com.americankimchilabs.whitebox.data

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the values of chain records. Only the car (holding the root key) and the owner's phone it is wrapped for
 * can read them; the server stores and checks the records without. Each minute has its own key derived from the
 * root key, so the keys of a few minutes (a severe crash, for the family) can be handed out without the rest.
 */
interface RecordCipher {
    /** [aad] binds the ciphertext to its record (type and time), so a value cannot be moved to another record. */
    fun seal(plain: String, aad: String): String

    /** Throws if the key is wrong or the ciphertext or its binding was altered. */
    fun open(sealed: String, aad: String): String

    companion object {
        /** Marks an encrypted field: version 1, AES-256-GCM, 96-bit random nonce, nonce‖ciphertext‖tag in base64. */
        const val PREFIX = "v1."

        /** Version 2 adds the key epoch: `v2.<epoch>.<nonce‖ciphertext‖tag>`; `v1.` fields are epoch 0. */
        const val PREFIX_V2 = "v2."

        /**
         * Version 3 encrypts with the key of one minute: `v3.<epoch>.<minute>.<nonce‖ciphertext‖tag>`, the key being
         * [minuteKey] of the epoch's root key; `minute` is the car's wall-clock minute when it was written.
         */
        const val PREFIX_V3 = "v3."

        fun isSealed(field: String) = field.startsWith(PREFIX) || field.startsWith(PREFIX_V2) || field.startsWith(PREFIX_V3)

        /** The key epoch of an encrypted field. */
        fun epochOf(field: String): Int = when {
            field.startsWith(PREFIX_V2) -> field.removePrefix(PREFIX_V2).substringBefore('.').toInt()
            field.startsWith(PREFIX_V3) -> field.removePrefix(PREFIX_V3).substringBefore('.').toInt()
            else -> 0
        }

        /** The key minute of a `v3.` field; null for older fields, which use the epoch's root key itself. */
        fun minuteOf(field: String): Long? = if (field.startsWith(PREFIX_V3)) field.removePrefix(PREFIX_V3).split('.')[1].toLong() else null

        /** The key of one minute: HKDF-SHA256 of the root key (empty salt, info `whitebox-minute-v1|<minute>`). */
        fun minuteKey(root: ByteArray, minute: Long): ByteArray = SealedBox.hkdf(root, "whitebox-minute-v1|$minute".toByteArray(), 32)
    }
}

/**
 * The car's root keys, one per epoch, and whose each epoch is (the owner it was made for, empty before the first
 * claim). The newest epoch encrypts; older ones stay to read older records. A new epoch starts when the car changes
 * owner: the new owner must not read the previous owner's records.
 */
interface KeyRing {
    val current: Int

    fun key(epoch: Int): ByteArray?

    /** Epoch → owner id ("" until the car is first claimed). */
    fun owners(): Map<Int, String>

    /** Gives every unclaimed epoch to [ownerId] (the first owner gets everything recorded since installation). */
    fun assignUnclaimed(ownerId: String)

    /** Starts a new epoch for [ownerId]; returns it. */
    fun rotate(ownerId: String): Int

    /** Hands every epoch of [from] to [to] (a pending takeover settled one way or the other). */
    fun reassign(from: String, to: String)
}

/** A [KeyRing] in memory (tests, the simulated car). */
class MemoryKeyRing(first: ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)) : KeyRing {
    private val keys = sortedMapOf(0 to first)
    private val owner = sortedMapOf(0 to "")
    override val current get() = keys.lastKey()
    @Synchronized override fun key(epoch: Int) = keys[epoch]
    @Synchronized override fun owners(): Map<Int, String> = owner.toMap()
    @Synchronized override fun assignUnclaimed(ownerId: String) = owner.replaceAll { _, o -> o.ifEmpty { ownerId } }
    @Synchronized override fun rotate(ownerId: String): Int {
        val next = current + 1
        keys[next] = ByteArray(32).also(SecureRandom()::nextBytes)
        owner[next] = ownerId
        return next
    }
    @Synchronized override fun reassign(from: String, to: String) = owner.replaceAll { _, o -> if (o == from) to else o }
}

/**
 * Record encryption with the newest epoch of a [KeyRing], one key per minute of [wallMillis]; reads any epoch the ring
 * holds (and the older single-key formats).
 */
class EpochRecordCipher(private val ring: KeyRing, private val wallMillis: () -> Long = System::currentTimeMillis) : RecordCipher {
    private var cached: Triple<Int, Long, AesGcmRecordCipher>? = null

    @Synchronized
    private fun minuteCipher(epoch: Int, minute: Long): AesGcmRecordCipher {
        cached?.let { (e, m, c) -> if (e == epoch && m == minute) return c }
        val root = ring.key(epoch) ?: throw LockedRecordException()
        return AesGcmRecordCipher(RecordCipher.minuteKey(root, minute)).also { cached = Triple(epoch, minute, it) }
    }

    override fun seal(plain: String, aad: String): String {
        val epoch = ring.current
        val minute = Math.floorDiv(wallMillis(), 60_000L)
        val sealed = minuteCipher(epoch, minute).seal(plain, aad)
        return RecordCipher.PREFIX_V3 + epoch + "." + minute + "." + sealed.removePrefix(RecordCipher.PREFIX)
    }

    override fun open(sealed: String, aad: String): String = when {
        sealed.startsWith(RecordCipher.PREFIX_V3) -> {
            val (epoch, minute, body) = sealed.removePrefix(RecordCipher.PREFIX_V3).split('.', limit = 3)
            minuteCipher(epoch.toInt(), minute.toLong()).open(RecordCipher.PREFIX + body, aad)
        }
        else -> {
            val key = ring.key(RecordCipher.epochOf(sealed)) ?: throw LockedRecordException()
            val body = if (sealed.startsWith(RecordCipher.PREFIX_V2)) sealed.removePrefix(RecordCipher.PREFIX_V2).substringAfter('.') else sealed.removePrefix(RecordCipher.PREFIX)
            AesGcmRecordCipher(key).open(RecordCipher.PREFIX + body, aad)
        }
    }
}

/** Reads `v3.` records with only the minute keys handed out for them ((epoch, minute) → key): a crash's minutes. */
class MinuteKeysCipher(private val keys: Map<Pair<Int, Long>, ByteArray>) : RecordCipher {
    override fun seal(plain: String, aad: String): String = throw UnsupportedOperationException("read-only")

    override fun open(sealed: String, aad: String): String {
        val minute = RecordCipher.minuteOf(sealed) ?: throw LockedRecordException()
        val key = keys[RecordCipher.epochOf(sealed) to minute] ?: throw LockedRecordException()
        return AesGcmRecordCipher(key).open(RecordCipher.PREFIX + sealed.removePrefix(RecordCipher.PREFIX_V3).split('.', limit = 3)[2], aad)
    }
}

/** AES-256-GCM with the car's root key (32 bytes). */
class AesGcmRecordCipher(key: ByteArray) : RecordCipher {
    init {
        require(key.size == 32) { "root key must be 256 bits" }
    }

    private val key = SecretKeySpec(key, "AES")
    private val random = SecureRandom()

    override fun seal(plain: String, aad: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            updateAAD(aad.toByteArray())
        }
        return RecordCipher.PREFIX + b64(nonce + c.doFinal(plain.toByteArray()))
    }

    override fun open(sealed: String, aad: String): String {
        val bytes = unb64(sealed.removePrefix(RecordCipher.PREFIX))
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
            updateAAD(aad.toByteArray())
        }
        return String(c.doFinal(bytes, 12, bytes.size - 12))
    }
}

/**
 * Public-key encryption ("sealed box") with P-256, the same construction a phone browser does with WebCrypto:
 * an ephemeral key pair, ECDH with the recipient's public key, HKDF-SHA256 (empty salt, [info]) to a 256-bit
 * AES-GCM key, a random 96-bit nonce. Format: `e1.<ephemeral public key, X.509>.<nonce‖ciphertext‖tag>`, base64.
 *
 * Used to wrap the car's root keys for the owner's phone, to send the server the crash signal copies it is allowed
 * to read, and to hand it a severe crash's minute keys for the family.
 */
object SealedBox {
    const val INFO_WRAP = "whitebox-wrap-v1"
    const val INFO_SERVER = "whitebox-server-v1"
    const val INFO_ESCROW = "whitebox-escrow-v1"
    private val random = SecureRandom()

    fun seal(recipient: ByteArray, plain: ByteArray, info: String): String {
        val eph = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val key = derive(eph.private, publicKey(recipient), info)
        val nonce = ByteArray(12).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        return "e1." + b64(eph.public.encoded) + "." + b64(nonce + c.doFinal(plain))
    }

    fun open(recipient: PrivateKey, sealed: String, info: String): ByteArray {
        val parts = sealed.split(".")
        require(parts.size == 3 && parts[0] == "e1") { "not a sealed box" }
        val key = derive(recipient, publicKey(unb64(parts[1])), info)
        val bytes = unb64(parts[2])
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, 0, 12)) }
        return c.doFinal(bytes, 12, bytes.size - 12)
    }

    private fun derive(own: PrivateKey, other: PublicKey, info: String): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(own)
            doPhase(other, true)
            generateSecret()
        }
        return hkdf(shared, info.toByteArray(), 32)
    }

    private fun publicKey(x509: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509))

    /** RFC 5869 HKDF-SHA256 with an empty salt (as WebCrypto's HKDF with a zero-length salt). */
    fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(ByteArray(32), "HmacSHA256"))
            doFinal(ikm)
        }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(prk, "HmacSHA256"))
                update(t)
                update(info)
                update(i.toByte())
                doFinal()
            }
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }
}

internal fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
internal fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
