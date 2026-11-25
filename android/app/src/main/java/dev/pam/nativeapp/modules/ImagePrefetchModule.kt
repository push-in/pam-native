package dev.pam.nativeapp.modules

import android.content.Context
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.render.NativeImageLoader
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

/** Speculative disk warming: bounded across calls, shared by identity, revoked offscreen. */
internal class ImagePrefetchModule(
    context: Context?,
    private val loaderFactory: () -> PrefetchLoader = {
        val native = NativeImageLoader(requireNotNull(context))
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

    private data class Identity(val source: String, val headers: String?, val cacheKey: String?)
    private val lock = Any()
    private val sequence = AtomicLong()
    private var closed = false
    private var active = true
    private var loader: PrefetchLoader? = null
    private val pending = mutableMapOf<Identity, Job>()
    private var subscribers = 0
    private val executor = ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS, PriorityBlockingQueue()) {
        runnable -> Thread(runnable, "pam-image-prefetch").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        val requests: List<Identity>
        val priority: Int
        try {
            require(method == "prefetch") { "Unknown image method $method" }
            val values = WireMap.decode(payload)
            val urls = JSONArray((values["urls"] as? WireValue.Text)?.value ?: error("Image URLs are required"))
            require(urls.length() in 1..MAX_URLS) { "Prefetch between 1 and $MAX_URLS images" }
            priority = ((values["priority"] as? WireValue.Integer)?.value ?: 2L).toInt().coerceIn(1, 5)
            val rawHeaders = (values["headers"] as? WireValue.Text)?.value?.takeIf(String::isNotBlank)
            val headers = rawHeaders?.let { raw ->
                val input = JSONObject(raw)
                JSONObject().also { canonical ->
                    input.keys().asSequence().toList().sorted().forEach { canonical.put(it, input.getString(it)) }
                }.toString()
            }
            val keys = (values["cacheKeys"] as? WireValue.Text)?.value?.let(::JSONArray)
            requests = List(urls.length()) { index ->
                Identity(urls.getString(index), headers, keys?.optString(index)?.takeIf(String::isNotBlank))
            }
        } catch (error: Exception) {
            completion.complete(ModuleResultStatus.FAILURE, (error.message ?: "Image prefetch failed").toByteArray())
            return
        }
        val tally = Tally(requests.size, completion)
        for (identity in requests) {
            var rejected = false
            synchronized(lock) {
                val existing = pending[identity]
                if (closed || !active || subscribers >= MAX_SUBSCRIBERS || (existing == null && pending.size >= MAX_PENDING)) {
                    rejected = true
                } else {
                    val job = existing ?: Job(identity, priority, sequence.incrementAndGet()).also { pending[identity] = it }
                    if (existing != null && job.runner == null && priority > job.priority) {
                        // Reheapify queued work before changing its comparison key. A worker
                        // may already have dequeued it; that job starts without restarting.
                        val queued = executor.remove(job)
                        job.priority = priority
                        if (queued) executor.execute(job)
                    }
                    job.waiters.add(tally)
                    subscribers++
                    if (existing == null) executor.execute(job)
                }
            }
            if (rejected) tally.finish(null)
        }
    }

    /** Called only for this module's speculative loader; visible renderer loads are independent. */
    fun setActive(value: Boolean) {
        if (value) {
            synchronized(lock) { if (!closed) active = true }
        } else {
            cancelPending(close = false)
        }
    }

    override fun close() {
        cancelPending(close = true)
        executor.shutdown()
    }

    private fun cancelPending(close: Boolean) {
        val callbacks: List<Tally>
        val retired: PrefetchLoader?
        synchronized(lock) {
            if (close) closed = true
            active = false
            val jobs = pending.values.toList()
            callbacks = jobs.flatMap { it.waiters.toList() }
            jobs.forEach {
                it.waiters.clear()
                executor.remove(it)
                // Protected by the same lock that clears runner before worker reuse.
                it.runner?.interrupt()
            }
            pending.clear()
            subscribers = 0
            retired = loader
            loader = null
        }
        retired?.let { runCatching { it.close() } }
        callbacks.forEach { it.finish(null) }
    }

    private inner class Job(val identity: Identity, priority: Int, order: Long) : PrefetchTask(priority, order, {}) {
        val waiters = mutableListOf<Tally>()
        var runner: Thread? = null
        override fun run() {
            var result: Long? = null
            try {
                val current = synchronized(lock) {
                    if (closed || !active || pending[identity] !== this) return
                    runner = Thread.currentThread()
                    loader ?: loaderFactory().also { loader = it }
                }
                result = current.prefetch(identity.source, identity.headers, identity.cacheKey)
            } catch (_: Exception) {
                // Cancellation, overflow and download failure all settle the existing failed count.
            } finally {
                val callbacks = synchronized(lock) {
                    runner = null
                    Thread.interrupted()
                    if (pending[identity] === this) {
                        pending.remove(identity)
                        subscribers -= waiters.size
                        waiters.toList().also { waiters.clear() }
                    } else emptyList()
                }
                callbacks.forEach { it.finish(result) }
            }
        }
    }

    private class Tally(count: Int, private val completion: ModuleCompletion) {
        private val remaining = AtomicInteger(count)
        private val succeeded = AtomicInteger()
        private val failed = AtomicInteger()
        private val bytes = AtomicLong()
        fun finish(value: Long?) {
            if (value == null) failed.incrementAndGet() else {
                succeeded.incrementAndGet()
                bytes.addAndGet(value)
            }
            if (remaining.decrementAndGet() == 0) completion.complete(ModuleResultStatus.SUCCESS, WireMap.encode(mapOf(
                "succeeded" to WireValue.Integer(succeeded.get().toLong()),
                "failed" to WireValue.Integer(failed.get().toLong()),
                "bytes" to WireValue.Integer(bytes.get()),
            )))
        }
    }

    /** Higher media priority first; FIFO within a priority. */
    internal open class PrefetchTask(var priority: Int, val order: Long, private val work: () -> Unit) : Runnable, Comparable<PrefetchTask> {
        override fun run() = work()
        override fun compareTo(other: PrefetchTask): Int = compareValuesBy(this, other, { -it.priority }, { it.order })
    }

    internal companion object {
        const val WORKERS = 2
        const val MAX_URLS = 100
        const val MAX_PENDING = 128
        const val MAX_SUBSCRIBERS = 512
    }
}
