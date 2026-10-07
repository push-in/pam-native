package dev.pam.nativeapp.render

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Outline
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.VelocityTracker
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.pam.nativeapp.R
import dev.pam.nativeapp.PamActivity
import java.lang.ref.WeakReference

// The legacy soft-input flags remain necessary for the API 26 compatibility
// floor. API 30+ insets are handled separately by WindowInsetsCompat below;
// this function only configures the window-manager fallback contract.
@Suppress("DEPRECATION")
internal fun modalSoftInputAdjustMode(
    focusKeyboard: Boolean,
    presentation: Int,
    bottomSheetKeyboardBehavior: Int,
): Int = when {
    focusKeyboard -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    presentation != 3 -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    bottomSheetKeyboardBehavior == 2 -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    bottomSheetKeyboardBehavior == 3 -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    else -> WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
}

/** Engine surface policy: Modal/BottomSheet Dialogs fit the system bars unless translucent. */
internal const val SURFACE_POLICY_SYSTEM_WINDOWS = 1

/** Engine surface policy: Modal/BottomSheet Dialogs are always edge-to-edge. */
internal const val SURFACE_POLICY_EDGE_TO_EDGE_WINDOWS = 2

/**
 * How the engine must treat PamModalHost's Dialog windows, so a SafeAreaView
 * inside a modal uses that window's own insets. Up to Android 14 a
 * non-translucent Dialog fits the system bars (it already starts below the
 * status bar and ends above the navigation bar). Android 15+ enforces
 * edge-to-edge for apps targeting SDK 35+, which ignores
 * `setDecorFitsSystemWindows(true)`: every Dialog then extends under the bars.
 */
internal fun modalWindowSurfacePolicy(sdkInt: Int, targetSdk: Int): Int =
    if (sdkInt >= 35 && targetSdk >= 35) {
        SURFACE_POLICY_EDGE_TO_EDGE_WINDOWS
    } else {
        SURFACE_POLICY_SYSTEM_WINDOWS
    }

internal fun modalWindowSurfacePolicy(context: android.content.Context): Int =
    modalWindowSurfacePolicy(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion)

internal fun interactiveBottomSheetLayout(
    baseHeight: Int,
    keyboardInset: Int,
): Pair<Int, Float> {
    val inset = keyboardInset.coerceAtLeast(0)
    val translation = if (inset == 0) 0f else -inset.toFloat()
    return baseHeight.coerceAtLeast(1) to translation
}

/**
 * How far an interactive sheet resting at [contentBottom] (window
 * coordinates) must rise so its bottom edge sits on the IME top of a window
 * [windowHeight] tall whose bottom [imeInset] pixels are covered.
 */
internal fun sheetKeyboardLift(contentBottom: Int, windowHeight: Int, imeInset: Int): Int {
    if (imeInset <= 0) return 0
    return (contentBottom - (windowHeight - imeInset)).coerceAtLeast(0)
}

/**
 * IME pixels (from the window bottom) a modal's own content must avoid:
 * everything over full-screen and dialog modals, what an interactive sheet's
 * lift leaves, nothing for pan/resize sheets (their window moves instead).
 */
internal fun modalSurfaceKeyboardInset(
    imeInset: Int,
    presentation: Int,
    interactiveSheet: Boolean,
    sheetLift: Int,
): Int = when {
    imeInset <= 0 -> 0
    presentation != 3 -> imeInset
    interactiveSheet -> (imeInset - sheetLift.coerceAtLeast(0)).coerceAtLeast(0)
    else -> 0
}

internal fun blocksModalDismissal(dismissible: Boolean): Boolean = !dismissible

/**
 * Height a bottom sheet's percentage snap points resolve against
 * (@gorhom/bottom-sheet: the container minus its top safe-area inset).
 *
 * An edge-to-edge sheet window spans the whole [windowHeight]: its snap base
 * is the window minus the top inset, and the sheet reaches the screen bottom
 * behind the navigation bar. A fitted (base) sheet window is laid out between
 * the system bars, so its base is the window minus the top and bottom bar
 * insets and the sheet rests on the navigation bar; the top inset is never
 * subtracted twice. Once the window is laid out, [laidOutContainerHeight]
 * (the real container) replaces the pre-layout window estimate.
 */
