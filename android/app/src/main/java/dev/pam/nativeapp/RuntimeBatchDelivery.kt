package dev.pam.nativeapp

import android.os.Handler

/**
 * Engine patches depend on every preceding batch. Posting each interactive
 * batch at the front reverses pending deliveries while the UI thread is busy,
 * and can overtake an earlier non-interactive batch or remount as well.
 * Keep the same FIFO queue as remounts; interaction latency is handled by
 * flushing immediately inside the delivery, after its predecessors arrived.
 */
internal fun Handler.postRuntimeBatch(delivery: Runnable): Boolean = post(delivery)
