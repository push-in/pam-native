package dev.pam.nativeapp.render

import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import kotlin.math.ceil

/**
 * CSS `backdrop-filter` for a [PamContainer] (Android 12+). Every frame the
 * host draws, the content painted *behind* it — ancestor backgrounds and
 * earlier siblings along the ancestor chain — is re-recorded into a
 * [RenderNode] (sibling subtrees are referenced, not re-rendered), filtered
 * with a GPU blur/color-matrix [RenderEffect] and drawn under the host's
 * own background, clipped to its corner radii.
 *
 * Limits: SurfaceView content (punched-through video) is not captured;
 * siblings painted later in z-order are ignored; before API 31 the filter
 * is a no-op and the translucent background shows instead.
 */
internal class PamBackdrop(private val host: PamContainer) : ViewTreeObserver.OnPreDrawListener {
    private var radiusPx = 0f
    private var matrix: ColorMatrix? = null
    private var node: RenderNode? = null
    private var observer: ViewTreeObserver? = null
    private val chain = ArrayList<View>()
    private val transform = Matrix()
    private val inverse = Matrix()
    private val clip = Path()

    val active: Boolean get() = radiusPx > 0f || matrix != null

    fun configure(radiusPx: Float, matrix: ColorMatrix?) {
        this.radiusPx = radiusPx.coerceAtLeast(0f)
        this.matrix = matrix
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && active) {
            warnUnsupported()
        }
        if (active && host.isAttachedToWindow) attach() else if (!active) detach()
        host.invalidate()
    }

    fun attach() {
        if (!active || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val current = host.viewTreeObserver
        if (observer === current && current.isAlive) return
        detach()
        observer = current
        current.addOnPreDrawListener(this)
    }

    fun detach() {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) node?.discardDisplayList()
    }

    override fun onPreDraw(): Boolean {
        // Content behind may change without invalidating the host (a list
        // scrolling under a blurred header): redraw within this frame. Only
        // when something in the window changed since the last frame (any
        // invalidation or property change marks the root dirty); otherwise
        // the host's own invalidation would schedule another traversal and
        // an idle screen would redraw on every frame, forever.
        if (host.isShown && host.rootView.isDirty) host.invalidate()
        return true
    }

    fun draw(canvas: Canvas, radii: FloatArray) {
        if (!active || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !canvas.isHardwareAccelerated) return
        if (host.width <= 0 || host.height <= 0) return
        drawFiltered(canvas, radii)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun drawFiltered(canvas: Canvas, radii: FloatArray) {
        val root = host.rootView as? ViewGroup ?: return
        if (!buildChain(root)) return
        val sigma = radiusPx
        val pad = ceil(sigma * 3f).toInt()
        val width = host.width + pad * 2
        val height = host.height + pad * 2
        val renderNode = node ?: RenderNode("pam-backdrop").also { node = it }
        renderNode.setPosition(-pad, -pad, host.width + pad, host.height + pad)
        val recording = renderNode.beginRecording(width, height)
        try {
            recording.translate(pad.toFloat(), pad.toFloat())
            // Map root coordinates into host-local coordinates.
            if (transform.invert(inverse)) recording.concat(inverse)
            drawBehind(recording, root, 0)
        } finally {
            renderNode.endRecording()
        }
        var effect: RenderEffect? = if (sigma > 0f) {
            val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        } else {
            null
        }
        matrix?.let { colorMatrix ->
            val color = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colorMatrix))
            effect = effect?.let { RenderEffect.createChainEffect(color, it) } ?: color
        }
        renderNode.setRenderEffect(effect)
        val checkpoint = canvas.save()
        clip.reset()
        clip.addRoundRect(
            0f,
            0f,
            host.width.toFloat(),
            host.height.toFloat(),
            scaledRadii(radii, host.width.toFloat(), host.height.toFloat()),
            Path.Direction.CW,
        )
        canvas.clipPath(clip)
        canvas.drawRenderNode(renderNode)
        canvas.restoreToCount(checkpoint)
    }

    /** Ancestor chain root → host and the root→host transform. */
    private fun buildChain(root: ViewGroup): Boolean {
        chain.clear()
        var current: View = host
        while (current !== root) {
            chain.add(current)
            current = current.parent as? View ?: return false
        }
        chain.add(root)
        chain.reverse()
        transform.reset()
        for (index in 1 until chain.size) {
            val child = chain[index]
            val parent = chain[index - 1]
            val step = Matrix()
            step.setTranslate((child.left - parent.scrollX).toFloat(), (child.top - parent.scrollY).toFloat())
            if (!child.matrix.isIdentity) step.preConcat(child.matrix)
            transform.preConcat(step)
        }
        return true
    }

    /** Draws what [chain]`[depth]` paints below the next chain element, in its coordinates. */
    private fun drawBehind(canvas: Canvas, group: View, depth: Int) {
        group.background?.draw(canvas)
        val next = chain.getOrNull(depth + 1) ?: return
        if (group !is ViewGroup) return
        val ordered = (0 until group.childCount)
            .map { group.getChildAt(it) }
            .sortedBy { it.z }
        for (child in ordered) {
            val checkpoint = canvas.save()
            canvas.translate((child.left - group.scrollX).toFloat(), (child.top - group.scrollY).toFloat())
            if (!child.matrix.isIdentity) canvas.concat(child.matrix)
            if (child === next) {
                if (child !== host) drawBehind(canvas, child, depth + 1)
                canvas.restoreToCount(checkpoint)
                return
            }
            if (child.visibility == View.VISIBLE && child.width > 0 && child.height > 0) {
                if (child.alpha < 1f) {
                    canvas.saveLayerAlpha(
                        0f,
                        0f,
                        child.width.toFloat(),
                        child.height.toFloat(),
                        (child.alpha * 255).toInt(),
                    )
                }
                if (group.clipChildren) {
                    canvas.clipRect(0, 0, child.width, child.height)
                }
                child.draw(canvas)
            }
            canvas.restoreToCount(checkpoint)
        }
    }

    private fun warnUnsupported() {
        if (warned) return
        warned = true
        Log.w(TAG, "backdrop-filter needs Android 12 (API 31); it is a no-op on API ${Build.VERSION.SDK_INT}.")
    }

    companion object {
        private const val TAG = "PamNative"
        private var warned = false

        fun colorMatrix(wire: String?): ColorMatrix? {
            if (wire.isNullOrEmpty()) return null
            val values = wire.split(',').mapNotNull { it.trim().toFloatOrNull() }
            return if (values.size == 20) ColorMatrix(values.toFloatArray()) else null
        }
    }
}
