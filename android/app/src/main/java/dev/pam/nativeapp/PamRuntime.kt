package dev.pam.nativeapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.view.Choreographer
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.NativeModuleRegistry
import dev.pam.nativeapp.protocol.BatchDecoder
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.render.PamRenderer
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PamRuntime(
    private val context: Context,
    private val renderer: PamRenderer,
    private val reportError: (String) -> Unit,
    private val onFrameCommitted: (RuntimeFrameMetrics) -> Unit = {},
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private val modules = NativeModuleRegistry(context)
    private val closed = AtomicBoolean()
    private val handleLock = Any()
    private val ownedBatchHandles = ConcurrentHashMap.newKeySet<Long>()
    private val pendingBatches = ArrayDeque<PendingBatch>()
    private var frameScheduled = false
    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        flushBatches()
    }

    @Volatile
    private var handle = 0L

    fun start(entry: File, widthDp: Float, heightDp: Float) {
        synchronized(handleLock) {
            check(!closed.get()) { "Pam Runtime is closed" }
            check(handle == 0L) { "Pam Runtime is already running" }
            val stateDirectory = File(context.filesDir, "pam/state").apply {
                check(mkdirs() || isDirectory) { "Cannot create Pam Native state directory" }
            }
            handle = nativeStart(entry.absolutePath, stateDirectory.absolutePath, widthDp, heightDp)
            check(handle != 0L) { "Pam Runtime failed to start" }
        }
    }

    fun updateViewport(widthDp: Float, heightDp: Float) {
        synchronized(handleLock) {
            val active = handle
            if (active != 0L) {
                nativeRelayout(active, widthDp, heightDp)
            }
        }
    }

    fun dispatchLifecycle(kind: Int, payload: ByteArray) {
        dispatchEvent(0, kind, payload)
    }

    fun trimMemory(critical: Boolean) {
        renderer.trimMemory(critical)
    }

    fun dispatchEvent(nodeId: Long, kind: Int, payload: ByteArray = ByteArray(0)) {
        if (payload.size > MAX_PAYLOAD_BYTES) return
        synchronized(handleLock) {
            val active = handle
            if (active != 0L) {
                nativeDispatchEvent(active, nodeId, kind, payload)
            }
        }
    }

    fun dispatchBack() {
        dispatchEvent(0, EVENT_BACK)
    }

    fun reload(entryPath: String) {
        synchronized(handleLock) {
            val active = handle
            if (active != 0L) {
                nativeReload(active, entryPath)
            }
        }
    }

    fun stats(): RuntimeStats {
        val values = synchronized(handleLock) {
            val active = handle
            if (active == 0L) LongArray(10) else nativeStats(active)
        }
        return RuntimeStats(
            commits = values.getOrElse(0) { 0 },
            nodes = values.getOrElse(1) { 0 },
            created = values.getOrElse(2) { 0 },
            removed = values.getOrElse(3) { 0 },
            updated = values.getOrElse(4) { 0 },
            retainedBytes = values.getOrElse(5) { 0 },
            fullCommits = values.getOrElse(6) { 0 },
            patchCommits = values.getOrElse(7) { 0 },
            inputBytes = values.getOrElse(8) { 0 },
            outputBytes = values.getOrElse(9) { 0 },
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(handleLock) {
            val active = handle
            handle = 0L
            if (active != 0L) {
                nativeStop(active)
            }
        }
        main.removeCallbacksAndMessages(null)
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
        while (pendingBatches.isNotEmpty()) {
            releaseBatch(pendingBatches.removeFirst().handle)
        }
        ownedBatchHandles.toList().forEach(::releaseBatch)
        modules.close()
        renderer.close()
    }

    @Suppress("unused")
    private fun onNativeBatch(batch: ByteBuffer, batchHandle: Long): Boolean {
        if (batchHandle == 0L || closed.get() || !ownedBatchHandles.add(batchHandle)) return false
        val decodeStarted = System.nanoTime()
        Trace.beginSection("PamNative.decode")
        val mutations = try {
            runCatching {
                BatchDecoder.decode(batch.asReadOnlyBuffer())
            }.getOrElse { error ->
                ownedBatchHandles.remove(batchHandle)
                onNativeError(error.message ?: "Cannot decode native batch")
                return false
            }
        } finally {
            Trace.endSection()
        }
        val decodeNanos = System.nanoTime() - decodeStarted
        main.post {
            if (closed.get()) {
                releaseBatch(batchHandle)
                return@post
            }
            pendingBatches.addLast(
                PendingBatch(
                    mutations = mutations,
                    handle = batchHandle,
                    decodeNanos = decodeNanos,
                ),
            )
            scheduleFrame()
        }
        return true
    }

    @Suppress("unused")
    private fun onNativeCall(
        requestId: Long,
        module: String,
        method: String,
        payload: ByteArray,
    ) {
        modules.invoke(
            module = module,
            method = method,
            payload = payload,
            completion = ModuleCompletion { status, result ->
                synchronized(handleLock) {
                    val active = handle
                    if (active != 0L) {
                        nativeDispatchModuleResult(active, requestId, status.value, result)
                    }
                }
            },
        )
    }

    @Suppress("unused")
    private fun onNativeCallTyped(
        requestId: Long,
        operation: Int,
        payload: ByteArray,
    ) {
        modules.invoke(
            operationValue = operation,
            payload = payload,
            completion = ModuleCompletion { status, result ->
                synchronized(handleLock) {
                    val active = handle
                    if (active != 0L) {
                        nativeDispatchModuleResult(active, requestId, status.value, result)
                    }
                }
            },
        )
    }

    @Suppress("unused")
    private fun onNativeError(message: String) {
        main.post {
            if (!closed.get()) {
                reportError(message)
            }
        }
    }

    private external fun nativeStart(
        entry: String,
        stateDirectory: String,
        widthDp: Float,
        heightDp: Float,
    ): Long
    private external fun nativeRelayout(handle: Long, widthDp: Float, heightDp: Float)

    private external fun nativeDispatchEvent(
        handle: Long,
        nodeId: Long,
        eventKind: Int,
        payload: ByteArray,
    )

    private external fun nativeDispatchModuleResult(
        handle: Long,
        requestId: Long,
        status: Int,
        payload: ByteArray,
    )

    private external fun nativeReload(handle: Long, entry: String)
    private external fun nativeStats(handle: Long): LongArray
    private external fun nativeReleaseBatch(batchHandle: Long)
    private external fun nativeStop(handle: Long)

    private fun scheduleFrame() {
        if (frameScheduled || pendingBatches.isEmpty()) return
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun flushBatches() {
        if (closed.get()) {
            while (pendingBatches.isNotEmpty()) {
                releaseBatch(pendingBatches.removeFirst().handle)
            }
            return
        }

        val current = ArrayList<PendingBatch>(pendingBatches.size)
        while (pendingBatches.isNotEmpty()) {
            current += pendingBatches.removeFirst()
        }
        val started = System.nanoTime()
        var committed = false
        Trace.beginSection("PamNative.mount")
        try {
            runCatching {
                renderer.commit(current.map(PendingBatch::mutations))
            }.onSuccess {
                committed = true
            }.onFailure {
                reportError(it.message ?: "Cannot render native batch")
            }
        } finally {
            Trace.endSection()
        }
        current.forEach { batch -> releaseBatch(batch.handle) }
        val runtimeStats = stats()
        val metrics = RuntimeFrameMetrics(
            batches = current.size,
            decodeNanos = current.sumOf(PendingBatch::decodeNanos),
            mountNanos = System.nanoTime() - started,
            stats = runtimeStats,
        )
        if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "benchmark") {
            Log.d(
                PERFORMANCE_LOG_TAG,
                "batches=${metrics.batches} decodeNs=${metrics.decodeNanos} " +
                    "mountNs=${metrics.mountNanos} " +
                    "full=${runtimeStats.fullCommits} patch=${runtimeStats.patchCommits} " +
                    "inBytes=${runtimeStats.inputBytes} outBytes=${runtimeStats.outputBytes} " +
                    "buffers=${ownedBatchHandles.size}",
            )
        }
        if (committed) {
            onFrameCommitted(metrics)
        }
        scheduleFrame()
    }

    private fun releaseBatch(batchHandle: Long) {
        if (ownedBatchHandles.remove(batchHandle)) {
            nativeReleaseBatch(batchHandle)
        }
    }

    companion object {
        private const val EVENT_BACK = 3
        private const val MAX_PAYLOAD_BYTES = 1024 * 1024
        private const val PERFORMANCE_LOG_TAG = "PamNativePerf"

        init {
            System.loadLibrary("pam_native_android")
        }
    }
}

data class RuntimeStats(
    val commits: Long,
    val nodes: Long,
    val created: Long,
    val removed: Long,
    val updated: Long,
    val retainedBytes: Long,
    val fullCommits: Long,
    val patchCommits: Long,
    val inputBytes: Long,
    val outputBytes: Long,
)

data class RuntimeFrameMetrics(
    val batches: Int,
    val decodeNanos: Long,
    val mountNanos: Long,
    val stats: RuntimeStats,
)

private data class PendingBatch(
    val mutations: List<Mutation>,
    val handle: Long,
    val decodeNanos: Long,
)
