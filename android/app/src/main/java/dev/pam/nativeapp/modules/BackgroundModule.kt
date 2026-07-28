package dev.pam.nativeapp.modules

import android.content.Context
import android.os.PowerManager
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.concurrent.atomic.AtomicInteger

internal class BackgroundModule(private val context: Context) : NativeModule, AutoCloseable {
    private val nextToken = AtomicInteger(1)
    private val locks = mutableMapOf<Int, PowerManager.WakeLock>()

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "begin" -> {
                    val values = WireMap.decode(payload)
                    val timeout = ((values["timeoutSeconds"] as? WireValue.Integer)?.value ?: 30L)
                        .coerceIn(1, 600)
                    val name = (values["name"] as? WireValue.Text)?.value.orEmpty().take(128)
                    val token = nextToken.getAndIncrement()
                    val lock = context.getSystemService(PowerManager::class.java)
                        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PamNative:$name")
                    lock.acquire(timeout * 1_000)
                    synchronized(locks) { locks[token] = lock }
                    completion.complete(
                        ModuleResultStatus.SUCCESS,
                        WireMap.encode(mapOf("token" to WireValue.Integer(token.toLong()))),
                    )
                }
                "end" -> {
                    val token = (WireMap.decode(payload)["token"] as? WireValue.Integer)?.value?.toInt()
                        ?: error("Missing background token")
                    synchronized(locks) { locks.remove(token) }?.let {
                        if (it.isHeld) it.release()
                    }
                    completion.complete(ModuleResultStatus.SUCCESS, ByteArray(0))
                }
                else -> error("Unknown background method $method")
            }
        }.onFailure { error ->
            completion.complete(ModuleResultStatus.FAILURE, (error.message ?: "Background task failed").toByteArray())
        }
    }

    override fun close() {
        synchronized(locks) {
            locks.values.forEach { if (it.isHeld) it.release() }
            locks.clear()
        }
    }
}
