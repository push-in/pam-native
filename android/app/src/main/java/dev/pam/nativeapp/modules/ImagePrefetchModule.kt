package dev.pam.nativeapp.modules

import android.content.Context
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.render.NativeImageLoader
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray

/**
 * Downloads remote images into the renderer's disk cache (pam-images-v1) so a
 * later Image with the same URL, headers and cache key renders from disk.
 */
internal class ImagePrefetchModule(
    context: Context,
    private val loader: Lazy<PrefetchLoader> = lazy {
        val native = NativeImageLoader(context)
        object : PrefetchLoader {
            override fun prefetch(source: String, headers: String?, cacheKey: String?): Long =
                native.prefetch(source, headers, cacheKey)

            override fun close() = native.close()
        }
    },
) : NativeModule, AutoCloseable {
    interface PrefetchLoader : AutoCloseable {
        fun prefetch(source: String, headers: String?, cacheKey: String?): Long
    }

    private val sequence = AtomicLong()
    private val closed = AtomicBoolean()
    private val executor = ThreadPoolExecutor(
        WORKERS,
        WORKERS,
        30,
        TimeUnit.SECONDS,
        PriorityBlockingQueue(),
    ) { runnable -> Thread(runnable, "pam-image-prefetch").apply { isDaemon = true } }.apply {
        allowCoreThreadTimeOut(true)
    }

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            require(method == "prefetch") { "Unknown image method $method" }
            check(!closed.get()) { "Image prefetch module is closed" }
            val values = WireMap.decode(payload)
            val urls = JSONArray((values["urls"] as? WireValue.Text)?.value ?: error("Image URLs are required"))
            require(urls.length() in 1..MAX_URLS) { "Prefetch between 1 and $MAX_URLS images" }
            val priority = ((values["priority"] as? WireValue.Integer)?.value ?: 2L).toInt().coerceIn(1, 5)
            val headers = (values["headers"] as? WireValue.Text)?.value?.takeIf(String::isNotBlank)
            val cacheKeys = (values["cacheKeys"] as? WireValue.Text)?.value?.let(::JSONArray)
            val succeeded = AtomicInteger()
            val failed = AtomicInteger()
            val bytes = AtomicLong()
            val finished = AtomicInteger(urls.length())
            for (index in 0 until urls.length()) {
                val source = urls.getString(index)
                val cacheKey = cacheKeys?.optString(index)?.takeIf(String::isNotBlank)
                executor.execute(
                    PrefetchTask(priority, sequence.incrementAndGet()) {
                        runCatching {
                            check(!closed.get()) { "Image prefetch module is closed" }
                            loader.value.prefetch(source, headers, cacheKey)
                        }.fold(
                            onSuccess = { succeeded.incrementAndGet(); bytes.addAndGet(it) },
                            onFailure = { failed.incrementAndGet() },
                        )
                        if (finished.decrementAndGet() == 0) {
                            completion.complete(
                                ModuleResultStatus.SUCCESS,
                                WireMap.encode(
                                    mapOf(
                                        "succeeded" to WireValue.Integer(succeeded.get().toLong()),
                                        "failed" to WireValue.Integer(failed.get().toLong()),
                                        "bytes" to WireValue.Integer(bytes.get()),
                                    ),
                                ),
                            )
                        }
                    },
                )
            }
        }.onFailure {
            completion.complete(ModuleResultStatus.FAILURE, (it.message ?: "Image prefetch failed").toByteArray())
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
        if (loader.isInitialized()) runCatching { loader.value.close() }
    }

    /** Higher MediaPriority first; FIFO within one priority. */
    internal class PrefetchTask(
        val priority: Int,
        val order: Long,
        private val work: () -> Unit,
    ) : Runnable, Comparable<PrefetchTask> {
        override fun run() = work()

        override fun compareTo(other: PrefetchTask): Int =
            compareValuesBy(this, other, { -it.priority }, { it.order })
    }

    internal companion object {
        const val WORKERS = 2
        const val MAX_URLS = 100
    }
}
