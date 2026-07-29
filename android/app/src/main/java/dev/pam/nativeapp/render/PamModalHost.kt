package dev.pam.nativeapp.render

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Outline
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.VelocityTracker
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import dev.pam.nativeapp.PamActivity
import java.lang.ref.WeakReference

internal class PamModalHost(context: Context) : FrameLayout(context) {
    private val content = PamModalContent(context)
    private val handle = View(context)
    private var dialog: Dialog? = null
    private var dialogBackCallback: OnBackInvokedCallback? = null
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
    private var bottomSheetSnapPoints = listOf(0.5f, 0.9f)
    private var bottomSheetIndex = 0
    private var bottomSheetDismissible = true
    private var bottomSheetBackdropDismiss = true
    private var bottomSheetHandleVisible = true
    private var bottomSheetDragEnabled = true
    private var bottomSheetCornerRadius = 20f
    private var bottomSheetKeyboardBehavior = KEYBOARD_INTERACTIVE
    private var onBottomSheetChange: ((Int, Float) -> Unit)? = null
    private var onBottomSheetDismiss: (() -> Unit)? = null
    private var dragStartY = 0f
    private var dragActive = false
    private var dragFromHandle = false
    private var dragVelocity: VelocityTracker? = null

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
            updateBottomSheetChrome()
        }
        handle.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(2f)
            setColor(Color.argb(112, 120, 120, 128))
        }
        content.addView(
            handle,
            FrameLayout.LayoutParams(dp(36f).toInt(), dp(4f).toInt()),
        )
        content.observeMotion = ::onBottomSheetMotion
    }

    fun insert(view: View, index: Int) {
        val contentCount = content.childCount - 1
        content.addView(view, index.coerceIn(0, contentCount))
        handle.bringToFront()
        updateBottomSheetChrome()
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

    fun setBottomSheetSnapPoints(points: List<Float>) {
        if (points.isEmpty()) return
        bottomSheetSnapPoints = points
            .map { it.coerceIn(0.05f, 1f) }
            .distinct()
            .sorted()
            .take(16)
        bottomSheetIndex = bottomSheetIndex.coerceIn(0, bottomSheetSnapPoints.lastIndex)
        dialog?.let(::applyWindowLayout)
    }

    fun setBottomSheetIndex(value: Int, notify: Boolean = false) {
        val next = value.coerceIn(0, bottomSheetSnapPoints.lastIndex)
        if (bottomSheetIndex == next) return
        bottomSheetIndex = next
        dialog?.let(::applyWindowLayout)
        if (notify) onBottomSheetChange?.invoke(next, bottomSheetSnapPoints[next])
    }

    fun setBottomSheetDismissible(value: Boolean) {
        bottomSheetDismissible = value
    }

    fun setBottomSheetBackdropDismiss(value: Boolean) {
        bottomSheetBackdropDismiss = value
    }

    fun setBottomSheetHandleVisible(value: Boolean) {
        bottomSheetHandleVisible = value
        updateBottomSheetChrome()
    }

    fun setBottomSheetDragEnabled(value: Boolean) {
        bottomSheetDragEnabled = value
    }

    fun setBottomSheetCornerRadius(value: Float) {
        bottomSheetCornerRadius = value.coerceIn(0f, 128f)
        updateBottomSheetChrome()
    }

    fun setBottomSheetKeyboardBehavior(value: Int) {
        bottomSheetKeyboardBehavior = value.coerceIn(KEYBOARD_INTERACTIVE, KEYBOARD_FILL_PARENT)
        dialog?.let(::applyWindowConfiguration)
    }

    fun setBottomSheetCallbacks(
        onChange: ((Int, Float) -> Unit)?,
        onDismiss: (() -> Unit)?,
    ) {
        onBottomSheetChange = onChange
        onBottomSheetDismiss = onDismiss
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
        destroyDialog(notify = false)
        content.removeAllViews()
        onRequestClose = null
        onShow = null
        onDismiss = null
        onOrientationChange = null
        onBottomSheetChange = null
        onBottomSheetDismiss = null
        dragVelocity?.recycle()
        dragVelocity = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleUpdate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(updateRunnable)
        updateScheduled = false
        destroyDialog(notify = false)
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
        if (active != null) {
            previousFocus = WeakReference(rootView.findFocus())
            val generation = ++dialogGeneration
            active.show()
            if (dialogGeneration != generation || !desiredVisible) {
                active.hide()
                return
            }
            applyWindowConfiguration(active)
            applyWindowLayout(active)
            animateEntrance()
            dispatchOrientation(force = true)
            onShow?.invoke()
            focusModalContent(active)
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
                    requestCloseFromBack()
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
            registerDialogBackCallback(modal)
            applyWindowConfiguration(modal)
            applyWindowLayout(modal)
            animateEntrance()
            dispatchOrientation(force = true)
            onShow?.invoke()
            focusModalContent(modal)
        }
    }

    private fun focusModalContent(modal: Dialog) {
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

    private fun requestClose() {
        if (!bottomSheetDismissible && presentation == PRESENTATION_SHEET) return
        desiredVisible = false
        val callback = onRequestClose
        if (callback != null) {
            callback()
            scheduleUpdate()
            return
        }
        dismiss(notify = true, animated = true)
    }

    private fun registerDialogBackCallback(modal: Dialog) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return
        }
        dialogBackCallback?.let {
            modal.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
        }
        dialogBackCallback = OnBackInvokedCallback { requestCloseFromBack() }.also { callback ->
            modal.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                callback,
            )
        }
    }

    private fun unregisterDialogBackCallback(modal: Dialog) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return
        }
        dialogBackCallback?.let {
            modal.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
        }
        dialogBackCallback = null
    }

    private fun requestCloseFromBack() {
        (context as? PamActivity)?.suppressNextPamBack()
        requestClose()
    }

    @Suppress("DEPRECATION")
    private fun applyWindowConfiguration(modal: Dialog) {
        modal.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setFormat(PixelFormat.TRANSLUCENT)
            decorView.setBackgroundColor(Color.TRANSPARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0f }
            val adjustMode = when {
                focusKeyboard -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                presentation != PRESENTATION_SHEET ->
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                bottomSheetKeyboardBehavior == KEYBOARD_EXTEND ->
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
                bottomSheetKeyboardBehavior == KEYBOARD_FILL_PARENT ->
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                else -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            }
            setSoftInputMode(
                adjustMode or
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
        val availableHeight = resources.displayMetrics.heightPixels
        val sheetHeight = (availableHeight * bottomSheetSnapPoints[bottomSheetIndex])
            .toInt()
            .coerceAtLeast(1)
        repeat(content.childCount) { index ->
            val child = content.getChildAt(index)
            if (child === handle) return@repeat
            child.layoutParams = when (presentation) {
                PRESENTATION_SHEET -> FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    sheetHeight,
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
        updateBottomSheetChrome()
    }

    private fun onBottomSheetMotion(event: MotionEvent) {
        if (presentation != PRESENTATION_SHEET) return
        val sheet = sheetChild() ?: return
        val sheetTop = content.height - sheet.height
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = event.y
                dragFromHandle = event.y in sheetTop.toFloat()..(sheetTop + dp(44f))
                dragActive = false
                dragVelocity?.recycle()
                dragVelocity = VelocityTracker.obtain().also { it.addMovement(event) }
            }
            MotionEvent.ACTION_MOVE -> {
                dragVelocity?.addMovement(event)
                val delta = event.y - dragStartY
                if (
                    bottomSheetDragEnabled &&
                    !dragActive &&
                    kotlin.math.abs(delta) >= dp(8f) &&
                    (dragFromHandle || delta > 0 && !sheet.canScrollVertically(-1))
                ) {
                    dragActive = true
                }
                if (dragActive) {
                    val translation = delta.coerceAtLeast(
                        -(content.height - sheet.height).toFloat(),
                    )
                    sheetChildren().forEach { it.translationY = translation }
                    handle.translationY = translation
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragVelocity?.addMovement(event)
                dragVelocity?.computeCurrentVelocity(1_000)
                val velocityY = dragVelocity?.yVelocity ?: 0f
                val delta = event.y - dragStartY
                if (dragActive && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                    settleBottomSheet(delta, velocityY)
                } else {
                    resetSheetTranslation()
                    if (
                        event.actionMasked == MotionEvent.ACTION_UP &&
                        event.y < sheetTop &&
                        bottomSheetBackdropDismiss
                    ) {
                        requestClose()
                    }
                }
                dragVelocity?.recycle()
                dragVelocity = null
                dragActive = false
            }
        }
    }

    private fun settleBottomSheet(delta: Float, velocityY: Float) {
        val height = content.height.coerceAtLeast(1)
        val current = bottomSheetSnapPoints[bottomSheetIndex]
        val projected = current - (delta + velocityY * 0.12f) / height
        if (
            bottomSheetDismissible &&
            bottomSheetIndex == 0 &&
            projected < bottomSheetSnapPoints.first() * 0.55f
        ) {
            onBottomSheetDismiss?.invoke()
            requestClose()
            return
        }
        val next = bottomSheetSnapPoints.indices.minByOrNull { index ->
            kotlin.math.abs(bottomSheetSnapPoints[index] - projected)
        } ?: bottomSheetIndex
        bottomSheetIndex = next
        dialog?.let(::applyWindowLayout)
        resetSheetTranslation()
        onBottomSheetChange?.invoke(next, bottomSheetSnapPoints[next])
    }

    private fun resetSheetTranslation() {
        sheetChildren().forEach { child ->
            child.animate().translationY(0f).setDuration(180L).start()
        }
        handle.animate().translationY(0f).setDuration(180L).start()
    }

    private fun sheetChildren(): List<View> =
        buildList {
            repeat(content.childCount) { index ->
                content.getChildAt(index).takeIf { it !== handle }?.let(::add)
            }
        }

    private fun sheetChild(): View? = sheetChildren().firstOrNull()

    private fun updateBottomSheetChrome() {
        val sheet = sheetChild()
        handle.visibility = if (
            presentation == PRESENTATION_SHEET &&
            bottomSheetHandleVisible &&
            desiredVisible
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (sheet == null || presentation != PRESENTATION_SHEET) return
        sheet.clipToOutline = bottomSheetCornerRadius > 0f
        sheet.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0,
                    0,
                    view.width,
                    view.height + dp(bottomSheetCornerRadius).toInt(),
                    dp(bottomSheetCornerRadius),
                )
            }
        }
        sheet.invalidateOutline()
        handle.x = (content.width - handle.layoutParams.width) / 2f
        handle.y = (content.height - sheet.height + dp(10f))
        handle.bringToFront()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

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
        modal.hide()
        lastOrientation = null
        if (!focusKeyboard) restoreFocus()
        if (notify && wasShowing) {
            onDismiss?.invoke()
        }
    }

    private fun destroyDialog(notify: Boolean) {
        val modal = dialog ?: return
        ++dialogGeneration
        content.animate().cancel()
        content.alpha = 1f
        content.translationY = 0f
        val wasShowing = modal.isShowing
        unregisterDialogBackCallback(modal)
        modal.dismiss()
        dialog = null
        lastOrientation = null
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
        const val KEYBOARD_INTERACTIVE = 1
        const val KEYBOARD_EXTEND = 2
        const val KEYBOARD_FILL_PARENT = 3
        const val MODAL_ENTER_DURATION_MS = 225L
        const val MODAL_EXIT_DURATION_MS = 125L
        const val SLIDE_DISTANCE_FRACTION = 0.25f
    }
}

private class PamModalContent(context: Context) : FrameLayout(context) {
    var observeMotion: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        observeMotion?.invoke(event)
        return super.dispatchTouchEvent(event)
    }
}
