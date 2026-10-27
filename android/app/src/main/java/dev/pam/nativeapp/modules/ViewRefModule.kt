package dev.pam.nativeapp.modules

import android.os.Handler
import android.os.Looper
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.render.PamRenderer

/**
 * `ViewRef`: imperative operations on a mounted view found by its
 * `nativeRef` (React Native refs): `focus`, `blur`, `clear`, `setSelection`,
 * `isFocused` on text inputs and `measure`/`measureInWindow` on any view.
 * Every call runs once on the UI thread; nothing is observed afterwards.
 */
internal class ViewRefModule(
    private val renderer: () -> PamRenderer?,
) : NativeModule {
    private val main = Handler(Looper.getMainLooper())

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        val values = runCatching { WireMap.decode(payload) }.getOrElse {
            completion.failure("Invalid view ref request")
            return
        }
        val ref = (values["ref"] as? WireValue.Text)?.value.orEmpty()
        if (!REF.matches(ref)) {
            completion.failure("A valid nativeRef is required.")
            return
        }
        main.post {
            runCatching {
                val target = renderer() ?: error("No active surface.")
                when (method) {
                    "focus", "blur", "clear" -> {
                        target.nativeRefInputOperation(ref, method)
                        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                    }
                    "setSelection" -> {
                        target.nativeRefInputOperation(
                            ref,
                            method,
                            values.integer("start"),
                            values.integer("end"),
                        )
                        completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                    }
                    "isFocused" -> completion.complete(
                        ModuleResultStatus.SUCCESS,
                        WireMap.encode(mapOf("focused" to WireValue.Flag(target.nativeRefInputFocused(ref)))),
                    )
                    "measure" -> completion.complete(ModuleResultStatus.SUCCESS, encodeFrame(target.nativeRefFrame(ref)))
                    else -> error("Unknown view ref method $method")
                }
            }.onFailure { completion.failure(it.message ?: "View ref $method failed.") }
        }
    }

    private fun Map<String, WireValue>.integer(key: String): Int =
        ((this[key] as? WireValue.Integer)?.value ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun ModuleCompletion.failure(message: String) =
        complete(ModuleResultStatus.FAILURE, message.toByteArray())

    internal companion object {
        private val REF = Regex("^[A-Za-z0-9_.:-]{1,64}$")

        /** `x`,`y` in the parent; `windowX`/`windowY`; `pageX`/`pageY` in the app root; all dp. */
        fun encodeFrame(frame: DoubleArray): ByteArray = WireMap.encode(
            mapOf(
                "x" to WireValue.Decimal(frame[0]),
                "y" to WireValue.Decimal(frame[1]),
                "width" to WireValue.Decimal(frame[2]),
                "height" to WireValue.Decimal(frame[3]),
                "windowX" to WireValue.Decimal(frame[4]),
                "windowY" to WireValue.Decimal(frame[5]),
                "pageX" to WireValue.Decimal(frame[6]),
                "pageY" to WireValue.Decimal(frame[7]),
            ),
        )
    }
}
