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
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
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
import android.util.TypedValue
import android.view.Choreographer
import android.view.DragEvent
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
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
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.RequiresApi
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
import java.nio.ByteOrder
import java.util.LinkedHashSet
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray

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

internal fun safeAreaChildCrossAxisReduction(
    mainAxisHorizontal: Boolean,
    horizontalInsets: Int,
    verticalInsets: Int,
): Pair<Int, Int> = if (mainAxisHorizontal) {
    0 to verticalInsets
} else {
    horizontalInsets to 0
}

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

internal fun snappedPixelSpan(
    start: Float,
    extent: Float,
    parentStart: Float,
    density: Float,
): SnappedPixelSpan {
    val safeDensity = density.coerceAtLeast(0.01f)
    val absoluteStart = (start * safeDensity).roundToInt()
    val absoluteEnd = ((start + extent.coerceAtLeast(0f)) * safeDensity).roundToInt()
    val absoluteParentStart = (parentStart * safeDensity).roundToInt()
    return SnappedPixelSpan(
        offset = absoluteStart - absoluteParentStart,
        extent = (absoluteEnd - absoluteStart).coerceAtLeast(0),
    )
}

internal fun resolvedImageScaleType(imageFit: Int): ImageView.ScaleType =
    when (imageFit) {
        2 -> ImageView.ScaleType.FIT_CENTER
        3 -> ImageView.ScaleType.FIT_XY
        4, 5 -> ImageView.ScaleType.CENTER
        else -> ImageView.ScaleType.CENTER_CROP
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
    private val main = Handler(Looper.getMainLooper())
    private val views = LongSparseArray<View>()
    private val nodes = LongSparseArray<NodeState>()
    private val frames = LongSparseArray<Frame>()
    private val children = LongSparseArray<MutableList<Long>>()
    private val imageLoader = NativeImageLoader(context)
    private val mediaCache = NativeMediaFileCache(context)
    private val nativeViews = NativeViewRegistry(context)
    private val typefaces = NativeTypefaceLoader(context)
    private var rootId = 0L
    private var nextMountOrder = 1L
    private var statusBarDefaults: StatusBarConfig? = null
    private var statusBarColorAnimator: ValueAnimator? = null

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

    fun commit(batches: List<List<Mutation>>) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Native mutations must be mounted on the Android UI thread"
        }
        val retainedScrollOffsets = buildMap {
            for (position in 0 until views.size()) {
                val view = views.valueAt(position)
                if (view is PamScrollContainer) {
                    put(views.keyAt(position), view.snapshotOffsetPixels())
                }
            }
        }
        val explicitlyUpdatedScrollOffsets = buildSet {
            batches.forEach { batch ->
                batch.forEach { mutation ->
                    if (
                        mutation is Mutation.Update
                        && (
                            mutation.key == PropKey.SCROLL_CONTENT_OFFSET_X
                                || mutation.key == PropKey.SCROLL_CONTENT_OFFSET_Y
                        )
                    ) {
                        add(mutation.id)
                    }
                }
            }
        }
        val dirtyLayouts = LinkedHashSet<Long>()
        batches.forEach { batch ->
            batch.forEach { mutation ->
                when (mutation) {
                    is Mutation.Create -> create(mutation.node)
                    is Mutation.Remove -> remove(mutation.id)
                    is Mutation.Update -> update(mutation.id, mutation.key, mutation.value)
                    is Mutation.Move -> move(mutation.id, mutation.parent, mutation.index)
                    is Mutation.Layout -> {
                        frames.put(mutation.id, mutation.frame)
                        dirtyLayouts += mutation.id
                    }
                    is Mutation.SetRoot -> rootId = mutation.id
                }
            }
        }
        syncLocalModalTriggers()
        syncHostBackground()
        syncVirtualLists()
        dirtyLayouts.forEach(::applyLayout)
        retainedScrollOffsets.forEach { (id, offset) ->
            if (id !in explicitlyUpdatedScrollOffsets) {
                (views[id] as? PamScrollContainer)?.restoreOffsetPixels(
                    offset.first,
                    offset.second,
                )
            }
        }
    }

    private fun syncHostBackground() {
        host.setBackgroundColor(resolveHostBackground(rootId, 0))
    }

    private val localModalInputTargets =
        java.util.WeakHashMap<EditText, PamModalHost>()

    private fun openLocalModalInput(input: EditText) {
        localModalInputTargets[input]?.setVisible(true)
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
        for (position in 0 until nodes.size()) {
            val state = nodes.valueAt(position)
            if (state.kind != NodeKind.MODAL) continue
            val marker = state.properties[PropKey.VALUE]?.textOrNull() ?: continue
            if (marker.startsWith(LOCAL_MODAL_PREFIX)) {
                (views[state.id] as? PamModalHost)?.let {
                    modals[marker.removePrefix(LOCAL_MODAL_PREFIX)] = it
                }
            }
        }
        for (position in 0 until nodes.size()) {
            val state = nodes.valueAt(position)
            val trigger = views[state.id] as? PamPressable ?: continue
            val marker = state.properties[PropKey.VALUE]?.textOrNull()
            val accessibilityMarker =
                state.properties[PropKey.ACCESSIBILITY_LABEL]?.textOrNull()
            val localPress = when {
                marker == MODAL_CLOSE_MARKER ||
                    accessibilityMarker == MODAL_CLOSE_ACCESSIBILITY_LABEL -> {
                    { closeLocalModalAncestor(state.id) }
                }
                marker?.startsWith(LOCAL_MODAL_TRIGGER_PREFIX) == true -> {
                    modals[marker.removePrefix(LOCAL_MODAL_TRIGGER_PREFIX)]?.let { target ->
                        { target.setVisible(true) }
                    }
                }
                else -> null
            }
            trigger.setLocalOnPress(localPress)
        }
        val orderedModals = ArrayList<Pair<Int, PamModalHost>>()
        val orderedInputs = ArrayList<Pair<Int, EditText>>()
        for (position in 0 until nodes.size()) {
            val state = nodes.valueAt(position)
            val marker = state.properties[PropKey.VALUE]?.textOrNull() ?: continue
            if (state.kind == NodeKind.MODAL && marker.startsWith(LOCAL_MODAL_PREFIX)) {
                (views[state.id] as? PamModalHost)?.let { orderedModals += position to it }
            }
        }
        for (position in 0 until nodes.size()) {
            val state = nodes.valueAt(position)
            (views[state.id] as? EditText)?.let { orderedInputs += position to it }
        }
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
    }

    override fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        for (position in 0 until views.size()) {
            (views.valueAt(position) as? PamModalHost)?.close()
        }
        for (position in 0 until nodes.size()) {
            val state = nodes.valueAt(position)
            state.propertyAnimator?.cancel()
            state.keyframeAnimator?.cancel()
            state.loadingDrawable?.stop()
            state.outsidePointerObserver?.let { observer ->
                (host as? PamRootHost)?.removePointerObserver(observer)
            }
            state.directiveLayoutListener?.let { listener ->
                views[state.id]?.removeOnLayoutChangeListener(listener)
            }
        }
        statusBarDefaults?.let(::applyStatusBarConfig)
        statusBarColorAnimator?.cancel()
        imageLoader.close()
        mediaCache.close()
        nativeViews.close()
        host.removeAllViews()
        views.clear()
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
        addChild(state.parent, state.id)
        if (!state.virtual && virtualListAncestor(state.parent) == null) {
            val view = createView(spec.kind, state)
            if (view is TextView) {
                state.defaultHighlightColor = view.highlightColor
            }
            views.put(spec.id, view)
            attachHosted(view, state)
            state.properties.forEach { (key, value) -> applyProperty(view, state, key, value) }
            (view as? TextView)?.let { applyTextAlignment(it, state) }
            installEvents(view, state)
        }
    }

    private fun createView(kind: NodeKind, state: NodeState? = null): View =
        when (kind) {
            NodeKind.SCREEN,
            NodeKind.COLUMN,
            NodeKind.ROW,
            NodeKind.VIEW,
            NodeKind.INPUT_ACCESSORY_VIEW,
            -> PamContainer(context)
            NodeKind.PRESSABLE -> PamPressable(context)
            NodeKind.TEXT -> TextView(context).apply {
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
            }
            NodeKind.BUTTON -> Button(context).apply {
                isAllCaps = false
                minHeight = 0
                minWidth = 0
            }
            NodeKind.INPUT -> PamEditText(context).apply {
                isSingleLine = true
                minHeight = 0
                background = null
                setPadding(0, 0, 0, 0)
            }
            NodeKind.IMAGE -> PamImageView(context)
            NodeKind.IMAGE_BACKGROUND -> PamImageBackground(context)
            NodeKind.SCROLL -> PamScrollContainer(context)
            NodeKind.LIST,
            NodeKind.SECTION_LIST,
            NodeKind.VIRTUAL_LIST,
            -> PamRecyclerList(context)
            NodeKind.SPACER,
            NodeKind.STATUS_BAR,
            -> Space(context)
            NodeKind.ACTIVITY_INDICATOR -> PamActivityIndicator(context)
            NodeKind.SWITCH -> PamSwitch(context)
            NodeKind.MODAL -> PamModalHost(context)
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
            NodeKind.MEDIA -> PamMediaView(context, mediaCache)
            NodeKind.DRAWING_CANVAS -> PamDrawingCanvas(context)
            NodeKind.CUSTOM_VIEW -> {
                val custom = requireNotNull(state) { "Custom native view requires node state" }
                val name = custom.properties[PropKey.HOST_NAME]?.text(PropKey.HOST_NAME)
                    ?: error("Custom native view is missing its generated name")
                nativeViews.create(name) { kind, payload ->
                    if (kind == EVENT_NATIVE) {
                        updateLocalModalSelection(custom.id)
                        closeLocalModalAncestor(custom.id)
                    }
                    val eventProperty = nativeEventProperty(kind)
                    if (eventProperty != null && custom.properties[eventProperty] != null) {
                        dispatchBytes(custom.id, kind, payload)
                    }
                }
            }
        }

    private fun remove(id: Long) {
        val state = nodes[id] ?: return
        val removedStatusBar = state.kind == NodeKind.STATUS_BAR
        val view = views[id]
        state.propertyAnimator?.cancel()
        state.keyframeAnimator?.cancel()
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
        state.outsidePointerObserver?.let { observer ->
            (host as? PamRootHost)?.removePointerObserver(observer)
        }
        if (state.kind == NodeKind.KEYBOARD_AVOIDING_VIEW) {
            if (state.keyboardAvoidingScrollId != 0L) {
                views[state.keyboardAvoidingScrollId]
                    ?.let { it as? PamScrollContainer }
                    ?.setKeyboardAvoidanceInset(0)
            }
            host.setOnApplyWindowInsetsListener(null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                host.setWindowInsetsAnimationCallback(null)
            }
            state.keyboardLayoutListener?.let(host::removeOnLayoutChangeListener)
        }
        (view?.parent as? ViewGroup)?.removeView(view)
        removeChild(state.parent, id)
        children.remove(id)
        views.remove(id)
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
            state.virtual = shouldBeVirtual
            dematerializeSubtree(cellRoot)
            if (holder != null) materializeCell(cellRoot, holder)
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
        val wasVirtualized = virtualListAncestor(state.parent) != null
        val view = views[id]
        view?.let(::clearHitSlop)
        (view?.parent as? ViewGroup)?.removeView(view)
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

    private fun addChild(parent: Long, id: Long) {
        if (parent == 0L) return
        val siblings = children[parent] ?: ArrayList<Long>().also { children.put(parent, it) }
        if (!siblings.contains(id)) siblings += id
        siblings.sortBy { child -> nodes[child]?.index ?: Int.MAX_VALUE }
    }

    private fun removeChild(parent: Long, id: Long) {
        if (parent == 0L) return
        children[parent]?.remove(id)
    }

    private fun syncVirtualLists() {
        for (position in 0 until nodes.size()) {
            val state = nodes.valueAt(position)
            if (state.kind != NodeKind.VIRTUAL_LIST) continue
            val list = views[state.id] as? PamRecyclerList ?: continue
            val itemIds = children[state.id]?.toList().orEmpty()
            val horizontal = state.flag(PropKey.LIST_HORIZONTAL, false)
            val fallbackExtent = state.number(PropKey.LIST_ROW_HEIGHT, 48.0).toFloat()
            val itemExtents = itemIds.associateWith { id ->
                frames[id]?.let { frame ->
                    if (horizontal) frame.width else frame.height
                }?.coerceAtLeast(1f) ?: fallbackExtent
            }
            list.setRichItems(
                ids = itemIds,
                extents = itemExtents,
                mount = { id, holder -> materializeCell(id, holder) },
                unmount = { id, _ -> dematerializeSubtree(id) },
            )
        }
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
        val rootFrame = frames[id] ?: return
        materializeCellNode(id, id, rootFrame, holder)
    }

    private fun materializeCellNode(
        id: Long,
        rootId: Long,
        rootFrame: Frame,
        holder: FrameLayout,
    ) {
        val state = nodes[id] ?: return
        if (!state.virtual && views[id] == null) {
            val view = createView(state.kind, state)
            if (view is TextView) state.defaultHighlightColor = view.highlightColor
            views.put(id, view)
            attachCellView(view, state, rootId, holder)
            state.properties.forEach { (key, value) ->
                applyProperty(view, state, key, value)
            }
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
        val horizontal = snappedPixelSpan(frame.x, frame.width, parentFrame.x, density)
        val vertical = snappedPixelSpan(frame.y, frame.height, parentFrame.y, density)
        view.layoutParams = FrameLayout.LayoutParams(horizontal.extent, vertical.extent).apply {
            leftMargin = if (id == rootId) 0 else horizontal.offset
            topMargin = if (id == rootId) 0 else vertical.offset
        }
    }

    private fun dematerializeSubtree(id: Long) {
        children[id]?.forEach(::dematerializeSubtree)
        val view = views[id] ?: return
        val state = nodes[id] ?: return
        state.propertyAnimator?.cancel()
        state.keyframeAnimator?.cancel()
        state.loadingDrawable?.stop()
        state.pendingChange?.let(main::removeCallbacks)
        pamImageView(view)?.let(imageLoader::cancel)
        (view as? PamWebView)?.destroy()
        view.let(nativeViews::release)
        clearHitSlop(view)
        (view.parent as? ViewGroup)?.removeView(view)
        views.remove(id)
    }

    private fun attach(view: View, parentId: Long, index: Int) {
        if (parentId == 0L) {
            host.addView(view, index.coerceIn(0, host.childCount))
            return
        }
        when (val parent = views[parentId]) {
            is PamContainer -> parent.insert(view, index)
            is PamRefreshContainer -> parent.insert(view, index)
            is PamDrawerLayout -> parent.insert(view, index)
            is PamNavigationHost -> parent.insert(view, index)
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

    private fun hostedInsertionIndex(state: NodeState, parentId: Long): Int {
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
        views.put(state.id, view)
        attachHosted(view, state)
        state.properties.forEach { (key, value) -> applyProperty(view, state, key, value) }
        installEvents(view, state)
        frames[state.id]?.let { applyLayout(state.id) }
        reattachHostedDescendants(state.id)
    }

    private fun demote(state: NodeState) {
        val view = views[state.id] ?: return
        clearHitSlop(view)
        (view.parent as? ViewGroup)?.removeView(view)
        views.remove(state.id)
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
        virtualCellRoot(id)?.let { rootId ->
            val rootFrame = frames[rootId] ?: return
            applyCellLayout(id, rootId, rootFrame)
            return
        }
        val frame = frames[id] ?: return
        val view = views[id] ?: return
        val state = nodes[id] ?: return
        val parentState = nodes[state.parent]
        val parentFrame = frames[effectiveParent(state.parent)]
        val density = resourcesDensity()
        val horizontal = snappedPixelSpan(
            frame.x,
            frame.width,
            parentFrame?.x ?: 0f,
            density,
        )
        val vertical = snappedPixelSpan(
            frame.y,
            frame.height,
            parentFrame?.y ?: 0f,
            density,
        )
        val parentSafePadding = parentState?.kind == NodeKind.SAFE_AREA_VIEW &&
            parentState.integer(
                PropKey.SAFE_AREA_MODE,
                SAFE_AREA_PADDING.toLong(),
            ).toInt() == SAFE_AREA_PADDING
        val parentSafeHorizontal = if (parentSafePadding) {
            (if (parentState?.flag(PropKey.SAFE_AREA_LEFT, true) == true) {
                parentState.safeAreaLeftInset
            } else {
                0
            }) + (if (parentState?.flag(PropKey.SAFE_AREA_RIGHT, true) == true) {
                parentState.safeAreaRightInset
            } else {
                0
            })
        } else {
            0
        }
        val parentSafeVertical = if (parentSafePadding) {
            (if (parentState?.flag(PropKey.SAFE_AREA_TOP, true) == true) {
                parentState.safeAreaTopInset
            } else {
                0
            }) + (if (parentState?.flag(PropKey.SAFE_AREA_BOTTOM_EDGE, true) == true) {
                parentState.safeAreaBottomInset
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
            horizontal.extent - safeLeft - safeRight - parentSafeWidthReduction
            ).coerceAtLeast(0)
        var height = (
            vertical.extent - safeTop - safeBottom - parentSafeHeightReduction
            ).coerceAtLeast(0)
        var leftPx = horizontal.offset + safeLeft
        var topPx = vertical.offset + safeTop
        compensateFlexParentViewportReduction(
            state = state,
            parentState = parentState,
            parentFrame = parentFrame,
            parentView = views[state.parent],
            applyHorizontal = { offset, reduction ->
                leftPx -= offset
                width = (width - reduction).coerceAtLeast(0)
            },
            applyVertical = { offset, reduction ->
                topPx -= offset
                height = (height - reduction).coerceAtLeast(0)
            },
        )
        val current = view.layoutParams as? ViewGroup.MarginLayoutParams

        val layoutChanged =
            current == null ||
            current.width != width ||
            current.height != height ||
            current.leftMargin != leftPx ||
            current.topMargin != topPx
        if (layoutChanged) {
            view.layoutParams = FrameLayout.LayoutParams(width, height).apply {
                leftMargin = leftPx
                topMargin = topPx
            }
            children[state.id]?.forEach(::applyLayout)
        }
        // Some plugin hosts (for example Calendar) draw against tagged child
        // bounds. A descendant frame can change without mutating the host's
        // own properties, so invalidate the hosted ancestor chain after
        // applying layout instead of leaving a stale custom canvas.
        var ancestor = view.parent
        while (ancestor is View) {
            ancestor.invalidate()
            ancestor = ancestor.parent
        }
        applyHitSlop(view, state)
        (view as? TextView)?.let { applyTextAlignment(it, state) }
        state.properties[PropKey.TRANSLATION_X_PERCENT]?.decimal()?.let { percent ->
            view.translationX = width * (percent / 100.0).toFloat()
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
            else -> return
        }
        val engineExtent = when (axis) {
            Axis.HORIZONTAL -> dp(parentFrame.width)
            Axis.VERTICAL -> dp(parentFrame.height)
        }
        val measuredExtent = when (axis) {
            Axis.HORIZONTAL -> parentView.width.takeIf { it > 0 }
                ?: parentView.layoutParams?.width
                ?: 0
            Axis.VERTICAL -> parentView.height.takeIf { it > 0 }
                ?: parentView.layoutParams?.height
                ?: 0
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
        val windowVisibleFrame = Rect().also(
            parentView::getWindowVisibleDisplayFrame,
        )
        val windowVisibleExtent = when (axis) {
            Axis.HORIZONTAL -> windowVisibleFrame.width()
            Axis.VERTICAL -> windowVisibleFrame.height()
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
            windowVisibleExtent = windowVisibleExtent,
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
                text.text = semanticText
                state.baseText = semanticText
                applyTextDataDetector(text, state)
            }
            PropKey.VALUE -> when (view) {
                is EditText -> applyInputValue(view, state, value.text(key))
                is PamDrawingCanvas -> view.setDrawing(value.text(key))
                else -> view.tag = value.semanticValue()
            }
            PropKey.PLACEHOLDER -> (view as? EditText)?.hint = value.text(key)
            PropKey.SOURCE -> loadImage(view, state)
            PropKey.BACKGROUND_COLOR,
            PropKey.BORDER_RADIUS,
            PropKey.BORDER_WIDTH,
            PropKey.BORDER_COLOR,
            PropKey.BORDER_TOP_LEFT_RADIUS,
            PropKey.BORDER_TOP_RIGHT_RADIUS,
            PropKey.BORDER_BOTTOM_RIGHT_RADIUS,
            PropKey.BORDER_BOTTOM_LEFT_RADIUS,
            PropKey.BORDER_LEFT_WIDTH,
            PropKey.BORDER_TOP_WIDTH,
            PropKey.BORDER_RIGHT_WIDTH,
            PropKey.BORDER_BOTTOM_WIDTH,
            PropKey.RIPPLE_COLOR,
            PropKey.RIPPLE_BORDERLESS,
            PropKey.RIPPLE_RADIUS,
            PropKey.RIPPLE_FOREGROUND,
            PropKey.RIPPLE_ALPHA,
            -> updateBackground(view, state)
            PropKey.SHADOW_OFFSET_X,
            PropKey.SHADOW_OFFSET_Y,
            PropKey.SHADOW_BLUR_RADIUS,
            PropKey.SHADOW_SPREAD_RADIUS,
            PropKey.SHADOW_COLOR,
            -> applyBoxShadow(view, state)
            PropKey.TEXT_COLOR -> when (view) {
                is TextView -> {
                    val color = value.integer().toInt()
                    view.setTextColor(color)
                    if (view is Button && state.flag(PropKey.LOADING, false)) {
                        state.loadingDrawable?.setColor(color)
                    }
                }
                is PamRecyclerList -> view.setTextColor(value.integer().toInt())
            }
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
            PropKey.ACCESSIBILITY_HINT -> view.tooltipText = value.text(key)
            PropKey.TEST_ID -> view.transitionName = value.text(key)
            PropKey.ITEMS -> applyStringList(view, state, value)
            PropKey.SECTION_ITEMS -> applySectionList(view, state, value)
            PropKey.NAVIGATION_OPERATION ->
                (view as? PamNavigationHost)?.operation = value.integer().toInt()
            PropKey.NAVIGATION_TRANSITION ->
                (view as? PamNavigationHost)?.transition = value.integer().toInt()
            PropKey.NAVIGATION_DURATION_MS ->
                (view as? PamNavigationHost)?.durationMs = value.integer()
            PropKey.NAVIGATION_REVISION ->
                (view as? PamNavigationHost)?.navigate(value.integer())
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
            PropKey.TEXT_TRANSFORM -> if (view is TextView && view !is EditText) {
                view.transformationMethod = when (value.integer().toInt()) {
                    2, 3, 4 -> PamTextTransformMethod(value.integer().toInt())
                    else -> null
                }
            }
            PropKey.NUMBER_OF_LINES -> (view as? TextView)?.maxLines =
                value.integer().toInt().takeIf { it > 0 } ?: Int.MAX_VALUE
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
            -> (view as? PamEditText)?.let {
                applyInputConfiguration(it, state)
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
            PropKey.VISIBLE -> when (view) {
                is PamModalHost -> view.setVisible(value.flag())
                is PamActivityIndicator -> view.setRequestedVisible(value.flag())
                else -> view.visibility = if (value.flag()) View.VISIBLE else View.GONE
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
            PropKey.ANIMATION_KEYFRAMES,
            PropKey.ANIMATION_ITERATIONS,
            PropKey.ANIMATION_DELAY_MS,
            PropKey.ANIMATION_FILL_MODE,
            PropKey.ANIMATION_PLAY_STATE,
            PropKey.ANIMATION_AUTO_REVERSE,
            -> configureKeyframeAnimation(view, state)
            PropKey.STATUS_BAR_COLOR,
            PropKey.STATUS_BAR_STYLE,
            PropKey.STATUS_BAR_HIDDEN,
            PropKey.STATUS_BAR_ANIMATED,
            PropKey.STATUS_BAR_TRANSLUCENT,
            -> applyMergedStatusBar()
            PropKey.KEYBOARD_BEHAVIOR -> {
                state.keyboardBehavior = value.integer().toInt()
                applyKeyboardAvoidance(view, state)
            }
            PropKey.KEYBOARD_VERTICAL_OFFSET,
            PropKey.KEYBOARD_AVOIDING_ENABLED,
            -> applyKeyboardAvoidance(view, state)
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
                (view as? PamScrollContainer)?.requestScroll()
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
            PropKey.SCROLL_PAGING_ENABLED ->
                (view as? PamScrollContainer)?.setPagingEnabled(value.flag())
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
            PropKey.ACCESSIBILITY_ROLE -> {
                val role = value.integer().toInt()
                val className = accessibilityClass(role)
                (view as? PamActivityIndicator)?.setHostAccessibility(true)
                view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = className
                        applyAccessibilityRoleInfo(info, role, state)
                    }
                }
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
            PropKey.TEXT_ELLIPSIZE_MODE -> (view as? TextView)?.ellipsize =
                textEllipsize(value.integer().toInt())
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
                view.post { view.requestFocus() }
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
            PropKey.BLUR_RADIUS -> applyBlur(view, state, value.decimal().toFloat())
            PropKey.TRANSLATION_X_PERCENT -> {
                view.translationX = view.width * (value.decimal() / 100.0).toFloat()
            }
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
            -> configurePressable(view, state)
            PropKey.WIDTH,
            PropKey.HEIGHT,
            PropKey.FLEX_GROW,
            PropKey.FLEX_SHRINK,
            PropKey.GAP,
            PropKey.MARGIN,
            PropKey.MARGIN_HORIZONTAL,
            PropKey.MARGIN_VERTICAL,
            PropKey.MARGIN_LEFT,
            PropKey.MARGIN_TOP,
            PropKey.MARGIN_RIGHT,
            PropKey.MARGIN_BOTTOM,
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
            PropKey.TEXT -> (view as? TextView)?.text = ""
            PropKey.VALUE -> when (view) {
                is EditText -> view.setText("")
                is PamDrawingCanvas -> view.setDrawing("")
                else -> view.tag = null
            }
            PropKey.PLACEHOLDER -> (view as? EditText)?.hint = null
            PropKey.SOURCE -> pamImageView(view)?.let(imageLoader::cancel)
            PropKey.BACKGROUND_COLOR,
            PropKey.BORDER_RADIUS,
            PropKey.BORDER_WIDTH,
            PropKey.BORDER_COLOR,
            PropKey.BORDER_TOP_LEFT_RADIUS,
            PropKey.BORDER_TOP_RIGHT_RADIUS,
            PropKey.BORDER_BOTTOM_RIGHT_RADIUS,
            PropKey.BORDER_BOTTOM_LEFT_RADIUS,
            PropKey.BORDER_LEFT_WIDTH,
            PropKey.BORDER_TOP_WIDTH,
            PropKey.BORDER_RIGHT_WIDTH,
            PropKey.BORDER_BOTTOM_WIDTH,
            PropKey.RIPPLE_COLOR,
            PropKey.RIPPLE_BORDERLESS,
            PropKey.RIPPLE_RADIUS,
            PropKey.RIPPLE_FOREGROUND,
            PropKey.RIPPLE_ALPHA,
            -> updateBackground(view, state)
            PropKey.TEXT_COLOR -> when (view) {
                is TextView -> view.setTextColor(Color.BLACK)
                is PamRecyclerList -> view.setTextColor(Color.BLACK)
            }
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
            PropKey.TEXT_ELLIPSIZE_MODE -> (view as? TextView)?.ellipsize =
                TextUtils.TruncateAt.END
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
            -> (view as? PamEditText)?.let {
                applyInputConfiguration(it, state)
            }
            PropKey.MAX_LENGTH -> (view as? EditText)?.filters = emptyArray()
            PropKey.AUTO_FOCUS -> Unit
            PropKey.ENABLED -> {
                view.isEnabled = true
                configurePressable(view, state)
            }
            PropKey.ACCESSIBILITY_LABEL -> view.contentDescription = null
            PropKey.ACCESSIBILITY_HINT -> view.tooltipText = null
            PropKey.ACCESSIBILITY_ROLE -> {
                view.accessibilityDelegate = null
                (view as? PamActivityIndicator)?.setHostAccessibility(false)
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
            PropKey.KEYBOARD_BEHAVIOR -> {
                state.keyboardBehavior = KEYBOARD_RESIZE
                applyKeyboardAvoidance(view, state)
            }
            PropKey.KEYBOARD_VERTICAL_OFFSET,
            PropKey.KEYBOARD_AVOIDING_ENABLED,
            -> applyKeyboardAvoidance(view, state)
            PropKey.SAFE_AREA_TOP,
            PropKey.SAFE_AREA_RIGHT,
            PropKey.SAFE_AREA_BOTTOM_EDGE,
            PropKey.SAFE_AREA_LEFT,
            PropKey.SAFE_AREA_MODE,
            -> applySafeAreaLayout(view, state)
            PropKey.TEST_ID -> view.transitionName = null
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
            PropKey.SCROLL_PAGING_ENABLED ->
                (view as? PamScrollContainer)?.setPagingEnabled(false)
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
            PropKey.VISIBLE -> when (view) {
                is PamModalHost -> view.setVisible(true)
                is PamActivityIndicator -> view.setRequestedVisible(true)
                else -> view.visibility = View.VISIBLE
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
                is PamMediaView -> configureMediaCache(view, state)
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
            }
            PropKey.POINTER_EVENTS -> applyPointerEvents(view, state, POINTER_EVENTS_AUTO)
            PropKey.SAFE_AREA_BOTTOM -> applySafeAreaBottom(view, state, false)
            PropKey.BLUR_RADIUS -> applyBlur(view, state, 0f)
            PropKey.TRANSLATION_X_PERCENT -> {
                view.translationX = dp(state.number(PropKey.TRANSLATION_X, 0.0).toFloat()).toFloat()
            }
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
        if (left <= 0 && top <= 0 && right <= 0 && bottom <= 0) {
            clearHitSlop(view)
            return
        }
        parent.post {
            if (view.parent !== parent || !view.isAttachedToWindow) return@post
            val bounds = Rect()
            view.getHitRect(bounds)
            bounds.left -= left
            bounds.top -= top
            bounds.right += right
            bounds.bottom += bottom
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
                onPress = state.callback(PropKey.ON_PRESS) {
                    flushFocusedNativeInputs()
                    dispatch(state.id, EVENT_PRESS)
                },
                onLongPress = state.callback(PropKey.ON_LONG_PRESS) {
                    dispatch(state.id, EVENT_LONG_PRESS)
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
            if (view is TextView && view !is EditText) {
                view.isClickable = state.properties[PropKey.ON_PRESS] != null
                view.isLongClickable =
                    state.properties[PropKey.ON_LONG_PRESS] != null
            }
        }
        installPressFeedback(view, state)
        if (
            state.properties[PropKey.RIPPLE_COLOR] != null &&
            state.properties[PropKey.ON_PRESS] == null &&
            state.kind != NodeKind.CUSTOM_VIEW
        ) {
            view.isClickable = true
        }
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
            view.onReady = state.callback(PropKey.ON_MEDIA_READY) {
                dispatch(state.id, EventKind.MEDIA_READY.value)
            }
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

        view.setOnLongClickListener(
            when {
                draggable -> View.OnLongClickListener {
                    val started = it.startDragAndDrop(
                        ClipData.newPlainText("Pam Native", dragData),
                        View.DragShadowBuilder(it),
                        dragData,
                        0,
                    )
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

        view.setOnDragListener(
            if (dropEnabled || draggable) {
                View.OnDragListener { _, event ->
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
            onPop = state.callback(PropKey.ON_NAVIGATION_GESTURE_POP) {
                dispatch(state.id, EventKind.NAVIGATION_GESTURE_POP.value)
            },
        )
    }

    private fun configureKeyframeAnimation(view: View, state: NodeState) {
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

        if (!ValueAnimator.areAnimatorsEnabled()) {
            applyKeyframe(view, frames, 1f)
            if (state.properties[PropKey.ON_ANIMATION_COMPLETE] != null) {
                dispatch(state.id, EventKind.ANIMATION_COMPLETE.value)
            }
            return
        }
        state.keyframeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = state.integer(PropKey.ANIMATION_DURATION_MS, 300L).coerceIn(1L, 60_000L)
            startDelay = state.integer(PropKey.ANIMATION_DELAY_MS, 0L).coerceIn(0L, 60_000L)
            val iterations = state.integer(PropKey.ANIMATION_ITERATIONS, 1L).coerceIn(0L, 10_000L)
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

    private fun applyKeyframe(view: View, frames: List<NativeKeyframe>, progress: Float) {
        val rightIndex = frames.indexOfFirst { it.offset >= progress }
            .let { if (it < 0) frames.lastIndex else it }
        val leftIndex = (rightIndex - 1).coerceAtLeast(0)
        val left = frames[leftIndex]
        val right = frames[rightIndex]
        val local = if (right.offset == left.offset) 0f else {
            ((progress - left.offset) / (right.offset - left.offset)).coerceIn(0f, 1f)
        }
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
    )

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
                    view.animate().alpha(state.pressOpacity).setDuration(70).start()
                    dispatchDirectiveTouch(state, EventKind.TOUCH_START.value, event)
                }
                MotionEvent.ACTION_MOVE ->
                    dispatchDirectiveTouch(state, EventKind.TOUCH_MOVE.value, event)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> {
                    view.animate().alpha(state.targetAlpha()).setDuration(110).start()
                    dispatchDirectiveTouch(state, EventKind.TOUCH_END.value, event)
                }
            }
            false
        }
    }

    private fun installDirectiveEvents(view: View, state: NodeState) {
        state.directiveLayoutListener?.let(view::removeOnLayoutChangeListener)
        state.directiveLayoutListener = null
        state.outsidePointerObserver?.let { observer ->
            (host as? PamRootHost)?.removePointerObserver(observer)
        }
        state.outsidePointerObserver = null
        state.lastDirectiveIntersection = null
        if (
            state.properties[PropKey.ON_RESIZE] == null &&
            state.properties[PropKey.ON_MUTATE] == null &&
            state.properties[PropKey.ON_INTERSECT] == null
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
            state.properties[PropKey.ON_MUTATE] == null &&
            state.properties[PropKey.ON_INTERSECT] == null
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
            if (state.properties[PropKey.ON_INTERSECT] != null) {
                val visibleRect = Rect()
                val intersecting =
                    target.isShown &&
                        target.alpha > 0f &&
                        target.getGlobalVisibleRect(visibleRect) &&
                        !visibleRect.isEmpty
                if (state.lastDirectiveIntersection != intersecting) {
                    state.lastDirectiveIntersection = intersecting
                    dispatch(state.id, EventKind.INTERSECT.value, if (intersecting) "1" else "0")
                }
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
        pressable.configure(
            pressOpacity = state.pressOpacity,
            targetOpacity = state.targetAlpha(),
            delayLongPressMs = state.integer(PropKey.PRESS_DELAY_LONG_MS, 500L),
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
                ),
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
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(editable: Editable?) {
                    if (state.updating) return
                    state.nativeValue = editable?.toString().orEmpty()
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
            if (focused) {
                if (state.properties[PropKey.ON_FOCUS] != null) dispatch(state.id, EVENT_FOCUS)
            } else {
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
        if (state.properties[PropKey.ON_SCROLL] == null) {
            scroll.setOnViewportChanged(null)
            return
        }
        scroll.setOnViewportChanged { scrollX, scrollY ->
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

    private fun installListEvents(list: PamRecyclerList, state: NodeState) {
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
        if (
            input.hasFocus() &&
            state.inputSyncMode() != INPUT_SYNC_IMMEDIATE &&
            next != state.nativeValue
        ) {
            return
        }
        if (input.text.toString() == next) return
        state.updating = true
        input.setText(next)
        input.setSelection(input.text.length)
        state.nativeValue = next
        state.updating = false
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
                ?: PamButtonLoadingDrawable(dp(20f), color).also {
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
        if (view is ViewGroup) {
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
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom) + state.safeBottomInset)
    }

    private fun updateBackground(view: View, state: NodeState) {
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
        val color = state.integer(PropKey.BACKGROUND_COLOR, defaultColor)
            .toInt()
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
        val hasDirectionalBorder = borderWidth > 0 && (
            leftBorderWidth != rightBorderWidth ||
                leftBorderWidth != topBorderWidth ||
                leftBorderWidth != bottomBorderWidth
            )
        val borderColor = state.integer(PropKey.BORDER_COLOR, Color.TRANSPARENT.toLong()).toInt()
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
        (view as? PamContainer)?.setOverflowClip(
            state.integer(PropKey.OVERFLOW, OVERFLOW_VISIBLE) == OVERFLOW_HIDDEN,
            radii,
        )
        val shape = GradientDrawable().apply {
            setColor(color)
            cornerRadii = radii
            if (!imageHost && borderWidth > 0 && !hasDirectionalBorder) {
                setStroke(borderWidth, borderColor)
            }
        }
        val background = if (!imageHost && hasDirectionalBorder) {
            val borders = object : android.graphics.drawable.Drawable() {
                private val paint = android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG,
                ).apply {
                    this.color = borderColor
                    style = android.graphics.Paint.Style.FILL
                }

                override fun draw(canvas: android.graphics.Canvas) {
                    val area = bounds
                    if (leftBorderWidth > 0) {
                        canvas.drawRect(
                            area.left.toFloat(),
                            area.top.toFloat(),
                            (area.left + leftBorderWidth).toFloat(),
                            area.bottom.toFloat(),
                            paint,
                        )
                    }
                    if (topBorderWidth > 0) {
                        canvas.drawRect(
                            area.left.toFloat(),
                            area.top.toFloat(),
                            area.right.toFloat(),
                            (area.top + topBorderWidth).toFloat(),
                            paint,
                        )
                    }
                    if (rightBorderWidth > 0) {
                        canvas.drawRect(
                            (area.right - rightBorderWidth).toFloat(),
                            area.top.toFloat(),
                            area.right.toFloat(),
                            area.bottom.toFloat(),
                            paint,
                        )
                    }
                    if (bottomBorderWidth > 0) {
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
                    paint.alpha = alpha
                }

                override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
                    paint.colorFilter = colorFilter
                }

                @Suppress("DEPRECATION")
                override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
            }
            android.graphics.drawable.LayerDrawable(arrayOf(shape, borders))
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
                    setStroke(borderWidth, borderColor)
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
            view.background = shape
            view.foreground = overlay
        } else {
            view.foreground = null
            view.background = (RippleDrawable(
                ColorStateList.valueOf(effectiveRipple),
                shape,
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
        val color = state.integer(PropKey.SHADOW_COLOR, Color.TRANSPARENT.toLong()).toInt()
        if (Color.alpha(color) == 0) {
            PamBoxShadows.set(view, null)
            return
        }
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
        PamBoxShadows.set(
            view,
            PamBoxShadow(
                offsetX = dp(state.number(PropKey.SHADOW_OFFSET_X, 0.0).toFloat()).toFloat(),
                offsetY = dp(state.number(PropKey.SHADOW_OFFSET_Y, 0.0).toFloat()).toFloat(),
                blurRadius = dp(
                    state.number(PropKey.SHADOW_BLUR_RADIUS, 0.0).toFloat(),
                ).toFloat(),
                spreadRadius = dp(
                    state.number(PropKey.SHADOW_SPREAD_RADIUS, 0.0).toFloat(),
                ).toFloat(),
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
        view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        val baseSize = state.number(PropKey.FONT_SIZE, 14.0).toFloat().coerceAtLeast(1f)
        val metrics = view.resources.displayMetrics
        val allowScaling = state.flag(PropKey.TEXT_ALLOW_FONT_SCALING, true)
        val deviceScale = view.resources.configuration.fontScale
        val maximumMultiplier = state
            .number(PropKey.TEXT_MAX_FONT_SIZE_MULTIPLIER, 0.0)
            .toFloat()
        val effectiveScale = if (!allowScaling) {
            1f
        } else if (maximumMultiplier > 0f) {
            min(deviceScale, maximumMultiplier.coerceAtLeast(1f))
        } else {
            deviceScale
        }
        val maximumPx = max(1f, baseSize * metrics.density * effectiveScale)
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

    private fun applyLetterSpacing(view: TextView, state: NodeState) {
        view.letterSpacing = resolvedAndroidLetterSpacing(
            state.number(PropKey.LETTER_SPACING, 0.0).toFloat(),
            state.number(PropKey.FONT_SIZE, 14.0).toFloat(),
        )
    }

    private fun applyTextAlignment(view: TextView, state: NodeState) {
        if (view is PamEditText) return
        val authored = state.properties[PropKey.TEXT_ALIGN]?.integer()?.toInt()
        val horizontal = when (authored) {
            2 -> Gravity.CENTER_HORIZONTAL
            3 -> Gravity.END
            1 -> Gravity.START
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
        view.gravity = horizontal or Gravity.CENTER_VERTICAL
    }

    private fun applyLineHeight(view: TextView, state: NodeState) {
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

    private fun applyTypeface(view: TextView, state: NodeState) {
        val bold = state.integer(PropKey.FONT_WEIGHT, 400L) >= 600L
        val italic = state.integer(PropKey.FONT_STYLE, 1L) == 2L
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val family = (state.properties[PropKey.FONT_FAMILY] as? PropValue.Text)?.value
        view.typeface = typefaces.resolve(family, style)
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

    private fun applyBlur(view: View, state: NodeState, radius: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(
                if (radius > 0f) {
                    val pixels = dp(radius).toFloat().coerceAtLeast(1f)
                    RenderEffect.createBlurEffect(pixels, pixels, Shader.TileMode.CLAMP)
                } else {
                    null
                },
            )
        } else {
            view.elevation = if (radius > 0f) {
                max(view.elevation, dp(radius / 2f).toFloat())
            } else {
                dp(state.number(PropKey.ELEVATION, 0.0).toFloat()).toFloat()
            }
        }
    }

    private fun applyAnimationKind(view: View, state: NodeState, kind: Int) {
        state.propertyAnimator?.cancel()
        state.propertyAnimator = null
        view.animate().cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || kind == 1) {
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
        if (!state.flag(PropKey.ANIMATE_CHANGES, false) || !view.isLaidOut) {
            setAnimatedProperty(view, key, target)
            return
        }
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
            val raw = windowSafeAreaInsets(insets)
            val resolved = safeAreaInsetsForView(raw, target)
            state.safeAreaLeftInset = resolved.left
            state.safeAreaTopInset = resolved.top
            state.safeAreaRightInset = resolved.right
            state.safeAreaBottomInset = resolved.bottom
            applySafeAreaLayout(target, state)
            insets
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

    private fun windowSafeAreaInsets(insets: WindowInsets): SafeAreaInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return SafeAreaInsets(safe.left, safe.top, safe.right, safe.bottom)
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
        return SafeAreaInsets(
            left = max(systemLeft, cutout.left),
            top = max(systemTop, cutout.top),
            right = max(systemRight, cutout.right),
            bottom = max(systemBottom, cutout.bottom),
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
        children[state.id]?.forEach(::applyLayout)
    }

    private fun installKeyboardInsets(view: View, state: NodeState) {
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
                val platformInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    host.rootWindowInsets
                        ?.getInsets(WindowInsets.Type.ime())
                        ?.bottom
                        ?: 0
                } else {
                    0
                }
                state.keyboardInset = resolvedKeyboardInset(
                    platformInset = platformInset,
                    baselineHeight = state.keyboardBaseHeight,
                    currentHeight = height,
                    minimumKeyboardHeight = dp(80f),
                )
                applyKeyboardAvoidance(view, state)
            }
        }
        state.keyboardLayoutListener = layoutListener
        host.addOnLayoutChangeListener(layoutListener)
        host.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                state.keyboardInset = insets.getInsets(WindowInsets.Type.ime()).bottom
                applyKeyboardAvoidance(view, state)
            }
            insets
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            host.setWindowInsetsAnimationCallback(
                object : WindowInsetsAnimation.Callback(
                    WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
                ) {
                    override fun onProgress(
                        insets: WindowInsets,
                        runningAnimations: MutableList<WindowInsetsAnimation>,
                    ): WindowInsets {
                        state.keyboardInset = insets.getInsets(WindowInsets.Type.ime()).bottom
                        applyKeyboardAvoidance(view, state)
                        return insets
                    }
                },
            )
        }
        host.requestApplyInsets()
    }

    private fun applyKeyboardAvoidance(view: View, state: NodeState) {
        val enabled = state.flag(PropKey.KEYBOARD_AVOIDING_ENABLED, true)
        val offset = dp(
            state.number(PropKey.KEYBOARD_VERTICAL_OFFSET, 0.0).toFloat(),
        )
        val keyboard = if (enabled) {
            max(0, state.keyboardInset + offset)
        } else {
            0
        }
        when (state.keyboardBehavior) {
            KEYBOARD_PAN -> {
                view.translationY = -keyboard.toFloat()
                view.setPadding(0, 0, 0, 0)
            }
            KEYBOARD_PADDING -> {
                view.translationY = 0f
                view.setPadding(0, 0, 0, 0)
            }
            else -> {
                view.translationY = 0f
                view.setPadding(0, 0, 0, 0)
            }
        }
        val scroll = when (state.keyboardBehavior) {
            KEYBOARD_PAN -> precedingScrollContainer(state)
            KEYBOARD_PADDING -> containedScrollContainer(state)
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
        scroll?.second?.setKeyboardAvoidanceInset(
            keyboard,
        )
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
        val mounted = ArrayList<NodeState>()
        for (position in 0 until nodes.size()) {
            val state = nodes.valueAt(position)
            if (
                state.kind == NodeKind.STATUS_BAR &&
                views[state.id]?.let(::isInActiveNavigationRoute) == true
            ) {
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
        }
        applyStatusBarConfig(merged)
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
            ?: return StatusBarConfig(Color.BLACK, STATUS_BAR_LIGHT, false, false, false)
        val decor = window.decorView
        val lightIcons = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appearance = decor.windowInsetsController?.systemBarsAppearance ?: 0
            appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS == 0
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
        )
    }

    private fun applyStatusBarConfig(config: StatusBarConfig) {
        applyStatusBarTranslucent(config.translucent)
        applyStatusBarColor(config.color, config.animated)
        applyStatusBarAppearance(config.appearance)
        applyStatusBarHidden(config.hidden)
    }

    @Suppress("DEPRECATION")
    private fun applyStatusBarAppearance(value: Int) {
        val decor = activity()?.window?.decorView ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = decor.windowInsetsController ?: return
            val useDarkIcons =
                value == STATUS_BAR_DARK && Build.VERSION.SDK_INT < 35
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
            5 -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            6 -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_CLASS_TEXT
        }

    private fun applyInputConfiguration(
        input: PamEditText,
        state: NodeState,
    ) {
        val multiline = state.flag(PropKey.MULTILINE, false)
        val secure = state.flag(PropKey.SECURE, false) && !multiline
        val inputMode = state.integer(PropKey.INPUT_MODE, 0L).toInt()
        var type = when (inputMode) {
            INPUT_MODE_DECIMAL ->
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
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
            if (!state.flag(PropKey.INPUT_AUTO_CORRECT, true)) {
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

        val selectionStart = state.integerOrNull(PropKey.INPUT_SELECTION_START)
            ?.toInt()
            ?: return
        val selectionEnd = state.integerOrNull(PropKey.INPUT_SELECTION_END)
            ?.toInt()
            ?: selectionStart
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
    )

    private data class NodeState(
        val id: Long,
        var parent: Long,
        var index: Int,
        val kind: NodeKind,
        val properties: MutableMap<PropKey, PropValue>,
        val mountOrder: Long,
        var updating: Boolean = false,
        var textWatcherInstalled: Boolean = false,
        var pendingChange: Runnable? = null,
        var nativeValue: String = "",
        var baseText: String = "",
        var pressOpacity: Float = 0.72f,
        var scrollScheduled: Boolean = false,
        var pendingScrollOffset: Float = 0f,
        var endReachedSent: Boolean = false,
        var keyboardBehavior: Int = KEYBOARD_RESIZE,
        var safeBottomInset: Int = 0,
        var safeAreaLeftInset: Int = 0,
        var safeAreaTopInset: Int = 0,
        var safeAreaRightInset: Int = 0,
        var safeAreaBottomInset: Int = 0,
        var keyboardInset: Int = 0,
        var keyboardBaseHeight: Int = 0,
        var keyboardLayoutListener: View.OnLayoutChangeListener? = null,
        var keyboardAvoidingScrollId: Long = 0L,
        var defaultHighlightColor: Int = Color.TRANSPARENT,
        var propertyAnimator: ObjectAnimator? = null,
        var keyframeAnimator: ValueAnimator? = null,
        var loadingDrawable: PamButtonLoadingDrawable? = null,
        var virtual: Boolean = false,
        var imageLoading: Boolean = false,
        var imageProgressScheduled: Boolean = false,
        var imageProgressLoaded: Long = 0L,
        var imageProgressTotal: Long = 0L,
        var inputSelectionScheduled: Boolean = false,
        var directiveLayoutListener: View.OnLayoutChangeListener? = null,
        var nativeInteractionsInstalled: Boolean = false,
        var outsidePointerObserver: ((MotionEvent) -> Unit)? = null,
        var lastDirectiveIntersection: Boolean? = null,
        var inputSelectionStart: Int = 0,
        var inputSelectionEnd: Int = 0,
    ) {
        fun inputSyncMode(): Int = integer(PropKey.INPUT_SYNC_MODE, INPUT_SYNC_DEBOUNCED.toLong()).toInt()

        fun inputDebounceMs(): Long =
            integer(PropKey.INPUT_DEBOUNCE_MS, 48L).coerceIn(0L, 5_000L)

        fun targetAlpha(): Float = number(PropKey.OPACITY, 1.0).toFloat()

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

        fun pointerCallback(
            key: PropKey,
            callback: (PamPressPointer) -> Unit,
        ): ((PamPressPointer) -> Unit)? =
            callback.takeIf { properties[key] != null }
    }

    private companion object {
        const val LOCAL_MODAL_PREFIX = "pam:local-modal:"
        const val LOCAL_MODAL_TRIGGER_PREFIX = "pam:local-modal-trigger:"
        const val MODAL_CLOSE_MARKER = "pam:modal-close"
        const val MODAL_CLOSE_ACCESSIBILITY_LABEL = "Close modal"
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
        const val INPUT_ALIGN_AUTO = 1
        const val INPUT_ALIGN_TOP = 2
        const val INPUT_ALIGN_CENTER = 3
        const val INPUT_ALIGN_BOTTOM = 4
        const val MAX_AUTOFILL_HINT_BYTES = 128
        const val MAX_INPUT_KEY_BYTES = 64
        const val KEYBOARD_RESIZE = 1
        const val KEYBOARD_PAN = 2
        const val KEYBOARD_PADDING = 3
        const val SAFE_AREA_PADDING = 1
        const val SAFE_AREA_MARGIN = 2
        const val OVERFLOW_VISIBLE = 1L
        const val OVERFLOW_HIDDEN = 2L
        const val REFRESH_SIZE_DEFAULT = 1
        const val STATUS_BAR_DARK = 1
        const val STATUS_BAR_LIGHT = 2
        const val STATUS_BAR_ANIMATION_DURATION_MS = 300L
        const val TEXT_ELLIPSIZE_HEAD = 2
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
            // A semantic value on a layout container is also its Android tag.
            // Native compound hosts query these tagged descendants for
            // calendars, accordions, tabs, overlays, file trees and pagers;
            // flattening the node makes that authored anatomy disappear.
            PropKey.VALUE,
            PropKey.BACKGROUND_COLOR,
            PropKey.BORDER_RADIUS,
            PropKey.BORDER_WIDTH,
            PropKey.BORDER_COLOR,
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
            PropKey.ANIMATION_KIND,
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
            PropKey.TRANSLATION_X,
            PropKey.TRANSLATION_Y,
            PropKey.SCALE_X,
            PropKey.SCALE_Y,
            PropKey.ROTATION,
            PropKey.ANIMATE_CHANGES,
            PropKey.OVERFLOW,
        )

        const val ROLE_DESCRIPTION_KEY = "AccessibilityNodeInfo.roleDescription"
        const val STATE_DESCRIPTION_KEY =
            "androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY"

        val EVENT_PROPERTIES = setOf(
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
            PropKey.ON_DRAG_START,
            PropKey.ON_DRAG_END,
            PropKey.ON_DROP,
            PropKey.ON_MENU_ACTION,
            PropKey.ON_NAVIGATION_GESTURE_POP,
            PropKey.ON_ANIMATION_COMPLETE,
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
