package dev.pam.nativeapp.modules

import android.content.Context
import android.util.Base64
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

/**
 * Small secrets (PIN hashes, tokens) AES-GCM encrypted with a non-exportable
 * Android Keystore key, stored under a SHA-256 of the key name. Values never
 * reach the plain `storage` module or backups in clear text.
 */
internal class SecureStorageModule(private val context: Context) : NativeModule {
    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            val values = WireMap.decode(payload)
            val key = (values["key"] as? WireValue.Text)?.value
                ?.takeIf { it.isNotEmpty() && it.length <= PamSecureStore.MAX_KEY }
                ?: error("Secure storage key is required")
            when (method) {
                "get" -> {
                    val value = PamSecureStore.get(context, key)
                    completion.complete(
                        ModuleResultStatus.SUCCESS,
                        WireMap.encode(
                            mapOf(
                                "found" to WireValue.Flag(value != null),
                                "value" to WireValue.Text(value ?: ""),
                            ),
                        ),
                    )
                }
                "set" -> {
                    val value = (values["value"] as? WireValue.Text)?.value ?: error("Secure storage value is required")
                    require(PamSecureStore.set(context, key, value)) { "Secure value could not be stored" }
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                "delete" -> {
                    PamSecureStore.delete(context, key)
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                else -> error("Unknown secure storage method $method")
            }
        }.onFailure {
            completion.complete(ModuleResultStatus.FAILURE, (it.message ?: "Secure storage failed").toByteArray())
        }
    }
}

/** Keystore-sealed key/value store behind the `secure-storage` module. */
internal object PamSecureStore {
    const val MAX_KEY = 256
    const val MAX_VALUE = 65_536
    private const val PREFERENCES_NAME = "pam-native-secure-storage"
    private const val KEY_ALIAS = "pam-native-secure-storage"
    private val lock = Any()

    @Volatile
    internal var sealer: PamKeystoreSealer.Sealer = PamKeystoreSealer.forAlias(KEY_ALIAS)

    fun get(context: Context, key: String): String? {
        val stored = synchronized(lock) { preferences(context).getString(slot(key), null) } ?: return null
        return runCatching {
            String(sealer.open(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    fun set(context: Context, key: String, value: String): Boolean {
        require(value.length <= MAX_VALUE) { "Secure values are limited to $MAX_VALUE characters" }
        val sealed = Base64.encodeToString(sealer.seal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return synchronized(lock) { preferences(context).edit().putString(slot(key), sealed).commit() }
    }

    fun delete(context: Context, key: String) {
        synchronized(lock) { preferences(context).edit().remove(slot(key)).commit() }
    }

    internal fun slot(key: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
