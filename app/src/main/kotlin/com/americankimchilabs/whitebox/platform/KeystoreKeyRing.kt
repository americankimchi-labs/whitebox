package com.americankimchilabs.whitebox.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.americankimchilabs.whitebox.data.KeyRing
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ALIAS = "whitebox-root-wrap"

/**
 * The car's root keys, one per epoch: 256 random bits each, made on the car and stored only wrapped by a key inside
 * the Android Keystore (which never leaves it), so the chain on disk is unreadable without this device. The owner's
 * phone and the emergency contacts receive them wrapped to their own keys. Epoch 0 is the key made at installation.
 */
class KeystoreKeyRing(context: Context) : KeyRing {
    private val dir = File(context.filesDir, "keys").apply { mkdirs() }
    private val ownersFile = File(dir, "epochs")
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val keys = sortedMapOf<Int, ByteArray>()
    private val owner = sortedMapOf<Int, String>()

    init {
        synchronized(KeystoreKeyRing::class) {
            File(dir, "root.key").takeIf { it.exists() }?.renameTo(file(0)) // the single key of earlier versions
            dir.listFiles()?.mapNotNull { f -> f.name.removePrefix("root-").removeSuffix(".key").toIntOrNull()?.let { it to f } }
                ?.forEach { (epoch, f) -> keys[epoch] = unwrap(f.readBytes()) }
            if (keys.isEmpty()) create(0)
            ownersFile.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.forEach { l -> owner[l.substringBefore('|').toInt()] = l.substringAfter('|') }
            keys.keys.forEach { owner.putIfAbsent(it, "") }
        }
    }

    override val current: Int get() = synchronized(this) { keys.lastKey() }

    @Synchronized override fun key(epoch: Int): ByteArray? = keys[epoch]

    @Synchronized override fun owners(): Map<Int, String> = owner.toMap()

    @Synchronized override fun assignUnclaimed(ownerId: String) {
        owner.replaceAll { _, o -> o.ifEmpty { ownerId } }
        saveOwners()
    }

    @Synchronized override fun rotate(ownerId: String): Int {
        val next = keys.lastKey() + 1
        create(next)
        owner[next] = ownerId
        saveOwners()
        return next
    }

    @Synchronized override fun reassign(from: String, to: String) {
        owner.replaceAll { _, o -> if (o == from) to else o }
        saveOwners()
    }

    private fun file(epoch: Int) = File(dir, "root-$epoch.key")

    private fun saveOwners() = ownersFile.writeText(owner.entries.joinToString("") { "${it.key}|${it.value}\n" })

    private fun wrapKey(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private fun create(epoch: Int) {
        val root = ByteArray(32).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrapKey()) }
        file(epoch).writeBytes(c.iv + c.doFinal(root))
        keys[epoch] = root
    }

    private fun unwrap(stored: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, stored, 0, 12)) }
        return c.doFinal(stored, 12, stored.size - 12)
    }
}
