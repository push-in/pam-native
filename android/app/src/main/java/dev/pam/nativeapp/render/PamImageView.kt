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
        if (cornerRadii.any { it > 0f }) {
            val checkpoint = canvas.save()
            canvas.clipPath(clipPath)
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
        } else {
            super.onDraw(canvas)
        }
    }

    private fun rebuildClipPath() {
        clipBounds.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(clipBounds, cornerRadii, Path.Direction.CW)
    }
}
