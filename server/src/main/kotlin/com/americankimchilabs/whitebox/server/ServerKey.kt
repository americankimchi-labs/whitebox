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
 * The server's own keys:
 * - a P-256 key pair: signs head anchors and dead-man seals (anyone with [publicKey] can check them), and opens what
 *   cars seal to the server (crash signal copies, capsule windows);
 * - an AES-256 data key: what the server may read is kept encrypted at rest with it.
 * Locally they are created on first start in a directory; a deployment keeps them in its secret store and passes them
 * as one value ([secret], [fromSecret]).
 */
class ServerKey private constructor(privateKey: ByteArray, val publicKey: ByteArray, private val dataKey: ByteArray) {
    private val private: PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKey))

    /** In [dir] (`server-key.pk8`, `server-key.pub`, `server-data.key`), created on first start. */
    constructor(dir: File) : this(load(dir))

    private constructor(k: Triple<ByteArray, ByteArray, ByteArray>) : this(k.first, k.second, k.third)

    companion object {
        private val b64 = Base64.getEncoder()
        private fun newKeys(): Triple<ByteArray, ByteArray, ByteArray> {
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            return Triple(pair.private.encoded, pair.public.encoded, ByteArray(32).also(SecureRandom()::nextBytes))
        }

        private fun load(dir: File): Triple<ByteArray, ByteArray, ByteArray> {
            val privateFile = File(dir.apply { mkdirs() }, "server-key.pk8")
            val publicFile = File(dir, "server-key.pub")
            val dataKeyFile = File(dir, "server-data.key")
            if (!privateFile.exists() || !dataKeyFile.exists()) {
                val (pk, pub, data) = newKeys()
                if (!privateFile.exists()) {
                    privateFile.writeBytes(pk)
                    publicFile.writeBytes(pub)
                }
                if (!dataKeyFile.exists()) dataKeyFile.writeBytes(data)
                listOf(privateFile, dataKeyFile).forEach { it.setReadable(false, false); it.setReadable(true, true) }
            }
            return Triple(privateFile.readBytes(), publicFile.readBytes(), dataKeyFile.readBytes())
        }

        /** The keys from their secret value: `private.public.data`, each base64 (PKCS#8, X.509, 32 bytes). */
        fun fromSecret(value: String): ServerKey {
            val (pk, pub, data) = value.trim().split(".").map { Base64.getDecoder().decode(it) }
            return ServerKey(pk, pub, data)
        }

        /** A new secret value, to store once in the deployment's secret store. */
        fun secret(): String = newKeys().let { (pk, pub, data) -> listOf(pk, pub, data).joinToString(".") { b64.encodeToString(it) } }
    }

    private val atRest = AesGcmRecordCipher(dataKey)

    /** Opens something a car sealed to this server. */
    fun open(sealed: String): ByteArray = SealedBox.open(private, sealed, SealedBox.INFO_SERVER)

    /** Opens a minute key of a severe capsule the car handed over (released only to the owner or a contact). */
    fun openEscrow(sealed: String): ByteArray = SealedBox.open(private, sealed, SealedBox.INFO_ESCROW)

    /** Encrypts [plain] for storage under the server's data key ([purpose] binds it to its use). */
    fun encryptAtRest(plain: String, purpose: String): String = atRest.seal(plain, purpose)

    fun decryptAtRest(stored: String, purpose: String): String = atRest.open(stored, purpose)

    /** HMAC-SHA256 under a key derived from the secret data key, for lookups that must not be reversible by guessing. */
    fun keyedHash(purpose: String, value: String): String {
        val k = SealedBox.hkdf(dataKey, "whitebox-hash|$purpose".toByteArray(), 32)
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
