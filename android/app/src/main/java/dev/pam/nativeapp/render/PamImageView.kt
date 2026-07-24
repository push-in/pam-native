package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.widget.ImageView

internal class PamImageView(context: Context) : ImageView(context) {
    var onImageSizeChanged: ((Int, Int) -> Unit)? = null
    private val clipPath = Path()
    private val clipBounds = RectF()
    private var cornerRadii = FloatArray(8)

    init {
        adjustViewBounds = true
        scaleType = ScaleType.CENTER_CROP
    }

    override fun onSizeChanged(
        width: Int,
        height: Int,
        oldWidth: Int,
        oldHeight: Int,
    ) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        rebuildClipPath()
        if (width > 0 && height > 0) {
            onImageSizeChanged?.invoke(width, height)
        }
    }

    fun setCornerRadii(value: FloatArray) {
        require(value.size == 8)
        if (cornerRadii.contentEquals(value)) return
        cornerRadii = value.copyOf()
        rebuildClipPath()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        if (cornerRadii.any { it > 0f }) {
            canvas.clipPath(clipPath)
        } else {
            canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        }
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    private fun rebuildClipPath() {
        clipBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(clipBounds, cornerRadii, Path.Direction.CW)
    }
}
