package com.facevault.core.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Generates and protects the SQLCipher database passphrase using the Android
 * Keystore.
 *
 * A random 256-bit passphrase is created once, then encrypted with a
 * hardware-backed AES-256-GCM key that never leaves the Keystore. The encrypted
 * passphrase (plus its GCM IV) is stored in private [android.content.SharedPreferences].
 * The plaintext passphrase only ever exists transiently in memory when the
 * database is opened.
 *
 * @param context any context; the application context is retained.
 */
class KeystoreManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the database passphrase, generating and sealing one on first use.
     *
     * @return the raw passphrase bytes (UTF-8 of a Base64 random string).
     */
    fun getOrCreatePassphrase(): ByteArray {
        val storedCipher = prefs.getString(KEY_CIPHERTEXT, null)
        val storedIv = prefs.getString(KEY_IV, null)
        if (storedCipher != null && storedIv != null) {
            return decrypt(
                Base64.decode(storedCipher, Base64.NO_WRAP),
                Base64.decode(storedIv, Base64.NO_WRAP)
            )
        }
        return createAndStorePassphrase()
    }

    private fun createAndStorePassphrase(): ByteArray {
        // 32 random bytes, Base64-encoded into a printable passphrase.
        val random = ByteArray(PASSPHRASE_BYTES)
        java.security.SecureRandom().nextBytes(random)
        val passphrase = Base64.encode(random, Base64.NO_WRAP)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(passphrase)

        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .apply()

        return passphrase
    }

    private fun decrypt(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), spec)
        return cipher.doFinal(ciphertext)
    }

    /** Returns the Keystore-resident AES master key, creating it if absent. */
    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            MASTER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val MASTER_KEY_ALIAS = "facevault_db_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PASSPHRASE_BYTES = 32

        const val PREFS_NAME = "facevault_secure_prefs"
        const val KEY_CIPHERTEXT = "db_passphrase_ct"
        const val KEY_IV = "db_passphrase_iv"
    }
}
