package dev.pam.nativeapp.render

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * Background paint layer above the background color: CSS gradient layers
 * (clipped to the rounded border box), inset box shadows (clipped to the
 * padding box) and a gradient border stroke. Shaders and paths are rebuilt
 * only when the bounds change.
 */
internal class PamEffectsDrawable(
    private val gradients: List<PamGradientLayer>,
    private val insetShadows: List<PamBoxShadow>,
    private val borderGradient: PamGradientLayer?,
    private val radii: FloatArray,
    private val borderWidths: FloatArray,
    private val density: Float,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerPath = Path()
    private val borderPath = Path()
    private val shadowPath = Path()
    private val outer = RectF()
    private val inner = RectF()
    private var innerRadii = FloatArray(8)
    private var shaders: List<Shader?> = emptyList()
    private var borderShader: Shader? = null
    private var drawableAlpha = 255
    private var colorFilter: ColorFilter? = null

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        outer.set(0f, 0f, width, height)
        val scaled = scaledRadii(radii, width, height)
        outerPath.reset()
        outerPath.addRoundRect(outer, scaled, Path.Direction.CW)
        val (left, top, right, bottom) = borderWidths.let { listOf(it[0], it[1], it[2], it[3]) }
        inner.set(left, top, width - right, height - bottom)
        innerRadii = floatArrayOf(
            (scaled[0] - left).coerceAtLeast(0f), (scaled[1] - top).coerceAtLeast(0f),
            (scaled[2] - right).coerceAtLeast(0f), (scaled[3] - top).coerceAtLeast(0f),
            (scaled[4] - right).coerceAtLeast(0f), (scaled[5] - bottom).coerceAtLeast(0f),
            (scaled[6] - left).coerceAtLeast(0f), (scaled[7] - bottom).coerceAtLeast(0f),
        )
        borderPath.reset()
        borderPath.fillType = Path.FillType.EVEN_ODD
        borderPath.addRoundRect(outer, scaled, Path.Direction.CW)
        if (inner.width() > 0f && inner.height() > 0f) {
            borderPath.addRoundRect(inner, innerRadii, Path.Direction.CW)
        }
        // Gradients are drawn last-to-first: the first CSS layer is on top.
        shaders = gradients.map { it.shader(width, height, density) }
        borderShader = borderGradient?.shader(width, height, density)
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val checkpoint = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        paint.alpha = drawableAlpha
        paint.colorFilter = colorFilter
        for (index in shaders.indices.reversed()) {
            val shader = shaders[index] ?: continue
            paint.shader = shader
            canvas.drawPath(outerPath, paint)
        }
        paint.shader = null
        if (insetShadows.isNotEmpty()) {
            PamBoxShadows.drawInset(canvas, insetShadows, inner, innerRadii, shadowPaint, shadowPath)
        }
        borderShader?.let { shader ->
            paint.shader = shader
            canvas.drawPath(borderPath, paint)
            paint.shader = null
        }
        canvas.restoreToCount(checkpoint)
    }

    override fun setAlpha(alpha: Int) {
        drawableAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        this.colorFilter = colorFilter
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** CSS corner overlap rule: scale every radius down when adjacent ones overflow a side. */
internal fun scaledRadii(radii: FloatArray, width: Float, height: Float): FloatArray {
    var factor = 1f
    fun fit(sum: Float, side: Float) {
        if (sum > side && sum > 0f) factor = minOf(factor, side / sum)
    }
    fit(radii[0] + radii[2], width)
    fit(radii[6] + radii[4], width)
    fit(radii[1] + radii[7], height)
    fit(radii[3] + radii[5], height)
    return if (factor == 1f) radii else FloatArray(radii.size) { radii[it] * factor }
}
