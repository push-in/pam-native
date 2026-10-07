package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.graphics.Path
import android.graphics.RectF
import android.widget.ImageView

internal class PamImageView(context: Context) : ImageView(context) {
    var onImageSizeChanged: ((Int, Int) -> Unit)? = null

    /**
     * Aggregated visibility of this view inside a visible window: false when
     * an ancestor hides it (a covered navigation route, an inactive tab) or
     * it leaves the window; true when it shows again. Window visibility
     * changes (app to background) are not reported. See NativeImageLoader.
     */
    var onShownChanged: ((Boolean) -> Unit)? = null

    /**
     * Keeps the decoded pixels while hidden or detached: set on the images of
     * a parked list cell (an inactive keyed section) so switching back draws
     * them in the same frame without a cache lookup or decode.
     */
    var retainPixels = false
    private var suppressLayoutRequest = false
    private val clipPath = Path()
    private val clipBounds = RectF()
    private var cornerRadii = FloatArray(8)
    private var sourceBitmap: Bitmap? = null
    private var sourceRepeat = false
    private var blurSigma = 0f

    /** RN `blurRadius` / CSS `filter: blur()` on an image, σ in px. */
    fun setBlur(sigma: Float) {
        val normalized = sigma.coerceAtLeast(0f)
        if (normalized == blurSigma) return
        blurSigma = normalized
        sourceBitmap?.let { bitmap -> setImageDrawable(bitmapDrawable(bitmap, sourceRepeat)) }
    }

    /** Drawable for a decoded bitmap, blurred when a blur radius is set. */
    fun bitmapDrawable(bitmap: Bitmap, repeat: Boolean): Drawable {
        sourceBitmap = bitmap
        sourceRepeat = repeat
        if (blurSigma <= 0f) {
            return BitmapDrawable(resources, bitmap).apply {
                if (repeat) setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            }
        }
        val blurred = PamBitmapBlur.blur(bitmap, blurSigma)
        val width = bitmap.width
        val height = bitmap.height
        // Keep the source's intrinsic size so scale types lay out identically.
        return object : BitmapDrawable(resources, blurred) {
            override fun getIntrinsicWidth(): Int = width
            override fun getIntrinsicHeight(): Int = height
        }
    }

    override fun setImageDrawable(drawable: Drawable?) {
        if (drawable == null) sourceBitmap = null
        // PAM's layout engine sizes this view; a new drawable never changes
        // its frame. ImageView would otherwise requestLayout() whenever the
        // intrinsic size changes, so every photo arriving in a 30-image grid
        // re-measured the whole ancestor chain in its own frame.
        suppressLayoutRequest = layoutParams != null && !isLayoutRequested
        try {
            super.setImageDrawable(drawable)
        } finally {
            suppressLayoutRequest = false
        }
    }

    override fun requestLayout() {
        if (suppressLayoutRequest) return
        super.requestLayout()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (windowVisibility == VISIBLE && (isVisible || !retainPixels)) onShownChanged?.invoke(isVisible)
    }

    override fun draw(canvas: Canvas) {
        // Hardware bitmaps only draw on hardware canvases. A software canvas
        // (a shared-element snapshot, a window without hardware acceleration)
        // gets a heap copy once instead of an exception.
        if (!canvas.isHardwareAccelerated) ensureSoftwareDrawable()
        super.draw(canvas)
    }

    private fun ensureSoftwareDrawable() {
        val current = drawable ?: return
        val bitmapDrawable = when (current) {
            is BitmapDrawable -> current
            is TransitionDrawable -> current.getDrawable(current.numberOfLayers - 1) as? BitmapDrawable
            else -> null
        } ?: return
        val bitmap = bitmapDrawable.bitmap ?: return
        if (bitmap.config != Bitmap.Config.HARDWARE) return
        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        copy.density = bitmap.density
        val tiled = bitmapDrawable.tileModeX == Shader.TileMode.REPEAT
        setImageDrawable(bitmapDrawable(copy, tiled))
    }

    init {
        // PAM's layout engine is authoritative for both dimensions. Letting
        // ImageView adjust its own bounds from the drawable's intrinsic aspect
        // ratio can collapse images inside RecyclerView/VirtualizedList cells
        // even when the engine supplied an exact full-width frame.
        adjustViewBounds = false
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
