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
 * Encrypts the values of chain records. Only the car (holding the root key) and the people it is wrapped for — the
 * owner's phone and the emergency contacts — can read them; the server stores and checks the records without.
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

        fun isSealed(field: String) = field.startsWith(PREFIX) || field.startsWith(PREFIX_V2)

        /** The key epoch of an encrypted field. */
        fun epochOf(field: String): Int = if (field.startsWith(PREFIX_V2)) field.removePrefix(PREFIX_V2).substringBefore('.').toInt() else 0
    }
}

/**
 * The car's root keys, one per epoch, and whose each epoch is (the owner it was made for, empty before the first
 * claim). The newest epoch encrypts; older ones stay to read older records. A new epoch starts when the car changes
 * owner (the new owner must not read the previous owner's records) or when an emergency contact is removed (they
 * must not read what comes after).
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

/** Record encryption with the newest epoch of a [KeyRing]; reads any epoch the ring holds. */
class EpochRecordCipher(private val ring: KeyRing) : RecordCipher {
    override fun seal(plain: String, aad: String): String {
        val epoch = ring.current
        val sealed = AesGcmRecordCipher(ring.key(epoch)!!).seal(plain, aad)
        return RecordCipher.PREFIX_V2 + epoch + "." + sealed.removePrefix(RecordCipher.PREFIX)
    }

    override fun open(sealed: String, aad: String): String {
        val epoch = RecordCipher.epochOf(sealed)
        val key = ring.key(epoch) ?: throw LockedRecordException()
        val body = if (sealed.startsWith(RecordCipher.PREFIX_V2)) sealed.removePrefix(RecordCipher.PREFIX_V2).substringAfter('.') else sealed.removePrefix(RecordCipher.PREFIX)
        return AesGcmRecordCipher(key).open(RecordCipher.PREFIX + body, aad)
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
 * Used to wrap the car's root key for the owner's phone and each emergency contact, and to send the server the
 * crash signal copies it is allowed to read.
 */
object SealedBox {
    const val INFO_WRAP = "whitebox-wrap-v1"
    const val INFO_SERVER = "whitebox-server-v1"
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
