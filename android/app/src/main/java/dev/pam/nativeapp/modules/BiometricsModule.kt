package dev.pam.nativeapp.modules

import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

/**
 * System biometric prompt (fingerprint / face / iris) through the platform
 * BiometricPrompt (Android 10+). `status` reports availability and the
 * hardware kind; `authenticate` shows the prompt and answers whether the user
 * passed it. Cancelling, the negative button or a lockout answer false.
 */
internal class BiometricsModule(private val activity: Activity) : NativeModule {
    @Volatile
    private var pending: CancellationSignal? = null

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "status" -> completion.complete(ModuleResultStatus.SUCCESS, statusSnapshot())
                "authenticate" -> {
                    val values = WireMap.decode(payload)
                    val title = (values["title"] as? WireValue.Text)?.value?.takeIf(String::isNotBlank)
                        ?: error("Biometric prompt title is required")
                    val cancel = (values["cancelLabel"] as? WireValue.Text)?.value?.takeIf(String::isNotBlank)
                        ?: error("Biometric prompt cancel label is required")
                    val subtitle = (values["subtitle"] as? WireValue.Text)?.value?.takeIf(String::isNotBlank)
                    activity.runOnUiThread { authenticate(title, subtitle, cancel, completion) }
                }
                else -> error("Unknown biometrics method $method")
            }
        }.onFailure {
            completion.complete(ModuleResultStatus.FAILURE, (it.message ?: "Biometric operation failed").toByteArray())
        }
    }

    private fun authenticate(title: String, subtitle: String?, cancel: String, completion: ModuleCompletion) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !available()) {
            completion.complete(ModuleResultStatus.SUCCESS, result(false, ERROR_UNAVAILABLE))
            return
        }
        pending?.cancel()
        val signal = CancellationSignal()
        pending = signal
        var settled = false
        val settle = { passed: Boolean, error: Int ->
            if (!settled) {
                settled = true
                if (pending === signal) pending = null
                completion.complete(ModuleResultStatus.SUCCESS, result(passed, error))
            }
        }
        val executor = activity.mainExecutor
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(title)
            .setNegativeButton(cancel, executor) { _, _ -> settle(false, ERROR_CANCELLED) }
        subtitle?.let(builder::setSubtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        }
        runCatching {
            builder.build().authenticate(
                signal,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) =
                        settle(true, ERROR_NONE)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                        settle(
                            false,
                            if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT ||
                                errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT
                            ) {
                                ERROR_LOCKOUT
                            } else {
                                ERROR_CANCELLED
                            },
                        )
                },
            )
        }.onFailure { settle(false, ERROR_UNAVAILABLE) }
    }

    private fun available(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val manager = activity.getSystemService(BiometricManager::class.java) ?: return false
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        return status == BiometricManager.BIOMETRIC_SUCCESS
    }

    /** BiometricKind: 1 none, 2 fingerprint, 3 face, 4 iris, 5 other. */
    private fun kind(): Int {
        if (!available()) return KIND_NONE
        val features = activity.packageManager
        return when {
            features.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) -> KIND_FINGERPRINT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                features.hasSystemFeature(PackageManager.FEATURE_FACE) -> KIND_FACE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                features.hasSystemFeature(PackageManager.FEATURE_IRIS) -> KIND_IRIS
            else -> KIND_OTHER
        }
    }

    private fun statusSnapshot(): ByteArray {
        val kind = kind()
        return WireMap.encode(
            mapOf(
                "available" to WireValue.Flag(kind != KIND_NONE),
                "kind" to WireValue.Integer(kind.toLong()),
            ),
        )
    }

    private fun result(passed: Boolean, error: Int) = WireMap.encode(
        mapOf(
            "authenticated" to WireValue.Flag(passed),
            "error" to WireValue.Integer(error.toLong()),
        ),
    )

    internal companion object {
        const val KIND_NONE = 1
        const val KIND_FINGERPRINT = 2
        const val KIND_FACE = 3
        const val KIND_IRIS = 4
        const val KIND_OTHER = 5

        /** BiometricError: 1 none, 2 cancelled, 3 unavailable, 4 lockout. */
        const val ERROR_NONE = 1
        const val ERROR_CANCELLED = 2
        const val ERROR_UNAVAILABLE = 3
        const val ERROR_LOCKOUT = 4
    }
}
