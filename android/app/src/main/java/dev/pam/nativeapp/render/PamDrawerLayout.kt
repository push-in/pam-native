package dev.pam.nativeapp.render

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

internal class PamDrawerLayout(context: Context) : FrameLayout(context) {
    private var open = false
    private var downX = 0f
    private var onOpen: (() -> Unit)? = null
    private var onClose: (() -> Unit)? = null

    init {
        clipChildren = false
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = event.x
                MotionEvent.ACTION_UP -> {
                    val delta = event.x - downX
                    if (abs(delta) >= dp(56f)) {
                        setOpen(delta > 0f)
                    } else {
                        performClick()
                    }
                }
            }
            false
        }
    }

    fun insert(view: View, index: Int) {
        addView(view, index.coerceIn(0, childCount))
        updateDrawer(animated = false)
    }

    fun setOpen(value: Boolean, animated: Boolean = true) {
        if (open == value && animated) return
        val changed = open != value
        open = value
        updateDrawer(animated)
        if (changed) {
            if (open) onOpen?.invoke() else onClose?.invoke()
        }
    }

    fun setCallbacks(opened: (() -> Unit)?, closed: (() -> Unit)?) {
        onOpen = opened
        onClose = closed
    }

    private fun updateDrawer(animated: Boolean) {
        val drawer = getChildAt(1) ?: return
        val target = if (open) 0f else -(drawer.width.takeIf { it > 0 } ?: width).toFloat()
        if (animated) {
            drawer.animate().translationX(target).setDuration(180).start()
        } else {
            drawer.translationX = target
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateDrawer(animated = false)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
