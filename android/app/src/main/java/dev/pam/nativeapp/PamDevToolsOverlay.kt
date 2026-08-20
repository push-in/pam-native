package dev.pam.nativeapp

import android.content.Context
import android.graphics.Color
import android.os.Debug
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject

enum class RuntimeDiagnosticKind(val value: Int) {
    MODULE_CALL(1),
    EVENT(2),
    ERROR(3),
    LIFECYCLE(4),
    NETWORK(5),
    HOT_RELOAD(6),
}

enum class RuntimeHttpMethod(val value: Int) {
    GET(1),
    POST(2),
    PUT(3),
    PATCH(4),
    DELETE(5),
}

data class RuntimeDiagnostic(
    val kind: RuntimeDiagnosticKind,
    val label: String,
    val durationNanos: Long = 0,
    val failed: Boolean = false,
    val methodCode: Int? = null,
    val statusCode: Int? = null,
    val requestBytes: Int? = null,
    val responseBytes: Int? = null,
)

internal class PamDevToolsOverlay(context: Context) : FrameLayout(context) {
    private val text = TextView(context).apply {
        setTextColor(Color.WHITE)
        setBackgroundColor(0xE6121720.toInt())
        textSize = 11f
        gravity = Gravity.START
        setPadding(dp(10), dp(8), dp(10), dp(8))
        contentDescription = "Pam Native DevTools"
    }
    private var visible = false
    private var smoothedFps = 0.0
    private var frameWindowStarted = 0L
    private var frameCount = 0
    private var latestMetrics: RuntimeFrameMetrics? = null
    private val diagnostics = ArrayDeque<RuntimeDiagnostic>()
    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!visible) return
            if (frameWindowStarted == 0L) frameWindowStarted = frameTimeNanos
            frameCount++
            val elapsed = frameTimeNanos - frameWindowStarted
            if (elapsed >= FPS_WINDOW_NANOS) {
                val measured = frameCount * 1_000_000_000.0 / elapsed
                smoothedFps = if (smoothedFps == 0.0) {
                    measured
                } else {
                    smoothedFps * 0.7 + measured * 0.3
                }
                frameWindowStarted = frameTimeNanos
                frameCount = 0
                renderMetrics()
            }
            choreographer.postFrameCallback(this)
        }
    }

    init {
        visibility = View.GONE
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(
            text,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = dp(12)
                marginEnd = dp(12)
            },
        )
    }

    fun toggle(): Boolean {
        visible = !visible
        visibility = if (visible) View.VISIBLE else View.GONE
        importantForAccessibility = if (visible) {
            IMPORTANT_FOR_ACCESSIBILITY_YES
        } else {
            IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (visible) {
            frameWindowStarted = 0L
            frameCount = 0
            choreographer.postFrameCallback(frameCallback)
            renderMetrics()
        } else {
            choreographer.removeFrameCallback(frameCallback)
        }
        return visible
    }

    fun update(metrics: RuntimeFrameMetrics) {
        latestMetrics = metrics
        if (visible) renderMetrics()
    }

    fun record(diagnostic: RuntimeDiagnostic) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            appendDiagnostic(diagnostic)
        } else {
            post { appendDiagnostic(diagnostic) }
        }
    }

    fun snapshotJson(capturedAtUnixMs: Long = System.currentTimeMillis()): String {
        val metrics = latestMetrics
        val stats = metrics?.stats
        val timeline = JSONArray()
        diagnostics.forEach { item ->
            val encoded = JSONObject()
                .put("kindCode", item.kind.value)
                .put("durationMicros", item.durationNanos / 1_000)
                .put("failed", item.failed)
            item.methodCode?.let { encoded.put("methodCode", it) }
            item.statusCode?.let { encoded.put("statusCode", it) }
            item.requestBytes?.let { encoded.put("requestBytes", it) }
            item.responseBytes?.let { encoded.put("responseBytes", it) }
            timeline.put(encoded)
        }
        return JSONObject()
            .put("schemaVersion", 1)
            .put("surfaceCode", 2)
            .put("capturedAtUnixMs", capturedAtUnixMs)
            .put("platformCode", 1)
            .put("framesPerSecond", smoothedFps)
            .put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
            .put(
                "frame",
                JSONObject()
                    .put("batches", metrics?.batches ?: 0)
                    .put("decodeMicros", (metrics?.decodeNanos ?: 0) / 1_000)
                    .put("mountMicros", (metrics?.mountNanos ?: 0) / 1_000)
                    .put("nodes", stats?.nodes ?: 0)
                    .put("measuredFrames", stats?.measuredFrames ?: 0)
                    .put("deadlineMisses", stats?.deadlineMisses ?: 0)
                    .put("patchCommits", stats?.patchCommits ?: 0)
                    .put("fullCommits", stats?.fullCommits ?: 0)
                    .put("decodeP95Micros", stats?.decodeP95Micros ?: 0)
                    .put("reconcileP95Micros", stats?.reconcileP95Micros ?: 0)
                    .put("layoutP95Micros", stats?.layoutP95Micros ?: 0)
                    .put("encodeP95Micros", stats?.encodeP95Micros ?: 0),
            )
            .put("timeline", timeline)
            .toString()
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    private fun renderMetrics() {
        val metrics = latestMetrics ?: return
        val stats = metrics.stats
        val heapMiB = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
        val summary = String.format(
            Locale.US,
            "PAM  %.0f fps\nmount %.2f ms  host decode %.2f ms\nengine p95 d/r/l/e %d/%d/%d/%d µs\nframes %d  deadline misses %d\nnodes %d  batches %d\npatch %d  full %d  coalesced %d\nbuffer reuse %d · %.1f MiB\nnative heap %.1f MiB",
            smoothedFps,
            metrics.mountNanos / 1_000_000.0,
            metrics.decodeNanos / 1_000_000.0,
            stats.decodeP95Micros,
            stats.reconcileP95Micros,
            stats.layoutP95Micros,
            stats.encodeP95Micros,
            stats.measuredFrames,
            stats.deadlineMisses,
            stats.nodes,
            metrics.batches,
            stats.patchCommits,
            stats.fullCommits,
            stats.coalescedCommands,
            stats.bufferReuses,
            stats.reusedBufferBytes / (1024.0 * 1024.0),
            heapMiB,
        )
        val timeline = diagnostics.joinToString(separator = "\n") { item ->
            val prefix = if (item.failed) "FAIL" else when (item.kind) {
                RuntimeDiagnosticKind.MODULE_CALL -> "CALL"
                RuntimeDiagnosticKind.EVENT -> "EVNT"
                RuntimeDiagnosticKind.ERROR -> "ERR "
                RuntimeDiagnosticKind.LIFECYCLE -> "LIFE"
                RuntimeDiagnosticKind.NETWORK -> "NET "
                RuntimeDiagnosticKind.HOT_RELOAD -> "LOAD"
            }
            if (item.durationNanos > 0) {
                String.format(Locale.US, "%s %6.1fms  %s", prefix, item.durationNanos / 1_000_000.0, item.label)
            } else {
                "$prefix          ${item.label}"
            }
        }
        text.text = if (timeline.isEmpty()) summary else "$summary\n\nCAPABILITIES\n$timeline"
    }

    private fun appendDiagnostic(diagnostic: RuntimeDiagnostic) {
        if (diagnostics.size >= MAX_DIAGNOSTICS) diagnostics.removeFirst()
        diagnostics.addLast(diagnostic)
        if (visible) renderMetrics()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val FPS_WINDOW_NANOS = 500_000_000L
        const val MAX_DIAGNOSTICS = 8
    }
}
