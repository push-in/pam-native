package dev.pam.nativeapp.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.Window
import androidx.core.view.WindowInsetsCompat

/**
 * Status and navigation bar colours of a window without the deprecated
 * `Window.setStatusBarColor`/`setNavigationBarColor` (Play's edge-to-edge
 * warning): the window keeps transparent bars (theme) and this drawable,
 * in the decor view's overlay, paints the bar areas above the content, as
 * the system did. Android 15+ ignores bar colours for edge-to-edge apps, so
 * it paints nothing there, matching what the deprecated setters did.
 * Main thread.
 */
internal class PamSystemBarBands private constructor(private val decor: View) : Drawable() {
    private val paint = Paint()
    private var top = 0
    private var bottom = 0
    private var left = 0
    private var right = 0

    var statusColor: Int = Color.TRANSPARENT
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    var navigationColor: Int = Color.TRANSPARENT
        set(value) {
            if (field == value) return
            field = value
            invalidateSelf()
        }

    private val layout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync() }

    private fun sync() {
        setBounds(0, 0, decor.width, decor.height)
        val insets = decor.rootWindowInsets?.let { WindowInsetsCompat.toWindowInsetsCompat(it, decor) }
        val status = insets?.getInsets(WindowInsetsCompat.Type.statusBars())
        val navigation = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())
        val changed = top != (status?.top ?: 0) || bottom != (navigation?.bottom ?: 0) ||
            left != (navigation?.left ?: 0) || right != (navigation?.right ?: 0)
        top = status?.top ?: 0
        bottom = navigation?.bottom ?: 0
        left = navigation?.left ?: 0
        right = navigation?.right ?: 0
        if (changed) invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (top > 0 && Color.alpha(statusColor) > 0) {
            paint.color = statusColor
            canvas.drawRect(0f, 0f, width, top.toFloat(), paint)
        }
        if (Color.alpha(navigationColor) == 0) return
        paint.color = navigationColor
        if (bottom > 0) canvas.drawRect(0f, height - bottom, width, height, paint)
        if (left > 0) canvas.drawRect(0f, top.toFloat(), left.toFloat(), height, paint)
        if (right > 0) canvas.drawRect(width - right, top.toFloat(), width, height, paint)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** Area covered by the bars (tests, diagnostics): top, bottom, left, right. */
    internal val barInsets: List<Int> get() = listOf(top, bottom, left, right)

    companion object {
        /** True where the platform still draws bar colours (below Android 15). */
        val paintsBars: Boolean get() = Build.VERSION.SDK_INT < 35

        /** The bands of [window], installed once in its decor overlay; null on Android 15+. */
        fun of(window: Window): PamSystemBarBands? {
            if (!paintsBars) return null
            val decor = window.decorView
            (decor.getTag(TAG) as? PamSystemBarBands)?.let { return it }
            return PamSystemBarBands(decor).also { bands ->
                decor.setTag(TAG, bands)
                decor.overlay.add(bands)
                decor.addOnLayoutChangeListener(bands.layout)
                bands.sync()
            }
        }

        private val TAG = dev.pam.nativeapp.R.id.pam_system_bar_bands
    }
}
