package dev.pam.nativeapp.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import android.view.View
import org.json.JSONArray
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One CSS box-shadow in px. Blur follows CSS: the Gaussian sigma is blur / 2. */
internal data class PamBoxShadow(
    val offsetX: Float,
    val offsetY: Float,
    val blurRadius: Float,
    val spreadRadius: Float,
    val color: Int,
    val cornerRadii: FloatArray,
    val inset: Boolean = false,
)

internal object PamBoxShadows {
    private val values = WeakHashMap<View, List<PamBoxShadow>>()
    private val bounds = RectF()
    private val src = Rect()
    private val dst = RectF()

    fun set(view: View, shadow: PamBoxShadow?) {
        set(view, listOfNotNull(shadow))
    }

    fun set(view: View, shadows: List<PamBoxShadow>) {
        val outer = shadows.filter { !it.inset && Color.alpha(it.color) != 0 }
        if (outer.isEmpty()) {
            if (values.remove(view) == null) return
        } else {
            if (values[view] == outer) return
            values[view] = outer
        }
        (view.parent as? View)?.invalidate()
    }

    /** Parses the compiler's `boxShadows` wire list (dp) into px shadows. */
    fun parse(wire: String?, density: Float, radii: FloatArray): List<PamBoxShadow> {
        if (wire.isNullOrEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(wire)
            List(array.length()) { index ->
                val item = array.getJSONArray(index)
                PamBoxShadow(
                    offsetX = item.getDouble(0).toFloat() * density,
                    offsetY = item.getDouble(1).toFloat() * density,
                    blurRadius = item.getDouble(2).toFloat() * density,
                    spreadRadius = item.getDouble(3).toFloat() * density,
                    color = item.getLong(4).toInt(),
                    cornerRadii = radii,
                    inset = item.optInt(5, 0) == 1,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Paints [child]'s outer shadows in the parent's coordinate space, last shadow first. */
    fun draw(canvas: Canvas, child: View, paint: Paint, path: Path, unused: RectF) {
        val shadows = values[child] ?: return
        if (child.width <= 0 || child.height <= 0 || child.visibility != View.VISIBLE) return
        val checkpoint = canvas.save()
        canvas.translate(child.left.toFloat(), child.top.toFloat())
        if (!child.matrix.isIdentity) canvas.concat(child.matrix)
        val alpha = child.alpha.coerceIn(0f, 1f)
        for (index in shadows.indices.reversed()) {
            drawOuter(canvas, shadows[index], child.width.toFloat(), child.height.toFloat(), alpha, paint, path)
        }
        canvas.restoreToCount(checkpoint)
    }

    private fun drawOuter(
        canvas: Canvas,
        shadow: PamBoxShadow,
        width: Float,
        height: Float,
        alpha: Float,
        paint: Paint,
        path: Path,
    ) {
        val spread = shadow.spreadRadius
        val shapeWidth = width + spread * 2f
        val shapeHeight = height + spread * 2f
        if (shapeWidth <= 0f || shapeHeight <= 0f) return
        val radii = scaledRadii(spreadRadii(scaledRadii(shadow.cornerRadii, width, height), spread), shapeWidth, shapeHeight)
        paint.reset()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.color = shadow.color
        paint.alpha = (Color.alpha(shadow.color) * alpha).toInt().coerceIn(0, 255)
        val left = shadow.offsetX - spread
        val top = shadow.offsetY - spread
        val sigma = shadow.blurRadius / 2f
        if (sigma < 0.5f) {
            bounds.set(left, top, left + shapeWidth, top + shapeHeight)
            path.reset()
            path.addRoundRect(bounds, radii, Path.Direction.CW)
            canvas.drawPath(path, paint)
            return
        }
        val mask = PamShadowMasks.outer(shapeWidth, shapeHeight, radii, sigma)
        val extent = mask.extent
        val scale = mask.scale
        val bitmap = mask.bitmap
        if (!mask.nineSlice) {
            dst.set(left - extent, top - extent, left + shapeWidth + extent, top + shapeHeight + extent)
            canvas.drawBitmap(bitmap, null, dst, paint)
            return
        }
        // Nine-slice: corners keep their size, the 1px center row/column stretches.
        val xs = floatArrayOf(
            left - extent,
            left - extent + mask.sliceLeft / scale,
            left + shapeWidth + extent - mask.sliceRight / scale,
            left + shapeWidth + extent,
        )
        val ys = floatArrayOf(
            top - extent,
            top - extent + mask.sliceTop / scale,
            top + shapeHeight + extent - mask.sliceBottom / scale,
            top + shapeHeight + extent,
        )
        val sx = intArrayOf(0, mask.sliceLeft, bitmap.width - mask.sliceRight, bitmap.width)
        val sy = intArrayOf(0, mask.sliceTop, bitmap.height - mask.sliceBottom, bitmap.height)
        for (row in 0..2) {
            for (column in 0..2) {
                if (xs[column + 1] <= xs[column] || ys[row + 1] <= ys[row]) continue
                src.set(sx[column], sy[row], sx[column + 1], sy[row + 1])
                dst.set(xs[column], ys[row], xs[column + 1], ys[row + 1])
                canvas.drawBitmap(bitmap, src, dst, paint)
            }
        }
    }

    /** Draws inset shadows inside the padding box ([inner] with [innerRadii]). */
    fun drawInset(
        canvas: Canvas,
        shadows: List<PamBoxShadow>,
        inner: RectF,
        innerRadii: FloatArray,
        paint: Paint,
        path: Path,
    ) {
        if (inner.width() <= 0f || inner.height() <= 0f) return
        path.reset()
        path.addRoundRect(inner, innerRadii, Path.Direction.CW)
        for (index in shadows.indices.reversed()) {
            val shadow = shadows[index]
            if (!shadow.inset || Color.alpha(shadow.color) == 0) continue
            val mask = PamShadowMasks.inset(
                inner.width(),
                inner.height(),
                innerRadii,
                shadow.offsetX,
                shadow.offsetY,
                shadow.blurRadius / 2f,
                shadow.spreadRadius,
            ) ?: continue
            paint.reset()
            paint.isAntiAlias = true
            paint.color = shadow.color
            paint.shader = BitmapShader(mask.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(
                    Matrix().apply {
                        setScale(1f / mask.scale, 1f / mask.scale)
                        postTranslate(inner.left, inner.top)
                    },
                )
            }
            canvas.drawPath(path, paint)
            paint.shader = null
        }
    }

    /** CSS spread grows non-zero radii by the spread distance. */
    fun spreadRadii(radii: FloatArray, spread: Float): FloatArray =
        FloatArray(radii.size) { index ->
            if (radii[index] <= 0f) 0f else (radii[index] + spread).coerceAtLeast(0f)
        }
}

/**
 * ALPHA_8 blur masks shared by every view with the same geometry. Outer
 * shadows use a nine-slice proxy whose size depends only on radii and blur,
 * so a list of 500 cards with one shadow style allocates one small bitmap.
 * Large blurs are rendered downscaled (a blurred mask has no high
 * frequencies) and stretched with bilinear filtering.
 */
internal object PamShadowMasks {
    class Mask(
        val bitmap: Bitmap,
        val scale: Float,
        val extent: Float,
        val nineSlice: Boolean,
        val sliceLeft: Int,
        val sliceTop: Int,
        val sliceRight: Int,
        val sliceBottom: Int,
    )

    private const val CACHE_BYTES = 6 * 1024 * 1024
    private const val MAX_MASK_PIXELS = 1_500_000
    private val cache = object : LruCache<String, Mask>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Mask): Int = value.bitmap.allocationByteCount
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    private fun scaleFor(sigma: Float): Float = min(1f, 4f / sigma).coerceAtLeast(0.125f)

    private fun blurFilter(sigma: Float): BlurMaskFilter? =
        if (sigma <= 0.5f) {
            null
        } else {
            // Skia converts a BlurMaskFilter radius to sigma = 0.57735 r + 0.5.
            BlurMaskFilter(((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f), BlurMaskFilter.Blur.NORMAL)
        }

    fun outer(width: Float, height: Float, radii: FloatArray, sigma: Float): Mask {
        val extent = ceil(sigma * 3f)
        var scale = scaleFor(sigma)
        val maxLeft = max(radii[0], radii[6])
        val maxRight = max(radii[2], radii[4])
        val maxTop = max(radii[1], radii[3])
        val maxBottom = max(radii[5], radii[7])
        val sliceLeft = ceil((maxLeft + extent * 2f) * scale).toInt()
        val sliceRight = ceil((maxRight + extent * 2f) * scale).toInt()
        val sliceTop = ceil((maxTop + extent * 2f) * scale).toInt()
        val sliceBottom = ceil((maxBottom + extent * 2f) * scale).toInt()
        val nineSlice = (width + extent * 2f) * scale > sliceLeft + sliceRight + 2 &&
            (height + extent * 2f) * scale > sliceTop + sliceBottom + 2
        val key = buildString {
            append(if (nineSlice) 'n' else 'f')
            append(radii.joinToString(",") { (it * 4f).toInt().toString() })
            append('|').append((sigma * 8f).toInt())
            if (!nineSlice) append('|').append((width * 2f).toInt()).append('x').append((height * 2f).toInt())
        }
        cache.get(key)?.let { return it }
        val bitmapWidth: Int
        val bitmapHeight: Int
        val shapeWidth: Float
        val shapeHeight: Float
        if (nineSlice) {
            bitmapWidth = sliceLeft + 1 + sliceRight
            bitmapHeight = sliceTop + 1 + sliceBottom
            shapeWidth = bitmapWidth / scale - extent * 2f
            shapeHeight = bitmapHeight / scale - extent * 2f
        } else {
            val pixels = (width + extent * 2f) * (height + extent * 2f) * scale * scale
            if (pixels > MAX_MASK_PIXELS) {
                scale *= kotlin.math.sqrt(MAX_MASK_PIXELS / pixels)
            }
            bitmapWidth = ceil((width + extent * 2f) * scale).toInt().coerceAtLeast(1)
            bitmapHeight = ceil((height + extent * 2f) * scale).toInt().coerceAtLeast(1)
            shapeWidth = width
            shapeHeight = height
        }
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        canvas.scale(
            bitmapWidth / (shapeWidth + extent * 2f),
            bitmapHeight / (shapeHeight + extent * 2f),
        )
        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.BLACK
        paint.maskFilter = blurFilter(sigma)
        rect.set(extent, extent, extent + shapeWidth, extent + shapeHeight)
        path.reset()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        val effectiveScale = bitmapWidth / (shapeWidth + extent * 2f)
        return Mask(
            bitmap,
            effectiveScale,
            extent,
            nineSlice,
            sliceLeft,
            sliceTop,
            sliceRight,
            sliceBottom,
        ).also { cache.put(key, it) }
    }

    /** Mask of the area outside the (offset, spread-shrunk) hole, blurred, for a padding box. */
    fun inset(
        width: Float,
        height: Float,
        radii: FloatArray,
        offsetX: Float,
        offsetY: Float,
        sigma: Float,
        spread: Float,
    ): Mask? {
        var scale = scaleFor(max(sigma, 0.5f))
        val pixels = width * height * scale * scale
        if (pixels > MAX_MASK_PIXELS) scale *= kotlin.math.sqrt(MAX_MASK_PIXELS / pixels)
        val key = buildString {
            append('i').append((width * 2f).toInt()).append('x').append((height * 2f).toInt())
            append('|').append(radii.joinToString(",") { (it * 4f).toInt().toString() })
            append('|').append((offsetX * 4f).toInt()).append(',').append((offsetY * 4f).toInt())
            append('|').append((sigma * 8f).toInt()).append(',').append((spread * 4f).toInt())
        }
        cache.get(key)?.let { return it }
        val bitmapWidth = ceil(width * scale).toInt().coerceAtLeast(1)
        val bitmapHeight = ceil(height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        canvas.scale(bitmapWidth / width, bitmapHeight / height)
        val extent = ceil(sigma * 3f) + abs(offsetX) + abs(offsetY) + abs(spread) + 1f
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(-extent, -extent, width + extent, height + extent, Path.Direction.CW)
        rect.set(offsetX + spread, offsetY + spread, offsetX + width - spread, offsetY + height - spread)
        if (rect.width() > 0f && rect.height() > 0f) {
            val holeRadii = FloatArray(radii.size) { (radii[it] - spread).coerceAtLeast(0f) }
            path.addRoundRect(rect, holeRadii, Path.Direction.CW)
        }
        paint.reset()
        paint.isAntiAlias = true
        paint.color = Color.BLACK
        paint.maskFilter = blurFilter(sigma)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
        path.fillType = Path.FillType.WINDING
        return Mask(bitmap, bitmapWidth / width, 0f, false, 0, 0, 0, 0).also { cache.put(key, it) }
    }
}
