package dev.pam.nativeapp.render

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import java.lang.ref.WeakReference

internal class PamModalHost(context: Context) : FrameLayout(context) {
    private val content = FrameLayout(context)
    private var dialog: Dialog? = null
    private var presentation = PRESENTATION_DIALOG
    private var desiredVisible = true
    private var animationType = ANIMATION_NONE
    private var backdropColor = Color.argb(82, 0, 0, 0)
    private var transparent = false
    private var hardwareAccelerated = false
    private var navigationBarTranslucent = false
    private var statusBarTranslucent = false
    private var allowSwipeDismissal = false
    private var focusKeyboard = false
    private var onRequestClose: (() -> Unit)? = null
    private var onShow: (() -> Unit)? = null
    private var onDismiss: (() -> Unit)? = null
    private var onOrientationChange: ((Int) -> Unit)? = null
    private var previousFocus: WeakReference<View>? = null
    private var lastOrientation: Int? = null
    private var dialogGeneration = 0L
    private var updateScheduled = false

    private val updateRunnable = Runnable {
        updateScheduled = false
        updateDialog()
    }

    init {
        visibility = View.INVISIBLE
        content.clipChildren = false
        content.clipToPadding = false
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (dialog?.isShowing == true) {
                dispatchOrientation(force = false)
            }
        }
    }

    fun insert(view: View, index: Int) {
        content.addView(view, index.coerceIn(0, content.childCount))
    }

    fun setPresentation(value: Int) {
        if (presentation == value) return
        presentation = value
        dialog?.let {
            applyWindowConfiguration(it)
            applyWindowLayout(it)
        }
    }

    fun setVisible(value: Boolean) {
        if (desiredVisible == value && dialog?.isShowing == value) return
        desiredVisible = value
        scheduleUpdate()
    }

    fun setAnimationType(value: Int) {
        animationType = value.coerceIn(ANIMATION_NONE, ANIMATION_FADE)
    }

    fun setBackdropColor(color: Int) {
        backdropColor = color
        applyBackdrop()
    }

    fun setTransparent(value: Boolean) {
        transparent = value
        applyBackdrop()
    }

    fun setHardwareAccelerated(value: Boolean) {
        hardwareAccelerated = value
        dialog?.let(::applyWindowConfiguration)
    }

    fun setNavigationBarTranslucent(value: Boolean) {
        navigationBarTranslucent = value
        dialog?.let(::applyWindowConfiguration)
    }

    fun setStatusBarTranslucent(value: Boolean) {
        statusBarTranslucent = value
        dialog?.let(::applyWindowConfiguration)
    }

    fun setAllowSwipeDismissal(value: Boolean) {
        allowSwipeDismissal = value
    }

    fun setFocusKeyboard(value: Boolean) {
        focusKeyboard = value
    }

    fun setCallbacks(
        onRequestClose: (() -> Unit)?,
        onShow: (() -> Unit)?,
        onDismiss: (() -> Unit)?,
        onOrientationChange: ((Int) -> Unit)?,
    ) {
        this.onRequestClose = onRequestClose
        this.onShow = onShow
        this.onDismiss = onDismiss
        this.onOrientationChange = onOrientationChange
        if (onOrientationChange != null && dialog?.isShowing == true) {
            dispatchOrientation(force = lastOrientation == null)
        }
    }

    fun close() {
        desiredVisible = false
        removeCallbacks(updateRunnable)
        updateScheduled = false
        dismiss(notify = false, animated = false)
        content.removeAllViews()
        onRequestClose = null
        onShow = null
        onDismiss = null
        onOrientationChange = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleUpdate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(updateRunnable)
        updateScheduled = false
        dismiss(notify = false, animated = false)
        super.onDetachedFromWindow()
    }

    private fun scheduleUpdate() {
        if (!isAttachedToWindow || updateScheduled) return
        updateScheduled = true
        post(updateRunnable)
    }

    @Suppress("DEPRECATION")
    private fun updateDialog() {
        if (!isAttachedToWindow || !desiredVisible) {
            dismiss(notify = true, animated = true)
            return
        }
        val active = dialog
        if (active?.isShowing == true) {
            content.animate().cancel()
            content.alpha = 1f
            content.translationY = 0f
            applyWindowConfiguration(active)
            applyWindowLayout(active)
            return
        }

        previousFocus = WeakReference(rootView.findFocus())
        val generation = ++dialogGeneration
        dialog = Dialog(context).also { modal ->
            modal.requestWindowFeature(Window.FEATURE_NO_TITLE)
            (content.parent as? ViewGroup)?.removeView(content)
            modal.setContentView(content)
            modal.setCanceledOnTouchOutside(false)
            modal.setOnKeyListener { _, keyCode, event ->
                if (
                    keyCode == KeyEvent.KEYCODE_BACK
                    && event.action == KeyEvent.ACTION_UP
                ) {
                    requestClose()
                    true
                } else {
                    false
                }
            }
            applyBackdrop()
            applyWindowConfiguration(modal)
            modal.show()
            if (dialogGeneration != generation || !desiredVisible) {
                modal.dismiss()
                return@also
            }
            applyWindowConfiguration(modal)
            applyWindowLayout(modal)
            animateEntrance()
            dispatchOrientation(force = true)
            onShow?.invoke()
            content.post {
                if (dialog === modal && modal.isShowing) {
                    val focus = if (focusKeyboard) {
                        content.findFirstEditText()
                    } else {
                        content.findFirstFocusable()
                    }
                    focus?.let {
                        focus.requestFocus()
                        if (focusKeyboard && focus is EditText) {
                            val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                                as? InputMethodManager
                            keyboard?.showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                }
            }
        }
    }

    private fun requestClose() {
        val callback = onRequestClose
        if (callback != null) {
            callback()
            return
        }
        desiredVisible = false
        dismiss(notify = true, animated = true)
    }

    @Suppress("DEPRECATION")
    private fun applyWindowConfiguration(modal: Dialog) {
        modal.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setFormat(PixelFormat.TRANSLUCENT)
            decorView.setBackgroundColor(Color.TRANSPARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0f }
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                    if (focusKeyboard) {
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                    } else {
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
                    },
            )
            setGravity(
                if (presentation == PRESENTATION_SHEET) {
                    Gravity.BOTTOM
                } else {
                    Gravity.CENTER
                },
            )
            if (hardwareAccelerated) {
                addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            } else {
                clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
            }
            if (statusBarTranslucent) {
                addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                statusBarColor = Color.TRANSPARENT
            } else {
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            }
            if (navigationBarTranslucent && statusBarTranslucent) {
                addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                navigationBarColor = Color.TRANSPARENT
            } else {
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            }
        }
        applyBackdrop()
    }

    private fun applyWindowLayout(modal: Dialog) {
        repeat(content.childCount) { index ->
            content.getChildAt(index).layoutParams = when (presentation) {
                PRESENTATION_SHEET -> FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.BOTTOM,
                )
                else -> FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        }
        modal.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun applyBackdrop() {
        content.setBackgroundColor(backdropColor)
    }

    private fun animateEntrance() {
        content.animate().cancel()
        if (animationType == ANIMATION_NONE || !ValueAnimator.areAnimatorsEnabled()) {
            content.alpha = 1f
            content.translationY = 0f
            return
        }
        content.alpha = if (animationType == ANIMATION_FADE) 0f else 1f
        content.translationY = if (animationType == ANIMATION_SLIDE) {
            resources.displayMetrics.heightPixels * SLIDE_DISTANCE_FRACTION
        } else {
            0f
        }
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MODAL_ENTER_DURATION_MS)
            .start()
    }

    private fun dismiss(notify: Boolean, animated: Boolean) {
        val modal = dialog ?: return
        if (!modal.isShowing) {
            dismissNow(modal, notify)
            return
        }
        if (
            animated &&
            animationType != ANIMATION_NONE &&
            ValueAnimator.areAnimatorsEnabled()
        ) {
            val generation = ++dialogGeneration
            content.animate().cancel()
            content.animate()
                .alpha(if (animationType == ANIMATION_FADE) 0f else 1f)
                .translationY(
                    if (animationType == ANIMATION_SLIDE) {
                        resources.displayMetrics.heightPixels * SLIDE_DISTANCE_FRACTION
                    } else {
                        0f
                    },
                )
                .setDuration(MODAL_EXIT_DURATION_MS)
                .withEndAction {
                    if (
                        dialogGeneration == generation &&
                        dialog === modal &&
                        !desiredVisible
                    ) {
                        dismissNow(modal, notify)
                    }
                }
                .start()
            return
        }
        dismissNow(modal, notify)
    }

    private fun dismissNow(modal: Dialog, notify: Boolean) {
        if (dialog !== modal) return
        ++dialogGeneration
        content.animate().cancel()
        content.alpha = 1f
        content.translationY = 0f
        val wasShowing = modal.isShowing
        if (focusKeyboard) {
            modal.currentFocus?.let { focus ->
                val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as? InputMethodManager
                keyboard?.hideSoftInputFromWindow(focus.windowToken, 0)
                focus.clearFocus()
            }
            previousFocus = null
        }
        modal.dismiss()
        dialog = null
        lastOrientation = null
        if (!focusKeyboard) restoreFocus()
        if (notify && wasShowing) {
            onDismiss?.invoke()
        }
    }

    private fun dispatchOrientation(force: Boolean) {
        val orientation = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> ORIENTATION_LANDSCAPE
            else -> ORIENTATION_PORTRAIT
        }
        if (!force && lastOrientation == orientation) return
        lastOrientation = orientation
        onOrientationChange?.invoke(orientation)
    }

    private fun restoreFocus() {
        previousFocus?.get()?.let { focus ->
            if (focus.isAttachedToWindow && focus.visibility == View.VISIBLE) {
                focus.post { focus.requestFocus() }
            }
        }
        previousFocus = null
    }

    private fun View.findFirstFocusable(): View? {
        if (this !== content && isFocusable && isEnabled && visibility == View.VISIBLE) {
            return this
        }
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findFirstFocusable()?.let { return it }
        }
        return null
    }

    private fun View.findFirstEditText(): EditText? {
        if (this is EditText && isEnabled && visibility == View.VISIBLE) return this
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findFirstEditText()?.let { return it }
        }
        return null
    }

    private companion object {
        const val PRESENTATION_DIALOG = 2
        const val PRESENTATION_SHEET = 3
        const val ANIMATION_NONE = 1
        const val ANIMATION_SLIDE = 2
        const val ANIMATION_FADE = 3
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
        const val MODAL_ENTER_DURATION_MS = 225L
        const val MODAL_EXIT_DURATION_MS = 125L
        const val SLIDE_DISTANCE_FRACTION = 0.25f
    }
}
