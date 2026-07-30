package dev.pam.nativeapp.render

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import java.util.WeakHashMap

internal data class PamBoxShadow(
    val offsetX: Float,
    val offsetY: Float,
    val blurRadius: Float,
    val spreadRadius: Float,
    val color: Int,
    val cornerRadii: FloatArray,
)

internal object PamBoxShadows {
    private val values = WeakHashMap<View, PamBoxShadow>()

    fun set(view: View, shadow: PamBoxShadow?) {
        if (shadow == null) {
            values.remove(view)
        } else {
            values[view] = shadow
        }
        (view.parent as? View)?.invalidate()
    }

    fun draw(canvas: Canvas, child: View, paint: Paint, path: Path, bounds: RectF) {
        val shadow = values[child] ?: return
        if (shadow.color ushr 24 == 0 || child.width <= 0 || child.height <= 0) return

        val spread = shadow.spreadRadius
        val translatedLeft = child.left + child.translationX + shadow.offsetX
        val translatedTop = child.top + child.translationY + shadow.offsetY
        bounds.set(
            translatedLeft - spread,
            translatedTop - spread,
            translatedLeft + child.width + spread,
            translatedTop + child.height + spread,
        )
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = shadow.color
        paint.alpha = (
            android.graphics.Color.alpha(shadow.color) * child.alpha.coerceIn(0f, 1f)
            ).toInt()
        paint.maskFilter = if (shadow.blurRadius > 0f) {
            BlurMaskFilter(shadow.blurRadius, BlurMaskFilter.Blur.NORMAL)
        } else {
            null
        }
        path.reset()
        val radii = FloatArray(shadow.cornerRadii.size) { index ->
            (shadow.cornerRadii[index] + spread).coerceAtLeast(0f)
        }
        path.addRoundRect(bounds, radii, Path.Direction.CW)
        canvas.drawPath(path, paint)
        paint.maskFilter = null
    }
}
