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

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    private fun renderMetrics() {
        val metrics = latestMetrics ?: return
        val stats = metrics.stats
        val heapMiB = Debug.getNativeHeapAllocatedSize() / (1024.0 * 1024.0)
        text.text = String.format(
            Locale.US,
            "PAM  %.0f fps\nmount %.2f ms  decode %.2f ms\nnodes %d  batches %d\npatch %d  full %d\nnative heap %.1f MiB",
            smoothedFps,
            metrics.mountNanos / 1_000_000.0,
            metrics.decodeNanos / 1_000_000.0,
            stats.nodes,
            metrics.batches,
            stats.patchCommits,
            stats.fullCommits,
            heapMiB,
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val FPS_WINDOW_NANOS = 500_000_000L
    }
}
