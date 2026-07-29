package dev.pam.nativeapp.modules

import android.os.Handler
import android.os.Looper
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.atomic.AtomicBoolean

internal class TimersModule : NativeModule, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()

    override fun invoke(
        method: String,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        if (method != "after") {
            completion.complete(
                ModuleResultStatus.FAILURE,
                "Unknown timers method $method".toByteArray(),
            )
            return
        }
        if (closed.get()) {
            completion.complete(
                ModuleResultStatus.FAILURE,
                "Timers module is closed".toByteArray(),
            )
            return
        }
        runCatching {
            val delay = ((WireMap.decode(payload)["milliseconds"] as? WireValue.Integer)
                ?.value ?: 0L)
                .coerceIn(0L, 86_400_000L)
            main.postDelayed({
                if (!closed.get()) {
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
            }, delay)
        }.onFailure { error ->
            completion.complete(
                ModuleResultStatus.FAILURE,
                (error.message ?: "Timer failed").toByteArray(),
            )
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            main.removeCallbacksAndMessages(null)
        }
    }
}