internal fun sheetAvailableHeight(
    laidOutContainerHeight: Int?,
    windowHeight: Int,
    topInset: Int,
    bottomInset: Int,
    edgeToEdge: Boolean,
): Int {
    val container = laidOutContainerHeight?.takeIf { it > 0 }
        ?: if (edgeToEdge) windowHeight else windowHeight - topInset - bottomInset
    return (container - if (edgeToEdge) topInset else 0).coerceAtLeast(1)
}

internal fun isPointOutsideModalChild(
    x: Float,
    y: Float,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
): Boolean = x < left || x >= right || y < top || y >= bottom

internal class PamModalHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val onBackConsumed: (() -> Unit)? = null,
) : FrameLayout(context, attrs, defStyleAttr) {
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
    private var autoFocusKeyboardPending = false
    private val clearAutoFocusKeyboard = Runnable { finishAutoFocusKeyboard() }
    private var onRequestClose: (() -> Unit)? = null
    private var onShow: (() -> Unit)? = null
    private var onDismiss: (() -> Unit)? = null
    private var onOrientationChange: ((Int) -> Unit)? = null
    private var previousFocus: WeakReference<View>? = null
    private var lastOrientation: Int? = null
    private var dialogGeneration = 0L
    private var slideFadeAnimator: ValueAnimator? = null
    private var updateScheduled = false
    private var bottomSheetSnapPoints = listOf(0.5f, 0.9f)
    private var bottomSheetIndex = 0
    private var bottomSheetDismissible = true
    private var bottomSheetBackdropDismiss = true
    private var bottomSheetHandleVisible = true
    private var bottomSheetDragEnabled = true
    private var bottomSheetCornerRadius = 20f
    private var bottomSheetKeyboardBehavior = KEYBOARD_INTERACTIVE
    private var bottomSheetKeyboardInset = 0
    private var sheetKeyboardTranslation = 0f
    private var sheetImeAnimating = false
    private var surfaceKeyboardInset = 0
    private val surfaceKeyboardListeners = LinkedHashSet<(Int, Boolean) -> Unit>()

    /**
     * The IME of this modal's window that its content still has to avoid,
     * in pixels from the window bottom (0 when hidden), and whether it comes
     * from a running IME animation frame. KeyboardAvoidingViews inside the
     * modal read it instead of the covered activity window, which never sees
     * this window's keyboard.
     */
    internal var onSurfaceKeyboardInset: ((Int, Boolean) -> Unit)? = null
    private var lastSheetHeight = 0
    private val backdropDrawable = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
    private var backdropAnimator: android.animation.ValueAnimator? = null
    private var onBottomSheetChange: ((Int, Float) -> Unit)? = null
    private var onBottomSheetDismiss: (() -> Unit)? = null
    private var dragStartY = 0f
    private var dragActive = false
    private var dragFromHandle = false
    private var dragScrollChain: List<View> = emptyList()
    private var modalBackdropPressed = false
    private var dragVelocity: VelocityTracker? = null

    /** Container the current sheet size was resolved against (0 = pre-layout estimate). */
    private var sheetLayoutContainerHeight = 0
    private var sheetLayoutContainerWidth = 0

    private val updateRunnable = Runnable {
        updateScheduled = false
        updateDialog()
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val destroyDetachedDialog = Runnable {
        if (!isAttachedToWindow) destroyDialog(notify = false)
    }

    init {
        visibility = View.INVISIBLE
        content.clipChildren = false
        content.clipToPadding = false
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val shown = dialog?.takeIf { it.isShowing }
            if (shown != null) {
                dispatchOrientation(force = false)
                // The first presentation is sized before its window exists;
                // re-resolve the snap point against the laid-out container
                // (and after rotation or split screen) within this same
                // traversal, before anything is drawn. IME-driven height
                // changes keep their keyboard behavior.
                if (
                    presentation == PRESENTATION_SHEET &&
                    content.height > 0 &&
                    (sheetLayoutContainerHeight == 0 || content.width != sheetLayoutContainerWidth)
                ) {
                    applyWindowLayout(shown)
                }
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
        content.observeMotion = ::onModalMotion
    }

    fun insert(view: View, index: Int) {
        val contentCount = content.childCount - 1
        content.addView(view, index.coerceIn(0, contentCount))
        handle.bringToFront()
        updateBottomSheetChrome()
    }

    internal fun usesWindowSizedContent(): Boolean = presentation != 2 && presentation != 3

    fun setPresentation(value: Int) {
        if (presentation == value) return
        presentation = value
        dialog?.let {
            applyDismissPolicy(it)
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
        animationType = value.coerceIn(ANIMATION_NONE, ANIMATION_SLIDE_FADE)
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
        dialog?.let(::applyDismissPolicy)
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

    @Suppress("DEPRECATION")
    fun applyStatusBar(
        color: Int,
        useDarkIcons: Boolean,
        hidden: Boolean,
        translucent: Boolean,
    ) {
        val window = dialog?.window ?: return
        if (!translucent) {
            window.statusBarColor = color
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = useDarkIcons
        if (hidden) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
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

    /**
     * An `autoFocus` input is about to take focus in this window. The window
     * keeps `adjustNothing` (interactive sheets), so with an unspecified
     * soft-input state the system hides the IME whenever the window gains
     * focus, which discarded the keyboard requested for the input (Android
     * 11-12: the request raced the window-focus hide). Until the IME shows,
     * ask the system itself to show it for the focused editor on focus gain.
     */
    fun prepareAutoFocusKeyboard() {
        removeCallbacks(clearAutoFocusKeyboard)
        postDelayed(clearAutoFocusKeyboard, AUTO_FOCUS_KEYBOARD_WINDOW_MS)
        if (autoFocusKeyboardPending) return
        autoFocusKeyboardPending = true
        dialog?.let(::applyWindowConfiguration)
    }

    private fun finishAutoFocusKeyboard() {
        removeCallbacks(clearAutoFocusKeyboard)
        if (!autoFocusKeyboardPending) return
        autoFocusKeyboardPending = false
        dialog?.let(::applyWindowConfiguration)
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
        mainHandler.removeCallbacks(destroyDetachedDialog)
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

    fun isPresented(): Boolean = dialog?.isShowing == true

    /**
     * Activity-level fallback for synthetic/OEM Back dispatch that reaches
     * both the dialog and its host activity. A visible modal always owns Back;
     * the activity must never pop its navigator or finish underneath it.
     */
    fun consumeActivityBack(): Boolean {
        if (!isPresented()) return false
        if (desiredVisible) requestCloseFromBack()
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mainHandler.removeCallbacks(destroyDetachedDialog)
        scheduleUpdate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(updateRunnable)
        updateScheduled = false
        // A presented window survives a detach that is undone in the same
        // main-thread turn (the host re-attached elsewhere by one commit):
        // tearing it down there re-created the window and lost its focused
        // input and IME. A host that stays detached still closes its window.
        val activity = pamActivity()
        if (
            dialog?.isShowing == true &&
            activity != null &&
            !activity.isFinishing &&
            !activity.isDestroyed &&
            !activity.isChangingConfigurations
        ) {
            mainHandler.removeCallbacks(destroyDetachedDialog)
            mainHandler.post(destroyDetachedDialog)
        } else {
            destroyDialog(notify = false)
        }
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
        Dialog(context, R.style.Theme_PamNative_Modal).also { modal ->
            // Track the window before it is shown: a close()/removal that
            // runs while show() dispatches attach/focus callbacks must still
            // find and dismiss it, never leaving an orphan dimmed window.
            dialog = modal
            modal.requestWindowFeature(Window.FEATURE_NO_TITLE)
            (content.parent as? ViewGroup)?.removeView(content)
            modal.setContentView(content)
            observeDialogIme(modal)
            applyDismissPolicy(modal)
            modal.setOnCancelListener {
                // Platform cancellation (legacy Back) closes this window only.
                pamActivity()?.suppressNextPamBack()
                requestClose()
            }
            modal.setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK) {
                    false
                } else {
                    // Own the whole Back gesture (down and up) so neither half
                    // reaches the activity's navigator below this window.
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        requestCloseFromBack()
                    }
                    true
                }
            }
            applyBackdrop()
            applyWindowConfiguration(modal)
            modal.show()
            if (dialog !== modal || dialogGeneration != generation || !desiredVisible) {
                modal.dismiss()
                if (dialog === modal) dialog = null
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

    /**
     * An interactive sheet's window keeps `adjustNothing`, so it must track
     * the IME itself. The insets are read on the dialog's decor view: a
     * window that fits system windows consumes them before they reach the
     * content (Android 11-14), which left the sheet behind the keyboard. The
     * covered activity window ignores this IME (see PamRootHost).
     */
    private fun observeDialogIme(modal: Dialog) {
        val decor = modal.window?.decorView ?: return
        ViewCompat.setOnApplyWindowInsetsListener(decor) { view, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) finishAutoFocusKeyboard()
            if (!sheetImeAnimating) {
                updateSheetKeyboardInset(sheetKeyboardLiftFor(insets))
                publishSurfaceKeyboard(surfaceKeyboardInsetFor(insets), animating = false)
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decor.setWindowInsetsAnimationCallback(
                object : android.view.WindowInsetsAnimation.Callback(
                    DISPATCH_MODE_CONTINUE_ON_SUBTREE,
                ) {
                    override fun onPrepare(animation: android.view.WindowInsetsAnimation) {
                        if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                            sheetImeAnimating = true
                        }
                    }

                    override fun onProgress(
                        insets: WindowInsets,
                        running: MutableList<android.view.WindowInsetsAnimation>,
                    ): WindowInsets {
                        if (sheetImeAnimating && running.any { it.typeMask and WindowInsets.Type.ime() != 0 }) {
                            val frame = WindowInsetsCompat.toWindowInsetsCompat(insets, decor)
                            followSheetKeyboard(sheetKeyboardLiftFor(frame))
                            publishSurfaceKeyboard(surfaceKeyboardInsetFor(frame), animating = true)
                        }
                        return insets
                    }

                    override fun onEnd(animation: android.view.WindowInsetsAnimation) {
                        if (animation.typeMask and WindowInsets.Type.ime() == 0) return
                        sheetImeAnimating = false
                        val settled = ViewCompat.getRootWindowInsets(decor)
                        bottomSheetKeyboardInset = -1
                        updateSheetKeyboardInset(settled?.let(::sheetKeyboardLiftFor) ?: 0)
                        publishSurfaceKeyboard(
                            settled?.let(::surfaceKeyboardInsetFor) ?: 0,
                            animating = false,
                            force = true,
                        )
                    }
                },
            )
        }
    }

    /**
     * The part of this window's IME the modal content must avoid itself:
     * the whole IME over a full-screen or dialog modal; over an interactive
     * sheet only what the sheet's own lift leaves (nothing for a sheet that
     * rests on the window bottom); none for pan/resize sheets, whose window
     * already moves or shrinks with the IME.
     */
    private fun surfaceKeyboardInsetFor(insets: WindowInsetsCompat): Int {
        if (!desiredVisible || !insets.isVisible(WindowInsetsCompat.Type.ime())) return 0
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom.coerceAtLeast(0)
        return modalSurfaceKeyboardInset(
            imeInset = ime,
            presentation = presentation,
            interactiveSheet = usesInteractiveKeyboard(),
            sheetLift = if (usesInteractiveKeyboard()) sheetKeyboardLiftFor(insets) else 0,
        )
    }

    private fun publishSurfaceKeyboard(inset: Int, animating: Boolean, force: Boolean = false) {
        if (!force && inset == surfaceKeyboardInset && !animating) return
        surfaceKeyboardInset = inset
        onSurfaceKeyboardInset?.invoke(inset, animating)
        surfaceKeyboardListeners.toList().forEach { it(inset, animating) }
    }

    /** IME inset (px from the window bottom) last published for this modal's content. */
    internal fun currentSurfaceKeyboardInset(): Int = surfaceKeyboardInset

    /** Screen y of the bottom of this modal's window (its IME insets are measured from it). */
    internal fun windowBottomOnScreen(): Int? {
        val decor = dialog?.window?.decorView?.takeIf { it.isAttachedToWindow } ?: return null
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        return location[1] + decor.height
    }

    internal fun addSurfaceKeyboardListener(listener: (Int, Boolean) -> Unit): AutoCloseable {
        surfaceKeyboardListeners += listener
        listener(surfaceKeyboardInset, false)
        return AutoCloseable { surfaceKeyboardListeners -= listener }
    }

    private fun sheetKeyboardLiftFor(insets: WindowInsetsCompat): Int {
        if (!insets.isVisible(WindowInsetsCompat.Type.ime())) return 0
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val root = content.rootView ?: return 0
        if (root.height <= 0 || content.height <= 0) return 0
        val location = IntArray(2)
        content.getLocationInWindow(location)
        val contentBottom = location[1] - content.translationY.toInt() + content.height
        return sheetKeyboardLift(contentBottom, root.height, ime)
    }

    private fun updateSheetKeyboardInset(lift: Int) {
        if (bottomSheetKeyboardInset == lift) return
        bottomSheetKeyboardInset = lift
        dialog?.let(::applyWindowLayout)
    }

    /** Moves the sheet with the IME frame by frame (gorhom `interactive`). */
    private fun followSheetKeyboard(lift: Int) {
        if (!usesInteractiveKeyboard()) return
        sheetKeyboardTranslation = -lift.coerceAtLeast(0).toFloat()
        if (backdropAnimator?.isRunning == true || dragActive) return
        (sheetChildren() + handle).forEach { view ->
            view.animate().cancel()
            view.translationY = sheetKeyboardTranslation
        }
    }

    private fun usesInteractiveKeyboard(): Boolean =
        presentation == PRESENTATION_SHEET && bottomSheetKeyboardBehavior == KEYBOARD_INTERACTIVE

    private fun focusModalContent(modal: Dialog) {
        content.post {
            // An auto-focused input that already took focus keeps it: moving
            // focus to the first focusable (a header button) would drop the
            // keyboard it is opening.
            if (dialog === modal && modal.isShowing && (focusKeyboard || content.findFocus() == null)) {
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
        if (blocksModalDismissal(bottomSheetDismissible)) return
        desiredVisible = false
        val callback = onRequestClose
        if (callback != null) {
            callback()
            scheduleUpdate()
            return
        }
        dismiss(notify = true, animated = true)
    }

    private fun applyDismissPolicy(modal: Dialog) {
        val dismissible = !blocksModalDismissal(bottomSheetDismissible)
        modal.setCancelable(dismissible)
        // Sheets own their backdrop gesture inside PamModalContent. Dialog
        // windows have platform decor insets outside that content, so Android
        // itself must observe those touches and report them through onCancel.
        modal.setCanceledOnTouchOutside(
            presentation != PRESENTATION_SHEET && dismissible,
        )
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
        if (hideVisibleKeyboard()) return
        onBackConsumed?.invoke()
        pamActivity()?.suppressNextPamBack()
        requestClose()
    }

    private fun pamActivity(): PamActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is PamActivity) return current
            current = current.baseContext
        }
        return current as? PamActivity
    }

    private fun hideVisibleKeyboard(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            return false
        }
        val focused = dialog?.currentFocus ?: return false
        val decor = dialog?.window?.decorView ?: return false
        if (decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true) {
            return false
        }
        val keyboard = context.getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        keyboard?.hideSoftInputFromWindow(focused.windowToken, 0)
        return true
    }

    @Suppress("DEPRECATION")
    private fun applyWindowConfiguration(modal: Dialog) {
        modal.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setFormat(PixelFormat.TRANSLUCENT)
            decorView.setBackgroundColor(Color.TRANSPARENT)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0f }
            val adjustMode = modalSoftInputAdjustMode(
                focusKeyboard = focusKeyboard,
                presentation = presentation,
                bottomSheetKeyboardBehavior = bottomSheetKeyboardBehavior,
            )
            setSoftInputMode(
                adjustMode or
                    if (focusKeyboard || autoFocusKeyboardPending) {
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
            val edgeToEdge = statusBarTranslucent || navigationBarTranslucent
            if (edgeToEdge) {
                WindowCompat.enableEdgeToEdge(this)
                WindowCompat.setDecorFitsSystemWindows(this, false)
                statusBarColor = Color.TRANSPARENT
                navigationBarColor = Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    isStatusBarContrastEnforced = false
                    isNavigationBarContrastEnforced = false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    attributes = attributes.apply {
                        layoutInDisplayCutoutMode =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            } else {
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                            }
                    }
                }
            } else {
                WindowCompat.setDecorFitsSystemWindows(this, true)
            }
        }
        applyBackdrop()
    }

    private fun applyWindowLayout(modal: Dialog) {
        val laidOut = content.height.takeIf { it > 0 }
        sheetLayoutContainerHeight = laidOut ?: 0
        sheetLayoutContainerWidth = if (laidOut != null) content.width else 0
        val availableHeight = if (presentation == PRESENTATION_SHEET) {
            val insets = sheetSystemBarInsets()
            sheetAvailableHeight(
                laidOutContainerHeight = laidOut,
                windowHeight = rootView.height.takeIf { it > 0 }
                    ?: modal.window?.decorView?.height?.takeIf { it > 0 }
                    ?: resources.displayMetrics.heightPixels,
                topInset = insets.top,
                bottomInset = insets.bottom,
                edgeToEdge = statusBarTranslucent || navigationBarTranslucent,
            )
        } else {
            resources.displayMetrics.heightPixels
        }
        val baseSheetHeight = (availableHeight * bottomSheetSnapPoints[bottomSheetIndex])
            .toInt()
            .coerceAtLeast(1)
        val (sheetHeight, keyboardTranslation) = if (
            presentation == PRESENTATION_SHEET &&
            bottomSheetKeyboardBehavior == KEYBOARD_INTERACTIVE
        ) {
            interactiveBottomSheetLayout(
                baseHeight = baseSheetHeight,
                keyboardInset = bottomSheetKeyboardInset,
            )
        } else {
            baseSheetHeight to 0f
        }
        lastSheetHeight = sheetHeight
        val sheetAnimating = presentation == PRESENTATION_SHEET && backdropAnimator?.isRunning == true
        sheetKeyboardTranslation = keyboardTranslation
        repeat(content.childCount) { index ->
            val child = content.getChildAt(index)
            if (child === handle) return@repeat
            // Full-screen content is placed by the renderer from its engine
            // frames (windowSizedModalChildPlacement): forcing MATCH_PARENT
            // here stretched a short bottom-anchored child over the window.
            if (usesWindowSizedContent()) return@repeat
            child.layoutParams = modalChildLayoutParams(presentation, sheetHeight)
            if (!sheetAnimating) child.translationY = keyboardTranslation
        }
        if (!sheetAnimating) handle.translationY = keyboardTranslation
        modal.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        updateBottomSheetChrome()
    }

    /**
     * System-bar and cutout insets of the window the sheet covers. The
     * dialog's own insets exist only after it is attached; until then the
     * covered activity window reports the same edges.
     */
    private fun sheetSystemBarInsets(): androidx.core.graphics.Insets {
        val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val source = content.rootWindowInsets ?: rootWindowInsets
        return source?.let {
            WindowInsetsCompat.toWindowInsetsCompat(it).getInsetsIgnoringVisibility(types)
        } ?: androidx.core.graphics.Insets.NONE
    }

    private fun onModalMotion(event: MotionEvent) {
        if (presentation != PRESENTATION_SHEET) {
            val modalChild = sheetChild() ?: return
            val outside = isPointOutsideModalChild(
                event.x,
                event.y,
                modalChild.left,
                modalChild.top,
                modalChild.right,
                modalChild.bottom,
            )
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> modalBackdropPressed = outside
                MotionEvent.ACTION_UP -> {
                    val shouldDismiss = modalBackdropPressed && outside
                    modalBackdropPressed = false
                    if (shouldDismiss) requestClose()
                }
                MotionEvent.ACTION_CANCEL -> modalBackdropPressed = false
            }
            return
        }
        val sheet = sheetChild() ?: return
        // Where the sheet is drawn: above the keyboard while the IME is up,
        // so a tap on its upper part is not taken for a backdrop tap.
        val sheetTop = (content.height - sheet.height + sheetKeyboardTranslation).toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = event.y
                dragFromHandle = event.y in sheetTop.toFloat()..(sheetTop + dp(44f))
                // Like gorhom/react-native-bottom-sheet: a drag that starts on
                // a nested scrollable only moves the sheet once that
                // scrollable is at its top. Resolve the chain under the finger
                // now; the sheet's direct child is rarely the scroller.
                dragScrollChain = verticalScrollChainAt(content, event.x, event.y)
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
                    (
                        dragFromHandle ||
                            delta > 0 &&
                            !sheet.canScrollVertically(-1) &&
                            dragScrollChain.none { it.canScrollVertically(-1) }
                        )
                ) {
                    dragActive = true
                }
                if (dragActive) {
                    val translation = (sheetKeyboardTranslation + delta).coerceAtLeast(
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
                dragScrollChain = emptyList()
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
        val keyboardTranslation = if (
            presentation == PRESENTATION_SHEET &&
            bottomSheetKeyboardBehavior == KEYBOARD_INTERACTIVE
        ) {
            -bottomSheetKeyboardInset.coerceAtLeast(0).toFloat()
        } else {
            0f
        }
        sheetChildren().forEach { child ->
            child.animate().translationY(keyboardTranslation).setDuration(180L).start()
        }
        handle.animate().translationY(keyboardTranslation).setDuration(180L).start()
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
        backdropDrawable.color = backdropColor
        if (content.background !== backdropDrawable) content.background = backdropDrawable
    }

    /**
     * Sheets animate like @gorhom/bottom-sheet: the backdrop fades while the
     * sheet (and its handle) slide by their own height, independently.
     */
    private fun animateSheet(entering: Boolean, endAction: (() -> Unit)? = null) {
        val distance = (lastSheetHeight.takeIf { it > 0 } ?: sheetChild()?.height ?: 0).toFloat() + dp(24f)
        val movers = sheetChildren() + handle
        backdropAnimator?.cancel()
        content.alpha = 1f
        content.translationY = 0f
        val from = if (entering) 0 else backdropDrawable.alpha
        val to = if (entering) 255 else 0
        movers.forEach { view ->
            view.animate().cancel()
            if (entering) view.translationY = sheetKeyboardTranslation + distance
            view.animate()
                .translationY(if (entering) sheetKeyboardTranslation else sheetKeyboardTranslation + distance)
                .setDuration(if (entering) SHEET_ENTER_DURATION_MS else SHEET_EXIT_DURATION_MS)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                .start()
        }
        backdropAnimator = android.animation.ValueAnimator.ofInt(from, to).apply {
            duration = if (entering) SHEET_ENTER_DURATION_MS else SHEET_EXIT_DURATION_MS
            addUpdateListener { backdropDrawable.alpha = it.animatedValue as Int }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return
                    // The IME of the window below usually hides while the
                    // sheet enters; its target translation was captured at
                    // the start, so settle on the current keyboard inset.
                    if (entering) {
                        movers.forEach { view ->
                            if (view.translationY != sheetKeyboardTranslation) {
                                view.animate()
                                    .translationY(sheetKeyboardTranslation)
                                    .setDuration(SHEET_KEYBOARD_SETTLE_MS)
                                    .start()
                            }
                        }
                    }
                    endAction?.invoke()
                }
            })
            start()
        }
    }

    /**
     * `slide-fade`: the backdrop fades while the sheet/content slides from
     * fully below the screen on its own curve (RN sheet modals). One
     * frame-synchronised animator drives both on the UI thread.
     */
    private fun animateSlideFade(entering: Boolean, end: (() -> Unit)? = null) {
        slideFadeAnimator?.cancel()
        content.alpha = 1f
        content.translationY = 0f
        val backdrop = content.background as? ColorDrawable
        val distance = { child: View ->
            (content.height - child.top).coerceAtLeast(resources.displayMetrics.heightPixels / 2).toFloat()
        }
        val sheetEasing = if (entering) PamEasings.parse("ease-out-cubic") else PamEasings.parse("ease-in-quad")
        fun apply(progress: Float) {
            // progress 0 = hidden, 1 = shown
            backdrop?.alpha = (255 * (progress / SLIDE_FADE_BACKDROP_SHARE).coerceIn(0f, 1f)).toInt()
            // Fraction of the travel still hidden below the screen.
            val slide = if (entering) 1f - sheetEasing.transform(progress) else sheetEasing.transform(1f - progress)
            for (index in 0 until content.childCount) {
                val child = content.getChildAt(index)
                child.translationY = distance(child) * slide.coerceIn(0f, 1f)
            }
        }
        apply(if (entering) 0f else 1f)
        slideFadeAnimator = ValueAnimator.ofFloat(if (entering) 0f else 1f, if (entering) 1f else 0f).apply {
            duration = if (entering) SLIDE_FADE_ENTER_DURATION_MS else SLIDE_FADE_EXIT_DURATION_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    slideFadeAnimator = null
                    if (!cancelled) end?.invoke()
                }
            })
            start()
        }
    }

    private fun resetSlideFade() {
        slideFadeAnimator?.cancel()
        slideFadeAnimator = null
        (content.background as? ColorDrawable)?.alpha = 255
        if (animationType == ANIMATION_SLIDE_FADE) {
            for (index in 0 until content.childCount) content.getChildAt(index).translationY = 0f
        }
    }

    private fun animateEntrance() {
        backdropAnimator?.cancel()
        backdropDrawable.alpha = 255
        if (
            presentation == PRESENTATION_SHEET &&
            animationType != ANIMATION_NONE &&
            !PamMotionPolicy.isReduced(context)
        ) {
            content.post { animateSheet(entering = true) }
            backdropDrawable.alpha = 0
            return
        }
        content.animate().cancel()
        resetSlideFade()
        if (animationType == ANIMATION_NONE || PamMotionPolicy.isReduced(context)) {
            content.alpha = 1f
            content.translationY = 0f
            return
        }
        if (animationType == ANIMATION_SLIDE_FADE) {
            if (content.isLaidOut) animateSlideFade(entering = true) else content.post { animateSlideFade(entering = true) }
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
            !PamMotionPolicy.isReduced(context)
        ) {
            val generation = ++dialogGeneration
            if (presentation == PRESENTATION_SHEET) {
                animateSheet(entering = false) {
                    if (dialogGeneration == generation && dialog === modal && !desiredVisible) {
                        dismissNow(modal, notify)
                    }
                }
                return
            }
            content.animate().cancel()
            if (animationType == ANIMATION_SLIDE_FADE) {
                animateSlideFade(entering = false) {
                    if (dialogGeneration == generation && dialog === modal && !desiredVisible) {
                        dismissNow(modal, notify)
                    }
                }
                return
            }
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
        removeCallbacks(clearAutoFocusKeyboard)
        autoFocusKeyboardPending = false
        backdropAnimator?.cancel()
        backdropDrawable.alpha = 255
        content.animate().cancel()
        resetSlideFade()
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
        publishSurfaceKeyboard(0, animating = false)
        if (!focusKeyboard) restoreFocus()
        if (notify && wasShowing) {
            onDismiss?.invoke()
        }
    }

    private fun destroyDialog(notify: Boolean) {
        val modal = dialog ?: return
        ++dialogGeneration
        content.animate().cancel()
        resetSlideFade()
        content.alpha = 1f
        content.translationY = 0f
        val wasShowing = modal.isShowing
        unregisterDialogBackCallback(modal)
        modal.dismiss()
        dialog = null
        lastOrientation = null
        publishSurfaceKeyboard(0, animating = false)
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
        const val ANIMATION_SLIDE_FADE = 4
        const val SLIDE_FADE_ENTER_DURATION_MS = 320L
        const val SLIDE_FADE_EXIT_DURATION_MS = 220L
        const val SLIDE_FADE_BACKDROP_SHARE = 0.7f
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
        const val KEYBOARD_INTERACTIVE = 1
        const val KEYBOARD_EXTEND = 2
        const val KEYBOARD_FILL_PARENT = 3
        const val MODAL_ENTER_DURATION_MS = 225L
        const val MODAL_EXIT_DURATION_MS = 125L
        const val SHEET_ENTER_DURATION_MS = 250L
        const val SHEET_KEYBOARD_SETTLE_MS = 160L
        const val AUTO_FOCUS_KEYBOARD_WINDOW_MS = 4_000L
        const val SHEET_EXIT_DURATION_MS = 200L
        const val SLIDE_DISTANCE_FRACTION = 0.25f
    }
}

