package com.americankimchilabs.whitebox.server

import com.americankimchilabs.whitebox.data.AesGcmRecordCipher
import com.americankimchilabs.whitebox.data.SealedBox
import java.io.File
import java.security.SecureRandom
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The server's own keys, created on first start in [dir] (a deployment provides them from its secret store instead):
 * - a P-256 key pair: signs head anchors and dead-man seals (anyone with [publicKey] can check them), and opens what
 *   cars seal to the server (crash signal copies, capsule windows);
 * - an AES-256 data key: what the server may read is kept encrypted at rest with it.
 */
class ServerKey(dir: File) {
    private val privateFile = File(dir.apply { mkdirs() }, "server-key.pk8")
    private val publicFile = File(dir, "server-key.pub")
    private val dataKeyFile = File(dir, "server-data.key")
    private val private: PrivateKey
    val publicKey: ByteArray

    init {
        if (!privateFile.exists()) {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            privateFile.writeBytes(pair.private.encoded)
            privateFile.setReadable(false, false)
            privateFile.setReadable(true, true)
            publicFile.writeBytes(pair.public.encoded)
        }
        private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
        publicKey = publicFile.readBytes()
        if (!dataKeyFile.exists()) {
            dataKeyFile.writeBytes(ByteArray(32).also(SecureRandom()::nextBytes))
            dataKeyFile.setReadable(false, false)
            dataKeyFile.setReadable(true, true)
        }
    }

    private val atRest = AesGcmRecordCipher(dataKeyFile.readBytes())

    /** Opens something a car sealed to this server. */
    fun open(sealed: String): ByteArray = SealedBox.open(private, sealed, SealedBox.INFO_SERVER)

    /** Opens a minute key of a severe capsule the car handed over (released only to the owner or a contact). */
    fun openEscrow(sealed: String): ByteArray = SealedBox.open(private, sealed, SealedBox.INFO_ESCROW)

    /** Encrypts [plain] for storage under the server's data key ([purpose] binds it to its use). */
    fun encryptAtRest(plain: String, purpose: String): String = atRest.seal(plain, purpose)

    fun decryptAtRest(stored: String, purpose: String): String = atRest.open(stored, purpose)

    /** HMAC-SHA256 under a key derived from the secret data key, for lookups that must not be reversible by guessing. */
    fun keyedHash(purpose: String, value: String): String {
        val k = SealedBox.hkdf(dataKeyFile.readBytes(), "whitebox-hash|$purpose".toByteArray(), 32)
        return javax.crypto.Mac.getInstance("HmacSHA256").run {
            init(javax.crypto.spec.SecretKeySpec(k, "HmacSHA256"))
            doFinal(value.toByteArray())
        }.joinToString("") { "%02x".format(it) }
    }

    fun sign(text: String): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run {
            initSign(private)
            update(text.toByteArray())
            sign()
        },
    )
}
