package dev.pam.nativeapp.render

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.graphics.Path
import android.graphics.RectF
import android.widget.ImageView

/**
 * A bitmap blurred by [NativeImageLoader] off the UI thread: [sourceWidth] x
 * [sourceHeight] is the sharp decode's size (the drawable's intrinsic size),
 * [viewSigma] the authored σ in view px and [bitmapSigma] the σ applied in the
 * sharp decode's pixels (see [PamBitmapBlur.bitmapSigma]).
 */
internal data class PamImageBlur(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val viewSigma: Float,
    val bitmapSigma: Float,
)

/** A blurred (downscaled) bitmap drawn at its sharp source's intrinsic size. */
internal class PamBlurredDrawable(
    resources: Resources,
    bitmap: Bitmap,
    val blur: PamImageBlur,
) : BitmapDrawable(resources, bitmap) {
    override fun getIntrinsicWidth(): Int = blur.sourceWidth
    override fun getIntrinsicHeight(): Int = blur.sourceHeight
}

internal class PamImageView(context: Context) : ImageView(context), PamSharedContentView {
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

    /** RN `blurRadius` / CSS `filter: blur()` σ in view px (0 = none). */
    var blurSigma = 0f
        private set

    /**
     * Set by [NativeImageLoader]: the blur changed, so the image is resolved
     * again (from the memory cache in this frame, or blurred off the UI thread).
     */
    var onBlurChanged: (() -> Unit)? = null

    /** RN `blurRadius` / CSS `filter: blur()` on an image, σ in px. */
    fun setBlur(sigma: Float) {
        val normalized = sigma.coerceAtLeast(0f)
        if (normalized == blurSigma) return
        blurSigma = normalized
        // A sharp image never shows under a blur (a chat spoiler): it goes in
        // this frame. A blurred one stays until the loader brings the new blur.
        if (normalized > 0f && drawable != null && appliedBlur == null) setImageDrawable(null)
        onBlurChanged?.invoke()
    }

    /** Blur of the shown drawable (null: sharp or nothing). */
    val appliedBlur: PamImageBlur?
        get() {
            val current = drawable
            val shown = (current as? TransitionDrawable)?.let { it.getDrawable(it.numberOfLayers - 1) } ?: current
            return (shown as? PamBlurredDrawable)?.blur
        }

    /**
     * Whether an image blurred with [blur] (null: sharp) looks as this view's
     * blur asks at its current size and scale type.
     */
    fun blurMatches(blur: PamImageBlur?): Boolean {
        if (blurSigma <= 0f) return blur == null
        if (blur == null || !PamBitmapBlur.sigmaMatches(blur.viewSigma, blurSigma)) return false
        if (width <= 0 || height <= 0) return true
        val wanted = PamBitmapBlur.bitmapSigma(blurSigma, width, height, blur.sourceWidth, blur.sourceHeight, scaleType)
        return PamBitmapBlur.sigmaMatches(blur.bitmapSigma, wanted)
    }

    /**
     * True unless a blur is set and the shown image is missing, sharp or
     * blurred at another σ, or no blur is set and a blurred image shows.
     */
    fun showsWantedBlur(): Boolean {
        val shown = appliedBlur
        if (blurSigma <= 0f && shown == null) return true
        return drawable != null && blurMatches(shown)
    }

    /**
     * Drawable for a decoded bitmap. A bitmap the loader blurred ([blur])
     * keeps its sharp size as intrinsic size, so scale types lay out
     * identically. Null for a sharp bitmap while a blur is set: it never shows.
     */
    fun bitmapDrawable(bitmap: Bitmap, repeat: Boolean, blur: PamImageBlur? = null): Drawable? {
        if (blur != null) return PamBlurredDrawable(resources, bitmap, blur)
        if (blurSigma > 0f) return null
        return BitmapDrawable(resources, bitmap).apply {
            if (repeat) setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    override fun setImageDrawable(drawable: Drawable?) {
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

    /** Inside onVisibilityAggregated: drawable visibility invalidations are filtered ([drawsSameWhenHidden]). */
    private var aggregatingVisibility = false

    override fun onVisibilityAggregated(isVisible: Boolean) {
        aggregatingVisibility = true
        try {
            super.onVisibilityAggregated(isVisible)
        } finally {
            aggregatingVisibility = false
        }
        if (windowVisibility == VISIBLE && (isVisible || !retainPixels)) onShownChanged?.invoke(isVisible)
    }

    override fun invalidateDrawable(drawable: android.graphics.drawable.Drawable) {
        if (aggregatingVisibility && drawsSameWhenHidden(drawable)) return
        super.invalidateDrawable(drawable)
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
        // Blurred images are heap bitmaps: only a sharp GPU bitmap is copied.
        if (bitmap.config != Bitmap.Config.HARDWARE) return
        val copy = runCatching { bitmap.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return
        copy.density = bitmap.density
        val tiled = bitmapDrawable.tileModeX == Shader.TileMode.REPEAT
        setImageDrawable(
            BitmapDrawable(resources, copy).apply {
                if (tiled) setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            },
        )
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
        // The loader re-blurs a shown blur that no longer fits the new size.
        if (width > 0 && height > 0) {
            onImageSizeChanged?.invoke(width, height)
        }
    }

    override val sharedFit: Int?
        get() = when (scaleType) {
            ScaleType.CENTER_CROP -> PamSharedFit.COVER
            ScaleType.FIT_CENTER -> PamSharedFit.CONTAIN
            ScaleType.FIT_XY -> PamSharedFit.FILL
            else -> null
        }

    override val hasSharedContent: Boolean
        get() = drawable?.let { it.intrinsicWidth > 0 && it.intrinsicHeight > 0 } == true

    override fun sharedContent(): PamSharedContent? = sharedDrawableCopy(this, drawable)

    /** Until the decoded image arrives (the loader replaces the drawable). */
    override fun adoptSharedPlaceholder(content: PamSharedContent): Boolean {
        // A blurred image never adopts a (possibly sharp) flight's content.
        if (hasSharedContent || blurSigma > 0f) return false
        setImageDrawable(content.drawable)
        return true
    }

    /** Largest corner radius of the image's own clip, px. */
    fun cornerRadius(): Float = cornerRadii.maxOrNull() ?: 0f

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