internal fun modalChildLayoutParams(
    presentation: Int,
    sheetHeight: Int,
): FrameLayout.LayoutParams = when (presentation) {
    2 -> FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
        Gravity.CENTER,
    )
    3 -> FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        sheetHeight,
        Gravity.BOTTOM,
    )
    else -> FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )
}

private class PamModalContent(context: Context) : FrameLayout(context) {
    var observeMotion: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        observeMotion?.invoke(event)
        return super.dispatchTouchEvent(event)
    }

    // Keep a gesture that starts on the otherwise empty backdrop alive until
    // ACTION_UP. Child controls still receive events first through
    // dispatchTouchEvent, so this only consumes touches no descendant handled.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * Vertically scrollable views under ([x], [y]) in [root]'s coordinates, from
 * the outermost to the innermost, following the same child order and
 * geometry (scroll offsets, translations) as touch dispatch.
 */
internal fun verticalScrollChainAt(root: View, x: Float, y: Float): List<View> {
    val chain = ArrayList<View>()
    var current: View = root
    var localX = x
    var localY = y
    var depth = 0
    while (depth++ < 64) {
        if (current !== root && (current.canScrollVertically(1) || current.canScrollVertically(-1))) {
            chain += current
        }
        val group = current as? ViewGroup ?: break
        var next: View? = null
        for (index in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(index)
            if (child.visibility != View.VISIBLE) continue
            val childX = localX + group.scrollX - child.left - child.translationX
            val childY = localY + group.scrollY - child.top - child.translationY
            if (childX >= 0f && childY >= 0f && childX < child.width && childY < child.height) {
                next = child
                localX = childX
                localY = childY
                break
            }
        }
        current = next ?: break
    }
    return chain
}
