package dev.pam.nativeapp.render

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
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
    private var adaptivePrefetchItems = prefetchItems
    private var lastScrollNanos = 0L
    private var initialIndex = 0
    private var initialPositionApplied = false
    private var initialPositionGeneration = 0
    private var pendingInitialGeneration = -1
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
    private var scrollPhase: ((Int, Float, Float, Float, Float) -> Unit)? = null
    private var pagerSnap: PagerSnapHelper? = null
    private var phaseDragging = false
    private var releaseVelocityX = 0f
    private var releaseVelocityY = 0f
    private var richIds: List<Long> = emptyList()
    private var richExtents: Map<Long, Int> = emptyMap()
    private val accessibilityModes = IdentityHashMap<View, Int>()
    private var stickyIds: Set<Long> = emptySet()
    private val stickyPins = StickyPins()

    init {
        itemAnimator = null
        // PamScrollContainer coordinates ownership explicitly so a bounded
        // list and its page never consume the same drag simultaneously.
        isNestedScrollingEnabled = false
        clipChildren = true
        clipToPadding = false
        setHasFixedSize(true)
        updateLayoutManager()
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                val listener = scrollPhase ?: return
                val density = resources.displayMetrics.density.coerceAtLeast(0.01f)
                val x = computeHorizontalScrollOffset() / density
                val y = computeVerticalScrollOffset() / density
                when (newState) {
                    SCROLL_STATE_DRAGGING -> {
                        phaseDragging = true
                        listener(PamScrollContainer.SCROLL_PHASE_BEGIN_DRAG, x, y, 0f, 0f)
                    }
                    SCROLL_STATE_SETTLING -> if (phaseDragging) {
                        phaseDragging = false
                        listener(
                            PamScrollContainer.SCROLL_PHASE_END_DRAG,
                            x,
                            y,
                            releaseVelocityX / density,
                            releaseVelocityY / density,
                        )
                    }
                    SCROLL_STATE_IDLE -> {
                        if (phaseDragging) {
                            phaseDragging = false
                            listener(PamScrollContainer.SCROLL_PHASE_END_DRAG, x, y, 0f, 0f)
                        }
                        listener(PamScrollContainer.SCROLL_PHASE_MOMENTUM_END, x, y, 0f, 0f)
                    }
                }
                releaseVelocityX = 0f
                releaseVelocityY = 0f
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                updateAdaptivePrefetch(if (horizontal) dx else dy)
                updateAccessibilityVisibility()
                dispatchViewport()
            }
        })
    }

    override fun onChildAttachedToWindow(child: View) {
        accessibilityModes.putIfAbsent(child, child.importantForAccessibility)
        super.onChildAttachedToWindow(child)
        updateAccessibilityVisibility()
    }

    override fun onChildDetachedFromWindow(child: View) {
        accessibilityModes.remove(child)?.let { child.importantForAccessibility = it }
        super.onChildDetachedFromWindow(child)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        android.os.Trace.beginSection("PamList.layout")
        super.onLayout(changed, left, top, right, bottom)
        updateAccessibilityVisibility()
        android.os.Trace.endSection()
    }

    /**
     * A viewport that shrinks while resting at its end (a banner or composer
     * growing around a chat timeline) keeps that end visible, like a
     * bottom-anchored React Native list, instead of hiding the newest rows.
     */
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val oldExtent = if (horizontal) oldWidth else oldHeight
        val newExtent = if (horizontal) width else height
        // A list resting at its start (an inbox whose first rows all fit)
        // is not "at its end": only one opened at its end or scrolled away
        // from its start follows the end.
        val wasAtEnd = initialPositionApplied && oldExtent > 0 && newExtent != oldExtent &&
            restingAtEnd() && (initialIndex > 0 || canScrollTowardStart())
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (wasAtEnd && !inverted) {
            post {
                val count = adapter?.itemCount ?: 0
                if (count > 0) (layoutManager as? LinearLayoutManager)?.scrollToPosition(count - 1)
            }
        }
    }

    /**
     * RecyclerView deliberately lays out prefetched rows beyond its clipped
     * viewport. Android's accessibility snapshot can otherwise intersect a
     * descendant with the clip and publish an inverted rectangle. Keep those
     * rows mounted for performance, but hide only fully offscreen rows.
     * Partially visible rows must remain reachable, including rows taller than
     * the viewport. Restore the original mode before a holder is recycled.
     */
    private fun updateAccessibilityVisibility() {
        val viewportLeft = paddingLeft
        val viewportTop = paddingTop
        val viewportRight = width - paddingRight
        val viewportBottom = height - paddingBottom
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val original = accessibilityModes[child] ?: child.importantForAccessibility.also {
                accessibilityModes[child] = it
            }
            val intersectsViewport = child.right > viewportLeft &&
                child.bottom > viewportTop &&
                child.left < viewportRight &&
                child.top < viewportBottom
            val desired = if (intersectsViewport) {
                original
            } else {
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            if (child.importantForAccessibility != desired) {
                child.importantForAccessibility = desired
            }
        }
    }

    fun setItems(items: PackedStringList?) {
        stickyPins.release()
        adapter = items?.let { PackedStringRecyclerAdapter(context, it) }
        configureAdapter()
        applyInitialPosition()
    }

    fun setSections(sections: PackedSectionList?) {
        stickyPins.release()
        adapter = sections?.let { PackedSectionRecyclerAdapter(context, it) }
        configureAdapter()
        updateHeaderSpans()
        applyInitialPosition()
    }

    /** Holder container bound to virtual cell [id], or null when it is off screen. */
    fun boundContainer(id: Long): FrameLayout? =
        (adapter as? RichRecyclerAdapter)?.boundContainer(id)

    /** Rebinds visible rows whose content was emptied, without a diff. */
    fun remountEmptyRows() {
        (adapter as? RichRecyclerAdapter)?.remountEmptyHolders()
    }

    fun setRichItems(
        ids: List<Long>,
        extents: Map<Long, Float>,
        mount: (Long, FrameLayout) -> Unit,
        unmount: (Long, FrameLayout) -> Unit,
    ) {
        val pixelExtents = extents.mapValues { (_, value) -> dp(value.coerceAtLeast(1f)) }
        // Content-sized cells are re-measured after mounting (fonts, images,
        // async text). When only extents change for the same rows, a list that
        // was resting at its end stays there instead of drifting by the delta.
        val keepEnd = initialPositionApplied &&
            ids.isNotEmpty() &&
            ids == richIds &&
            pixelExtents != richExtents &&
            restingAtEnd() &&
            (initialIndex > 0 || canScrollTowardStart())
        richIds = ids
        richExtents = pixelExtents
        val current = adapter as? RichRecyclerAdapter
        if (keepEnd) {
            post {
                if (!inverted) (layoutManager as? LinearLayoutManager)?.scrollToPosition(ids.size - 1)
            }
        }
        if (current == null) {
            adapter = RichRecyclerAdapter(
                context,
                ids,
                pixelExtents,
                mount,
                unmount,
                deferMounts = { !isShown },
            )
        } else {
            current.submit(ids, pixelExtents)
        }
        configureAdapter()
        updateHeaderSpans()
        applyInitialPosition()
    }

    /**
     * Aligns a rich cell using the same per-cell pixel extents the adapter
     * lays out. Converting an accumulated logical offset once drifts by the
     * per-cell rounding (about 9 px across a 33-row chat on a 2.625 density),
     * leaving end-aligned rows partially hidden. Returns false when [id] is
     * not a rich cell of this list.
     */
    fun scrollToRichItem(
        id: Long,
        alignment: Int,
        innerStartPx: Int = 0,
        targetExtentPx: Int? = null,
    ): Boolean {
        val index = richIds.indexOf(id)
        if (index < 0) return false
        initialPositionApplied = true
        val extents = richIds.map { richExtents[it] ?: dp(rowHeight) }
        val viewport = if (horizontal) width else height
        val available = (viewport - (targetExtentPx ?: extents[index])).coerceAtLeast(0)
        val adjustment = when (alignment) {
            2 -> available / 2
            3 -> available
            else -> 0
        }
        val start = extents.subList(0, index).sum() + innerStartPx
        val position = virtualScrollPosition(extents, (start - adjustment).coerceAtLeast(0))
        (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
            position.index,
            -position.offset,
        )
        dispatchViewport()
        return true
    }

    private var fullSpanIds: Set<Long> = emptySet()

    /** ListHeaderComponent/ListFooterComponent-style rows spanning every column. */
    fun setFullSpanIds(ids: Set<Long>) {
        if (fullSpanIds == ids) return
        fullSpanIds = ids
        updateHeaderSpans()
    }

    fun setStickyIds(ids: Set<Long>) {
        if (stickyIds == ids) return
        stickyIds = ids
        requestLayout()
    }

    /** The pinned sticky header holder, or null when no header is pinned. */
    internal fun pinnedStickyHeader(): View? = stickyPins.view

    /**
     * React Native sticky headers for virtualized lists (FlashList
     * `stickyHeaderIndices`): the last sticky cell at or above the top edge
     * stays pinned there until the next sticky cell pushes it away.
     *
     * The pinned header is a real RecyclerView child, not a picture: it is
     * drawn last and hit-tested first, so presses, pressed state and
     * accessibility reach it and the row scrolling underneath never receives
     * the touch. The layout manager ignores it (it is never scrapped or
     * recycled while pinned) and does not count it among its children, so
     * the rows keep virtualizing normally. The cell's mounted views move into
     * the pinned holder and back, so their native state (a horizontal tab
     * rail offset, a focused input) survives pinning. Everything runs inside
     * the layout manager's own scroll and layout passes: no PHP round trip
     * and no bitmap copy per frame.
     */
    private inner class StickyPins {
        var view: View? = null
            private set
        private var holder: RichRecyclerAdapter.RichHolder? = null
        private var pinnedId = NO_ID
        private var manager: LayoutManager? = null
        private var recycler: Recycler? = null
        private var positions = IntArray(0)
        private var positionsIds: List<Long>? = null
        private var positionsSticky: Set<Long>? = null

        /**
         * The layout manager never sees the pinned header: it stays the last
         * child and is left out of the manager's count, so index-based
         * scrap, recycle and fill passes keep addressing only the rows.
         */
        fun hidesLast(lastChild: View?): Boolean = lastChild != null && lastChild === view

        /** Rows the layout manager appends land before the pinned header, which stays last. */
        fun addIndex(child: View, index: Int, rows: Int): Int {
            val pinned = view ?: return index
            if (pinned === child || pinned.parent !== this@PamRecyclerList) return index
            return if (index < 0 || index > rows) rows else index
        }

        /** Runs after every layout and scroll pass of the layout manager. */
        fun place(manager: LayoutManager, recycler: Recycler, state: State) {
            if (state.isPreLayout) return
            this.manager = manager
            this.recycler = recycler
            val richAdapter = adapter as? RichRecyclerAdapter
            if (richAdapter == null || horizontal || inverted || stickyIds.isEmpty()) {
                release()
                return
            }
            val sticky = stickyPositions()
            val top = topPosition(manager)
            val slot = if (top < 0) -1 else lastAtOrBefore(sticky, top)
            if (slot < 0) {
                release()
                return
            }
            val position = sticky[slot]
            val id = richIds.getOrNull(position) ?: run {
                release()
                return
            }
            val natural = manager.findViewByPosition(position)
            if (natural != null && manager.getDecoratedTop(natural) >= 0) {
                release(natural)
                return
            }
            var target = view
            var targetHolder = holder
            if (target == null || targetHolder == null || pinnedId != id || target.parent !== this@PamRecyclerList) {
                release()
                val created = recycler.getViewForPosition(position)
                manager.addView(created)
                manager.ignoreView(created)
                targetHolder = getChildViewHolder(created) as? RichRecyclerAdapter.RichHolder
                if (targetHolder == null) {
                    manager.stopIgnoringView(created)
                    manager.removeAndRecycleView(created, recycler)
                    return
                }
                target = created
                view = created
                holder = targetHolder
                pinnedId = id
                (created as? RichCellContainer)?.pinned = true
                richAdapter.adoptPinned(targetHolder)
                measureAndLayout(manager, created)
            } else {
                richAdapter.adoptPinned(targetHolder)
                if (target.isLayoutRequested || target.width != manager.width - paddingLeft - paddingRight) {
                    measureAndLayout(manager, target)
                }
            }
            val next = sticky.getOrNull(slot + 1)?.let(manager::findViewByPosition)
            val y = if (next == null) 0 else minOf(0, manager.getDecoratedTop(next) - target.height)
            if (target.top != y) target.offsetTopAndBottom(y - target.top)
        }

        /** Keeps the pinned header in place while the layout manager offsets its rows. */
        fun compensateOffset(dy: Int) {
            view?.takeIf { it.parent === this@PamRecyclerList }?.offsetTopAndBottom(-dy)
        }

        /**
         * Unpins the header. Its views return to [home] (the cell's own row)
         * or any other holder bound to the cell; without one they unmount.
         */
        fun release(home: View? = null) {
            val target = view ?: return
            val targetHolder = holder
            view = null
            holder = null
            pinnedId = NO_ID
            (target as? RichCellContainer)?.pinned = false
            val homeHolder = home?.takeIf { it.parent === this@PamRecyclerList }
                ?.let(::getChildViewHolder) as? RichRecyclerAdapter.RichHolder
            if (targetHolder != null) (adapter as? RichRecyclerAdapter)?.unpin(targetHolder, homeHolder)
            val owner = manager
            val pool = recycler
            if (target.parent !== this@PamRecyclerList) return
            if (owner != null && pool != null && owner === layoutManager) {
                owner.stopIgnoringView(target)
                owner.removeAndRecycleView(target, pool)
            } else {
                owner?.stopIgnoringView(target)
                removeView(target)
            }
        }

        private fun measureAndLayout(manager: LayoutManager, target: View) {
            manager.measureChildWithMargins(target, 0, 0)
            val left = paddingLeft
            manager.layoutDecoratedWithMargins(
                target,
                left,
                0,
                left + target.measuredWidth,
                target.measuredHeight,
            )
        }

        private fun topPosition(manager: LayoutManager): Int {
            for (index in 0 until manager.childCount) {
                val child = manager.getChildAt(index) ?: continue
                if (manager.getDecoratedBottom(child) <= 0) continue
                return manager.getPosition(child)
            }
            return -1
        }

        private fun stickyPositions(): IntArray {
            if (positionsIds !== richIds || positionsSticky !== stickyIds) {
                positionsIds = richIds
                positionsSticky = stickyIds
                positions = stickyHeaderPositions(richIds, stickyIds)
            }
            return positions
        }
    }

    fun scrollToLogicalOffset(value: Float) {
        initialPositionApplied = true
        val targetPx = dp(value.coerceAtLeast(0f))
        if (richIds.isEmpty()) {
            scrollBy(
                if (horizontal) targetPx - computeHorizontalScrollOffset() else 0,
                if (horizontal) 0 else targetPx - computeVerticalScrollOffset(),
            )
            return
        }
        val position = virtualScrollPosition(
            richIds.map { richExtents[it] ?: dp(rowHeight) },
            targetPx,
        )
        (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
            position.index,
            -position.offset,
        )
        dispatchViewport()
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
        adaptivePrefetchItems = prefetchItems
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

    fun setOnScrollPhase(listener: ((Int, Float, Float, Float, Float) -> Unit)?) {
        scrollPhase = listener
    }

    /** RN `pagingEnabled` for lists: one item per page, snapped natively. */
    fun setPagingEnabled(enabled: Boolean) {
        if (enabled == (pagerSnap != null)) return
        pagerSnap?.attachToRecyclerView(null)
        pagerSnap = if (enabled) PagerSnapHelper().also { it.attachToRecyclerView(this) } else null
    }

    fun pageIndex(): Int {
        val manager = layoutManager as? LinearLayoutManager ?: return 0
        pagerSnap?.findSnapView(manager)?.let { return manager.getPosition(it) }
        return manager.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
            ?: manager.findFirstVisibleItemPosition().coerceAtLeast(0)
    }

    override fun fling(velocityX: Int, velocityY: Int): Boolean {
        releaseVelocityX = velocityX.toFloat()
        releaseVelocityY = velocityY.toFloat()
        return super.fling(velocityX, velocityY)
    }

    fun setOnViewportChanged(listener: ((Float, Int, Int, Int) -> Unit)?) {
        viewportChanged = listener
    }

    fun trimMemory(critical: Boolean) {
        if (!critical) return
        stopScroll()
        recycledViewPool.clear()
        setItemViewCacheSize(0)
        adaptivePrefetchItems = 0
        lastScrollNanos = 0L
        (layoutManager as? PrefetchLayoutManager)?.apply {
            prefetchCount = 0
            extraLayoutSpace = 0
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && scrollEnabled) {
            // Run before RecyclerView dispatches DOWN to a row child. Waiting
            // for onInterceptTouchEvent is too late when the row itself owns
            // the first phase of the gesture inside a platform ScrollView.
            parent?.requestDisallowInterceptTouchEvent(canConsumeScrollGesture())
        }
        return super.dispatchTouchEvent(event)
    }

    private fun canConsumeScrollGesture(): Boolean {
        val count = adapter?.itemCount ?: 0
        if (count <= 0) return false
        val hasOverflow = if (horizontal) {
            count.toLong() * dp(rowHeight) > width
        } else if (richIds.isNotEmpty()) {
            richIds.sumOf { richExtents[it]?.toLong() ?: dp(rowHeight).toLong() } > height
        } else {
            val rows = (count + columns.coerceAtLeast(1) - 1) /
                columns.coerceAtLeast(1)
            rows.toLong() * dp(rowHeight) > height
        }
        return hasOverflow || if (horizontal) {
            canScrollHorizontally(-1) || canScrollHorizontally(1)
        } else {
            canScrollVertically(-1) || canScrollVertically(1)
        }
    }

    internal fun hasScrollableContent(): Boolean = canConsumeScrollGesture()

    /** An ancestor that can still scroll along this list's axis in [direction]. */
    private fun outerCanScroll(direction: Int): Boolean {
        var current = parent
        while (current is View) {
            val scrolls = if (horizontal) {
                current.canScrollHorizontally(direction)
            } else {
                current.canScrollVertically(direction)
            }
            if (scrolls) return true
            current = current.parent
        }
        return false
    }

    internal fun canScrollInDirection(direction: Int): Boolean {
        val manager = layoutManager as? LinearLayoutManager ?: return false
        val count = adapter?.itemCount ?: 0
        if (count <= 0) return false
        if (direction > 0) {
            val last = manager.findLastVisibleItemPosition()
            val view = manager.findViewByPosition(last)
            val edge = if (horizontal) {
                (view?.left ?: 0) + (view?.width?.takeIf { it > 0 } ?: dp(rowHeight))
            } else {
                (view?.top ?: 0) + (view?.height?.takeIf { it > 0 } ?: dp(rowHeight))
            }
            val viewport = if (horizontal) width - paddingRight else height - paddingBottom
            return last < count - 1 || edge > viewport
        }
        val first = manager.findFirstVisibleItemPosition()
        val view = manager.findViewByPosition(first)
        val edge = if (horizontal) view?.left ?: 0 else view?.top ?: 0
        val viewport = if (horizontal) paddingLeft else paddingTop
        return first > 0 || edge < viewport
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!scrollEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                // PamScrollContainer intentionally uses the platform
                // ScrollView for native parity. It is not a
                // NestedScrollingParent, so an overflowing RecyclerView must
                // retain the gesture until it reaches its own boundary.
                // Otherwise the page steals every vertical drag and rows
                // beyond the list viewport become unreachable.
                parent?.requestDisallowInterceptTouchEvent(
                    canConsumeScrollGesture(),
                )
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = if (horizontal) {
                    event.x - touchDownX
                } else {
                    event.y - touchDownY
                }
                val direction = if (delta < 0f) 1 else -1
                val canScroll = canScrollInDirection(direction)
                // At its boundary the list hands the drag to an outer scroll
                // container that can still move. Without one it keeps the drag
                // (overscroll), like a React Native list: a row Pressable must
                // not turn a swipe past the last page into a tap (the Zé Chat
                // Loops paused their last loop on every swipe up).
                if (abs(delta) > touchSlop && !canScroll && outerCanScroll(direction)) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        repairEmptyRichHolders()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) repairEmptyRichHolders()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) repairEmptyRichHolders()
    }

    override fun draw(canvas: Canvas) {
        val checkpoint = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        super.draw(canvas)
        canvas.restoreToCount(checkpoint)
    }

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
        stickyPins.release()
        layoutManager = if (columns > 1 && !horizontal) {
            PamGridLayoutManager(
                context,
                columns,
                orientation,
                inverted,
                stickyPins,
                ::takePendingInitialPosition,
            )
        } else {
            PamLinearLayoutManager(
                context,
                orientation,
                inverted,
                stickyPins,
                ::takePendingInitialPosition,
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
            prefetchCount = adaptivePrefetchItems
            extraLayoutSpace = extent * adaptivePrefetchItems
        }
        val requestedCache = adaptivePrefetchItems * max(1, columns)
        val cache = if (removeClippedSubviews) {
            requestedCache
        } else {
            max(requestedCache, (adapter?.itemCount ?: 0).coerceAtMost(64))
        }
        setItemViewCacheSize(cache.coerceAtMost(64))
        val recycled = (adaptivePrefetchItems * max(1, columns) * 2).coerceAtMost(96)
        recycledViewPool.setMaxRecycledViews(PackedRowAdapter.TYPE_ITEM, recycled)
        recycledViewPool.setMaxRecycledViews(PackedRowAdapter.TYPE_HEADER, adaptivePrefetchItems)
    }

    private fun updateAdaptivePrefetch(deltaPixels: Int) {
        if (deltaPixels == 0) return
        val now = System.nanoTime()
        val elapsed = now - lastScrollNanos
        lastScrollNanos = now
        if (elapsed <= 0L || elapsed > 250_000_000L) {
            if (adaptivePrefetchItems == 0) {
                adaptivePrefetchItems = prefetchItems
                updatePrefetch()
            }
            return
        }
        val rowsPerSecond = abs(deltaPixels).toDouble() / dp(rowHeight) * 1_000_000_000.0 / elapsed
        val next = (prefetchItems + (rowsPerSecond * 0.15).toInt())
            .coerceIn(prefetchItems, MAX_PREFETCH_ITEMS)
        if (next == adaptivePrefetchItems) return
        adaptivePrefetchItems = next
        updatePrefetch()
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
        if (adapter is RichRecyclerAdapter) {
            grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (richIds.getOrNull(position) in fullSpanIds) grid.spanCount else 1
            }
            return
        }
        val rows = adapter as? PackedRowAdapter ?: return
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (rows.isHeader(position)) grid.spanCount else 1
        }
    }

    private fun canScrollTowardStart(): Boolean =
        if (horizontal) canScrollHorizontally(-1) else canScrollVertically(-1)

    private fun restingAtEnd(): Boolean {
        val count = adapter?.itemCount ?: 0
        val layout = layoutManager as? LinearLayoutManager ?: return false
        return count > 0 && layout.findLastCompletelyVisibleItemPosition() == count - 1
    }

    private fun applyInitialPosition() {
        if (initialPositionApplied) return
        val count = adapter?.itemCount ?: 0
        if (count == 0) return
        val generation = ++initialPositionGeneration
        pendingInitialGeneration = generation
        post {
            // An explicit scroll request issued in the same commit (or a newer
            // initial index) supersedes this pending initial position.
            if (initialPositionApplied || generation != initialPositionGeneration) return@post
            val itemCount = adapter?.itemCount ?: 0
            if (itemCount == 0) return@post
            (layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(initialIndex.coerceAtMost(itemCount - 1), 0)
            initialPositionApplied = true
        }
    }

    /**
     * The first layout after a commit starts at the initial index instead of
     * binding the rows at position 0 and replacing them a frame later (a chat
     * opening at its last message bound two screens of cells). Runs inside
     * the layout pass, after every operation of the commit was applied, so an
     * explicit scroll request of the same commit still wins.
     */
    private fun takePendingInitialPosition(manager: LinearLayoutManager) {
        if (initialPositionApplied || pendingInitialGeneration != initialPositionGeneration) return
        val itemCount = adapter?.itemCount ?: 0
        if (itemCount == 0) return
        manager.scrollToPositionWithOffset(initialIndex.coerceAtMost(itemCount - 1), 0)
        initialPositionApplied = true
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

    private fun repairEmptyRichHolders() {
        post {
            (adapter as? RichRecyclerAdapter)?.remountEmptyHolders()
        }
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
        private val pins: StickyPins,
        private val beforeLayout: (LinearLayoutManager) -> Unit,
    ) : LinearLayoutManager(context, orientation, reverseLayout), PrefetchLayoutManager {
        override fun getChildCount(): Int {
            val raw = super.getChildCount()
            return if (raw > 0 && pins.hidesLast(super.getChildAt(raw - 1))) raw - 1 else raw
        }

        override fun addView(child: View, index: Int) {
            super.addView(child, pins.addIndex(child, index, childCount))
        }

        override fun offsetChildrenVertical(dy: Int) {
            super.offsetChildrenVertical(dy)
            pins.compensateOffset(dy)
        }

        override fun onLayoutChildren(recycler: Recycler, state: State) {
            // RecyclerView defers requestLayout() during its own layout, so
            // this only seeds the anchor the fill below starts from.
            if (!state.isPreLayout) beforeLayout(this)
            super.onLayoutChildren(recycler, state)
            pins.place(this, recycler, state)
        }

        override fun scrollVerticallyBy(dy: Int, recycler: Recycler, state: State): Int {
            val consumed = super.scrollVerticallyBy(dy, recycler, state)
            pins.place(this, recycler, state)
            return consumed
        }

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
        private val pins: StickyPins,
        private val beforeLayout: (LinearLayoutManager) -> Unit,
    ) : GridLayoutManager(
        context,
        spanCount,
        orientation,
        reverseLayout,
    ), PrefetchLayoutManager {
        override fun getChildCount(): Int {
            val raw = super.getChildCount()
            return if (raw > 0 && pins.hidesLast(super.getChildAt(raw - 1))) raw - 1 else raw
        }

        override fun addView(child: View, index: Int) {
            super.addView(child, pins.addIndex(child, index, childCount))
        }

        override fun offsetChildrenVertical(dy: Int) {
            super.offsetChildrenVertical(dy)
            pins.compensateOffset(dy)
        }

        override fun onLayoutChildren(recycler: Recycler, state: State) {
            // RecyclerView defers requestLayout() during its own layout, so
            // this only seeds the anchor the fill below starts from.
            if (!state.isPreLayout) beforeLayout(this)
            super.onLayoutChildren(recycler, state)
            pins.place(this, recycler, state)
        }

        override fun scrollVerticallyBy(dy: Int, recycler: Recycler, state: State): Int {
            val consumed = super.scrollVerticallyBy(dy, recycler, state)
            pins.place(this, recycler, state)
            return consumed
        }

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

internal data class VirtualScrollPosition(val index: Int, val offset: Int)

internal fun virtualScrollPosition(extents: List<Int>, target: Int): VirtualScrollPosition {
    if (extents.isEmpty()) return VirtualScrollPosition(0, 0)
    var remaining = target.coerceAtLeast(0)
    for ((index, extent) in extents.withIndex()) {
        val safeExtent = extent.coerceAtLeast(1)
        if (remaining < safeExtent || index == extents.lastIndex) {
            return VirtualScrollPosition(index, remaining.coerceAtMost(safeExtent - 1))
        }
        remaining -= safeExtent
    }
    return VirtualScrollPosition(extents.lastIndex, 0)
}

/** Adapter positions of the sticky cells, ascending. */
internal fun stickyHeaderPositions(ids: List<Long>, sticky: Set<Long>): IntArray {
    if (sticky.isEmpty()) return IntArray(0)
    var count = 0
    for (id in ids) if (id in sticky) count++
    val result = IntArray(count)
    var next = 0
    for ((position, id) in ids.withIndex()) if (id in sticky) result[next++] = position
    return result
}

/** Index in ascending [positions] of the last one at or before [position], or -1. */
internal fun lastAtOrBefore(positions: IntArray, position: Int): Int {
    var low = 0
    var high = positions.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (positions[middle] <= position) low = middle + 1 else high = middle
    }
    return low - 1
}

private class RichRecyclerAdapter(
    private val context: Context,
    ids: List<Long>,
    extents: Map<Long, Int>,
    private val mount: (Long, FrameLayout) -> Unit,
    private val unmount: (Long, FrameLayout) -> Unit,
    private val deferMounts: () -> Boolean = { false },
) : RecyclerView.Adapter<RichRecyclerAdapter.RichHolder>() {
    /**
     * Rows of a list nobody sees yet (a screen mounted ahead, under the
     * visible one) are laid out with their final extents at once, but their
     * views are created a few per frame within [MOUNT_BUDGET_NANOS], so the
     * first layout of a hidden chat never takes a whole screen of cells in
     * one frame. Showing the list mounts what is left on the next frame.
     */
    private val pendingMounts = LinkedHashMap<RichHolder, Long>()
    private var mountWindowStart = 0L
    private var mountWindowUsed = 0L
    private var drainScheduled = false

    private fun mountWithinBudget(holder: RichHolder, id: Long): Boolean {
        if (!deferMounts()) return mountNow(holder, id)
        // One budget per frame: the animation clock is the frame's vsync time.
        val frame = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
        if (frame != mountWindowStart) {
            mountWindowStart = frame
            mountWindowUsed = 0L
        }
        if (mountWindowUsed >= MOUNT_BUDGET_NANOS) {
            pendingMounts[holder] = id
            scheduleDrain()
            return false
        }
        val started = System.nanoTime()
        mountNow(holder, id)
        mountWindowUsed += System.nanoTime() - started
        return true
    }

    private fun mountNow(holder: RichHolder, id: Long): Boolean {
        pendingMounts.remove(holder)
        mount(id, holder.container)
        return true
    }

    private fun scheduleDrain() {
        if (drainScheduled) return
        drainScheduled = true
        android.view.Choreographer.getInstance().postFrameCallback {
            drainScheduled = false
            mountWindowStart = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
            mountWindowUsed = 0L
            val budget = if (deferMounts()) MOUNT_BUDGET_NANOS else SHOWN_MOUNT_BUDGET_NANOS
            val iterator = pendingMounts.entries.iterator()
            while (iterator.hasNext() && mountWindowUsed < budget) {
                val (holder, id) = iterator.next()
                iterator.remove()
                if (holder.boundId != id || holder.container.childCount > 0) continue
                val started = System.nanoTime()
                mount(id, holder.container)
                mountWindowUsed += System.nanoTime() - started
            }
            if (pendingMounts.isNotEmpty()) scheduleDrain()
        }
    }

    private fun isPending(holder: RichHolder): Boolean = pendingMounts.containsKey(holder)
    private var ids = ids.toList()
    private var extents = extents.toMap()
    private var extent = dp(48f)
    private var horizontal = false
    private val boundHolders: MutableSet<RichHolder> = Collections.newSetFromMap(
        IdentityHashMap(),
    )
    private val emptyRemountAttempts = IdentityHashMap<RichHolder, Long>()

    init {
        setHasStableIds(true)
    }

    fun submit(next: List<Long>, nextExtents: Map<Long, Int>) {
        if (ids == next && extents == nextExtents) {
            remountEmptyHolders()
            return
        }
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

    fun remountEmptyHolders() {
        // A row whose views currently live in the pinned sticky header (or
        // the reverse) is empty on purpose.
        val mounted = boundHolders.filter { it.container.childCount > 0 }.mapTo(HashSet()) { it.boundId }
        boundHolders
            .mapNotNull { holder ->
                val id = holder.boundId
                if (isPending(holder)) return@mapNotNull null
                if (holder.container.childCount == 0 && id in mounted) return@mapNotNull null
                val position = ids.indexOf(id)
                position.takeIf {
                    richHolderNeedsResumeRebind(
                        id,
                        holder.container.childCount,
                        position,
                        emptyRemountAttempts[holder],
                    )
                }?.also {
                    emptyRemountAttempts[holder] = id
                }
            }
            .distinct()
            .forEach { position -> notifyItemChanged(position, PAYLOAD_LAYOUT) }
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
        RichHolder(RichCellContainer(context).apply {
            clipChildren = true
            clipToPadding = true
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
        val id = ids[position]
        if (
            payloads.size == 1 &&
            payloads[0] === PAYLOAD_LAYOUT &&
            !richHolderNeedsFullBind(holder.boundId, id, holder.container.childCount)
        ) {
            applyLayout(holder.container, id)
        } else {
            bind(holder, id)
        }
    }

    override fun onViewRecycled(holder: RichHolder) {
        pendingMounts.remove(holder)
        boundHolders.remove(holder)
        emptyRemountAttempts.remove(holder)
        holder.boundId.takeIf { it != RecyclerView.NO_ID && ownsCell(holder, it) }?.let {
            unmount(it, holder.container)
        }
        holder.boundId = RecyclerView.NO_ID
        holder.container.removeAllViews()
        super.onViewRecycled(holder)
    }

    private fun bind(holder: RichHolder, id: Long) {
        val previous = holder.boundId
        pendingMounts.remove(holder)
        if (previous != RecyclerView.NO_ID && previous != id) {
            emptyRemountAttempts.remove(holder)
            if (ownsCell(holder, previous)) unmount(previous, holder.container)
            holder.container.removeAllViews()
        }
        applyLayout(holder.container, id)
        holder.boundId = id
        mountWithinBudget(holder, id)
        boundHolders += holder
        if (holder.container.childCount > 0) {
            emptyRemountAttempts.remove(holder)
        }
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

    /** Container currently bound to cell [id], if that cell is on screen. */
    fun boundContainer(id: Long): FrameLayout? =
        boundHolders.firstOrNull { it.boundId == id && it.container.childCount > 0 }?.container
            ?: pinned?.takeIf { it.boundId == id }?.container
            ?: boundHolders.firstOrNull { it.boundId == id && !isPending(it) }?.container

    /** Holder pinned as the sticky header, if any. */
    private var pinned: RichHolder? = null

    /**
     * A cell's views exist once. A holder without them must not unmount the
     * cell while another holder bound to it (the pinned header, or the row
     * the header returned to) still shows them.
     */
    private fun ownsCell(holder: RichHolder, id: Long): Boolean =
        holder.container.childCount > 0 ||
            boundHolders.none { it !== holder && it.boundId == id && it.container.childCount > 0 }

    /** Moves the cell's views into the pinned [holder], mounting them when no holder has them. */
    fun adoptPinned(holder: RichHolder) {
        pinned = holder
        val id = holder.boundId
        if (id == RecyclerView.NO_ID || holder.container.childCount > 0) return
        val source = boundHolders.firstOrNull {
            it !== holder && it.boundId == id && it.container.childCount > 0
        }
        if (source != null) {
            moveCellViews(source.container, holder.container)
        } else {
            mount(id, holder.container)
        }
    }

    /**
     * Returns the pinned [holder]'s views to [home] (the cell's own row) or
     * to any other holder bound to the cell, unmounting them when the cell
     * has no other holder, then unbinds [holder] for recycling.
     */
    fun unpin(holder: RichHolder, home: RichHolder?) {
        if (pinned === holder) pinned = null
        val id = holder.boundId
        if (id != RecyclerView.NO_ID && holder.container.childCount > 0) {
            val target = home?.takeIf { it !== holder && it.boundId == id }
                ?: boundHolders.firstOrNull { it !== holder && it.boundId == id }
            if (target != null) {
                applyLayout(target.container, id)
                moveCellViews(holder.container, target.container)
            } else {
                unmount(id, holder.container)
            }
        }
        boundHolders.remove(holder)
        emptyRemountAttempts.remove(holder)
        holder.boundId = RecyclerView.NO_ID
        holder.container.removeAllViews()
    }

    private fun moveCellViews(from: FrameLayout, to: FrameLayout) {
        while (from.childCount > 0) {
            val child = from.getChildAt(0)
            from.removeViewAt(0)
            to.addView(child)
        }
    }

    class RichHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container) {
        var boundId: Long = RecyclerView.NO_ID
    }

    private companion object {
        val PAYLOAD_LAYOUT = Any()
        const val MOUNT_BUDGET_NANOS = 5_000_000L
        const val SHOWN_MOUNT_BUDGET_NANOS = 10_000_000L
    }
}

/**
 * Virtual cell holder. While pinned as a sticky header it owns every touch
 * inside its bounds, so a press never falls through to the row underneath.
 */
internal class RichCellContainer(context: Context) : FrameLayout(context) {
    var pinned = false

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = super.onTouchEvent(event) || pinned
}

internal enum class VirtualCellHostingRepair {
    /** Promote/demote the node alone; its hosted ancestor inside the cell is mounted. */
    IN_PLACE,

    /** Rebuild the cell: its root is the node, the root is flattened, or nothing is mounted. */
    REMOUNT_CELL,
}

/**
 * A property that makes a node inside a virtual-list cell gain or lose its
 * native view (an animation appearing on a flattened View, a `nativeRef`...)
 * re-hosts only that node while the cell is on screen under a hosted root.
 */
internal fun virtualCellHostingRepair(
    isCellRoot: Boolean,
    cellMounted: Boolean,
    cellRootHosted: Boolean,
): VirtualCellHostingRepair =
    if (!isCellRoot && cellMounted && cellRootHosted) {
        VirtualCellHostingRepair.IN_PLACE
    } else {
        VirtualCellHostingRepair.REMOUNT_CELL
    }

internal fun richHolderNeedsFullBind(boundId: Long, requestedId: Long, childCount: Int): Boolean =
    boundId != requestedId || childCount == 0

internal fun richHolderNeedsResumeRebind(
    boundId: Long,
    childCount: Int,
    position: Int,
    lastAttemptedId: Long? = null,
): Boolean = boundId != RecyclerView.NO_ID &&
    childCount == 0 &&
    position >= 0 &&
    lastAttemptedId != boundId

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
        android.R.attr.colorAccent,
        0xFF00875A.toInt(),
    )
    private val headerForeground = context.themeColor(
        android.R.attr.colorAccent,
        0xFF006C49.toInt(),
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
            setPadding(dp(20f), 0, dp(20f), 0)
            includeFontPadding = false
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
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
            setTextColor(if (header) headerForeground else textColor)
            setTypeface(typeface, if (header) Typeface.BOLD else Typeface.NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (header) 13f else 16f)
            letterSpacing = if (header) 0.035f else 0f
            setBackgroundColor(
                if (header) Color.argb(
                    24,
                    Color.red(headerBackground),
                    Color.green(headerBackground),
                    Color.blue(headerBackground),
                ) else Color.TRANSPARENT,
            )
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
