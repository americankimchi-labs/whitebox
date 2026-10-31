package com.americankimchilabs.whitebox.server

import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The server's own signing key (ECDSA P-256, like the cars'): it signs head anchors and dead-man seals, so a
 * server-attested statement can be checked by anyone with [publicKey]. Created on first start in [dir]; a deployment
 * provides it from its secret store instead of the local disk.
 */
class ServerKey(dir: File) {
    private val privateFile = File(dir.apply { mkdirs() }, "server-key.pk8")
    private val publicFile = File(dir, "server-key.pub")
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
    }

    fun sign(text: String): String = Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run {
            initSign(private)
            update(text.toByteArray())
            sign()
        },
    )
}
