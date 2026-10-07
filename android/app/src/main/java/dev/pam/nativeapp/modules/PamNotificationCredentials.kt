package dev.pam.nativeapp.modules

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-account bearer tokens for notification action endpoints, so an inline
 * reply or mark-as-read is sent as the account that received the push even
 * while PHP is not running (`{credential:user_id}` placeholders).
 *
 * Tokens are AES-GCM encrypted with a non-exportable Android Keystore key and
 * stored under a SHA-256 of the normalized account id; a token never leaves
 * this store except into the Authorization header of an action endpoint.
 */
public object PamNotificationCredentials {
    /** Encrypts/decrypts stored tokens; replaced in tests where the Keystore is unavailable. */
    internal interface Sealer {
        fun seal(plain: ByteArray): ByteArray
        fun open(sealed: ByteArray): ByteArray
    }

    private val lock = Any()

    @Volatile
    internal var sealer: Sealer = KeystoreSealer

    internal fun set(context: Context, account: String, token: String) {
        val (key, value) = sealedEntry(account, token)
        synchronized(lock) {
            preferences(context).edit().putString(key, value).commit()
        }
    }

    internal fun remove(context: Context, account: String) {
        val id = normalize(account)
        if (id.isEmpty()) return
        synchronized(lock) { preferences(context).edit().remove(slot(id)).commit() }
    }

    /** Replaces every stored credential with [tokens] (account id to token). */
    internal fun replace(context: Context, tokens: Map<String, String>) {
        // Every token is validated and sealed before the store is touched,
        // and the clear and the writes land in one commit: an invalid entry
        // leaves the previous credentials intact instead of a half-written set.
        val entries = tokens.map { (account, token) -> sealedEntry(account, token) }
        synchronized(lock) {
            preferences(context).edit().apply {
                clear()
                entries.forEach { (key, value) -> putString(key, value) }
            }.commit()
        }
    }

    private fun sealedEntry(account: String, token: String): Pair<String, String> {
        val id = normalize(account)
        require(id.isNotEmpty() && id.length <= MAX_ACCOUNT) { "Invalid credential account" }
        require(token.isNotEmpty() && token.length <= MAX_TOKEN && '\r' !in token && '\n' !in token) {
            "Invalid credential token"
        }
        val sealed = sealer.seal(token.toByteArray(Charsets.UTF_8))
        return slot(id) to Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    internal fun clear(context: Context) {
        synchronized(lock) { preferences(context).edit().clear().commit() }
    }

    /** The token of [account], or null when that account has none on this device. */
    internal fun token(context: Context, account: String): String? {
        val id = normalize(account)
        if (id.isEmpty()) return null
        val stored = synchronized(lock) { preferences(context).getString(slot(id), null) } ?: return null
        return runCatching {
            String(sealer.open(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()?.takeIf(String::isNotEmpty)
    }

    internal fun normalize(account: String): String = account.trim().lowercase()

    internal fun slot(normalized: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private object KeystoreSealer : Sealer {
        override fun seal(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            return cipher.iv + cipher.doFinal(plain)
        }

        override fun open(sealed: ByteArray): ByteArray {
            require(sealed.size > IV_BYTES) { "Invalid sealed credential" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
            return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        }

        @Synchronized
        private fun key(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "pam-native-notification-credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val PREFERENCES_NAME = "pam-native-notification-credentials"
    internal const val MAX_ACCOUNT = 128
    internal const val MAX_TOKEN = 8_192
}
