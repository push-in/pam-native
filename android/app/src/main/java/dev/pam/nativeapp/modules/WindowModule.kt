package dev.pam.nativeapp.modules

import android.app.Activity
import android.view.WindowManager
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

/** Window-level privacy controls such as FLAG_SECURE. */
internal class WindowModule(private val activity: Activity) : NativeModule {
    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "secure" -> {
                    val enabled = (WireMap.decode(payload)["enabled"] as? WireValue.Flag)?.value
                        ?: error("Secure flag is required")
                    activity.runOnUiThread {
                        runCatching { applySecure(activity, enabled) }.fold(
                            onSuccess = { completion.secure(isSecure(activity)) },
                            onFailure = { completion.failure(it) },
                        )
                    }
                }
                "isSecure" -> activity.runOnUiThread { completion.secure(isSecure(activity)) }
                else -> error("Unknown window method $method")
            }
        }.onFailure { completion.failure(it) }
    }

    private fun ModuleCompletion.secure(enabled: Boolean) = complete(
        ModuleResultStatus.SUCCESS,
        WireMap.encode(
            mapOf("enabled" to WireValue.Flag(enabled), "supported" to WireValue.Flag(true)),
        ),
    )

    private fun ModuleCompletion.failure(error: Throwable) =
        complete(ModuleResultStatus.FAILURE, (error.message ?: "Window operation failed").toByteArray())

    internal companion object {
        fun applySecure(activity: Activity, enabled: Boolean) {
            if (enabled) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        fun isSecure(activity: Activity): Boolean =
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    }
}
