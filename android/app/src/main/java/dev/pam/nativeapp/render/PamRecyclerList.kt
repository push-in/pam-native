package dev.pam.nativeapp.render

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.pam.nativeapp.protocol.PackedSectionList
import dev.pam.nativeapp.protocol.PackedStringList
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max

internal class PamRecyclerList(context: Context) : RecyclerView(context) {
    private var rowHeight = 48f
    private var horizontal = false
    private var columns = 1
    private var inverted = false
    private var prefetchItems = 5
    private var initialIndex = 0
    private var initialPositionApplied = false
    private var scrollEnabled = true
    private var showsScrollIndicator = true
    private var removeClippedSubviews = true
    private var rowTextColor = context.themeColor(
        android.R.attr.textColorPrimary,
        Color.BLACK,
    )
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchMoved = false
    private var viewportChanged: ((Float, Int, Int, Int) -> Unit)? = null

    init {
        itemAnimator = null
        isNestedScrollingEnabled = true
        clipToPadding = false
        setHasFixedSize(true)
        updateLayoutManager()
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                dispatchViewport()
            }
        })
    }

    fun setItems(items: PackedStringList?) {
        adapter = items?.let { PackedStringRecyclerAdapter(context, it) }
        configureAdapter()
        applyInitialPosition()
    }

    fun setSections(sections: PackedSectionList?) {
        adapter = sections?.let { PackedSectionRecyclerAdapter(context, it) }
        configureAdapter()
        updateHeaderSpans()
        applyInitialPosition()
    }

    fun setRichItems(
        ids: List<Long>,
        extents: Map<Long, Float>,
        mount: (Long, FrameLayout) -> Unit,
        unmount: (Long, FrameLayout) -> Unit,
    ) {
        val pixelExtents = extents.mapValues { (_, value) -> dp(value.coerceAtLeast(1f)) }
        val current = adapter as? RichRecyclerAdapter
        if (current == null) {
            adapter = RichRecyclerAdapter(
                context,
                ids,
                pixelExtents,
                mount,
                unmount,
            )
        } else {
            current.submit(ids, pixelExtents)
        }
        configureAdapter()
        updateHeaderSpans()
        applyInitialPosition()
    }

    fun setRowHeight(value: Float) {
        rowHeight = value.coerceAtLeast(1f)
        configureAdapter()
        updatePrefetch()
    }

    fun setHorizontal(value: Boolean) {
        if (horizontal == value) return
        horizontal = value
        if (value) columns = 1
        updateLayoutManager()
    }

    fun setColumns(value: Int) {
        val next = if (horizontal) 1 else value.coerceAtLeast(1)
        if (columns == next) return
        columns = next
        updateLayoutManager()
    }

    fun setInverted(value: Boolean) {
        if (inverted == value) return
        inverted = value
        updateLayoutManager()
    }

    fun setPrefetchItems(value: Int) {
        prefetchItems = value.coerceIn(1, MAX_PREFETCH_ITEMS)
        updatePrefetch()
    }

    fun setInitialIndex(value: Int) {
        val next = value.coerceAtLeast(0)
        if (initialIndex == next && initialPositionApplied) return
        initialIndex = next
        initialPositionApplied = false
        applyInitialPosition()
    }

    fun setRemoveClippedSubviews(value: Boolean) {
        removeClippedSubviews = value
        updatePrefetch()
    }

    fun setScrollEnabled(value: Boolean) {
        scrollEnabled = value
        isEnabled = value
        if (!value) stopScroll()
    }

    fun setShowsScrollIndicator(value: Boolean) {
        showsScrollIndicator = value
        isVerticalScrollBarEnabled = value && !horizontal
        isHorizontalScrollBarEnabled = value && horizontal
    }

    fun setTextColor(value: Int) {
        if (rowTextColor == value) return
        rowTextColor = value
        configureAdapter()
    }

    fun setOnViewportChanged(listener: ((Float, Int, Int, Int) -> Unit)?) {
        viewportChanged = listener
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!scrollEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = if (horizontal) {
                    event.x - touchDownX
                } else {
                    event.y - touchDownY
                }
                val direction = if (delta < 0f) 1 else -1
                val canScroll = if (horizontal) {
                    canScrollHorizontally(direction)
                } else {
                    canScrollVertically(direction)
                }
                if (abs(delta) > touchSlop && !canScroll) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!scrollEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (
                    abs(event.x - touchDownX) > touchSlop ||
                    abs(event.y - touchDownY) > touchSlop
                ) {
                    touchMoved = true
                }
            }
        }
        val handled = super.onTouchEvent(event)
        if (handled && event.actionMasked == MotionEvent.ACTION_UP && !touchMoved) {
            performClick()
        }
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            touchMoved = false
        }
        return handled
    }

    override fun performClick(): Boolean = super.performClick()

    private fun updateLayoutManager() {
        val previous = layoutManager as? LinearLayoutManager
        val position = previous?.findFirstVisibleItemPosition()?.coerceAtLeast(0) ?: 0
        val previousHorizontal = previous?.orientation == HORIZONTAL
        val offset = previous
            ?.findViewByPosition(position)
            ?.let {
                if (previousHorizontal) {
                    it.left - paddingLeft
                } else {
                    it.top - paddingTop
                }
            }
            ?: 0
        val orientation = if (horizontal) HORIZONTAL else VERTICAL
        layoutManager = if (columns > 1 && !horizontal) {
            PamGridLayoutManager(
                context,
                columns,
                orientation,
                inverted,
            )
        } else {
            PamLinearLayoutManager(
                context,
                orientation,
                inverted,
            )
        }
        (layoutManager as LinearLayoutManager).stackFromEnd = inverted
        configureAdapter()
        updateHeaderSpans()
        updatePrefetch()
        if ((adapter?.itemCount ?: 0) > 0) {
            (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position, offset)
        }
        setShowsScrollIndicator(showsScrollIndicator)
    }

    private fun updatePrefetch() {
        val extent = dp(rowHeight)
        (layoutManager as? PrefetchLayoutManager)?.apply {
            prefetchCount = prefetchItems
            extraLayoutSpace = extent * prefetchItems
        }
        val requestedCache = prefetchItems * max(1, columns)
        val cache = if (removeClippedSubviews) {
            requestedCache
        } else {
            max(requestedCache, (adapter?.itemCount ?: 0).coerceAtMost(64))
        }
        setItemViewCacheSize(cache.coerceAtMost(64))
        val recycled = (prefetchItems * max(1, columns) * 2).coerceAtMost(96)
        recycledViewPool.setMaxRecycledViews(PackedRowAdapter.TYPE_ITEM, recycled)
        recycledViewPool.setMaxRecycledViews(PackedRowAdapter.TYPE_HEADER, prefetchItems)
    }

    private fun configureAdapter() {
        (adapter as? PackedRowAdapter)?.configure(
            extent = dp(rowHeight),
            horizontal = horizontal,
            textColor = rowTextColor,
        )
        (adapter as? RichRecyclerAdapter)?.configure(
            extent = dp(rowHeight),
            horizontal = horizontal,
        )
    }

    private fun updateHeaderSpans() {
        val grid = layoutManager as? GridLayoutManager ?: return
        val rows = adapter as? PackedRowAdapter ?: return
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (rows.isHeader(position)) grid.spanCount else 1
        }
    }

    private fun applyInitialPosition() {
        if (initialPositionApplied) return
        val count = adapter?.itemCount ?: 0
        if (count == 0) return
        val target = initialIndex.coerceAtMost(count - 1)
        post {
            (layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(target, 0)
            initialPositionApplied = true
        }
    }

    private fun dispatchViewport() {
        val layout = layoutManager as? LinearLayoutManager ?: return
        val first = layout.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = layout.findLastVisibleItemPosition()
        val visible = if (last >= first) last - first + 1 else 0
        val total = adapter?.itemCount ?: 0
        val offset = if (horizontal) {
            computeHorizontalScrollOffset()
        } else {
            computeVerticalScrollOffset()
        } / resources.displayMetrics.density
        viewportChanged?.invoke(offset, first, visible, total)
    }

    private fun dp(value: Float): Int =
        max(1, (value * resources.displayMetrics.density + 0.5f).toInt())

    private interface PrefetchLayoutManager {
        var prefetchCount: Int
        var extraLayoutSpace: Int
    }

    private class PamLinearLayoutManager(
        context: Context,
        orientation: Int,
        reverseLayout: Boolean,
    ) : LinearLayoutManager(context, orientation, reverseLayout), PrefetchLayoutManager {
        override var prefetchCount = 5
            set(value) {
                field = value
                initialPrefetchItemCount = value
                isItemPrefetchEnabled = value > 0
            }
        override var extraLayoutSpace = 0

        override fun calculateExtraLayoutSpace(
            state: State,
            extraLayoutSpace: IntArray,
        ) {
            super.calculateExtraLayoutSpace(state, extraLayoutSpace)
            extraLayoutSpace[0] = max(extraLayoutSpace[0], this.extraLayoutSpace)
            extraLayoutSpace[1] = max(extraLayoutSpace[1], this.extraLayoutSpace)
        }
    }

    private class PamGridLayoutManager(
        context: Context,
        spanCount: Int,
        orientation: Int,
        reverseLayout: Boolean,
    ) : GridLayoutManager(
        context,
        spanCount,
        orientation,
        reverseLayout,
    ), PrefetchLayoutManager {
        override var prefetchCount = 5
            set(value) {
                field = value
                initialPrefetchItemCount = value
                isItemPrefetchEnabled = value > 0
            }
        override var extraLayoutSpace = 0

        override fun calculateExtraLayoutSpace(
            state: State,
            extraLayoutSpace: IntArray,
        ) {
            super.calculateExtraLayoutSpace(state, extraLayoutSpace)
            extraLayoutSpace[0] = max(extraLayoutSpace[0], this.extraLayoutSpace)
            extraLayoutSpace[1] = max(extraLayoutSpace[1], this.extraLayoutSpace)
        }
    }

    private companion object {
        const val MAX_PREFETCH_ITEMS = 32
    }
}

