package dev.pam.nativeapp.modules

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM with a non-exportable Android Keystore key (IV prepended). */
internal object PamKeystoreSealer {
    /** Replaced in JVM tests, where the Android Keystore is unavailable. */
    interface Sealer {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    fun forAlias(alias: String): Sealer = object : Sealer {
        override fun seal(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(alias))
            return cipher.iv + cipher.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            require(sealed.size > IV_BYTES) { "Invalid sealed value" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }
    }

    @Synchronized
    private fun key(alias: String): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
}
