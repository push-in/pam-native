package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.LongSparseArray
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PackedSectionList
import dev.pam.nativeapp.protocol.PackedStringList
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import dev.pam.nativeapp.views.NativeViewRegistry
import java.util.LinkedHashSet
import kotlin.math.max

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
    private val imageLoader = NativeImageLoader()
    private val nativeViews = NativeViewRegistry(context)
    private var rootId = 0L

    fun commit(batches: List<List<Mutation>>) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Native mutations must be mounted on the Android UI thread"
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
        dirtyLayouts.forEach(::applyLayout)
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
        imageLoader.close()
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
            virtual = isLayoutOnly(spec),
        )
        nodes.put(spec.id, state)
        addChild(state.parent, state.id)
        if (!state.virtual) {
            val view = createView(spec.kind, state)
            views.put(spec.id, view)
            attachHosted(view, state)
            state.properties.forEach { (key, value) -> applyProperty(view, state, key, value) }
            installEvents(view, state)
        }
    }

    private fun createView(kind: NodeKind, state: NodeState? = null): View =
        when (kind) {
            NodeKind.SCREEN,
            NodeKind.COLUMN,
            NodeKind.ROW,
            NodeKind.VIEW,
            NodeKind.PRESSABLE,
            NodeKind.INPUT_ACCESSORY_VIEW,
            -> PamContainer(context)
            NodeKind.TEXT -> TextView(context).apply {
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
            }
            NodeKind.BUTTON -> Button(context).apply {
                isAllCaps = false
                minHeight = 0
                minWidth = 0
            }
            NodeKind.INPUT -> EditText(context).apply {
                isSingleLine = true
                minHeight = 0
                setPadding(dp(12f), 0, dp(12f), 0)
            }
            NodeKind.IMAGE -> ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            NodeKind.IMAGE_BACKGROUND -> PamImageBackground(context)
            NodeKind.SCROLL -> ScrollView(context).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
            }
            NodeKind.LIST,
            NodeKind.SECTION_LIST,
            -> ListView(context).apply {
                dividerHeight = 0
                isVerticalScrollBarEnabled = false
                isFastScrollEnabled = true
            }
            NodeKind.SPACER,
            NodeKind.STATUS_BAR,
            -> Space(context)
            NodeKind.ACTIVITY_INDICATOR -> ProgressBar(context)
            NodeKind.SWITCH -> Switch(context)
            NodeKind.MODAL -> PamModalHost(context)
            NodeKind.KEYBOARD_AVOIDING_VIEW -> PamContainer(context).also(::installKeyboardInsets)
            NodeKind.REFRESH_CONTROL -> PamRefreshContainer(context)
            NodeKind.SAFE_AREA_VIEW -> PamContainer(context).also(::installSafeArea)
            NodeKind.DRAWER_LAYOUT -> PamDrawerLayout(context)
            NodeKind.CUSTOM_VIEW -> {
                val custom = requireNotNull(state) { "Custom native view requires node state" }
                val name = custom.properties[PropKey.HOST_NAME]?.text()
                    ?: error("Custom native view is missing its generated name")
                nativeViews.create(name) { payload ->
                    if (custom.properties[PropKey.ON_NATIVE_EVENT] != null) {
                        dispatchBytes(custom.id, EVENT_NATIVE, payload)
                    }
                }
            }
        }

    private fun remove(id: Long) {
        val state = nodes[id] ?: return
        val view = views[id]
        state.pendingChange?.let(main::removeCallbacks)
        (view as? PamModalHost)?.close()
        view?.let(nativeViews::release)
        (view?.parent as? ViewGroup)?.removeView(view)
        removeChild(state.parent, id)
        children.remove(id)
        views.remove(id)
        nodes.remove(id)
        frames.remove(id)
        if (id == rootId) rootId = 0L
    }

    private fun update(id: Long, key: PropKey, value: PropValue?) {
        val state = nodes[id] ?: return
        if (value == null) {
            state.properties.remove(key)
        } else {
            state.properties[key] = value
        }
        val shouldBeVirtual = isLayoutOnly(state)
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
    }

    private fun move(id: Long, parent: Long, index: Int) {
        val state = nodes[id] ?: return
        val view = views[id]
        (view?.parent as? ViewGroup)?.removeView(view)
        removeChild(state.parent, id)
        state.parent = parent
        state.index = index
        addChild(parent, id)
        if (view != null) {
            attachHosted(view, state)
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

    private fun attach(view: View, parentId: Long, index: Int) {
        if (parentId == 0L) {
            host.addView(view, index.coerceIn(0, host.childCount))
            return
        }
        when (val parent = views[parentId]) {
            is PamContainer -> parent.insert(view, index)
            is PamImageBackground -> parent.insert(view, index)
            is PamRefreshContainer -> parent.insert(view, index)
            is PamDrawerLayout -> parent.insert(view, index)
            is PamModalHost -> parent.insert(view, index)
            is ScrollView -> {
                check(parent.childCount == 0) { "Scroll accepts exactly one child" }
                parent.addView(view)
            }
            else -> error("Node $parentId cannot contain children")
        }
    }

    private fun attachHosted(view: View, state: NodeState) {
        val parent = effectiveParent(state.parent)
        val index = if (parent == state.parent) state.index else {
            (views[parent] as? ViewGroup)?.childCount ?: host.childCount
        }
        attach(view, parent, index)
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
                (child.parent as? ViewGroup)?.removeView(child)
                attachHosted(child, childState)
                applyLayout(childId)
            }
        }
    }

    private fun applyLayout(id: Long) {
        val frame = frames[id] ?: return
        val view = views[id] ?: return
        val state = nodes[id] ?: return
        val parentFrame = frames[effectiveParent(state.parent)]
        val left = frame.x - (parentFrame?.x ?: 0f)
        val top = frame.y - (parentFrame?.y ?: 0f)
        val width = dp(frame.width).coerceAtLeast(0)
        val height = dp(frame.height).coerceAtLeast(0)
        val leftPx = dp(left)
        val topPx = dp(top)
        val current = view.layoutParams as? ViewGroup.MarginLayoutParams

        if (
            current == null ||
            current.width != width ||
            current.height != height ||
            current.leftMargin != leftPx ||
            current.topMargin != topPx
        ) {
            view.layoutParams = FrameLayout.LayoutParams(width, height).apply {
                leftMargin = leftPx
                topMargin = topPx
            }
        }
    }

    private fun applyProperty(
        view: View,
        state: NodeState,
        key: PropKey,
        value: PropValue,
    ) {
        when (key) {
            PropKey.TEXT -> (view as? TextView)?.let { text ->
                text.text = value.text()
                state.baseText = value.text()
            }
            PropKey.VALUE -> applyInputValue(view, state, value.text())
            PropKey.PLACEHOLDER -> (view as? EditText)?.hint = value.text()
            PropKey.SOURCE -> when (view) {
                is ImageView -> imageLoader.load(value.text(), view)
                is PamImageBackground -> imageLoader.load(value.text(), view.image)
            }
            PropKey.BACKGROUND_COLOR,
            PropKey.BORDER_RADIUS,
            PropKey.BORDER_WIDTH,
            PropKey.BORDER_COLOR,
            PropKey.RIPPLE_COLOR,
            -> updateBackground(view, state)
            PropKey.TEXT_COLOR -> (view as? TextView)?.setTextColor(value.integer().toInt())
            PropKey.FONT_SIZE -> (view as? TextView)?.textSize = value.decimal().toFloat()
            PropKey.ENABLED -> view.isEnabled = value.flag()
            PropKey.ACCESSIBILITY_LABEL -> view.contentDescription = value.text()
            PropKey.ACCESSIBILITY_HINT -> view.tooltipText = value.text()
            PropKey.TEST_ID -> view.transitionName = value.text()
            PropKey.ITEMS -> applyStringList(view, state, value)
            PropKey.SECTION_ITEMS -> applySectionList(view, state, value)
            PropKey.OPACITY -> animateOrSet(view, state, key, value.decimal().toFloat())
            PropKey.TEXT_ALIGN -> (view as? TextView)?.gravity = when (value.integer().toInt()) {
                2 -> Gravity.CENTER
                3 -> Gravity.END or Gravity.CENTER_VERTICAL
                else -> Gravity.START or Gravity.CENTER_VERTICAL
            }
            PropKey.FONT_WEIGHT -> (view as? TextView)?.setTypeface(
                view.typeface,
                if (value.integer() >= 600) Typeface.BOLD else Typeface.NORMAL,
            )
            PropKey.NUMBER_OF_LINES -> (view as? TextView)?.maxLines = value.integer().toInt()
            PropKey.MULTILINE -> (view as? EditText)?.let { input ->
                input.isSingleLine = !value.flag()
                input.inputType = if (value.flag()) {
                    input.inputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                } else {
                    input.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE.inv()
                }
            }
            PropKey.SECURE -> (view as? EditText)?.transformationMethod =
                if (value.flag()) PasswordTransformationMethod.getInstance() else null
            PropKey.KEYBOARD_TYPE -> (view as? EditText)?.inputType = keyboardType(value.integer().toInt())
            PropKey.AUTO_COMPLETE -> (view as? EditText)?.setAutofillHints(value.text())
            PropKey.CHECKED -> if (view is Switch && view.isChecked != value.flag()) {
                state.updating = true
                view.isChecked = value.flag()
                state.updating = false
            }
            PropKey.LOADING -> applyLoading(view, state, value.flag())
            PropKey.PROGRESS_COLOR -> (view as? ProgressBar)?.indeterminateTintList =
                ColorStateList.valueOf(value.integer().toInt())
            PropKey.IMAGE_FIT -> imageView(view)?.scaleType = when (value.integer().toInt()) {
                2 -> ImageView.ScaleType.CENTER_INSIDE
                3 -> ImageView.ScaleType.FIT_XY
                4 -> ImageView.ScaleType.CENTER
                else -> ImageView.ScaleType.CENTER_CROP
            }
            PropKey.TINT_COLOR -> imageView(view)?.imageTintList =
                ColorStateList.valueOf(value.integer().toInt())
            PropKey.ELEVATION -> view.elevation = dp(value.decimal().toFloat()).toFloat()
            PropKey.VISIBLE -> when (view) {
                is PamModalHost -> view.setVisible(value.flag())
                else -> view.visibility = if (value.flag()) View.VISIBLE else View.GONE
            }
            PropKey.MODAL_PRESENTATION -> (view as? PamModalHost)?.setPresentation(
                value.integer().toInt(),
            )
            PropKey.STATUS_BAR_COLOR -> applyStatusBarColor(value.integer().toInt())
            PropKey.STATUS_BAR_STYLE -> applyStatusBarAppearance(value.integer().toInt())
            PropKey.STATUS_BAR_HIDDEN -> applyStatusBarHidden(value.flag())
            PropKey.KEYBOARD_BEHAVIOR -> state.keyboardBehavior = value.integer().toInt()
            PropKey.REFRESHING -> (view as? PamRefreshContainer)?.setRefreshing(value.flag())
            PropKey.SCROLL_ENABLED -> when (view) {
                is ScrollView -> view.isEnabled = value.flag()
                is ListView -> view.isEnabled = value.flag()
            }
            PropKey.SHOWS_SCROLL_INDICATOR -> when (view) {
                is ScrollView -> view.isVerticalScrollBarEnabled = value.flag()
                is ListView -> view.isVerticalScrollBarEnabled = value.flag()
            }
            PropKey.SELECTED -> view.isSelected = value.flag()
            PropKey.PRESS_OPACITY -> state.pressOpacity = value.decimal().toFloat()
            PropKey.ACCESSIBILITY_ROLE -> {
                val className = accessibilityClass(value.integer().toInt())
                view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfo,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = className
                    }
                }
            }
            PropKey.TRANSLATION_X,
            PropKey.TRANSLATION_Y,
            PropKey.SCALE_X,
            PropKey.SCALE_Y,
            PropKey.ROTATION,
            -> animateOrSet(view, state, key, value.decimal().toFloat())
            PropKey.LIST_ROW_HEIGHT -> {
                state.listAdapter?.rowHeight = value.decimal().toFloat()
                state.sectionAdapter?.rowHeight = value.decimal().toFloat()
            }
            PropKey.DRAWER_OPEN -> (view as? PamDrawerLayout)?.setOpen(value.flag())
            PropKey.LETTER_SPACING -> (view as? TextView)?.letterSpacing = value.decimal().toFloat()
            PropKey.LINE_HEIGHT -> (view as? TextView)?.setLineSpacing(
                0f,
                max(0.1f, value.decimal().toFloat() / max(1f, view.textSize / resourcesDensity())),
            )
            PropKey.PLACEHOLDER_COLOR -> (view as? EditText)?.setHintTextColor(value.integer().toInt())
            PropKey.SELECTION_COLOR -> (view as? EditText)?.highlightColor = value.integer().toInt()
            PropKey.MAX_LENGTH -> (view as? EditText)?.filters = arrayOf(
                InputFilter.LengthFilter(value.integer().toInt()),
            )
            PropKey.AUTO_FOCUS -> if (value.flag()) {
                view.post { view.requestFocus() }
            } else {
                Unit
            }
            PropKey.Z_INDEX -> view.z = value.decimal().toFloat()
            PropKey.OVERFLOW -> (view as? ViewGroup)?.clipChildren = value.integer() == 2L
            PropKey.HOST_PROPERTIES -> {
                val properties = (value as? PropValue.Properties)?.value
                    ?: error("Expected native view property map")
                nativeViews.update(view, properties)
            }
            PropKey.PADDING,
            PropKey.PADDING_HORIZONTAL,
            PropKey.PADDING_VERTICAL,
            -> applyLeafPadding(view, state)
            PropKey.WIDTH,
            PropKey.HEIGHT,
            PropKey.FLEX_GROW,
            PropKey.GAP,
            PropKey.MARGIN,
            PropKey.MARGIN_HORIZONTAL,
            PropKey.MARGIN_VERTICAL,
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
            PropKey.ANIMATION_DURATION_MS,
            PropKey.ANIMATION_EASING,
            PropKey.ANIMATE_CHANGES,
            PropKey.LIST_PREFETCH,
            PropKey.ON_END_REACHED,
            PropKey.END_REACHED_THRESHOLD,
            PropKey.DRAWER_POSITION,
            PropKey.ON_DRAWER_OPEN,
            PropKey.ON_DRAWER_CLOSE,
            PropKey.RETURN_KEY_TYPE,
            PropKey.HIT_SLOP,
            PropKey.HOST_NAME,
            PropKey.ON_NATIVE_EVENT,
            -> Unit
        }
    }

    private fun resetProperty(view: View, state: NodeState, key: PropKey) {
        when (key) {
            PropKey.TEXT -> (view as? TextView)?.text = ""
            PropKey.VALUE -> (view as? EditText)?.setText("")
            PropKey.PLACEHOLDER -> (view as? EditText)?.hint = null
            PropKey.SOURCE -> imageView(view)?.setImageDrawable(null)
            PropKey.BACKGROUND_COLOR,
            PropKey.BORDER_RADIUS,
            PropKey.BORDER_WIDTH,
            PropKey.BORDER_COLOR,
            PropKey.RIPPLE_COLOR,
            -> updateBackground(view, state)
            PropKey.TEXT_COLOR -> (view as? TextView)?.setTextColor(Color.BLACK)
            PropKey.FONT_SIZE -> (view as? TextView)?.textSize = 14f
            PropKey.ENABLED -> view.isEnabled = true
            PropKey.ACCESSIBILITY_LABEL -> view.contentDescription = null
            PropKey.ACCESSIBILITY_HINT -> view.tooltipText = null
            PropKey.TEST_ID -> view.transitionName = null
            PropKey.ITEMS,
            PropKey.SECTION_ITEMS,
            -> (view as? ListView)?.adapter = null
            PropKey.OPACITY -> view.alpha = 1f
            PropKey.TRANSLATION_X -> view.translationX = 0f
            PropKey.TRANSLATION_Y -> view.translationY = 0f
            PropKey.SCALE_X -> view.scaleX = 1f
            PropKey.SCALE_Y -> view.scaleY = 1f
            PropKey.ROTATION -> view.rotation = 0f
            PropKey.VISIBLE -> view.visibility = View.VISIBLE
            PropKey.CHECKED -> (view as? Switch)?.isChecked = false
            PropKey.REFRESHING -> (view as? PamRefreshContainer)?.setRefreshing(false)
            PropKey.DRAWER_OPEN -> (view as? PamDrawerLayout)?.setOpen(false)
            PropKey.HOST_PROPERTIES -> Unit
            else -> Unit
        }
    }

    private fun installEvents(view: View, state: NodeState) {
        if (state.properties[PropKey.ON_PRESS] != null) {
            view.setOnClickListener { dispatch(state.id, EVENT_PRESS) }
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
        installPressFeedback(view, state)
        if (view is EditText) installInputEvents(view, state)
        if (view is Switch) {
            view.setOnCheckedChangeListener { _, checked ->
                if (!state.updating && state.properties[PropKey.ON_TOGGLE] != null) {
                    dispatch(state.id, EVENT_TOGGLE, if (checked) "1" else "0")
                }
            }
        }
        if (view is ScrollView) installScrollEvents(view, state)
        if (view is ListView) installListEvents(view, state)
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
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installPressFeedback(view: View, state: NodeState) {
        if (
            state.kind != NodeKind.PRESSABLE &&
            state.kind != NodeKind.BUTTON
        ) {
            return
        }
        if (
            state.properties[PropKey.ON_PRESS] == null &&
            state.properties[PropKey.ON_LONG_PRESS] == null
        ) {
            view.setOnTouchListener(null)
            return
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().alpha(state.pressOpacity).setDuration(70).start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> view.animate().alpha(state.targetAlpha()).setDuration(110).start()
            }
            false
        }
    }

    private fun installInputEvents(input: EditText, state: NodeState) {
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
                    if (state.updating || state.properties[PropKey.ON_CHANGE] == null) return
                    state.nativeValue = editable?.toString().orEmpty()
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
            }
        }
        input.setOnEditorActionListener { _, _, event ->
            val submitted = event == null || event.keyCode == KeyEvent.KEYCODE_ENTER
            if (submitted) {
                if (state.inputSyncMode() == INPUT_SYNC_NATIVE || state.inputSyncMode() == INPUT_SYNC_SUBMIT) {
                    dispatchInput(state)
                }
                if (state.properties[PropKey.ON_SUBMIT] != null) {
                    dispatch(state.id, EVENT_SUBMIT, input.text.toString())
                }
            }
            false
        }
    }

    private fun installScrollEvents(scroll: ScrollView, state: NodeState) {
        if (state.properties[PropKey.ON_SCROLL] == null) {
            scroll.setOnScrollChangeListener(null as View.OnScrollChangeListener?)
            return
        }
        scroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (state.scrollScheduled) return@setOnScrollChangeListener
            state.scrollScheduled = true
            Choreographer.getInstance().postFrameCallback {
                state.scrollScheduled = false
                dispatch(state.id, EVENT_SCROLL, (scrollY / resourcesDensity()).toString())
            }
        }
    }

    private fun installListEvents(list: ListView, state: NodeState) {
        if (state.properties[PropKey.ON_END_REACHED] == null) {
            list.setOnScrollListener(null)
            return
        }
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int,
            ) {
                if (totalItemCount == 0 || state.endReachedSent) return
                val threshold = state.number(PropKey.END_REACHED_THRESHOLD, 0.5).coerceIn(0.0, 1.0)
                val remaining = totalItemCount - firstVisibleItem - visibleItemCount
                val trigger = max(1, (visibleItemCount * threshold).toInt())
                if (remaining <= trigger) {
                    state.endReachedSent = true
                    dispatch(state.id, EVENT_END_REACHED)
                }
            }
        })
    }

    private fun dispatchInput(state: NodeState) {
        state.pendingChange?.let(main::removeCallbacks)
        state.pendingChange = null
        dispatch(state.id, EVENT_CHANGE, state.nativeValue)
    }

    private fun dispatch(id: Long, kind: Int, payload: String = "") {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        dispatchBytes(id, kind, bytes)
    }

    private fun dispatchBytes(id: Long, kind: Int, payload: ByteArray) {
        if (payload.size <= MAX_EVENT_BYTES) dispatchEvent(id, kind, payload)
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
        val list = view as? ListView ?: return
        val items = (value as? PropValue.Strings)?.value
            ?: error("Expected packed string list")
        val adapter = state.listAdapter
        if (adapter == null) {
            state.listAdapter = PackedStringAdapter(context, items).also { list.adapter = it }
        } else {
            adapter.update(items)
        }
        state.endReachedSent = false
    }

    private fun applySectionList(view: View, state: NodeState, value: PropValue) {
        val list = view as? ListView ?: return
        val sections = (value as? PropValue.Sections)?.value
            ?: error("Expected packed section list")
        val adapter = state.sectionAdapter
        if (adapter == null) {
            state.sectionAdapter = PackedSectionAdapter(context, sections).also { list.adapter = it }
        } else {
            adapter.update(sections)
        }
        state.endReachedSent = false
    }

    private fun applyLoading(view: View, state: NodeState, loading: Boolean) {
        val button = view as? Button ?: return
        button.isEnabled = !loading && state.flag(PropKey.ENABLED, true)
        button.text = if (loading) "…" else state.baseText
    }

    private fun applyLeafPadding(view: View, state: NodeState) {
        if (view is ViewGroup) return
        val all = state.number(PropKey.PADDING, 0.0).toFloat()
        val horizontal = state.number(PropKey.PADDING_HORIZONTAL, all.toDouble()).toFloat()
        val vertical = state.number(PropKey.PADDING_VERTICAL, all.toDouble()).toFloat()
        view.setPadding(dp(horizontal), dp(vertical), dp(horizontal), dp(vertical))
    }

    private fun updateBackground(view: View, state: NodeState) {
        val color = state.integer(PropKey.BACKGROUND_COLOR, Color.TRANSPARENT.toLong()).toInt()
        val radius = dp(state.number(PropKey.BORDER_RADIUS, 0.0).toFloat()).toFloat()
        val borderWidth = dp(state.number(PropKey.BORDER_WIDTH, 0.0).toFloat())
        val borderColor = state.integer(PropKey.BORDER_COLOR, Color.TRANSPARENT.toLong()).toInt()
        val shape = GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (borderWidth > 0) setStroke(borderWidth, borderColor)
        }
        val ripple = state.properties[PropKey.RIPPLE_COLOR]?.integer()?.toInt()
        view.background = if (ripple != null) {
            RippleDrawable(ColorStateList.valueOf(ripple), shape, null)
        } else {
            shape
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

    private fun installSafeArea(view: View) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                target.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        view.requestApplyInsets()
    }

    private fun installKeyboardInsets(view: View) {
        view.setOnApplyWindowInsetsListener { target, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val position = views.indexOfValue(target)
                val node = if (position >= 0) nodes[views.keyAt(position)] else null
                val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
                when (node?.keyboardBehavior ?: KEYBOARD_RESIZE) {
                    KEYBOARD_PAN -> target.translationY = -keyboard.toFloat()
                    KEYBOARD_PADDING -> target.setPadding(0, 0, 0, keyboard)
                    else -> {
                        target.translationY = 0f
                        target.setPadding(0, 0, 0, 0)
                    }
                }
            }
            insets
        }
        view.requestApplyInsets()
    }

    private fun applyStatusBarAppearance(value: Int) {
        val decor = activity()?.window?.decorView ?: return
        @Suppress("DEPRECATION")
        decor.systemUiVisibility = if (value == 1) {
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
    private fun applyStatusBarColor(color: Int) {
        activity()?.window?.statusBarColor = color
    }

    private fun imageView(view: View): ImageView? =
        when (view) {
            is ImageView -> view
            is PamImageBackground -> view.image
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

    private fun accessibilityClass(value: Int): String =
        when (value) {
            2 -> Button::class.java.name
            3 -> EditText::class.java.name
            4 -> ImageView::class.java.name
            5 -> Switch::class.java.name
            else -> View::class.java.name
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

    private fun PropValue.text(): String =
        (this as? PropValue.Text)?.value ?: error("Expected text property")

    private fun PropValue.integer(): Long =
        when (this) {
            is PropValue.Integer -> value
            else -> error("Expected integer property")
        }

    private fun PropValue.decimal(): Double =
        when (this) {
            is PropValue.Decimal -> value
            is PropValue.Integer -> value.toDouble()
            else -> error("Expected numeric property")
        }

    private fun PropValue.flag(): Boolean =
        (this as? PropValue.Flag)?.value ?: error("Expected boolean property")

    private fun dp(value: Float): Int =
        (value * resourcesDensity() + 0.5f).toInt()

    private fun resourcesDensity(): Float = context.resources.displayMetrics.density

    private data class NodeState(
        val id: Long,
        var parent: Long,
        var index: Int,
        val kind: NodeKind,
        val properties: MutableMap<PropKey, PropValue>,
        var updating: Boolean = false,
        var textWatcherInstalled: Boolean = false,
        var pendingChange: Runnable? = null,
        var nativeValue: String = "",
        var baseText: String = "",
        var pressOpacity: Float = 0.72f,
        var scrollScheduled: Boolean = false,
        var endReachedSent: Boolean = false,
        var keyboardBehavior: Int = KEYBOARD_RESIZE,
        var listAdapter: PackedStringAdapter? = null,
        var sectionAdapter: PackedSectionAdapter? = null,
        var virtual: Boolean = false,
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
    }

    private companion object {
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
        const val MAX_EVENT_BYTES = 1024 * 1024
        const val INPUT_SYNC_NATIVE = 1
        const val INPUT_SYNC_DEBOUNCED = 2
        const val INPUT_SYNC_IMMEDIATE = 3
        const val INPUT_SYNC_BLUR = 4
        const val INPUT_SYNC_SUBMIT = 5
        const val KEYBOARD_RESIZE = 1
        const val KEYBOARD_PAN = 2
        const val KEYBOARD_PADDING = 3
        const val MAX_VIRTUAL_DEPTH = 512

        val LAYOUT_ONLY_KINDS = setOf(
            NodeKind.COLUMN,
            NodeKind.ROW,
            NodeKind.VIEW,
        )

        val HOST_PROPERTIES = setOf(
            PropKey.BACKGROUND_COLOR,
            PropKey.BORDER_RADIUS,
            PropKey.BORDER_WIDTH,
            PropKey.BORDER_COLOR,
            PropKey.OPACITY,
            PropKey.ON_PRESS,
            PropKey.ON_LONG_PRESS,
            PropKey.ACCESSIBILITY_LABEL,
            PropKey.ACCESSIBILITY_HINT,
            PropKey.ACCESSIBILITY_ROLE,
            PropKey.TEST_ID,
            PropKey.RIPPLE_COLOR,
            PropKey.PRESS_OPACITY,
            PropKey.ELEVATION,
            PropKey.TRANSLATION_X,
            PropKey.TRANSLATION_Y,
            PropKey.SCALE_X,
            PropKey.SCALE_Y,
            PropKey.ROTATION,
            PropKey.ANIMATE_CHANGES,
            PropKey.OVERFLOW,
        )

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
        )
    }
}

private class PackedStringAdapter(
    private val context: Context,
    private var items: PackedStringList,
) : BaseAdapter() {
    var rowHeight = 48f

    fun update(next: PackedStringList) {
        items = next
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): String = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val text = convertView as? TextView ?: TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), 0, dp(16f), 0)
            includeFontPadding = false
        }
        text.minHeight = dp(rowHeight)
        text.text = items[position]
        return text
    }

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

private class PackedSectionAdapter(
    private val context: Context,
    private var sections: PackedSectionList,
) : BaseAdapter() {
    var rowHeight = 48f

    fun update(next: PackedSectionList) {
        sections = next
        notifyDataSetChanged()
    }

    override fun getCount(): Int = sections.size
    override fun getItem(position: Int): String = sections[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun getViewTypeCount(): Int = 2
    override fun getItemViewType(position: Int): Int = if (sections.isHeader(position)) 0 else 1
    override fun isEnabled(position: Int): Boolean = !sections.isHeader(position)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val text = convertView as? TextView ?: TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), 0, dp(16f), 0)
            includeFontPadding = false
        }
        val header = sections.isHeader(position)
        text.minHeight = dp(if (header) 36f else rowHeight)
        text.setTypeface(text.typeface, if (header) Typeface.BOLD else Typeface.NORMAL)
        text.setBackgroundColor(if (header) 0xFFF3F4F6.toInt() else Color.TRANSPARENT)
        text.text = sections[position]
        return text
    }

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