private class RichRecyclerAdapter(
    private val context: Context,
    ids: List<Long>,
    extents: Map<Long, Int>,
    private val mount: (Long, FrameLayout) -> Unit,
    private val unmount: (Long, FrameLayout) -> Unit,
) : RecyclerView.Adapter<RichRecyclerAdapter.RichHolder>() {
    private var ids = ids.toList()
    private var extents = extents.toMap()
    private var extent = dp(48f)
    private var horizontal = false
    private val boundHolders: MutableSet<RichHolder> = Collections.newSetFromMap(
        IdentityHashMap(),
    )

    init {
        setHasStableIds(true)
    }

    fun submit(next: List<Long>, nextExtents: Map<Long, Int>) {
        if (ids == next && extents == nextExtents) return
        val previous = ids
        val previousExtents = extents
        val replacement = next.toList()
        val replacementExtents = nextExtents.toMap()
        if (previous == replacement) {
            extents = replacementExtents
            boundHolders.forEach { holder ->
                val id = holder.boundId
                if (
                    id != RecyclerView.NO_ID &&
                    previousExtents[id] != replacementExtents[id]
                ) {
                    applyLayout(holder.container, id)
                }
            }
            return
        }
        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = previous.size

                override fun getNewListSize(): Int = replacement.size

                override fun areItemsTheSame(oldPosition: Int, newPosition: Int): Boolean =
                    previous[oldPosition] == replacement[newPosition]

                override fun areContentsTheSame(oldPosition: Int, newPosition: Int): Boolean {
                    val oldId = previous[oldPosition]
                    val newId = replacement[newPosition]
                    return previousExtents[oldId] == replacementExtents[newId]
                }

                override fun getChangePayload(oldPosition: Int, newPosition: Int): Any =
                    PAYLOAD_LAYOUT
            },
            true,
        )
        ids = replacement
        extents = replacementExtents
        diff.dispatchUpdatesTo(this)
    }

    fun configure(extent: Int, horizontal: Boolean) {
        if (this.extent == extent && this.horizontal == horizontal) return
        this.extent = extent
        this.horizontal = horizontal
        notifyItemRangeChanged(0, itemCount, PAYLOAD_LAYOUT)
    }

    override fun getItemCount(): Int = ids.size

    override fun getItemId(position: Int): Long = ids[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RichHolder =
        RichHolder(FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })

    override fun onBindViewHolder(holder: RichHolder, position: Int) {
        bind(holder, ids[position])
    }

    override fun onBindViewHolder(
        holder: RichHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.size == 1 && payloads[0] === PAYLOAD_LAYOUT) {
            applyLayout(holder.container, ids[position])
        } else {
            bind(holder, ids[position])
        }
    }

    override fun onViewRecycled(holder: RichHolder) {
        boundHolders.remove(holder)
        holder.boundId.takeIf { it != RecyclerView.NO_ID }?.let {
            unmount(it, holder.container)
        }
        holder.boundId = RecyclerView.NO_ID
        holder.container.removeAllViews()
        super.onViewRecycled(holder)
    }

    private fun bind(holder: RichHolder, id: Long) {
        val previous = holder.boundId
        if (previous != RecyclerView.NO_ID && previous != id) {
            unmount(previous, holder.container)
            holder.container.removeAllViews()
        }
        applyLayout(holder.container, id)
        mount(id, holder.container)
        holder.boundId = id
        boundHolders += holder
    }

    private fun applyLayout(container: FrameLayout, id: Long) {
        val itemExtent = extents[id] ?: extent
        val width = if (horizontal) itemExtent else ViewGroup.LayoutParams.MATCH_PARENT
        val height = if (horizontal) ViewGroup.LayoutParams.MATCH_PARENT else itemExtent
        val params = container.layoutParams as? RecyclerView.LayoutParams
        if (params == null) {
            container.layoutParams = RecyclerView.LayoutParams(width, height)
            return
        }
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        container.requestLayout()
    }

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    class RichHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        var boundId: Long = RecyclerView.NO_ID
    }

    private companion object {
        val PAYLOAD_LAYOUT = Any()
    }
}

