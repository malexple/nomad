package org.nomad.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.nomad.crypto.StateVault

/**
 * The 32-byte master key of the state file. It is random, stored only in wrapped form (AES-GCM under a key that
 * lives in the Android Keystore and never leaves it) in a private file.
 *
 * If the Keystore key is gone (factory reset, restored backup, a replaced lock screen on some devices) unwrapping
 * fails with an exception. That must reach the user: silently creating a new identity would hide the loss.
 */
object KeystoreMasterKey {
    private const val ALIAS = "nomad_master_wrap_v1"
    private const val FILE_NAME = "master_key.bin"
    private const val PROVIDER = "AndroidKeyStore"

    @Synchronized
    fun getOrCreate(context: Context): ByteArray {
        val file = File(context.filesDir, FILE_NAME)
        val wrapKey = wrapKey()
        if (file.exists()) {
            return unwrap(wrapKey, file.readBytes())
        }
        val master = StateVault.newKey()
        file.writeBytes(wrap(wrapKey, master))
        return master
    }

    private fun wrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrap(key: SecretKey, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + ciphertext
    }

    private fun unwrap(key: SecretKey, data: ByteArray): ByteArray {
        val ivLength = data[0].toInt() and 0xff
        val iv = data.copyOfRange(1, 1 + ivLength)
        val ciphertext = data.copyOfRange(1 + ivLength, data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }
}
