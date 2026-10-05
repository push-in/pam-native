package dev.pam.nativeapp.modules

import android.os.Handler
import android.os.Looper
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class TimersModule : NativeModule, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val pending = ConcurrentHashMap<Long, Pair<Runnable, ModuleCompletion>>()

    override fun invoke(
        method: String,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        if (method != "after" && method != "cancel") {
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
            val values = WireMap.decode(payload)
            val timer = (values["timer"] as? WireValue.Integer)?.value
            if (method == "cancel") {
                requireNotNull(timer) { "Timer id is required" }
                pending.remove(timer)?.let { (runnable, waiting) ->
                    main.removeCallbacks(runnable)
                    waiting.complete(ModuleResultStatus.FAILURE, "Timer cancelled".toByteArray())
                }
                completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                return
            }
            val delay = ((values["milliseconds"] as? WireValue.Integer)?.value ?: 0L)
                .coerceIn(0L, 86_400_000L)
            lateinit var entry: Pair<Runnable, ModuleCompletion>
            val runnable = Runnable {
                if (timer != null && !pending.remove(timer, entry)) return@Runnable
                if (!closed.get()) {
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
            }
            entry = runnable to completion
            if (timer != null) pending.put(timer, entry)?.let { (previous, _) -> main.removeCallbacks(previous) }
            main.postDelayed(runnable, delay)
        }.onFailure { error ->
            completion.complete(
                ModuleResultStatus.FAILURE,
                (error.message ?: "Timer failed").toByteArray(),
            )
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            pending.clear()
            main.removeCallbacksAndMessages(null)
        }
    }
}