private abstract class PackedRowAdapter(
    private val context: Context,
) : RecyclerView.Adapter<PackedRowAdapter.RowHolder>() {
    private var extent = dp(48f)
    private var horizontal = false
    private var textColor = context.themeColor(
        android.R.attr.textColorPrimary,
        Color.BLACK,
    )
    private val headerBackground = context.themeColor(
        android.R.attr.colorControlHighlight,
        0x1F000000,
    )

    init {
        setHasStableIds(false)
    }

    fun configure(extent: Int, horizontal: Boolean, textColor: Int) {
        this.extent = extent
        this.horizontal = horizontal
        this.textColor = textColor
        notifyItemRangeChanged(0, itemCount, PAYLOAD_LAYOUT)
    }

    open fun isHeader(position: Int): Boolean = false

    override fun getItemViewType(position: Int): Int =
        if (isHeader(position)) TYPE_HEADER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
        RowHolder(TextView(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), 0, dp(16f), 0)
            includeFontPadding = false
            setTextColor(textColor)
        })

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        bind(holder, position)
    }

    override fun onBindViewHolder(
        holder: RowHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.size == 1 && payloads[0] === PAYLOAD_LAYOUT) {
            applyLayout(holder.text)
            return
        }
        bind(holder, position)
    }

    private fun bind(holder: RowHolder, position: Int) {
        val header = isHeader(position)
        holder.text.apply {
            text = value(position)
            setTextColor(textColor)
            setTypeface(typeface, if (header) Typeface.BOLD else Typeface.NORMAL)
            setBackgroundColor(if (header) headerBackground else Color.TRANSPARENT)
            isEnabled = !header
        }
        applyLayout(holder.text)
    }

    private fun applyLayout(text: TextView) {
        val width = if (horizontal) extent else ViewGroup.LayoutParams.MATCH_PARENT
        val height = if (horizontal) ViewGroup.LayoutParams.MATCH_PARENT else extent
        val params = text.layoutParams as? RecyclerView.LayoutParams
        if (params == null) {
            text.layoutParams = RecyclerView.LayoutParams(width, height)
            return
        }
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        text.requestLayout()
    }

    protected abstract fun value(position: Int): String

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    class RowHolder(val text: TextView) : RecyclerView.ViewHolder(text)

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
        private val PAYLOAD_LAYOUT = Any()
    }
}

private fun Context.themeColor(attribute: Int, fallback: Int): Int {
    val value = TypedValue()
    if (!theme.resolveAttribute(attribute, value, true)) return fallback
    return when {
        value.resourceId != 0 ->
            ColorStateList.valueOf(getColor(value.resourceId)).defaultColor
        value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT ->
            value.data
        else -> fallback
    }
}

private class PackedStringRecyclerAdapter(
    context: Context,
    private val items: PackedStringList,
) : PackedRowAdapter(context) {
    override fun getItemCount(): Int = items.size
    override fun value(position: Int): String = items[position]
}

private class PackedSectionRecyclerAdapter(
    context: Context,
    private val sections: PackedSectionList,
) : PackedRowAdapter(context) {
    override fun getItemCount(): Int = sections.size
    override fun value(position: Int): String = sections[position]
    override fun isHeader(position: Int): Boolean = sections.isHeader(position)
}
