package com.americankimchilabs.whitebox.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.americankimchilabs.whitebox.data.Signer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

private const val ALIAS = "whitebox-device-key"

/**
 * Device signing key in the Android Keystore: ECDSA P-256 / SHA256withECDSA, the same algorithm the JVM tests use.
 * The private key never leaves the keystore. Re-keying to the phone identity comes with the phone claim.
 */
class KeystoreSigner : Signer {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!keyStore.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
        }
    }

    override val publicKey: ByteArray = keyStore.getCertificate(ALIAS).publicKey.encoded

    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(keyStore.getKey(ALIAS, null) as PrivateKey)
        update(data)
        sign()
    }
}
