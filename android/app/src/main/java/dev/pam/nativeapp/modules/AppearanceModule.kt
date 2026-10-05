package dev.pam.nativeapp.modules

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.PamAppearance
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

/** Persists and applies the application's light/dark preference. */
internal class AppearanceModule(private val context: Context) : NativeModule {
    private val main = Handler(Looper.getMainLooper())

    override fun invoke(
        method: String,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        when (method) {
            "get" -> main.post { completion.complete(ModuleResultStatus.SUCCESS, snapshot()) }
            "set" -> {
                val mode = runCatching {
                    (WireMap.decode(payload)["mode"] as? WireValue.Integer)?.value?.toInt()
                }.getOrNull()
                if (mode == null || !PamAppearance.isValidMode(mode)) {
                    completion.complete(
                        ModuleResultStatus.FAILURE,
                        "Appearance mode must be 1 (System), 2 (Light) or 3 (Dark)".toByteArray(),
                    )
                    return
                }
                main.post {
                    val applied = runCatching {
                        (context as? PamActivity)?.setAppearanceMode(mode)
                            ?: PamAppearance.persist(context, mode).also { persisted ->
                                if (persisted) PamAppearance.applyPlatformNightMode(context, mode)
                            }
                    }.getOrDefault(false)
                    if (applied) {
                        completion.complete(ModuleResultStatus.SUCCESS, snapshot())
                    } else {
                        completion.complete(
                            ModuleResultStatus.FAILURE,
                            "Appearance preference could not be persisted".toByteArray(),
                        )
                    }
                }
            }
            else -> completion.complete(
                ModuleResultStatus.FAILURE,
                "Unknown appearance method $method".toByteArray(),
            )
        }
    }

    private fun snapshot(): ByteArray {
        val mode = PamAppearance.storedMode(context)
        return WireMap.encode(
            mapOf(
                "mode" to WireValue.Integer(mode.toLong()),
                "appearance" to WireValue.Integer(
                    if (PamAppearance.isDark(context, mode)) {
                        PamAppearance.APPEARANCE_DARK.toLong()
                    } else {
                        PamAppearance.APPEARANCE_LIGHT.toLong()
                    },
                ),
                "systemAppearance" to WireValue.Integer(
                    PamAppearance.systemAppearance(context, mode).toLong(),
                ),
            ),
        )
    }
}
