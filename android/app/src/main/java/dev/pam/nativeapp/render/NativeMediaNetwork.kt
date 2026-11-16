package dev.pam.nativeapp.render

import java.io.FileNotFoundException
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * Network resilience shared by images and videos: classification of failed
 * media requests, retry delays (exponential backoff with jitter, honoring
 * `Retry-After`), a per-host pause after 429/503 and a priority work lane.
 *
 * A media API that rate limits every photo and video of a user in one bucket
 * answers 429 while a feed scrolls. Retrying at once (or giving up after two
 * immediate tries) left cells blank until they were rebound; every other image
 * of that host kept spending the bucket meanwhile.
 */

/** A media response outside 2xx/3xx. */
internal class MediaHttpException(
    val status: Int,
    val retryAfterMs: Long?,
    what: String = "Media",
) : IOException("$what request failed with HTTP $status.")

/** The host answered 429/503 a moment ago: no request is made until its pause ends. */
internal class MediaHostPausedException(
    val host: String,
    val remainingMs: Long,
) : IOException("Media host is paused for $remainingMs ms.")

/** A queued load no view wants any more (a recycled or rebound cell). */
internal class MediaNotWantedException : CancellationException("Media load is no longer wanted.")

internal const val MEDIA_RETRY_BASE_MS = 500L
internal const val MEDIA_RETRY_MAX_MS = 30_000L

/** Statuses retried with backoff; every other non-2xx status is permanent. */
internal val MEDIA_TRANSIENT_STATUS = setOf(408, 429, 502, 503, 504)

/** Statuses that pause every load of the host (rate limited or overloaded). */
internal val MEDIA_HOST_PAUSE_STATUS = setOf(429, 503)

/** Work priorities: higher first. Below zero a queued job is dropped. */
internal const val MEDIA_PRIORITY_DROP = -1
internal const val MEDIA_PRIORITY_PREFETCH = 0
internal const val MEDIA_PRIORITY_HIDDEN = 1
internal const val MEDIA_PRIORITY_NEAR = 2
internal const val MEDIA_PRIORITY_VISIBLE = 3

/** `Retry-After` in delta-seconds or an HTTP date, capped at [MEDIA_RETRY_MAX_MS]; null when absent or invalid. */
internal fun parseRetryAfterMs(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (text.all(Char::isDigit)) {
        val seconds = text.toLongOrNull() ?: return MEDIA_RETRY_MAX_MS
        return seconds.coerceAtMost(MEDIA_RETRY_MAX_MS / 1_000) * 1_000
    }
    val date = runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME) }.getOrNull()
        ?: return null
    return (date.toInstant().toEpochMilli() - nowMs).coerceIn(0, MEDIA_RETRY_MAX_MS)
}

/**
 * Backoff before retry number [attempt] (0 = first retry): 0.5 s doubling up
 * to 30 s, with equal jitter (half the step plus a random half) so a burst of
 * failed cells does not retry in the same millisecond.
 */
internal fun mediaBackoffMs(attempt: Int, random: Double): Long {
    val ceiling = (MEDIA_RETRY_BASE_MS shl attempt.coerceIn(0, 16)).coerceAtMost(MEDIA_RETRY_MAX_MS)
    return ceiling / 2 + (ceiling / 2 * random.coerceIn(0.0, 1.0)).toLong()
}

private fun retryAfterJitterMs(baseMs: Long, random: Double): Long =
    ((baseMs / 5 + 100).coerceAtMost(1_000) * random.coerceIn(0.0, 1.0)).toLong()

/** The error under CompletableFuture wrappers. */
internal fun mediaRootCause(error: Throwable?): Throwable? {
    var current = error
    while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
        current = current.cause
    }
    return current
}

/**
 * Delay before retrying a load that failed with [error] ([attempt] = retries
 * already made), or null when the failure is permanent: 4xx other than
 * 408/429, a wrong content type, an oversized or undecodable image, a
 * checksum mismatch or a missing local file.
 */
internal fun mediaRetryDelayMs(error: Throwable?, attempt: Int, random: Double): Long? =
    when (val cause = mediaRootCause(error)) {
        is MediaHttpException ->
            if (cause.status in MEDIA_TRANSIENT_STATUS) {
                cause.retryAfterMs?.let { it + retryAfterJitterMs(it, random) } ?: mediaBackoffMs(attempt, random)
            } else {
                null
            }
        is MediaHostPausedException -> cause.remainingMs + retryAfterJitterMs(cause.remainingMs, random)
        is FileNotFoundException -> null
        // Timeouts, resets, DNS and TLS hiccups of a mobile network.
        is IOException -> mediaBackoffMs(attempt, random)
        else -> null
    }

/**
 * Process-wide pause per host, shared by every image loader (render and
 * prefetch) and the video players: a 429/503 from one request holds the
 * other requests to that host for `Retry-After` (or a growing default)
 * instead of letting each of them spend the rate limit too.
 */
internal object PamMediaHostGate {
    private class State(val until: Long, val strikes: Int)

    private val hosts = ConcurrentHashMap<String, State>()

    /** Network jobs of visible or nearby views waiting for a thread, in every loader. */
    val backlog = AtomicInteger()

