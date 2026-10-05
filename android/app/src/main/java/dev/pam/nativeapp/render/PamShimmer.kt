package dev.pam.nativeapp.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import kotlin.math.roundToInt

/**
 * Skeleton shimmer for `<Shimmer>`: a soft highlight strip 1.25× the box
 * width sweeps left → right once per duration over the background and below
 * the children. All shimmers share one Choreographer callback and pause
 * while detached, hidden or scrolled off screen.
 */
internal class PamShimmer(private val host: View) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val matrix = Matrix()
    private val visible = Rect()
    private var shader: LinearGradient? = null
    private var shaderWidth = 0
    private var color = DEFAULT_COLOR
    private var durationNanos = DEFAULT_DURATION_MS * 1_000_000L
    private var startNanos = UNSET
    private var translateX = 0f
    var enabled = false
        private set

    fun configure(enabled: Boolean, color: Int, durationMs: Long) {
        if (this.color != color) {
            this.color = color
            shader = null
        }
        durationNanos = durationMs.coerceAtLeast(1L) * 1_000_000L
        this.enabled = enabled
        update()
        host.invalidate()
    }

    fun update() {
        if (shouldRun()) Clock.register(this) else Clock.unregister(this)
    }

    fun draw(canvas: Canvas, clip: Path) {
        if (!enabled || host.width <= 0 || host.height <= 0) return
        val strip = (host.width * STRIP_RATIO).coerceAtLeast(1f)
        val current = shader?.takeIf { shaderWidth == host.width } ?: LinearGradient(
            0f,
            0f,
            strip,
            0f,
            intArrayOf(alpha(0f), alpha(SOFT), color, alpha(SOFT), alpha(0f)),
            floatArrayOf(0f, 0.35f, 0.5f, 0.65f, 1f),
            Shader.TileMode.CLAMP,
        ).also {
            shader = it
            shaderWidth = host.width
        }
        if (startNanos == UNSET) translateX = -strip
        matrix.setTranslate(translateX, 0f)
        current.setLocalMatrix(matrix)
        paint.shader = current
        canvas.drawPath(clip, paint)
        paint.shader = null
    }

    private fun frame(nanos: Long) {
        if (!host.getGlobalVisibleRect(visible) || visible.isEmpty) return
        if (startNanos == UNSET) startNanos = nanos
        val width = host.width.toFloat()
        val strip = (width * STRIP_RATIO).coerceAtLeast(1f)
        val progress = ((nanos - startNanos).coerceAtLeast(0L) % durationNanos).toFloat() / durationNanos
        translateX = -strip + (width + strip) * progress
        host.invalidate()
    }

    private fun shouldRun(): Boolean =
        enabled && host.isAttachedToWindow && host.width > 0 && host.height > 0 && host.isShown && host.alpha > 0f

    private fun alpha(factor: Float): Int =
        Color.argb((Color.alpha(color) * factor).roundToInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private object Clock : Choreographer.FrameCallback {
        private val active = LinkedHashSet<PamShimmer>()
        private var scheduled = false

        fun register(shimmer: PamShimmer) {
            active.add(shimmer)
            schedule()
        }

        fun unregister(shimmer: PamShimmer) {
            if (active.remove(shimmer)) shimmer.startNanos = UNSET
            if (active.isEmpty() && scheduled) {
                Choreographer.getInstance().removeFrameCallback(this)
                scheduled = false
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            val iterator = active.iterator()
            while (iterator.hasNext()) {
                val shimmer = iterator.next()
                if (shimmer.shouldRun()) {
                    shimmer.frame(frameTimeNanos)
                } else {
                    iterator.remove()
                    shimmer.startNanos = UNSET
                }
            }
            schedule()
        }

        private fun schedule() {
            if (scheduled || active.isEmpty()) return
            Choreographer.getInstance().postFrameCallback(this)
            scheduled = true
        }
    }

    private companion object {
        const val DEFAULT_COLOR = 0x59FFFFFF
        const val DEFAULT_DURATION_MS = 1200L
        const val STRIP_RATIO = 1.25f
        const val SOFT = 0.24f
        const val UNSET = -1L
    }
}
