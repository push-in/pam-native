package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.animation.ArgbEvaluator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.method.PasswordTransformationMethod
import android.text.method.TransformationMethod
import android.text.style.URLSpan
import android.text.util.Linkify
import android.util.LongSparseArray
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.DragEvent
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.Space
import dev.pam.nativeapp.BuildConfig
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
import dev.pam.nativeapp.PamActivity
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.EventKind
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.R
import dev.pam.nativeapp.views.NativeViewRegistry
import dev.pam.nativeapp.views.NativeChildVisibilityHost
import org.json.JSONArray
import java.nio.ByteOrder
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PAM's layout engine sends physical x/y coordinates. Keep FrameLayout from
 * resolving its implicit START gravity again when a parent is RTL, otherwise
 * every absolutely positioned child is anchored to the right edge.
 */
@SuppressLint("RtlHardcoded")
internal const val PAM_PHYSICAL_FRAME_GRAVITY: Int = Gravity.TOP or Gravity.LEFT

/**
 * Bottom-anchored variant of [PAM_PHYSICAL_FRAME_GRAVITY]: the child's left
 * margin is the engine's physical x, so it must not be mirrored in RTL.
 */
@SuppressLint("RtlHardcoded")
internal const val PAM_PHYSICAL_BOTTOM_FRAME_GRAVITY: Int = Gravity.BOTTOM or Gravity.LEFT

/**
 * React Native's TextView default. Lint false positive (WrongConstant):
 * TextView.setBreakStrategy is annotated with the API 29 LineBreaker
 * constants, which have the same values as the Layout constants it documents
 * (BREAK_STRATEGY_HIGH_QUALITY == 1 in both); LineBreaker itself does not
 * exist below API 29, and minSdk is 26.
 */
@SuppressLint("WrongConstant")
private fun TextView.useHighQualityLineBreaks() {
    breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
}

private const val LOCAL_MODAL_SELECTION_BEHAVIOR = 24L
private const val MAX_POOLED_CELL_VIEWS_PER_SHAPE = 24
private val PROP_KEY_WORDS = (PropKey.entries.size + 63) / 64

/** Kind plus property-key bit set of a pooled cell view. */
private class CellViewShape(private val kind: Int, private val keys: LongArray) {
    private val hash = 31 * kind + keys.contentHashCode()

    override fun equals(other: Any?): Boolean =
        other is CellViewShape && other.kind == kind && other.keys.contentEquals(keys)

    override fun hashCode(): Int = hash
}

private val POOLED_CELL_KINDS = setOf(
    NodeKind.VIEW, NodeKind.COLUMN, NodeKind.ROW, NodeKind.TEXT, NodeKind.PRESSABLE, NodeKind.IMAGE,
)
private val POOLED_CELL_VIEW_CLASSES = setOf<Class<*>>(
    PamContainer::class.java,
    TextView::class.java,
    PamPressable::class.java,
    PamImageView::class.java,
)
private const val KEYBOARD_VIEWPORT_RECONCILE_RETRIES = 8
private const val KEYBOARD_VIEWPORT_RECONCILE_RETRY_MS = 100L

internal inline fun <reified T> snapshotValues(
    size: Int,
    valueAt: (Int) -> Any?,
): List<T> {
    val snapshot = ArrayList<T>(size)
    for (position in 0 until size) {
        (valueAt(position) as? T)?.let(snapshot::add)
    }
    return snapshot
}

internal const val INPUT_ECHO_WINDOW_MS = 3_000L
private const val INPUT_ECHO_LIMIT = 64

internal fun recordInputInFlight(
    inFlight: ArrayDeque<Pair<String, Long>>,
    value: String,
    now: Long,
) {
    if (inFlight.lastOrNull()?.first == value) return
    inFlight.addLast(value to now)
    while (inFlight.size > INPUT_ECHO_LIMIT) inFlight.removeFirst()
}

/**
 * Change events are processed by PHP in order, so a rendered value equal to
 * one still in flight is the echo of that event. When the editor already
 * holds newer text the echo is stale and must be ignored; any other value is
 * an authored change (clearing after send, normalization) and is applied.
 */
internal fun isStaleInputEcho(
    inFlight: ArrayDeque<Pair<String, Long>>,
    value: String,
    current: String,
    now: Long,
): Boolean {
    while (inFlight.isNotEmpty() && now - inFlight.first().second > INPUT_ECHO_WINDOW_MS) {
        inFlight.removeFirst()
    }
    if (value == current) {
        inFlight.clear()
        return false
    }
    val index = inFlight.indexOfFirst { it.first == value }
    if (index < 0) {
        inFlight.clear()
        return false
    }
    repeat(index + 1) { inFlight.removeFirst() }
    return true
}

internal fun resolvedKeyboardInset(
    platformInset: Int,
    baselineHeight: Int,
    currentHeight: Int,
    minimumKeyboardHeight: Int,
): Int {
    val resizedInset = (baselineHeight - currentHeight)
        .takeIf { it >= minimumKeyboardHeight }
        ?: 0
    return max(platformInset, resizedInset)
}

internal fun isLocalModalSelectionEvent(properties: PropValue?): Boolean {
    val host = (properties as? PropValue.Properties)?.value ?: return false
    val behavior = (host["behavior"] as? WireValue.Integer)?.value ?: return false

    return behavior == LOCAL_MODAL_SELECTION_BEHAVIOR
}

internal fun visibleImeInset(rawInset: Int, visible: Boolean): Int =
    if (visible) rawInset.coerceAtLeast(0) else 0

internal fun keyboardTopForInset(windowBottom: Int, keyboardInset: Int): Int =
    windowBottom - keyboardInset.coerceAtLeast(0)

internal fun keyboardOverlapForBounds(
    originalBottom: Float,
    windowBottom: Int,
    keyboardInset: Int,
    offset: Int,
): Int {
    val keyboardTop = keyboardTopForInset(windowBottom, keyboardInset)
    val visibleBottom = min(originalBottom, windowBottom.toFloat())
    return max(0, (visibleBottom - keyboardTop + offset).toInt())
}

internal fun interactiveKeyboardTranslation(
    keyboardOverlap: Int,
    originalTop: Int,
    minimumTop: Int,
): Int = min(
    keyboardOverlap.coerceAtLeast(0),
    (originalTop - minimumTop).coerceAtLeast(0),
)

/**
 * A modal window's IME inset (from that window's bottom) re-expressed from
 * the bottom of the PAM host view the engine lays out against.
 */
internal fun surfaceKeyboardInsetForHost(imeInset: Int, windowBottom: Int, hostBottom: Int): Int {
    if (imeInset <= 0) return 0
    val keyboardTop = windowBottom - imeInset
    return (hostBottom - keyboardTop).coerceAtLeast(0)
}

internal fun keyboardAvoidingViewportHeight(
    baseHeight: Int,
    keyboardOverlap: Int,
    resize: Boolean,
): Int = if (resize) {
    (baseHeight - keyboardOverlap.coerceAtLeast(0)).coerceAtLeast(0)
} else {
    baseHeight.coerceAtLeast(0)
}

internal fun keyboardAvoidingBehaviorReducesViewport(behavior: Int): Boolean =
    behavior == 1 || behavior == 3

internal fun useDarkStatusBarIcons(
    systemBarsAppearance: Int?,
    darkTheme: Boolean,
    lightStatusBarMask: Int,
): Boolean = systemBarsAppearance?.let { appearance ->
    appearance and lightStatusBarMask != 0
} ?: !darkTheme

internal fun safeAreaChildCrossAxisReduction(
    mainAxisHorizontal: Boolean,
    horizontalInsets: Int,
    verticalInsets: Int,
): Pair<Int, Int> = if (mainAxisHorizontal) {
    0 to verticalInsets
} else {
    horizontalInsets to 0
}

internal fun measuredCrossAxisViewportReduction(
    mainAxisHorizontal: Boolean,
    engineWidth: Int,
    measuredWidth: Int,
    engineHeight: Int,
    measuredHeight: Int,
): Pair<Int, Int> = safeAreaChildCrossAxisReduction(
    mainAxisHorizontal = mainAxisHorizontal,
    horizontalInsets = if (measuredWidth > 0) {
        (engineWidth - measuredWidth).coerceAtLeast(0)
    } else {
        0
    },
    verticalInsets = if (measuredHeight > 0) {
        (engineHeight - measuredHeight).coerceAtLeast(0)
    } else {
        0
    },
)

internal fun safeAreaFlexViewportExtent(
    layoutExtent: Int,
    safeAreaInsets: Int,
    windowVisibleExtent: Int = layoutExtent,
): Int = (layoutExtent - safeAreaInsets).coerceAtLeast(0)
    .let { paddedExtent ->
        if (windowVisibleExtent in 1 until layoutExtent) {
            windowVisibleExtent
        } else {
            paddedExtent
        }
    }

internal fun safeAreaLayoutBoundsChanged(
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    oldLeft: Int,
    oldTop: Int,
    oldRight: Int,
    oldBottom: Int,
): Boolean =
    left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom

internal fun measuredParentExtent(measuredExtent: Int, layoutParamExtent: Int): Int =
    measuredExtent.takeIf { it > 0 }
        ?: layoutParamExtent.coerceAtLeast(0)

internal fun parentViewportMeasurementIsStale(
    measuredExtent: Int,
    layoutParamExtent: Int,
    layoutRequested: Boolean,
): Boolean =
    layoutRequested &&
        measuredExtent > 0 &&
        layoutParamExtent > 0 &&
        measuredExtent != layoutParamExtent

internal fun hostedContentExtent(
    measuredExtent: Int,
    layoutParamExtent: Int,
    nativePadding: Int,
): Int = (measuredParentExtent(measuredExtent, layoutParamExtent) - nativePadding)
    .coerceAtLeast(0)

internal fun usesNativeViewGroupPadding(kind: NodeKind): Boolean =
    kind == NodeKind.CUSTOM_VIEW

/** Physical margins of a virtualized cell root (auto margins count as zero). */
internal data class CellRootMargins(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

internal fun cellRootMargins(properties: Map<PropKey, PropValue>?): CellRootMargins {
    if (properties == null) return CellRootMargins()
    fun value(key: PropKey, fallback: Double): Double {
        val resolved = when (val raw = properties[key]) {
            is PropValue.Decimal -> raw.value
            is PropValue.Integer -> raw.value.toDouble()
            else -> fallback
        }
        return if (resolved.isFinite()) resolved else 0.0
    }
    val all = value(PropKey.MARGIN, 0.0)
    val horizontal = value(PropKey.MARGIN_HORIZONTAL, all)
    val vertical = value(PropKey.MARGIN_VERTICAL, all)
    return CellRootMargins(
        left = value(PropKey.MARGIN_LEFT, horizontal).toFloat(),
        top = value(PropKey.MARGIN_TOP, vertical).toFloat(),
        right = value(PropKey.MARGIN_RIGHT, horizontal).toFloat(),
        bottom = value(PropKey.MARGIN_BOTTOM, vertical).toFloat(),
    )
}

internal fun cellSlotFrame(rootFrame: Frame, margins: CellRootMargins): Frame = Frame(
    x = rootFrame.x - margins.left,
    y = rootFrame.y - margins.top,
    width = rootFrame.width + margins.left + margins.right,
    height = rootFrame.height + margins.top + margins.bottom,
)

internal fun engineFrameMargin(offset: Int, nativeFramePadding: Int): Int =
    offset - nativeFramePadding

internal fun resolvedAndroidLetterSpacing(
    logicalSpacing: Float,
    logicalFontSize: Float,
): Float = logicalSpacing / logicalFontSize.coerceAtLeast(1f)

internal fun resolvedLineSpacingExtra(
    logicalLineHeight: Float,
    renderedTextSizePx: Float,
    logicalFontSize: Float,
    fontMetricsHeightPx: Float,
): Float {
    val effectiveScale = renderedTextSizePx / logicalFontSize.coerceAtLeast(1f)
    val targetLineHeightPx = logicalLineHeight.coerceAtLeast(0f) * effectiveScale
    return targetLineHeightPx - fontMetricsHeightPx
}

internal data class SnappedPixelSpan(
    val offset: Int,
    val extent: Int,
)

internal data class SafeAreaInsets(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class SafeAreaBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal fun unconsumedSafeAreaInsets(
    raw: SafeAreaInsets,
    consumed: SafeAreaInsets,
): SafeAreaInsets = SafeAreaInsets(
    left = (raw.left - consumed.left).coerceAtLeast(0),
    top = (raw.top - consumed.top).coerceAtLeast(0),
    right = (raw.right - consumed.right).coerceAtLeast(0),
    bottom = (raw.bottom - consumed.bottom).coerceAtLeast(0),
)

internal fun safeAreaInsetsForBounds(
    raw: SafeAreaInsets,
    window: SafeAreaBounds,
    target: SafeAreaBounds,
): SafeAreaInsets {
    if (
        window.right <= window.left ||
        window.bottom <= window.top ||
        target.right <= target.left ||
        target.bottom <= target.top
    ) {
        return raw
    }
    val safeLeft = window.left + raw.left
    val safeTop = window.top + raw.top
    val safeRight = window.right - raw.right
    val safeBottom = window.bottom - raw.bottom

    return SafeAreaInsets(
        left = (safeLeft - target.left).coerceIn(0, raw.left),
        top = (safeTop - target.top).coerceIn(0, raw.top),
        right = (target.right - safeRight).coerceIn(0, raw.right),
        bottom = (target.bottom - safeBottom).coerceIn(0, raw.bottom),
    )
}

internal data class ModalChildPlacement(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val gravity: Int,
)

/** Runs [block] inside a systrace/Perfetto section named [name]. */
internal inline fun <T> pamTraceSection(name: String, block: () -> T): T {
    android.os.Trace.beginSection(name)
    try {
        return block()
    } finally {
        android.os.Trace.endSection()
    }
}

/** View kinds prebuilt for a cold start's first frame, with per-surface targets. */
internal enum class PrewarmPool(val kind: NodeKind, val target: Int) {
    // Costliest first instances first: the batch can arrive at any slice.
    INPUT(NodeKind.INPUT, 2),
    LIST(NodeKind.VIRTUAL_LIST, 1),
    IMAGE(NodeKind.IMAGE, 24),
    TEXT(NodeKind.TEXT, 48),
    CONTAINER(NodeKind.VIEW, 64),
    PRESSABLE(NodeKind.PRESSABLE, 16),
    SPACER(NodeKind.SPACER, 4),
    ;

    companion object {
        fun of(kind: NodeKind): PrewarmPool? = when (kind) {
            NodeKind.SCREEN,
            NodeKind.COLUMN,
            NodeKind.ROW,
            NodeKind.VIEW,
            NodeKind.INPUT_ACCESSORY_VIEW,
            -> CONTAINER
            NodeKind.TEXT -> TEXT
            NodeKind.PRESSABLE -> PRESSABLE
            NodeKind.IMAGE -> IMAGE
            NodeKind.SPACER -> SPACER
            NodeKind.LIST, NodeKind.SECTION_LIST, NodeKind.VIRTUAL_LIST -> LIST
            NodeKind.INPUT -> INPUT
            else -> null
        }
    }
}

/**
 * Layout of a child hosted by a full-screen Modal window, from its engine
 * frame relative to the modal ([modalWidth] x [modalHeight] px). A child
 * spanning the modal fills the window; a child ending at the modal's bottom
 * stays bottom-anchored (the window can be shorter than the engine viewport
 * when it fits the system bars); any other child keeps its frame.
 */
internal fun windowSizedModalChildPlacement(
    left: Int,
    top: Int,
    width: Int,
    height: Int,
    modalWidth: Int,
    modalHeight: Int,
): ModalChildPlacement {
    val match = ViewGroup.LayoutParams.MATCH_PARENT
    val unknown = modalWidth <= 0 || modalHeight <= 0
    val spansWidth = left <= 1 && width >= modalWidth - 1
    val spansHeight = top <= 1 && height >= modalHeight - 1
    if (unknown || spansWidth && spansHeight) {
        return ModalChildPlacement(0, 0, match, match, PAM_PHYSICAL_FRAME_GRAVITY)
    }
    val childWidth = if (spansWidth) match else width
    val childLeft = if (spansWidth) 0 else left
    return if (top > 1 && top + height >= modalHeight - 1) {
        ModalChildPlacement(childLeft, 0, childWidth, height, PAM_PHYSICAL_BOTTOM_FRAME_GRAVITY)
    } else {
        ModalChildPlacement(childLeft, top, childWidth, height, PAM_PHYSICAL_FRAME_GRAVITY)
    }
}

internal fun snappedPixelSpan(
    start: Float,
    extent: Float,
    parentStart: Float,
    density: Float,
    preserveContentExtent: Boolean = false,
): SnappedPixelSpan {
    val safeDensity = density.coerceAtLeast(0.01f)
    val absoluteStart = (start * safeDensity).roundToInt()
    val absoluteEnd = ((start + extent.coerceAtLeast(0f)) * safeDensity).roundToInt()
    val absoluteParentStart = (parentStart * safeDensity).roundToInt()
    var snappedExtent = (absoluteEnd - absoluteStart).coerceAtLeast(0)
    if (preserveContentExtent) {
        // Text widths are measured as whole (ceiled) pixels. Rounding both
        // edges independently can lose one of them at a .5 boundary, and a
        // TextView one pixel narrower than its measured line wraps the last
        // glyph onto a clipped second line ("QA" drawn as "Q").
        val content = kotlin.math.ceil(extent.coerceAtLeast(0f) * safeDensity - 0.01f).toInt()
        snappedExtent = max(snappedExtent, content)
    }
    return SnappedPixelSpan(
        offset = absoluteStart - absoluteParentStart,
        extent = snappedExtent,
    )
}

internal fun resolvedImageScaleType(imageFit: Int): ImageView.ScaleType =
    when (imageFit) {
        2 -> ImageView.ScaleType.FIT_CENTER
        3 -> ImageView.ScaleType.FIT_XY
        4, 5 -> ImageView.ScaleType.CENTER
        else -> ImageView.ScaleType.CENTER_CROP
    }

internal fun resolvedFontScale(
    allowScaling: Boolean,
    deviceScale: Float,
    maximumMultiplier: Float,
): Float = when {
    !allowScaling -> 1f
    maximumMultiplier > 0f -> min(
        deviceScale.coerceAtLeast(0.01f),
        maximumMultiplier.coerceAtLeast(1f),
    )
    else -> deviceScale.coerceAtLeast(0.01f)
}

internal fun applySemanticTextColor(view: TextView, color: Int) {
    view.setTextColor(color)
}

private enum class Axis {
    HORIZONTAL,
    VERTICAL,
}

class PamRenderer(
    private val context: Context,
    private val host: FrameLayout,
    private val dispatchEvent: (Long, Int, ByteArray) -> Unit,
) : AutoCloseable {
    var onNativeChildVisibility: ((Long, Long, Boolean) -> Unit)? = null

    /**
     * IME inset (dp from the root window bottom, 0 when hidden) over the
     * Modal/BottomSheet window of a node; the runtime lays that modal's
     * resize/padding KeyboardAvoidingViews out above it (engine surface
     * keyboard inset).
     */
    var onSurfaceKeyboardInset: ((Long, Float) -> Unit)? = null

    /**
     * True once the runtime feeds window insets to the engine: SafeAreaView
     * padding is then part of engine layout and must not be applied again.
     */
    @Volatile
    var engineManagedSafeArea: Boolean = false

    /**
     * Drops any native SafeAreaView padding applied before the engine owned
     * the insets, so a view is never inset twice (or by a stale value).
     */
    fun onEngineSafeAreaChanged() {
        if (!engineManagedSafeArea) return
        main.post {
            for (index in 0 until nodes.size()) {
                val state = nodes.valueAt(index) ?: continue
                if (state.kind != NodeKind.SAFE_AREA_VIEW) continue
                val view = views[state.id] ?: continue
                if (state.safeAreaLeftInset == 0 && state.safeAreaTopInset == 0 &&
                    state.safeAreaRightInset == 0 && state.safeAreaBottomInset == 0 &&
                    view.paddingLeft == 0 && view.paddingTop == 0 &&
                    view.paddingRight == 0 && view.paddingBottom == 0
                ) {
                    continue
                }
                state.safeAreaLeftInset = 0
                state.safeAreaTopInset = 0
                state.safeAreaRightInset = 0
                state.safeAreaBottomInset = 0
                applySafeAreaLayout(view, state)
            }
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val views = LongSparseArray<View>()
    private val scrollContainers = LongSparseArray<PamScrollContainer>()
    private val scrollKeyboardInsets = HashMap<PamScrollContainer, PamScrollKeyboardInset>()
    private val virtualListIds = LinkedHashSet<Long>()

    /**
     * Cells of inactive keyed sections (`listSection`) whose views stay
     * materialized off screen: their holder was recycled when their section
     * was switched away, and binding them again reattaches the same views
     * (images, text layouts, players) instead of rebuilding them.
     */
    private val parkedCells = HashSet<Long>()
    private val localModalIds = LinkedHashSet<Long>()
    private val pressableIds = LinkedHashSet<Long>()
    private val inputIds = LinkedHashSet<Long>()
    private val statusBarIds = LinkedHashSet<Long>()
    private var appliedStatusBar: StatusBarConfig? = null
    private var appliedHostBackground: Int? = null
    private val nodes = LongSparseArray<NodeState>()

    /** True while [id] is part of the committed tree (events for removed nodes are stale). */
    fun hasNode(id: Long): Boolean = nodes.indexOfKey(id) >= 0
    private val frames = LongSparseArray<Frame>()
    private val children = LongSparseArray<MutableList<Long>>()
    private val imageLoader = NativeImageLoader(context)
    private val mediaCache = NativeMediaFileCache(context)
    private val nativeViews = NativeViewRegistry(context)
    private val typefaces = NativeTypefaceLoader.shared(context)
    private var rootId = 0L
    private var nextMountOrder = 1L
    private var statusBarDefaults: StatusBarConfig? = null
    private var statusBarColorAnimator: ValueAnimator? = null
    private var lastFocusedInput: EditText? = null
    private val deferredViewportLayouts = HashMap<Long, Pair<View, View.OnLayoutChangeListener>>()

    /**
     * Views of scrolled-out list cells, reused for cells with the same node
     * kind and the same set of authored properties. With an identical key set
     * every property is re-applied on reuse, so no stale value can survive.
     */
    private val cellViewPool = HashMap<CellViewShape, ArrayDeque<View>>()
    private val cellViewShapes = java.util.WeakHashMap<View, CellViewShape>()
    private var recyclingCell = false

    /**
     * The pool key of a cell view: its kind and the set of properties it
     * carries, as a bit set (no sorting or string building per bind).
     */
    private fun cellViewShape(state: NodeState): CellViewShape? {
        if (state.kind !in POOLED_CELL_KINDS) return null
        val words = LongArray(PROP_KEY_WORDS)
        for (key in state.properties.keys) {
            val ordinal = key.ordinal
            words[ordinal ushr 6] = words[ordinal ushr 6] or (1L shl (ordinal and 63))
        }
        return CellViewShape(state.kind.value, words)
    }

    private fun takePooledCellView(state: NodeState): View? {
        val shape = cellViewShape(state) ?: return null
        val pool = cellViewPool[shape] ?: return null
        val view = pool.removeLastOrNull() ?: return null
        cellViewShapes.remove(view)
        return view
    }

    private fun poolCellView(id: Long, state: NodeState, view: View) {
        if (!recyclingCell || deferredViewportLayouts.containsKey(id)) return
        if (view.javaClass !in POOLED_CELL_VIEW_CLASSES) return
        val shape = cellViewShape(state) ?: return
        val pool = cellViewPool.getOrPut(shape) { ArrayDeque() }
        if (pool.size >= MAX_POOLED_CELL_VIEWS_PER_SHAPE) return
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.rotation = 0f
        view.visibility = View.VISIBLE
        view.isPressed = false
        view.jumpDrawablesToCurrentState()
        when (view) {
            // Never show the previous cell's pixels while the new source loads.
            is PamImageView -> {
                view.setImageDrawable(null)
                view.onImageSizeChanged = null
                view.retainPixels = false
            }
            // Modal trigger actions are re-installed by the commit pass.
            is PamPressable -> view.setLocalOnPress(null)
        }
        cellViewShapes[view] = shape
        pool.addLast(view)
    }

    private fun recycleCell(id: Long) {
        recyclingCell = true
        try {
            dematerializeSubtree(id)
        } finally {
            recyclingCell = false
        }
    }

    private fun putView(id: Long, view: View) {
        views.put(id, view)
        if (view is PamScrollContainer) scrollContainers.put(id, view)
        else scrollContainers.remove(id)
        if (view is PamPressable) pressableIds.add(id) else pressableIds.remove(id)
        if (view is EditText) inputIds.add(id) else inputIds.remove(id)
    }

    private fun removeView(id: Long) {
        (views[id] as? PamScrollContainer)?.let { scrollKeyboardInsets.remove(it)?.close() }
        scrollContainers.remove(id)
        pressableIds.remove(id)
        inputIds.remove(id)
        views.remove(id)
    }

    fun onHostPause() {
        for (index in 0 until views.size()) {
            when (val view = views.valueAt(index)) {
                is PamMediaView -> view.onHostPause()
                is PamWebView -> view.onPause()
            }
        }
    }

    fun onHostResume() {
        for (index in 0 until views.size()) {
            when (val view = views.valueAt(index)) {
                is PamMediaView -> view.onHostResume()
                is PamWebView -> view.onResume()
            }
        }
    }

    fun isLayoutInProgress(): Boolean {
        if (host.isInLayout) return true
        // Batches are flushed from a Choreographer frame callback, before the
        // traversal; only self-laying-out containers can still be mid-layout.
        for (id in virtualListIds) {
            if (views[id]?.isInLayout == true) return true
        }
        for (index in 0 until scrollContainers.size()) {
            if (scrollContainers.valueAt(index).isInLayout) return true
        }
        return false
    }

    /** Ancestors already invalidated by [applyLayout] in the running commit. */
    private var layoutInvalidatedAncestors: HashSet<View>? = null

    fun commit(batches: List<List<Mutation>>) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Native mutations must be mounted on the Android UI thread"
        }
        // The frame the pool was built for has arrived: no more prewarming.
        prewarmActive = false
        val profileCommit = BuildConfig.DEBUG && Log.isLoggable(COMMIT_PERF_TAG, Log.DEBUG)
        val commitStarted = if (profileCommit) System.nanoTime() else 0L
        val retainedScrollOffsets = buildMap {
            for (position in 0 until scrollContainers.size()) {
                put(
                    scrollContainers.keyAt(position),
                    scrollContainers.valueAt(position).snapshotOffsetPixels(),
                )
            }
        }
        val scrollSnapshotNanos = if (profileCommit) System.nanoTime() - commitStarted else 0L
        val explicitlyUpdatedScrollOffsets = buildSet {
            batches.forEach { batch ->
                batch.forEach { mutation ->
                    if (
                        mutation is Mutation.Update
                        && (
                            mutation.key == PropKey.SCROLL_CONTENT_OFFSET_X
                                || mutation.key == PropKey.SCROLL_CONTENT_OFFSET_Y
                                || mutation.key == PropKey.SCROLL_REQUEST
                        )
                    ) {
                        add(mutation.id)
                    }
                }
            }
        }
        val dirtyLayouts = LinkedHashSet<Long>()
        val createdNodes = LinkedHashSet<Long>()
        var needsModalSync = false
        var needsVirtualListSync = false
        // Lists whose rows, row extents or own configuration changed; every
        // other list keeps its adapter untouched (no per-commit O(rows) pass).
        val dirtyLists = HashSet<Long>()
        fun markListOf(id: Long) {
            val parent = nodes[id]?.parent ?: return
            if (nodes[parent]?.kind == NodeKind.VIRTUAL_LIST) dirtyLists += parent
        }
        pamTraceSection("PamCommit.mutations") {
            batches.forEach { batch ->
                batch.forEach { mutation ->
                    when (mutation) {
                        is Mutation.Create -> {
                            create(mutation.node)
                            createdNodes += mutation.node.id
                            needsModalSync = true
                            needsVirtualListSync = true
                            if (mutation.node.kind == NodeKind.VIRTUAL_LIST) dirtyLists += mutation.node.id
                            markListOf(mutation.node.id)
                        }
                        is Mutation.Remove -> {
                            markListOf(mutation.id)
                            remove(mutation.id)
                            needsModalSync = true
                            needsVirtualListSync = true
                        }
                        is Mutation.Update -> {
                            update(mutation.id, mutation.key, mutation.value)
                            if (
                                mutation.key == PropKey.VALUE ||
                                mutation.key == PropKey.ACCESSIBILITY_LABEL
                            ) {
                                needsModalSync = true
                            }
                            if (
                                mutation.key == PropKey.LIST_HORIZONTAL ||
                                mutation.key == PropKey.LIST_ROW_HEIGHT ||
                                mutation.key == PropKey.LIST_FULL_SPAN ||
                                mutation.key == PropKey.STICKY_HEADER ||
                                mutation.key == PropKey.LIST_SECTION ||
                                mutation.key == PropKey.LIST_ACTIVE_SECTION
                            ) {
                                needsVirtualListSync = true
                                if (nodes[mutation.id]?.kind == NodeKind.VIRTUAL_LIST) dirtyLists += mutation.id
                                markListOf(mutation.id)
                            }
                        }
                        is Mutation.Move -> {
                            markListOf(mutation.id)
                            move(mutation.id, mutation.parent, mutation.index)
                            markListOf(mutation.id)
                            needsVirtualListSync = true
                        }
                        is Mutation.Layout -> {
                            val previous = frames[mutation.id]
                            frames.put(mutation.id, mutation.frame)
                            dirtyLayouts += mutation.id
                            if (nodes[mutation.id]?.kind == NodeKind.VIRTUAL_LIST) {
                                dirtyLists += mutation.id
                                needsVirtualListSync = true
                            } else if (
                                previous == null ||
                                previous.width != mutation.frame.width ||
                                previous.height != mutation.frame.height
                            ) {
                                // Only a row's extent feeds its list; a pure
                                // offset (rows shifted by a prepend) does not.
                                val before = dirtyLists.size
                                markListOf(mutation.id)
                                if (dirtyLists.size != before) needsVirtualListSync = true
                            }
                        }
                        is Mutation.SetRoot -> {
                            rootId = mutation.id
                            needsModalSync = true
                            needsVirtualListSync = true
                            dirtyLists += virtualListIds
                        }
                    }
                }
            }
        }
        val mutationsNanos = if (profileCommit) {
            System.nanoTime() - commitStarted - scrollSnapshotNanos
        } else 0L
        if (needsModalSync) syncLocalModalTriggers()
        val modalSyncNanos = if (profileCommit) {
            System.nanoTime() - commitStarted - scrollSnapshotNanos - mutationsNanos
        } else 0L
        syncHostBackground()
        // Android 15 renders the status-bar surface through the decor view.
        // Host background synchronization also writes that surface, so the
        // authored StatusBar color must win at the end of every commit.
        applyMergedStatusBar()
        val virtualListStarted = if (profileCommit) System.nanoTime() else 0L
        pamTraceSection("PamCommit.lists") {
            if (needsVirtualListSync) syncVirtualLists(dirtyLists) else remountEmptyListRows()
        }
        val virtualListSyncNanos = if (profileCommit) System.nanoTime() - virtualListStarted else 0L
        // A stable row ID/extent does not trigger a RecyclerView rebind when
        // conditional descendants are inserted. Materialize only affected,
        // already-mounted cells after all nodes and frames have arrived.
        createdNodes.mapNotNull(::virtualCellRoot).toSet().forEach { cellRoot ->
            val holder = virtualCellHolder(cellRoot)
            if (holder != null) materializeCell(cellRoot, holder)
        }
        pamTraceSection("PamCommit.layout") {
            layoutInvalidatedAncestors = HashSet()
            try {
                dirtyLayouts.forEach(::applyLayout)
                dirtyLayouts.forEach(::queueLayoutEvent)
            } finally {
                layoutInvalidatedAncestors = null
            }
        }
        retainedScrollOffsets.forEach { (id, offset) ->
            if (id !in explicitlyUpdatedScrollOffsets) {
                (views[id] as? PamScrollContainer)?.restoreOffsetPixels(
                    offset.first,
                    offset.second,
                )
            }
        }
        ensureFocusedInputVisibleAfterCommit()
        scheduleIdlePrewarm()
        if (profileCommit) {
            Log.d(
                COMMIT_PERF_TAG,
                "nodes=${nodes.size()} views=${views.size()} scrolls=${scrollContainers.size()} " +
                    "lists=${virtualListIds.size} modals=${localModalIds.size} " +
                    "mutations=${batches.sumOf { it.size }} " +
                    "scrollNs=$scrollSnapshotNanos mutationNs=$mutationsNanos " +
                    "modalNs=$modalSyncNanos virtualListNs=$virtualListSyncNanos " +
                    "totalNs=${System.nanoTime() - commitStarted}",
            )
        }
    }

    private fun ensureFocusedInputVisibleAfterCommit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val input = (host.findFocus() as? EditText)
            ?: lastFocusedInput?.takeIf(EditText::hasFocus)
            ?: return
        if (currentPlatformImeInset() <= 0) return
        var ancestor = input.parent as? View
        while (ancestor != null) {
            if (ancestor is PamScrollContainer) {
                // Scroll-offset restoration is posted during commit. Queueing
                // this reconciliation afterwards makes keyboard visibility win
                // over a stale retained offset from the reactive render.
                if (scrollKeyboardInsets.containsKey(ancestor)) {
                    ancestor.ensureKeyboardTargetVisible(input)
                } else {
                    ancestor.ensureViewportTargetVisible(input)
                }
                return
            }
            ancestor = ancestor.parent as? View
        }
    }

    private fun syncHostBackground() {
        val color = resolveHostBackground(rootId, 0)
        if (appliedHostBackground == color) return
        appliedHostBackground = color
        // The host background also paints the Android 15 status-bar surface:
        // the authored StatusBar must be applied again afterwards.
        appliedStatusBar = null
        host.setBackgroundColor(color)
    }

    /**
     * Forgets the last applied system-bar state so the next commit re-applies
     * the authored StatusBar (the Activity repainted its default bars).
     */
    fun invalidateSystemBars() {
        appliedStatusBar = null
        applyMergedStatusBar()
    }

    private val localModalInputTargets =
        java.util.WeakHashMap<EditText, PamModalHost>()

    private fun openLocalModalInput(input: EditText) {
        val target = localModalInputTargets[input] ?: return
        localModalInputTargets.values.toSet().forEach { modal ->
            if (modal !== target) modal.setVisible(false)
        }
        target.setVisible(true)
    }

    private fun bindLocalModalInput(input: EditText, target: PamModalHost) {
        if (!localModalInputTargets.containsKey(input)) {
            val previous = input.onFocusChangeListener
            input.onFocusChangeListener = View.OnFocusChangeListener { view, hasFocus ->
                previous?.onFocusChange(view, hasFocus)
                if (hasFocus) openLocalModalInput(input)
            }
        }
        localModalInputTargets[input] = target
        target.setFocusKeyboard(true)
        input.setOnClickListener { openLocalModalInput(input) }
    }

    private fun syncLocalModalTriggers() {
        val modals = HashMap<String, PamModalHost>()
        val orderedModals = ArrayList<Pair<Int, PamModalHost>>()
        for (id in localModalIds) {
            val state = nodes[id] ?: continue
            val marker = state.properties[PropKey.VALUE]?.textOrNull() ?: continue
            if (marker.startsWith(LOCAL_MODAL_PREFIX)) {
                (views[id] as? PamModalHost)?.let { modal ->
                    modals[marker.removePrefix(LOCAL_MODAL_PREFIX)] = modal
                    orderedModals += nodes.indexOfKey(id) to modal
                }
            }
        }
        for (id in pressableIds) {
            val state = nodes[id] ?: continue
            val trigger = views[id] as? PamPressable ?: continue
            val marker = state.properties[PropKey.VALUE]?.textOrNull()
            val accessibilityMarker =
                state.properties[PropKey.ACCESSIBILITY_LABEL]?.textOrNull()
            if (
                marker == null &&
                accessibilityMarker != MODAL_CLOSE_ACCESSIBILITY_LABEL &&
                !trigger.hasLocalOnPress
            ) {
                continue
            }
            val localPress = when {
                marker == MODAL_CLOSE_MARKER ||
                    accessibilityMarker == MODAL_CLOSE_ACCESSIBILITY_LABEL -> {
                    { closeLocalModalAncestor(state.id) }
                }
                marker?.startsWith(LOCAL_MODAL_TRIGGER_PREFIX) == true -> {
                    modals[marker.removePrefix(LOCAL_MODAL_TRIGGER_PREFIX)]?.let { target ->
                        {
                            modals.values.forEach { modal ->
                                if (modal !== target) modal.setVisible(false)
                            }
                            target.setVisible(true)
                        }
                    }
                }
                else -> null
            }
            trigger.setLocalOnPress(localPress)
        }
        orderedModals.sortBy { it.first }
        val orderedInputs = ArrayList<Pair<Int, EditText>>(inputIds.size)
        for (id in inputIds) {
            (views[id] as? EditText)?.let { orderedInputs += nodes.indexOfKey(id) to it }
        }
        orderedInputs.sortBy { it.first }
        orderedInputs.forEach { (inputPosition, input) ->
            val target = orderedModals.minByOrNull { (modalPosition, _) ->
                kotlin.math.abs(modalPosition - inputPosition)
            }?.second
            if (target != null) bindLocalModalInput(input, target)
        }
    }

    private fun updateLocalModalSelection(startId: Long) {
        val selected = views[startId]?.contentDescription?.toString()?.takeIf(String::isNotBlank)
            ?: return
        var currentId = nodes[startId]?.parent ?: 0L
        var modalKey: String? = null
        var depth = 0
        while (currentId != 0L && depth++ < MAX_VIRTUAL_DEPTH) {
            val state = nodes[currentId] ?: break
            if (state.kind == NodeKind.MODAL) {
                val marker = state.properties[PropKey.VALUE]?.textOrNull()
                if (marker?.startsWith(LOCAL_MODAL_PREFIX) == true) {
                    modalKey = marker.removePrefix(LOCAL_MODAL_PREFIX)
                }
                break
            }
            currentId = state.parent
        }
        val key = modalKey ?: return
        for (position in 0 until nodes.size()) {
            val state = nodes.valueAt(position)
            val marker = state.properties[PropKey.VALUE]?.textOrNull()
            if (marker != LOCAL_MODAL_TRIGGER_PREFIX + key) continue
            val trigger = views[state.id] as? ViewGroup ?: continue
            val value = descendantTextViews(trigger)
                .filter { it !is EditText && it.text.toString() !in setOf("⌄", "›") }
                .maxByOrNull { it.width }
            value?.text = selected
            trigger.contentDescription = selected
        }
    }

    private fun descendantTextViews(root: ViewGroup): List<TextView> {
        val labels = ArrayList<TextView>()
        fun collect(group: ViewGroup) {
            for (index in 0 until group.childCount) {
                when (val child = group.getChildAt(index)) {
                    is TextView -> labels += child
                    is ViewGroup -> collect(child)
                }
            }
        }
        collect(root)
        return labels
    }

    private fun closeLocalModalAncestor(startId: Long) {
        var currentId = nodes[startId]?.parent ?: 0L
        var depth = 0
        while (currentId != 0L && depth++ < MAX_VIRTUAL_DEPTH) {
            val state = nodes[currentId] ?: return
            if (state.kind == NodeKind.MODAL) {
                views[startId]?.let { source ->
                    val keyboard = source.context.getSystemService(
                        Context.INPUT_METHOD_SERVICE,
                    ) as? android.view.inputmethod.InputMethodManager
                    keyboard?.hideSoftInputFromWindow(source.windowToken, 0)
                }
                (views[currentId] as? PamModalHost)?.setVisible(false)
                main.postDelayed({
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        host.windowInsetsController?.hide(
                            android.view.WindowInsets.Type.ime(),
                        )
                    } else {
                        val keyboard = context.getSystemService(
                            Context.INPUT_METHOD_SERVICE,
                        ) as? android.view.inputmethod.InputMethodManager
                        keyboard?.hideSoftInputFromWindow(host.windowToken, 0)
                    }
                }, 180L)
                return
            }
            currentId = state.parent
        }
    }

    private fun resolveHostBackground(nodeId: Long, depth: Int): Int {
        if (depth > 8) return Color.TRANSPARENT
        val node = nodes[nodeId] ?: return Color.TRANSPARENT
        node.properties[PropKey.BACKGROUND_COLOR]?.let { value ->
            return value.integer().toInt()
        }
        val descendants = children[nodeId] ?: return Color.TRANSPARENT
        for (childId in descendants) {
            val color = resolveHostBackground(childId, depth + 1)
            if (color != Color.TRANSPARENT) return color
        }

        return Color.TRANSPARENT
    }

    fun trimMemory(critical: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper())
        imageLoader.trimMemory(critical)
        if (critical) {
            releaseParkedCells { true }
            val recyclerLists = snapshotValues<PamRecyclerList>(views.size(), views::valueAt)
            for (recyclerList in recyclerLists) {
                recyclerList.trimMemory(true)
            }
        }
    }

    fun hasPresentedModal(): Boolean {
        for (position in views.size() - 1 downTo 0) {
            if ((views.valueAt(position) as? PamModalHost)?.isPresented() == true) {
                return true
            }
        }
        return false
    }

    fun consumePresentedModalBack(): Boolean {
        for (position in views.size() - 1 downTo 0) {
            val modal = views.valueAt(position) as? PamModalHost ?: continue
            if (modal.consumeActivityBack()) return true
        }
        return false
    }

    /**
     * Unmounts every node but keeps the renderer usable (loaders, caches and
     * host stay alive). The runtime calls this before replaying the engine's
     * retained tree after the host tree diverged from it, for example when a
     * batch was rejected or only partly mounted. Deepest nodes go first so no
     * parent is released before its children.
     */
    fun resetTree() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Native mutations must be mounted on the Android UI thread"
        }
        if (nodes.size() > 0) {
            val count = nodes.size()
            val depths = HashMap<Long, Int>(count)
            for (position in 0 until count) {
                var depth = 0
                var parent = nodes.valueAt(position).parent
                while (parent != 0L && depth <= count) {
                    depth++
                    parent = nodes[parent]?.parent ?: 0L
                }
                depths[nodes.keyAt(position)] = depth
            }
            depths.entries
                .sortedByDescending { it.value }
                .forEach { (id, _) -> remove(id) }
        }
        views.clear()
        scrollContainers.clear()
        virtualListIds.clear()
        localModalIds.clear()
        statusBarIds.clear()
        pressableIds.clear()
        inputIds.clear()
        nodes.clear()
        frames.clear()
        children.clear()
        rootId = 0L
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        onNativeChildVisibility = null
        onSurfaceKeyboardInset = null
        prewarmActive = false
        main.removeCallbacks(prewarmSlice)
        destroyed = true
        if (idlePrewarmScheduled) {
            Looper.myQueue().removeIdleHandler(idlePrewarm)
            idlePrewarmScheduled = false
        }
        prewarmedViews.clear()
        for (position in 0 until views.size()) {
            (views.valueAt(position) as? NativeChildVisibilityHost)?.onChildVisibilityChanged = null
            (views.valueAt(position) as? PamModalHost)?.close()
        }
        for (position in 0 until nodes.size()) {
            val state = nodes.valueAt(position)
            state.propertyAnimator?.cancel()
            state.keyframeAnimator?.cancel()
            state.workletAnimator?.cancel()
            state.cancelMotion()
            state.loadingDrawable?.stop()
            state.outsidePointerObserver?.let { observer ->
                (host as? PamRootHost)?.removePointerObserver(observer)
            }
            state.directiveLayoutListener?.let { listener ->
                views[state.id]?.removeOnLayoutChangeListener(listener)
            }
            state.intersectionObserver?.close(notify = false)
            state.legacyKeyboardSubscription?.close()
        }
        statusBarDefaults?.let(::applyStatusBarConfig)
        statusBarColorAnimator?.cancel()
        deferredViewportLayouts.values.forEach { (parent, listener) ->
            parent.removeOnLayoutChangeListener(listener)
        }
        deferredViewportLayouts.clear()
        scrollKeyboardInsets.values.forEach(PamScrollKeyboardInset::close)
        scrollKeyboardInsets.clear()
        imageLoader.close()
        mediaCache.close()
        nativeViews.close()
        host.removeAllViews()
        views.clear()
        scrollContainers.clear()
        virtualListIds.clear()
        localModalIds.clear()
        statusBarIds.clear()
        pressableIds.clear()
        inputIds.clear()
        nodes.clear()
        frames.clear()
        children.clear()
    }

    private fun create(spec: NodeSpec) {
        check(nodes[spec.id] == null) { "Duplicate native node ${spec.id}" }
        val state = NodeState(
            id = spec.id,
            parent = spec.parent,
            index = spec.index,
            kind = spec.kind,
            properties = spec.properties.toMutableMap(),
            mountOrder = nextMountOrder++,
            virtual = isLayoutOnly(spec),
        )
        nodes.put(spec.id, state)
        if (state.kind == NodeKind.VIRTUAL_LIST) virtualListIds.add(spec.id)
        if (state.kind == NodeKind.MODAL) localModalIds.add(spec.id)
        if (state.kind == NodeKind.STATUS_BAR) statusBarIds.add(spec.id)
        addChild(state.parent, state.id, fresh = true)
        if (!state.virtual && virtualListAncestor(state.parent) == null) {
            val view = createView(spec.kind, state)
            if (view is TextView) {
                state.defaultHighlightColor = view.highlightColor
            }
            putView(spec.id, view)
            attachHosted(view, state)
            applyInitialProperties(view, state)
            (view as? TextView)?.let { applyTextAlignment(it, state) }
            installEvents(view, state)
        }
    }

    /**
     * Fresh, never-attached views built while the UI thread waits for PHP's
     * first frame (see [prewarmViews]). A cold process otherwise pays class
     * loading, style resolution and construction for every view of that frame
     * on the critical path. Only kinds whose construction does not depend on
     * node state are pooled; a pooled view is indistinguishable from a new one.
     */
    private val prewarmedViews = java.util.EnumMap<PrewarmPool, ArrayDeque<View>>(PrewarmPool::class.java)
    private var prewarmActive = false

    /**
     * Builds the views a typical first frame needs, in short UI-thread slices
     * that yield to input, vsync and the first PHP batch; stops as soon as a
     * commit starts or the targets are reached.
     */
    fun prewarmViews() {
        if (prewarmActive || nodes.size() > 0) return
        prewarmActive = true
        main.post(prewarmSlice)
    }

    private var prewarmedClasses = false

    private val prewarmSlice: Runnable = object : Runnable {
        override fun run() {
            if (!prewarmActive) return
            if (!prewarmedClasses) {
                prewarmedClasses = true
                // Not pooled (state-dependent or self-animating), only warmed:
                // the first instance of each pays class loading and, for the
                // spinner's ProgressBar base, the theme's progress drawable.
                PamVuetifySpinner(context)
                PamScrollContainer(
                    context,
                    initialHorizontal = false,
                    initialPersistentScrollbar = false,
                    initialIndicatorStyle = ScrollIndicatorStyle.AUTO,
                )
                main.post(this)
                return
            }
            val deadline = System.nanoTime() + PREWARM_SLICE_NANOS
            for (pool in PrewarmPool.entries) {
                val queue = prewarmedViews.getOrPut(pool) { ArrayDeque(pool.target) }
                while (queue.size < pool.target) {
                    queue.addLast(newView(pool.kind, null))
                    if (System.nanoTime() >= deadline) {
                        main.post(this)
                        return
                    }
                }
            }
            prewarmActive = false
        }
    }

    private fun createView(kind: NodeKind, state: NodeState? = null): View {
        val pool = PrewarmPool.of(kind) ?: return newView(kind, state)
        val pooled = prewarmedViews[pool]?.removeFirstOrNull() ?: return newView(kind, state)
        drainedPrewarmPools = true
        return pooled
    }

    private var destroyed = false

    /** A commit took views from the prewarm pools since they were last full. */
    private var drainedPrewarmPools = false
    private var idlePrewarmScheduled = false

    /**
     * Refills the prewarm pools while the UI thread is idle after a commit
     * that drained them, in [PREWARM_SLICE_NANOS] slices, so the next large
     * subtree (a profile grid, a tab's rows, a reel page) takes ready views
     * instead of constructing (and class-initializing) each one inside its
     * mount. Idle handlers only run when no message or frame is due.
     */
    private val idlePrewarm = android.os.MessageQueue.IdleHandler {
        if (destroyed) {
            idlePrewarmScheduled = false
            return@IdleHandler false
        }
        val deadline = System.nanoTime() + PREWARM_SLICE_NANOS
        for (pool in PrewarmPool.entries) {
            val queue = prewarmedViews.getOrPut(pool) { ArrayDeque(pool.target) }
            while (queue.size < pool.target) {
                queue.addLast(newView(pool.kind, null))
                if (System.nanoTime() >= deadline) return@IdleHandler true
            }
        }
        idlePrewarmScheduled = false
        false
    }

    private fun scheduleIdlePrewarm() {
        if (!drainedPrewarmPools || idlePrewarmScheduled || prewarmActive || destroyed) return
        drainedPrewarmPools = false
        idlePrewarmScheduled = true
        Looper.myQueue().addIdleHandler(idlePrewarm)
    }

    private fun newView(kind: NodeKind, state: NodeState?): View =
        when (kind) {
            NodeKind.SCREEN,
            NodeKind.COLUMN,
            NodeKind.ROW,
            NodeKind.VIEW,
            NodeKind.INPUT_ACCESSORY_VIEW,
            -> PamContainer(context)
            NodeKind.PRESSABLE -> PamPressable(context)
            NodeKind.TEXT -> TextView(context).apply {
                // React Native Android defaults: includeFontPadding=true,
                // high-quality breaking, no hyphenation, fallback spacing.
                includeFontPadding = true
                useHighQualityLineBreaks()
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isFallbackLineSpacing = true
                gravity = Gravity.CENTER_VERTICAL
            }
            NodeKind.BUTTON -> Button(context).apply {
                isAllCaps = false
                minHeight = 0
                minWidth = 0
            }
            NodeKind.INPUT -> PamEditText(context).apply {
                isSingleLine = true
                includeFontPadding = false
                minHeight = 0
                background = null
                setPadding(0, 0, 0, 0)
            }
            NodeKind.IMAGE -> PamImageView(context)
            NodeKind.IMAGE_BACKGROUND -> PamImageBackground(context)
            NodeKind.SCROLL -> PamScrollContainer(
                context,
                initialHorizontal = state?.flag(PropKey.SCROLL_HORIZONTAL, false) ?: false,
                initialPersistentScrollbar = state?.flag(PropKey.SCROLL_PERSISTENT_SCROLLBAR, false) ?: false,
                initialIndicatorStyle = ScrollIndicatorStyle.fromWire(
                    state?.integer(PropKey.SCROLL_INDICATOR_STYLE, ScrollIndicatorStyle.AUTO.wireValue.toLong())
                        ?.toInt() ?: ScrollIndicatorStyle.AUTO.wireValue,
                ),
            )
            NodeKind.LIST,
            NodeKind.SECTION_LIST,
            NodeKind.VIRTUAL_LIST,
            -> PamRecyclerList(context)
            NodeKind.SPACER,
            NodeKind.STATUS_BAR,
            -> Space(context)
            NodeKind.ACTIVITY_INDICATOR -> PamActivityIndicator(context)
            NodeKind.SWITCH -> PamSwitch(context)
            NodeKind.MODAL -> PamModalHost(context) {
                (context as? PamActivity)?.suppressNextPamBack()
            }.also { modal ->
                val id = state?.id ?: return@also
                modal.onSurfaceKeyboardInset = { inset, _ -> forwardSurfaceKeyboard(id, modal, inset) }
            }
            NodeKind.KEYBOARD_AVOIDING_VIEW -> PamContainer(context).also {
                installKeyboardInsets(it, requireNotNull(state))
            }
            NodeKind.REFRESH_CONTROL -> PamRefreshContainer(context)
            NodeKind.SAFE_AREA_VIEW -> PamContainer(context).also {
                installSafeArea(it, requireNotNull(state))
            }
            NodeKind.DRAWER_LAYOUT -> PamDrawerLayout(context)
            NodeKind.NAVIGATION_HOST -> PamNavigationHost(context).also {
                it.onActiveRouteChanged = ::applyMergedStatusBar
            }
            NodeKind.WEB_VIEW -> PamWebView(context)
            NodeKind.MEDIA -> PamMediaView(context, mediaCache, imageLoader)
            NodeKind.DRAWING_CANVAS -> PamDrawingCanvas(context)
            NodeKind.TAB_HOST -> PamTabHost(context)
            NodeKind.CANVAS -> PamVectorCanvas(context)
            NodeKind.CUSTOM_VIEW -> {
                val custom = requireNotNull(state) { "Custom native view requires node state" }
                val name = custom.properties[PropKey.HOST_NAME]?.text(PropKey.HOST_NAME)
                    ?: error("Custom native view is missing its generated name")
                nativeViews.create(name) { kind, payload ->
                    if (kind == EVENT_NATIVE) {
                        if (isLocalModalSelectionEvent(
                                custom.properties[PropKey.HOST_PROPERTIES],
                            )
                        ) {
                            updateLocalModalSelection(custom.id)
                        }
                        closeLocalModalAncestor(custom.id)
                    }
                    val eventProperty = nativeEventProperty(kind)
                    if (eventProperty != null && custom.properties[eventProperty] != null) {
                        dispatchBytes(custom.id, kind, payload)
                    }
                }.also { nativeView ->
                    if (nativeView is NativeChildVisibilityHost) {
                        nativeView.onChildVisibilityChanged = { child, visible ->
                            main.post {
                                if (views[custom.id] === nativeView) {
                                    val childId = children[custom.id]?.firstOrNull { views[it] === child }
                                    if (childId != null && nodes[childId]?.parent == custom.id) {
                                        onNativeChildVisibility?.invoke(custom.id, childId, visible)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    private fun remove(id: Long) {
        val state = nodes[id] ?: return
        val removedStatusBar = state.kind == NodeKind.STATUS_BAR
        val view = views[id]
        (view as? NativeChildVisibilityHost)?.onChildVisibilityChanged = null
        deferredViewportLayouts.remove(id)?.let { (parent, listener) ->
            parent.removeOnLayoutChangeListener(listener)
        }
        state.propertyAnimator?.cancel()
        state.keyframeAnimator?.cancel()
        state.workletAnimator?.cancel()
        state.cancelMotion()
        state.loadingDrawable?.stop()
        state.pendingChange?.let(main::removeCallbacks)
        (view as? PamModalHost)?.close()
        pamImageView(view)?.let(imageLoader::cancel)
        (view as? PamWebView)?.destroy()
        view?.let(nativeViews::release)
        view?.let(::clearHitSlop)
        state.directiveLayoutListener?.let { listener ->
            view?.removeOnLayoutChangeListener(listener)
        }
        state.intersectionObserver?.close()
        state.outsidePointerObserver?.let { observer ->
            (host as? PamRootHost)?.removePointerObserver(observer)
        }
        if (state.kind == NodeKind.KEYBOARD_AVOIDING_VIEW) {
            state.legacyKeyboardSubscription?.close()
            if (state.keyboardAvoidingScrollId != 0L) {
                views[state.keyboardAvoidingScrollId]
                    ?.let { it as? PamScrollContainer }
                    ?.setKeyboardAvoidanceInset(0)
            }
            val modalSubscription = state.modalKeyboardSubscription
            if (modalSubscription != null) {
                // A modal's KAV never owned the activity window's listeners:
                // clearing them would stop the screen's own composer KAV.
                modalSubscription.close()
                state.modalKeyboardSubscription = null
                state.keyboardModalHost = null
            } else {
                host.setOnApplyWindowInsetsListener(null)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    host.setWindowInsetsAnimationCallback(null)
                }
            }
            state.keyboardLayoutListener?.let(host::removeOnLayoutChangeListener)
            state.keyboardSelfLayoutListener?.let { view?.removeOnLayoutChangeListener(it) }
        }
        (view?.parent as? ViewGroup)?.removeView(view)
        removeChild(state.parent, id)
        children.remove(id)
        removeView(id)
        virtualListIds.remove(id)
        parkedCells.remove(id)
        localModalIds.remove(id)
        statusBarIds.remove(id)
        nodes.remove(id)
        frames.remove(id)
        if (id == rootId) rootId = 0L
        if (removedStatusBar) applyMergedStatusBar()
    }

    private fun update(id: Long, key: PropKey, value: PropValue?) {
        val state = nodes[id] ?: return
        if (value == null) {
            state.properties.remove(key)
        } else {
            state.properties[key] = value
        }
        val shouldBeVirtual = isLayoutOnly(state)
        val cellRoot = virtualCellRoot(id)
        if (cellRoot != null && state.virtual != shouldBeVirtual) {
            val holder = virtualCellHolder(cellRoot)
            when (
                virtualCellHostingRepair(
                    isCellRoot = id == cellRoot,
                    cellMounted = holder != null,
                    cellRootHosted = views[cellRoot] != null,
                )
            ) {
                // Only this node gains or loses its native view: the rest of
                // the cell (decoded images, players, scroll offsets, running
                // motions such as a double-tap heart) stays mounted.
                VirtualCellHostingRepair.IN_PLACE ->
                    if (shouldBeVirtual) demote(state) else promote(state)
                VirtualCellHostingRepair.REMOUNT_CELL -> {
                    state.virtual = shouldBeVirtual
                    dematerializeSubtree(cellRoot)
                    if (holder != null) materializeCell(cellRoot, holder)
                }
            }
            return
        }
        if (state.virtual && !shouldBeVirtual) {
            promote(state)
        } else if (!state.virtual && shouldBeVirtual) {
            demote(state)
        } else {
            val view = views[id]
            if (view != null) {
                if (value == null) {
                    resetProperty(view, state, key)
                } else {
                    applyProperty(view, state, key, value)
                }
            }
        }
        if (key.isEventProperty()) {
            views[id]?.let { installEvents(it, state) }
        }
        if (key in IMAGE_EVENT_PROPERTIES) {
            views[id]?.let { loadImage(it, state) }
        }
    }

    private fun move(id: Long, parent: Long, index: Int) {
        val state = nodes[id] ?: return
        if (state.parent == parent && nodes[parent]?.kind == NodeKind.VIRTUAL_LIST) {
            // A keyed row keeps its holder and native subtree when a page or
            // loading header changes its adapter index. RecyclerView's diff
            // positions that holder; detaching here would cancel every image
            // and empty all visible rows before the adapter can reconcile.
            removeChild(parent, id)
            state.index = index
            addChild(parent, id)
            return
        }
        val wasVirtualized = virtualListAncestor(state.parent) != null
        val view = views[id]
        if (view?.parent != null && !wasVirtualized && moveKeepsHostedPosition(state, parent, index)) {
            // Only the logical index changed (a sibling before it was removed
            // or inserted): the view keeps its place among its host's views.
            // Re-attaching it would drop its focus/IME connection and dismiss
            // the window of a presented Modal/BottomSheet (which then comes
            // back as a new window without the focused input).
            return
        }
        val sourceNavigation = view?.parent as? PamNavigationHost
        val destinationNavigation = views[effectiveParent(parent)] as? PamNavigationHost
        if (view != null && sourceNavigation != null && sourceNavigation === destinationNavigation && state.parent == parent) {
            // A route changing places in its stack (a screen mounted ahead
            // under the top one, a kept-alive screen coming back) is reordered
            // without leaving the window: touches in progress on the other
            // routes are not cancelled and its own views, images and list
            // layout stay as they are.
            removeChild(state.parent, id)
            state.index = index
            addChild(parent, id)
            sourceNavigation.reorderRoute(view, hostedInsertionIndex(state, effectiveParent(parent)))
            return
        }
        view?.let(::clearHitSlop)
        if (sourceNavigation != null && sourceNavigation === destinationNavigation) {
            sourceNavigation.detachRouteForMove(requireNotNull(view))
        } else {
            (view?.parent as? ViewGroup)?.removeView(view)
        }
        removeChild(state.parent, id)
        state.parent = parent
        state.index = index
        addChild(parent, id)
        val isVirtualized = virtualListAncestor(parent) != null
        if (wasVirtualized || isVirtualized) {
            if (view != null) dematerializeSubtree(id)
            return
        }
        if (view != null) {
            attachHosted(view, state)
            applyHitSlop(view, state)
        } else {
            reattachHostedDescendants(id)
        }
    }

    /**
     * Applies a move to the logical tree when it leaves [state]'s view at the
     * same position under the same hosting view (returns false, with nothing
     * changed, when the view must be re-attached).
     */
    private fun moveKeepsHostedPosition(state: NodeState, parent: Long, index: Int): Boolean {
        if (virtualListAncestor(parent) != null) return false
        val host = effectiveParent(state.parent)
        if (effectiveParent(parent) != host) return false
        val previousParent = state.parent
        val previousIndex = state.index
        val before = hostedInsertionIndex(state, host)
        removeChild(previousParent, state.id)
        state.parent = parent
        state.index = index
        addChild(parent, state.id)
        if (hostedInsertionIndex(state, host) == before) return true
        removeChild(parent, state.id)
        state.parent = previousParent
        state.index = previousIndex
        addChild(previousParent, state.id)
        return false
    }

    private fun addChild(parent: Long, id: Long, fresh: Boolean = false) {
        if (parent == 0L) return
        val siblings = children[parent] ?: ArrayList<Long>().also { children.put(parent, it) }
        val index = nodes[id]?.index ?: Int.MAX_VALUE
        if (fresh) {
            // Creation streams arrive in sibling order: append without the
            // O(n) contains + O(n log n) sort per insert. Out-of-order inserts
            // (prepending an older page) use a binary search, never a re-sort.
            val last = siblings.lastOrNull()
            if (last == null || (nodes[last]?.index ?: Int.MAX_VALUE) <= index) {
                siblings += id
                return
            }
            var low = 0
            var high = siblings.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if ((nodes[siblings[middle]]?.index ?: Int.MAX_VALUE) <= index) {
                    low = middle + 1
                } else {
                    high = middle
                }
            }
            siblings.add(low, id)
            return
        }
        if (!siblings.contains(id)) siblings += id
        siblings.sortBy { child -> nodes[child]?.index ?: Int.MAX_VALUE }
    }

    private fun removeChild(parent: Long, id: Long) {
        if (parent == 0L) return
        children[parent]?.remove(id)
    }

    /** Repairs rows emptied by a re-materialization; O(visible rows). */
    private fun remountEmptyListRows() {
        for (id in virtualListIds) {
            (views[id] as? PamRecyclerList)?.remountEmptyRows()
        }
    }

    private fun syncVirtualLists(dirty: Set<Long>? = null) {
        for (id in virtualListIds) {
            val state = nodes[id] ?: continue
            val list = views[id] as? PamRecyclerList ?: continue
            if (dirty != null && id !in dirty && state.virtualListItemIds.isNotEmpty()) {
                list.remountEmptyRows()
                continue
            }
            val activeSection = state.textOrNull(PropKey.LIST_ACTIVE_SECTION).orEmpty()
            val allItemIds = children[id]?.toList().orEmpty()
            val itemIds = visibleSectionItems(allItemIds, activeSection, ::listSectionOf)
            val sectionSwitch = state.appliedListSection
                ?.takeIf { previous -> previous != activeSection }
                ?.let { previous ->
                    ListSectionSwitch(previous, activeSection, sectionRailId(allItemIds, ::listSectionOf))
                }
            state.appliedListSection = activeSection
            if (state.virtualListItemIds != itemIds) {
                state.virtualListItemIds = itemIds
                state.endReachedSent = false
            }
            val horizontal = state.flag(PropKey.LIST_HORIZONTAL, false)
            val fallbackExtent = state.number(PropKey.LIST_ROW_HEIGHT, 48.0).toFloat()
            // A cell slot is its root's margin box, like a React Native cell
            // around an item with margins (the engine insets the root frame).
            val itemExtents = itemIds.associateWith { id ->
                frames[id]?.let { frame ->
                    val margins = cellRootMargins(nodes[id]?.properties)
                    if (horizontal) {
                        frame.width + margins.left + margins.right
                    } else {
                        frame.height + margins.top + margins.bottom
                    }
                }?.coerceAtLeast(1f) ?: fallbackExtent
            }
            list.setRichItems(
                ids = itemIds,
                extents = itemExtents,
                mount = { cell, holder -> materializeCell(cell, holder) },
                unmount = { cell, _ ->
                    val section = listSectionOf(cell)
                    val listState = nodes[nodes[cell]?.parent ?: 0L]
                    if (
                        section != null &&
                        listState != null &&
                        section != listState.textOrNull(PropKey.LIST_ACTIVE_SECTION).orEmpty()
                    ) {
                        parkCell(cell)
                    } else {
                        recycleCell(cell)
                    }
                },
                sectionSwitch = sectionSwitch,
            )
            if (sectionSwitch != null) {
                // Cells of the active section that the switch did not bind
                // (scrolled away meanwhile) return to the ordinary recycling.
                list.postOnAnimation {
                    // After this frame's layout bound the switched-in rows.
                    list.post {
                        releaseParkedCells { cell ->
                            nodes[cell]?.parent == id &&
                                listSectionOf(cell) == nodes[id]?.textOrNull(PropKey.LIST_ACTIVE_SECTION).orEmpty() &&
                                !list.isBound(cell)
                        }
                    }
                }
            }
            list.setFullSpanIds(
                itemIds.filterTo(HashSet()) { item -> nodes[item]?.flag(PropKey.LIST_FULL_SPAN, false) == true },
            )
            list.setStickyIds(
                itemIds.filterTo(HashSet()) { item -> nodes[item]?.flag(PropKey.STICKY_HEADER, false) == true },
            )
        }
    }

    private fun requestVirtualListScroll(list: PamRecyclerList, state: NodeState) {
        list.post {
            val targetTestId = state.textOrNull(PropKey.SCROLL_TARGET_TEST_ID).orEmpty()
            val targetOffset = if (targetTestId.isNotEmpty()) {
                val targetId = descendantWithTestId(state.id, targetTestId)
                val cellId = targetId?.let(::virtualCellRoot)
                val cellFrame = cellId?.let { frames[it] }
                val innerFrame = targetId?.let { frames[it] }
                if (cellId != null && cellFrame != null && innerFrame != null) {
                    val horizontal = state.flag(PropKey.LIST_HORIZONTAL, false)
                    val innerStart = if (horizontal) {
                        innerFrame.x - cellFrame.x
                    } else {
                        innerFrame.y - cellFrame.y
                    }
                    val innerExtent = if (horizontal) innerFrame.width else innerFrame.height
                    if (
                        list.scrollToRichItem(
                            cellId,
                            state.integer(PropKey.SCROLL_TARGET_ALIGNMENT, 1).toInt(),
                            innerStartPx = if (cellId == targetId) 0 else dp(innerStart),
                            targetExtentPx = if (cellId == targetId) null else dp(innerExtent),
                        )
                    ) {
                        return@post
                    }
                }
                val targetFrame = targetId?.let { frames[it] }
                val listFrame = frames[state.id]
                if (targetFrame != null && listFrame != null) {
                    val horizontal = state.flag(PropKey.LIST_HORIZONTAL, false)
                    val targetStart = if (horizontal) {
                        targetFrame.x - listFrame.x
                    } else {
                        targetFrame.y - listFrame.y
                    }
                    val targetExtent = if (horizontal) targetFrame.width else targetFrame.height
                    // Align against the list's real native viewport: host-side
                    // insets (keyboard avoidance, safe areas) can make it
                    // shorter than the engine frame, which left end-aligned
                    // targets hidden below the visible edge.
                    val nativeExtent = (if (horizontal) list.width else list.height) /
                        list.resources.displayMetrics.density
                    val viewportExtent = if (nativeExtent > 0f) {
                        nativeExtent
                    } else if (horizontal) {
                        listFrame.width
                    } else {
                        listFrame.height
                    }
                    alignedTargetOffset(
                        targetStart,
                        targetExtent,
                        viewportExtent,
                        state.integer(PropKey.SCROLL_TARGET_ALIGNMENT, 1).toInt(),
                    )
                } else {
                    return@post
                }
            } else {
                state.number(PropKey.SCROLL_TARGET_OFFSET, -1.0).toFloat()
            }
            if (targetOffset >= 0f) {
                list.scrollToLogicalOffset(targetOffset)
            }
        }
    }

    private fun alignedTargetOffset(
        targetStart: Float,
        targetExtent: Float,
        viewportExtent: Float,
        alignment: Int,
    ): Float {
        val available = (viewportExtent - targetExtent).coerceAtLeast(0f)
        val adjustment = when (alignment) {
            2 -> available / 2f
            3 -> available
            else -> 0f
        }
        return (targetStart - adjustment).coerceAtLeast(0f)
    }

    private fun descendantWithTestId(rootId: Long, testId: String): Long? {
        val pending = ArrayDeque<Long>()
        children[rootId]?.forEach(pending::addLast)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            val state = nodes[id] ?: continue
            if (state.textOrNull(PropKey.TEST_ID) == testId) return id
            children[id]?.forEach(pending::addLast)
        }
        return null
    }

    private fun virtualListAncestor(start: Long): Long? {
        var current = start
        var depth = 0
        while (current != 0L) {
            val state = nodes[current] ?: return null
            if (state.kind == NodeKind.VIRTUAL_LIST) return current
            current = state.parent
            check(++depth <= MAX_VIRTUAL_DEPTH) { "Virtual list hierarchy is too deep" }
        }
        return null
    }

    private fun materializeCell(id: Long, holder: FrameLayout) {
        val slotFrame = cellSlotFrame(id) ?: return
        if (parkedCells.remove(id)) unparkCell(id, holder)
        materializeCellNode(id, id, slotFrame, holder)
    }

    /** Keeps the views of a cell whose section was switched away. */
    private fun parkCell(id: Long) {
        parkedCells += id
        forEachCellView(id) { view -> pamImageView(view)?.retainPixels = true }
    }

    /**
     * Moves a parked cell's top-level views into [holder] in node order;
     * nodes created while it was parked are materialized by the caller.
     */
    private fun unparkCell(id: Long, holder: FrameLayout) {
        fun attachTop(nodeId: Long) {
            val view = views[nodeId]
            if (view != null) {
                (view.parent as? ViewGroup)?.removeView(view)
                holder.addView(view)
                return
            }
            children[nodeId]?.forEach(::attachTop)
        }
        attachTop(id)
        forEachCellView(id) { view -> pamImageView(view)?.retainPixels = false }
    }

    private fun forEachCellView(id: Long, action: (View) -> Unit) {
        views[id]?.let(action)
        children[id]?.forEach { child -> forEachCellView(child, action) }
    }

    /** Dematerializes parked cells matching [predicate] (memory pressure, stale). */
    private fun releaseParkedCells(predicate: (Long) -> Boolean) {
        if (parkedCells.isEmpty()) return
        val released = parkedCells.filter(predicate)
        released.forEach { id ->
            parkedCells.remove(id)
            forEachCellView(id) { view -> pamImageView(view)?.retainPixels = false }
            recycleCell(id)
        }
    }

    /** Section key of a list row, or null for rows shown in every section. */
    private fun listSectionOf(id: Long): String? =
        nodes[id]?.textOrNull(PropKey.LIST_SECTION)?.takeIf { it.isNotEmpty() }

    /** The cell holder's frame: the root frame outset by the root's margins. */
    private fun cellSlotFrame(rootId: Long): Frame? {
        val frame = frames[rootId] ?: return null
        return cellSlotFrame(frame, cellRootMargins(nodes[rootId]?.properties))
    }

    private fun materializeCellNode(
        id: Long,
        rootId: Long,
        rootFrame: Frame,
        holder: FrameLayout,
    ) {
        val state = nodes[id] ?: return
        if (!state.virtual && views[id] == null) {
            val view = takePooledCellView(state) ?: createView(state.kind, state)
            if (view is TextView) state.defaultHighlightColor = view.highlightColor
            putView(id, view)
            attachCellView(view, state, rootId, holder)
            applyInitialProperties(view, state)
            installEvents(view, state)
            applyCellLayout(id, rootId, rootFrame)
        }
        children[id]?.forEach { child ->
            materializeCellNode(child, rootId, rootFrame, holder)
        }
    }

    private fun attachCellView(
        view: View,
        state: NodeState,
        rootId: Long,
        holder: FrameLayout,
    ) {
        var parentId = state.parent
        while (parentId != 0L && parentId != rootId && views[parentId] == null) {
            parentId = nodes[parentId]?.parent ?: 0L
        }
        val parent = views[parentId]
        if (parentId == rootId && state.id != rootId && parent != null) {
            attach(view, parentId, state.index)
        } else if (state.id != rootId && parent != null) {
            attach(view, parentId, state.index)
        } else {
            (view.parent as? ViewGroup)?.removeView(view)
            holder.addView(view)
        }
    }

    private fun applyCellLayout(id: Long, rootId: Long, rootFrame: Frame) {
        val frame = frames[id] ?: return
        val view = views[id] ?: return
        val state = nodes[id] ?: return
        var hostedParent = state.parent
        while (hostedParent != 0L && hostedParent != rootId && views[hostedParent] == null) {
            hostedParent = nodes[hostedParent]?.parent ?: 0L
        }
        val parentFrame = if (id == rootId || hostedParent == rootId && views[rootId] == null) {
            rootFrame
        } else {
            frames[hostedParent] ?: rootFrame
        }
        val density = resourcesDensity()
        val horizontal = snappedPixelSpan(
            frame.x,
            frame.width,
            parentFrame.x,
            density,
            preserveContentExtent = state.kind == NodeKind.TEXT,
        )
        val vertical = snappedPixelSpan(
            frame.y,
            frame.height,
            parentFrame.y,
            density,
            preserveContentExtent = state.kind == NodeKind.TEXT,
        )
        val paddedHost = if (nodes[hostedParent]?.kind == NodeKind.CUSTOM_VIEW) {
            views[hostedParent] as? FrameLayout
        } else {
            null
        }
        view.layoutParams = FrameLayout.LayoutParams(horizontal.extent, vertical.extent).apply {
            // Cell frames are physical engine coordinates too; START would
            // mirror them a second time inside an RTL holder.
            gravity = PAM_PHYSICAL_FRAME_GRAVITY
            // The root sits at its margins inside the holder (the slot).
            leftMargin = if (id == rootId) horizontal.offset else engineFrameMargin(
                horizontal.offset, paddedHost?.paddingLeft ?: 0,
            )
            topMargin = if (id == rootId) vertical.offset else engineFrameMargin(
                vertical.offset, paddedHost?.paddingTop ?: 0,
            )
        }
    }

    private fun dematerializeSubtree(id: Long) {
        children[id]?.forEach(::dematerializeSubtree)
        val view = views[id] ?: return
        val state = nodes[id] ?: return
        state.intersectionObserver?.close()
        state.intersectionObserver = null
        state.propertyAnimator?.cancel()
        state.keyframeAnimator?.cancel()
        state.workletAnimator?.cancel()
        state.cancelMotion()
        state.loadingDrawable?.stop()
        state.pendingChange?.let(main::removeCallbacks)
        pamImageView(view)?.let(imageLoader::cancel)
        (view as? PamWebView)?.destroy()
        view.let(nativeViews::release)
        clearHitSlop(view)
        val navigationParent = views[state.parent] as? PamNavigationHost
        if (navigationParent != null) navigationParent.removeRoute(view)
        else (view.parent as? ViewGroup)?.removeView(view)
        removeView(id)
        poolCellView(id, state, view)
    }

    private fun attach(view: View, parentId: Long, index: Int) {
        if (parentId == 0L) {
            host.addView(view, index.coerceIn(0, host.childCount))
            return
        }
        when (val parent = views[parentId]) {
            is PamPressable -> {
                parent.insert(view, index)
                parent.setNativeTransformTarget(view)
            }
            is PamContainer -> parent.insert(view, index)
            is PamRefreshContainer -> parent.insert(view, index)
            is PamDrawerLayout -> parent.insert(view, index)
            is PamNavigationHost -> parent.insert(view, index)
            is PamTabHost -> parent.insertScene(view, index)
            is PamModalHost -> parent.insert(view, index)
            is PamScrollContainer -> parent.insert(view)
            else -> {
                if (parent is ViewGroup && nodes[parentId]?.kind == NodeKind.CUSTOM_VIEW) {
                    parent.addView(view, index.coerceIn(0, parent.childCount))
                } else {
                    error("Node $parentId cannot contain children")
                }
            }
        }
    }

    private fun attachHosted(view: View, state: NodeState) {
        val parent = effectiveParent(state.parent)
        val index = hostedInsertionIndex(state, parent)
        attach(view, parent, index)
    }

    /**
     * Index of [state]'s view among the views hosted by [parentId]: an
     * in-order walk of the host's logical children that descends only into
     * layout-only (virtual) nodes. Proportional to the host's children, not to
     * the whole tree (the previous scan visited every node on every create,
     * which made large commits and prepends quadratic).
     */
    private fun hostedInsertionIndex(state: NodeState, parentId: Long): Int {
        if (parentId != 0L) {
            var index = 0
            var depth = 0
            fun visit(containerId: Long): Boolean {
                val siblings = children[containerId] ?: return false
                for (childId in siblings) {
                    if (childId == state.id) return true
                    val child = nodes[childId] ?: continue
                    if (child.virtual) {
                        check(++depth <= MAX_VIRTUAL_DEPTH) { "Virtual native hierarchy is too deep" }
                        val found = visit(childId)
                        depth--
                        if (found) return true
                    } else if (views[childId] != null) {
                        index++
                    }
                }
                return false
            }
            if (visit(parentId)) return index
        }
        return scanHostedInsertionIndex(state, parentId)
    }

    private fun scanHostedInsertionIndex(state: NodeState, parentId: Long): Int {
        val targetPath = hostedPath(state, parentId)
        var index = 0
        for (position in 0 until nodes.size()) {
            val candidate = nodes.valueAt(position)
            if (
                candidate.id == state.id ||
                views[candidate.id] == null ||
                effectiveParent(candidate.parent) != parentId
            ) {
                continue
            }
            val comparison = compareHostedPaths(
                hostedPath(candidate, parentId),
                targetPath,
            )
            if (
                comparison < 0 ||
                comparison == 0 && candidate.mountOrder < state.mountOrder
            ) {
                index++
            }
        }
        return index
    }

    private fun hostedPath(state: NodeState, parentId: Long): List<Int> {
        val reversed = ArrayList<Int>()
        var current: NodeState? = state
        var depth = 0
        while (current != null && current.id != parentId) {
            reversed += current.index
            current = nodes[current.parent]
            check(++depth <= MAX_VIRTUAL_DEPTH) { "Virtual native hierarchy is too deep" }
        }
        check(parentId == 0L || current?.id == parentId) {
            "Node ${state.id} is not descended from host $parentId"
        }
        reversed.reverse()
        return reversed
    }

    private fun compareHostedPaths(left: List<Int>, right: List<Int>): Int {
        val shared = min(left.size, right.size)
        for (index in 0 until shared) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }

    private fun effectiveParent(start: Long): Long {
        var parent = start
        var depth = 0
        while (parent != 0L && nodes[parent]?.virtual == true) {
            parent = nodes[parent]?.parent ?: 0L
            check(++depth <= MAX_VIRTUAL_DEPTH) { "Virtual native hierarchy is too deep" }
        }
        return parent
    }

    private fun promote(state: NodeState) {
        state.virtual = false
        val view = createView(state.kind, state)
        putView(state.id, view)
        attachHosted(view, state)
        applyInitialProperties(view, state)
        installEvents(view, state)
        frames[state.id]?.let { applyLayout(state.id) }
        reattachHostedDescendants(state.id)
    }

    private fun demote(state: NodeState) {
        val view = views[state.id] ?: return
        clearHitSlop(view)
        (view.parent as? ViewGroup)?.removeView(view)
        removeView(state.id)
        state.virtual = true
        reattachHostedDescendants(state.id)
    }

    private fun reattachHostedDescendants(parent: Long) {
        children[parent]?.forEach { childId ->
            val childState = nodes[childId] ?: return@forEach
            val child = views[childId]
            if (child == null) {
                reattachHostedDescendants(childId)
            } else {
                clearHitSlop(child)
                (child.parent as? ViewGroup)?.removeView(child)
                attachHosted(child, childState)
                applyLayout(childId)
            }
        }
    }

    private fun applyLayout(id: Long) {
        if (views[id] == null) return
        virtualCellRoot(id)?.let { rootId ->
            val slotFrame = cellSlotFrame(rootId) ?: return
            applyCellLayout(id, rootId, slotFrame)
            return
        }
        val frame = frames[id] ?: return
        val view = views[id] ?: return
        val state = nodes[id] ?: return
        val parentState = nodes[state.parent]
        val effectiveParentId = effectiveParent(state.parent)
        val parentFrame = frames[effectiveParentId]
        val parentView = views[effectiveParentId]
        val density = resourcesDensity()
        val horizontal = snappedPixelSpan(
            frame.x,
            frame.width,
            parentFrame?.x ?: 0f,
            density,
            preserveContentExtent = state.kind == NodeKind.TEXT,
        )
        val vertical = snappedPixelSpan(
            frame.y,
            frame.height,
            parentFrame?.y ?: 0f,
            density,
            preserveContentExtent = state.kind == NodeKind.TEXT,
        )
        val hostedParentState = nodes[effectiveParent(state.parent)]
        val safeAreaParentState = when {
            parentState?.kind == NodeKind.SAFE_AREA_VIEW -> parentState
            hostedParentState?.kind == NodeKind.SAFE_AREA_VIEW -> hostedParentState
            else -> null
        }
        val parentSafePadding = safeAreaParentState != null &&
            safeAreaParentState.integer(
                PropKey.SAFE_AREA_MODE,
                SAFE_AREA_PADDING.toLong(),
            ).toInt() == SAFE_AREA_PADDING
        val parentSafeHorizontal = if (parentSafePadding) {
            (if (safeAreaParentState?.flag(PropKey.SAFE_AREA_LEFT, true) == true) {
                safeAreaParentState.safeAreaLeftInset
            } else {
                0
            }) + (if (safeAreaParentState?.flag(PropKey.SAFE_AREA_RIGHT, true) == true) {
                safeAreaParentState.safeAreaRightInset
            } else {
                0
            })
        } else {
            0
        }
        val parentSafeVertical = if (parentSafePadding) {
            (if (safeAreaParentState?.flag(PropKey.SAFE_AREA_TOP, true) == true) {
                safeAreaParentState.safeAreaTopInset
            } else {
                0
            }) + (if (safeAreaParentState?.flag(PropKey.SAFE_AREA_BOTTOM_EDGE, true) == true) {
                safeAreaParentState.safeAreaBottomInset
            } else {
                0
            })
        } else {
            0
        }
        val parentMainAxisHorizontal = when (
            parentState?.integer(
                PropKey.FLEX_DIRECTION,
                when (parentState.kind) {
                    NodeKind.ROW -> 2L
                    else -> 1L
                },
            )?.toInt()
        ) {
            2, 4 -> true
            else -> false
        }
        val (parentSafeWidthReduction, parentSafeHeightReduction) =
            safeAreaChildCrossAxisReduction(
                mainAxisHorizontal = parentMainAxisHorizontal,
                horizontalInsets = parentSafeHorizontal,
                verticalInsets = parentSafeVertical,
            )
        val parentLayout = parentView?.layoutParams
        val measuredParentWidth = parentView?.let { measured ->
            val layoutWidth = parentLayout?.width ?: 0
            measuredParentExtent(
                if (parentViewportMeasurementIsStale(
                        measured.width,
                        layoutWidth,
                        measured.isLayoutRequested,
                    )
                ) {
                    0
                } else {
                    measured.width
                },
                layoutWidth,
            )
        } ?: 0
        val measuredParentHeight = parentView?.let { measured ->
            val layoutHeight = parentLayout?.height ?: 0
            measuredParentExtent(
                if (parentViewportMeasurementIsStale(
                        measured.height,
                        layoutHeight,
                        measured.isLayoutRequested,
                    )
                ) {
                    0
                } else {
                    measured.height
                },
                layoutHeight,
            )
        } ?: 0
        val (measuredHorizontalReduction, measuredVerticalReduction) =
            measuredCrossAxisViewportReduction(
                // These dimensions belong to the materialized host, not a
                // flattened Row between it and this child. Mixing the Row's
                // axis with a full-height Column's viewport can subtract the
                // system-bar/IME height from each button and collapse it to 0.
                mainAxisHorizontal = hostedParentState?.integer(
                    PropKey.FLEX_DIRECTION,
                    if (hostedParentState.kind == NodeKind.ROW) 2L else 1L,
                )?.toInt() in listOf(2, 4),
                // Compare with the parent's pixel-snapped extent: a parent
                // whose fractional frame snapped one pixel narrower is not
                // a reduced viewport (that misread trimmed text by 1 px and
                // ellipsized bold labels: "Curtir" -> "Cur…").
                engineWidth = parentFrame?.let {
                    snappedPixelSpan(it.x, it.width, 0f, density).extent
                } ?: 0,
                measuredWidth = measuredParentWidth,
                engineHeight = parentFrame?.let {
                    snappedPixelSpan(it.y, it.height, 0f, density).extent
                } ?: 0,
                measuredHeight = measuredParentHeight,
            )
        val parentCrossAxisWidthReduction = max(
            parentSafeWidthReduction,
            measuredHorizontalReduction,
        )
        val parentCrossAxisHeightReduction = max(
            parentSafeHeightReduction,
            measuredVerticalReduction,
        )
        val safeMargin = state.kind == NodeKind.SAFE_AREA_VIEW &&
            state.integer(PropKey.SAFE_AREA_MODE, SAFE_AREA_PADDING.toLong()).toInt() ==
            SAFE_AREA_MARGIN
        val safeLeft = if (safeMargin && state.flag(PropKey.SAFE_AREA_LEFT, true)) {
            state.safeAreaLeftInset
        } else {
            0
        }
        val safeTop = if (safeMargin && state.flag(PropKey.SAFE_AREA_TOP, true)) {
            state.safeAreaTopInset
        } else {
            0
        }
        val safeRight = if (safeMargin && state.flag(PropKey.SAFE_AREA_RIGHT, true)) {
            state.safeAreaRightInset
        } else {
            0
        }
        val safeBottom = if (
            safeMargin &&
            state.flag(PropKey.SAFE_AREA_BOTTOM_EDGE, true)
        ) {
            state.safeAreaBottomInset
        } else {
            0
        }
        var width = (
            horizontal.extent - safeLeft - safeRight - parentCrossAxisWidthReduction
            ).coerceAtLeast(0)
        var height = (
            vertical.extent - safeTop - safeBottom - parentCrossAxisHeightReduction
            ).coerceAtLeast(0)
        height = keyboardAvoidingViewportHeight(
            baseHeight = height,
            keyboardOverlap = state.keyboardAvoidingViewportInset,
            resize = state.kind == NodeKind.KEYBOARD_AVOIDING_VIEW &&
                keyboardAvoidingBehaviorReducesViewport(state.keyboardBehavior),
        )
        // Engine frames already include authored padding. FrameLayout adds
        // its padding to child margins again, unlike engine-owned containers
        // whose Android padding is zero. Preserve native host padding while
        // expressing engine positions relative to that padded origin.
        val paddedHost = if (hostedParentState?.kind == NodeKind.CUSTOM_VIEW) {
            parentView as? FrameLayout
        } else {
            null
        }
        var leftPx = engineFrameMargin(horizontal.offset, paddedHost?.paddingLeft ?: 0) + safeLeft
        var topPx = engineFrameMargin(vertical.offset, paddedHost?.paddingTop ?: 0) + safeTop
        compensateFlexParentViewportReduction(
            state = state,
            parentState = parentState,
            parentFrame = parentFrame,
            // Layout-only parents have no Android View. Their children are hosted by the
            // nearest materialized ancestor, whose measured viewport is the one that can be
            // reduced by safe areas or fixed siblings.
            parentView = parentView,
            applyHorizontal = { offset, reduction ->
                leftPx -= offset
                width = (width - reduction).coerceAtLeast(0)
            },
            applyVertical = { offset, reduction ->
                topPx -= offset
                height = (height - reduction).coerceAtLeast(0)
            },
        )
        var frameGravity = PAM_PHYSICAL_FRAME_GRAVITY
        // Full-window modal content belongs to the Dialog viewport, not the
        // activity's engine frame (system bars and IME resize can make those
        // heights differ). Only content spanning the modal fills the window;
        // a shorter child (a bottom-anchored options sheet inside a flattened
        // full-height Column) keeps its frame and its anchoring edge.
        if (parentView is PamModalHost && parentView.usesWindowSizedContent()) {
            val placement = windowSizedModalChildPlacement(
                left = leftPx,
                top = topPx,
                width = width,
                height = height,
                modalWidth = parentFrame?.let { snappedPixelSpan(it.x, it.width, 0f, density).extent } ?: 0,
                modalHeight = parentFrame?.let { snappedPixelSpan(it.y, it.height, 0f, density).extent } ?: 0,
            )
            width = placement.width
            height = placement.height
            leftPx = placement.left
            topPx = placement.top
            frameGravity = placement.gravity
        }
        val current = view.layoutParams as? FrameLayout.LayoutParams

        val layoutChanged =
            current == null ||
            current.width != width ||
            current.height != height ||
            current.leftMargin != leftPx ||
            current.topMargin != topPx ||
            current.gravity != frameGravity
        if (layoutChanged) {
            view.layoutParams = FrameLayout.LayoutParams(width, height).apply {
                gravity = frameGravity
                leftMargin = leftPx
                topMargin = topPx
            }
            applyHostedChildLayouts(state.id)
        }
        // Some plugin hosts (for example Calendar) draw against tagged child
        // bounds. A descendant frame can change without mutating the host's
        // own properties, so invalidate the hosted ancestor chain after
        // applying layout instead of leaving a stale custom canvas.
        // Within a commit each ancestor is invalidated once: a mount of a
        // few hundred views otherwise walked (and invalidated) the full
        // ancestor chain of every view, O(views x depth).
        val invalidated = layoutInvalidatedAncestors
        var ancestor = view.parent
        while (ancestor is View) {
            if (invalidated != null && !invalidated.add(ancestor)) break
            ancestor.invalidate()
            ancestor = ancestor.parent
        }
        applyHitSlop(view, state)
        (view as? TextView)?.let { applyTextAlignment(it, state) }
        state.properties[PropKey.TRANSLATION_X_PERCENT]?.decimal()?.let { percent ->
            view.translationX = width * (percent / 100.0).toFloat()
        }
        state.properties[PropKey.TRANSLATION_Y_PERCENT]?.decimal()?.let { percent ->
            view.translationY = height * (percent / 100.0).toFloat()
        }
        if (
            state.properties.containsKey(PropKey.TRANSFORM_ORIGIN_X) ||
            state.properties.containsKey(PropKey.TRANSFORM_ORIGIN_Y)
        ) {
            applyTransformOrigin(view, state, width, height)
        }
    }

    private fun compensateFlexParentViewportReduction(
        state: NodeState,
        parentState: NodeState?,
        parentFrame: Frame?,
        parentView: View?,
        applyHorizontal: (offset: Int, reduction: Int) -> Unit,
        applyVertical: (offset: Int, reduction: Int) -> Unit,
    ) {
        if (parentState == null || parentFrame == null || parentView == null) return
        val axis = when (parentState.kind) {
            NodeKind.ROW -> Axis.HORIZONTAL
            NodeKind.COLUMN -> Axis.VERTICAL
            NodeKind.SAFE_AREA_VIEW -> when (
                parentState.integer(PropKey.FLEX_DIRECTION, 1L).toInt()
            ) {
                2, 4 -> Axis.HORIZONTAL
                else -> Axis.VERTICAL
            }
            NodeKind.KEYBOARD_AVOIDING_VIEW -> Axis.VERTICAL
            else -> return
        }
        val density = resourcesDensity()
        val engineExtent = when (axis) {
            Axis.HORIZONTAL -> snappedPixelSpan(parentFrame.x, parentFrame.width, 0f, density).extent
            Axis.VERTICAL -> snappedPixelSpan(parentFrame.y, parentFrame.height, 0f, density).extent
        }
        val rawMeasuredExtent = when (axis) {
            Axis.HORIZONTAL -> parentView.width
            Axis.VERTICAL -> parentView.height
        }
        val layoutParamExtent = when (axis) {
            Axis.HORIZONTAL -> parentView.layoutParams?.width ?: 0
            Axis.VERTICAL -> parentView.layoutParams?.height ?: 0
        }
        val measurementIsStale = parentViewportMeasurementIsStale(
            measuredExtent = rawMeasuredExtent,
            layoutParamExtent = layoutParamExtent,
            layoutRequested = parentView.isLayoutRequested,
        )
        if (!parentView.isLaidOut || rawMeasuredExtent <= 0 || measurementIsStale) {
            deferViewportLayoutUntilMeasured(state.id, parentView, axis)
        }
        val hostedThroughLayoutOnlyParent = views[state.parent] == null
        val nativePadding = if (hostedThroughLayoutOnlyParent) {
            when (axis) {
                Axis.HORIZONTAL -> parentView.paddingLeft + parentView.paddingRight
                Axis.VERTICAL -> parentView.paddingTop + parentView.paddingBottom
            }
        } else {
            0
        }
        val measuredExtent = when (axis) {
            Axis.HORIZONTAL -> hostedContentExtent(
                if (measurementIsStale) 0 else parentView.width,
                layoutParamExtent,
                nativePadding,
            )
            Axis.VERTICAL -> hostedContentExtent(
                if (measurementIsStale) 0 else parentView.height,
                layoutParamExtent,
                nativePadding,
            )
        }
        val hostExtent = when (axis) {
            Axis.HORIZONTAL -> host.width
            Axis.VERTICAL -> host.height
        }
        val visibleExtent = if (hostExtent > 0) {
            min(measuredExtent, hostExtent)
        } else {
            measuredExtent
        }
        val parentUsesSafeAreaPadding =
            parentState.kind == NodeKind.SAFE_AREA_VIEW &&
                parentState.integer(
                    PropKey.SAFE_AREA_MODE,
                    SAFE_AREA_PADDING.toLong(),
                ).toInt() == SAFE_AREA_PADDING
        val safeAreaInsets = if (parentUsesSafeAreaPadding) {
            when (axis) {
                Axis.HORIZONTAL ->
                    (if (parentState.flag(PropKey.SAFE_AREA_LEFT, true)) {
                        parentState.safeAreaLeftInset
                    } else {
                        0
                    }) + (if (parentState.flag(PropKey.SAFE_AREA_RIGHT, true)) {
                        parentState.safeAreaRightInset
                    } else {
                        0
                    })
                Axis.VERTICAL ->
                    (if (parentState.flag(PropKey.SAFE_AREA_TOP, true)) {
                        parentState.safeAreaTopInset
                    } else {
                        0
                    }) + (if (
                        parentState.flag(PropKey.SAFE_AREA_BOTTOM_EDGE, true)
                    ) {
                        parentState.safeAreaBottomInset
                    } else {
                        0
                    })
            }
        } else {
            0
        }
        val renderedExtent = safeAreaFlexViewportExtent(
            layoutExtent = visibleExtent,
            safeAreaInsets = safeAreaInsets,
            // PAM owns an explicit edge-to-edge window. The legacy visible
            // display frame excludes status/navigation bars on Samsung and
            // would subtract them a second time from fixed flex siblings.
            windowVisibleExtent = visibleExtent,
        )
        if (renderedExtent <= 0) return
        val viewportReduction = engineExtent - renderedExtent
        if (viewportReduction <= 0) return

        val siblings = children[parentState.id]
            ?.mapNotNull(nodes::get)
            ?.sortedBy(NodeState::index)
            ?: return
        val totalGrow = siblings.sumOf { sibling ->
            sibling.number(PropKey.FLEX_GROW, 0.0).coerceAtLeast(0.0)
        }
        if (totalGrow <= 0.0) return

        var growBefore = 0.0
        for (sibling in siblings) {
            if (sibling.id == state.id) break
            growBefore += sibling.number(PropKey.FLEX_GROW, 0.0).coerceAtLeast(0.0)
        }
        val ownGrow = state.number(PropKey.FLEX_GROW, 0.0).coerceAtLeast(0.0)
        val reductionBefore = (viewportReduction * growBefore / totalGrow).roundToInt()
        val reductionThrough = (
            viewportReduction * (growBefore + ownGrow) / totalGrow
            ).roundToInt()
        val ownReduction = (reductionThrough - reductionBefore).coerceAtLeast(0)
        when (axis) {
            Axis.HORIZONTAL -> applyHorizontal(reductionBefore, ownReduction)
            Axis.VERTICAL -> applyVertical(reductionBefore, ownReduction)
        }
    }

    private fun deferViewportLayoutUntilMeasured(
        stateId: Long,
        parentView: View,
        axis: Axis,
    ) {
        if (deferredViewportLayouts.containsKey(stateId)) return
        lateinit var listener: View.OnLayoutChangeListener
        listener = View.OnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            val extent = if (axis == Axis.HORIZONTAL) right - left else bottom - top
            if (extent <= 0) return@OnLayoutChangeListener
            view.removeOnLayoutChangeListener(listener)
            deferredViewportLayouts.remove(stateId)
            main.post {
                if (nodes[stateId] != null) applyLayout(stateId)
            }
        }
        deferredViewportLayouts[stateId] = parentView to listener
        parentView.addOnLayoutChangeListener(listener)
    }

    private fun virtualCellRoot(id: Long): Long? {
        var current = id
        var depth = 0
        while (current != 0L) {
            val state = nodes[current] ?: return null
            val parent = nodes[state.parent] ?: return null
            if (parent.kind == NodeKind.VIRTUAL_LIST) return current
            current = state.parent
            check(++depth <= MAX_VIRTUAL_DEPTH) { "Virtual cell hierarchy is too deep" }
        }
        return null
    }

    private fun virtualCellHolder(rootId: Long): FrameLayout? {
        // Fast path: ask the owning list for the holder bound to this cell
        // instead of scanning every materialized view.
        val listId = nodes[rootId]?.parent
        val list = listId?.let { views[it] } as? PamRecyclerList
        if (list != null && nodes[listId]?.kind == NodeKind.VIRTUAL_LIST) {
            return list.boundContainer(rootId)
        }
        for (position in 0 until views.size()) {
            val id = views.keyAt(position)
            if (id != rootId && virtualCellRoot(id) != rootId) continue
            var parent = views.valueAt(position).parent
            while (parent is ViewGroup) {
                if (parent is FrameLayout && parent.parent is PamRecyclerList) return parent
                parent = parent.parent
            }
        }
        return null
    }

    /**
     * NodeState already contains all authored properties. Gesture properties
     * therefore configure the same recognizer/drag program repeatedly during
     * mounting. Keep the first configuration (an initial snap may need it),
     * then let installEvents apply the final press feedback after this pass.
     * Incremental updates still apply immediately.
     */
    private fun applyInitialProperties(view: View, state: NodeState) {
        // Text state already holds its final style. Rebuilding spans for each
        // font/size/line-height property repeats the same content and setText.
        // Controls keep their immediate configuration and value semantics.
        val text = (view as? TextView)?.takeIf { isRichTextView(it, state) }
        val textUpdates = text?.let { PamInitialTextUpdates() }
        val backgroundUpdate = if (
            view is PamContainer &&
            state.kind != NodeKind.IMAGE_BACKGROUND &&
            state.kind != NodeKind.CUSTOM_VIEW
        ) PamInitialBackgroundUpdate() else null
        state.initialTextUpdates = textUpdates
        state.initialBackgroundUpdate = backgroundUpdate
        state.applyingInitialProperties = true
        state.pressableConfiguredDuringInitialization = false
        try {
            state.properties.forEach { (key, value) -> applyProperty(view, state, key, value) }
        } finally {
            state.initialTextUpdates = null
            state.initialBackgroundUpdate = null
            state.applyingInitialProperties = false
            state.pressableConfiguredDuringInitialization = false
        }
        if (text != null && textUpdates != null) {
            if (textUpdates.typeface) applyTypeface(text, state)
            if (textUpdates.size) applyTextSizing(text, state)
            if (textUpdates.letterSpacing) applyLetterSpacing(text, state)
            if (textUpdates.content) applyTextContent(text, state)
        }
        if (backgroundUpdate?.requested == true) {
            updateBackground(view, state, backgroundUpdate.backgroundColor, backgroundUpdate.borderColor)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applyProperty(
        view: View,
        state: NodeState,
        key: PropKey,
        value: PropValue,
    ) {
        when (key) {
            PropKey.TEXT -> (view as? TextView)?.let { text ->
                val semanticText = value.semanticValue().toString()
                state.baseText = semanticText
                if (isRichTextView(text, state)) {
                    applyTextContent(text, state)
                } else {
                    text.text = semanticText
                    applyTextDataDetector(text, state)
                }
            }
            PropKey.TEXT_SPANS,
            PropKey.ON_SPAN_PRESS,
            -> (view as? TextView)?.let { if (isRichTextView(it, state)) applyTextContent(it, state) }
            PropKey.INCLUDE_FONT_PADDING -> (view as? TextView)?.includeFontPadding = value.flag()
            PropKey.ON_LAYOUT -> {
                lastLayoutEvents.remove(state.id)
                queueLayoutEvent(state.id)
            }
            PropKey.STICKY_HEADER -> applyStickyHeader(view, value.flag(), state)
            PropKey.SCROLL_KEYBOARD_INSET ->
                (view as? PamScrollContainer)?.let { configureScrollKeyboardInset(it, state, value.flag()) }
            PropKey.VALUE -> when (view) {
                is EditText -> applyInputValue(view, state, value.text(key))
                is PamDrawingCanvas -> view.setDrawing(value.text(key))
                else -> view.tag = value.semanticValue()
            }
            PropKey.PLACEHOLDER -> (view as? EditText)?.hint = value.text(key)
            PropKey.SOURCE -> loadImage(view, state)
            PropKey.BACKGROUND_COLOR,
            PropKey.NATIVE_BACKGROUND_COLOR_RESOURCE,
            PropKey.BORDER_RADIUS,
            PropKey.BORDER_WIDTH,
            PropKey.BORDER_COLOR,
            PropKey.NATIVE_BORDER_COLOR_RESOURCE,
            PropKey.BORDER_STYLE,
            PropKey.BORDER_TOP_LEFT_RADIUS,
            PropKey.BORDER_TOP_RIGHT_RADIUS,
            PropKey.BORDER_BOTTOM_RIGHT_RADIUS,
            PropKey.BORDER_BOTTOM_LEFT_RADIUS,
            PropKey.BORDER_LEFT_WIDTH,
            PropKey.BORDER_TOP_WIDTH,
            PropKey.BORDER_RIGHT_WIDTH,
            PropKey.BORDER_BOTTOM_WIDTH,
            PropKey.BORDER_TOP_COLOR,
            PropKey.BORDER_RIGHT_COLOR,
            PropKey.BORDER_BOTTOM_COLOR,
            PropKey.BORDER_LEFT_COLOR,
            PropKey.RIPPLE_COLOR,
            PropKey.RIPPLE_BORDERLESS,
            PropKey.RIPPLE_RADIUS,
            PropKey.RIPPLE_FOREGROUND,
            PropKey.RIPPLE_ALPHA,
            PropKey.BACKGROUND_GRADIENT,
            PropKey.BORDER_GRADIENT,
            PropKey.BOX_SHADOWS,
            -> {
                updateBackground(view, state)
                if (key in BORDER_WIDTH_KEYS) applyLeafPadding(view, state)
            }
            PropKey.FILTER_COLOR_MATRIX -> applyFilter(view, state)
            PropKey.BACKDROP_BLUR_RADIUS,
            PropKey.BACKDROP_COLOR_MATRIX,
            -> applyBackdrop(view, state)
            PropKey.SHIMMER_GRADIENT_COLOR,
            PropKey.SHIMMER_DURATION_MS,
            PropKey.SHIMMER_ENABLED,
            -> (view as? PamContainer)?.setShimmer(
                state.properties[PropKey.SHIMMER_ENABLED]?.let { (it as? PropValue.Flag)?.value } ?: false,
                state.integer(PropKey.SHIMMER_GRADIENT_COLOR, 0x59FFFFFF).toInt(),
                state.integer(PropKey.SHIMMER_DURATION_MS, 1200L),
            )
            PropKey.SHADOW_OFFSET_X,
            PropKey.SHADOW_OFFSET_Y,
            PropKey.SHADOW_BLUR_RADIUS,
            PropKey.SHADOW_SPREAD_RADIUS,
            PropKey.SHADOW_COLOR,
            -> applyBoxShadow(view, state)
            PropKey.TEXT_COLOR -> when (view) {
                is TextView -> {
                    val color = value.integer().toInt()
                    applySemanticTextColor(view, color)
                    if (view is Button && state.flag(PropKey.LOADING, false)) {
                        state.loadingDrawable?.setColor(color)
                    }
                }
                is PamRecyclerList -> view.setTextColor(value.integer().toInt())
            }
            PropKey.NATIVE_TEXT_COLOR_RESOURCE -> resolveNativeColor(value.text(key))?.let { color ->
                when (view) {
                    is TextView -> applySemanticTextColor(view, color)
                    is PamRecyclerList -> view.setTextColor(color)
                }
            }
            PropKey.NATIVE_STATE_STYLES -> configureNativeStateStyles(view, state)
            PropKey.FONT_SIZE -> (view as? TextView)?.let {
                applyTextSizing(it, state)
                applyLetterSpacing(it, state)
                applyLineHeight(it, state)
            }
            PropKey.ENABLED -> {
                view.isEnabled = value.flag()
                configurePressable(view, state)
            }
            PropKey.ACCESSIBILITY_LABEL -> view.contentDescription = value.text(key)
            PropKey.ACCESSIBILITY_HINT -> {
                view.tooltipText = null
                configureAccessibilityDelegate(view, state)
            }
            PropKey.TEST_ID -> view.transitionName = value.text(key)
            PropKey.SHARED_TRANSITION_TAG ->
                view.setTag(dev.pam.nativeapp.R.id.pam_shared_transition_tag, value.text(key))
            PropKey.SHARED_TRANSITION_CONFIG ->
                view.setTag(dev.pam.nativeapp.R.id.pam_shared_transition_config, value.text(key))
            PropKey.ITEMS -> applyStringList(view, state, value)
            PropKey.SECTION_ITEMS -> applySectionList(view, state, value)
            PropKey.NAVIGATION_OPERATION ->
                (view as? PamNavigationHost)?.operation = value.integer().toInt()
            PropKey.NAVIGATION_TRANSITION ->
                (view as? PamNavigationHost)?.transition = value.integer().toInt()
            PropKey.NAVIGATION_DURATION_MS ->
                (view as? PamNavigationHost)?.durationMs = value.integer()
            PropKey.NAVIGATION_ORIENTATION ->
                (view as? PamNavigationHost)?.navigationOrientation = value.integer().toInt()
            PropKey.NAVIGATION_AUTO_HIDE_HOME_INDICATOR -> Unit
            PropKey.NAVIGATION_TITLE ->
                (view as? PamNavigationHost)?.screenTitle = value.text(key)
            PropKey.NAVIGATION_HEADER_SHOWN ->
                (view as? PamNavigationHost)?.headerShown = value.flag()
            PropKey.NAVIGATION_HEADER_TRANSPARENT ->
                (view as? PamNavigationHost)?.headerTransparent = value.flag()
            PropKey.NAVIGATION_HEADER_BACKGROUND_COLOR ->
                (view as? PamNavigationHost)?.headerBackgroundColor = value.integer().toInt()
            PropKey.NAVIGATION_HEADER_TINT_COLOR ->
                (view as? PamNavigationHost)?.headerTintColor = value.integer().toInt()
            PropKey.NAVIGATION_HEADER_SHADOW_VISIBLE ->
                (view as? PamNavigationHost)?.headerShadowVisible = value.flag()
            PropKey.NAVIGATION_HEADER_SEARCH_ENABLED,
            PropKey.NAVIGATION_HEADER_SEARCH_PLACEHOLDER,
            -> configureNavigationChrome(view, state)
            PropKey.NAVIGATION_HEADER_LARGE_TITLE_ENABLED,
            PropKey.NAVIGATION_GESTURE_DIRECTION,
            PropKey.NAVIGATION_FULL_SCREEN_GESTURE_ENABLED,
            PropKey.NAVIGATION_FREEZE_ON_BLUR,
            PropKey.NAVIGATION_SHEET_GRABBER_VISIBLE,
            PropKey.NAVIGATION_SHEET_EXPANDS_WHEN_SCROLLED_TO_EDGE,
            -> Unit
            PropKey.NAVIGATION_PRESENTATION,
            PropKey.NAVIGATION_SHEET_DETENTS,
            PropKey.NAVIGATION_SHEET_INITIAL_DETENT_INDEX,
            PropKey.NAVIGATION_SHEET_CORNER_RADIUS,
            -> configureNavigationPresentation(view, state)
            PropKey.NAVIGATION_REVISION ->
                (view as? PamNavigationHost)?.navigate(value.integer())
            PropKey.TAB_ITEMS,
            PropKey.TAB_SELECTED_INDEX,
            PropKey.TAB_POSITION,
            PropKey.TAB_ACTIVE_COLOR,
            PropKey.TAB_INACTIVE_COLOR,
            PropKey.TAB_BACKGROUND_COLOR,
            PropKey.TAB_INDICATOR_COLOR,
            PropKey.TAB_SWIPE_ENABLED,
            PropKey.TAB_SCROLL_ENABLED,
            -> configureTabHost(view, state)
            PropKey.CANVAS_COMMANDS ->
                (view as? PamVectorCanvas)?.setCommands(value.text(PropKey.CANVAS_COMMANDS))
            PropKey.NAVIGATION_GESTURE_ENABLED,
            PropKey.NAVIGATION_GESTURE_EDGE_WIDTH,
            PropKey.NAVIGATION_GESTURE_THRESHOLD,
            -> configureGestureNavigation(view, state)
            PropKey.OPACITY -> {
                if (state.integer(PropKey.ANIMATION_KIND, 1L) == 2L) {
                    applyAnimationKind(view, state, 2)
                } else {
                    animateOrSet(view, state, key, value.decimal().toFloat())
                }
                configurePressable(view, state)
            }
            PropKey.TEXT_ALIGN -> if (view is PamEditText) {
                applyInputConfiguration(view, state)
            } else {
                (view as? TextView)?.let { applyTextAlignment(it, state) }
            }
            PropKey.FONT_WEIGHT,
            PropKey.FONT_STYLE,
            PropKey.FONT_FAMILY,
            -> (view as? TextView)?.let {
                applyTypeface(it, state)
                applyLineHeight(it, state)
            }
            PropKey.TEXT_DECORATION -> (view as? TextView)?.let { text ->
                text.paintFlags = text.paintFlags and
                    (Paint.UNDERLINE_TEXT_FLAG or Paint.STRIKE_THRU_TEXT_FLAG).inv()
                when (value.integer().toInt()) {
                    2 -> text.paintFlags = text.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                    3 -> text.paintFlags = text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    4 -> text.paintFlags = text.paintFlags or
                        Paint.UNDERLINE_TEXT_FLAG or Paint.STRIKE_THRU_TEXT_FLAG
                }
            }
            PropKey.TEXT_TRANSFORM -> if (view is TextView && isRichTextView(view, state)) {
                view.transformationMethod = null
                applyTextContent(view, state)
            } else if (view is TextView && view !is EditText) {
                view.transformationMethod = when (value.integer().toInt()) {
                    2, 3, 4 -> PamTextTransformMethod(value.integer().toInt())
                    else -> null
                }
            }
            PropKey.NUMBER_OF_LINES -> (view as? TextView)?.let { text ->
                text.maxLines = value.integer().toInt().takeIf { it > 0 } ?: Int.MAX_VALUE
                // React Native: numberOfLines implies ellipsizeMode="tail".
                if (isRichTextView(text, state) && state.properties[PropKey.TEXT_ELLIPSIZE_MODE] == null) {
                    text.ellipsize = if (value.integer() > 0) TextUtils.TruncateAt.END else null
                }
            }
            PropKey.MULTILINE,
            PropKey.SECURE,
            PropKey.KEYBOARD_TYPE,
            PropKey.AUTO_COMPLETE,
            PropKey.INPUT_EDITABLE,
            PropKey.INPUT_AUTO_CORRECT,
            PropKey.INPUT_AUTO_CAPITALIZE,
            PropKey.INPUT_CARET_HIDDEN,
            PropKey.INPUT_CONTEXT_MENU_HIDDEN,
            PropKey.INPUT_CURSOR_COLOR,
            PropKey.INPUT_DISABLE_FULLSCREEN_UI,
            PropKey.INPUT_AUTOFILL_IMPORTANCE,
            PropKey.INPUT_MODE,
            PropKey.INPUT_MIN_LINES,
            PropKey.INPUT_SELECT_TEXT_ON_FOCUS,
            PropKey.INPUT_SELECTION_START,
            PropKey.INPUT_SELECTION_END,
            PropKey.INPUT_SHOW_SOFT_INPUT_ON_FOCUS,
            PropKey.INPUT_SUBMIT_BEHAVIOR,
            PropKey.INPUT_TEXT_ALIGN_VERTICAL,
            PropKey.INPUT_RETURN_KEY_LABEL,
            PropKey.INPUT_SCROLL_ENABLED,
            PropKey.INPUT_UNDERLINE_COLOR,
            PropKey.INPUT_FORMAT,
            PropKey.INPUT_FORMAT_PATTERN,
            PropKey.INPUT_FORMAT_PLACEHOLDER,
            PropKey.INPUT_FORMAT_PREFIX,
            PropKey.INPUT_FORMAT_SUFFIX,
            PropKey.INPUT_FORMAT_DECIMAL_DIGITS,
            PropKey.INPUT_FORMAT_LOCALE,
            -> (view as? PamEditText)?.let {
                applyInputConfiguration(it, state)
                applyInputValue(it, state, it.text.toString())
            }
            PropKey.CHECKED -> if (view is Switch && view.isChecked != value.flag()) {
                state.updating = true
                view.isChecked = value.flag()
                state.updating = false
            }
            PropKey.LOADING -> {
                val loading = value.flag()
                if (loading && view is Button) {
                    view.post {
                        if (
                            nodes[state.id] === state
                            && state.flag(PropKey.LOADING, false)
                        ) {
                            applyLoading(view, state, true)
                        }
                    }
                } else {
                    applyLoading(view, state, false)
                }
            }
            PropKey.PROGRESS_COLOR -> {
                val color = value.integer().toInt()
                (view as? PamActivityIndicator)?.setColor(color)
                if (view is Button && state.flag(PropKey.LOADING, false)) {
                    state.loadingDrawable?.setColor(color)
                }
            }
            PropKey.IMAGE_FIT -> {
                imageView(view)?.scaleType =
                    resolvedImageScaleType(value.integer().toInt())
                (view as? PamMediaView)?.setResizeMode(value.integer().toInt())
                loadImage(view, state)
            }
            PropKey.TINT_COLOR -> imageView(view)?.imageTintList =
                ColorStateList.valueOf(value.integer().toInt())
            PropKey.IMAGE_DEFAULT_SOURCE,
            PropKey.IMAGE_LOADING_INDICATOR_SOURCE,
            PropKey.IMAGE_FADE_DURATION_MS,
            PropKey.IMAGE_RESIZE_METHOD,
            PropKey.IMAGE_RESIZE_MULTIPLIER,
            PropKey.IMAGE_PROGRESSIVE_RENDERING_ENABLED,
            PropKey.IMAGE_CACHE_POLICY,
            PropKey.IMAGE_SOURCE_SET,
            PropKey.IMAGE_REQUEST_HEADERS,
            -> loadImage(view, state)
            PropKey.IMAGE_OVERLAY_COLOR -> updateBackground(view, state)
            PropKey.ELEVATION -> view.elevation = dp(value.decimal().toFloat()).toFloat()
            PropKey.VISIBLE -> {
                val visible = value.flag()
                when (view) {
                    is PamModalHost -> view.setVisible(visible)
                    is PamActivityIndicator -> view.setRequestedVisible(visible)
                    else -> view.visibility = if (visible) View.VISIBLE else View.GONE
                }
                if (visible) requestAutoFocusDescendant(state.id)
            }
            PropKey.MODAL_PRESENTATION -> (view as? PamModalHost)?.setPresentation(
                value.integer().toInt(),
            )
            PropKey.MODAL_ANIMATION_TYPE ->
                (view as? PamModalHost)?.setAnimationType(value.integer().toInt())
            PropKey.MODAL_BACKDROP_COLOR ->
                (view as? PamModalHost)?.setBackdropColor(value.integer().toInt())
            PropKey.MODAL_TRANSPARENT ->
                (view as? PamModalHost)?.setTransparent(value.flag())
            PropKey.MODAL_HARDWARE_ACCELERATED ->
                (view as? PamModalHost)?.setHardwareAccelerated(value.flag())
            PropKey.MODAL_NAVIGATION_BAR_TRANSLUCENT ->
                (view as? PamModalHost)?.setNavigationBarTranslucent(value.flag())
            PropKey.MODAL_STATUS_BAR_TRANSLUCENT ->
                (view as? PamModalHost)?.setStatusBarTranslucent(value.flag())
            PropKey.MODAL_ALLOW_SWIPE_DISMISSAL ->
                (view as? PamModalHost)?.setAllowSwipeDismissal(value.flag())
            PropKey.BOTTOM_SHEET_SNAP_POINTS ->
                (view as? PamModalHost)?.setBottomSheetSnapPoints(
                    decodeBottomSheetSnapPoints(value),
                )
            PropKey.BOTTOM_SHEET_INDEX ->
                (view as? PamModalHost)?.setBottomSheetIndex(value.integer().toInt())
            PropKey.BOTTOM_SHEET_DISMISSIBLE ->
                (view as? PamModalHost)?.setBottomSheetDismissible(value.flag())
            PropKey.BOTTOM_SHEET_BACKDROP_DISMISS ->
                (view as? PamModalHost)?.setBottomSheetBackdropDismiss(value.flag())
            PropKey.BOTTOM_SHEET_HANDLE_VISIBLE ->
                (view as? PamModalHost)?.setBottomSheetHandleVisible(value.flag())
            PropKey.BOTTOM_SHEET_DRAG_ENABLED ->
                (view as? PamModalHost)?.setBottomSheetDragEnabled(value.flag())
            PropKey.BOTTOM_SHEET_KEYBOARD_BEHAVIOR ->
                (view as? PamModalHost)?.setBottomSheetKeyboardBehavior(
                    value.integer().toInt(),
                )
            PropKey.BOTTOM_SHEET_CORNER_RADIUS ->
                (view as? PamModalHost)?.setBottomSheetCornerRadius(
                    value.decimal().toFloat(),
                )
            PropKey.WEB_VIEW_SOURCE -> (view as? PamWebView)?.setSource(value.text(key))
            PropKey.WEB_VIEW_JAVA_SCRIPT_ENABLED ->
                (view as? PamWebView)?.setJavaScriptEnabled(value.flag())
            PropKey.WEB_VIEW_DOM_STORAGE_ENABLED ->
                (view as? PamWebView)?.setDomStorageEnabled(value.flag())
            PropKey.WEB_VIEW_USER_AGENT ->
                (view as? PamWebView)?.setUserAgent(value.text(key))
            PropKey.WEB_VIEW_INJECTED_JAVA_SCRIPT ->
                (view as? PamWebView)?.setInjectedJavaScript(value.text(key))
            PropKey.WEB_VIEW_ALLOWS_INLINE_MEDIA ->
                (view as? PamWebView)?.setAllowsInlineMedia(value.flag())
            PropKey.WEB_VIEW_ALLOWED_HOSTS ->
                (view as? PamWebView)?.setAllowedHosts(value.text(key))
            PropKey.MEDIA_SOURCE -> (view as? PamMediaView)?.let {
                configureMediaCache(it, state)
                it.setSource(value.text(key))
            }
            PropKey.MEDIA_TYPE -> Unit
            PropKey.MEDIA_AUTO_PLAY -> (view as? PamMediaView)?.setAutoPlay(value.flag())
            PropKey.MEDIA_CONTROLS -> (view as? PamMediaView)?.setControls(value.flag())
            PropKey.MEDIA_LOOP -> (view as? PamMediaView)?.setLoop(value.flag())
            PropKey.MEDIA_MUTED -> (view as? PamMediaView)?.setMuted(value.flag())
            PropKey.MEDIA_VOLUME ->
                (view as? PamMediaView)?.setVolume(value.decimal().toFloat())
            PropKey.MEDIA_CURRENT_TIME ->
                (view as? PamMediaView)?.seek(value.decimal())
            PropKey.MEDIA_PLAYBACK_RATE ->
                (view as? PamMediaView)?.setPlaybackRate(value.decimal().toFloat())
            PropKey.MEDIA_CACHE_POLICY,
            PropKey.MEDIA_CACHE_KEY,
            PropKey.MEDIA_CACHE_MAX_AGE_MS,
            PropKey.MEDIA_CACHE_TAGS,
            PropKey.MEDIA_CACHE_PIN_OFFLINE,
            PropKey.MEDIA_CACHE_STREAMING,
            PropKey.MEDIA_CACHE_PRELOAD_SECONDS,
            PropKey.MEDIA_CACHE_DOWNLOAD_WHILE_PLAYING,
            PropKey.MEDIA_CACHE_MAX_BYTES,
            PropKey.MEDIA_THUMBNAIL_SOURCE,
            PropKey.MEDIA_RESIZE_WIDTH,
            PropKey.MEDIA_RESIZE_HEIGHT,
            PropKey.MEDIA_PRIORITY,
            PropKey.MEDIA_CACHE_CHECKSUM,
            -> if (view is PamMediaView) {
                configureMediaCache(view, state)
                configureMediaThumbnail(view, state)
            } else if (pamImageView(view) != null) {
                loadImage(view, state)
            }
            PropKey.ON_MEDIA_CACHE_HIT,
            PropKey.ON_MEDIA_CACHE_MISS,
            PropKey.ON_MEDIA_CACHE_PROGRESS,
            PropKey.ON_MEDIA_CACHE_READY,
            -> installEvents(view, state)
            PropKey.DRAGGABLE,
            PropKey.DRAG_DATA,
            PropKey.DROP_ENABLED,
            PropKey.CONTEXT_MENU_ITEMS,
            -> configureNativeInteractions(view, state)
            PropKey.PRESS_DOUBLE_TAP_DELAY_MS,
            PropKey.PRESS_TAP_EFFECT,
            PropKey.GESTURE_DRAG,
            -> configurePressable(view, state)
            PropKey.GESTURE_DRAG_SNAP_INDEX -> applyDragSnap(view, state)
            PropKey.NATIVE_REF -> view.setTag(dev.pam.nativeapp.R.id.pam_native_ref, value.text(key))
            PropKey.ANIMATION_PROGRAM -> configureMotionProgram(view, state)
            PropKey.ANIMATION_RESTART_KEY -> restartAnimations(view, state)
            PropKey.TRANSITION_SPEC -> state.transitionRules = PamTransitionSpec.parse(value.text(key))
            PropKey.ON_DOUBLE_TAP,
            PropKey.ON_GESTURE_SETTLE,
            PropKey.ON_SCROLL_BEGIN_DRAG,
            PropKey.ON_SCROLL_END_DRAG,
            PropKey.ON_MOMENTUM_SCROLL_END,
            PropKey.ON_TEXT_LAYOUT,
            -> Unit
            PropKey.ANIMATION_KEYFRAMES,
            PropKey.ANIMATION_ITERATIONS,
            PropKey.ANIMATION_DELAY_MS,
            PropKey.ANIMATION_FILL_MODE,
            PropKey.ANIMATION_PLAY_STATE,
            PropKey.ANIMATION_AUTO_REVERSE,
            -> configureKeyframeAnimation(view, state)
            PropKey.WORKLET_PROGRAM,
            PropKey.WORKLET_TARGET,
            PropKey.WORKLET_DURATION_MS,
            PropKey.WORKLET_ITERATIONS,
            -> configureWorkletAnimation(view, state)
            PropKey.STATUS_BAR_COLOR,
            PropKey.STATUS_BAR_STYLE,
            PropKey.STATUS_BAR_HIDDEN,
            PropKey.STATUS_BAR_ANIMATED,
            PropKey.STATUS_BAR_TRANSLUCENT,
            PropKey.NAVIGATION_BAR_HIDDEN,
            -> applyMergedStatusBar()
            PropKey.KEYBOARD_BEHAVIOR -> {
                state.keyboardBehavior = value.integer().toInt()
                applyKeyboardAvoidance(view, state)
            }
            PropKey.KEYBOARD_VERTICAL_OFFSET -> if (view is PamScrollContainer) {
                configureScrollKeyboardInset(view, state, state.flag(PropKey.SCROLL_KEYBOARD_INSET, false))
            } else {
                applyKeyboardAvoidance(view, state)
            }
            PropKey.KEYBOARD_AVOIDING_ENABLED,
            -> {
                configureLegacyKeyboardInsets(view, state)
                applyKeyboardAvoidance(view, state)
            }
            PropKey.SAFE_AREA_TOP,
            PropKey.SAFE_AREA_RIGHT,
            PropKey.SAFE_AREA_BOTTOM_EDGE,
            PropKey.SAFE_AREA_LEFT,
            PropKey.SAFE_AREA_MODE,
            -> applySafeAreaLayout(view, state)
            PropKey.REFRESHING -> (view as? PamRefreshContainer)?.setRefreshing(value.flag())
            PropKey.REFRESH_COLORS -> (view as? PamRefreshContainer)?.setColors(value.text(key))
            PropKey.REFRESH_PROGRESS_BACKGROUND_COLOR ->
                (view as? PamRefreshContainer)?.setProgressBackgroundColor(
                    value.integer().toInt(),
                )
            PropKey.REFRESH_PROGRESS_VIEW_OFFSET ->
                (view as? PamRefreshContainer)?.setProgressViewOffset(
                    value.decimal().toFloat(),
                )
            PropKey.REFRESH_INDICATOR_SIZE ->
                (view as? PamRefreshContainer)?.setIndicatorSize(
                    value.integer().toInt(),
                )
            PropKey.SCROLL_ENABLED -> when (view) {
                is PamScrollContainer -> view.setScrollEnabled(value.flag())
                is PamRecyclerList -> view.setScrollEnabled(value.flag())
            }
            PropKey.SHOWS_SCROLL_INDICATOR -> when (view) {
                is PamScrollContainer -> view.setShowsScrollIndicator(value.flag())
                is PamRecyclerList -> view.setShowsScrollIndicator(value.flag())
            }
            PropKey.SCROLL_HORIZONTAL ->
                (view as? PamScrollContainer)?.setHorizontal(value.flag())
            PropKey.SCROLL_CONTENT_OFFSET_X ->
                (view as? PamScrollContainer)?.setContentOffsetX(
                    value.decimal().toFloat(),
                )
            PropKey.SCROLL_CONTENT_OFFSET_Y ->
                (view as? PamScrollContainer)?.setContentOffsetY(
                    value.decimal().toFloat(),
                )
            PropKey.SCROLL_ANCHOR_TO_END ->
                (view as? PamScrollContainer)?.setAnchorToEnd(value.flag())
            PropKey.SCROLL_MAINTAIN_VISIBLE_CONTENT_POSITION ->
                (view as? PamScrollContainer)?.setMaintainVisibleContentPosition(
                    value.flag(),
                )
            PropKey.SCROLL_AUTO_SCROLL_TO_END_THRESHOLD ->
                (view as? PamScrollContainer)?.setAutoScrollToEndThreshold(
                    value.decimal().toFloat(),
                )
            PropKey.SCROLL_TARGET_TEST_ID ->
                (view as? PamScrollContainer)?.setScrollTargetTestId(
                    value.text(key),
                )
            PropKey.SCROLL_TARGET_OFFSET ->
                (view as? PamScrollContainer)?.setScrollTargetOffset(
                    value.decimal().toFloat(),
                )
            PropKey.SCROLL_TARGET_ALIGNMENT ->
                (view as? PamScrollContainer)?.setScrollTargetAlignment(
                    value.integer().toInt(),
                )
            PropKey.DRAWING_COLOR ->
                (view as? PamDrawingCanvas)?.setBrushColor(value.integer().toInt())
            PropKey.DRAWING_WIDTH ->
                (view as? PamDrawingCanvas)?.setBrushWidth(value.decimal().toFloat())
            PropKey.DRAWING_MODE ->
                (view as? PamDrawingCanvas)?.setDrawingMode(value.integer().toInt())
            PropKey.DRAWING_CLEAR_REQUEST ->
                (view as? PamDrawingCanvas)?.setClearRequest(value.integer().toInt())
            PropKey.DRAWING_UNDO_REQUEST ->
                (view as? PamDrawingCanvas)?.setUndoRequest(value.integer().toInt())
            PropKey.SCROLL_REQUEST ->
                when (view) {
                    is PamScrollContainer -> view.requestScroll()
                    is PamRecyclerList -> requestVirtualListScroll(view, state)
                }
            PropKey.SCROLL_FILL_VIEWPORT ->
                (view as? PamScrollContainer)?.setFillViewport(value.flag())
            PropKey.SCROLL_OVER_SCROLL_MODE ->
                (view as? PamScrollContainer)?.setOverScrollModeValue(
                    value.integer().toInt(),
                )
            PropKey.SCROLL_NESTED_ENABLED ->
                (view as? PamScrollContainer)?.setNestedScrollEnabled(value.flag())
            PropKey.SCROLL_FADING_EDGE_LENGTH ->
                (view as? PamScrollContainer)?.setFadingEdgeLength(
                    value.decimal().toFloat(),
                )
            PropKey.SCROLL_PERSISTENT_SCROLLBAR ->
                (view as? PamScrollContainer)?.setPersistentScrollbar(value.flag())
            PropKey.SCROLL_INDICATOR_STYLE ->
                (view as? PamScrollContainer)?.setIndicatorStyle(value.integer().toInt())
            PropKey.SCROLL_PAGING_ENABLED -> when (view) {
                is PamScrollContainer -> view.setPagingEnabled(value.flag())
                is PamRecyclerList -> view.setPagingEnabled(value.flag())
            }
            PropKey.SCROLL_SNAP_INTERVAL ->
                (view as? PamScrollContainer)?.setSnapInterval(
                    value.decimal().toFloat(),
                )
            PropKey.SCROLL_DECELERATION_RATE ->
                (view as? PamScrollContainer)?.setDecelerationRate(
                    value.decimal().toFloat(),
                )
            PropKey.SCROLL_KEYBOARD_DISMISS_MODE ->
                (view as? PamScrollContainer)?.setKeyboardDismissMode(
                    value.integer().toInt(),
                )
            PropKey.ACTIVITY_ANIMATING ->
                (view as? PamActivityIndicator)?.setAnimating(value.flag())
            PropKey.ACTIVITY_HIDES_WHEN_STOPPED ->
                (view as? PamActivityIndicator)?.setHidesWhenStopped(value.flag())
            PropKey.ACTIVITY_SIZE ->
                (view as? PamActivityIndicator)?.setSize(value.decimal().toFloat())
            PropKey.SWITCH_TRACK_COLOR_FALSE ->
                (view as? PamSwitch)?.setTrackOffColor(value.integer().toInt())
            PropKey.SWITCH_TRACK_COLOR_TRUE ->
                (view as? PamSwitch)?.setTrackOnColor(value.integer().toInt())
            PropKey.SWITCH_THUMB_COLOR ->
                (view as? PamSwitch)?.setThumbColor(value.integer().toInt())
            PropKey.LIST_ROW_HEIGHT ->
                (view as? PamRecyclerList)?.setRowHeight(value.decimal().toFloat())
            PropKey.LIST_PREFETCH ->
                (view as? PamRecyclerList)?.setPrefetchItems(value.integer().toInt())
            PropKey.LIST_HORIZONTAL ->
                (view as? PamRecyclerList)?.setHorizontal(value.flag())
            PropKey.LIST_NUM_COLUMNS ->
                (view as? PamRecyclerList)?.setColumns(value.integer().toInt())
            PropKey.LIST_INVERTED ->
                (view as? PamRecyclerList)?.setInverted(value.flag())
            PropKey.LIST_INITIAL_SCROLL_INDEX ->
                (view as? PamRecyclerList)?.setInitialIndex(value.integer().toInt())
            PropKey.LIST_REMOVE_CLIPPED_SUBVIEWS ->
                (view as? PamRecyclerList)?.setRemoveClippedSubviews(value.flag())
            PropKey.SELECTED -> view.isSelected = value.flag()
            PropKey.PRESS_OPACITY -> {
                state.pressOpacity = value.decimal().toFloat()
                configurePressable(view, state)
            }
            PropKey.PRESS_SCALE -> {
                state.pressScale = value.decimal().toFloat()
                configurePressable(view, state)
            }
            PropKey.ACCESSIBILITY_ROLE -> {
                (view as? PamActivityIndicator)?.setHostAccessibility(true)
                configureAccessibilityDelegate(view, state)
            }
            PropKey.ACCESSIBLE -> view.isFocusable = value.flag()
            PropKey.ACCESSIBILITY_LIVE_REGION -> view.accessibilityLiveRegion =
                when (value.integer().toInt()) {
                    2 -> View.ACCESSIBILITY_LIVE_REGION_POLITE
                    3 -> View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
                    else -> View.ACCESSIBILITY_LIVE_REGION_NONE
                }
            PropKey.ACCESSIBILITY_IMPORTANCE -> view.importantForAccessibility =
                when (value.integer().toInt()) {
                    2 -> View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    3 -> View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    4 -> View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    else -> View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                }
            PropKey.ACCESSIBILITY_EXPANDED,
            PropKey.ACCESSIBILITY_BUSY,
            PropKey.ACCESSIBILITY_CHECKED_STATE,
            PropKey.ACCESSIBILITY_VALUE_MIN,
            PropKey.ACCESSIBILITY_VALUE_MAX,
            PropKey.ACCESSIBILITY_VALUE_NOW,
            PropKey.ACCESSIBILITY_VALUE_TEXT,
            -> notifyAccessibilityChanged(view)
            PropKey.ACCESSIBILITY_ACTIONS,
            PropKey.ON_ACCESSIBILITY_ACTION,
            -> configureAccessibilityDelegate(view, state)
            PropKey.TRANSLATION_X,
            PropKey.TRANSLATION_Y,
            PropKey.SCALE_X,
            PropKey.SCALE_Y,
            PropKey.ROTATION,
            -> animateOrSet(view, state, key, value.decimal().toFloat())
            PropKey.DRAWER_OPEN -> (view as? PamDrawerLayout)?.setOpen(value.flag())
            PropKey.DRAWER_TYPE -> (view as? PamDrawerLayout)?.setDrawerType(value.integer().toInt())
            PropKey.DRAWER_POSITION -> (view as? PamDrawerLayout)?.setDrawerPosition(value.integer().toInt())
            PropKey.DRAWER_WIDTH -> (view as? PamDrawerLayout)?.setDrawerWidth(value.decimal().toFloat())
            PropKey.DRAWER_OVERLAY_COLOR -> (view as? PamDrawerLayout)?.setOverlayColor(value.integer().toInt())
            PropKey.DRAWER_SWIPE_ENABLED -> (view as? PamDrawerLayout)?.setSwipeEnabled(value.flag())
            PropKey.DRAWER_SWIPE_EDGE_WIDTH -> (view as? PamDrawerLayout)?.setSwipeEdgeWidth(value.decimal().toFloat())
            PropKey.DRAWER_SWIPE_MIN_DISTANCE -> (view as? PamDrawerLayout)?.setSwipeMinDistance(value.decimal().toFloat())
            PropKey.DRAWER_KEYBOARD_DISMISS_MODE -> (view as? PamDrawerLayout)?.setKeyboardDismissMode(value.integer().toInt())
            PropKey.DRAWER_HIDE_STATUS_BAR_ON_OPEN -> (view as? PamDrawerLayout)?.setHideStatusBarOnOpen(value.flag())
            PropKey.DRAWER_STATUS_BAR_ANIMATION -> (view as? PamDrawerLayout)?.setStatusBarAnimation(value.integer().toInt())
            PropKey.DRAWER_PERMANENT_BREAKPOINT -> (view as? PamDrawerLayout)?.setPermanentBreakpoint(value.decimal().toFloat())
            PropKey.LAYOUT_DIRECTION -> view.layoutDirection =
                if (value.integer().toInt() == 2) {
                    View.LAYOUT_DIRECTION_RTL
                } else {
                    View.LAYOUT_DIRECTION_LTR
                }
            PropKey.LETTER_SPACING -> (view as? TextView)?.let {
                applyLetterSpacing(it, state)
            }
            PropKey.LINE_HEIGHT -> (view as? TextView)?.let {
                applyLineHeight(it, state)
            }
            PropKey.PLACEHOLDER_COLOR -> (view as? EditText)?.setHintTextColor(value.integer().toInt())
            PropKey.SELECTION_COLOR -> (view as? TextView)?.highlightColor =
                value.integer().toInt()
            PropKey.TEXT_SELECTABLE -> (view as? TextView)?.let { text ->
                text.setTextIsSelectable(value.flag())
                applyTextDataDetector(text, state)
            }
            PropKey.TEXT_ELLIPSIZE_MODE -> (view as? TextView)?.let { text ->
                applyTextEllipsize(text, value.integer().toInt())
            }
            PropKey.TEXT_ALLOW_FONT_SCALING,
            PropKey.TEXT_MAX_FONT_SIZE_MULTIPLIER,
            PropKey.TEXT_ADJUSTS_FONT_SIZE_TO_FIT,
            PropKey.TEXT_MINIMUM_FONT_SCALE,
            -> (view as? TextView)?.let {
                applyTextSizing(it, state)
                applyLetterSpacing(it, state)
                applyLineHeight(it, state)
            }
            PropKey.TEXT_BREAK_STRATEGY -> (view as? TextView)?.let {
                applyTextBreakStrategy(it, value.integer().toInt())
            }
            PropKey.TEXT_HYPHENATION_FREQUENCY ->
                (view as? TextView)?.hyphenationFrequency =
                    when (value.integer().toInt()) {
                        TEXT_HYPHENATION_NORMAL -> Layout.HYPHENATION_FREQUENCY_NORMAL
                        TEXT_HYPHENATION_FULL -> Layout.HYPHENATION_FREQUENCY_FULL
                        else -> Layout.HYPHENATION_FREQUENCY_NONE
                    }
            PropKey.TEXT_DATA_DETECTOR_TYPE ->
                (view as? TextView)?.let { applyTextDataDetector(it, state) }
            PropKey.MAX_LENGTH -> (view as? EditText)?.filters = arrayOf(
                InputFilter.LengthFilter(value.integer().toInt()),
            )
            PropKey.AUTO_FOCUS -> if (value.flag()) {
                requestAutoFocus(view, state.id)
            } else {
                Unit
            }
            PropKey.RETURN_KEY_TYPE -> (view as? PamEditText)?.let {
                applyInputConfiguration(it, state)
            }
            PropKey.Z_INDEX -> view.z = value.decimal().toFloat()
            PropKey.OVERFLOW -> applyOverflowClip(view, state)
            PropKey.HOST_PROPERTIES -> {
                val properties = (value as? PropValue.Properties)?.value
                    ?: error("Expected native view property map")
                nativeViews.update(view, properties)
            }
            PropKey.PADDING,
            PropKey.PADDING_HORIZONTAL,
            PropKey.PADDING_VERTICAL,
            PropKey.PADDING_LEFT,
            PropKey.PADDING_TOP,
            PropKey.PADDING_RIGHT,
            PropKey.PADDING_BOTTOM,
            -> applyLeafPadding(view, state)
            PropKey.POINTER_EVENTS -> applyPointerEvents(view, state, value.integer().toInt())
            PropKey.SAFE_AREA_BOTTOM -> applySafeAreaBottom(view, state, value.flag())
            PropKey.BLUR_RADIUS -> applyFilter(view, state)
            PropKey.TRANSLATION_X_PERCENT -> {
                view.translationX = view.width * (value.decimal() / 100.0).toFloat()
            }
            PropKey.TRANSLATION_Y_PERCENT -> {
                view.translationY = view.height * (value.decimal() / 100.0).toFloat()
            }
            PropKey.TRANSFORM_ORIGIN_X,
            PropKey.TRANSFORM_ORIGIN_Y,
            -> applyTransformOrigin(view, state)
            PropKey.TEXT_SHADOW_OFFSET_X,
            PropKey.TEXT_SHADOW_OFFSET_Y,
            PropKey.TEXT_SHADOW_RADIUS,
            PropKey.TEXT_SHADOW_COLOR,
            -> (view as? TextView)?.let { applyTextShadow(it, state) }
            PropKey.FONT_FEATURE_SETTINGS ->
                (view as? TextView)?.fontFeatureSettings = value.text(key)
            PropKey.FLEX_BASIS,
            PropKey.FLEX_BASIS_PERCENT,
            PropKey.FLEX_BASIS_CONTENT,
            PropKey.ALIGN_CONTENT,
            PropKey.MARGIN_TOP_AUTO,
            PropKey.MARGIN_RIGHT_AUTO,
            PropKey.MARGIN_BOTTOM_AUTO,
            PropKey.MIN_WIDTH_PERCENT,
            PropKey.MIN_HEIGHT_PERCENT,
            PropKey.LIST_FULL_SPAN,
            PropKey.LIST_SECTION,
            PropKey.LIST_ACTIVE_SECTION,
            -> Unit
            PropKey.ANIMATION_KIND -> applyAnimationKind(view, state, value.integer().toInt())
            PropKey.ANIMATION_DURATION_MS -> {
                if (state.integer(PropKey.ANIMATION_KIND, 1L) == 2L) {
                    applyAnimationKind(view, state, 2)
                }
            }
            PropKey.HIT_SLOP,
            PropKey.HIT_SLOP_LEFT,
            PropKey.HIT_SLOP_TOP,
            PropKey.HIT_SLOP_RIGHT,
            PropKey.HIT_SLOP_BOTTOM,
            -> applyHitSlop(view, state)
            PropKey.PRESS_RETENTION_LEFT,
            PropKey.PRESS_RETENTION_TOP,
            PropKey.PRESS_RETENTION_RIGHT,
            PropKey.PRESS_RETENTION_BOTTOM,
            PropKey.PRESS_DELAY_LONG_MS,
            PropKey.PRESS_DELAY_IN_MS,
            PropKey.PRESS_DELAY_OUT_MS,
            PropKey.PRESS_ANDROID_DISABLE_SOUND,
            PropKey.GESTURE_TYPE,
            PropKey.GESTURE_ENABLED,
            PropKey.GESTURE_MIN_POINTERS,
            PropKey.GESTURE_MAX_POINTERS,
            PropKey.GESTURE_DIRECTION,
            PropKey.GESTURE_COMPOSITION,
            PropKey.GESTURE_MIN_DISTANCE,
            PropKey.GESTURE_MIN_DURATION_MS,
            PropKey.GESTURE_NATIVE_TRANSFORM,
            PropKey.GESTURE_NATIVE_MIN_SCALE,
            PropKey.GESTURE_NATIVE_MAX_SCALE,
            PropKey.GESTURE_NATIVE_RESET_KEY,
            PropKey.GESTURE_NATIVE_TRANSLATION_LIMIT_X,
            PropKey.GESTURE_NATIVE_RESET_ON_END,
            -> configurePressable(view, state)
            PropKey.MARGIN,
            PropKey.MARGIN_HORIZONTAL,
            PropKey.MARGIN_VERTICAL,
            PropKey.MARGIN_LEFT,
            PropKey.MARGIN_TOP,
            PropKey.MARGIN_RIGHT,
            PropKey.MARGIN_BOTTOM,
            -> if (state.flag(PropKey.STICKY_HEADER, false)) applyStickyHeader(view, true, state)
            PropKey.WIDTH,
            PropKey.HEIGHT,
            PropKey.FLEX_GROW,
            PropKey.FLEX_SHRINK,
            PropKey.GAP,
            PropKey.MIN_WIDTH,
            PropKey.MIN_HEIGHT,
            PropKey.MAX_WIDTH,
            PropKey.MAX_HEIGHT,
            PropKey.ALIGN_ITEMS,
            PropKey.ALIGN_SELF,
            PropKey.JUSTIFY_CONTENT,
            PropKey.ON_PRESS,
            PropKey.ON_CHANGE,
            PropKey.ON_LONG_PRESS,
            PropKey.ON_FOCUS,
            PropKey.ON_BLUR,
            PropKey.ON_SUBMIT,
            PropKey.ON_SCROLL,
            PropKey.ON_REFRESH,
            PropKey.ON_TOGGLE,
            PropKey.INPUT_DEBOUNCE_MS,
            PropKey.INPUT_SYNC_MODE,
            PropKey.COLLAPSABLE,
            PropKey.ANIMATION_EASING,
            PropKey.ANIMATE_CHANGES,
            PropKey.ON_END_REACHED,
            PropKey.END_REACHED_THRESHOLD,
            PropKey.DRAWER_POSITION,
            PropKey.ON_DRAWER_OPEN,
            PropKey.ON_DRAWER_CLOSE,
            PropKey.HOST_NAME,
            PropKey.ON_NATIVE_EVENT,
            PropKey.ON_IMAGE_LOAD_START,
            PropKey.ON_IMAGE_PROGRESS,
            PropKey.ON_IMAGE_LOAD,
            PropKey.ON_IMAGE_ERROR,
            PropKey.ON_IMAGE_LOAD_END,
            PropKey.ON_INPUT_END_EDITING,
            PropKey.ON_INPUT_SELECTION_CHANGE,
            PropKey.ON_INPUT_CONTENT_SIZE_CHANGE,
            PropKey.ON_INPUT_KEY_PRESS,
            PropKey.ON_PRESS_IN,
            PropKey.ON_PRESS_OUT,
            PropKey.ON_PRESS_MOVE,
            PropKey.ON_MODAL_REQUEST_CLOSE,
            PropKey.ON_MODAL_SHOW,
            PropKey.ON_MODAL_DISMISS,
            PropKey.ON_MODAL_ORIENTATION_CHANGE,
            PropKey.ON_CLICK_OUTSIDE,
            PropKey.ON_INTERSECT,
            PropKey.ON_MUTATE,
            PropKey.ON_RESIZE,
            PropKey.ON_TOUCH_START,
            PropKey.ON_TOUCH_MOVE,
            PropKey.ON_TOUCH_END,
            PropKey.ON_GESTURE_BEGIN,
            PropKey.ON_GESTURE_UPDATE,
            PropKey.ON_GESTURE_END,
            PropKey.ON_GESTURE_CANCEL,
            PropKey.ON_BOTTOM_SHEET_CHANGE,
            PropKey.ON_BOTTOM_SHEET_DISMISS,
            PropKey.ON_WEB_VIEW_LOAD,
            PropKey.ON_WEB_VIEW_ERROR,
            PropKey.ON_WEB_VIEW_MESSAGE,
            PropKey.ON_MEDIA_READY,
            PropKey.ON_MEDIA_PROGRESS,
            PropKey.ON_MEDIA_END,
            PropKey.ON_MEDIA_ERROR,
            PropKey.ON_MEDIA_BUFFERING,
            PropKey.ON_MEDIA_LOAD_START,
            PropKey.ON_DRAG_START,
            PropKey.ON_DRAG_END,
            PropKey.ON_DROP,
            PropKey.ON_MENU_ACTION,
            PropKey.ON_NAVIGATION_GESTURE_POP,
            PropKey.ON_ANIMATION_COMPLETE,
            PropKey.FLEX_DIRECTION,
            PropKey.FLEX_WRAP,
            PropKey.POSITION_TYPE,
            PropKey.LEFT,
            PropKey.TOP,
            PropKey.RIGHT,
            PropKey.BOTTOM,
            PropKey.LEFT_PERCENT,
            PropKey.TOP_PERCENT,
            PropKey.RIGHT_PERCENT,
            PropKey.BOTTOM_PERCENT,
            PropKey.ASPECT_RATIO,
            PropKey.WIDTH_PERCENT,
            PropKey.HEIGHT_PERCENT,
            PropKey.MAX_WIDTH_PERCENT,
            PropKey.MAX_HEIGHT_PERCENT,
            PropKey.MARGIN_LEFT_AUTO,
            PropKey.GRID_COLUMNS,
            PropKey.GRID_MIN_COLUMN_WIDTH,
            PropKey.GRID_TEMPLATE,
            PropKey.GRID_SPAN2XL,
            PropKey.GRID_OFFSET2XL,
            PropKey.GRID_ORDER2XL,
            PropKey.GRID_SPAN,
            PropKey.GRID_SPAN_SM,
            PropKey.GRID_SPAN_MD,
            PropKey.GRID_SPAN_LG,
            PropKey.GRID_SPAN_XL,
            PropKey.GRID_OFFSET,
            PropKey.GRID_OFFSET_SM,
            PropKey.GRID_OFFSET_MD,
            PropKey.GRID_OFFSET_LG,
            PropKey.GRID_OFFSET_XL,
            PropKey.GRID_ORDER,
            PropKey.GRID_ORDER_SM,
            PropKey.GRID_ORDER_MD,
            PropKey.GRID_ORDER_LG,
            PropKey.GRID_ORDER_XL,
            PropKey.GRID_COLUMN_GAP,
            PropKey.GRID_ROW_GAP,
            PropKey.NAVIGATION_OPERATION,
            PropKey.NAVIGATION_TRANSITION,
            PropKey.NAVIGATION_DURATION_MS,
            PropKey.NAVIGATION_REVISION,
            -> Unit
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun resetProperty(view: View, state: NodeState, key: PropKey) {
        when (key) {
            PropKey.LAYOUT_DIRECTION -> view.layoutDirection = View.LAYOUT_DIRECTION_INHERIT
            PropKey.TEXT -> (view as? TextView)?.let { text ->
                state.baseText = ""
                if (isRichTextView(text, state)) applyTextContent(text, state) else text.text = ""
            }
            PropKey.VALUE -> when (view) {
                is EditText -> view.setText("")
                is PamDrawingCanvas -> view.setDrawing("")
                else -> view.tag = null
            }
            PropKey.PLACEHOLDER -> (view as? EditText)?.hint = null
            PropKey.SOURCE -> pamImageView(view)?.let(imageLoader::cancel)
            PropKey.BACKGROUND_COLOR,
            PropKey.NATIVE_BACKGROUND_COLOR_RESOURCE,
            PropKey.BORDER_RADIUS,
            PropKey.BORDER_WIDTH,
            PropKey.BORDER_COLOR,
            PropKey.NATIVE_BORDER_COLOR_RESOURCE,
            PropKey.BORDER_STYLE,
            PropKey.BORDER_TOP_LEFT_RADIUS,
            PropKey.BORDER_TOP_RIGHT_RADIUS,
            PropKey.BORDER_BOTTOM_RIGHT_RADIUS,
            PropKey.BORDER_BOTTOM_LEFT_RADIUS,
            PropKey.BORDER_LEFT_WIDTH,
            PropKey.BORDER_TOP_WIDTH,
            PropKey.BORDER_RIGHT_WIDTH,
            PropKey.BORDER_BOTTOM_WIDTH,
            PropKey.BORDER_TOP_COLOR,
            PropKey.BORDER_RIGHT_COLOR,
            PropKey.BORDER_BOTTOM_COLOR,
            PropKey.BORDER_LEFT_COLOR,
            PropKey.RIPPLE_COLOR,
            PropKey.RIPPLE_BORDERLESS,
            PropKey.RIPPLE_RADIUS,
            PropKey.RIPPLE_FOREGROUND,
            PropKey.RIPPLE_ALPHA,
            PropKey.BACKGROUND_GRADIENT,
            PropKey.BORDER_GRADIENT,
            PropKey.BOX_SHADOWS,
            -> {
                updateBackground(view, state)
                if (key in BORDER_WIDTH_KEYS) applyLeafPadding(view, state)
            }
            PropKey.FILTER_COLOR_MATRIX -> applyFilter(view, state)
            PropKey.BACKDROP_BLUR_RADIUS,
            PropKey.BACKDROP_COLOR_MATRIX,
            -> applyBackdrop(view, state)
            PropKey.SHIMMER_GRADIENT_COLOR,
            PropKey.SHIMMER_DURATION_MS,
            PropKey.SHIMMER_ENABLED,
            -> (view as? PamContainer)?.setShimmer(
                state.properties[PropKey.SHIMMER_ENABLED]?.let { (it as? PropValue.Flag)?.value } ?: false,
                state.integer(PropKey.SHIMMER_GRADIENT_COLOR, 0x59FFFFFF).toInt(),
                state.integer(PropKey.SHIMMER_DURATION_MS, 1200L),
            )
            PropKey.TEXT_COLOR -> when (view) {
                is TextView -> view.setTextColor(Color.BLACK)
                is PamRecyclerList -> view.setTextColor(Color.BLACK)
            }
            PropKey.NATIVE_TEXT_COLOR_RESOURCE -> when (view) {
                is TextView -> view.setTextColor(Color.BLACK)
                is PamRecyclerList -> view.setTextColor(Color.BLACK)
            }
            PropKey.NATIVE_STATE_STYLES -> configureNativeStateStyles(view, state)
            PropKey.FONT_SIZE -> (view as? TextView)?.let {
                applyTextSizing(it, state)
                applyLetterSpacing(it, state)
                applyLineHeight(it, state)
            }
            PropKey.FONT_WEIGHT,
            PropKey.FONT_STYLE,
            PropKey.FONT_FAMILY,
            -> (view as? TextView)?.let {
                applyTypeface(it, state)
                applyLineHeight(it, state)
            }
            PropKey.LINE_HEIGHT -> (view as? TextView)?.let {
                applyLineHeight(it, state)
            }
            PropKey.NUMBER_OF_LINES -> (view as? TextView)?.maxLines = Int.MAX_VALUE
            PropKey.SELECTION_COLOR -> (view as? TextView)?.highlightColor =
                state.defaultHighlightColor
            PropKey.TEXT_SELECTABLE -> (view as? TextView)?.let { text ->
                text.setTextIsSelectable(false)
                applyTextDataDetector(text, state)
            }
            PropKey.TEXT_ELLIPSIZE_MODE -> (view as? TextView)?.let { text ->
                applyTextEllipsize(text, 1)
            }
            PropKey.TEXT_ALLOW_FONT_SCALING,
            PropKey.TEXT_MAX_FONT_SIZE_MULTIPLIER,
            PropKey.TEXT_ADJUSTS_FONT_SIZE_TO_FIT,
            PropKey.TEXT_MINIMUM_FONT_SCALE,
            -> (view as? TextView)?.let {
                applyTextSizing(it, state)
                applyLetterSpacing(it, state)
                applyLineHeight(it, state)
            }
            PropKey.TEXT_BREAK_STRATEGY -> (view as? TextView)?.let {
                applyTextBreakStrategy(it, TEXT_BREAK_HIGH_QUALITY)
            }
            PropKey.TEXT_HYPHENATION_FREQUENCY ->
                (view as? TextView)?.hyphenationFrequency =
                    Layout.HYPHENATION_FREQUENCY_NONE
            PropKey.TEXT_DATA_DETECTOR_TYPE ->
                (view as? TextView)?.let { applyTextDataDetector(it, state) }
            PropKey.STATUS_BAR_COLOR,
            PropKey.STATUS_BAR_STYLE,
            PropKey.STATUS_BAR_HIDDEN,
            PropKey.STATUS_BAR_ANIMATED,
            PropKey.STATUS_BAR_TRANSLUCENT,
            PropKey.NAVIGATION_BAR_HIDDEN,
            -> applyMergedStatusBar()
            PropKey.TINT_COLOR -> imageView(view)?.imageTintList = null
            PropKey.IMAGE_FIT -> {
                imageView(view)?.scaleType = ImageView.ScaleType.CENTER_CROP
                (view as? PamMediaView)?.setResizeMode(1)
                loadImage(view, state)
            }
            PropKey.IMAGE_DEFAULT_SOURCE,
            PropKey.IMAGE_LOADING_INDICATOR_SOURCE,
            PropKey.IMAGE_FADE_DURATION_MS,
            PropKey.IMAGE_RESIZE_METHOD,
            PropKey.IMAGE_RESIZE_MULTIPLIER,
            PropKey.IMAGE_PROGRESSIVE_RENDERING_ENABLED,
            PropKey.IMAGE_CACHE_POLICY,
            PropKey.IMAGE_SOURCE_SET,
            PropKey.IMAGE_REQUEST_HEADERS,
            -> loadImage(view, state)
            PropKey.IMAGE_OVERLAY_COLOR -> updateBackground(view, state)
            PropKey.PLACEHOLDER_COLOR -> (view as? EditText)?.setHintTextColor(Color.GRAY)
            PropKey.MULTILINE,
            PropKey.SECURE,
            PropKey.KEYBOARD_TYPE,
            PropKey.AUTO_COMPLETE,
            PropKey.RETURN_KEY_TYPE,
            PropKey.INPUT_EDITABLE,
            PropKey.INPUT_AUTO_CORRECT,
            PropKey.INPUT_AUTO_CAPITALIZE,
            PropKey.INPUT_CARET_HIDDEN,
            PropKey.INPUT_CONTEXT_MENU_HIDDEN,
            PropKey.INPUT_CURSOR_COLOR,
            PropKey.INPUT_DISABLE_FULLSCREEN_UI,
            PropKey.INPUT_AUTOFILL_IMPORTANCE,
            PropKey.INPUT_MODE,
            PropKey.INPUT_MIN_LINES,
            PropKey.INPUT_SELECT_TEXT_ON_FOCUS,
            PropKey.INPUT_SELECTION_START,
            PropKey.INPUT_SELECTION_END,
            PropKey.INPUT_SHOW_SOFT_INPUT_ON_FOCUS,
            PropKey.INPUT_SUBMIT_BEHAVIOR,
            PropKey.INPUT_TEXT_ALIGN_VERTICAL,
            PropKey.INPUT_RETURN_KEY_LABEL,
            PropKey.INPUT_SCROLL_ENABLED,
            PropKey.INPUT_UNDERLINE_COLOR,
            PropKey.INPUT_FORMAT,
            PropKey.INPUT_FORMAT_PATTERN,
            PropKey.INPUT_FORMAT_PLACEHOLDER,
            PropKey.INPUT_FORMAT_PREFIX,
            PropKey.INPUT_FORMAT_SUFFIX,
            PropKey.INPUT_FORMAT_DECIMAL_DIGITS,
            PropKey.INPUT_FORMAT_LOCALE,
            -> (view as? PamEditText)?.let {
                applyInputConfiguration(it, state)
                applyInputValue(it, state, it.text.toString())
            }
            PropKey.MAX_LENGTH -> (view as? EditText)?.filters = emptyArray()
            PropKey.AUTO_FOCUS -> Unit
            PropKey.ENABLED -> {
                view.isEnabled = true
                configurePressable(view, state)
            }
            PropKey.ACCESSIBILITY_LABEL -> view.contentDescription = null
            PropKey.ACCESSIBILITY_HINT -> {
                view.tooltipText = null
                configureAccessibilityDelegate(view, state)
            }
            PropKey.ACCESSIBILITY_ROLE -> {
                (view as? PamActivityIndicator)?.setHostAccessibility(false)
                configureAccessibilityDelegate(view, state)
            }
            PropKey.ACCESSIBLE -> view.isFocusable = false
            PropKey.ACCESSIBILITY_LIVE_REGION -> {
                view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            }
            PropKey.ACCESSIBILITY_IMPORTANCE -> {
                view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            }
            PropKey.ACCESSIBILITY_EXPANDED,
            PropKey.ACCESSIBILITY_BUSY,
            PropKey.ACCESSIBILITY_CHECKED_STATE,
            PropKey.ACCESSIBILITY_VALUE_MIN,
            PropKey.ACCESSIBILITY_VALUE_MAX,
            PropKey.ACCESSIBILITY_VALUE_NOW,
            PropKey.ACCESSIBILITY_VALUE_TEXT,
            -> notifyAccessibilityChanged(view)
            PropKey.ACCESSIBILITY_ACTIONS,
            PropKey.ON_ACCESSIBILITY_ACTION,
            -> configureAccessibilityDelegate(view, state)
            PropKey.KEYBOARD_BEHAVIOR -> {
                state.keyboardBehavior = KEYBOARD_RESIZE
                applyKeyboardAvoidance(view, state)
            }
            PropKey.KEYBOARD_VERTICAL_OFFSET -> if (view is PamScrollContainer) {
                configureScrollKeyboardInset(view, state, state.flag(PropKey.SCROLL_KEYBOARD_INSET, false))
            } else {
                applyKeyboardAvoidance(view, state)
            }
            PropKey.KEYBOARD_AVOIDING_ENABLED,
            -> {
                configureLegacyKeyboardInsets(view, state)
                applyKeyboardAvoidance(view, state)
            }
            PropKey.SAFE_AREA_TOP,
            PropKey.SAFE_AREA_RIGHT,
            PropKey.SAFE_AREA_BOTTOM_EDGE,
            PropKey.SAFE_AREA_LEFT,
            PropKey.SAFE_AREA_MODE,
            -> applySafeAreaLayout(view, state)
            PropKey.TEST_ID -> view.transitionName = null
            PropKey.SHARED_TRANSITION_TAG ->
                view.setTag(dev.pam.nativeapp.R.id.pam_shared_transition_tag, null)
            PropKey.SHARED_TRANSITION_CONFIG ->
                view.setTag(dev.pam.nativeapp.R.id.pam_shared_transition_config, null)
            PropKey.ITEMS,
            PropKey.SECTION_ITEMS,
            -> (view as? PamRecyclerList)?.setItems(null)
            PropKey.SCROLL_ENABLED -> when (view) {
                is PamScrollContainer -> view.setScrollEnabled(true)
                is PamRecyclerList -> view.setScrollEnabled(true)
            }
            PropKey.SHOWS_SCROLL_INDICATOR -> when (view) {
                is PamScrollContainer -> view.setShowsScrollIndicator(false)
                is PamRecyclerList -> view.setShowsScrollIndicator(true)
            }
            PropKey.SCROLL_HORIZONTAL ->
                (view as? PamScrollContainer)?.setHorizontal(false)
            PropKey.SCROLL_CONTENT_OFFSET_X ->
                (view as? PamScrollContainer)?.setContentOffsetX(0f)
            PropKey.SCROLL_CONTENT_OFFSET_Y ->
                (view as? PamScrollContainer)?.setContentOffsetY(0f)
            PropKey.SCROLL_ANCHOR_TO_END ->
                (view as? PamScrollContainer)?.setAnchorToEnd(false)
            PropKey.SCROLL_MAINTAIN_VISIBLE_CONTENT_POSITION ->
                (view as? PamScrollContainer)?.setMaintainVisibleContentPosition(false)
            PropKey.SCROLL_AUTO_SCROLL_TO_END_THRESHOLD ->
                (view as? PamScrollContainer)?.setAutoScrollToEndThreshold(24f)
            PropKey.SCROLL_TARGET_TEST_ID ->
                (view as? PamScrollContainer)?.setScrollTargetTestId("")
            PropKey.SCROLL_TARGET_OFFSET ->
                (view as? PamScrollContainer)?.setScrollTargetOffset(-1f)
            PropKey.SCROLL_TARGET_ALIGNMENT ->
                (view as? PamScrollContainer)?.setScrollTargetAlignment(1)
            PropKey.DRAWING_COLOR ->
                (view as? PamDrawingCanvas)?.setBrushColor(Color.WHITE)
            PropKey.DRAWING_WIDTH ->
                (view as? PamDrawingCanvas)?.setBrushWidth(6f)
            PropKey.DRAWING_MODE ->
                (view as? PamDrawingCanvas)?.setDrawingMode(1)
            PropKey.DRAWING_CLEAR_REQUEST ->
                (view as? PamDrawingCanvas)?.setClearRequest(0)
            PropKey.DRAWING_UNDO_REQUEST ->
                (view as? PamDrawingCanvas)?.setUndoRequest(0)
            PropKey.FLEX_WRAP -> Unit
            PropKey.LEFT_PERCENT,
            PropKey.TOP_PERCENT,
            PropKey.RIGHT_PERCENT,
            PropKey.BOTTOM_PERCENT,
            -> Unit
            PropKey.SCROLL_REQUEST -> Unit
            PropKey.SCROLL_FILL_VIEWPORT ->
                (view as? PamScrollContainer)?.setFillViewport(true)
            PropKey.SCROLL_OVER_SCROLL_MODE ->
                (view as? PamScrollContainer)?.setOverScrollModeValue(1)
            PropKey.SCROLL_NESTED_ENABLED ->
                (view as? PamScrollContainer)?.setNestedScrollEnabled(true)
            PropKey.SCROLL_FADING_EDGE_LENGTH ->
                (view as? PamScrollContainer)?.setFadingEdgeLength(0f)
            PropKey.SCROLL_PERSISTENT_SCROLLBAR ->
                (view as? PamScrollContainer)?.setPersistentScrollbar(false)
            PropKey.SCROLL_INDICATOR_STYLE ->
                (view as? PamScrollContainer)?.setIndicatorStyle(ScrollIndicatorStyle.AUTO.wireValue)
            PropKey.SCROLL_PAGING_ENABLED -> when (view) {
                is PamScrollContainer -> view.setPagingEnabled(false)
                is PamRecyclerList -> view.setPagingEnabled(false)
            }
            PropKey.SCROLL_SNAP_INTERVAL ->
                (view as? PamScrollContainer)?.setSnapInterval(0f)
            PropKey.SCROLL_DECELERATION_RATE ->
                (view as? PamScrollContainer)?.setDecelerationRate(0.985f)
            PropKey.SCROLL_KEYBOARD_DISMISS_MODE ->
                (view as? PamScrollContainer)?.setKeyboardDismissMode(1)
            PropKey.LIST_ROW_HEIGHT -> (view as? PamRecyclerList)?.setRowHeight(48f)
            PropKey.LIST_PREFETCH -> (view as? PamRecyclerList)?.setPrefetchItems(5)
            PropKey.LIST_HORIZONTAL -> (view as? PamRecyclerList)?.setHorizontal(false)
            PropKey.LIST_NUM_COLUMNS -> (view as? PamRecyclerList)?.setColumns(1)
            PropKey.LIST_INVERTED -> (view as? PamRecyclerList)?.setInverted(false)
            PropKey.LIST_INITIAL_SCROLL_INDEX ->
                (view as? PamRecyclerList)?.setInitialIndex(0)
            PropKey.LIST_REMOVE_CLIPPED_SUBVIEWS ->
                (view as? PamRecyclerList)?.setRemoveClippedSubviews(true)
            PropKey.OPACITY -> {
                if (state.integer(PropKey.ANIMATION_KIND, 1L) == 2L) {
                    applyAnimationKind(view, state, 2)
                } else {
                    view.alpha = 1f
                }
                configurePressable(view, state)
            }
            PropKey.TRANSLATION_X -> view.translationX = 0f
            PropKey.TRANSLATION_Y -> view.translationY = 0f
            PropKey.SCALE_X -> view.scaleX = 1f
            PropKey.SCALE_Y -> view.scaleY = 1f
            PropKey.ROTATION -> view.rotation = 0f
            PropKey.OVERFLOW -> applyOverflowClip(view, state)
            PropKey.VISIBLE -> {
                when (view) {
                    is PamModalHost -> view.setVisible(true)
                    is PamActivityIndicator -> view.setRequestedVisible(true)
                    else -> view.visibility = View.VISIBLE
                }
                requestAutoFocusDescendant(state.id)
            }
            PropKey.MODAL_PRESENTATION ->
                (view as? PamModalHost)?.setPresentation(2)
            PropKey.MODAL_ANIMATION_TYPE ->
                (view as? PamModalHost)?.setAnimationType(1)
            PropKey.MODAL_BACKDROP_COLOR ->
                (view as? PamModalHost)?.setBackdropColor(Color.WHITE)
            PropKey.MODAL_TRANSPARENT ->
                (view as? PamModalHost)?.setTransparent(false)
            PropKey.MODAL_HARDWARE_ACCELERATED ->
                (view as? PamModalHost)?.setHardwareAccelerated(false)
            PropKey.MODAL_NAVIGATION_BAR_TRANSLUCENT ->
                (view as? PamModalHost)?.setNavigationBarTranslucent(false)
            PropKey.MODAL_STATUS_BAR_TRANSLUCENT ->
                (view as? PamModalHost)?.setStatusBarTranslucent(false)
            PropKey.MODAL_ALLOW_SWIPE_DISMISSAL ->
                (view as? PamModalHost)?.setAllowSwipeDismissal(false)
            PropKey.BOTTOM_SHEET_SNAP_POINTS ->
                (view as? PamModalHost)?.setBottomSheetSnapPoints(listOf(0.5f, 0.9f))
            PropKey.BOTTOM_SHEET_INDEX ->
                (view as? PamModalHost)?.setBottomSheetIndex(0)
            PropKey.BOTTOM_SHEET_DISMISSIBLE ->
                (view as? PamModalHost)?.setBottomSheetDismissible(true)
            PropKey.BOTTOM_SHEET_BACKDROP_DISMISS ->
                (view as? PamModalHost)?.setBottomSheetBackdropDismiss(true)
            PropKey.BOTTOM_SHEET_HANDLE_VISIBLE ->
                (view as? PamModalHost)?.setBottomSheetHandleVisible(true)
            PropKey.BOTTOM_SHEET_DRAG_ENABLED ->
                (view as? PamModalHost)?.setBottomSheetDragEnabled(true)
            PropKey.BOTTOM_SHEET_KEYBOARD_BEHAVIOR ->
                (view as? PamModalHost)?.setBottomSheetKeyboardBehavior(1)
            PropKey.BOTTOM_SHEET_CORNER_RADIUS ->
                (view as? PamModalHost)?.setBottomSheetCornerRadius(20f)
            PropKey.WEB_VIEW_SOURCE -> (view as? PamWebView)?.setSource("")
            PropKey.WEB_VIEW_JAVA_SCRIPT_ENABLED ->
                (view as? PamWebView)?.setJavaScriptEnabled(true)
            PropKey.WEB_VIEW_DOM_STORAGE_ENABLED ->
                (view as? PamWebView)?.setDomStorageEnabled(true)
            PropKey.WEB_VIEW_USER_AGENT -> (view as? PamWebView)?.setUserAgent("")
            PropKey.WEB_VIEW_INJECTED_JAVA_SCRIPT ->
                (view as? PamWebView)?.setInjectedJavaScript("")
            PropKey.WEB_VIEW_ALLOWS_INLINE_MEDIA ->
                (view as? PamWebView)?.setAllowsInlineMedia(true)
            PropKey.WEB_VIEW_ALLOWED_HOSTS ->
                (view as? PamWebView)?.setAllowedHosts("")
            PropKey.MEDIA_SOURCE -> (view as? PamMediaView)?.setSource("")
            PropKey.MEDIA_TYPE -> Unit
            PropKey.MEDIA_AUTO_PLAY -> (view as? PamMediaView)?.setAutoPlay(false)
            PropKey.MEDIA_CONTROLS -> (view as? PamMediaView)?.setControls(true)
            PropKey.MEDIA_LOOP -> (view as? PamMediaView)?.setLoop(false)
            PropKey.MEDIA_MUTED -> (view as? PamMediaView)?.setMuted(false)
            PropKey.MEDIA_VOLUME -> (view as? PamMediaView)?.setVolume(1f)
            PropKey.MEDIA_CURRENT_TIME -> (view as? PamMediaView)?.seek(0.0)
            PropKey.MEDIA_PLAYBACK_RATE -> (view as? PamMediaView)?.setPlaybackRate(1f)
            PropKey.MEDIA_CACHE_POLICY,
            PropKey.MEDIA_CACHE_KEY,
            PropKey.MEDIA_CACHE_MAX_AGE_MS,
            PropKey.MEDIA_CACHE_TAGS,
            PropKey.MEDIA_CACHE_PIN_OFFLINE,
            PropKey.MEDIA_CACHE_STREAMING,
            PropKey.MEDIA_CACHE_PRELOAD_SECONDS,
            PropKey.MEDIA_CACHE_DOWNLOAD_WHILE_PLAYING,
            PropKey.MEDIA_CACHE_MAX_BYTES,
            PropKey.MEDIA_THUMBNAIL_SOURCE,
            PropKey.MEDIA_RESIZE_WIDTH,
            PropKey.MEDIA_RESIZE_HEIGHT,
            PropKey.MEDIA_PRIORITY,
            PropKey.MEDIA_CACHE_CHECKSUM,
            -> when (view) {
                is PamMediaView -> {
                    configureMediaCache(view, state)
                    configureMediaThumbnail(view, state)
                }
                is PamImageView -> loadImage(view, state)
            }
            PropKey.ON_MEDIA_CACHE_HIT,
            PropKey.ON_MEDIA_CACHE_MISS,
            PropKey.ON_MEDIA_CACHE_PROGRESS,
            PropKey.ON_MEDIA_CACHE_READY,
            -> installEvents(view, state)
            PropKey.NAVIGATION_GESTURE_ENABLED,
            PropKey.NAVIGATION_GESTURE_EDGE_WIDTH,
            PropKey.NAVIGATION_GESTURE_THRESHOLD,
            -> configureGestureNavigation(view, state)
            PropKey.DRAGGABLE,
            PropKey.DRAG_DATA,
            PropKey.DROP_ENABLED,
            PropKey.CONTEXT_MENU_ITEMS,
            -> configureNativeInteractions(view, state)
            PropKey.ANIMATION_KEYFRAMES,
            PropKey.ANIMATION_ITERATIONS,
            PropKey.ANIMATION_DELAY_MS,
            PropKey.ANIMATION_FILL_MODE,
            PropKey.ANIMATION_PLAY_STATE,
            PropKey.ANIMATION_AUTO_REVERSE,
            -> {
                state.keyframeAnimator?.cancel()
                state.keyframeAnimator = null
            }
            PropKey.ANIMATION_PROGRAM -> {
                state.motionRunner?.cancel()
                state.motionRunner = null
                state.motionProgramId = Long.MIN_VALUE
            }
            PropKey.NATIVE_REF -> view.setTag(dev.pam.nativeapp.R.id.pam_native_ref, null)
            PropKey.TRANSITION_SPEC -> state.transitionRules = emptyMap()
            PropKey.PRESS_DOUBLE_TAP_DELAY_MS,
            PropKey.PRESS_TAP_EFFECT,
            PropKey.GESTURE_DRAG,
            -> configurePressable(view, state)
            PropKey.WORKLET_PROGRAM,
            PropKey.WORKLET_TARGET,
            PropKey.WORKLET_DURATION_MS,
            PropKey.WORKLET_ITERATIONS,
            -> {
                state.workletAnimator?.cancel()
                state.workletAnimator = null
            }
            PropKey.CHECKED -> (view as? Switch)?.isChecked = false
            PropKey.ACTIVITY_ANIMATING ->
                (view as? PamActivityIndicator)?.setAnimating(true)
            PropKey.ACTIVITY_HIDES_WHEN_STOPPED ->
                (view as? PamActivityIndicator)?.setHidesWhenStopped(true)
            PropKey.ACTIVITY_SIZE ->
                (view as? PamActivityIndicator)?.setSize(20f)
            PropKey.SWITCH_TRACK_COLOR_FALSE ->
                (view as? PamSwitch)?.setTrackOffColor(null)
            PropKey.SWITCH_TRACK_COLOR_TRUE ->
                (view as? PamSwitch)?.setTrackOnColor(null)
            PropKey.SWITCH_THUMB_COLOR ->
                (view as? PamSwitch)?.setThumbColor(null)
            PropKey.PROGRESS_COLOR -> (view as? PamActivityIndicator)?.setColor(null)
            PropKey.REFRESHING -> (view as? PamRefreshContainer)?.setRefreshing(false)
            PropKey.REFRESH_COLORS -> (view as? PamRefreshContainer)?.setColors(null)
            PropKey.REFRESH_PROGRESS_BACKGROUND_COLOR ->
                (view as? PamRefreshContainer)?.setProgressBackgroundColor(null)
            PropKey.REFRESH_PROGRESS_VIEW_OFFSET ->
                (view as? PamRefreshContainer)?.setProgressViewOffset(0f)
            PropKey.REFRESH_INDICATOR_SIZE ->
                (view as? PamRefreshContainer)?.setIndicatorSize(REFRESH_SIZE_DEFAULT)
            PropKey.DRAWER_OPEN -> (view as? PamDrawerLayout)?.setOpen(false)
            PropKey.TEXT_DECORATION -> (view as? TextView)?.let { text ->
                text.paintFlags = text.paintFlags and
                    (Paint.UNDERLINE_TEXT_FLAG or Paint.STRIKE_THRU_TEXT_FLAG).inv()
            }
            PropKey.TEXT_TRANSFORM -> if (view is TextView && view !is EditText) {
                view.transformationMethod = null
                if (isRichTextView(view, state)) applyTextContent(view, state)
            }
            PropKey.POINTER_EVENTS -> applyPointerEvents(view, state, POINTER_EVENTS_AUTO)
            PropKey.SAFE_AREA_BOTTOM -> applySafeAreaBottom(view, state, false)
            PropKey.BLUR_RADIUS -> applyFilter(view, state)
            PropKey.TRANSLATION_X_PERCENT -> {
                view.translationX = dp(state.number(PropKey.TRANSLATION_X, 0.0).toFloat()).toFloat()
            }
            PropKey.TRANSLATION_Y_PERCENT -> {
                view.translationY = dp(state.number(PropKey.TRANSLATION_Y, 0.0).toFloat()).toFloat()
            }
            PropKey.TRANSFORM_ORIGIN_X,
            PropKey.TRANSFORM_ORIGIN_Y,
            -> applyTransformOrigin(view, state)
            PropKey.TEXT_SHADOW_OFFSET_X,
            PropKey.TEXT_SHADOW_OFFSET_Y,
            PropKey.TEXT_SHADOW_RADIUS,
            PropKey.TEXT_SHADOW_COLOR,
            -> (view as? TextView)?.let { applyTextShadow(it, state) }
            PropKey.FONT_FEATURE_SETTINGS -> (view as? TextView)?.fontFeatureSettings = null
            PropKey.FLEX_BASIS,
            PropKey.FLEX_BASIS_PERCENT,
            PropKey.FLEX_BASIS_CONTENT,
            PropKey.ALIGN_CONTENT,
            PropKey.MARGIN_TOP_AUTO,
            PropKey.MARGIN_RIGHT_AUTO,
            PropKey.MARGIN_BOTTOM_AUTO,
            PropKey.MIN_WIDTH_PERCENT,
            PropKey.MIN_HEIGHT_PERCENT,
            PropKey.LIST_FULL_SPAN,
            PropKey.LIST_SECTION,
            PropKey.LIST_ACTIVE_SECTION,
            -> Unit
            PropKey.TEXT_SPANS,
            PropKey.ON_SPAN_PRESS,
            -> (view as? TextView)?.let { if (isRichTextView(it, state)) applyTextContent(it, state) }
            PropKey.INCLUDE_FONT_PADDING -> (view as? TextView)?.includeFontPadding = view !is EditText
            PropKey.ON_LAYOUT -> lastLayoutEvents.remove(state.id)
            PropKey.STICKY_HEADER -> applyStickyHeader(view, false)
            PropKey.SCROLL_KEYBOARD_INSET ->
                (view as? PamScrollContainer)?.let { configureScrollKeyboardInset(it, state, false) }
            PropKey.ANIMATION_KIND -> applyAnimationKind(view, state, 1)
            PropKey.ANIMATION_DURATION_MS -> {
                if (state.integer(PropKey.ANIMATION_KIND, 1L) == 2L) {
                    applyAnimationKind(view, state, 2)
                }
            }
            PropKey.HIT_SLOP,
            PropKey.HIT_SLOP_LEFT,
            PropKey.HIT_SLOP_TOP,
            PropKey.HIT_SLOP_RIGHT,
            PropKey.HIT_SLOP_BOTTOM,
            -> applyHitSlop(view, state)
            PropKey.PRESS_OPACITY,
            PropKey.PRESS_RETENTION_LEFT,
            PropKey.PRESS_RETENTION_TOP,
            PropKey.PRESS_RETENTION_RIGHT,
            PropKey.PRESS_RETENTION_BOTTOM,
            PropKey.PRESS_DELAY_LONG_MS,
            PropKey.PRESS_DELAY_IN_MS,
            PropKey.PRESS_DELAY_OUT_MS,
            PropKey.PRESS_ANDROID_DISABLE_SOUND,
            -> configurePressable(view, state)
            PropKey.PRESS_SCALE -> {
                state.pressScale = 1f
                configurePressable(view, state)
            }
            PropKey.HOST_PROPERTIES -> Unit
            PropKey.ON_INPUT_END_EDITING,
            PropKey.ON_INPUT_SELECTION_CHANGE,
            PropKey.ON_INPUT_CONTENT_SIZE_CHANGE,
            PropKey.ON_INPUT_KEY_PRESS,
            PropKey.ON_PRESS_IN,
            PropKey.ON_PRESS_OUT,
            PropKey.ON_PRESS_MOVE,
            PropKey.ON_MODAL_REQUEST_CLOSE,
            PropKey.ON_MODAL_SHOW,
            PropKey.ON_MODAL_DISMISS,
            PropKey.ON_MODAL_ORIENTATION_CHANGE,
            -> Unit
            else -> Unit
        }
    }

    private fun applyHitSlop(view: View, state: NodeState) {
        val parent = view.parent as? ViewGroup ?: return
        val all = state.number(PropKey.HIT_SLOP, 0.0)
            .toFloat()
            .coerceAtLeast(0f)
        val left = dp(
            state.number(PropKey.HIT_SLOP_LEFT, all.toDouble()).toFloat().coerceAtLeast(0f),
        )
        val top = dp(
            state.number(PropKey.HIT_SLOP_TOP, all.toDouble()).toFloat().coerceAtLeast(0f),
        )
        val right = dp(
            state.number(PropKey.HIT_SLOP_RIGHT, all.toDouble()).toFloat().coerceAtLeast(0f),
        )
        val bottom = dp(
            state.number(PropKey.HIT_SLOP_BOTTOM, all.toDouble()).toFloat().coerceAtLeast(0f),
        )
        val maintainsMinimumTarget = requiresMinimumTouchTarget(view)
        if (!maintainsMinimumTarget && left <= 0 && top <= 0 && right <= 0 && bottom <= 0) {
            clearHitSlop(view)
            return
        }
        parent.post {
            if (view.parent !== parent || !view.isAttachedToWindow) return@post
            val bounds = Rect()
            view.getHitRect(bounds)
            val minimumInsets = minimumTouchTargetInsets(
                bounds.width(),
                bounds.height(),
                dp(48f),
            )
            val expandLeft = maxOf(left, minimumInsets.left)
            val expandTop = maxOf(top, minimumInsets.top)
            val expandRight = maxOf(right, minimumInsets.right)
            val expandBottom = maxOf(bottom, minimumInsets.bottom)
            if (expandLeft <= 0 && expandTop <= 0 && expandRight <= 0 && expandBottom <= 0) {
                // Already a large enough target: regular hit testing reaches it
                // and its descendants; a delegate would only pre-empt them.
                clearHitSlop(view)
                return@post
            }
            bounds.left -= expandLeft
            bounds.top -= expandTop
            bounds.right += expandRight
            bounds.bottom += expandBottom
            val group = parent.touchDelegate as? PamTouchDelegateGroup
                ?: PamTouchDelegateGroup(parent).also { parent.touchDelegate = it }
            group.update(view, bounds)
        }
    }

    private fun clearHitSlop(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        val group = parent.touchDelegate as? PamTouchDelegateGroup ?: return
        group.remove(view)
        if (group.isEmpty()) {
            parent.touchDelegate = null
        }
    }

    private fun installEvents(view: View, state: NodeState) {
        if (view is PamPressable) {
            view.setOnClickListener(null)
            view.setOnLongClickListener(null)
            view.setCallbacks(
                onPress = state.pointerCallback(PropKey.ON_PRESS) { pointer ->
                    flushFocusedNativeInputs()
                    dispatchPressPointer(state, EVENT_PRESS, pointer)
                },
                onLongPress = state.pointerCallback(PropKey.ON_LONG_PRESS) { pointer ->
                    dispatchPressPointer(state, EVENT_LONG_PRESS, pointer)
                },
                onDoubleTap = state.pointerCallback(PropKey.ON_DOUBLE_TAP) { pointer ->
                    dispatchPressPointer(state, EventKind.DOUBLE_TAP.value, pointer)
                },
                onPressIn = state.pointerCallback(PropKey.ON_PRESS_IN) { pointer ->
                    dispatchPressPointer(state, EVENT_PRESS_IN, pointer)
                },
                onPressOut = state.pointerCallback(PropKey.ON_PRESS_OUT) { pointer ->
                    dispatchPressPointer(state, EVENT_PRESS_OUT, pointer)
                },
                onPressMove = state.pointerCallback(PropKey.ON_PRESS_MOVE) { pointer ->
                    dispatchPressPointer(state, EVENT_PRESS_MOVE, pointer)
                },
            )
            configurePressable(view, state)
        } else if (state.kind != NodeKind.CUSTOM_VIEW) {
            if (state.properties[PropKey.ON_PRESS] != null) {
                view.setOnClickListener {
                    flushFocusedNativeInputs()
                    dispatch(state.id, EVENT_PRESS)
                }
            } else {
                view.setOnClickListener(null)
            }
            if (state.properties[PropKey.ON_LONG_PRESS] != null) {
                view.setOnLongClickListener {
                    dispatch(state.id, EVENT_LONG_PRESS)
                    true
                }
            } else {
                view.setOnLongClickListener(null)
            }
            if (
                view !is EditText &&
                view !is Switch &&
                !state.nativeInteractionsInstalled &&
                state.properties[PropKey.POINTER_EVENTS] == null
            ) {
                view.isClickable = state.properties[PropKey.ON_PRESS] != null
                view.isLongClickable =
                    state.properties[PropKey.ON_LONG_PRESS] != null
            }
        }
        installPressFeedback(view, state)
        installDirectiveEvents(view, state)
        if (view is EditText) installInputEvents(view, state)
        if (view is Switch) {
            view.setOnCheckedChangeListener { _, checked ->
                if (!state.updating && state.properties[PropKey.ON_TOGGLE] != null) {
                    dispatch(state.id, EVENT_TOGGLE, if (checked) "1" else "0")
                }
            }
        }
        if (view is PamScrollContainer) installScrollEvents(view, state)
        if (view is PamDrawingCanvas) {
            view.setOnDrawingChange(
                if (state.properties[PropKey.ON_CHANGE] != null) {
                    { drawing -> dispatch(state.id, EVENT_CHANGE, drawing) }
                } else {
                    null
                },
            )
        }
        if (view is PamRecyclerList) installListEvents(view, state)
        if (view is TextView && view !is EditText) installTextLayout(view, state)
        if (view is PamRefreshContainer) {
            view.setOnRefresh(
                if (state.properties[PropKey.ON_REFRESH] != null) {
                    { dispatch(state.id, EVENT_REFRESH) }
                } else {
                    null
                },
            )
        }
        if (view is PamDrawerLayout) {
            view.setCallbacks(
                if (state.properties[PropKey.ON_DRAWER_OPEN] != null) {
                    { dispatch(state.id, EVENT_DRAWER_OPEN) }
                } else {
                    null
                },
                if (state.properties[PropKey.ON_DRAWER_CLOSE] != null) {
                    { dispatch(state.id, EVENT_DRAWER_CLOSE) }
                } else {
                    null
                },
            )
        }
        if (view is PamModalHost) {
            view.setCallbacks(
                onRequestClose = if (
                    state.properties[PropKey.ON_MODAL_REQUEST_CLOSE] != null ||
                    state.properties[PropKey.ON_NATIVE_EVENT] != null
                ) {
                    {
                        if (state.properties[PropKey.ON_MODAL_REQUEST_CLOSE] != null) {
                            dispatch(state.id, EVENT_MODAL_REQUEST_CLOSE)
                        }
                        if (state.properties[PropKey.ON_NATIVE_EVENT] != null) {
                            dispatchBytes(
                                state.id,
                                EVENT_NATIVE,
                                MODAL_DISMISS_PAYLOAD,
                            )
                        }
                    }
                } else {
                    null
                },
                onShow = state.callback(PropKey.ON_MODAL_SHOW) {
                    dispatch(state.id, EVENT_MODAL_SHOW)
                },
                onDismiss = state.callback(PropKey.ON_MODAL_DISMISS) {
                    dispatch(state.id, EVENT_MODAL_DISMISS)
                },
                onOrientationChange = if (
                    state.properties[PropKey.ON_MODAL_ORIENTATION_CHANGE] != null
                ) {
                    { orientation ->
                        dispatch(
                            state.id,
                            EVENT_MODAL_ORIENTATION_CHANGE,
                            orientation.toString(),
                        )
                    }
                } else {
                    null
                },
            )
            view.setBottomSheetCallbacks(
                onChange = if (
                    state.properties[PropKey.ON_BOTTOM_SHEET_CHANGE] != null
                ) {
                    { index, position ->
                        dispatchBytes(
                            state.id,
                            EVENT_BOTTOM_SHEET_CHANGE,
                            WireMap.encode(
                                mapOf(
                                    "index" to WireValue.Integer(index.toLong()),
                                    "position" to WireValue.Decimal(position.toDouble()),
                                ),
                            ),
                        )
                    }
                } else {
                    null
                },
                onDismiss = state.callback(PropKey.ON_BOTTOM_SHEET_DISMISS) {
                    dispatch(state.id, EVENT_BOTTOM_SHEET_DISMISS)
                },
            )
        }
        if (view is PamWebView) {
            view.onLoad = state.callback(PropKey.ON_WEB_VIEW_LOAD) {
                dispatch(state.id, EventKind.WEB_VIEW_LOAD.value)
            }
            view.onError = if (state.properties[PropKey.ON_WEB_VIEW_ERROR] != null) {
                { message ->
                    dispatchBytes(
                        state.id,
                        EventKind.WEB_VIEW_ERROR.value,
                        WireMap.encode(mapOf("message" to WireValue.Text(message))),
                    )
                }
            } else null
            view.onMessage = if (state.properties[PropKey.ON_WEB_VIEW_MESSAGE] != null) {
                { message ->
                    dispatchBytes(
                        state.id,
                        EventKind.WEB_VIEW_MESSAGE.value,
                        WireMap.encode(mapOf("message" to WireValue.Text(message))),
                    )
                }
            } else null
        }
        if (view is PamMediaView) {
            view.onReady = null
            view.onReadyDetails = if (state.properties[PropKey.ON_MEDIA_READY] != null) {
                { width, height, duration ->
                    dispatchBytes(
                        state.id,
                        EventKind.MEDIA_READY.value,
                        WireMap.encode(
                            mapOf(
                                "naturalWidth" to WireValue.Integer(width.toLong()),
                                "naturalHeight" to WireValue.Integer(height.toLong()),
                                "duration" to WireValue.Decimal(duration),
                            ),
                        ),
                    )
                }
            } else null
            view.onLoadStart = state.callback(PropKey.ON_MEDIA_LOAD_START) {
                dispatch(state.id, EventKind.MEDIA_LOAD_START.value)
            }
            view.onBuffering = if (state.properties[PropKey.ON_MEDIA_BUFFERING] != null) {
                { buffering ->
                    dispatchBytes(
                        state.id,
                        EventKind.MEDIA_BUFFERING.value,
                        WireMap.encode(mapOf("buffering" to WireValue.Flag(buffering))),
                    )
                }
            } else null
            view.onProgress = if (state.properties[PropKey.ON_MEDIA_PROGRESS] != null) {
                { current, duration ->
                    dispatchBytes(
                        state.id,
                        EventKind.MEDIA_PROGRESS.value,
                        WireMap.encode(
                            mapOf(
                                "currentTime" to WireValue.Decimal(current),
                                "duration" to WireValue.Decimal(duration),
                            ),
                        ),
                    )
                }
            } else null
            view.onEnd = state.callback(PropKey.ON_MEDIA_END) {
                dispatch(state.id, EventKind.MEDIA_END.value)
            }
            view.onError = if (state.properties[PropKey.ON_MEDIA_ERROR] != null) {
                { message ->
                    dispatchBytes(
                        state.id,
                        EventKind.MEDIA_ERROR.value,
                        WireMap.encode(mapOf("message" to WireValue.Text(message))),
                    )
                }
            } else null
            view.onCacheHit = mediaCacheCallback(state, PropKey.ON_MEDIA_CACHE_HIT, EventKind.MEDIA_CACHE_HIT)
            view.onCacheMiss = mediaCacheCallback(state, PropKey.ON_MEDIA_CACHE_MISS, EventKind.MEDIA_CACHE_MISS)
            view.onCacheProgress =
                if (state.properties[PropKey.ON_MEDIA_CACHE_PROGRESS] != null) {
                    { key, loaded, total ->
                        dispatchBytes(
                            state.id,
                            EventKind.MEDIA_CACHE_PROGRESS.value,
                            mediaCachePayload(key, loaded, total, true),
                        )
                    }
                } else null
            view.onCacheReady =
                if (state.properties[PropKey.ON_MEDIA_CACHE_READY] != null) {
                    { key, bytes ->
                        dispatchBytes(
                            state.id,
                            EventKind.MEDIA_CACHE_READY.value,
                            mediaCachePayload(key, bytes, bytes, true),
                        )
                    }
                } else null
        }
        configureNativeInteractions(view, state)
        configureGestureNavigation(view, state)
    }

    private fun mediaCacheCallback(
        state: NodeState,
        property: PropKey,
        event: EventKind,
    ): ((String) -> Unit)? =
        if (state.properties[property] != null) {
            { key ->
                dispatchBytes(
                    state.id,
                    event.value,
                    mediaCachePayload(key, 0, 0, event == EventKind.MEDIA_CACHE_HIT),
                )
            }
        } else null

    private fun configureMediaCache(view: PamMediaView, state: NodeState) {
        view.setCacheRequest(
            MediaCacheRequest(
                source = state.textOrNull(PropKey.MEDIA_SOURCE).orEmpty(),
                policy = state.integer(PropKey.MEDIA_CACHE_POLICY, MEDIA_CACHE_NONE.toLong()).toInt(),
                key = state.textOrNull(PropKey.MEDIA_CACHE_KEY),
                maxAgeMs = state.integer(PropKey.MEDIA_CACHE_MAX_AGE_MS, 0),
                maxBytes = state.integer(PropKey.MEDIA_CACHE_MAX_BYTES, 0),
                checksum = state.textOrNull(PropKey.MEDIA_CACHE_CHECKSUM),
                pinOffline = state.flag(PropKey.MEDIA_CACHE_PIN_OFFLINE, false),
                streaming = state.flag(PropKey.MEDIA_CACHE_STREAMING, false),
                downloadWhilePlaying =
                    state.flag(PropKey.MEDIA_CACHE_DOWNLOAD_WHILE_PLAYING, false),
            ),
        )
    }

    private fun decodeBottomSheetSnapPoints(value: PropValue): List<Float> {
        val source = (value as? PropValue.Bytes)?.value?.duplicate()
            ?: error("Bottom Sheet snap points must be bytes")
        source.order(ByteOrder.LITTLE_ENDIAN)
        require(source.remaining() >= 2) { "Invalid Bottom Sheet snap points" }
        val count = source.short.toInt() and 0xffff
        require(count in 1..16 && source.remaining() == count * 8) {
            "Invalid Bottom Sheet snap points"
        }
        return List(count) { source.double.toFloat() }
    }

    private fun configureNativeInteractions(view: View, state: NodeState) {
        val configured = listOf(
            PropKey.DRAGGABLE,
            PropKey.DRAG_DATA,
            PropKey.DROP_ENABLED,
            PropKey.CONTEXT_MENU_ITEMS,
            PropKey.ON_DRAG_START,
            PropKey.ON_DRAG_END,
            PropKey.ON_DROP,
            PropKey.ON_MENU_ACTION,
        ).any(state.properties::containsKey)
        if (!configured && !state.nativeInteractionsInstalled) return
        state.nativeInteractionsInstalled = configured
        val draggable = state.flag(PropKey.DRAGGABLE, false)
        val dropEnabled = state.flag(PropKey.DROP_ENABLED, false)
        val dragData = state.textOrNull(PropKey.DRAG_DATA).orEmpty()
        val menuItems = decodeContextMenuItems(state.properties[PropKey.CONTEXT_MENU_ITEMS])
        (view as? PamPressable)?.setNativeInteractionEnabled(
            draggable || dropEnabled || menuItems.isNotEmpty(),
        )

        view.setOnLongClickListener(
            when {
                draggable -> View.OnLongClickListener {
                    val started = it.startDragAndDrop(
                        ClipData.newPlainText("Pam Native", dragData),
                        View.DragShadowBuilder(it),
                        dragData,
                        0,
                    )
                    if (BuildConfig.DEBUG && Log.isLoggable(INTERACTION_LOG_TAG, Log.DEBUG)) {
                        Log.d(INTERACTION_LOG_TAG, "${state.id}: drag start requested=$started data=$dragData")
                    }
                    if (started && state.properties[PropKey.ON_DRAG_START] != null) {
                        dispatch(state.id, EventKind.DRAG_START.value)
                    }
                    started
                }
                menuItems.isNotEmpty() -> View.OnLongClickListener {
                    val popup = PopupMenu(context, it)
                    menuItems.forEachIndexed { index, item ->
                        popup.menu.add(0, index + 1, index, item.title).apply {
                            isEnabled = !item.disabled
                        }
                    }
                    popup.setOnMenuItemClickListener { selected ->
                        val item = menuItems.getOrNull(selected.itemId - 1)
                            ?: return@setOnMenuItemClickListener false
                        if (state.properties[PropKey.ON_MENU_ACTION] != null) {
                            dispatchBytes(
                                state.id,
                                EventKind.MENU_ACTION.value,
                                WireMap.encode(mapOf("id" to WireValue.Text(item.id))),
                            )
                        }
                        true
                    }
                    popup.show()
                    true
                }
                else -> null
            },
        )
        view.isLongClickable = draggable || menuItems.isNotEmpty()

        view.setOnDragListener(
            if (dropEnabled || draggable) {
                View.OnDragListener { _, event ->
                    if (
                        BuildConfig.DEBUG &&
                        (event.action == DragEvent.ACTION_DRAG_STARTED ||
                            event.action == DragEvent.ACTION_DROP ||
                            event.action == DragEvent.ACTION_DRAG_ENDED) &&
                        Log.isLoggable(INTERACTION_LOG_TAG, Log.DEBUG)
                    ) {
                        Log.d(
                            INTERACTION_LOG_TAG,
                            "${state.id}: drag action=${event.action} dropEnabled=$dropEnabled draggable=$draggable",
                        )
                    }
                    when (event.action) {
                        DragEvent.ACTION_DRAG_STARTED ->
                            dropEnabled && event.clipDescription?.hasMimeType("text/plain") == true
                                || draggable
                        DragEvent.ACTION_DROP -> {
                            if (!dropEnabled) return@OnDragListener false
                            val data = event.clipData?.getItemAt(0)?.coerceToText(context)?.toString()
                                ?: event.localState?.toString().orEmpty()
                            if (state.properties[PropKey.ON_DROP] != null) {
                                dispatchBytes(
                                    state.id,
                                    EventKind.DROP.value,
                                    WireMap.encode(mapOf("data" to WireValue.Text(data))),
                                )
                            }
                            true
                        }
                        DragEvent.ACTION_DRAG_ENDED -> {
                            if (draggable && state.properties[PropKey.ON_DRAG_END] != null) {
                                dispatch(state.id, EventKind.DRAG_END.value)
                            }
                            true
                        }
                        else -> true
                    }
                }
            } else {
                null
            },
        )
    }

    private fun decodeContextMenuItems(value: PropValue?): List<NativeMenuItem> {
        val buffer = (value as? PropValue.Bytes)?.value?.duplicate() ?: return emptyList()
        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
        return runCatching {
            val values = JSONArray(bytes.toString(Charsets.UTF_8))
            List(values.length().coerceAtMost(64)) { index ->
                values.getJSONObject(index).let {
                    NativeMenuItem(
                        id = it.getString("id"),
                        title = it.getString("title"),
                        disabled = it.optBoolean("disabled"),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private data class NativeMenuItem(
        val id: String,
        val title: String,
        val disabled: Boolean,
    )

    private fun configureGestureNavigation(view: View, state: NodeState) {
        val navigation = view as? PamNavigationHost ?: return
        navigation.setGestureNavigation(
            enabled = state.flag(PropKey.NAVIGATION_GESTURE_ENABLED, true),
            edgeWidth = state.number(PropKey.NAVIGATION_GESTURE_EDGE_WIDTH, 24.0).toFloat(),
            threshold = state.number(PropKey.NAVIGATION_GESTURE_THRESHOLD, 0.35).toFloat(),
            direction = state.integer(PropKey.NAVIGATION_GESTURE_DIRECTION, 1).toInt(),
            fullScreen = state.flag(PropKey.NAVIGATION_FULL_SCREEN_GESTURE_ENABLED, false),
            onPop = state.callback(PropKey.ON_NAVIGATION_GESTURE_POP) {
                dispatch(state.id, EventKind.NAVIGATION_GESTURE_POP.value)
            },
            onTransitionEnd = state.callback(PropKey.ON_ANIMATION_COMPLETE) {
                dispatch(state.id, EventKind.ANIMATION_COMPLETE.value)
            },
            onGestureStart = state.callback(PropKey.ON_GESTURE_BEGIN) {
                dispatch(state.id, EventKind.GESTURE_BEGIN.value)
            },
            onGestureEnd = state.callback(PropKey.ON_GESTURE_END) {
                dispatch(state.id, EventKind.GESTURE_END.value)
            },
            onGestureCancel = state.callback(PropKey.ON_GESTURE_CANCEL) {
                dispatch(state.id, EventKind.GESTURE_CANCEL.value)
            },
        )
    }

    private fun configureNavigationChrome(view: View, state: NodeState) {
        val navigation = view as? PamNavigationHost ?: return
        navigation.headerSearchEnabled = state.flag(PropKey.NAVIGATION_HEADER_SEARCH_ENABLED, false)
        navigation.headerSearchPlaceholder = state.textOrNull(PropKey.NAVIGATION_HEADER_SEARCH_PLACEHOLDER) ?: "Search"
        navigation.onSearchChange = if (state.properties[PropKey.ON_CHANGE] != null) {
            { text -> dispatch(state.id, EventKind.CHANGE.value, text) }
        } else null
    }

    private fun configureNavigationPresentation(view: View, state: NodeState) {
        val navigation = view as? PamNavigationHost ?: return
        navigation.screenPresentation = state.integer(PropKey.NAVIGATION_PRESENTATION, 1).toInt()
        navigation.sheetDetents = state.textOrNull(PropKey.NAVIGATION_SHEET_DETENTS)
            ?.split(',')
            ?.mapNotNull(String::toFloatOrNull)
            ?.take(3)
            .orEmpty()
        navigation.sheetInitialDetentIndex = state.integer(
            PropKey.NAVIGATION_SHEET_INITIAL_DETENT_INDEX,
            1,
        ).toInt()
        navigation.sheetCornerRadius = state.number(PropKey.NAVIGATION_SHEET_CORNER_RADIUS, 0.0).toFloat()
    }

    private fun configureTabHost(view: View, state: NodeState) {
        val tabs = view as? PamTabHost ?: return
        tabs.configure(
            encodedItems = state.textOrNull(PropKey.TAB_ITEMS) ?: "[]",
            selectedIndex = state.integer(PropKey.TAB_SELECTED_INDEX, 1).toInt(),
            position = state.integer(PropKey.TAB_POSITION, 1).toInt(),
            activeColor = state.integer(PropKey.TAB_ACTIVE_COLOR, 0xFF000000).toInt(),
            inactiveColor = state.integer(PropKey.TAB_INACTIVE_COLOR, 0xFF777777).toInt(),
            barColor = state.integer(PropKey.TAB_BACKGROUND_COLOR, 0xFFFFFFFF).toInt(),
            indicatorColor = state.integer(PropKey.TAB_INDICATOR_COLOR, 0xFF000000).toInt(),
            swipeEnabled = state.flag(PropKey.TAB_SWIPE_ENABLED, false),
        )
        tabs.onSelect = if (state.properties[PropKey.ON_CHANGE] != null) {
            { index -> dispatch(state.id, EventKind.CHANGE.value, index.toString()) }
        } else null
    }

    private fun configureKeyframeAnimation(view: View, state: NodeState) {
        state.workletAnimator?.cancel()
        state.workletAnimator = null
        val bytes = (state.properties[PropKey.ANIMATION_KEYFRAMES] as? PropValue.Bytes)
            ?.value
            ?.duplicate()
            ?.let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
            ?: return
        val frames = runCatching {
            val array = JSONArray(bytes.toString(Charsets.UTF_8))
            List(array.length()) { index ->
                val value = array.getJSONObject(index)
                NativeKeyframe(
                    offset = value.getDouble("offset").toFloat().coerceIn(0f, 1f),
                    opacity = value.optDoubleOrNull("opacity"),
                    translationX = value.optDoubleOrNull("translationX")?.let {
                        dp(it).toFloat()
                    },
                    translationXPercent = value.optDoubleOrNull("translationXPercent"),
                    translationY = value.optDoubleOrNull("translationY")?.let {
                        dp(it).toFloat()
                    },
                    scaleX = value.optDoubleOrNull("scaleX"),
                    scaleY = value.optDoubleOrNull("scaleY"),
                    rotation = value.optDoubleOrNull("rotation"),
                    easing = if (value.has("easing") && !value.isNull("easing")) {
                        value.getString("easing").takeIf(String::isNotEmpty)?.let(PamEasings::parse)
                    } else {
                        null
                    },
                )
            }
        }.getOrNull()?.takeIf { it.size in 2..64 } ?: return

        val playState = state.integer(PropKey.ANIMATION_PLAY_STATE, 1L).toInt()
        if (playState == 2) {
            state.keyframeAnimator?.pause()
            return
        }
        state.keyframeAnimator?.cancel()
        state.keyframeAnimator = null
        if (playState == 3) return

        val iterations = state.integer(PropKey.ANIMATION_ITERATIONS, 1L).coerceIn(0L, 10_000L)
        if (PamMotionPolicy.isReduced(view.context)) {
            applyKeyframe(view, frames, 1f)
            if (iterations != 0L && state.properties[PropKey.ON_ANIMATION_COMPLETE] != null) {
                dispatch(state.id, EventKind.ANIMATION_COMPLETE.value)
            }
            return
        }
        state.keyframeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = state.integer(PropKey.ANIMATION_DURATION_MS, 300L).coerceIn(1L, 60_000L)
            startDelay = state.integer(PropKey.ANIMATION_DELAY_MS, 0L).coerceIn(0L, 60_000L)
            repeatCount = if (iterations == 0L) ValueAnimator.INFINITE else iterations.toInt() - 1
            repeatMode = if (state.flag(PropKey.ANIMATION_AUTO_REVERSE, false)) {
                ValueAnimator.REVERSE
            } else {
                ValueAnimator.RESTART
            }
            interpolator = animationInterpolator(
                state.integer(PropKey.ANIMATION_EASING, 1L).toInt(),
            )
            addUpdateListener { applyKeyframe(view, frames, it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || repeatCount == ValueAnimator.INFINITE) return
                    if (state.properties[PropKey.ON_ANIMATION_COMPLETE] != null) {
                        dispatch(state.id, EventKind.ANIMATION_COMPLETE.value)
                    }
                }
            })
            start()
        }
    }

    /** Tail/head/middle/clip, or 5 = native single-line marquee (RN audio-name ticker). */
    private fun applyTextEllipsize(text: TextView, mode: Int) {
        if (mode == TEXT_ELLIPSIZE_MARQUEE) {
            text.isSingleLine = true
            text.setHorizontallyScrolling(true)
            text.marqueeRepeatLimit = -1
            text.ellipsize = TextUtils.TruncateAt.MARQUEE
            text.isSelected = true
            return
        }
        if (text.ellipsize == TextUtils.TruncateAt.MARQUEE) {
            text.isSelected = false
            text.setHorizontallyScrolling(false)
        }
        text.ellipsize = textEllipsize(mode)
    }

    /**
     * RN `onTextLayout`: total wrapped line count at the current width (even
     * when `numberOfLines` truncates), visible lines, truncation and per-line
     * widths. Reported only when the measurement changes.
     */
    private fun installTextLayout(text: TextView, state: NodeState) {
        state.textLayoutListener?.let(text::removeOnLayoutChangeListener)
        state.textLayoutListener = null
        if (state.properties[PropKey.ON_TEXT_LAYOUT] == null) {
            state.textLayoutSignature = ""
            return
        }
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            reportTextLayout(text, state)
        }
        text.addOnLayoutChangeListener(listener)
        state.textLayoutListener = listener
        if (text.isLaidOut) text.post { reportTextLayout(text, state) }
    }

    private fun reportTextLayout(text: TextView, state: NodeState) {
        if (nodes[state.id] !== state || state.properties[PropKey.ON_TEXT_LAYOUT] == null) return
        val visible = text.layout ?: return
        val width = (text.width - text.totalPaddingLeft - text.totalPaddingRight).coerceAtLeast(0)
        if (width == 0) return
        val content = text.text ?: ""
        val full = android.text.StaticLayout.Builder
            .obtain(content, 0, content.length, text.paint, width)
            .setLineSpacing(text.lineSpacingExtra, text.lineSpacingMultiplier)
            .setIncludePad(text.includeFontPadding)
            .setBreakStrategy(text.breakStrategy)
            .setHyphenationFrequency(text.hyphenationFrequency)
            .setAlignment(visible.alignment)
            .build()
        val totalLines = full.lineCount
        val maxLines = text.maxLines.takeIf { it in 1 until Int.MAX_VALUE } ?: Int.MAX_VALUE
        val visibleLines = min(visible.lineCount, maxLines)
        val truncated = totalLines > visibleLines ||
            (visibleLines > 0 && visible.getEllipsisCount(visibleLines - 1) > 0)
        val density = resourcesDensity().coerceAtLeast(0.01f)
        val widths = (0 until min(totalLines, MAX_TEXT_LAYOUT_LINES)).joinToString(",", "[", "]") {
            "%.2f".format(java.util.Locale.ROOT, full.getLineWidth(it) / density)
        }
        val signature = "$totalLines|$visibleLines|$truncated|$width|$widths"
        if (signature == state.textLayoutSignature) return
        state.textLayoutSignature = signature
        dispatchBytes(
            state.id,
            EventKind.TEXT_LAYOUT.value,
            WireMap.encode(
                mapOf(
                    "lines" to WireValue.Integer(totalLines.toLong()),
                    "visibleLines" to WireValue.Integer(visibleLines.toLong()),
                    "truncated" to WireValue.Flag(truncated),
                    "width" to WireValue.Decimal((width / density).toDouble()),
                    "height" to WireValue.Decimal((full.height / density).toDouble()),
                    "lineWidths" to WireValue.Text(widths),
                ),
            ),
        )
    }

    private fun applyDragSnap(view: View, state: NodeState) {
        val pressable = view as? PamPressable ?: return
        val request = state.integer(PropKey.GESTURE_DRAG_SNAP_INDEX, -1L)
        if (request < 0L || request == state.dragSnapRequest) return
        state.dragSnapRequest = request
        pressable.drag.snapTo((request % DRAG_SNAP_REQUEST_STRIDE).toInt(), animated = view.isLaidOut)
    }

    /**
     * Plays a `pam-motion` program. A program id that is already playing (or
     * already played) is not restarted, so re-rendering an unchanged
     * Animation value never replays it; a new Animation instance does.
     */
    private fun configureMotionProgram(view: View, state: NodeState, force: Boolean = false) {
        val source = (state.properties[PropKey.ANIMATION_PROGRAM] as? PropValue.Text)?.value ?: return
        val program = PamMotionProgram.parse(source) ?: return
        if (!force && program.id == state.motionProgramId) return
        state.motionProgramId = program.id
        state.motionRunner?.cancel()
        val start = start@{
            if (nodes[state.id] !== state) return@start
            val timeline = PamMotionTimeline.build(
                program,
                current = { PamMotionTarget.read(view, it) },
                resolve = { property, value -> PamMotionTarget.resolve(view, property, value) },
            )
            state.motionRunner = PamMotionRunner(
                view,
                timeline,
                program.iterations,
                onComplete = {
                    state.motionRunner = null
                    if (nodes[state.id] === state && state.properties[PropKey.ON_ANIMATION_COMPLETE] != null) {
                        dispatch(state.id, EventKind.ANIMATION_COMPLETE.value, program.id.toString())
                    }
                },
            ).also { it.start(PamMotionPolicy.isReduced(view.context)) }
        }
        // Percentages resolve against the laid-out size.
        if (view.isLaidOut) start() else view.post { start() }
    }

    private fun restartAnimations(view: View, state: NodeState) {
        val key = state.integer(PropKey.ANIMATION_RESTART_KEY, 0L)
        if (key == state.motionRestartKey) return
        val first = state.motionRestartKey == Long.MIN_VALUE
        state.motionRestartKey = key
        if (first) return
        if (state.properties[PropKey.ANIMATION_PROGRAM] != null) {
            configureMotionProgram(view, state, force = true)
        }
        if (state.properties[PropKey.ANIMATION_KEYFRAMES] != null) {
            configureKeyframeAnimation(view, state)
        }
    }

    /** Per-property CSS transition (duration, delay, easing or spring). */
    private fun animateWithTransitionRule(
        view: View,
        state: NodeState,
        key: PropKey,
        target: Float,
    ): Boolean {
        if (state.transitionRules.isEmpty()) return false
        val property = when (key) {
            PropKey.OPACITY -> PamMotionProperty.OPACITY
            PropKey.TRANSLATION_X -> PamMotionProperty.TRANSLATE_X
            PropKey.TRANSLATION_Y -> PamMotionProperty.TRANSLATE_Y
            PropKey.SCALE_X -> PamMotionProperty.SCALE_X
            PropKey.SCALE_Y -> PamMotionProperty.SCALE_Y
            PropKey.ROTATION -> PamMotionProperty.ROTATE
            else -> return false
        }
        val rule = PamTransitionSpec.ruleFor(state.transitionRules, property) ?: return false
        if (rule.spring == null && rule.durationMs == 0L && rule.delayMs == 0L) {
            state.transitionRunners?.remove(property)?.cancel()
            setAnimatedProperty(view, key, target)
            return true
        }
        val runners = state.transitionRunners ?: mutableMapOf<PamMotionProperty, PamMotionRunner>().also {
            state.transitionRunners = it
        }
        runners.remove(property)?.cancel()
        val viewAnimator = view.animate()
        when (property) {
            PamMotionProperty.OPACITY -> viewAnimator.alpha(view.alpha)
            PamMotionProperty.TRANSLATE_X -> viewAnimator.translationX(view.translationX)
            PamMotionProperty.TRANSLATE_Y -> viewAnimator.translationY(view.translationY)
            PamMotionProperty.SCALE_X -> viewAnimator.scaleX(view.scaleX)
            PamMotionProperty.SCALE_Y -> viewAnimator.scaleY(view.scaleY)
            PamMotionProperty.ROTATE -> viewAnimator.rotation(view.rotation)
            else -> Unit
        }
        viewAnimator.setDuration(0L).start()
        val step = if (rule.spring != null) {
            PamMotionStep.Spring(PamMotionValue(target.toDouble()), rule.spring, 0.0, rule.delayMs)
        } else {
            PamMotionStep.Timing(PamMotionValue(target.toDouble()), rule.durationMs, rule.easing, rule.delayMs)
        }
        val timeline = PamMotionTimeline.build(
            PamMotionProgram(0L, 1, listOf(mapOf(property to listOf(step)))),
            current = { PamMotionTarget.read(view, it) },
            resolve = { _, value -> value.number },
        )
        val runner = PamMotionRunner(view, timeline, 1, onComplete = {
            state.transitionRunners?.remove(property)
            Unit
        })
        runners[property] = runner
        runner.start(reducedMotion = false)
        return true
    }

    private fun configureWorkletAnimation(view: View, state: NodeState) {
        state.workletAnimator?.cancel()
        state.workletAnimator = null
        val bytes = (state.properties[PropKey.WORKLET_PROGRAM] as? PropValue.Bytes)
            ?.value
            ?.duplicate()
            ?.let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
            ?: return
        val program = PamWorkletProgram.decode(bytes) ?: return
        val target = PamWorkletTarget.from(
            state.integer(PropKey.WORKLET_TARGET, 0).toInt(),
        ) ?: return
        val durationMs = state.integer(PropKey.WORKLET_DURATION_MS, 300)
            .coerceIn(1, 60_000)
        val iterations = state.integer(PropKey.WORKLET_ITERATIONS, 1)
            .coerceIn(0, 10_000)
        state.keyframeAnimator?.cancel()
        state.keyframeAnimator = null

        fun apply(inputMs: Double) {
            val value = program.evaluate(inputMs) ?: return
            when (target) {
                PamWorkletTarget.OPACITY -> view.alpha = value.toFloat().coerceIn(0f, 1f)
                PamWorkletTarget.TRANSLATION_X ->
                    view.translationX = dp(value.coerceIn(-1_000_000.0, 1_000_000.0).toFloat()).toFloat()
                PamWorkletTarget.TRANSLATION_Y ->
                    view.translationY = dp(value.coerceIn(-1_000_000.0, 1_000_000.0).toFloat()).toFloat()
                PamWorkletTarget.SCALE -> {
                    val scale = value.toFloat().coerceIn(-100f, 100f)
                    view.scaleX = scale
                    view.scaleY = scale
                }
                PamWorkletTarget.ROTATION_DEGREES -> {
                    view.rotation = value.toFloat().coerceIn(-36_000f, 36_000f)
                }
            }
        }
        if (PamMotionPolicy.isReduced(view.context)) {
            apply(durationMs.toDouble())
            if (iterations != 0L && state.properties[PropKey.ON_ANIMATION_COMPLETE] != null) {
                dispatch(state.id, EventKind.ANIMATION_COMPLETE.value)
            }
            return
        }
        state.workletAnimator = ValueAnimator.ofFloat(0f, durationMs.toFloat()).apply {
            duration = durationMs
            repeatCount = if (iterations == 0L) ValueAnimator.INFINITE else iterations.toInt() - 1
            interpolator = LinearInterpolator()
            addUpdateListener { apply((it.animatedValue as Float).toDouble()) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled && repeatCount != ValueAnimator.INFINITE &&
                        state.properties[PropKey.ON_ANIMATION_COMPLETE] != null
                    ) {
                        dispatch(state.id, EventKind.ANIMATION_COMPLETE.value)
                    }
                }
            })
            start()
        }
    }

    private fun applyKeyframe(view: View, frames: List<NativeKeyframe>, progress: Float) {
        val rightIndex = frames.indexOfFirst { it.offset >= progress }
            .let { if (it < 0) frames.lastIndex else it }
        val leftIndex = (rightIndex - 1).coerceAtLeast(0)
        val left = frames[leftIndex]
        val right = frames[rightIndex]
        val local = if (right.offset == left.offset) 0f else {
            ((progress - left.offset) / (right.offset - left.offset)).coerceIn(0f, 1f)
        }.let { linear -> left.easing?.transform(linear) ?: linear }
        fun value(
            start: Float?,
            end: Float?,
            fallback: Float,
        ): Float {
            val from = start ?: end ?: fallback
            val to = end ?: start ?: fallback
            return from + (to - from) * local
        }
        view.alpha = value(left.opacity, right.opacity, view.alpha).coerceIn(0f, 1f)
        fun translationX(frame: NativeKeyframe): Float? =
            frame.translationXPercent?.let { percent ->
                ((view.parent as? View)?.width ?: view.rootView.width) * (percent / 100f)
            } ?: frame.translationX
        view.translationX = value(
            translationX(left),
            translationX(right),
            view.translationX,
        )
        view.translationY = value(left.translationY, right.translationY, view.translationY)
        view.scaleX = value(left.scaleX, right.scaleX, view.scaleX)
        view.scaleY = value(left.scaleY, right.scaleY, view.scaleY)
        view.rotation = value(left.rotation, right.rotation, view.rotation)
    }

    private fun animationInterpolator(value: Int): android.animation.TimeInterpolator =
        when (value) {
            1 -> LinearInterpolator()
            2 -> AccelerateInterpolator()
            3 -> DecelerateInterpolator()
            5 -> OvershootInterpolator()
            else -> AccelerateDecelerateInterpolator()
        }

    private fun org.json.JSONObject.optDoubleOrNull(name: String): Float? =
        if (has(name) && !isNull(name)) getDouble(name).toFloat() else null

    private data class NativeKeyframe(
        val offset: Float,
        val opacity: Float?,
        val translationX: Float?,
        val translationXPercent: Float?,
        val translationY: Float?,
        val scaleX: Float?,
        val scaleY: Float?,
        val rotation: Float?,
        val easing: PamEasing? = null,
    )

    private enum class NativeStyleState(val value: Int) {
        PRESSED(1),
        FOCUSED(2),
        HOVERED(3),
        DISABLED(4),
        CHECKED(5),
        SELECTED(6),
        ACTIVE(7),
        LOADING(8),
        ERROR(9),
    }

    private fun nativeStateDeclarations(state: NodeState, kind: NativeStyleState): org.json.JSONObject? {
        val source = (state.properties[PropKey.NATIVE_STATE_STYLES] as? PropValue.Text)?.value
            ?: return null
        return try {
            org.json.JSONObject(source).optJSONObject(kind.value.toString())
        } catch (_: org.json.JSONException) {
            null
        }
    }

    private fun applyNativeStyleState(
        view: View,
        state: NodeState,
        kind: NativeStyleState,
        active: Boolean,
    ) {
        val styles = nativeStateDeclarations(state, kind) ?: return
        if (!active) {
            view.alpha = state.targetAlpha()
            view.scaleX = state.targetScaleX()
            view.scaleY = state.targetScaleY()
            view.translationX = state.number(PropKey.TRANSLATION_X, 0.0).toFloat() * resourcesDensity()
            view.translationY = state.number(PropKey.TRANSLATION_Y, 0.0).toFloat() * resourcesDensity()
            view.elevation = dp(state.number(PropKey.ELEVATION, 0.0).toFloat()).toFloat()
            updateBackground(view, state)
            state.properties[PropKey.TEXT_COLOR]?.let { color ->
                (view as? TextView)?.let { applySemanticTextColor(it, color.integer().toInt()) }
            }
            return
        }
        var backgroundColorOverride: Int? = null
        var borderColorOverride: Int? = null
        val keys = styles.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            val key = rawKey.toIntOrNull()?.let(PropKey::from) ?: continue
            when (key) {
                PropKey.OPACITY -> view.alpha = styles.optDouble(rawKey, view.alpha.toDouble()).toFloat()
                PropKey.SCALE_X -> view.scaleX = styles.optDouble(rawKey, view.scaleX.toDouble()).toFloat()
                PropKey.SCALE_Y -> view.scaleY = styles.optDouble(rawKey, view.scaleY.toDouble()).toFloat()
                // Translations are authored in points (React Native transform).
                PropKey.TRANSLATION_X -> styles.optDouble(rawKey).takeIf { !it.isNaN() }?.let {
                    view.translationX = it.toFloat() * resourcesDensity()
                }
                PropKey.TRANSLATION_Y -> styles.optDouble(rawKey).takeIf { !it.isNaN() }?.let {
                    view.translationY = it.toFloat() * resourcesDensity()
                }
                PropKey.BACKGROUND_COLOR -> backgroundColorOverride = styles.optLong(rawKey).toInt()
                PropKey.BORDER_COLOR -> borderColorOverride = styles.optLong(rawKey).toInt()
                PropKey.TEXT_COLOR -> (view as? TextView)?.let {
                    applySemanticTextColor(it, styles.optLong(rawKey).toInt())
                }
                PropKey.ELEVATION -> view.elevation = dp(styles.optDouble(rawKey).toFloat()).toFloat()
                else -> Unit
            }
        }
        if (backgroundColorOverride != null || borderColorOverride != null) {
            updateBackground(
                view = view,
                state = state,
                backgroundColorOverride = backgroundColorOverride,
                borderColorOverride = borderColorOverride,
            )
        }
    }

    private fun configureNativeStateStyles(view: View, state: NodeState) {
        if (view !is EditText) {
            view.onFocusChangeListener = View.OnFocusChangeListener { focusedView, focused ->
                applyNativeStyleState(focusedView, state, NativeStyleState.FOCUSED, focused)
                if (focused) {
                    if (state.properties[PropKey.ON_FOCUS] != null) dispatch(state.id, EVENT_FOCUS)
                } else if (state.properties[PropKey.ON_BLUR] != null) {
                    dispatch(state.id, EVENT_BLUR)
                }
            }
        }
        view.setOnHoverListener { hoveredView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER -> applyNativeStyleState(
                    hoveredView,
                    state,
                    NativeStyleState.HOVERED,
                    true,
                )
                MotionEvent.ACTION_HOVER_EXIT -> applyNativeStyleState(
                    hoveredView,
                    state,
                    NativeStyleState.HOVERED,
                    false,
                )
            }
            false
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installPressFeedback(view: View, state: NodeState) {
        if (
            view is PamPressable ||
            state.kind != NodeKind.PRESSABLE &&
            state.kind != NodeKind.BUTTON
        ) {
            return
        }
        val hasTouchDirective =
            state.properties[PropKey.ON_TOUCH_START] != null ||
                state.properties[PropKey.ON_TOUCH_MOVE] != null ||
                state.properties[PropKey.ON_TOUCH_END] != null
        if (
            state.properties[PropKey.ON_PRESS] == null &&
            state.properties[PropKey.ON_LONG_PRESS] == null &&
            !hasTouchDirective
        ) {
            view.setOnTouchListener(null)
            return
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    applyNativeStyleState(view, state, NativeStyleState.PRESSED, true)
                    if (PamMotionPolicy.isReduced(view.context)) {
                        view.animate().cancel()
                        view.alpha = state.pressOpacity
                        view.scaleX = state.targetScaleX() * state.pressScale
                        view.scaleY = state.targetScaleY() * state.pressScale
                    } else {
                        view.animate()
                            .alpha(state.pressOpacity)
                            .scaleX(state.targetScaleX() * state.pressScale)
                            .scaleY(state.targetScaleY() * state.pressScale)
                            .setDuration(70)
                            .start()
                    }
                    dispatchDirectiveTouch(state, EventKind.TOUCH_START.value, event)
                }
                MotionEvent.ACTION_MOVE ->
                    dispatchDirectiveTouch(state, EventKind.TOUCH_MOVE.value, event)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    if (PamMotionPolicy.isReduced(view.context)) {
                        view.animate().cancel()
                        view.alpha = state.targetAlpha()
                        view.scaleX = state.targetScaleX()
                        view.scaleY = state.targetScaleY()
                    } else {
                        view.animate()
                            .alpha(state.targetAlpha())
                            .scaleX(state.targetScaleX())
                            .scaleY(state.targetScaleY())
                            .setDuration(110)
                            .start()
                    }
                    applyNativeStyleState(view, state, NativeStyleState.PRESSED, false)
                    dispatchDirectiveTouch(state, EventKind.TOUCH_END.value, event)
                }
            }
            false
        }
    }

    private fun installDirectiveEvents(view: View, state: NodeState) {
        state.directiveLayoutListener?.let(view::removeOnLayoutChangeListener)
        state.directiveLayoutListener = null
        state.intersectionObserver?.close(notify = false)
        state.intersectionObserver = null
        state.outsidePointerObserver?.let { observer ->
            (host as? PamRootHost)?.removePointerObserver(observer)
        }
        state.outsidePointerObserver = null
        if (state.properties[PropKey.ON_INTERSECT] != null) {
            state.intersectionObserver = PamIntersectionObserver(view) { intersecting ->
                if (nodes[state.id] === state && state.lastDirectiveIntersection != intersecting) {
                    state.lastDirectiveIntersection = intersecting
                    dispatch(state.id, EventKind.INTERSECT.value, if (intersecting) "1" else "0")
                }
            }
        } else {
            state.lastDirectiveIntersection = null
        }
        if (
            state.properties[PropKey.ON_RESIZE] == null &&
            state.properties[PropKey.ON_MUTATE] == null
        ) {
            if (state.properties[PropKey.ON_CLICK_OUTSIDE] == null) return
        }

        if (state.properties[PropKey.ON_CLICK_OUTSIDE] != null) {
            val observer: (MotionEvent) -> Unit = observer@{ event ->
                if (nodes[state.id] !== state || !view.isShown) return@observer
                val location = IntArray(2)
                val hostLocation = IntArray(2)
                view.getLocationOnScreen(location)
                host.getLocationOnScreen(hostLocation)
                val pageX = event.x + hostLocation[0]
                val pageY = event.y + hostLocation[1]
                val inside =
                    pageX >= location[0] &&
                        pageX < location[0] + view.width &&
                        pageY >= location[1] &&
                        pageY < location[1] + view.height
                if (!inside) {
                    val density = resourcesDensity().coerceAtLeast(0.01f)
                    dispatchBytes(
                        state.id,
                        EventKind.CLICK_OUTSIDE.value,
                        WireMap.encode(
                            mapOf(
                                "pageX" to WireValue.Decimal(pageX / density.toDouble()),
                                "pageY" to WireValue.Decimal(pageY / density.toDouble()),
                            ),
                        ),
                    )
                }
            }
            state.outsidePointerObserver = observer
            (host as? PamRootHost)?.addPointerObserver(observer)
        }

        if (
            state.properties[PropKey.ON_RESIZE] == null &&
            state.properties[PropKey.ON_MUTATE] == null
        ) {
            return
        }

        val listener = View.OnLayoutChangeListener {
                target,
                left,
                top,
                right,
                bottom,
                oldLeft,
                oldTop,
                oldRight,
                oldBottom,
            ->
            val density = resourcesDensity().coerceAtLeast(0.01f)
            val width = right - left
            val height = bottom - top
            if (
                state.properties[PropKey.ON_RESIZE] != null &&
                (width != oldRight - oldLeft || height != oldBottom - oldTop)
            ) {
                dispatchBytes(
                    state.id,
                    EventKind.RESIZE.value,
                    WireMap.encode(
                        mapOf(
                            "width" to WireValue.Decimal(width / density.toDouble()),
                            "height" to WireValue.Decimal(height / density.toDouble()),
                        ),
                    ),
                )
            }
            if (
                state.properties[PropKey.ON_MUTATE] != null &&
                (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)
            ) {
                dispatchBytes(
                    state.id,
                    EventKind.MUTATE.value,
                    WireMap.encode(
                        mapOf(
                            "x" to WireValue.Decimal(left / density.toDouble()),
                            "y" to WireValue.Decimal(top / density.toDouble()),
                            "width" to WireValue.Decimal(width / density.toDouble()),
                            "height" to WireValue.Decimal(height / density.toDouble()),
                        ),
                    ),
                )
            }
        }
        state.directiveLayoutListener = listener
        view.addOnLayoutChangeListener(listener)
        view.post {
            if (nodes[state.id] === state) {
                listener.onLayoutChange(
                    view,
                    view.left,
                    view.top,
                    view.right,
                    view.bottom,
                    view.left,
                    view.top,
                    view.left,
                    view.top,
                )
            }
        }
    }

    private fun dispatchDirectiveTouch(state: NodeState, eventKind: Int, event: MotionEvent) {
        val property = when (eventKind) {
            EventKind.TOUCH_START.value -> PropKey.ON_TOUCH_START
            EventKind.TOUCH_MOVE.value -> PropKey.ON_TOUCH_MOVE
            EventKind.TOUCH_END.value -> PropKey.ON_TOUCH_END
            else -> return
        }
        if (state.properties[property] == null) return
        val density = resourcesDensity().coerceAtLeast(0.01f)
        dispatchBytes(
            state.id,
            eventKind,
            WireMap.encode(
                mapOf(
                    "x" to WireValue.Decimal(event.x / density.toDouble()),
                    "y" to WireValue.Decimal(event.y / density.toDouble()),
                    "pageX" to WireValue.Decimal(event.rawX / density.toDouble()),
                    "pageY" to WireValue.Decimal(event.rawY / density.toDouble()),
                    "pointerCount" to WireValue.Integer(event.pointerCount.toLong()),
                ),
            ),
        )
    }

    private fun configurePressable(view: View, state: NodeState) {
        val pressable = view as? PamPressable ?: return
        if (state.applyingInitialProperties) {
            if (state.pressableConfiguredDuringInitialization) return
            state.pressableConfiguredDuringInitialization = true
        }
        pressable.onPressedStateChanged = if (state.properties[PropKey.NATIVE_STATE_STYLES] != null) {
            { pressed -> applyNativeStyleState(pressable, state, NativeStyleState.PRESSED, pressed) }
        } else {
            null
        }
        pressable.configure(
            pressOpacity = state.pressOpacity,
            pressScale = state.pressScale,
            targetOpacity = state.targetAlpha(),
            targetScaleX = state.targetScaleX(),
            targetScaleY = state.targetScaleY(),
            delayLongPressMs = state.integer(
                PropKey.PRESS_DELAY_LONG_MS,
                ViewConfiguration.getLongPressTimeout().toLong(),
            ),
            delayPressInMs = state.integer(PropKey.PRESS_DELAY_IN_MS, 0L),
            delayPressOutMs = state.integer(PropKey.PRESS_DELAY_OUT_MS, 0L),
            retentionLeft = dp(
                state.number(PropKey.PRESS_RETENTION_LEFT, 20.0).toFloat(),
            ).toFloat(),
            retentionTop = dp(
                state.number(PropKey.PRESS_RETENTION_TOP, 20.0).toFloat(),
            ).toFloat(),
            retentionRight = dp(
                state.number(PropKey.PRESS_RETENTION_RIGHT, 20.0).toFloat(),
            ).toFloat(),
            retentionBottom = dp(
                state.number(PropKey.PRESS_RETENTION_BOTTOM, 30.0).toFloat(),
            ).toFloat(),
            androidDisableSound = state.flag(PropKey.PRESS_ANDROID_DISABLE_SOUND, false),
        )
        pressable.configureDoubleTap(
            delayMs = state.integer(PropKey.PRESS_DOUBLE_TAP_DELAY_MS, 250L),
            effect = (state.properties[PropKey.PRESS_TAP_EFFECT] as? PropValue.Text)
                ?.value
                ?.let(PamTapEffect::cached),
        )
        val dragConfig = (state.properties[PropKey.GESTURE_DRAG] as? PropValue.Text)
            ?.value
            ?.let(PamDragConfig::cached)
        pressable.configureDrag(dragConfig) { index, position ->
            if (nodes[state.id] === state && state.properties[PropKey.ON_GESTURE_SETTLE] != null) {
                dispatchBytes(
                    state.id,
                    EventKind.GESTURE_SETTLE.value,
                    WireMap.encode(
                        mapOf(
                            "snapIndex" to WireValue.Integer(index.toLong()),
                            "position" to WireValue.Decimal(position),
                        ),
                    ),
                )
            }
        }
        val gestureType = state.integer(PropKey.GESTURE_TYPE, 0L).toInt()
        val hasGesture = gestureType in 1..6
        pressable.configureGesture(
            config = if (hasGesture) {
                PamGestureConfig(
                    type = gestureType,
                    enabled = state.flag(PropKey.GESTURE_ENABLED, true),
                    minPointers = state.integer(PropKey.GESTURE_MIN_POINTERS, 1L)
                        .toInt()
                        .coerceIn(1, 10),
                    maxPointers = state.integer(PropKey.GESTURE_MAX_POINTERS, 1L)
                        .toInt()
                        .coerceIn(1, 10),
                    direction = state.integer(PropKey.GESTURE_DIRECTION, 1L).toInt(),
                    composition = state.integer(PropKey.GESTURE_COMPOSITION, 1L).toInt(),
                    minDistance = dp(
                        state.number(PropKey.GESTURE_MIN_DISTANCE, 12.0).toFloat(),
                    ).toFloat(),
                    minDurationMs = state.integer(PropKey.GESTURE_MIN_DURATION_MS, 0L)
                        .coerceIn(0L, 60_000L),
                )
            } else {
                null
            },
            callback = if (hasGesture) {
                { payload -> dispatchGesture(state, payload) }
            } else {
                null
            },
            nativeTransform = state.flag(PropKey.GESTURE_NATIVE_TRANSFORM, false),
            nativeMinScale = state.number(PropKey.GESTURE_NATIVE_MIN_SCALE, 1.0).toFloat(),
            nativeMaxScale = state.number(PropKey.GESTURE_NATIVE_MAX_SCALE, 4.0).toFloat(),
            nativeResetKey = state.integer(PropKey.GESTURE_NATIVE_RESET_KEY, 0L),
            nativeTranslationLimitX = dp(
                state.number(PropKey.GESTURE_NATIVE_TRANSLATION_LIMIT_X, 0.0).toFloat(),
            ).toFloat(),
            nativeResetOnEnd = state.flag(PropKey.GESTURE_NATIVE_RESET_ON_END, false),
        )
    }

    private fun dispatchGesture(state: NodeState, payload: PamGesturePayload) {
        if (nodes[state.id] !== state) return
        val event = when (payload.state) {
            1 -> EventKind.GESTURE_BEGIN to PropKey.ON_GESTURE_BEGIN
            2 -> EventKind.GESTURE_UPDATE to PropKey.ON_GESTURE_UPDATE
            3 -> EventKind.GESTURE_END to PropKey.ON_GESTURE_END
            else -> EventKind.GESTURE_CANCEL to PropKey.ON_GESTURE_CANCEL
        }
        if (state.properties[event.second] == null) return
        val density = resourcesDensity().coerceAtLeast(0.01f)
        dispatchBytes(
            state.id,
            event.first.value,
            WireMap.encode(
                mapOf(
                    "type" to WireValue.Integer(payload.type.toLong()),
                    "state" to WireValue.Integer(payload.state.toLong()),
                    "x" to WireValue.Decimal((payload.x / density).toDouble()),
                    "y" to WireValue.Decimal((payload.y / density).toDouble()),
                    "pageX" to WireValue.Decimal((payload.pageX / density).toDouble()),
                    "pageY" to WireValue.Decimal((payload.pageY / density).toDouble()),
                    "translationX" to WireValue.Decimal(
                        (payload.translationX / density).toDouble(),
                    ),
                    "translationY" to WireValue.Decimal(
                        (payload.translationY / density).toDouble(),
                    ),
                    "velocityX" to WireValue.Decimal(
                        (payload.velocityX / density).toDouble(),
                    ),
                    "velocityY" to WireValue.Decimal(
                        (payload.velocityY / density).toDouble(),
                    ),
                    "scale" to WireValue.Decimal(payload.scale.toDouble()),
                    "rotation" to WireValue.Decimal(payload.rotation.toDouble()),
                    "pointerCount" to WireValue.Integer(payload.pointerCount.toLong()),
                    "timestamp" to WireValue.Integer(payload.timestamp),
                    "snapIndex" to WireValue.Integer(payload.snapIndex.toLong()),
                    "thresholdReached" to WireValue.Flag(payload.thresholdReached),
                ).toMutableMap().apply {
                    payload.nativeScale?.let { put("nativeScale", WireValue.Decimal(it.toDouble())) }
                    payload.nativeTranslationX?.let { put("nativeTranslationX", WireValue.Decimal(it / density.toDouble())) }
                    payload.nativeTranslationY?.let { put("nativeTranslationY", WireValue.Decimal(it / density.toDouble())) }
                },
            ),
        )
    }

    private fun dispatchPressPointer(
        state: NodeState,
        event: Int,
        pointer: PamPressPointer,
    ) {
        if (nodes[state.id] !== state) return
        val density = resourcesDensity().coerceAtLeast(0.01f)
        dispatchBytes(
            state.id,
            event,
            WireMap.encode(
                mapOf(
                    "x" to WireValue.Decimal((pointer.x / density).toDouble()),
                    "y" to WireValue.Decimal((pointer.y / density).toDouble()),
                    "pageX" to WireValue.Decimal((pointer.pageX / density).toDouble()),
                    "pageY" to WireValue.Decimal((pointer.pageY / density).toDouble()),
                    "timestamp" to WireValue.Integer(pointer.timestamp),
                    "pointerId" to WireValue.Integer(pointer.pointerId.toLong()),
                ),
            ),
        )
    }

    private fun installInputEvents(input: EditText, state: NodeState) {
        if (state.properties[PropKey.ON_CHANGE] == null) {
            state.pendingChange?.let(main::removeCallbacks)
            state.pendingChange = null
        }
        if (!state.textWatcherInstalled) {
            state.textWatcherInstalled = true
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {
                    state.inputDeleting = count > after
                    state.inputDigitsBeforeChange = text?.count(Char::isDigit) ?: 0
                }

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(editable: Editable?) {
                    if (state.updating) return
                    var current = editable?.toString().orEmpty()
                    val selection = input.selectionStart.coerceAtLeast(0)
                    // Masked/currency inputs: deleting a literal (separator)
                    // removes the digit before it. Plain inputs must delete
                    // exactly what the IME deleted, or every non-digit
                    // deletion would also drop an unrelated digit.
                    if (
                        state.inputDeleting
                        && state.integer(PropKey.INPUT_FORMAT, INPUT_FORMAT_NONE.toLong()).toInt() !=
                        INPUT_FORMAT_NONE
                        && current.count(Char::isDigit) == state.inputDigitsBeforeChange
                    ) {
                        val removeAt = current.take(selection).count(Char::isDigit) - 1
                        if (removeAt >= 0) {
                            var seen = 0
                            current = current.filter { character ->
                                !character.isDigit() || seen++ != removeAt
                            }
                        }
                    }
                    val formatted = formatInputValue(current, state)
                    if (formatted != current) {
                        val digitOffset = current.take(selection).count(Char::isDigit)
                        val currency = state.integer(
                            PropKey.INPUT_FORMAT,
                            INPUT_FORMAT_NONE.toLong(),
                        ).toInt() == INPUT_FORMAT_CURRENCY
                        state.updating = true
                        // Keep the same Editable/input connection while formatting. Calling
                        // setText() from a TextWatcher restarts the connection and can drop
                        // subsequent key events from a fast IME (or hardware keyboard).
                        editable?.replace(0, editable.length, formatted)
                        val formattedSelection = if (currency) {
                            input.text.length
                        } else {
                            cursorAfterDigits(input.text.toString(), digitOffset)
                        }
                        input.setSelection(
                            formattedSelection.coerceIn(0, input.text.length),
                        )
                        state.updating = false
                    }
                    state.nativeValue = formatted
                    state.deferredInputValue = null
                    state.nativeValueAcknowledged = false
                    if (state.properties[PropKey.ON_CHANGE] == null) return
                    when (state.inputSyncMode()) {
                        INPUT_SYNC_IMMEDIATE -> dispatchInput(state)
                        INPUT_SYNC_DEBOUNCED -> {
                            state.pendingChange?.let(main::removeCallbacks)
                            state.pendingChange = Runnable { dispatchInput(state) }.also { pending ->
                                main.postDelayed(pending, state.inputDebounceMs())
                            }
                        }
                    }
                }
            })
        }
        input.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            applyNativeStyleState(input, state, NativeStyleState.FOCUSED, focused)
            if (focused) {
                lastFocusedInput = input
                var ancestor = input.parent as? View
                while (ancestor != null) {
                    if (ancestor is PamScrollContainer && scrollKeyboardInsets.containsKey(ancestor)) {
                        ancestor.ensureKeyboardTargetVisible(input)
                        break
                    }
                    ancestor = ancestor.parent as? View
                }
                if (state.properties[PropKey.ON_FOCUS] != null) dispatch(state.id, EVENT_FOCUS)
            } else {
                // A normalized authored value may have arrived while editing.
                // Apply it only if no newer keystroke invalidated that value.
                state.deferredInputValue?.let { next ->
                    applyInputValue(input, state, next)
                }
                if (state.inputSyncMode() == INPUT_SYNC_NATIVE || state.inputSyncMode() == INPUT_SYNC_BLUR) {
                    dispatchInput(state)
                }
                if (state.properties[PropKey.ON_BLUR] != null) dispatch(state.id, EVENT_BLUR)
                if (state.properties[PropKey.ON_INPUT_END_EDITING] != null) {
                    dispatch(
                        state.id,
                        EVENT_INPUT_END_EDITING,
                        input.text.toString(),
                    )
                }
            }
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val submitted =
                actionId != EditorInfo.IME_ACTION_NONE &&
                    actionId != EditorInfo.IME_NULL ||
                    (
                        event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.action == KeyEvent.ACTION_UP
                    )
            if (!submitted || inputSubmitBehavior(state) == INPUT_SUBMIT_NEWLINE) {
                return@setOnEditorActionListener false
            }
            if (
                state.inputSyncMode() == INPUT_SYNC_NATIVE ||
                state.inputSyncMode() == INPUT_SYNC_SUBMIT
            ) {
                dispatchInput(state)
            }
            if (state.properties[PropKey.ON_SUBMIT] != null) {
                dispatch(state.id, EVENT_SUBMIT, input.text.toString())
            }
            if (inputSubmitBehavior(state) == INPUT_SUBMIT_BLUR) {
                input.clearFocus()
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(input.windowToken, 0)
            }
            true
        }
        (input as? PamEditText)?.setInputCallbacks(
            selection = if (
                state.properties[PropKey.ON_INPUT_SELECTION_CHANGE] != null
            ) {
                { start, end ->
                    if (!state.updating) {
                        state.inputSelectionStart = start
                        state.inputSelectionEnd = end
                        if (!state.inputSelectionScheduled) {
                            state.inputSelectionScheduled = true
                            Choreographer.getInstance().postFrameCallback {
                                state.inputSelectionScheduled = false
                                if (nodes[state.id] === state) {
                                    dispatchInputSelection(state)
                                }
                            }
                        }
                    }
                }
            } else {
                null
            },
            contentSize = if (
                state.properties[PropKey.ON_INPUT_CONTENT_SIZE_CHANGE] != null
            ) {
                { width, height ->
                    if (nodes[state.id] === state) {
                        dispatchBytes(
                            state.id,
                            EVENT_INPUT_CONTENT_SIZE_CHANGE,
                            WireMap.encode(
                                mapOf(
                                    "width" to WireValue.Decimal(
                                        width / resourcesDensity().toDouble(),
                                    ),
                                    "height" to WireValue.Decimal(
                                        height / resourcesDensity().toDouble(),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            } else {
                null
            },
            key = if (state.properties[PropKey.ON_INPUT_KEY_PRESS] != null) {
                { key ->
                    if (nodes[state.id] === state) {
                        dispatchBytes(
                            state.id,
                            EVENT_INPUT_KEY_PRESS,
                            WireMap.encode(
                                mapOf(
                                    "key" to WireValue.Text(
                                        key.take(MAX_INPUT_KEY_BYTES),
                                    ),
                                ),
                            ),
                        )
                    }
                }
            } else {
                null
            },
        )
    }

    private fun installScrollEvents(scroll: PamScrollContainer, state: NodeState) {
        installScrollPhases(state) { listener -> scroll.setOnScrollPhase(listener) }
        val onScroll = state.properties[PropKey.ON_SCROLL] != null
        val endReached = state.properties[PropKey.ON_END_REACHED] != null
        if (!onScroll && !endReached) {
            scroll.setOnViewportChanged(null)
            return
        }
        scroll.setOnViewportChanged { scrollX, scrollY ->
            if (endReached) {
                val threshold = state.number(PropKey.END_REACHED_THRESHOLD, 0.5).coerceIn(0.0, 1.0)
                val trigger = scroll.primaryViewportPixels() * threshold
                val remaining = scroll.remainingPrimaryPixels()
                if (remaining <= trigger && !state.endReachedSent) {
                    state.endReachedSent = true
                    dispatch(state.id, EVENT_END_REACHED)
                } else if (remaining > trigger) {
                    // Content grew or the user scrolled back: allow the next page request.
                    state.endReachedSent = false
                }
            }
            if (!onScroll) return@setOnViewportChanged
            state.pendingScrollOffset = scroll.primaryOffset(scrollX, scrollY)
            if (!state.scrollScheduled) {
                state.scrollScheduled = true
                Choreographer.getInstance().postFrameCallback {
                    state.scrollScheduled = false
                    if (nodes[state.id] === state) {
                        dispatch(
                            state.id,
                            EVENT_SCROLL,
                            state.pendingScrollOffset.toString(),
                        )
                    }
                }
            }
        }
    }

    private fun installScrollPhases(
        state: NodeState,
        install: (((Int, Float, Float, Float, Float) -> Unit)?) -> Unit,
    ) {
        val wanted = state.properties[PropKey.ON_SCROLL_BEGIN_DRAG] != null ||
            state.properties[PropKey.ON_SCROLL_END_DRAG] != null ||
            state.properties[PropKey.ON_MOMENTUM_SCROLL_END] != null
        if (!wanted) {
            install(null)
            return
        }
        install { phase, x, y, vx, vy ->
            if (nodes[state.id] !== state) return@install
            val (event, key) = when (phase) {
                PamScrollContainer.SCROLL_PHASE_BEGIN_DRAG ->
                    EventKind.SCROLL_BEGIN_DRAG to PropKey.ON_SCROLL_BEGIN_DRAG
                PamScrollContainer.SCROLL_PHASE_END_DRAG ->
                    EventKind.SCROLL_END_DRAG to PropKey.ON_SCROLL_END_DRAG
                else -> EventKind.MOMENTUM_SCROLL_END to PropKey.ON_MOMENTUM_SCROLL_END
            }
            if (state.properties[key] == null) return@install
            val page = when (val view = views[state.id]) {
                is PamScrollContainer -> view.pageIndex()
                is PamRecyclerList -> view.pageIndex()
                else -> 0
            }
            dispatchBytes(
                state.id,
                event.value,
                WireMap.encode(
                    mapOf(
                        "x" to WireValue.Decimal(x.toDouble()),
                        "y" to WireValue.Decimal(y.toDouble()),
                        "velocityX" to WireValue.Decimal(vx.toDouble()),
                        "velocityY" to WireValue.Decimal(vy.toDouble()),
                        "page" to WireValue.Integer(page.toLong()),
                    ),
                ),
            )
        }
    }

    private fun installListEvents(list: PamRecyclerList, state: NodeState) {
        installScrollPhases(state) { listener -> list.setOnScrollPhase(listener) }
        val scroll = state.properties[PropKey.ON_SCROLL] != null
        val endReached = state.properties[PropKey.ON_END_REACHED] != null
        if (!scroll && !endReached) {
            list.setOnViewportChanged(null)
            return
        }
        list.setOnViewportChanged {
                offset,
                firstVisibleItem,
                visibleItemCount,
                totalItemCount,
            ->
            if (scroll) {
                state.pendingScrollOffset = offset
                if (!state.scrollScheduled) {
                    state.scrollScheduled = true
                    Choreographer.getInstance().postFrameCallback {
                        state.scrollScheduled = false
                        if (nodes[state.id] === state) {
                            dispatch(
                                state.id,
                                EVENT_SCROLL,
                                state.pendingScrollOffset.toString(),
                            )
                        }
                    }
                }
            }
            if (endReached && totalItemCount > 0 && !state.endReachedSent) {
                val threshold = state.number(PropKey.END_REACHED_THRESHOLD, 0.5).coerceIn(0.0, 1.0)
                val remaining = totalItemCount - firstVisibleItem - visibleItemCount
                val trigger = max(1, (visibleItemCount * threshold).toInt())
                if (remaining <= trigger) {
                    state.endReachedSent = true
                    dispatch(state.id, EVENT_END_REACHED)
                }
            }
        }
    }

    private fun dispatchInput(state: NodeState) {
        state.pendingChange?.let(main::removeCallbacks)
        state.pendingChange = null
        if (state.properties[PropKey.ON_CHANGE] != null) {
            recordInputInFlight(state.inputInFlight, state.nativeValue, SystemClock.uptimeMillis())
            dispatch(state.id, EVENT_CHANGE, state.nativeValue)
        }
    }

    /**
     * Native-synced inputs intentionally avoid a PHP round trip per keystroke.
     * Drain their current value before another control fires so the following
     * action observes exactly what is visible in the focused editor.
     */
    private fun flushFocusedNativeInputs() {
        val pending = buildList {
            for (index in 0 until nodes.size()) {
                val state = nodes.valueAt(index)
                if (
                state.inputSyncMode() == INPUT_SYNC_NATIVE &&
                    state.properties[PropKey.ON_CHANGE] != null &&
                    (views[state.id] as? EditText)?.hasFocus() == true
                ) {
                    add(state)
                }
            }
        }
        pending.forEach { state ->
            if (nodes[state.id] === state) dispatchInput(state)
        }
    }

    private fun dispatchInputSelection(state: NodeState) {
        if (state.properties[PropKey.ON_INPUT_SELECTION_CHANGE] == null) {
            return
        }
        dispatchBytes(
            state.id,
            EVENT_INPUT_SELECTION_CHANGE,
            WireMap.encode(
                mapOf(
                    "start" to WireValue.Integer(state.inputSelectionStart.toLong()),
                    "end" to WireValue.Integer(state.inputSelectionEnd.toLong()),
                ),
            ),
        )
    }

    private fun dispatch(id: Long, kind: Int, payload: String = "") {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        dispatchBytes(id, kind, bytes)
    }

    private fun dispatchBytes(id: Long, kind: Int, payload: ByteArray) {
        if (payload.size <= MAX_EVENT_BYTES) dispatchEvent(id, kind, payload)
    }

    private fun nativeEventProperty(kind: Int): PropKey? =
        when (kind) {
            EVENT_PRESS -> PropKey.ON_PRESS
            EVENT_CHANGE -> PropKey.ON_CHANGE
            EVENT_LONG_PRESS -> PropKey.ON_LONG_PRESS
            EVENT_FOCUS -> PropKey.ON_FOCUS
            EVENT_BLUR -> PropKey.ON_BLUR
            EVENT_SUBMIT -> PropKey.ON_SUBMIT
            EVENT_SCROLL -> PropKey.ON_SCROLL
            EVENT_REFRESH -> PropKey.ON_REFRESH
            EVENT_TOGGLE -> PropKey.ON_TOGGLE
            EVENT_END_REACHED -> PropKey.ON_END_REACHED
            EVENT_DRAWER_OPEN -> PropKey.ON_DRAWER_OPEN
            EVENT_DRAWER_CLOSE -> PropKey.ON_DRAWER_CLOSE
            EVENT_NATIVE -> PropKey.ON_NATIVE_EVENT
            EVENT_IMAGE_LOAD_START -> PropKey.ON_IMAGE_LOAD_START
            EVENT_IMAGE_PROGRESS -> PropKey.ON_IMAGE_PROGRESS
            EVENT_IMAGE_LOAD -> PropKey.ON_IMAGE_LOAD
            EVENT_IMAGE_ERROR -> PropKey.ON_IMAGE_ERROR
            EVENT_IMAGE_LOAD_END -> PropKey.ON_IMAGE_LOAD_END
            EVENT_INPUT_END_EDITING -> PropKey.ON_INPUT_END_EDITING
            EVENT_INPUT_SELECTION_CHANGE -> PropKey.ON_INPUT_SELECTION_CHANGE
            EVENT_INPUT_CONTENT_SIZE_CHANGE ->
                PropKey.ON_INPUT_CONTENT_SIZE_CHANGE
            EVENT_INPUT_KEY_PRESS -> PropKey.ON_INPUT_KEY_PRESS
            EVENT_PRESS_IN -> PropKey.ON_PRESS_IN
            EVENT_PRESS_OUT -> PropKey.ON_PRESS_OUT
            EVENT_PRESS_MOVE -> PropKey.ON_PRESS_MOVE
            EVENT_MODAL_REQUEST_CLOSE -> PropKey.ON_MODAL_REQUEST_CLOSE
            EVENT_MODAL_SHOW -> PropKey.ON_MODAL_SHOW
            EVENT_MODAL_DISMISS -> PropKey.ON_MODAL_DISMISS
            EVENT_MODAL_ORIENTATION_CHANGE -> PropKey.ON_MODAL_ORIENTATION_CHANGE
            EventKind.CLICK_OUTSIDE.value -> PropKey.ON_CLICK_OUTSIDE
            EventKind.INTERSECT.value -> PropKey.ON_INTERSECT
            EventKind.MUTATE.value -> PropKey.ON_MUTATE
            EventKind.RESIZE.value -> PropKey.ON_RESIZE
            EventKind.TOUCH_START.value -> PropKey.ON_TOUCH_START
            EventKind.TOUCH_MOVE.value -> PropKey.ON_TOUCH_MOVE
            EventKind.TOUCH_END.value -> PropKey.ON_TOUCH_END
            EventKind.GESTURE_BEGIN.value -> PropKey.ON_GESTURE_BEGIN
            EventKind.GESTURE_UPDATE.value -> PropKey.ON_GESTURE_UPDATE
            EventKind.GESTURE_END.value -> PropKey.ON_GESTURE_END
            EventKind.GESTURE_CANCEL.value -> PropKey.ON_GESTURE_CANCEL
            else -> null
        }

    private fun applyInputValue(view: View, state: NodeState, next: String) {
        val input = view as? EditText ?: return
        val formattedNext = formatInputValue(next, state)
        // A controlled value rendered from an older change event must not
        // overwrite newer IME text (React Native's mostRecentEventCount).
        if (
            input.hasFocus() &&
            isStaleInputEcho(
                inFlight = state.inputInFlight,
                value = formattedNext,
                current = input.text.toString(),
                now = SystemClock.uptimeMillis(),
            )
        ) {
            return
        }
        if (
            input.hasFocus() &&
            state.inputSyncMode() != INPUT_SYNC_IMMEDIATE &&
            formattedNext != state.nativeValue &&
            !state.nativeValueAcknowledged
        ) {
            state.deferredInputValue = next
            return
        }
        state.deferredInputValue = null
        if (input.text.toString() == formattedNext) {
            state.nativeValueAcknowledged = true
            return
        }
        state.updating = true
        input.setText(formattedNext)
        // An inactive field should reveal the beginning of its value, not scroll
        // to a trailing cursor before the user has interacted with it.
        val requestedStart = state.integerOrNull(PropKey.INPUT_SELECTION_START)?.toInt()
        val start = (requestedStart ?: if (input.hasFocus()) input.text.length else 0)
            .coerceIn(0, input.text.length)
        val end = if (requestedStart != null) {
            (state.integerOrNull(PropKey.INPUT_SELECTION_END)?.toInt() ?: start)
                .coerceIn(start, input.text.length)
        } else start
        input.setSelection(start, end)
        state.nativeValue = formattedNext
        state.nativeValueAcknowledged = true
        state.updating = false
    }

    private fun formatInputValue(value: String, state: NodeState): String =
        when (state.integer(PropKey.INPUT_FORMAT, INPUT_FORMAT_NONE.toLong()).toInt()) {
            INPUT_FORMAT_PATTERN -> formatPatternInput(value, state)
            INPUT_FORMAT_CURRENCY -> formatCurrencyInput(value, state)
            else -> value
        }

    private fun formatPatternInput(value: String, state: NodeState): String {
        val pattern = state.textOrNull(PropKey.INPUT_FORMAT_PATTERN).orEmpty().take(128)
        if (pattern.isEmpty()) return value
        val slot = state.textOrNull(PropKey.INPUT_FORMAT_PLACEHOLDER)?.firstOrNull() ?: '#'
        val digits = value.filter(Char::isDigit)
        if (digits.isEmpty()) return ""
        val output = StringBuilder(pattern.length)
        var digitIndex = 0
        for (character in pattern) {
            if (character == slot) {
                if (digitIndex >= digits.length) break
                output.append(digits[digitIndex++])
            } else if (digitIndex == 0 || digitIndex < digits.length) {
                output.append(character)
            }
        }
        return output.toString()
    }

    private fun formatCurrencyInput(value: String, state: NodeState): String {
        val digits = value.filter(Char::isDigit).take(36)
        if (digits.isEmpty()) return ""
        val decimals = state.integer(PropKey.INPUT_FORMAT_DECIMAL_DIGITS, 2L).toInt().coerceIn(0, 6)
        val amount = BigDecimal(BigInteger(digits), decimals)
        val requestedLocale = state.textOrNull(PropKey.INPUT_FORMAT_LOCALE)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val locale = requestedLocale
            ?.let(Locale::forLanguageTag)
            ?.takeUnless { it.language.isEmpty() }
            ?: Locale.getDefault()
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
            isGroupingUsed = true
        }
        val prefix = state.textOrNull(PropKey.INPUT_FORMAT_PREFIX).orEmpty().take(16)
        val suffix = state.textOrNull(PropKey.INPUT_FORMAT_SUFFIX).orEmpty().take(16)
        return prefix + formatter.format(amount) + suffix
    }

    private fun cursorAfterDigits(value: String, digitCount: Int): Int {
        if (digitCount <= 0) return 0
        var seen = 0
        value.forEachIndexed { index, character ->
            if (character.isDigit() && ++seen >= digitCount) return index + 1
        }
        return value.length
    }

    private fun applyStringList(view: View, state: NodeState, value: PropValue) {
        val list = view as? PamRecyclerList ?: return
        val items = (value as? PropValue.Strings)?.value
            ?: error("Expected packed string list")
        list.setItems(items)
        state.endReachedSent = false
    }

    private fun applySectionList(view: View, state: NodeState, value: PropValue) {
        val list = view as? PamRecyclerList ?: return
        val sections = (value as? PropValue.Sections)?.value
            ?: error("Expected packed section list")
        list.setSections(sections)
        state.endReachedSent = false
    }

    private fun applyLoading(view: View, state: NodeState, loading: Boolean) {
        val button = view as? Button ?: return
        val enabled = state.flag(PropKey.ENABLED, true)
        if (loading) {
            val color = button.currentTextColor
            val indicator = state.loadingDrawable
                ?: PamButtonLoadingDrawable(view.context, dp(20f), color).also {
                    state.loadingDrawable = it
                }
            indicator.setColor(color)
            button.text = ""
            button.setCompoundDrawables(indicator, null, null, null)
            button.isEnabled = false
            indicator.start()
            return
        }
        state.loadingDrawable?.stop()
        button.setCompoundDrawables(null, null, null, null)
        button.text = state.baseText
        button.isEnabled = enabled
    }

    private fun applyLeafPadding(view: View, state: NodeState) {
        // Flex containers receive engine-computed child frames, so Android
        // padding would offset their content twice. Custom native ViewGroups
        // own descendant layout and must receive authored padding as real
        // View padding (for example Material list-item content insets).
        if (view is ViewGroup && !usesNativeViewGroupPadding(state.kind)) {
            view.setPadding(0, 0, 0, state.safeBottomInset)
            return
        }
        val all = state.number(PropKey.PADDING, 0.0).toFloat()
        val horizontal = state.number(PropKey.PADDING_HORIZONTAL, all.toDouble()).toFloat()
        val vertical = state.number(PropKey.PADDING_VERTICAL, all.toDouble()).toFloat()
        val left = state.number(PropKey.PADDING_LEFT, horizontal.toDouble()).toFloat()
        val top = state.number(PropKey.PADDING_TOP, vertical.toDouble()).toFloat()
        val right = state.number(PropKey.PADDING_RIGHT, horizontal.toDouble()).toFloat()
        val bottom = state.number(PropKey.PADDING_BOTTOM, vertical.toDouble()).toFloat()
        // Yoga: borders belong to the padding box, so leaf content (text,
        // images) is inset by the border as well.
        val border = state.number(PropKey.BORDER_WIDTH, 0.0).toFloat().coerceAtLeast(0f)
        fun edge(key: PropKey) = state.number(key, border.toDouble()).toFloat().coerceAtLeast(0f)
        // Text content was measured at the exact inner width; never round the
        // insets up or the last word of a fitted line could wrap.
        val inset: (Float) -> Int = if (view is TextView) {
            { value -> (value * resourcesDensity() + 0.001f).toInt() }
        } else {
            ::dp
        }
        view.setPadding(
            inset(left + edge(PropKey.BORDER_LEFT_WIDTH)),
            inset(top + edge(PropKey.BORDER_TOP_WIDTH)),
            inset(right + edge(PropKey.BORDER_RIGHT_WIDTH)),
            inset(bottom + edge(PropKey.BORDER_BOTTOM_WIDTH)) + state.safeBottomInset,
        )
    }

    @SuppressLint("DiscouragedApi")
    private fun resolveNativeColor(name: String): Int? {
        val identifier = context.resources.getIdentifier(name, "color", context.packageName)
        if (identifier == 0) return null
        return context.resources.getColor(identifier, context.theme)
    }

    private fun updateBackground(
        view: View,
        state: NodeState,
        backgroundColorOverride: Int? = null,
        borderColorOverride: Int? = null,
    ) {
        state.initialBackgroundUpdate?.let {
            it.request(backgroundColorOverride, borderColorOverride)
            return
        }
        val defaultColor = if (
            state.kind == NodeKind.IMAGE ||
            state.kind == NodeKind.IMAGE_BACKGROUND ||
            state.kind == NodeKind.DRAWING_CANVAS
        ) {
            state.integer(
                PropKey.IMAGE_OVERLAY_COLOR,
                Color.TRANSPARENT.toLong(),
            )
        } else {
            Color.TRANSPARENT.toLong()
        }
        val color = backgroundColorOverride ?: state.properties[PropKey.NATIVE_BACKGROUND_COLOR_RESOURCE]
            ?.let { resolveNativeColor(it.text(PropKey.NATIVE_BACKGROUND_COLOR_RESOURCE)) }
            ?: state.integer(PropKey.BACKGROUND_COLOR, defaultColor).toInt()
        val logicalRadius = state.number(PropKey.BORDER_RADIUS, 0.0)
        val topLeft = dp(state.number(PropKey.BORDER_TOP_LEFT_RADIUS, logicalRadius).toFloat())
            .toFloat()
        val topRight = dp(state.number(PropKey.BORDER_TOP_RIGHT_RADIUS, logicalRadius).toFloat())
            .toFloat()
        val bottomRight = dp(
            state.number(PropKey.BORDER_BOTTOM_RIGHT_RADIUS, logicalRadius).toFloat(),
        ).toFloat()
        val bottomLeft = dp(
            state.number(PropKey.BORDER_BOTTOM_LEFT_RADIUS, logicalRadius).toFloat(),
        ).toFloat()
        val uniformBorderWidth = dp(state.number(PropKey.BORDER_WIDTH, 0.0).toFloat())
        fun directionalBorderWidth(key: PropKey): Int {
            return if (state.properties.containsKey(key)) {
                dp(state.number(key, 0.0).toFloat())
            } else {
                uniformBorderWidth
            }
        }
        val leftBorderWidth = directionalBorderWidth(PropKey.BORDER_LEFT_WIDTH)
        val topBorderWidth = directionalBorderWidth(PropKey.BORDER_TOP_WIDTH)
        val rightBorderWidth = directionalBorderWidth(PropKey.BORDER_RIGHT_WIDTH)
        val bottomBorderWidth = directionalBorderWidth(PropKey.BORDER_BOTTOM_WIDTH)
        val borderWidth = maxOf(
            leftBorderWidth,
            topBorderWidth,
            rightBorderWidth,
            bottomBorderWidth,
        )
        val uniformBorderColor = borderColorOverride ?: state.properties[PropKey.NATIVE_BORDER_COLOR_RESOURCE]
            ?.let { resolveNativeColor(it.text(PropKey.NATIVE_BORDER_COLOR_RESOURCE)) }
            ?: state.integer(PropKey.BORDER_COLOR, Color.TRANSPARENT.toLong()).toInt()
        // Per-side CSS colors; a pressed/focused state color overrides every side.
        fun sideBorderColor(key: PropKey): Int =
            borderColorOverride ?: state.properties[key]?.integer()?.toInt() ?: uniformBorderColor
        val leftBorderColor = sideBorderColor(PropKey.BORDER_LEFT_COLOR)
        val topBorderColor = sideBorderColor(PropKey.BORDER_TOP_COLOR)
        val rightBorderColor = sideBorderColor(PropKey.BORDER_RIGHT_COLOR)
        val bottomBorderColor = sideBorderColor(PropKey.BORDER_BOTTOM_COLOR)
        val paintedSideColors = listOfNotNull(
            leftBorderColor.takeIf { leftBorderWidth > 0 },
            topBorderColor.takeIf { topBorderWidth > 0 },
            rightBorderColor.takeIf { rightBorderWidth > 0 },
            bottomBorderColor.takeIf { bottomBorderWidth > 0 },
        ).distinct()
        val hasDirectionalBorder = borderWidth > 0 && (
            leftBorderWidth != rightBorderWidth ||
                leftBorderWidth != topBorderWidth ||
                leftBorderWidth != bottomBorderWidth ||
                paintedSideColors.size > 1
            )
        val borderColor = paintedSideColors.singleOrNull() ?: uniformBorderColor
        val borderStyle = state.integer(PropKey.BORDER_STYLE, 1).toInt()
        val imageHost = state.kind == NodeKind.IMAGE ||
            state.kind == NodeKind.IMAGE_BACKGROUND ||
            state.kind == NodeKind.DRAWING_CANVAS
        val radii = floatArrayOf(
            topLeft,
            topLeft,
            topRight,
            topRight,
            bottomRight,
            bottomRight,
            bottomLeft,
            bottomLeft,
        )
        val clipBorder = state.number(PropKey.BORDER_WIDTH, 0.0).toFloat().coerceAtLeast(0f)
        fun clipEdge(key: PropKey) =
            state.number(key, clipBorder.toDouble()).toFloat().coerceAtLeast(0f) * resourcesDensity()
        (view as? PamContainer)?.setOverflowClip(
            state.integer(PropKey.OVERFLOW, OVERFLOW_VISIBLE) == OVERFLOW_HIDDEN,
            radii,
            floatArrayOf(
                clipEdge(PropKey.BORDER_LEFT_WIDTH),
                clipEdge(PropKey.BORDER_TOP_WIDTH),
                clipEdge(PropKey.BORDER_RIGHT_WIDTH),
                clipEdge(PropKey.BORDER_BOTTOM_WIDTH),
            ),
        )
        val density = resourcesDensity()
        val gradientLayers = PamGradientLayer.parse(
            state.properties[PropKey.BACKGROUND_GRADIENT]?.textOrNull(),
        )
        val borderGradient = PamGradientLayer.parse(
            state.properties[PropKey.BORDER_GRADIENT]?.textOrNull(),
        ).firstOrNull()?.takeIf { borderWidth > 0 && !imageHost }
        val insetShadows = PamBoxShadows.parse(
            state.properties[PropKey.BOX_SHADOWS]?.textOrNull(),
            density,
            radii,
        ).filter { it.inset }
        val effects = if (
            !imageHost &&
            (gradientLayers.isNotEmpty() || insetShadows.isNotEmpty() || borderGradient != null)
        ) {
            PamEffectsDrawable(
                gradientLayers,
                insetShadows,
                borderGradient,
                radii,
                floatArrayOf(
                    leftBorderWidth.toFloat(),
                    topBorderWidth.toFloat(),
                    rightBorderWidth.toFloat(),
                    bottomBorderWidth.toFloat(),
                ),
                density,
            )
        } else {
            null
        }
        val shape = GradientDrawable().apply {
            setColor(color)
            cornerRadii = radii
            if (!imageHost && borderWidth > 0 && !hasDirectionalBorder && borderGradient == null) {
                when (borderStyle) {
                    2 -> setStroke(
                        borderWidth,
                        borderColor,
                        maxOf(borderWidth * 3f, dp(3f).toFloat()),
                        maxOf(borderWidth * 2f, dp(2f).toFloat()),
                    )
                    3 -> setStroke(
                        borderWidth,
                        borderColor,
                        maxOf(borderWidth.toFloat(), dp(1f).toFloat()),
                        maxOf(borderWidth * 1.5f, dp(1.5f).toFloat()),
                    )
                    else -> setStroke(borderWidth, borderColor)
                }
            }
        }
        val background = if (!imageHost && hasDirectionalBorder && borderGradient == null) {
            val borders = object : android.graphics.drawable.Drawable() {
                private var drawableAlpha = 255
                private val paint = android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG,
                ).apply {
                    this.color = borderColor
                    style = android.graphics.Paint.Style.FILL
                }

                override fun draw(canvas: android.graphics.Canvas) {
                    val area = bounds
                    fun sidePaint(color: Int): android.graphics.Paint = paint.apply {
                        this.color = color
                        this.alpha = Color.alpha(color) * drawableAlpha / 255
                    }
                    if (leftBorderWidth > 0) {
                        sidePaint(leftBorderColor)
                        canvas.drawRect(
                            area.left.toFloat(),
                            area.top.toFloat(),
                            (area.left + leftBorderWidth).toFloat(),
                            area.bottom.toFloat(),
                            paint,
                        )
                    }
                    if (topBorderWidth > 0) {
                        sidePaint(topBorderColor)
                        canvas.drawRect(
                            area.left.toFloat(),
                            area.top.toFloat(),
                            area.right.toFloat(),
                            (area.top + topBorderWidth).toFloat(),
                            paint,
                        )
                    }
                    if (rightBorderWidth > 0) {
                        sidePaint(rightBorderColor)
                        canvas.drawRect(
                            (area.right - rightBorderWidth).toFloat(),
                            area.top.toFloat(),
                            area.right.toFloat(),
                            area.bottom.toFloat(),
                            paint,
                        )
                    }
                    if (bottomBorderWidth > 0) {
                        sidePaint(bottomBorderColor)
                        canvas.drawRect(
                            area.left.toFloat(),
                            (area.bottom - bottomBorderWidth).toFloat(),
                            area.right.toFloat(),
                            area.bottom.toFloat(),
                            paint,
                        )
                    }
                }

                override fun setAlpha(alpha: Int) {
                    drawableAlpha = alpha.coerceIn(0, 255)
                    invalidateSelf()
                }

                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                    paint.colorFilter = colorFilter
                }

                @Suppress("DEPRECATION")
                override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            }
            android.graphics.drawable.LayerDrawable(listOfNotNull(shape, effects, borders).toTypedArray())
        } else if (effects != null) {
            android.graphics.drawable.LayerDrawable(arrayOf(shape, effects))
        } else {
            shape
        }
        pamImageView(view)?.setCornerRadii(radii)
        applyBoxShadow(view, state, radii)
        if (imageHost) {
            view.foreground = if (borderWidth > 0) {
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadii = radii
                    when (borderStyle) {
                        2 -> setStroke(borderWidth, borderColor, borderWidth * 3f, borderWidth * 2f)
                        3 -> setStroke(borderWidth, borderColor, borderWidth.toFloat(), borderWidth * 1.5f)
                        else -> setStroke(borderWidth, borderColor)
                    }
                }
            } else {
                null
            }
        }
        val configuredRipple = state.properties[PropKey.RIPPLE_COLOR]?.integer()?.toInt()
        val ripple = configuredRipple?.let { color ->
            if (color != 0) {
                color
            } else {
                state.properties[PropKey.TEXT_COLOR]?.integer()?.toInt()
                    ?: if (
                        context.resources.configuration.uiMode and
                            Configuration.UI_MODE_NIGHT_MASK ==
                            Configuration.UI_MODE_NIGHT_YES
                    ) {
                        Color.WHITE
                    } else {
                        Color.BLACK
                    }
            }
        }
        if (ripple == null || imageHost) {
            view.background = background
            if (!imageHost) {
                view.foreground = null
            }
            return
        }

        val rippleAlpha = state.number(PropKey.RIPPLE_ALPHA, 0.12)
            .toFloat()
            .coerceIn(0f, 1f)
        val effectiveRipple = Color.argb(
            (Color.alpha(ripple) * rippleAlpha).toInt().coerceIn(0, 255),
            Color.red(ripple),
            Color.green(ripple),
            Color.blue(ripple),
        )
        val borderless = state.flag(PropKey.RIPPLE_BORDERLESS, false)
        val foreground = state.flag(PropKey.RIPPLE_FOREGROUND, false)
        val rippleMask = if (borderless) {
            null
        } else {
            GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadii = radii
            }
        }
        val overlay = (RippleDrawable(
            ColorStateList.valueOf(effectiveRipple),
            null,
            rippleMask,
        ).mutate() as RippleDrawable).apply {
            state.properties[PropKey.RIPPLE_RADIUS]?.decimal()?.let { radius ->
                this.radius = dp(radius.toFloat().coerceAtLeast(0f))
            }
        }
        if (foreground || borderless) {
            view.background = background
            view.foreground = overlay
        } else {
            view.foreground = null
            view.background = (RippleDrawable(
                ColorStateList.valueOf(effectiveRipple),
                background,
                rippleMask,
            ).mutate() as RippleDrawable).apply {
                state.properties[PropKey.RIPPLE_RADIUS]?.decimal()?.let { radius ->
                    this.radius = dp(radius.toFloat().coerceAtLeast(0f))
                }
            }
        }
    }

    private fun applyBoxShadow(
        view: View,
        state: NodeState,
        resolvedRadii: FloatArray? = null,
    ) {
        val logicalRadius = state.number(PropKey.BORDER_RADIUS, 0.0)
        val radii = resolvedRadii ?: floatArrayOf(
            dp(state.number(PropKey.BORDER_TOP_LEFT_RADIUS, logicalRadius).toFloat()).toFloat(),
            dp(state.number(PropKey.BORDER_TOP_LEFT_RADIUS, logicalRadius).toFloat()).toFloat(),
            dp(state.number(PropKey.BORDER_TOP_RIGHT_RADIUS, logicalRadius).toFloat()).toFloat(),
            dp(state.number(PropKey.BORDER_TOP_RIGHT_RADIUS, logicalRadius).toFloat()).toFloat(),
            dp(state.number(PropKey.BORDER_BOTTOM_RIGHT_RADIUS, logicalRadius).toFloat()).toFloat(),
            dp(state.number(PropKey.BORDER_BOTTOM_RIGHT_RADIUS, logicalRadius).toFloat()).toFloat(),
            dp(state.number(PropKey.BORDER_BOTTOM_LEFT_RADIUS, logicalRadius).toFloat()).toFloat(),
            dp(state.number(PropKey.BORDER_BOTTOM_LEFT_RADIUS, logicalRadius).toFloat()).toFloat(),
        )
        val list = state.properties[PropKey.BOX_SHADOWS]?.textOrNull()
        if (!list.isNullOrEmpty()) {
            PamBoxShadows.set(view, PamBoxShadows.parse(list, resourcesDensity(), radii))
            return
        }
        val color = state.integer(PropKey.SHADOW_COLOR, Color.TRANSPARENT.toLong()).toInt()
        if (Color.alpha(color) == 0) {
            PamBoxShadows.set(view, null)
            return
        }
        val density = resourcesDensity()
        PamBoxShadows.set(
            view,
            PamBoxShadow(
                offsetX = state.number(PropKey.SHADOW_OFFSET_X, 0.0).toFloat() * density,
                offsetY = state.number(PropKey.SHADOW_OFFSET_Y, 0.0).toFloat() * density,
                blurRadius = state.number(PropKey.SHADOW_BLUR_RADIUS, 0.0).toFloat() * density,
                spreadRadius = state.number(PropKey.SHADOW_SPREAD_RADIUS, 0.0).toFloat() * density,
                color = color,
                cornerRadii = radii,
            ),
        )
    }

    private fun applyOverflowClip(view: View, state: NodeState) {
        val enabled = state.integer(PropKey.OVERFLOW, OVERFLOW_VISIBLE) == OVERFLOW_HIDDEN
        if (view is PamContainer) {
            updateBackground(view, state)
            return
        }
        (view as? ViewGroup)?.clipChildren = if (view is PamRecyclerList) {
            true
        } else {
            enabled
        }
    }

    private fun textEllipsize(mode: Int): TextUtils.TruncateAt? =
        when (mode) {
            TEXT_ELLIPSIZE_HEAD -> TextUtils.TruncateAt.START
            TEXT_ELLIPSIZE_MIDDLE -> TextUtils.TruncateAt.MIDDLE
            TEXT_ELLIPSIZE_CLIP -> null
            else -> TextUtils.TruncateAt.END
        }

    private fun applyTextSizing(view: TextView, state: NodeState) {
        state.initialTextUpdates?.let { it.size = true; return }
        view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        val baseSize = state.number(PropKey.FONT_SIZE, 14.0).toFloat().coerceAtLeast(1f)
        val metrics = view.resources.displayMetrics
        val allowScaling = state.flag(PropKey.TEXT_ALLOW_FONT_SCALING, true)
        val deviceScale = view.resources.configuration.fontScale
        val maximumMultiplier = state
            .number(PropKey.TEXT_MAX_FONT_SIZE_MULTIPLIER, 0.0)
            .toFloat()
        val effectiveScale = resolvedFontScale(allowScaling, deviceScale, maximumMultiplier)
        val maximumPx = if (isRichTextView(view, state)) {
            PamTextLayout.fontSizePx(baseSize, effectiveScale, metrics.density)
        } else {
            max(1f, baseSize * metrics.density * effectiveScale)
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, maximumPx)

        if (!state.flag(PropKey.TEXT_ADJUSTS_FONT_SIZE_TO_FIT, false)) return
        val minimumScale = state
            .number(PropKey.TEXT_MINIMUM_FONT_SCALE, 0.01)
            .toFloat()
            .coerceIn(0.01f, 1f)
        val minimumPx = max(1, (maximumPx * minimumScale).toInt())
        val maximumPxInt = max(minimumPx, maximumPx.toInt())
        view.setAutoSizeTextTypeUniformWithConfiguration(
            minimumPx,
            maximumPxInt,
            1,
            TypedValue.COMPLEX_UNIT_PX,
        )
    }

    /** CSS transform-origin as a percentage of the view's own box. */
    private fun applyTransformOrigin(
        view: View,
        state: NodeState,
        width: Int = view.width,
        height: Int = view.height,
    ) {
        val originX = state.properties[PropKey.TRANSFORM_ORIGIN_X]?.decimal()
        val originY = state.properties[PropKey.TRANSFORM_ORIGIN_Y]?.decimal()
        if (originX == null && originY == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                view.resetPivot()
            } else {
                view.pivotX = width / 2f
                view.pivotY = height / 2f
            }
            return
        }
        view.pivotX = width * ((originX ?: 50.0) / 100.0).toFloat()
        view.pivotY = height * ((originY ?: 50.0) / 100.0).toFloat()
    }

    /** CSS text-shadow; a zero blur still paints a hard shadow. */
    private fun applyTextShadow(view: TextView, state: NodeState) {
        val color = state.integer(PropKey.TEXT_SHADOW_COLOR, Color.TRANSPARENT.toLong()).toInt()
        if (Color.alpha(color) == 0) {
            view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            return
        }
        val radius = dp(state.number(PropKey.TEXT_SHADOW_RADIUS, 0.0).toFloat()).toFloat()
        view.setShadowLayer(
            radius.coerceAtLeast(MIN_TEXT_SHADOW_RADIUS),
            dp(state.number(PropKey.TEXT_SHADOW_OFFSET_X, 0.0).toFloat()).toFloat(),
            dp(state.number(PropKey.TEXT_SHADOW_OFFSET_Y, 0.0).toFloat()).toFloat(),
            color,
        )
    }

    private fun applyLetterSpacing(view: TextView, state: NodeState) {
        state.initialTextUpdates?.let { it.letterSpacing = true; return }
        if (isRichTextView(view, state)) {
            view.letterSpacing = PamTextLayout.letterSpacingEm(textStyle(state), resourcesDensity())
            return
        }
        view.letterSpacing = resolvedAndroidLetterSpacing(
            state.number(PropKey.LETTER_SPACING, 0.0).toFloat(),
            state.number(PropKey.FONT_SIZE, 14.0).toFloat(),
        )
    }

    private fun applyTextAlignment(view: TextView, state: NodeState) {
        if (view is PamEditText) return
        val authored = state.properties[PropKey.TEXT_ALIGN]?.integer()?.toInt()
        view.justificationMode = if (authored == TEXT_ALIGN_JUSTIFY) {
            android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
        } else {
            android.text.Layout.JUSTIFICATION_MODE_NONE
        }
        val horizontal = when (authored) {
            2 -> Gravity.CENTER_HORIZONTAL
            3 -> Gravity.END
            1, TEXT_ALIGN_JUSTIFY -> Gravity.START
            else -> {
                val parent = nodes[state.parent]
                val hasAllocatedWidth = state.properties.containsKey(PropKey.WIDTH) ||
                    state.properties.containsKey(PropKey.MIN_WIDTH) ||
                    state.number(PropKey.FLEX_GROW, 0.0) > 0.0
                if (hasAllocatedWidth) {
                    Gravity.START
                } else {
                    val parentDirection = parent?.integer(
                        PropKey.FLEX_DIRECTION,
                        if (parent.kind == NodeKind.ROW) 2L else 1L,
                    )?.toInt() ?: 1
                    val parentIsColumn = parentDirection == 1 || parentDirection == 3
                    if (parentIsColumn) {
                        val alignment = state.properties[PropKey.ALIGN_SELF]
                            ?.integer()
                            ?.toInt()
                            ?.takeUnless { it == 4 }
                            ?: parent?.integer(PropKey.ALIGN_ITEMS, 4L)?.toInt()
                            ?: 4
                        when (alignment) {
                            2 -> Gravity.CENTER_HORIZONTAL
                            3 -> Gravity.END
                            else -> Gravity.START
                        }
                    } else {
                        when (parent?.integer(PropKey.JUSTIFY_CONTENT, 1L)?.toInt()) {
                            2 -> Gravity.CENTER_HORIZONTAL
                            3 -> Gravity.END
                            else -> Gravity.START
                        }
                    }
                }
            }
        }
        // Intrinsic text must keep its first baseline stable when the engine's
        // conservative wrapping estimate reserves an extra line. Explicit text
        // boxes (button labels, badges, etc.) retain vertical centering.
        val allocatedHeight = state.properties.containsKey(PropKey.HEIGHT) ||
            state.properties.containsKey(PropKey.HEIGHT_PERCENT) ||
            state.properties.containsKey(PropKey.MIN_HEIGHT)
        val parent = nodes[state.parent]
        val parentDirection = parent?.integer(
            PropKey.FLEX_DIRECTION,
            if (parent.kind == NodeKind.ROW) 2L else 1L,
        )?.toInt() ?: 1
        val centeredByParent = if (parentDirection == 2 || parentDirection == 4) {
            (state.properties[PropKey.ALIGN_SELF]?.integer()
                ?: parent?.integer(PropKey.ALIGN_ITEMS, 4L)) == 2L
        } else {
            parent?.integer(PropKey.JUSTIFY_CONTENT, 1L) == 2L
        }
        view.gravity = horizontal or if (allocatedHeight || centeredByParent) Gravity.CENTER_VERTICAL else Gravity.TOP
    }

    private fun applyLineHeight(view: TextView, state: NodeState) {
        if (isRichTextView(view, state)) {
            applyTextContent(view, state)
            return
        }
        val logicalLineHeight = state.properties[PropKey.LINE_HEIGHT]?.decimal()?.toFloat()
        if (logicalLineHeight == null) {
            view.setLineSpacing(0f, 1f)
            return
        }
        val metrics = view.paint.fontMetricsInt
        view.setLineSpacing(
            resolvedLineSpacingExtra(
                logicalLineHeight = logicalLineHeight,
                renderedTextSizePx = view.textSize,
                logicalFontSize = state.number(PropKey.FONT_SIZE, 14.0).toFloat(),
                fontMetricsHeightPx = (metrics.descent - metrics.ascent).toFloat(),
            ),
            1f,
        )
    }

    private fun applyTextDataDetector(view: TextView, state: NodeState) {
        (view.text as? Spannable)?.let { text ->
            text.getSpans(0, text.length, URLSpan::class.java)
                .forEach(text::removeSpan)
        }
        val mask = when (
            state.integer(PropKey.TEXT_DATA_DETECTOR_TYPE, TEXT_DATA_NONE.toLong()).toInt()
        ) {
            TEXT_DATA_PHONE -> Linkify.PHONE_NUMBERS
            TEXT_DATA_LINK -> Linkify.WEB_URLS
            TEXT_DATA_EMAIL -> Linkify.EMAIL_ADDRESSES
            TEXT_DATA_ALL ->
                Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES or Linkify.PHONE_NUMBERS
            else -> 0
        }
        view.linksClickable = mask != 0
        if (mask == 0) {
            if (!state.flag(PropKey.TEXT_SELECTABLE, false)) {
                view.movementMethod = null
            }
            return
        }
        Linkify.addLinks(view, mask)
        view.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun applyTextBreakStrategy(view: TextView, strategy: Int) {
        view.breakStrategy = when (strategy) {
            TEXT_BREAK_SIMPLE -> ANDROID_BREAK_SIMPLE
            TEXT_BREAK_BALANCED -> ANDROID_BREAK_BALANCED
            else -> ANDROID_BREAK_HIGH_QUALITY
        }
    }

    /**
     * KeyboardAwareScrollView: the visible IME overlap becomes bottom content
     * inset inside the scroll content and the focused input stays revealed.
     */
    private fun configureScrollKeyboardInset(scroll: PamScrollContainer, state: NodeState, enabled: Boolean) {
        if (!enabled) {
            scrollKeyboardInsets.remove(scroll)?.close()
            return
        }
        val observer = scrollKeyboardInsets.getOrPut(scroll) { PamScrollKeyboardInset(scroll, host as? PamRootHost) }
        observer.extraInsetPx = dp(state.number(PropKey.KEYBOARD_VERTICAL_OFFSET, 0.0).toFloat())
        scroll.requestApplyInsets()
    }

    /** Materialized native view of a node (instrumentation and diagnostics). */
    internal fun viewForNode(id: Long): View? = views[id]

    private fun applyStickyHeader(view: View, sticky: Boolean, state: NodeState? = null) {
        var parent = view.parent
        while (parent != null && parent !is PamScrollContainer) parent = parent.parent
        val margins = cellRootMargins(state?.properties)
        val density = resourcesDensity()
        (parent as? PamScrollContainer)?.setSticky(
            view,
            sticky,
            marginTopPx = (margins.top * density).roundToInt(),
            marginBottomPx = (margins.bottom * density).roundToInt(),
        )
    }

    private val lastLayoutEvents = HashMap<Long, Frame>()
    private val pendingLayoutEvents = LinkedHashSet<Long>()
    private var layoutEventsPosted = false

    /** React Native onLayout: frame relative to the parent, coalesced per frame. */
    private fun queueLayoutEvent(id: Long) {
        val state = nodes[id] ?: return
        if (state.properties[PropKey.ON_LAYOUT] == null) return
        pendingLayoutEvents += id
        if (!layoutEventsPosted) {
            layoutEventsPosted = true
            main.post(::flushLayoutEvents)
        }
    }

    private fun flushLayoutEvents() {
        layoutEventsPosted = false
        val ids = pendingLayoutEvents.toList()
        pendingLayoutEvents.clear()
        for (id in ids) {
            val state = nodes[id] ?: continue
            if (state.properties[PropKey.ON_LAYOUT] == null) continue
            val frame = frames[id] ?: continue
            val parent = frames[state.parent]
            val relative = Frame(
                frame.x - (parent?.x ?: 0f),
                frame.y - (parent?.y ?: 0f),
                frame.width,
                frame.height,
            )
            if (lastLayoutEvents[id] == relative) continue
            lastLayoutEvents[id] = relative
            dispatchBytes(
                id,
                EventKind.LAYOUT.value,
                WireMap.encode(
                    mapOf(
                        "x" to WireValue.Decimal(relative.x.toDouble()),
                        "y" to WireValue.Decimal(relative.y.toDouble()),
                        "width" to WireValue.Decimal(relative.width.toDouble()),
                        "height" to WireValue.Decimal(relative.height.toDouble()),
                    ),
                ),
            )
        }
    }

    /** Text nodes use the shared PamTextLayout pipeline (React Native parity). */
    private fun isRichTextView(view: TextView, state: NodeState): Boolean =
        state.kind == NodeKind.TEXT && view !is EditText && view !is Button

    private fun textStyle(state: NodeState): PamTextStyle =
        PamTextStyle(
            fontFamily = (state.properties[PropKey.FONT_FAMILY] as? PropValue.Text)?.value,
            fontSize = state.number(PropKey.FONT_SIZE, 14.0).toFloat().coerceAtLeast(1f),
            fontScale = resolvedFontScale(
                state.flag(PropKey.TEXT_ALLOW_FONT_SCALING, true),
                context.resources.configuration.fontScale,
                state.number(PropKey.TEXT_MAX_FONT_SIZE_MULTIPLIER, 0.0).toFloat(),
            ),
            fontWeight = state.integer(PropKey.FONT_WEIGHT, 400L).coerceIn(1L, 1000L).toInt(),
            italic = state.integer(PropKey.FONT_STYLE, 1L) == 2L,
            letterSpacing = state.number(PropKey.LETTER_SPACING, 0.0).toFloat(),
            lineHeight = state.number(PropKey.LINE_HEIGHT, 0.0).toFloat().coerceAtLeast(0f),
            includeFontPadding = state.flag(PropKey.INCLUDE_FONT_PADDING, true),
            textTransform = state.integer(PropKey.TEXT_TRANSFORM, 1L).toInt(),
            breakStrategy = state.integer(PropKey.TEXT_BREAK_STRATEGY, 1L).toInt(),
            hyphenation = state.integer(PropKey.TEXT_HYPHENATION_FREQUENCY, 1L).toInt(),
            maxLines = state.integer(PropKey.NUMBER_OF_LINES, 0L).toInt().coerceAtLeast(0),
            fontFeatures = (state.properties[PropKey.FONT_FEATURE_SETTINGS] as? PropValue.Text)?.value,
        )

    private fun applyTextContent(view: TextView, state: NodeState) {
        state.initialTextUpdates?.let { it.content = true; return }
        view.setLineSpacing(0f, 1f)
        view.transformationMethod = null
        val spans = (state.properties[PropKey.TEXT_SPANS] as? PropValue.Text)?.value
        view.text = PamTextLayout.content(
            state.baseText,
            spans,
            textStyle(state),
            resourcesDensity(),
            typefaces,
        )
        configureSpanPress(view, state, !spans.isNullOrEmpty())
        applyTextDataDetector(view, state)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configureSpanPress(view: TextView, state: NodeState, hasSpans: Boolean) {
        if (!hasSpans || state.properties[PropKey.ON_SPAN_PRESS] == null) {
            if (state.spanPressInstalled) {
                view.setOnTouchListener(null)
                state.spanPressInstalled = false
            }
            return
        }
        state.spanPressInstalled = true
        var pressed: PamSpanPress? = null
        view.setOnTouchListener { touched, event ->
            val text = (touched as TextView).text as? android.text.Spanned
            val layout = touched.layout
            fun spanAt(): PamSpanPress? {
                if (text == null || layout == null) return null
                val x = event.x - touched.totalPaddingLeft + touched.scrollX
                val y = event.y - touched.totalPaddingTop + touched.scrollY
                if (y < 0 || y > layout.height) return null
                val line = layout.getLineForVertical(y.toInt())
                if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null
                val offset = layout.getOffsetForHorizontal(line, x)
                return text.getSpans(offset, offset, PamSpanPress::class.java)
                    .filter { text.getSpanStart(it) <= offset && offset < text.getSpanEnd(it) }
                    .lastOrNull()
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = spanAt()
                    pressed != null
                }
                MotionEvent.ACTION_UP -> {
                    val target = pressed
                    pressed = null
                    if (target != null && spanAt() === target) {
                        dispatch(state.id, EventKind.SPAN_PRESS.value, target.slot.toString())
                        touched.performClick()
                    }
                    target != null
                }
                MotionEvent.ACTION_CANCEL -> {
                    val consumed = pressed != null
                    pressed = null
                    consumed
                }
                else -> pressed != null
            }
        }
    }

    private fun applyTypeface(view: TextView, state: NodeState) {
        state.initialTextUpdates?.let { it.typeface = true; return }
        val weight = state.integer(PropKey.FONT_WEIGHT, 400L).coerceIn(1L, 1000L).toInt()
        val italic = state.integer(PropKey.FONT_STYLE, 1L) == 2L
        val family = (state.properties[PropKey.FONT_FAMILY] as? PropValue.Text)?.value
        // Text nodes are measured by PamTextLayout with React Native's default
        // paint (hinted advances); other controls keep linear metrics.
        view.paintFlags = if (isRichTextView(view, state)) {
            view.paintFlags and (android.graphics.Paint.SUBPIXEL_TEXT_FLAG or
                android.graphics.Paint.LINEAR_TEXT_FLAG).inv()
        } else {
            view.paintFlags or android.graphics.Paint.SUBPIXEL_TEXT_FLAG or
                android.graphics.Paint.LINEAR_TEXT_FLAG
        }
        view.typeface = typefaces.resolve(family, weight, italic)
    }

    private fun applySafeAreaBottom(view: View, state: NodeState, enabled: Boolean) {
        if (!enabled) {
            view.setOnApplyWindowInsetsListener(null)
            state.safeBottomInset = 0
            applyLeafPadding(view, state)
            return
        }
        view.setOnApplyWindowInsetsListener { _, insets ->
            state.safeBottomInset = windowSafeAreaInsets(insets).bottom
            applyLeafPadding(view, state)
            insets
        }
        view.requestApplyInsets()
    }

    /**
     * CSS `filter`: blur() via RenderEffect (API 31+) and the compiled color
     * matrix (brightness/contrast/saturate/grayscale/sepia/invert/opacity/
     * hue-rotate) via RenderEffect or, before API 31, a hardware layer paint.
     */
    private fun applyFilter(view: View, state: NodeState) {
        var sigma = state.number(PropKey.BLUR_RADIUS, 0.0).toFloat().coerceAtLeast(0f) * resourcesDensity()
        val matrix = PamBackdrop.colorMatrix(state.properties[PropKey.FILTER_COLOR_MATRIX]?.textOrNull())
        // Images blur their bitmap like React Native's blurRadius: every API
        // level, opaque clamped edges, no per-frame GPU blur.
        pamImageView(view)?.let { image ->
            image.setBlur(sigma)
            sigma = 0f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            var effect: RenderEffect? = if (sigma > 0f) {
                val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
            } else {
                null
            }
            if (matrix != null) {
                val color = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix))
                effect = effect?.let { RenderEffect.createChainEffect(color, it) } ?: color
            }
            view.setRenderEffect(effect)
            return
        }
        if (sigma > 0f && !blurFilterWarned) {
            blurFilterWarned = true
            Log.w("PamNative", "CSS filter: blur() needs Android 12 (API 31); it is a no-op on API ${Build.VERSION.SDK_INT}.")
        }
        if (matrix != null) {
            view.setLayerType(
                View.LAYER_TYPE_HARDWARE,
                Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) },
            )
            state.filterLayer = true
        } else if (state.filterLayer) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
            state.filterLayer = false
        }
    }

    private fun applyBackdrop(view: View, state: NodeState) {
        val container = view as? PamContainer ?: return
        container.setBackdrop(
            state.number(PropKey.BACKDROP_BLUR_RADIUS, 0.0).toFloat().coerceAtLeast(0f) * resourcesDensity(),
            PamBackdrop.colorMatrix(state.properties[PropKey.BACKDROP_COLOR_MATRIX]?.textOrNull()),
        )
    }

    private fun applyAnimationKind(view: View, state: NodeState, kind: Int) {
        state.propertyAnimator?.cancel()
        state.propertyAnimator = null
        view.animate().cancel()
        if (PamMotionPolicy.isReduced(view.context) || kind == 1) {
            view.alpha = state.targetAlpha()
            view.translationX =
                dp(state.number(PropKey.TRANSLATION_X, 0.0).toFloat()).toFloat()
            view.translationY =
                dp(state.number(PropKey.TRANSLATION_Y, 0.0).toFloat()).toFloat()
            view.scaleX = state.number(PropKey.SCALE_X, 1.0).toFloat()
            view.scaleY = state.number(PropKey.SCALE_Y, 1.0).toFloat()
            return
        }
        val target = state.targetAlpha()
        val duration = state.integer(
            PropKey.ANIMATION_DURATION_MS,
            if (kind == 2) 1_500L else 240L,
        ).coerceIn(100L, if (kind == 2) 60_000L else 2_000L)
        if (kind == 2) {
            state.propertyAnimator = ObjectAnimator.ofFloat(
                view,
                View.ALPHA,
                target * 0.55f,
                target,
            ).apply {
                this.duration = duration
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
            return
        }
        val targetTranslationX = dp(state.number(PropKey.TRANSLATION_X, 0.0).toFloat()).toFloat()
        val targetTranslationY = dp(state.number(PropKey.TRANSLATION_Y, 0.0).toFloat()).toFloat()
        val targetScaleX = state.number(PropKey.SCALE_X, 1.0).toFloat()
        val targetScaleY = state.number(PropKey.SCALE_Y, 1.0).toFloat()
        when (kind) {
            3 -> view.alpha = 0f
            4 -> {
                view.alpha = 0f
                view.scaleX = targetScaleX * 0.94f
                view.scaleY = targetScaleY * 0.94f
            }
            5 -> {
                view.alpha = 0f
                view.translationY = targetTranslationY + dp(18f)
            }
            6 -> {
                view.alpha = 0f
                view.translationY = targetTranslationY - dp(18f)
            }
            7 -> {
                view.alpha = target
                view.scaleX = targetScaleX * 0.9f
                view.scaleY = targetScaleY * 0.9f
            }
            8 -> {
                view.alpha = target
                view.translationX = targetTranslationX - dp(8f)
            }
        }
        view.animate()
            .alpha(target)
            .translationX(targetTranslationX)
            .translationY(targetTranslationY)
            .scaleX(targetScaleX)
            .scaleY(targetScaleY)
            .setDuration(duration)
            .setInterpolator(
                when (state.integer(PropKey.ANIMATION_EASING, 3L).toInt()) {
                    1 -> LinearInterpolator()
                    2 -> AccelerateInterpolator()
                    4 -> AccelerateDecelerateInterpolator()
                    5 -> OvershootInterpolator()
                    else -> DecelerateInterpolator()
                },
            )
            .start()
    }

    private fun applyPointerEvents(view: View, state: NodeState, mode: Int) {
        if (view is PamPointerEventsHost) {
            view.setPointerEvents(mode)
            return
        }
        when (mode) {
            2 -> {
                view.isClickable = false
                view.isLongClickable = false
            }
            3 -> view.isClickable = false
            4 -> view.isClickable = true
            else -> {
                view.isClickable = state.properties[PropKey.ON_PRESS] != null
                view.isLongClickable = state.properties[PropKey.ON_LONG_PRESS] != null
            }
        }
    }

    private fun animateOrSet(view: View, state: NodeState, key: PropKey, value: Float) {
        val target = when (key) {
            PropKey.TRANSLATION_X,
            PropKey.TRANSLATION_Y,
            -> dp(value).toFloat()
            else -> value
        }
        if (
            !state.flag(PropKey.ANIMATE_CHANGES, false) ||
            !view.isLaidOut ||
            PamMotionPolicy.isReduced(view.context)
        ) {
            setAnimatedProperty(view, key, target)
            return
        }
        if (animateWithTransitionRule(view, state, key, target)) return
        val animator = view.animate()
            .setDuration(state.integer(PropKey.ANIMATION_DURATION_MS, 180L).coerceIn(1L, 10_000L))
            .setInterpolator(
                when (state.integer(PropKey.ANIMATION_EASING, 4L).toInt()) {
                    1 -> LinearInterpolator()
                    2 -> AccelerateInterpolator()
                    3 -> DecelerateInterpolator()
                    5 -> OvershootInterpolator()
                    else -> AccelerateDecelerateInterpolator()
                },
            )
        when (key) {
            PropKey.OPACITY -> animator.alpha(target)
            PropKey.TRANSLATION_X -> animator.translationX(target)
            PropKey.TRANSLATION_Y -> animator.translationY(target)
            PropKey.SCALE_X -> animator.scaleX(target)
            PropKey.SCALE_Y -> animator.scaleY(target)
            PropKey.ROTATION -> animator.rotation(target)
            else -> return
        }
        animator.start()
    }

    private fun setAnimatedProperty(view: View, key: PropKey, value: Float) {
        when (key) {
            PropKey.OPACITY -> view.alpha = value
            PropKey.TRANSLATION_X -> view.translationX = value
            PropKey.TRANSLATION_Y -> view.translationY = value
            PropKey.SCALE_X -> view.scaleX = value
            PropKey.SCALE_Y -> view.scaleY = value
            PropKey.ROTATION -> view.rotation = value
            else -> Unit
        }
    }

    private fun installSafeArea(view: View, state: NodeState) {
        (view as? ViewGroup)?.let { safeArea ->
            safeArea.clipChildren = true
            safeArea.clipToPadding = true
        }
        view.setOnApplyWindowInsetsListener { target, insets ->
            refreshSafeAreaLayout(target, state, insets)
            insets
        }
        // Insets are normally dispatched before the orientation layout pass.
        // Bounds-dependent consumption must therefore be recalculated once
        // the SafeAreaView owns its final geometry; otherwise a landscape
        // navigation inset (right edge) can remain as a zero bottom inset
        // after returning to portrait on Samsung devices.
        view.addOnLayoutChangeListener {
                target,
                left,
                top,
                right,
                bottom,
                oldLeft,
                oldTop,
                oldRight,
                oldBottom,
            ->
            if (!safeAreaLayoutBoundsChanged(
                    left,
                    top,
                    right,
                    bottom,
                    oldLeft,
                    oldTop,
                    oldRight,
                    oldBottom,
                )
            ) {
                return@addOnLayoutChangeListener
            }
            target.post {
                if (!target.isAttachedToWindow || views[state.id] !== target) return@post
                target.rootWindowInsets?.let { current ->
                    refreshSafeAreaLayout(target, state, current)
                }
                target.requestApplyInsets()
            }
        }
        if (view.isAttachedToWindow) {
            view.requestApplyInsets()
        } else {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(attached: View) {
                    attached.removeOnAttachStateChangeListener(this)
                    attached.requestApplyInsets()
                }

                override fun onViewDetachedFromWindow(detached: View) = Unit
            })
        }
    }

    private fun refreshSafeAreaLayout(
        target: View,
        state: NodeState,
        insets: WindowInsets,
    ) {
        val raw = windowSafeAreaInsets(insets)
        val resolved = if (engineManagedSafeArea) {
            SafeAreaInsets(0, 0, 0, 0)
        } else {
            safeAreaInsetsForView(raw, target)
        }
        state.safeAreaLeftInset = resolved.left
        state.safeAreaTopInset = resolved.top
        state.safeAreaRightInset = resolved.right
        state.safeAreaBottomInset = resolved.bottom
        applySafeAreaLayout(target, state)
    }

    private fun windowSafeAreaInsets(insets: WindowInsets): SafeAreaInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return withStableRootInsets(
                SafeAreaInsets(safe.left, safe.top, safe.right, safe.bottom),
            )
        }

        @Suppress("DEPRECATION")
        val systemLeft = insets.systemWindowInsetLeft
        @Suppress("DEPRECATION")
        val systemTop = insets.systemWindowInsetTop
        @Suppress("DEPRECATION")
        val systemRight = insets.systemWindowInsetRight
        @Suppress("DEPRECATION")
        val systemBottom = insets.systemWindowInsetBottom
        val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            displayCutoutSafeArea(insets)
        } else {
            SafeAreaInsets(0, 0, 0, 0)
        }
        return withStableRootInsets(
            SafeAreaInsets(
                left = max(systemLeft, cutout.left),
                top = max(systemTop, cutout.top),
                right = max(systemRight, cutout.right),
                bottom = max(systemBottom, cutout.bottom),
            ),
        )
    }

    private fun withStableRootInsets(current: SafeAreaInsets): SafeAreaInsets {
        val rootHost = (activity() as? PamActivity)?.rootHost
            ?: return current
        val stable = rootHost.stableSafeAreaInsets
        return SafeAreaInsets(
            left = max(current.left, stable.left),
            top = max(current.top, stable.top),
            right = max(current.right, stable.right),
            bottom = if (rootHost.consumesBottomSystemInset) {
                0
            } else {
                max(current.bottom, stable.bottom)
            },
        )
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun displayCutoutSafeArea(insets: WindowInsets): SafeAreaInsets {
        val cutout = insets.displayCutout ?: return SafeAreaInsets(0, 0, 0, 0)
        return SafeAreaInsets(
            left = cutout.safeInsetLeft,
            top = cutout.safeInsetTop,
            right = cutout.safeInsetRight,
            bottom = cutout.safeInsetBottom,
        )
    }

    private fun safeAreaInsetsForView(raw: SafeAreaInsets, target: View): SafeAreaInsets {
        val decor = activity()?.window?.decorView ?: target.rootView
        if (
            decor.width <= 0 ||
            decor.height <= 0 ||
            target.width <= 0 ||
            target.height <= 0
        ) {
            return raw
        }

        val decorLocation = IntArray(2)
        val targetLocation = IntArray(2)
        decor.getLocationOnScreen(decorLocation)
        target.getLocationOnScreen(targetLocation)

        return safeAreaInsetsForBounds(
            raw = raw,
            window = SafeAreaBounds(
                left = decorLocation[0],
                top = decorLocation[1],
                right = decorLocation[0] + decor.width,
                bottom = decorLocation[1] + decor.height,
            ),
            target = SafeAreaBounds(
                left = targetLocation[0],
                top = targetLocation[1],
                right = targetLocation[0] + target.width,
                bottom = targetLocation[1] + target.height,
            ),
        )
    }

    private fun applySafeAreaLayout(view: View, state: NodeState) {
        val paddingMode = state.integer(
            PropKey.SAFE_AREA_MODE,
            SAFE_AREA_PADDING.toLong(),
        ).toInt() == SAFE_AREA_PADDING
        view.setPadding(
            if (paddingMode && state.flag(PropKey.SAFE_AREA_LEFT, true)) {
                state.safeAreaLeftInset
            } else {
                0
            },
            if (paddingMode && state.flag(PropKey.SAFE_AREA_TOP, true)) {
                state.safeAreaTopInset
            } else {
                0
            },
            if (paddingMode && state.flag(PropKey.SAFE_AREA_RIGHT, true)) {
                state.safeAreaRightInset
            } else {
                0
            },
            if (
                paddingMode &&
                state.flag(PropKey.SAFE_AREA_BOTTOM_EDGE, true)
            ) {
                state.safeAreaBottomInset
            } else {
                0
            },
        )
        applyLayout(state.id)
        applyHostedChildLayouts(state.id)
    }

    private fun configureLegacyKeyboardInsets(view: View, state: NodeState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return
        if (state.keyboardModalHost != null) return
        if (!state.flag(PropKey.KEYBOARD_AVOIDING_ENABLED, true)) {
            state.legacyKeyboardSubscription?.close()
            state.legacyKeyboardSubscription = null
            return
        }
        if (state.legacyKeyboardSubscription != null) return
        state.legacyKeyboardSubscription = (host as? PamRootHost)?.legacyImeInsets?.subscribe(view) { inset ->
            state.keyboardInset = ownWindowImeInset(view, inset, state.keyboardInset)
            if (!state.keyboardAnimating) applyKeyboardAvoidance(view, state)
        }
    }

    /**
     * A KeyboardAvoidingView inside a Modal/BottomSheet lives in that
     * modal's Dialog window. The activity window never sees that window's
     * IME (and its inset listeners belong to the screen's own KAV), so the
     * view follows the modal's IME feed, frame by frame while the IME
     * animates. Resize and padding are laid out by the engine from the same
     * feed (PamModalHost.onSurfaceKeyboardInset); pan and interactive
     * translate natively.
     */
    private fun installModalKeyboardInsets(view: View, state: NodeState, modal: PamModalHost) {
        state.keyboardModalHost = modal
        state.modalKeyboardSubscription = modal.addSurfaceKeyboardListener { inset, animating ->
            if (nodes[state.id] !== state) return@addSurfaceKeyboardListener
            state.keyboardInset = inset
            state.keyboardAnimating = animating
            applyKeyboardAvoidance(view, state)
        }
        val selfLayoutListener = View.OnLayoutChangeListener {
                _,
                _,
                top,
                _,
                bottom,
                _,
                oldTop,
                _,
                oldBottom,
            ->
            if (
                (top != oldTop || bottom != oldBottom) &&
                !state.keyboardAnimating &&
                (state.keyboardInset > 0 || view.translationY != 0f)
            ) {
                applyKeyboardAvoidance(view, state)
            }
        }
        state.keyboardSelfLayoutListener = selfLayoutListener
        view.addOnLayoutChangeListener(selfLayoutListener)
    }

    /** Engine-laid-out avoidance: resize/padding inside a modal window. */
    private fun engineAvoidsKeyboard(state: NodeState): Boolean =
        state.keyboardModalHost != null && (
            state.keyboardBehavior == KEYBOARD_RESIZE ||
                state.keyboardBehavior == KEYBOARD_PADDING
            )

    /**
     * Forwards a modal window's IME to the engine in root-window
     * coordinates: the inset is measured from the dialog window's bottom,
     * the engine's viewport from the PAM host's.
     */
    private fun forwardSurfaceKeyboard(id: Long, modal: PamModalHost, inset: Int) {
        val callback = onSurfaceKeyboardInset ?: return
        val engineInset = if (inset <= 0) {
            0
        } else {
            val windowBottom = modal.windowBottomOnScreen()
            if (windowBottom == null || !host.isAttachedToWindow) {
                inset
            } else {
                val location = IntArray(2)
                host.getLocationOnScreen(location)
                surfaceKeyboardInsetForHost(
                    imeInset = inset,
                    windowBottom = windowBottom,
                    hostBottom = location[1] + host.height,
                )
            }
        }
        callback(id, engineInset / resourcesDensity())
    }

    private fun installKeyboardInsets(view: View, state: NodeState) {
        modalAncestor(state.id)?.let { modal ->
            installModalKeyboardInsets(view, state, modal)
            return
        }
        configureLegacyKeyboardInsets(view, state)
        val layoutListener = View.OnLayoutChangeListener {
                _,
                _,
                _,
                _,
                bottom,
                _,
                _,
                _,
                oldBottom,
            ->
            val height = bottom.coerceAtLeast(0)
            if (state.keyboardBaseHeight == 0 || height > state.keyboardBaseHeight) {
                state.keyboardBaseHeight = height
            }
            if (oldBottom != bottom && state.keyboardBaseHeight > 0) {
                val platformInset = currentPlatformImeInset()
                state.keyboardInset = resolvedKeyboardInset(
                    platformInset = platformInset,
                    baselineHeight = state.keyboardBaseHeight,
                    currentHeight = height,
                    minimumKeyboardHeight = dp(80f),
                )
                if (!state.keyboardAnimating) applyKeyboardAvoidance(view, state)
            }
        }
        state.keyboardLayoutListener = layoutListener
        host.addOnLayoutChangeListener(layoutListener)
        // The engine lays a trailing panning KAV out above the IME. When that
        // frame lands, recompute the native residual translation from the new
        // position so the view is never moved twice.
        val selfLayoutListener = View.OnLayoutChangeListener {
                _,
                _,
                top,
                _,
                bottom,
                _,
                oldTop,
                _,
                oldBottom,
            ->
            if (
                (top != oldTop || bottom != oldBottom) &&
                !state.keyboardAnimating &&
                (state.keyboardInset > 0 || view.translationY != 0f)
            ) {
                applyKeyboardAvoidance(view, state)
            }
        }
        state.keyboardSelfLayoutListener = selfLayoutListener
        view.addOnLayoutChangeListener(selfLayoutListener)
        host.setOnApplyWindowInsetsListener { target, insets ->
            // The root host's own inset handling (stable safe areas and the
            // engine IME inset) must keep running while a KAV is mounted.
            val dispatched = target.onApplyWindowInsets(insets)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                state.keyboardInset = ownWindowImeInset(
                    view,
                    visibleImeInset(
                        rawInset = insets.getInsets(WindowInsets.Type.ime()).bottom,
                        visible = insets.isVisible(WindowInsets.Type.ime()),
                    ),
                    state.keyboardInset,
                )
                if (!state.keyboardAnimating) applyKeyboardAvoidance(view, state)
            }
            dispatched
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            host.setWindowInsetsAnimationCallback(
                object : WindowInsetsAnimation.Callback(
                    WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
                ) {
                    override fun onPrepare(animation: WindowInsetsAnimation) {
                        if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                            state.keyboardAnimating = true
                        }
                    }

                    override fun onProgress(
                        insets: WindowInsets,
                        runningAnimations: MutableList<WindowInsetsAnimation>,
                    ): WindowInsets {
                        val nextInset = insets.getInsets(WindowInsets.Type.ime()).bottom
                        state.keyboardInset = ownWindowImeInset(view, nextInset, state.keyboardInset)
                        return insets
                    }

                    override fun onEnd(animation: WindowInsetsAnimation) {
                        if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                            state.keyboardAnimating = false
                            state.keyboardInset = ownWindowImeInset(
                                view,
                                currentPlatformImeInset(),
                                state.keyboardInset,
                            )
                            (host as? PamRootHost)?.reconcileImeInset()
                            applyKeyboardAvoidance(view, state)
                            view.post { restoreKeyboardAvoidingInput(state) }
                        }
                    }
                },
            )
        }
        host.requestApplyInsets()
        // A KAV can mount after an IME animation has started. Android does not guarantee
        // that a callback registered mid-animation receives onEnd, so reconcile against
        // the authoritative root inset after the current traversal and once more after
        // the animation settling window.
        reconcileMountedKeyboardInsets(view, state)
    }

    /**
     * The IME a KeyboardAvoidingView may avoid. While another window (a
     * BottomSheet or dialog) has input focus, its keyboard does not cover this
     * window's composer: only hiding is applied, like PamRootHost.
     */
    private fun ownWindowImeInset(view: View, inset: Int, previous: Int): Int =
        if (inset > 0 && !view.hasWindowFocus()) minOf(previous, inset) else inset

    private fun currentPlatformImeInset(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return (host as? PamRootHost)?.legacyImeInsets?.bottom ?: 0
        val insets = host.rootWindowInsets ?: return 0
        return visibleImeInset(
            rawInset = insets.getInsets(WindowInsets.Type.ime()).bottom,
            visible = insets.isVisible(WindowInsets.Type.ime()),
        )
    }

    private fun reconcileMountedKeyboardInsets(view: View, state: NodeState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        fun reconcile() {
            if (nodes[state.id] !== state) return
            val inset = ownWindowImeInset(view, currentPlatformImeInset(), state.keyboardInset)
            (host as? PamRootHost)?.reconcileImeInset()
            if (state.keyboardInset != inset || inset == 0) {
                state.keyboardInset = inset
                state.keyboardAnimating = false
                applyKeyboardAvoidance(view, state)
            }
        }
        view.postOnAnimation(::reconcile)
        view.postDelayed(::reconcile, 300L)
    }

    private fun applyKeyboardAvoidance(view: View, state: NodeState) {
        val enabled = state.flag(PropKey.KEYBOARD_AVOIDING_ENABLED, true)
        val offset = dp(
            state.number(PropKey.KEYBOARD_VERTICAL_OFFSET, 0.0).toFloat(),
        )
        val keyboard = if (enabled) {
            keyboardOverlap(view, state.keyboardInset, offset, state.keyboardModalHost)
        } else {
            0
        }
        val engineManaged = engineAvoidsKeyboard(state)
        if (keyboard > 0) {
            ((host.findFocus() as? EditText) ?: lastFocusedInput)
                ?.let { state.keyboardFocusedInput = it }
        } else {
            state.keyboardFocusedInput = null
            if (lastFocusedInput?.hasFocus() != true) lastFocusedInput = null
        }
        // Padding needs an IME-sized flex viewport even without a scroll descendant, so
        // bottom controls reflow above the keyboard. Scroll descendants additionally receive
        // their native avoidance inset below.
        val viewportInset = if (
            !engineManaged && (
                state.keyboardBehavior == KEYBOARD_RESIZE ||
                    state.keyboardBehavior == KEYBOARD_PADDING
                )
        ) keyboard else 0
        if (state.keyboardAvoidingViewportInset != viewportInset) {
            state.keyboardAvoidingViewportInset = viewportInset
            val applyViewportLayout = {
                if (nodes[state.id] === state) {
                    scheduleKeyboardViewportDescendantLayout(view, state)
                    applyLayout(state.id)
                }
            }
            if (host.isInLayout || view.isInLayout) {
                view.post { applyViewportLayout() }
            } else {
                applyViewportLayout()
            }
        }
        when (state.keyboardBehavior) {
            KEYBOARD_PAN -> {
                view.translationY = -keyboard.toFloat()
                view.setPadding(0, 0, 0, 0)
                liftTranslatedKeyboardView(view, state, keyboard > 0)
                // A panning container may extend into a system-inset region even
                // while the IME is closed. Keep its interactive descendants in
                // the geometry-aware dispatcher so the first input tap is not
                // clipped by an ancestor's pre-inset bounds.
                updateTranslatedTouchTarget(
                    view,
                    enabled,
                    includePressables = keyboard > 0,
                )
            }
            KEYBOARD_INTERACTIVE -> {
                val root = if (state.keyboardModalHost != null) {
                    view.rootView
                } else {
                    (activity() as? PamActivity)?.rootHost ?: host
                }
                val rootLocation = IntArray(2)
                val viewLocation = IntArray(2)
                root.getLocationOnScreen(rootLocation)
                view.getLocationOnScreen(viewLocation)
                val originalTop = viewLocation[1] - view.translationY.toInt()
                val safeTop = (root as? PamRootHost)?.stableSafeAreaInsets?.top ?: 0
                val translation = interactiveKeyboardTranslation(
                    keyboardOverlap = keyboard,
                    originalTop = originalTop,
                    minimumTop = rootLocation[1] + safeTop + dp(16f),
                )
                view.translationY = -translation.toFloat()
                view.setPadding(0, 0, 0, 0)
                updateTranslatedTouchTarget(
                    view,
                    enabled,
                    includePressables = keyboard > 0,
                )
            }
            KEYBOARD_PADDING -> {
                view.translationY = 0f
                view.setPadding(0, 0, 0, 0)
                updateTranslatedTouchTarget(view, false)
            }
            else -> {
                view.translationY = 0f
                view.setPadding(0, 0, 0, 0)
                updateTranslatedTouchTarget(view, false)
            }
        }
        val scroll = when (state.keyboardBehavior) {
            KEYBOARD_PAN -> precedingScrollContainer(state)
            KEYBOARD_PADDING,
            KEYBOARD_RESIZE,
            -> containedScrollContainer(state)
            else -> null
        }
        val scrollId = scroll?.first ?: 0L
        if (state.keyboardAvoidingScrollId != scrollId) {
            if (state.keyboardAvoidingScrollId != 0L) {
                views[state.keyboardAvoidingScrollId]
                    ?.let { it as? PamScrollContainer }
                    ?.setKeyboardAvoidanceInset(0)
            }
            state.keyboardAvoidingScrollId = scrollId
        }
        scroll?.second?.let { container ->
            container.setKeyboardAvoidanceInset(
                if (state.keyboardBehavior == KEYBOARD_RESIZE || engineManaged) 0 else keyboard,
            )
            if (keyboard > 0 && !state.keyboardAnimating) {
                state.keyboardFocusedInput?.let { input ->
                    if (state.keyboardBehavior == KEYBOARD_RESIZE || engineManaged) {
                        container.ensureViewportTargetVisible(input)
                    } else {
                        container.ensureKeyboardTargetVisible(input)
                    }
                }
            }
        }
        if (keyboard > 0 && !state.keyboardAnimating) {
            view.post { restoreKeyboardAvoidingInput(state) }
        }
    }

    /**
     * A translated container is drawn where its later siblings are not, so it
     * must not stay underneath an earlier sibling with a higher z-index (a chat
     * timeline with `z-index: 1`): lift it above its siblings while
     * translated and restore the authored z-index afterwards.
     */
    private fun liftTranslatedKeyboardView(view: View, state: NodeState, translated: Boolean) {
        val authored = runCatching {
            state.properties[PropKey.Z_INDEX]?.decimal()?.toFloat()
        }.getOrNull() ?: 0f
        val parent = view.parent as? ViewGroup
        var target = authored
        if (translated && parent != null) {
            for (index in 0 until parent.childCount) {
                val sibling = parent.getChildAt(index)
                if (sibling !== view) target = max(target, sibling.z + 1f)
            }
        }
        if (view.z != target) view.z = target
    }

    private fun keyboardOverlap(
        view: View,
        keyboardInset: Int,
        offset: Int,
        modal: PamModalHost? = null,
    ): Int {
        if (keyboardInset <= 0) return 0
        if (modal != null) {
            // The modal's IME is measured from its own Dialog window bottom.
            val windowBottom = modal.windowBottomOnScreen() ?: return 0
            val viewLocation = IntArray(2)
            view.getLocationOnScreen(viewLocation)
            return keyboardOverlapForBounds(
                originalBottom = viewLocation[1] - view.translationY + view.height,
                windowBottom = windowBottom,
                keyboardInset = keyboardInset,
                offset = offset,
            )
        }
        val root = (activity() as? PamActivity)?.rootHost ?: host
        val rootLocation = IntArray(2)
        val viewLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        view.getLocationOnScreen(viewLocation)
        val originalBottom = viewLocation[1] - view.translationY + view.height
        val pamActivity = activity() as? PamActivity
        val windowBottom = if (pamActivity != null && !pamActivity.isInMultiWindowMode) {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            pamActivity.windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.heightPixels
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && pamActivity != null) {
            val metrics = if (pamActivity.isInMultiWindowMode) {
                pamActivity.window.windowManager.currentWindowMetrics
            } else {
                pamActivity.window.windowManager.maximumWindowMetrics
            }
            metrics.bounds.bottom
        } else {
            // Embedded hosts: the window bottom is the decor view's bottom
            // (edge-to-edge hosts already extend behind the navigation bar).
            val decor = root.rootView
            val decorLocation = IntArray(2)
            decor.getLocationOnScreen(decorLocation)
            max(
                decorLocation[1] + decor.height,
                rootLocation[1] + root.height,
            )
        }
        // WindowInsets.Type.ime() is a bottom inset in the same full-window
        // coordinate space as `windowBottom`. Removing the top safe-area
        // inset here leaves exactly that many pixels hidden behind the IME.
        // A safe-area/flex parent can temporarily extend this view beyond the
        // physical window while Android and the PHP layout settle after a
        // rotation. That overflow is already removed by viewport compensation;
        // counting it here would subtract the same pixels twice and collapse
        // the keyboard-safe viewport to zero.
        return keyboardOverlapForBounds(
            originalBottom = originalBottom,
            windowBottom = windowBottom,
            keyboardInset = keyboardInset,
            offset = offset,
        )
    }

    private fun applyDescendantLayouts(parentId: Long) {
        children[parentId]?.forEach { childId ->
            applyLayout(childId)
            applyDescendantLayouts(childId)
        }
    }

    private fun applyHostedChildLayouts(parentId: Long) {
        children[parentId]?.forEach { childId ->
            if (views[childId] == null) {
                // Layout-only Row/Column/View nodes have no Android View of
                // their own. Their nearest materialized descendants are
                // hosted directly by this ancestor and must be reconciled
                // whenever its native bounds change, regardless of mutation
                // order within the frame.
                applyHostedChildLayouts(childId)
            } else {
                applyLayout(childId)
            }
        }
    }

    private fun scheduleKeyboardViewportDescendantLayout(
        view: View,
        state: NodeState,
    ) {
        val generation = ++state.keyboardViewportReconcileGeneration
        fun reconcile(attempt: Int) {
            view.postDelayed({
                if (
                    nodes[state.id] !== state ||
                    generation != state.keyboardViewportReconcileGeneration
                ) {
                    return@postDelayed
                }
                if (!host.isInLayout && !view.isInLayout) {
                    // Flex descendants must be recomputed from the KAV's
                    // measured viewport. Insets can transition through old,
                    // zero and new values before a single Android layout pass,
                    // so an onLayout callback alone is not a reliable signal.
                    applyDescendantLayouts(state.id)
                    ensureFocusedInputVisibleAfterCommit()
                }
                if (attempt < KEYBOARD_VIEWPORT_RECONCILE_RETRIES) {
                    reconcile(attempt + 1)
                }
            }, if (attempt == 0) 0L else KEYBOARD_VIEWPORT_RECONCILE_RETRY_MS)
        }
        reconcile(attempt = 0)
    }

    private fun updateTranslatedTouchTarget(
        view: View,
        translated: Boolean,
        includePressables: Boolean = true,
    ) {
        (activity() as? PamActivity)?.rootHost?.replaceTranslatedTouchTargets(
            view,
            translated,
            includePressables,
        )
        val delegateHost = host
        val existing = delegateHost.touchDelegate as? PamTouchDelegateGroup
        if (!translated) {
            existing?.remove(view)
            if (existing?.isEmpty() == true) delegateHost.touchDelegate = null
            return
        }
        delegateHost.post {
            if (!view.isAttachedToWindow) return@post
            val hostLocation = IntArray(2)
            val viewLocation = IntArray(2)
            delegateHost.getLocationOnScreen(hostLocation)
            view.getLocationOnScreen(viewLocation)
            val left = viewLocation[0] - hostLocation[0]
            val top = viewLocation[1] - hostLocation[1]
            val bounds = Rect(left, top, left + view.width, top + view.height)
            val group = delegateHost.touchDelegate as? PamTouchDelegateGroup
                ?: PamTouchDelegateGroup(delegateHost).also {
                    delegateHost.touchDelegate = it
                }
            group.updateTranslated(view, bounds)
        }
    }

    private fun restoreKeyboardAvoidingInput(state: NodeState) {
        if (state.keyboardInset <= 0) return
        val input = state.keyboardFocusedInput ?: return
        if (!input.isAttachedToWindow) return
        if (!input.hasFocus()) input.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun requestAutoFocus(view: View, id: Long) {
        // The sheet/dialog window must let the IME show when it gains focus
        // (see PamModalHost.prepareAutoFocusKeyboard).
        if (view is PamEditText && view.showSoftInputOnFocus) {
            modalAncestor(id)?.prepareAutoFocusKeyboard()
        }
        attemptAutoFocus(view, attempt = 0)
    }

    private fun modalAncestor(id: Long): PamModalHost? {
        var current = nodes[id]?.parent ?: return null
        var depth = 0
        while (current != 0L && depth++ < MAX_VIRTUAL_DEPTH) {
            val state = nodes[current] ?: return null
            if (state.kind == NodeKind.MODAL) return views[current] as? PamModalHost
            current = state.parent
        }
        return null
    }

    private fun requestAutoFocusDescendant(rootId: Long) {
        val pending = ArrayDeque<Long>()
        pending.add(rootId)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            val state = nodes[id] ?: continue
            val view = views[id]
            if (
                state.flag(PropKey.AUTO_FOCUS, false) &&
                view is PamEditText &&
                view.visibility == View.VISIBLE
            ) {
                requestAutoFocus(view, id)
                return
            }
            children[id]?.forEach(pending::addLast)
        }
    }

    private fun attemptAutoFocus(view: View, attempt: Int) {
        val deadline = SystemClock.uptimeMillis() + AUTO_FOCUS_DEADLINE_MS
        attemptAutoFocus(view, deadline, retry = attempt > 0)
    }

    /**
     * Focuses an `autoFocus` input once it is attached and focusable (inside a
     * Modal/BottomSheet that is still being presented), bounded by wall-clock
     * time rather than attempts: a busy UI thread can run many short retries
     * in one burst after a long frame.
     */
    private fun attemptAutoFocus(view: View, deadline: Long, retry: Boolean) {
        view.postDelayed({
            if (!view.isAttachedToWindow || !view.hasFocus() && !view.requestFocus()) {
                if (SystemClock.uptimeMillis() < deadline) {
                    attemptAutoFocus(view, deadline, retry = true)
                } else {
                    Log.i(AUTO_FOCUS_LOG, "gave up focusing ${view.transitionName}: attached=${view.isAttachedToWindow}")
                }
                return@postDelayed
            }
            val input = view as? PamEditText ?: return@postDelayed
            if (!input.showSoftInputOnFocus) return@postDelayed
            showAutoFocusKeyboard(input, SystemClock.uptimeMillis() + AUTO_FOCUS_DEADLINE_MS, retry = false)
        }, if (retry) AUTO_FOCUS_RETRY_MS else 0L)
    }

    /**
     * Opens the IME for an auto-focused input. The IME ignores requests from a
     * window without input focus (a Dialog/BottomSheet that was just shown or
     * is still under a closing overlay) and, on Android 11-12, requests for a
     * view it does not serve yet: after the window gains focus the IME starts
     * serving the view asynchronously. So: wait for the input's own window
     * focus, ask again when the IME creates the view's input connection, and
     * keep asking (insets controller + IMM) until the IME is reported visible
     * or the deadline passes.
     */
    private fun showAutoFocusKeyboard(input: PamEditText, deadline: Long, retry: Boolean) {
        input.onInputConnectionCreated = {
            if (input.hasFocus() && input.hasWindowFocus() && !autoFocusImeVisible(input)) {
                Log.i(AUTO_FOCUS_LOG, "input connection created; showing the IME")
                requestAutoFocusIme(input)
            }
        }
        input.postDelayed({
            if (!input.isAttachedToWindow || !input.hasFocus()) {
                Log.i(AUTO_FOCUS_LOG, "stopped: attached=${input.isAttachedToWindow} focused=${input.hasFocus()}")
                input.onInputConnectionCreated = null
                return@postDelayed
            }
            if (!input.hasWindowFocus()) {
                Log.i(AUTO_FOCUS_LOG, "waiting for window focus")
                awaitWindowFocus(input) {
                    showAutoFocusKeyboard(
                        input,
                        SystemClock.uptimeMillis() + AUTO_FOCUS_DEADLINE_MS,
                        retry = false,
                    )
                }
                return@postDelayed
            }
            if (autoFocusImeVisible(input)) {
                Log.i(AUTO_FOCUS_LOG, "IME visible")
                input.onInputConnectionCreated = null
                return@postDelayed
            }
            Log.i(AUTO_FOCUS_LOG, "showing the IME (retry=$retry)")
            requestAutoFocusIme(input)
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                SystemClock.uptimeMillis() < deadline
            ) {
                showAutoFocusKeyboard(input, deadline, retry = true)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                input.onInputConnectionCreated = null
            } else {
                // Without IME visibility, keep only the connection hook armed.
                val hook = input.onInputConnectionCreated
                input.postDelayed({
                    if (input.onInputConnectionCreated === hook) input.onInputConnectionCreated = null
                }, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
            }
        }, if (retry) AUTO_FOCUS_KEYBOARD_RETRY_MS else 0L)
    }

    private fun requestAutoFocusIme(input: PamEditText) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            input.windowInsetsController?.show(WindowInsets.Type.ime())
        }
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun autoFocusImeVisible(input: View): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            input.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    /** Runs [action] once [view]'s window gains input focus (one pending wait per view). */
    private fun awaitWindowFocus(view: View, action: () -> Unit) {
        if (view.getTag(dev.pam.nativeapp.R.id.pam_await_window_focus) != null) return
        val observer = view.viewTreeObserver
        val listener = object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
            override fun onWindowFocusChanged(hasFocus: Boolean) {
                if (!hasFocus) return
                view.setTag(dev.pam.nativeapp.R.id.pam_await_window_focus, null)
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(this)
                view.post(action)
            }
        }
        view.setTag(dev.pam.nativeapp.R.id.pam_await_window_focus, listener)
        observer.addOnWindowFocusChangeListener(listener)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                if (v.getTag(dev.pam.nativeapp.R.id.pam_await_window_focus) === listener) {
                    v.setTag(dev.pam.nativeapp.R.id.pam_await_window_focus, null)
                    if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
                }
            }
        })
    }

    private fun containedScrollContainer(
        state: NodeState,
    ): Pair<Long, PamScrollContainer>? {
        val descendants = children[state.id] ?: return null
        for (id in descendants) {
            firstScrollContainer(id)?.let { return it }
        }
        return null
    }

    private fun firstScrollContainer(id: Long): Pair<Long, PamScrollContainer>? {
        (views[id] as? PamScrollContainer)?.let { return id to it }
        val descendants = children[id] ?: return null
        for (descendant in descendants) {
            firstScrollContainer(descendant)?.let { return it }
        }
        return null
    }

    private fun precedingScrollContainer(
        state: NodeState,
    ): Pair<Long, PamScrollContainer>? {
        val siblings = children[state.parent] ?: return null
        val position = siblings.indexOf(state.id)
        if (position <= 0) return null
        for (index in position - 1 downTo 0) {
            lastScrollContainer(siblings[index])?.let { return it }
        }
        return null
    }

    private fun lastScrollContainer(id: Long): Pair<Long, PamScrollContainer>? {
        (views[id] as? PamScrollContainer)?.let { return id to it }
        val descendants = children[id] ?: return null
        for (index in descendants.lastIndex downTo 0) {
            lastScrollContainer(descendants[index])?.let { return it }
        }
        return null
    }

    private fun applyMergedStatusBar() {
        val window = activity()?.window ?: return
        val defaults = statusBarDefaults
            ?: captureStatusBarDefaults().also { statusBarDefaults = it }
        var merged = defaults
        val mounted = ArrayList<NodeState>(statusBarIds.size)
        for (id in statusBarIds) {
            val state = nodes[id] ?: continue
            if (views[state.id]?.let(::isInActiveNavigationRoute) == true) {
                mounted += state
            }
        }
        mounted.sortBy(NodeState::mountOrder)
        mounted.forEach { state ->
            if (state.properties.containsKey(PropKey.STATUS_BAR_COLOR)) {
                merged = merged.copy(
                    color = state.integer(
                        PropKey.STATUS_BAR_COLOR,
                        merged.color.toLong(),
                    ).toInt(),
                )
            }
            if (state.properties.containsKey(PropKey.STATUS_BAR_STYLE)) {
                merged = merged.copy(
                    appearance = state.integer(
                        PropKey.STATUS_BAR_STYLE,
                        merged.appearance.toLong(),
                    ).toInt(),
                )
            }
            if (state.properties.containsKey(PropKey.STATUS_BAR_HIDDEN)) {
                merged = merged.copy(
                    hidden = state.flag(PropKey.STATUS_BAR_HIDDEN, merged.hidden),
                )
            }
            if (state.properties.containsKey(PropKey.STATUS_BAR_ANIMATED)) {
                merged = merged.copy(
                    animated = state.flag(PropKey.STATUS_BAR_ANIMATED, merged.animated),
                )
            }
            if (state.properties.containsKey(PropKey.STATUS_BAR_TRANSLUCENT)) {
                merged = merged.copy(
                    translucent = state.flag(
                        PropKey.STATUS_BAR_TRANSLUCENT,
                        merged.translucent,
                    ),
                )
            }
            if (state.properties.containsKey(PropKey.NAVIGATION_BAR_HIDDEN)) {
                merged = merged.copy(
                    navigationBarHidden = state.flag(
                        PropKey.NAVIGATION_BAR_HIDDEN,
                        merged.navigationBarHidden,
                    ),
                )
            }
        }
        // Re-applying unchanged bars every commit costs window/insets
        // controller round trips (and inset dispatches) on the UI thread.
        if (merged == appliedStatusBar) return
        appliedStatusBar = merged
        applyStatusBarConfig(merged)
        for (id in localModalIds) {
            (views[id] as? PamModalHost)?.applyStatusBar(
                color = merged.color,
                useDarkIcons = merged.appearance == STATUS_BAR_DARK,
                hidden = merged.hidden,
                translucent = merged.translucent,
            )
        }
    }

    private fun isInActiveNavigationRoute(view: View): Boolean {
        var routeChild = view
        var ancestor = routeChild.parent
        while (ancestor is View) {
            if (ancestor is PamNavigationHost && !ancestor.isActiveRoute(routeChild)) {
                return false
            }
            routeChild = ancestor
            ancestor = routeChild.parent
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun captureStatusBarDefaults(): StatusBarConfig {
        val window = activity()?.window
            ?: return StatusBarConfig(Color.BLACK, STATUS_BAR_LIGHT, false, false, false, false)
        val decor = window.decorView
        val lightIcons = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val darkTheme = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            // A newly created controller can still expose the previous
            // Activity's appearance until the first inset traversal. The
            // default must be deterministic; explicit PAM StatusBar nodes are
            // merged below and remain authoritative.
            !useDarkStatusBarIcons(
                systemBarsAppearance = null,
                darkTheme = darkTheme,
                lightStatusBarMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
        } else {
            decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR == 0
        }
        val hidden = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            decor.rootWindowInsets?.isVisible(WindowInsets.Type.statusBars()) == false
        } else {
            decor.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != 0
        }
        val translucent =
            decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN != 0

        return StatusBarConfig(
            color = window.statusBarColor,
            appearance = if (lightIcons) STATUS_BAR_LIGHT else STATUS_BAR_DARK,
            hidden = hidden,
            animated = false,
            translucent = translucent,
            navigationBarHidden = false,
        )
    }

    private fun applyStatusBarConfig(config: StatusBarConfig) {
        applyStatusBarTranslucent(config.translucent)
        applyStatusBarColor(config.color, config.animated)
        applyStatusBarAppearance(config.appearance)
        applyStatusBarHidden(config.hidden)
        applyNavigationBarHidden(config.navigationBarHidden)
    }

    @Suppress("DEPRECATION")
    private fun applyNavigationBarHidden(hidden: Boolean) {
        val window = activity()?.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.windowInsetsController?.let { controller ->
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (hidden) {
                    controller.hide(WindowInsets.Type.navigationBars())
                } else {
                    controller.show(WindowInsets.Type.navigationBars())
                }
            }
            return
        }
        window.decorView.systemUiVisibility = if (hidden) {
            window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            window.decorView.systemUiVisibility and
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv() and
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyStatusBarAppearance(value: Int) {
        val decor = activity()?.window?.decorView ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = decor.windowInsetsController ?: return
            val useDarkIcons = value == STATUS_BAR_DARK
            controller.setSystemBarsAppearance(
                if (useDarkIcons) {
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                } else {
                    0
                },
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            )
            return
        }
        decor.systemUiVisibility = if (value == STATUS_BAR_DARK) {
            decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    private fun applyStatusBarHidden(hidden: Boolean) {
        val window = activity()?.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hidden) {
                window.decorView.windowInsetsController?.hide(WindowInsets.Type.statusBars())
            } else {
                window.decorView.windowInsetsController?.show(WindowInsets.Type.statusBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (hidden) {
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_FULLSCREEN
            } else {
                window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN.inv()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyStatusBarColor(color: Int, animated: Boolean) {
        val window = activity()?.window ?: return
        (host as? PamRootHost)?.setStatusBarSurfaceColor(color)
        if (Build.VERSION.SDK_INT >= 35) {
            window.decorView.setBackgroundColor(color)
            return
        }
        statusBarColorAnimator?.cancel()
        if (!animated || window.statusBarColor == color) {
            window.statusBarColor = color
            return
        }
        statusBarColorAnimator = ValueAnimator.ofObject(
            ArgbEvaluator(),
            window.statusBarColor,
            color,
        ).apply {
            duration = STATUS_BAR_ANIMATION_DURATION_MS
            addUpdateListener { animation ->
                window.statusBarColor = animation.animatedValue as Int
            }
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun applyStatusBarTranslucent(translucent: Boolean) {
        if (Build.VERSION.SDK_INT >= 35) return
        val window = activity()?.window ?: return
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        val decor = window.decorView
        decor.systemUiVisibility = if (translucent) {
            decor.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        } else {
            decor.systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv()
        }
    }

    private fun loadImage(view: View, state: NodeState) {
        val image = pamImageView(view) ?: return
        val source = (state.properties[PropKey.SOURCE] as? PropValue.Text)
            ?.value
            ?: run {
                imageLoader.cancel(image)
                return
            }
        val request = NativeImageRequest(
            source = source,
            defaultSource = state.textOrNull(PropKey.IMAGE_DEFAULT_SOURCE),
            loadingIndicatorSource =
                state.textOrNull(PropKey.IMAGE_LOADING_INDICATOR_SOURCE),
            sourceSet = state.textOrNull(PropKey.IMAGE_SOURCE_SET),
            requestHeaders = state.textOrNull(PropKey.IMAGE_REQUEST_HEADERS),
            fadeDurationMs = state.integer(
                PropKey.IMAGE_FADE_DURATION_MS,
                300L,
            ).toInt().coerceIn(0, 10_000),
            resizeMethod = state.integer(
                PropKey.IMAGE_RESIZE_METHOD,
                IMAGE_RESIZE_AUTO.toLong(),
            ).toInt(),
            resizeMultiplier = state.number(
                PropKey.IMAGE_RESIZE_MULTIPLIER,
                1.0,
            ).toFloat(),
            progressiveRenderingEnabled = state.flag(
                PropKey.IMAGE_PROGRESSIVE_RENDERING_ENABLED,
                false,
            ),
            cachePolicy = state.integer(
                PropKey.IMAGE_CACHE_POLICY,
                IMAGE_CACHE_DEFAULT.toLong(),
            ).toInt(),
            mediaCachePolicy = state.integer(
                PropKey.MEDIA_CACHE_POLICY,
                MEDIA_CACHE_MEMORY_AND_DISK.toLong(),
            ).toInt(),
            mediaCacheKey = state.textOrNull(PropKey.MEDIA_CACHE_KEY),
            mediaCacheMaxAgeMs = state.integer(PropKey.MEDIA_CACHE_MAX_AGE_MS, 0),
            mediaCacheMaxBytes = state.integer(PropKey.MEDIA_CACHE_MAX_BYTES, 0),
            mediaCacheChecksum = state.textOrNull(PropKey.MEDIA_CACHE_CHECKSUM),
            repeat = state.integer(PropKey.IMAGE_FIT, 1L) == 5L,
        )
        imageLoader.load(
            request,
            image,
            NativeImageCallbacks(
                onStart = {
                    state.imageLoading = true
                    if (
                        nodes[state.id] === state &&
                        state.properties[PropKey.ON_IMAGE_LOAD_START] != null
                    ) {
                        dispatch(state.id, EVENT_IMAGE_LOAD_START)
                    }
                },
                onProgress = { loaded, total ->
                    if (state.properties[PropKey.ON_MEDIA_CACHE_PROGRESS] != null) {
                        dispatchBytes(
                            state.id,
                            EventKind.MEDIA_CACHE_PROGRESS.value,
                            mediaCachePayload(
                                state.textOrNull(PropKey.MEDIA_CACHE_KEY).orEmpty(),
                                loaded,
                                total,
                                false,
                            ),
                        )
                    }
                    if (state.properties[PropKey.ON_IMAGE_PROGRESS] == null) {
                        return@NativeImageCallbacks
                    }
                    state.imageProgressLoaded = loaded
                    state.imageProgressTotal = total
                    if (!state.imageProgressScheduled) {
                        state.imageProgressScheduled = true
                        Choreographer.getInstance().postFrameCallback {
                            if (
                                state.imageProgressScheduled &&
                                state.imageLoading &&
                                nodes[state.id] === state
                            ) {
                                dispatchImageProgress(state)
                            }
                        }
                    }
                },
                onSuccess = { result ->
                    if (nodes[state.id] !== state) {
                        return@NativeImageCallbacks
                    }
                    if (
                        state.imageProgressScheduled &&
                        state.properties[PropKey.ON_IMAGE_PROGRESS] != null
                    ) {
                        dispatchImageProgress(state)
                    }
                    state.imageLoading = false
                    if (state.properties[PropKey.ON_IMAGE_LOAD] != null) {
                        dispatchBytes(
                            state.id,
                            EVENT_IMAGE_LOAD,
                            WireMap.encode(
                                mapOf(
                                    "uri" to WireValue.Text(result.source),
                                    "width" to WireValue.Decimal(
                                        result.width.toDouble(),
                                    ),
                                    "height" to WireValue.Decimal(
                                        result.height.toDouble(),
                                    ),
                                ),
                            ),
                        )
                    }
                },
                onError = { message ->
                    if (nodes[state.id] !== state) {
                        return@NativeImageCallbacks
                    }
                    state.imageLoading = false
                    state.imageProgressScheduled = false
                    if (state.properties[PropKey.ON_IMAGE_ERROR] != null) {
                        dispatchBytes(
                            state.id,
                            EVENT_IMAGE_ERROR,
                            WireMap.encode(
                                mapOf("error" to WireValue.Text(message)),
                            ),
                        )
                    }
                },
                onEnd = {
                    if (
                        nodes[state.id] === state &&
                        state.properties[PropKey.ON_IMAGE_LOAD_END] != null
                    ) {
                        dispatch(state.id, EVENT_IMAGE_LOAD_END)
                    }
                },
                onCacheHit = { disk, key ->
                    if (state.properties[PropKey.ON_MEDIA_CACHE_HIT] != null) {
                        dispatchBytes(
                            state.id,
                            EventKind.MEDIA_CACHE_HIT.value,
                            mediaCachePayload(key, 0, 0, disk),
                        )
                    }
                },
                onCacheMiss = { key ->
                    if (state.properties[PropKey.ON_MEDIA_CACHE_MISS] != null) {
                        dispatchBytes(
                            state.id,
                            EventKind.MEDIA_CACHE_MISS.value,
                            mediaCachePayload(key, 0, 0, false),
                        )
                    }
                },
                onCacheReady = { key, bytes ->
                    if (state.properties[PropKey.ON_MEDIA_CACHE_READY] != null) {
                        dispatchBytes(
                            state.id,
                            EventKind.MEDIA_CACHE_READY.value,
                            mediaCachePayload(key, bytes, bytes, true),
                        )
                    }
                },
            ),
        )
    }

    private fun configureMediaThumbnail(view: PamMediaView, state: NodeState) {
        val source = state.textOrNull(PropKey.MEDIA_THUMBNAIL_SOURCE)
        view.setThumbnail(
            source?.let {
                NativeImageRequest(
                    source = it,
                    fadeDurationMs = 0,
                    mediaCachePolicy = state.integer(
                        PropKey.MEDIA_CACHE_POLICY,
                        MEDIA_CACHE_MEMORY_AND_DISK.toLong(),
                    ).toInt(),
                    mediaCacheKey = state.textOrNull(PropKey.MEDIA_CACHE_KEY)?.let { key ->
                        "$key:thumbnail"
                    },
                    mediaCacheMaxAgeMs = state.integer(PropKey.MEDIA_CACHE_MAX_AGE_MS, 0),
                    mediaCacheMaxBytes = state.integer(PropKey.MEDIA_CACHE_MAX_BYTES, 0),
                    mediaCacheChecksum = null,
                )
            },
        )
    }

    private fun mediaCachePayload(
        key: String,
        loaded: Long,
        total: Long,
        disk: Boolean,
    ): ByteArray = WireMap.encode(
        mapOf(
            "key" to WireValue.Text(key),
            "loaded" to WireValue.Integer(loaded),
            "total" to WireValue.Integer(total),
            "disk" to WireValue.Flag(disk),
        ),
    )

    private fun dispatchImageProgress(state: NodeState) {
        state.imageProgressScheduled = false
        dispatchBytes(
            state.id,
            EVENT_IMAGE_PROGRESS,
            WireMap.encode(
                mapOf(
                    "loaded" to WireValue.Integer(state.imageProgressLoaded),
                    "total" to WireValue.Integer(state.imageProgressTotal),
                ),
            ),
        )
    }

    private fun pamImageView(view: View?): PamImageView? =
        when (view) {
            is PamImageView -> view
            is PamImageBackground -> view.image
            is PamDrawingCanvas -> view.image
            else -> null
        }

    private fun imageView(view: View): ImageView? =
        when (view) {
            is ImageView -> view
            is PamImageBackground -> view.image
            is PamDrawingCanvas -> view.image
            else -> null
        }

    private fun keyboardType(value: Int): Int =
        when (value) {
            2 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            3 -> InputType.TYPE_CLASS_NUMBER
            4 -> InputType.TYPE_CLASS_PHONE
            5 -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            6 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_CLASS_TEXT
        }

    private fun applyInputConfiguration(
        input: PamEditText,
        state: NodeState,
    ) {
        val previousSelectionStart = input.selectionStart
        val previousSelectionEnd = input.selectionEnd
        val multiline = state.flag(PropKey.MULTILINE, false)
        val secure = state.flag(PropKey.SECURE, false) && !multiline
        val inputMode = state.integer(PropKey.INPUT_MODE, 0L).toInt()
        var type = when (inputMode) {
            INPUT_MODE_DECIMAL ->
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            INPUT_MODE_NUMERIC -> InputType.TYPE_CLASS_NUMBER
            INPUT_MODE_TEL -> InputType.TYPE_CLASS_PHONE
            INPUT_MODE_EMAIL ->
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            INPUT_MODE_URL ->
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> keyboardType(
                state.integer(PropKey.KEYBOARD_TYPE, 1L).toInt(),
            )
        }
        if (multiline) {
            type = type or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        if ((type and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            // A mask or currency format rewrites the whole text on every
            // keystroke. An IME composing over it (suggestions on) commits
            // its next keys against the replaced composition and can drop or
            // repeat one of a fast burst; formatted text never takes
            // suggestions anyway.
            val formatted = state.integer(PropKey.INPUT_FORMAT, INPUT_FORMAT_NONE.toLong()).toInt() !=
                INPUT_FORMAT_NONE
            if (!state.flag(PropKey.INPUT_AUTO_CORRECT, true) || formatted) {
                type = type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            type = type or when (
                state.integer(
                    PropKey.INPUT_AUTO_CAPITALIZE,
                    INPUT_CAPITALIZE_SENTENCES.toLong(),
                ).toInt()
            ) {
                INPUT_CAPITALIZE_NONE -> 0
                INPUT_CAPITALIZE_WORDS -> InputType.TYPE_TEXT_FLAG_CAP_WORDS
                INPUT_CAPITALIZE_CHARACTERS -> InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                else -> InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            if (secure) {
                type = type or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        } else if (
            secure &&
            (type and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
        ) {
            type = type or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        input.isSingleLine = !multiline
        input.inputType = type
        input.transformationMethod =
            if (secure) PasswordTransformationMethod.getInstance() else null
        input.setHorizontallyScrolling(!multiline)
        input.minLines = if (multiline) {
            state.integer(PropKey.INPUT_MIN_LINES, 1L).toInt().coerceAtLeast(1)
        } else {
            1
        }
        input.maxLines = state.integer(PropKey.NUMBER_OF_LINES, 0L)
            .toInt()
            .takeIf { it > 0 }
            ?: if (multiline) Int.MAX_VALUE else 1

        var imeOptions = returnKeyImeOption(
            state.integer(PropKey.RETURN_KEY_TYPE, 1L).toInt(),
        )
        if (
            imeOptions == EditorInfo.IME_ACTION_UNSPECIFIED &&
            inputMode == INPUT_MODE_SEARCH
        ) {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        if (state.flag(PropKey.INPUT_DISABLE_FULLSCREEN_UI, false)) {
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        if (inputSubmitBehavior(state) == INPUT_SUBMIT_NEWLINE) {
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        }
        input.imeOptions = imeOptions
        val returnLabel = state.textOrNull(PropKey.INPUT_RETURN_KEY_LABEL)
        input.setImeActionLabel(
            returnLabel,
            imeOptions and EditorInfo.IME_MASK_ACTION,
        )

        val horizontal = when (
            state.integer(PropKey.TEXT_ALIGN, 1L).toInt()
        ) {
            2 -> Gravity.CENTER_HORIZONTAL
            3 -> Gravity.END
            else -> Gravity.START
        }
        val vertical = when (
            state.integer(
                PropKey.INPUT_TEXT_ALIGN_VERTICAL,
                INPUT_ALIGN_AUTO.toLong(),
            ).toInt()
        ) {
            INPUT_ALIGN_TOP -> Gravity.TOP
            INPUT_ALIGN_BOTTOM -> Gravity.BOTTOM
            INPUT_ALIGN_CENTER -> Gravity.CENTER_VERTICAL
            else -> if (multiline) Gravity.TOP else Gravity.CENTER_VERTICAL
        }
        input.gravity = horizontal or vertical

        input.setAutofillHints(
            *autofillHints(state.textOrNull(PropKey.AUTO_COMPLETE)),
        )
        input.importantForAutofill = when (
            state.integer(
                PropKey.INPUT_AUTOFILL_IMPORTANCE,
                INPUT_AUTOFILL_AUTO.toLong(),
            ).toInt()
        ) {
            INPUT_AUTOFILL_NO -> View.IMPORTANT_FOR_AUTOFILL_NO
            INPUT_AUTOFILL_NO_EXCLUDE ->
                View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            INPUT_AUTOFILL_YES -> View.IMPORTANT_FOR_AUTOFILL_YES
            INPUT_AUTOFILL_YES_EXCLUDE ->
                View.IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS
            else -> View.IMPORTANT_FOR_AUTOFILL_AUTO
        }
        input.isCursorVisible = !state.flag(PropKey.INPUT_CARET_HIDDEN, false)
        input.setContextMenuHidden(
            state.flag(PropKey.INPUT_CONTEXT_MENU_HIDDEN, false),
        )
        input.setSelectAllOnFocus(
            state.flag(PropKey.INPUT_SELECT_TEXT_ON_FOCUS, false),
        )
        input.showSoftInputOnFocus =
            inputMode != INPUT_MODE_NONE &&
                state.flag(PropKey.INPUT_SHOW_SOFT_INPUT_ON_FOCUS, true)
        val scrollEnabled = state.flag(PropKey.INPUT_SCROLL_ENABLED, true)
        input.isVerticalScrollBarEnabled = multiline && scrollEnabled
        input.overScrollMode = if (scrollEnabled) {
            View.OVER_SCROLL_IF_CONTENT_SCROLLS
        } else {
            View.OVER_SCROLL_NEVER
        }
        input.setCursorColor(
            state.integerOrNull(PropKey.INPUT_CURSOR_COLOR)?.toInt(),
        )
        input.setUnderlineColor(
            state.integerOrNull(PropKey.INPUT_UNDERLINE_COLOR)?.toInt(),
        )
        input.setEditableValue(state.flag(PropKey.INPUT_EDITABLE, true))

        val requestedStart = state.integerOrNull(PropKey.INPUT_SELECTION_START)?.toInt()
        val selectionStart = requestedStart
            ?: previousSelectionStart.takeIf { it >= 0 }
            ?: return
        val selectionEnd = if (requestedStart != null) {
            state.integerOrNull(PropKey.INPUT_SELECTION_END)?.toInt() ?: selectionStart
        } else {
            previousSelectionEnd
        }
        val length = input.text.length
        val safeStart = selectionStart.coerceIn(0, length)
        val safeEnd = selectionEnd.coerceIn(safeStart, length)
        if (
            input.selectionStart != safeStart ||
            input.selectionEnd != safeEnd
        ) {
            state.updating = true
            input.setSelection(safeStart, safeEnd)
            state.updating = false
        }
    }

    private fun autofillHints(value: String?): Array<String> =
        when (value?.lowercase()) {
            null, "", "off" -> emptyArray()
            "email" -> arrayOf("emailAddress")
            "tel" -> arrayOf("phone")
            "current-password", "password" -> arrayOf("password")
            "new-password", "password-new" -> arrayOf("newPassword")
            "postal-code" -> arrayOf("postalCode")
            "street-address", "postal-address" -> arrayOf("postalAddress")
            "cc-number" -> arrayOf("creditCardNumber")
            "cc-csc" -> arrayOf("creditCardSecurityCode")
            "cc-exp" -> arrayOf("creditCardExpirationDate")
            "name" -> arrayOf("name")
            "username", "username-new" -> arrayOf("username")
            else -> arrayOf(value.take(MAX_AUTOFILL_HINT_BYTES))
        }

    private fun inputSubmitBehavior(state: NodeState): Int =
        state.integerOrNull(PropKey.INPUT_SUBMIT_BEHAVIOR)
            ?.toInt()
            ?: if (state.flag(PropKey.MULTILINE, false)) {
                INPUT_SUBMIT_NEWLINE
            } else {
                INPUT_SUBMIT_BLUR
            }

    private fun returnKeyImeOption(value: Int): Int =
        when (value) {
            2 -> EditorInfo.IME_ACTION_DONE
            3 -> EditorInfo.IME_ACTION_GO
            4 -> EditorInfo.IME_ACTION_NEXT
            5 -> EditorInfo.IME_ACTION_SEARCH
            6 -> EditorInfo.IME_ACTION_SEND
            7 -> EditorInfo.IME_ACTION_NONE
            8 -> EditorInfo.IME_ACTION_PREVIOUS
            else -> EditorInfo.IME_ACTION_UNSPECIFIED
        }

    private fun accessibilityClass(value: Int): String =
        when (value) {
            2 -> Button::class.java.name
            3 -> EditText::class.java.name
            4 -> ImageView::class.java.name
            5 -> Switch::class.java.name
            6 -> "android.widget.SeekBar"
            8 -> "android.widget.CheckBox"
            9 -> "android.widget.Spinner"
            10, 24, 27, 28 -> TextView::class.java.name
            11 -> "android.widget.ImageButton"
            12 -> "android.inputmethodservice.Keyboard\$Key"
            19 -> "android.widget.RadioButton"
            22 -> EditText::class.java.name
            23 -> "android.widget.SpinButton"
            29 -> "android.widget.ToggleButton"
            31, 32 -> "androidx.recyclerview.widget.RecyclerView"
            else -> View::class.java.name
        }

    private fun configureAccessibilityDelegate(view: View, state: NodeState) {
        val nativeListDelegate = (view as? PamRecyclerList)?.compatAccessibilityDelegate
        val role = state.integer(PropKey.ACCESSIBILITY_ROLE, 1L).toInt()
        val actions = if (state.properties[PropKey.ON_ACCESSIBILITY_ACTION] != null) {
            accessibilityActions(state.textOrNull(PropKey.ACCESSIBILITY_ACTIONS))
        } else {
            emptyList()
        }
        val hint = state.textOrNull(PropKey.ACCESSIBILITY_HINT)
            ?.takeIf(String::isNotEmpty)
        if (role == 1 && actions.isEmpty() && hint == null) {
            androidx.core.view.ViewCompat.setAccessibilityDelegate(view, nativeListDelegate)
            return
        }
        if (actions.isNotEmpty()) {
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: AccessibilityNodeInfo,
            ) {
                if (nativeListDelegate != null) {
                    nativeListDelegate.onInitializeAccessibilityNodeInfo(
                        host, androidx.core.view.accessibility.AccessibilityNodeInfoCompat.wrap(info),
                    )
                } else {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                }
                if (role != 1) info.className = accessibilityClass(role)
                if (hint != null) {
                    info.hintText = hint
                }
                applyAccessibilityRoleInfo(info, role, state)
                actions.forEachIndexed { index, action ->
                    info.addAction(
                        AccessibilityNodeInfo.AccessibilityAction(
                            ACCESSIBILITY_ACTION_BASE + index,
                            action.label,
                        ),
                    )
                }
            }

            override fun performAccessibilityAction(
                host: View,
                actionId: Int,
                arguments: android.os.Bundle?,
            ): Boolean {
                val index = actionId - ACCESSIBILITY_ACTION_BASE
                if (index in actions.indices) {
                    dispatch(
                        state.id,
                        EventKind.ACCESSIBILITY_ACTION.value,
                        actions[index].name,
                    )
                    return true
                }
                return nativeListDelegate?.performAccessibilityAction(host, actionId, arguments)
                    ?: super.performAccessibilityAction(host, actionId, arguments)
            }
        }
    }

    private fun accessibilityActions(encoded: String?): List<AccessibilityActionSpec> {
        if (encoded == null || encoded.toByteArray(Charsets.UTF_8).size > 4_096) return emptyList()
        return runCatching {
            val values = JSONArray(encoded)
            require(values.length() in 1..8)
            buildList<AccessibilityActionSpec> {
                repeat(values.length()) { index ->
                    val value = values.getJSONObject(index)
                    val name = value.getString("name")
                    val label = value.getString("label")
                    require(ACCESSIBILITY_ACTION_NAME.matches(name))
                    require(label.isNotEmpty() && label.toByteArray(Charsets.UTF_8).size <= 128)
                    require(none { it.name == name })
                    add(AccessibilityActionSpec(name, label))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun applyAccessibilityRoleInfo(
        info: AccessibilityNodeInfo,
        role: Int,
        state: NodeState,
    ) {
        when (role) {
            2, 11, 12 -> info.isClickable = true
            5, 8, 19, 29 -> {
                info.isCheckable = true
                val checkedState = state.integer(
                    PropKey.ACCESSIBILITY_CHECKED_STATE,
                    if (state.flag(PropKey.CHECKED, false)) 2L else 1L,
                ).toInt()
                setAccessibilityChecked(info, checkedState)
            }
            10 -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.isHeading = true
            }
        }

        accessibilityRoleDescription(role)?.let { description ->
            info.extras.putCharSequence(
                ROLE_DESCRIPTION_KEY,
                context.getString(description),
            )
        }

        val stateDescriptions = ArrayList<CharSequence>(4)
        if (
            state.integer(PropKey.ACCESSIBILITY_CHECKED_STATE, 0L) == 3L
        ) {
            stateDescriptions += context.getString(R.string.pam_accessibility_mixed)
        }
        if (state.flag(PropKey.ACCESSIBILITY_BUSY, false)) {
            stateDescriptions += context.getString(R.string.pam_accessibility_busy)
        }
        state.properties[PropKey.ACCESSIBILITY_EXPANDED]?.let { expandedValue ->
            val expanded = expandedValue.flag()
            stateDescriptions += context.getString(
                if (expanded) {
                    R.string.pam_accessibility_expanded
                } else {
                    R.string.pam_accessibility_collapsed
                },
            )
            info.addAction(
                if (expanded) {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                } else {
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND
                },
            )
        }
        (state.properties[PropKey.ACCESSIBILITY_VALUE_TEXT] as? PropValue.Text)
            ?.value
            ?.takeIf(String::isNotEmpty)
            ?.let(stateDescriptions::add)
        if (stateDescriptions.isNotEmpty()) {
            setStateDescription(info, stateDescriptions.joinToString(", "))
        }

        val minimum = state.number(PropKey.ACCESSIBILITY_VALUE_MIN, Double.NaN)
        val maximum = state.number(PropKey.ACCESSIBILITY_VALUE_MAX, Double.NaN)
        val current = state.number(PropKey.ACCESSIBILITY_VALUE_NOW, Double.NaN)
        if (
            minimum.isFinite() &&
            maximum.isFinite() &&
            current.isFinite() &&
            minimum <= current &&
            current <= maximum
        ) {
            info.rangeInfo = accessibilityRangeInfo(
                minimum.toFloat(),
                maximum.toFloat(),
                current.toFloat(),
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun setAccessibilityChecked(
        info: AccessibilityNodeInfo,
        checkedState: Int,
    ) {
        if (Build.VERSION.SDK_INT >= 36) {
            info.setChecked(
                when (checkedState) {
                    2 -> AccessibilityNodeInfo.CHECKED_STATE_TRUE
                    3 -> AccessibilityNodeInfo.CHECKED_STATE_PARTIAL
                    else -> AccessibilityNodeInfo.CHECKED_STATE_FALSE
                },
            )
        } else {
            info.isChecked = checkedState == 2
        }
    }

    @Suppress("DEPRECATION")
    private fun accessibilityRangeInfo(
        minimum: Float,
        maximum: Float,
        current: Float,
    ): AccessibilityNodeInfo.RangeInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AccessibilityNodeInfo.RangeInfo(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                minimum,
                maximum,
                current,
            )
        } else {
            AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
                minimum,
                maximum,
                current,
            )
        }

    private fun setStateDescription(
        info: AccessibilityNodeInfo,
        description: CharSequence,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.stateDescription = description
        } else {
            info.extras.putCharSequence(STATE_DESCRIPTION_KEY, description)
        }
    }

    private fun notifyAccessibilityChanged(view: View) {
        if (view.isAttachedToWindow) {
            view.sendAccessibilityEvent(
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            )
        }
    }

    private fun accessibilityRoleDescription(role: Int): Int? =
        when (role) {
            7 -> R.string.pam_accessibility_role_alert
            9 -> R.string.pam_accessibility_role_combobox
            13 -> R.string.pam_accessibility_role_link
            14 -> R.string.pam_accessibility_role_menu
            15 -> R.string.pam_accessibility_role_menubar
            16 -> R.string.pam_accessibility_role_menuitem
            18 -> R.string.pam_accessibility_role_progressbar
            20 -> R.string.pam_accessibility_role_radiogroup
            21 -> R.string.pam_accessibility_role_scrollbar
            23 -> R.string.pam_accessibility_role_spinbutton
            24 -> R.string.pam_accessibility_role_summary
            25 -> R.string.pam_accessibility_role_tab
            26 -> R.string.pam_accessibility_role_tablist
            28 -> R.string.pam_accessibility_role_timer
            30 -> R.string.pam_accessibility_role_toolbar
            33 -> R.string.pam_accessibility_role_listitem
            else -> null
        }

    private fun activity(): Activity? = context as? Activity

    private fun isLayoutOnly(spec: NodeSpec): Boolean =
        spec.parent != 0L &&
            spec.kind in LAYOUT_ONLY_KINDS &&
            spec.properties[PropKey.COLLAPSABLE]?.flag() != false &&
            spec.properties.keys.none(HOST_PROPERTIES::contains)

    private fun isLayoutOnly(state: NodeState): Boolean =
        state.parent != 0L &&
            state.kind in LAYOUT_ONLY_KINDS &&
            state.properties[PropKey.COLLAPSABLE]?.flag() != false &&
            state.properties.keys.none(HOST_PROPERTIES::contains)

    private fun PropKey.isEventProperty(): Boolean =
        this in EVENT_PROPERTIES

    private fun PropValue.text(key: PropKey): String =
        (this as? PropValue.Text)?.value
            ?: error("Expected text property for $key, received ${this::class.simpleName}")

    private fun PropValue.textOrNull(): String? = (this as? PropValue.Text)?.value

    private fun PropValue.integer(): Long =
        when (this) {
            is PropValue.Integer -> value
            else -> error("Expected integer property, received ${this::class.simpleName}")
        }

    private fun PropValue.decimal(): Double =
        when (this) {
            is PropValue.Decimal -> value
            is PropValue.Integer -> value.toDouble()
            else -> error("Expected numeric property")
        }

    private fun PropValue.flag(): Boolean =
        (this as? PropValue.Flag)?.value ?: error("Expected boolean property")

    private fun PropValue.semanticValue(): Any =
        when (this) {
            is PropValue.Text -> value
            is PropValue.Integer -> value
            is PropValue.Decimal -> value
            is PropValue.Flag -> value
            else -> error("Semantic values must be scalar")
        }

    private fun dp(value: Float): Int =
        (value * resourcesDensity() + 0.5f).toInt()

    private fun resourcesDensity(): Float = context.resources.displayMetrics.density

    private data class StatusBarConfig(
        val color: Int,
        val appearance: Int,
        val hidden: Boolean,
        val animated: Boolean,
        val translucent: Boolean,
        val navigationBarHidden: Boolean,
    )

    private data class NodeState(
        val id: Long,
        var parent: Long,
        var index: Int,
        val kind: NodeKind,
        val properties: MutableMap<PropKey, PropValue>,
        val mountOrder: Long,
        var updating: Boolean = false,
        var applyingInitialProperties: Boolean = false,
        var initialTextUpdates: PamInitialTextUpdates? = null,
        var initialBackgroundUpdate: PamInitialBackgroundUpdate? = null,
        var pressableConfiguredDuringInitialization: Boolean = false,
        var textWatcherInstalled: Boolean = false,
        var pendingChange: Runnable? = null,
        var nativeValue: String = "",
        var nativeValueAcknowledged: Boolean = true,
        var deferredInputValue: String? = null,
        var baseText: String = "",
        var spanPressInstalled: Boolean = false,
        var pressOpacity: Float = 0.72f,
        var pressScale: Float = 1f,
        var scrollScheduled: Boolean = false,
        var pendingScrollOffset: Float = 0f,
        var endReachedSent: Boolean = false,
        var virtualListItemIds: List<Long> = emptyList(),
        /** Active keyed section the list last showed (null before the first sync). */
        var appliedListSection: String? = null,
        var keyboardBehavior: Int = KEYBOARD_RESIZE,
        var safeBottomInset: Int = 0,
        var safeAreaLeftInset: Int = 0,
        var safeAreaTopInset: Int = 0,
        var safeAreaRightInset: Int = 0,
        var safeAreaBottomInset: Int = 0,
        var keyboardInset: Int = 0,
        var keyboardAnimating: Boolean = false,
        var keyboardBaseHeight: Int = 0,
        var keyboardLayoutListener: View.OnLayoutChangeListener? = null,
        var legacyKeyboardSubscription: AutoCloseable? = null,
        /** Set while the KAV lives in a Modal/BottomSheet window: that window's IME feed. */
        var modalKeyboardSubscription: AutoCloseable? = null,
        var keyboardModalHost: PamModalHost? = null,
        var keyboardSelfLayoutListener: View.OnLayoutChangeListener? = null,
        val inputInFlight: ArrayDeque<Pair<String, Long>> = ArrayDeque(),
        var keyboardViewportReconcileGeneration: Int = 0,
        var keyboardAvoidingScrollId: Long = 0L,
        var keyboardAvoidingViewportInset: Int = 0,
        var keyboardFocusedInput: EditText? = null,
        var defaultHighlightColor: Int = Color.TRANSPARENT,
        var propertyAnimator: ObjectAnimator? = null,
        var keyframeAnimator: ValueAnimator? = null,
        var motionRunner: PamMotionRunner? = null,
        var motionProgramId: Long = Long.MIN_VALUE,
        var motionRestartKey: Long = Long.MIN_VALUE,
        var transitionRules: Map<String, PamTransitionRule> = emptyMap(),
        var transitionRunners: MutableMap<PamMotionProperty, PamMotionRunner>? = null,
        var dragSnapRequest: Long = Long.MIN_VALUE,
        var textLayoutSignature: String = "",
        var textLayoutListener: View.OnLayoutChangeListener? = null,
        var workletAnimator: ValueAnimator? = null,
        var loadingDrawable: PamButtonLoadingDrawable? = null,
        var filterLayer: Boolean = false,
        var virtual: Boolean = false,
        var imageLoading: Boolean = false,
        var imageProgressScheduled: Boolean = false,
        var imageProgressLoaded: Long = 0L,
        var imageProgressTotal: Long = 0L,
        var inputSelectionScheduled: Boolean = false,
        var directiveLayoutListener: View.OnLayoutChangeListener? = null,
        var intersectionObserver: PamIntersectionObserver? = null,
        var nativeInteractionsInstalled: Boolean = false,
        var outsidePointerObserver: ((MotionEvent) -> Unit)? = null,
        var lastDirectiveIntersection: Boolean? = null,
        var inputSelectionStart: Int = 0,
        var inputSelectionEnd: Int = 0,
        var inputDeleting: Boolean = false,
        var inputDigitsBeforeChange: Int = 0,
    ) {
        fun inputSyncMode(): Int = integer(PropKey.INPUT_SYNC_MODE, INPUT_SYNC_DEBOUNCED.toLong()).toInt()

        fun inputDebounceMs(): Long =
            integer(PropKey.INPUT_DEBOUNCE_MS, 48L).coerceIn(0L, 5_000L)

        fun targetAlpha(): Float = number(PropKey.OPACITY, 1.0).toFloat()

        fun targetScaleX(): Float = number(PropKey.SCALE_X, 1.0).toFloat()

        fun targetScaleY(): Float = number(PropKey.SCALE_Y, 1.0).toFloat()

        fun flag(key: PropKey, fallback: Boolean): Boolean =
            (properties[key] as? PropValue.Flag)?.value ?: fallback

        fun number(key: PropKey, fallback: Double): Double =
            when (val value = properties[key]) {
                is PropValue.Decimal -> value.value
                is PropValue.Integer -> value.value.toDouble()
                else -> fallback
            }

        fun integer(key: PropKey, fallback: Long): Long =
            (properties[key] as? PropValue.Integer)?.value ?: fallback

        fun integerOrNull(key: PropKey): Long? =
            (properties[key] as? PropValue.Integer)?.value

        fun textOrNull(key: PropKey): String? =
            (properties[key] as? PropValue.Text)?.value

        fun callback(key: PropKey, callback: () -> Unit): (() -> Unit)? =
            callback.takeIf { properties[key] != null }

        fun cancelMotion() {
            motionRunner?.cancel()
            motionRunner = null
            transitionRunners?.values?.forEach(PamMotionRunner::cancel)
            transitionRunners = null
        }

        fun pointerCallback(
            key: PropKey,
            callback: (PamPressPointer) -> Unit,
        ): ((PamPressPointer) -> Unit)? =
            callback.takeIf { properties[key] != null }
    }

    private data class AccessibilityActionSpec(val name: String, val label: String)

    private companion object {
        const val COMMIT_PERF_TAG = "PamRendererPerf"
        /** One prewarm slice; PHP's first batch waits at most this long behind it. */
        const val PREWARM_SLICE_NANOS = 2_000_000L
        const val DRAG_SNAP_REQUEST_STRIDE = 64L
        const val AUTO_FOCUS_DEADLINE_MS = 3_000L
        const val AUTO_FOCUS_LOG = "PamAutoFocus"
        const val AUTO_FOCUS_RETRY_MS = 50L
        const val AUTO_FOCUS_KEYBOARD_RETRY_MS = 120L
        const val LOCAL_MODAL_PREFIX = "pam:local-modal:"
        const val LOCAL_MODAL_TRIGGER_PREFIX = "pam:local-modal-trigger:"
        const val MODAL_CLOSE_MARKER = "pam:modal-close"
        const val MODAL_CLOSE_ACCESSIBILITY_LABEL = "Close modal"
        const val ACCESSIBILITY_ACTION_BASE = 0x3F00_0000
        const val INTERACTION_LOG_TAG = "PamInteraction"
        val ACCESSIBILITY_ACTION_NAME = Regex("^[a-z][a-z0-9._-]{0,63}$")
        const val EVENT_PRESS = 1
        const val EVENT_CHANGE = 2
        const val EVENT_LONG_PRESS = 5
        const val EVENT_FOCUS = 6
        const val EVENT_BLUR = 7
        const val EVENT_SUBMIT = 8
        const val EVENT_SCROLL = 9
        const val EVENT_REFRESH = 10
        const val EVENT_TOGGLE = 11
        const val EVENT_END_REACHED = 12
        const val EVENT_DRAWER_OPEN = 13
        const val EVENT_DRAWER_CLOSE = 14
        const val EVENT_NATIVE = 15
        const val EVENT_IMAGE_LOAD_START = 19
        const val EVENT_IMAGE_PROGRESS = 20
        const val EVENT_IMAGE_LOAD = 21
        const val EVENT_IMAGE_ERROR = 22
        const val EVENT_IMAGE_LOAD_END = 23
        const val EVENT_INPUT_END_EDITING = 24
        const val EVENT_INPUT_SELECTION_CHANGE = 25
        const val EVENT_INPUT_CONTENT_SIZE_CHANGE = 26
        const val EVENT_INPUT_KEY_PRESS = 27
        const val EVENT_PRESS_IN = 28
        const val EVENT_PRESS_OUT = 29
        const val EVENT_PRESS_MOVE = 30
        const val EVENT_MODAL_REQUEST_CLOSE = 31
        const val EVENT_MODAL_SHOW = 32
        const val EVENT_MODAL_DISMISS = 33
        const val EVENT_MODAL_ORIENTATION_CHANGE = 34
        const val EVENT_BOTTOM_SHEET_CHANGE = 46
        const val EVENT_BOTTOM_SHEET_DISMISS = 47
        const val MAX_EVENT_BYTES = 1024 * 1024
        const val INPUT_SYNC_NATIVE = 1
        const val INPUT_SYNC_DEBOUNCED = 2
        const val INPUT_SYNC_IMMEDIATE = 3
        const val INPUT_SYNC_BLUR = 4
        const val INPUT_SYNC_SUBMIT = 5
        const val INPUT_CAPITALIZE_NONE = 1
        const val INPUT_CAPITALIZE_SENTENCES = 2
        const val INPUT_CAPITALIZE_WORDS = 3
        const val INPUT_CAPITALIZE_CHARACTERS = 4
        const val INPUT_AUTOFILL_AUTO = 1
        const val INPUT_AUTOFILL_NO = 2
        const val INPUT_AUTOFILL_NO_EXCLUDE = 3
        const val INPUT_AUTOFILL_YES = 4
        const val INPUT_AUTOFILL_YES_EXCLUDE = 5
        const val INPUT_MODE_NONE = 2
        const val INPUT_MODE_DECIMAL = 3
        const val INPUT_MODE_NUMERIC = 4
        const val INPUT_MODE_TEL = 5
        const val INPUT_MODE_SEARCH = 6
        const val INPUT_MODE_EMAIL = 7
        const val INPUT_MODE_URL = 8
        const val INPUT_SUBMIT_BLUR = 2
        const val INPUT_SUBMIT_NEWLINE = 3
        const val INPUT_FORMAT_NONE = 1
        const val INPUT_FORMAT_PATTERN = 2
        const val INPUT_FORMAT_CURRENCY = 3
        const val INPUT_ALIGN_AUTO = 1
        const val INPUT_ALIGN_TOP = 2
        const val INPUT_ALIGN_CENTER = 3
        const val INPUT_ALIGN_BOTTOM = 4
        const val MAX_AUTOFILL_HINT_BYTES = 128
        const val MAX_INPUT_KEY_BYTES = 64
        const val KEYBOARD_RESIZE = 1
        const val KEYBOARD_PAN = 2
        const val KEYBOARD_PADDING = 3
        const val KEYBOARD_INTERACTIVE = 4
        const val SAFE_AREA_PADDING = 1
        const val SAFE_AREA_MARGIN = 2
        const val OVERFLOW_VISIBLE = 1L
        const val OVERFLOW_HIDDEN = 2L
        const val REFRESH_SIZE_DEFAULT = 1
        const val STATUS_BAR_DARK = 1
        const val STATUS_BAR_LIGHT = 2
        const val STATUS_BAR_ANIMATION_DURATION_MS = 300L
        const val TEXT_ELLIPSIZE_HEAD = 2
        const val TEXT_ELLIPSIZE_MARQUEE = 5
        const val MAX_TEXT_LAYOUT_LINES = 200
        const val TEXT_ELLIPSIZE_MIDDLE = 3
        const val TEXT_ELLIPSIZE_CLIP = 4
        const val TEXT_BREAK_HIGH_QUALITY = 1
        const val TEXT_BREAK_SIMPLE = 2
        const val TEXT_BREAK_BALANCED = 3
        const val ANDROID_BREAK_SIMPLE = 0
        const val ANDROID_BREAK_HIGH_QUALITY = 1
        const val ANDROID_BREAK_BALANCED = 2
        const val TEXT_HYPHENATION_NORMAL = 2
        const val TEXT_HYPHENATION_FULL = 3
        const val TEXT_DATA_NONE = 1
        const val TEXT_DATA_PHONE = 2
        const val TEXT_DATA_LINK = 3
        const val TEXT_DATA_EMAIL = 4
        const val TEXT_DATA_ALL = 5
        const val MAX_VIRTUAL_DEPTH = 512
        val LAYOUT_ONLY_KINDS = setOf(
            NodeKind.COLUMN,
            NodeKind.ROW,
            NodeKind.VIEW,
        )

        val HOST_PROPERTIES = setOf(
            // Sticky headers are positioned natively while scrolling.
            PropKey.STICKY_HEADER,
            // A semantic value on a layout container is also its Android tag.
            // Native compound hosts query these tagged descendants for
            // calendars, accordions, tabs, overlays, file trees and pagers;
            // flattening the node makes that authored anatomy disappear.
            PropKey.VALUE,
            PropKey.BACKGROUND_COLOR,
            PropKey.BORDER_RADIUS,
            PropKey.BORDER_WIDTH,
            PropKey.BORDER_COLOR,
            PropKey.BORDER_STYLE,
            PropKey.BORDER_TOP_LEFT_RADIUS,
            PropKey.BORDER_TOP_RIGHT_RADIUS,
            PropKey.BORDER_BOTTOM_RIGHT_RADIUS,
            PropKey.BORDER_BOTTOM_LEFT_RADIUS,
            PropKey.BORDER_LEFT_WIDTH,
            PropKey.BORDER_TOP_WIDTH,
            PropKey.BORDER_RIGHT_WIDTH,
            PropKey.BORDER_BOTTOM_WIDTH,
            PropKey.OPACITY,
            PropKey.VISIBLE,
            PropKey.TRANSLATION_X_PERCENT,
            PropKey.TRANSLATION_Y_PERCENT,
            PropKey.TRANSFORM_ORIGIN_X,
            PropKey.TRANSFORM_ORIGIN_Y,
            PropKey.BORDER_TOP_COLOR,
            PropKey.BORDER_RIGHT_COLOR,
            PropKey.BORDER_BOTTOM_COLOR,
            PropKey.BORDER_LEFT_COLOR,
            PropKey.ANIMATION_KIND,
            PropKey.ANIMATION_PROGRAM,
            PropKey.ANIMATION_RESTART_KEY,
            PropKey.TRANSITION_SPEC,
            PropKey.NATIVE_REF,
            PropKey.PRESS_TAP_EFFECT,
            PropKey.GESTURE_DRAG,
            PropKey.ON_DOUBLE_TAP,
            PropKey.ON_GESTURE_SETTLE,
            PropKey.ANIMATION_KEYFRAMES,
            PropKey.ANIMATION_DURATION_MS,
            PropKey.ANIMATION_EASING,
            PropKey.ANIMATION_ITERATIONS,
            PropKey.ANIMATION_DELAY_MS,
            PropKey.ANIMATION_FILL_MODE,
            PropKey.ANIMATION_PLAY_STATE,
            PropKey.ANIMATION_AUTO_REVERSE,
            PropKey.ON_ANIMATION_COMPLETE,
            PropKey.POINTER_EVENTS,
            PropKey.SAFE_AREA_BOTTOM,
            PropKey.BLUR_RADIUS,
            PropKey.ON_PRESS,
            PropKey.ON_LONG_PRESS,
            PropKey.ON_PRESS_IN,
            PropKey.ON_PRESS_OUT,
            PropKey.ON_PRESS_MOVE,
            PropKey.ON_MODAL_REQUEST_CLOSE,
            PropKey.ON_MODAL_SHOW,
            PropKey.ON_MODAL_DISMISS,
            PropKey.ON_MODAL_ORIENTATION_CHANGE,
            PropKey.ON_CLICK_OUTSIDE,
            PropKey.ON_INTERSECT,
            PropKey.ON_MUTATE,
            PropKey.ON_RESIZE,
            PropKey.ON_TOUCH_START,
            PropKey.ON_TOUCH_MOVE,
            PropKey.ON_TOUCH_END,
            PropKey.ACCESSIBILITY_LABEL,
            PropKey.ACCESSIBILITY_HINT,
            PropKey.ACCESSIBILITY_ROLE,
            PropKey.ACCESSIBLE,
            PropKey.ACCESSIBILITY_LIVE_REGION,
            PropKey.ACCESSIBILITY_IMPORTANCE,
            PropKey.ACCESSIBILITY_EXPANDED,
            PropKey.ACCESSIBILITY_BUSY,
            PropKey.ACCESSIBILITY_CHECKED_STATE,
            PropKey.ACCESSIBILITY_VALUE_MIN,
            PropKey.ACCESSIBILITY_VALUE_MAX,
            PropKey.ACCESSIBILITY_VALUE_NOW,
            PropKey.ACCESSIBILITY_VALUE_TEXT,
            PropKey.SAFE_AREA_TOP,
            PropKey.SAFE_AREA_RIGHT,
            PropKey.SAFE_AREA_BOTTOM_EDGE,
            PropKey.SAFE_AREA_LEFT,
            PropKey.SAFE_AREA_MODE,
            PropKey.KEYBOARD_VERTICAL_OFFSET,
            PropKey.KEYBOARD_AVOIDING_ENABLED,
            PropKey.REFRESH_COLORS,
            PropKey.REFRESH_PROGRESS_BACKGROUND_COLOR,
            PropKey.REFRESH_PROGRESS_VIEW_OFFSET,
            PropKey.REFRESH_INDICATOR_SIZE,
            PropKey.TEST_ID,
            PropKey.RIPPLE_COLOR,
            PropKey.RIPPLE_BORDERLESS,
            PropKey.RIPPLE_RADIUS,
            PropKey.RIPPLE_FOREGROUND,
            PropKey.RIPPLE_ALPHA,
            PropKey.PRESS_OPACITY,
            PropKey.PRESS_SCALE,
            PropKey.HIT_SLOP,
            PropKey.HIT_SLOP_LEFT,
            PropKey.HIT_SLOP_TOP,
            PropKey.HIT_SLOP_RIGHT,
            PropKey.HIT_SLOP_BOTTOM,
            PropKey.PRESS_RETENTION_LEFT,
            PropKey.PRESS_RETENTION_TOP,
            PropKey.PRESS_RETENTION_RIGHT,
            PropKey.PRESS_RETENTION_BOTTOM,
            PropKey.PRESS_DELAY_LONG_MS,
            PropKey.PRESS_DELAY_IN_MS,
            PropKey.PRESS_DELAY_OUT_MS,
            PropKey.PRESS_ANDROID_DISABLE_SOUND,
            PropKey.ELEVATION,
            PropKey.SHADOW_COLOR,
            PropKey.BACKGROUND_GRADIENT,
            PropKey.BORDER_GRADIENT,
            PropKey.BOX_SHADOWS,
            PropKey.FILTER_COLOR_MATRIX,
            PropKey.BACKDROP_BLUR_RADIUS,
            PropKey.BACKDROP_COLOR_MATRIX,
            PropKey.SHIMMER_ENABLED,
            PropKey.TRANSLATION_X,
            PropKey.TRANSLATION_Y,
            PropKey.SCALE_X,
            PropKey.SCALE_Y,
            PropKey.ROTATION,
            PropKey.ANIMATE_CHANGES,
            PropKey.OVERFLOW,
            PropKey.ACCESSIBILITY_ACTIONS,
            PropKey.ON_ACCESSIBILITY_ACTION,
        )

        const val TEXT_ALIGN_JUSTIFY = 4
        var blurFilterWarned = false
        val BORDER_WIDTH_KEYS = setOf(
            PropKey.BORDER_WIDTH,
            PropKey.BORDER_LEFT_WIDTH,
            PropKey.BORDER_TOP_WIDTH,
            PropKey.BORDER_RIGHT_WIDTH,
            PropKey.BORDER_BOTTOM_WIDTH,
        )
        const val MIN_TEXT_SHADOW_RADIUS = 0.01f

        const val ROLE_DESCRIPTION_KEY = "AccessibilityNodeInfo.roleDescription"
        const val STATE_DESCRIPTION_KEY =
            "androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY"

        val EVENT_PROPERTIES = setOf(
            PropKey.ON_DOUBLE_TAP,
            PropKey.ON_GESTURE_SETTLE,
            PropKey.ON_SCROLL_BEGIN_DRAG,
            PropKey.ON_SCROLL_END_DRAG,
            PropKey.ON_MOMENTUM_SCROLL_END,
            PropKey.ON_TEXT_LAYOUT,
            PropKey.ON_PRESS,
            PropKey.ON_CHANGE,
            PropKey.ON_LONG_PRESS,
            PropKey.ON_FOCUS,
            PropKey.ON_BLUR,
            PropKey.ON_SUBMIT,
            PropKey.ON_SCROLL,
            PropKey.ON_REFRESH,
            PropKey.ON_TOGGLE,
            PropKey.ON_END_REACHED,
            PropKey.ON_DRAWER_OPEN,
            PropKey.ON_DRAWER_CLOSE,
            PropKey.ON_NATIVE_EVENT,
            PropKey.ON_IMAGE_LOAD_START,
            PropKey.ON_IMAGE_PROGRESS,
            PropKey.ON_IMAGE_LOAD,
            PropKey.ON_IMAGE_ERROR,
            PropKey.ON_IMAGE_LOAD_END,
            PropKey.ON_INPUT_END_EDITING,
            PropKey.ON_INPUT_SELECTION_CHANGE,
            PropKey.ON_INPUT_CONTENT_SIZE_CHANGE,
            PropKey.ON_INPUT_KEY_PRESS,
            PropKey.ON_PRESS_IN,
            PropKey.ON_PRESS_OUT,
            PropKey.ON_PRESS_MOVE,
            PropKey.ON_MODAL_REQUEST_CLOSE,
            PropKey.ON_MODAL_SHOW,
            PropKey.ON_MODAL_DISMISS,
            PropKey.ON_MODAL_ORIENTATION_CHANGE,
            PropKey.ON_CLICK_OUTSIDE,
            PropKey.ON_INTERSECT,
            PropKey.ON_MUTATE,
            PropKey.ON_RESIZE,
            PropKey.ON_TOUCH_START,
            PropKey.ON_TOUCH_MOVE,
            PropKey.ON_TOUCH_END,
            PropKey.ON_GESTURE_BEGIN,
            PropKey.ON_GESTURE_UPDATE,
            PropKey.ON_GESTURE_END,
            PropKey.ON_GESTURE_CANCEL,
            PropKey.ON_BOTTOM_SHEET_CHANGE,
            PropKey.ON_BOTTOM_SHEET_DISMISS,
            PropKey.ON_WEB_VIEW_LOAD,
            PropKey.ON_WEB_VIEW_ERROR,
            PropKey.ON_WEB_VIEW_MESSAGE,
            PropKey.ON_MEDIA_READY,
            PropKey.ON_MEDIA_PROGRESS,
            PropKey.ON_MEDIA_END,
            PropKey.ON_MEDIA_ERROR,
            PropKey.ON_MEDIA_BUFFERING,
            PropKey.ON_MEDIA_LOAD_START,
            PropKey.ON_DRAG_START,
            PropKey.ON_DRAG_END,
            PropKey.ON_DROP,
            PropKey.ON_MENU_ACTION,
            PropKey.ON_NAVIGATION_GESTURE_POP,
            PropKey.ON_ANIMATION_COMPLETE,
            PropKey.ON_ACCESSIBILITY_ACTION,
        )
        val IMAGE_EVENT_PROPERTIES = setOf(
            PropKey.ON_IMAGE_LOAD_START,
            PropKey.ON_IMAGE_PROGRESS,
            PropKey.ON_IMAGE_LOAD,
            PropKey.ON_IMAGE_ERROR,
            PropKey.ON_IMAGE_LOAD_END,
        )
        val MODAL_DISMISS_PAYLOAD = WireMap.encode(
            mapOf(
                "action" to WireValue.Integer(1),
                "dismissed" to WireValue.Flag(true),
            ),
        )
    }
}

private class PamTextTransformMethod(
    private val mode: Int,
) : TransformationMethod {
    override fun getTransformation(source: CharSequence?, view: View?): CharSequence? {
        val value = source?.toString() ?: return source
        return when (mode) {
            2 -> value.uppercase(Locale.getDefault())
            3 -> value.lowercase(Locale.getDefault())
            4 -> value.split(WORD_BOUNDARY).joinToString(separator = "") { part ->
                if (part.firstOrNull()?.isLetter() == true) {
                    part.replaceFirstChar { character ->
                        character.titlecase(Locale.getDefault())
                    }
                } else {
                    part
                }
            }
            else -> value
        }
    }

    override fun onFocusChanged(
        view: View?,
        sourceText: CharSequence?,
        focused: Boolean,
        direction: Int,
        previouslyFocusedRect: Rect?,
    ) = Unit

    private companion object {
        val WORD_BOUNDARY = Regex("(?<=\\s)|(?=\\s)")
    }
}