    @Volatile
    internal var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    fun remainingMs(host: String?): Long {
        if (host.isNullOrEmpty() || hosts.isEmpty()) return 0
        val state = hosts[host.lowercase()] ?: return 0
        return (state.until - clock()).coerceAtLeast(0)
    }

    /** [host] answered 429/503: pause it for [retryAfterMs], or 1 s doubling per strike. Returns the pause. */
    fun pause(host: String?, retryAfterMs: Long?): Long {
        if (host.isNullOrEmpty()) return 0
        val now = clock()
        var applied = 0L
        hosts.compute(host.lowercase()) { _, previous ->
            val strikes = (previous?.strikes ?: 0) + 1
            val pause = (retryAfterMs ?: mediaBackoffMs(strikes, 1.0)).coerceIn(MIN_PAUSE_MS, MEDIA_RETRY_MAX_MS)
            applied = pause
            State(maxOf(previous?.until ?: 0, now + pause), strikes)
        }
        return applied
    }

    /** A request to [host] succeeded: once its pause is over, strikes start again from zero. */
    fun succeeded(host: String?) {
        if (host.isNullOrEmpty() || hosts.isEmpty()) return
        val now = clock()
        hosts.computeIfPresent(host.lowercase()) { _, state -> if (state.until <= now) null else state }
    }

    internal fun reset() {
        hosts.clear()
        backlog.set(0)
    }

    private const val MIN_PAUSE_MS = 250L
}

/**
 * A small pool that runs the highest priority job first (FIFO within one
 * priority) and asks each job, when a thread frees, whether it is still
 * wanted and whether it must wait (a paused host). Threads start on demand
 * and exit after [keepAliveMs] idle, so an unused loader holds none.
 */
internal class MediaWorkLane(
    private val name: String,
    private val threads: Int,
    private val keepAliveMs: Long = 30_000,
) : AutoCloseable {
    abstract class Job : Runnable {
        internal var order = 0L

        /** Current priority; below zero the job is dropped. Called under the lane lock: cheap and thread-safe. */
        abstract fun priority(): Int

        /** Milliseconds the job must still wait (a paused host); zero lets it start. */
        open fun waitMs(): Long = 0

        /** Called instead of [run] when the job is dropped or the lane closes. */
        abstract fun drop()
    }

    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val queue = ArrayList<Job>()
    private val sequence = AtomicLong()
    private var workers = 0
    private var idle = 0
    private var closed = false

    val queued: Int get() = lock.withLockValue { queue.size }

    fun submit(job: Job) {
        job.order = sequence.incrementAndGet()
        val rejected = lock.withLockValue {
            if (closed) return@withLockValue true
            queue += job
            if (idle > 0) {
                ready.signal()
            } else if (workers < threads) {
                workers++
                Thread(::work, name).apply { isDaemon = true }.start()
            }
            false
        }
        if (rejected) job.drop()
    }

    /** Lets every waiting thread look at the queue again (a host pause or the demand changed). */
    fun poke() = lock.withLockValue { ready.signalAll() }

    private fun work() {
        while (true) {
            val dropped = ArrayList<Job>(0)
            var job: Job? = null
            var exit = false
            lock.lock()
            try {
                while (true) {
                    if (closed) {
                        exit = true
                        break
                    }
                    var wait = Long.MAX_VALUE
                    var bestPriority = Int.MIN_VALUE
                    val iterator = queue.iterator()
                    while (iterator.hasNext()) {
                        val candidate = iterator.next()
                        val priority = runCatching { candidate.priority() }.getOrDefault(MEDIA_PRIORITY_DROP)
                        if (priority < 0) {
                            iterator.remove()
                            dropped += candidate
                            continue
                        }
                        val blocked = runCatching { candidate.waitMs() }.getOrDefault(0)
                        if (blocked > 0) {
                            wait = minOf(wait, blocked)
                            continue
                        }
                        val best = job
                        if (best == null || priority > bestPriority || (priority == bestPriority && candidate.order < best.order)) {
                            job = candidate
                            bestPriority = priority
                        }
                    }
                    if (job != null) {
                        queue.remove(job)
                        break
                    }
                    if (dropped.isNotEmpty()) break
                    idle++
                    try {
                        if (wait == Long.MAX_VALUE) {
                            if (!ready.await(keepAliveMs, TimeUnit.MILLISECONDS) && queue.isEmpty()) exit = true
                        } else {
                            ready.await(wait, TimeUnit.MILLISECONDS)
                        }
                    } catch (_: InterruptedException) {
                        exit = true
                    } finally {
                        idle--
                    }
                    if (exit) break
                }
                if (exit) workers--
            } finally {
                lock.unlock()
            }
            dropped.forEach { runCatching { it.drop() } }
            if (exit) return
            job?.let { runCatching { it.run() } }
        }
    }

    override fun close() {
        val leftover = lock.withLockValue {
            closed = true
            ready.signalAll()
            queue.toList().also { queue.clear() }
        }
        leftover.forEach { runCatching { it.drop() } }
    }

    private inline fun <T> ReentrantLock.withLockValue(block: () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }
}
