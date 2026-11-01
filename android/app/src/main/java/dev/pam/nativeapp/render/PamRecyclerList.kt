package dev.pam.nativeapp.render

import android.annotation.SuppressLint
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
import androidx.recyclerview.widget.OrientationHelper
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import dev.pam.nativeapp.protocol.PackedSectionList
import dev.pam.nativeapp.protocol.PackedStringList
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Debug-only per-frame motion trace of virtual lists (see PamRecyclerList.traceMotion). */
private const val MOTION_TRACE_TAG = "PamListTrace"

internal class PamRecyclerList(context: Context) : RecyclerView(context) {
    /**
     * `keyboardDismissMode` "on-drag" / "interactive": a user drag that
     * scrolls the list closes the keyboard, as on a scroll view. Off unless
     * the app sets it.
     */
    var dismissKeyboardOnDrag = false

    private fun dismissKeyboard() {
        val focused = rootView.findFocus() ?: return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    private var rowHeight = 48f
    private var keyboardTaps: KeyboardTapTracker? = null
    private var horizontal = false
    private var columns = 1
    private var inverted = false
    private var followEndOnResize = true
    /** `stackFromEnd` (extension property 1011): rows that fit rest at the end. */
    private var stackRowsFromEnd = false
    /** The last layout found every row fitting and laid them against the end. */
    private var restingStacked = false
    // Set only by a real scroll (a drag, fling or scroll request) that left
    // the list at its end, never by a resize or a re-layout: a list whose
    // viewport grows under its last row (a sheet animating its height) is
    // not "at its end" and must keep its offset.
    private var scrolledToEnd = false
    // A real scroll moved the list since its initial position: from then on
    // only [scrolledToEnd] decides whether it follows its end. A list opened
    // at its end (initialScrollIndex) that the user scrolled away from must
    // never be pulled back by a resize, a re-measured row or a page landing.
    private var scrollMoved = false
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

    /** A finger drag, or the fling it released, is moving the list. */
    private var userScrolling = false

    /** When the user's current drag began (uptime ms). */
    private var userScrollStartedAt = 0L
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
                when (newState) {
                    SCROLL_STATE_DRAGGING -> if (!userScrolling) {
                        userScrolling = true
                        userScrollStartedAt = android.os.SystemClock.uptimeMillis()
                        if (dismissKeyboardOnDrag) dismissKeyboard()
                    }
                    SCROLL_STATE_IDLE -> {
                        userScrolling = false
                        PamVisibleAutoplay.listSettled()
                    }
                }
                val listener = scrollPhase ?: return
                val density = resources.displayMetrics.density.coerceAtLeast(0.01f)
                val x = (if (horizontal) logicalScrollOffsetPx() else computeHorizontalScrollOffset()) / density
                val y = (if (horizontal) computeVerticalScrollOffset() else logicalScrollOffsetPx()) / density
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
                scrolledTotal += if (horizontal) dx else dy
                if ((if (horizontal) dx else dy) != 0) {
                    scrollMoved = true
                    scrolledToEnd = restingAtEnd() && canScrollTowardStart()
                }
                updateAdaptivePrefetch(if (horizontal) dx else dy)
                (adapter as? RichRecyclerAdapter)?.mountShown()
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
        try {
            super.onLayout(changed, left, top, right, bottom)
            if (endStateAfterLayout) {
                endStateAfterLayout = false
                scrollMoved = true
                scrolledToEnd = restingAtEnd() && canScrollTowardStart()
            }
            updateAccessibilityVisibility()
            scheduleRampAhead()
        } finally {
            android.os.Trace.endSection()
        }
    }

    private var rampAheadPosted = false

    /** Rows ahead kept at rest: the configured prefetch, not a fast fling's adaptive extent. */
    private fun restAheadSpace(): Int = dp(rowHeight) * prefetchItems
    private val rampAhead = Runnable {
        rampAheadPosted = false
        val manager = layoutManager as? PrefetchLayoutManager
        if (manager != null && scrollState == SCROLL_STATE_IDLE && isAttachedToWindow &&
            windowVisibility == VISIBLE && !manager.extraLayoutRamped(restAheadSpace())
        ) {
            requestLayout()
        }
    }

    /**
     * A list at rest grows its laid-out off-screen space (the rows ahead) one
     * row per frame, as a scroll does, so the rows next to the viewport are
     * bound (in slices) before the next drag instead of inside its first
     * frames: a row bound in the drag's first frame made it late, and a late
     * frame while the list moves leaves the window buffer-stuffed until it
     * stops.
     */
    private fun scheduleRampAhead() {
        if (rampAheadPosted || scrollState != SCROLL_STATE_IDLE) return
        val manager = layoutManager as? PrefetchLayoutManager ?: return
        if (manager.extraLayoutRamped(restAheadSpace())) return
        rampAheadPosted = true
        postOnAnimation(rampAhead)
    }

    /**
     * A viewport that shrinks while resting at its end (a banner or composer
     * growing around a chat timeline) keeps that end visible, like a
     * bottom-anchored React Native list, instead of hiding the newest rows.
     * Only a list opened at its end (`initialScrollIndex`) or scrolled there
     * follows it; an inverted list keeps its newest rows natively. A sheet
     * or modal growing around a list at offset 0 leaves it at offset 0, and
     * `followEndOnResize=false` turns the behaviour off.
     */
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        val oldExtent = if (horizontal) oldWidth else oldHeight
        val newExtent = if (horizontal) width else height
        // A list resting at its start (an inbox whose first rows all fit)
        // is not "at its end": only one opened at its end or scrolled away
        // from its start follows the end.
        val wasAtEnd = followsEnd() && oldExtent > 0 && newExtent != oldExtent && restingAtEnd()
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        // Pinned in this same layout pass: a viewport that follows the IME
        // frame by frame keeps its newest row on the edge every frame
        // instead of catching up one frame late (a visible stutter).
        if (wasAtEnd && !inverted && !pinEndInThisLayout(newExtent)) post(::keepEndAfterResize)
    }

    /** Lays the last row flush with the new viewport end in the coming layout pass. */
    private fun pinEndInThisLayout(viewportExtent: Int): Boolean {
        val layout = layoutManager as? LinearLayoutManager ?: return false
        if (layout.reverseLayout || layout.stackFromEnd) return false
        val last = (adapter?.itemCount ?: 0) - 1
        if (last < 0) return false
        val row = layout.findViewByPosition(last) ?: return false
        val rowExtent = if (horizontal) layout.getDecoratedMeasuredWidth(row) else layout.getDecoratedMeasuredHeight(row)
        val padding = if (horizontal) paddingLeft + paddingRight else paddingTop + paddingBottom
        layout.scrollToPositionWithOffset(last, pinnedEndOffset(viewportExtent, padding, rowExtent))
        return true
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

    /**
     * Defers the view repair of cell [id] to the per-frame mount budget when
     * it is bound off screen; false when the caller must repair it now.
     */
    fun deferCellRepair(id: Long, repair: () -> Unit): Boolean =
        deferCellRepairSliced(id) { _ ->
            repair()
            true
        }

    /**
     * [deferCellRepair] whose [repair] builds the cell until a deadline
     * (System.nanoTime) and returns whether it is complete; an incomplete
     * repair resumes on a later frame (or at once when the row is shown).
     */
    fun deferCellRepairSliced(id: Long, repair: (Long) -> Boolean): Boolean =
        (adapter as? RichRecyclerAdapter)?.deferRepair(id, repair) == true

    /** Holder container bound to virtual cell [id], or null when it is off screen. */
    fun boundContainer(id: Long): FrameLayout? =
        (adapter as? RichRecyclerAdapter)?.boundContainer(id)

    /** True while a holder is bound to cell [id] (mounted or mounting). */
    fun isBound(id: Long): Boolean = (adapter as? RichRecyclerAdapter)?.isBound(id) == true

    /** Scroll anchor of each keyed section, saved when it was switched away. */
    private val sectionAnchors = HashMap<String, VirtualScrollAnchor>()

    /** Rebinds visible rows whose content was emptied, without a diff. */
    fun remountEmptyRows() {
        (adapter as? RichRecyclerAdapter)?.remountEmptyHolders()
    }

    fun setRichItems(
        ids: List<Long>,
        extents: Map<Long, Float>,
        mount: (Long, FrameLayout) -> Unit,
        unmount: (Long, FrameLayout) -> Unit,
        sectionSwitch: ListSectionSwitch? = null,
        mountSlice: ((Long, FrameLayout, Long) -> Boolean)? = null,
    ) {
        val sectionRestore = sectionSwitch?.let(::leaveSection)
        val pixelExtents = extents.mapValues { (_, value) -> dp(value.coerceAtLeast(0f)) }
        // Content-sized cells are re-measured after mounting (fonts, images,
        // async text). When only extents change for the same rows, a list that
        // was resting at its end stays there instead of drifting by the delta.
        val keepEnd = initialPositionApplied &&
            ids.isNotEmpty() &&
            ids == richIds &&
            pixelExtents != richExtents &&
            followsEnd() &&
            restingAtEnd()
        // A page appended under (or history prepended above) the prefetched
        // space would otherwise be laid out and bound all at once (a dozen
        // rows in one frame). Only that side restarts: the rows already laid
        // out on the other side stay bound instead of being recycled and
        // bound again a few frames later.
        val appended = rowsAddedAfter(richIds, ids)
        val prepended = rowsAddedBefore(richIds, ids)
        if (appended || prepended) {
            (layoutManager as? PrefetchLayoutManager)?.restartExtraLayoutRamp(start = prepended, end = appended)
        }
        val headAnchor = headInsertAnchorCapture(richIds, ids)
        val idAnchor = if (headAnchor == null && ids != richIds) firstVisibleKeptAnchor(ids) else null
        // Rows re-measured at rest (media, a preview or reactions arriving):
        // RecyclerView keeps the partly hidden top row's top, so a height
        // change there pushed every visible row. The first fully visible row
        // keeps its pixel offset instead; a list resting at its end keeps
        // following the end.
        val resizeAnchor = if (headAnchor == null && !keepEnd && ids == richIds && pixelExtents != richExtents) {
            firstFullyVisibleAnchor()
        } else {
            null
        }
        richIds = ids
        richExtents = pixelExtents
        richSpans = rowSpan?.let { span ->
            HashMap<Long, IntArray>(ids.size * 2).also { spans ->
                for (id in ids) span(id)?.let { spans[id] = it }
            }
        } ?: emptyMap()
        val current = adapter as? RichRecyclerAdapter
        if (keepEnd) post(::keepEndAfterResize)
        if (current == null) {
            adapter = RichRecyclerAdapter(
                context,
                ids,
                pixelExtents,
                mount,
                unmount,
                deferMounts = { !isShown },
                mountSlice = mountSlice,
            ).also(::installOffscreenMounts)
        } else {
            current.submit(ids, pixelExtents)
            // A full rebind keeps adapter positions, so rows joining above
            // (an older page) moved the content under the reader: the first
            // visible row that is still there keeps its pixel offset.
            if (current.reboundAll) {
                idAnchor?.let(::restoreHeadInsertAnchor)
            } else if (scrollState != SCROLL_STATE_IDLE) {
                // Keyed inserts keep the rows on screen through RecyclerView's
                // own anchor; while the list moves, that is checked before
                // the next draw (a row joining mid-fling must not jump it).
                idAnchor?.let(::verifyAnchorBeforeDraw)
            }
        }
        configureAdapter()
        updateHeaderSpans()
        applyInitialPosition()
        headAnchor?.let(::restoreHeadInsertAnchor)
        resizeAnchor?.let(::restoreHeadInsertAnchor)
        if (sectionSwitch != null && sectionRestore != null) enterSection(sectionSwitch, sectionRestore)
    }

    /**
     * Rows prepended right below a leading row on screen (a list header, a
     * "loading older" row) would push the content down: RecyclerView keeps
     * its first visible child, the header, in place. The first row after the
     * inserted run keeps its pixel offset instead, like any prepend above the
     * viewport. Returns (row id, top px) or null when RecyclerView's own
     * anchoring already does it.
     */
    private fun headInsertAnchorCapture(previous: List<Long>, next: List<Long>): Pair<Long, Int>? {
        if (!initialPositionApplied || inverted || !isLaidOut || previous.isEmpty()) return null
        val manager = layoutManager as? LinearLayoutManager ?: return null
        val first = manager.findFirstVisibleItemPosition()
        val id = headInsertAnchor(previous, next, first) ?: return null
        val view = manager.findViewByPosition(previous.indexOf(id)) ?: return null
        return id to (if (horizontal) view.left - paddingLeft else view.top - paddingTop)
    }

    /** (row id, top px) of the first visible row that [next] keeps. */
    private fun firstVisibleKeptAnchor(next: List<Long>): Pair<Long, Int>? {
        if (!initialPositionApplied || inverted || !isLaidOut || richIds.isEmpty()) return null
        val manager = layoutManager as? LinearLayoutManager ?: return null
        val first = manager.findFirstVisibleItemPosition()
        val last = manager.findLastVisibleItemPosition()
        if (first == NO_POSITION) return null
        val kept = next.toHashSet()
        for (position in first..last) {
            val id = richIds.getOrNull(position) ?: continue
            if (id !in kept) continue
            val view = manager.findViewByPosition(position) ?: continue
            return id to (if (horizontal) view.left - paddingLeft else view.top - paddingTop)
        }
        return null
    }

    /** (row id, top px) of the first row fully on screen, when the list is laid out and idle. */
    private fun firstFullyVisibleAnchor(): Pair<Long, Int>? {
        if (!initialPositionApplied || inverted || !isLaidOut || scrollState != SCROLL_STATE_IDLE) return null
        val manager = layoutManager as? LinearLayoutManager ?: return null
        val position = manager.findFirstCompletelyVisibleItemPosition()
        if (position == NO_POSITION || position >= richIds.size) return null
        val view = manager.findViewByPosition(position) ?: return null
        return richIds[position] to (if (horizontal) view.left - paddingLeft else view.top - paddingTop)
    }

    private fun restoreHeadInsertAnchor(anchor: Pair<Long, Int>) {
        val index = richIds.indexOf(anchor.first)
        if (index < 0) return
        // Does not stop a running fling: it continues from the anchored layout.
        (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, anchor.second)
        if (scrollState != SCROLL_STATE_IDLE) verifyAnchorBeforeDraw(anchor)
    }

    /** Px the list scrolled since it was created (scroll steps only, not layouts). */
    private var scrolledTotal = 0L

    /** (row id, top px, [scrolledTotal]) an anchored commit must show before the next draw. */
    private var anchorCheck: Triple<Long, Int, Long>? = null

    private val anchorCheckListener = android.view.ViewTreeObserver.OnPreDrawListener {
        verifyAnchor()
        true
    }

    /**
     * A moving list re-anchored by a commit (an older page landing mid-fling)
     * applied the row's top captured at the commit; the fling may step before
     * that layout lands, so the row could show one step back or, with a full
     * rebind, re-measured rows, several. Before the next draw the row is put
     * where the anchor plus the fling's travel since the commit says.
     */
    private fun verifyAnchorBeforeDraw(anchor: Pair<Long, Int>) {
        if (anchorCheck == null) viewTreeObserver.addOnPreDrawListener(anchorCheckListener)
        anchorCheck = Triple(anchor.first, anchor.second, scrolledTotal)
    }

    private fun verifyAnchor() {
        val check = anchorCheck ?: return
        if (isLayoutRequested) return
        anchorCheck = null
        viewTreeObserver.removeOnPreDrawListener(anchorCheckListener)
        val manager = layoutManager as? LinearLayoutManager ?: return
        val view = manager.findViewByPosition(richIds.indexOf(check.first).takeIf { it >= 0 } ?: return) ?: return
        val expected = check.second - (scrolledTotal - check.third).toInt()
        val actual = if (horizontal) view.left - paddingLeft else view.top - paddingTop
        val delta = actual - expected
        if (motionTrace != null && delta != 0) {
            android.util.Log.d(MOTION_TRACE_TAG, "anchor id=${check.first} corrected=$delta state=$scrollState")
        }
        if (delta == 0 || abs(delta) >= (if (horizontal) width else height)) return
        if (horizontal) scrollBy(delta, 0) else scrollBy(0, delta)
    }

    /**
     * Saves the outgoing section's scroll anchor. Returns whether the rail
     * (the last row shared by every section above them) was at or above the
     * top of the viewport: only then does each section keep its own offset,
     * like React Native tabs over one list; with the header still visible
     * every section starts right below it at the current offset.
     */
    private fun leaveSection(change: ListSectionSwitch): Boolean? {
        if (inverted || !initialPositionApplied) return null
        val manager = layoutManager as? LinearLayoutManager ?: return null
        val first = manager.findFirstVisibleItemPosition()
        if (first == NO_POSITION || first >= richIds.size) return null
        val firstView = manager.findViewByPosition(first) ?: return null
        val railIndex = change.railId?.let(richIds::indexOf) ?: -1
        val pinned = railIndex >= 0 && first >= railIndex
        if (pinned) {
            val offset = if (horizontal) firstView.left - paddingLeft else firstView.top - paddingTop
            sectionAnchors[change.from] = VirtualScrollAnchor(richIds[first], offset)
        } else {
            sectionAnchors.remove(change.from)
        }
        return pinned
    }

    private fun enterSection(change: ListSectionSwitch, pinned: Boolean) {
        if (!pinned) return
        val manager = layoutManager as? LinearLayoutManager ?: return
        val target = sectionScrollTarget(richIds, sectionAnchors[change.to], change.railId) ?: return
        stopScroll()
        manager.scrollToPositionWithOffset(target.index, target.offset)
        dispatchViewport()
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
        animated: Boolean = false,
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
        // Anchored on the target cell itself: the rows above may be windowed
        // placeholders whose extents are estimates, so summing them landed
        // the target (a conversation's end) screens away. The layout manager
        // closes any gap at either end of the list.
        stopScroll()
        scrollToVirtualPosition(index, adjustment - innerStartPx, animated)
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

    /**
     * Whether a programmatic scroll may move the list now. While the user's
     * own drag or fling moves it, only a request the user asked for (a press
     * or submit elsewhere after the gesture began, [userActionAt] in uptime
     * ms: a send, a jump button) takes over. Any other request (an anchor, a
     * follow-the-end, a window shift) is dropped: run then it cut the fling
     * short, and run after it settled it snapped the resting list.
     */
    fun acceptsProgrammaticScroll(userActionAt: Long): Boolean {
        if (!userScrolling || scrollState == SCROLL_STATE_IDLE) {
            userScrolling = false
            return true
        }
        return userActionAt >= userScrollStartedAt
    }

    /**
     * Scrolls to a logical (dp) offset. A fling in progress is stopped
     * first: `scrollToPositionWithOffset` does not stop it, and the fling
     * kept moving after the jump (a scroll to the top stopped ~40 px short).
     * [animated] glides there and then snaps exactly onto the target.
     */
    fun scrollToLogicalOffset(value: Float, animated: Boolean = false) {
        initialPositionApplied = true
        stopScroll()
        // Not dp(): it never returns less than 1 px, so the top landed 1 px short.
        val targetPx = (value.coerceAtLeast(0f) * resources.displayMetrics.density + 0.5f).toInt()
        if (richIds.isEmpty()) {
            val dx = if (horizontal) targetPx - computeHorizontalScrollOffset() else 0
            val dy = if (horizontal) 0 else targetPx - computeVerticalScrollOffset()
            if (animated) {
                val current = if (horizontal) computeHorizontalScrollOffset() else computeVerticalScrollOffset()
                val viewport = if (horizontal) width else height
                val start = longGlideStart(current, targetPx, viewport)
                if (start != current) {
                    if (horizontal) scrollBy(start - current, 0) else scrollBy(0, start - current)
                }
                smoothScrollBy(if (horizontal) targetPx - start else 0, if (horizontal) 0 else targetPx - start)
            } else {
                scrollBy(dx, dy)
            }
            return
        }
        val extents = richIds.map { richExtents[it] ?: dp(rowHeight) }
        if (animated && isLaidOut) {
            val current = if (horizontal) computeHorizontalScrollOffset() else computeVerticalScrollOffset()
            val start = longGlideStart(current, targetPx, if (horizontal) width else height)
            if (start != current) {
                // Jump to one screen from the target; glide only that screen.
                val near = virtualScrollPosition(extents, start)
                (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(near.index, -near.offset)
                dispatchViewport()
            }
        }
        val position = virtualScrollPosition(extents, targetPx)
        scrollToVirtualPosition(position.index, -position.offset, animated)
    }

    private var pendingExactScroll: RecyclerView.OnScrollListener? = null

    private fun scrollToVirtualPosition(index: Int, offset: Int, animated: Boolean) {
        val manager = layoutManager as? LinearLayoutManager ?: return
        pendingExactScroll?.let(::removeOnScrollListener)
        pendingExactScroll = null
        if (!animated || !isLaidOut) {
            manager.scrollToPositionWithOffset(index, offset)
            dispatchViewport()
            refreshEndStateAfterJump()
            return
        }
        val scroller = object : androidx.recyclerview.widget.LinearSmoothScroller(context) {
            override fun calculateDtToFit(
                viewStart: Int,
                viewEnd: Int,
                boxStart: Int,
                boxEnd: Int,
                snapPreference: Int,
            ): Int = boxStart + offset - viewStart
        }
        scroller.targetPosition = index
        // The glide lands on estimated extents; snap exactly once it settles,
        // unless a finger took over meanwhile.
        val settle = object : RecyclerView.OnScrollListener() {
            private var interrupted = false

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_DRAGGING) interrupted = true
                if (newState != SCROLL_STATE_IDLE) return
                removeOnScrollListener(this)
                if (pendingExactScroll === this) pendingExactScroll = null
                if (!interrupted) {
                    manager.scrollToPositionWithOffset(index, offset)
                    dispatchViewport()
                    refreshEndStateAfterJump()
                }
            }
        }
        pendingExactScroll = settle
        addOnScrollListener(settle)
        manager.startSmoothScroll(scroller)
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

    fun setFollowEndOnResize(value: Boolean) {
        followEndOnResize = value
    }

    fun setStackFromEnd(value: Boolean) {
        if (stackRowsFromEnd == value) return
        stackRowsFromEnd = value
        restingStacked = false
        requestLayout()
    }

    /**
     * `stackFromEnd`, in the same layout pass that placed the rows: when every
     * row fits, they are offset to rest against the viewport end (an inverted
     * React Native list); scrolling is unchanged because nothing overflows.
     * A list that rested anchored and now overflows (a new row) keeps its
     * last row on the end instead of growing past it. A finger on the list
     * is never overridden.
     */
    private fun restRowsAtEnd(manager: LinearLayoutManager, recycler: Recycler, state: State) {
        if (!stackRowsFromEnd || horizontal || inverted || state.isPreLayout ||
            manager.reverseLayout || manager.stackFromEnd
        ) {
            restingStacked = false
            return
        }
        val count = state.itemCount
        val helper = OrientationHelper.createVerticalHelper(manager)
        val last = if (count > 0) manager.findViewByPosition(count - 1) else null
        val first = if (count > 0) manager.findViewByPosition(0) else null
        if (first != null && last != null) {
            val top = helper.getDecoratedStart(first)
            val bottom = helper.getDecoratedEnd(last)
            if (top >= helper.startAfterPadding - 1 && bottom - top <= helper.totalSpace) {
                val gap = helper.endAfterPadding - bottom
                if (gap != 0) manager.offsetChildrenVertical(gap)
                restingStacked = true
                return
            }
        }
        if (restingStacked && last != null && !isUserScrolling()) {
            val overflow = helper.getDecoratedEnd(last) - helper.endAfterPadding
            if (overflow > 0) manager.scrollVerticallyBy(overflow, recycler, state)
        }
        restingStacked = false
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
        scrollMoved = false
        scrolledToEnd = false
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
    /** One item per page (a pager, e.g. reels): its next pages are prepared ahead on purpose. */
    internal val pagingEnabled: Boolean get() = pagerSnap != null

    fun setPagingEnabled(enabled: Boolean) {
        if (enabled == (pagerSnap != null)) return
        pagerSnap?.attachToRecyclerView(null)
        pagerSnap = if (enabled) PagerSnapHelper().also { it.attachToRecyclerView(this) } else null
    }

    /**
     * Content size in px (RN `contentSize`). Rich rows have known extents, so
     * the list's axis is their exact sum (RecyclerView's own range is an
     * average-row estimate); the cross axis is the viewport.
     */
    fun contentSizePx(): IntArray {
        val exact = if (richIds.isNotEmpty() && layoutManager !is GridLayoutManager) {
            var sum = 0
            for (id in richIds) sum += richExtents[id] ?: dp(rowHeight)
            sum + if (horizontal) paddingLeft + paddingRight else paddingTop + paddingBottom
        } else {
            null
        }
        return if (horizontal) {
            intArrayOf(exact ?: computeHorizontalScrollRange(), height)
        } else {
            intArrayOf(width, exact ?: computeVerticalScrollRange())
        }
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
        val flinging = super.fling(velocityX, velocityY)
        // A fling toward the end (adapter order) projects where it rests.
        val velocity = (if (horizontal) velocityX else velocityY).toFloat() * (if (inverted) -1f else 1f)
        flingStartPx = axisOffsetPx()
        flingProjectedPx = if (flingStarted(flinging) && velocity > 0f) {
            splineFlingDistancePx(velocity, resources.displayMetrics.density)
        } else {
            0f
        }
        flingForward = velocity > 0f
        flingStartScrolled = scrolledTotal
        flingPathPx = if (flingStarted(flinging) && velocity != 0f) {
            splineFlingDistancePx(abs(velocity), resources.displayMetrics.density)
        } else {
            0f
        }
        return flinging
    }

    /** Projected travel (px) of the running fling in either direction. */
    private var flingPathPx = 0f

    /** [scrolledTotal] when the fling was released (its exact travel since). */
    private var flingStartScrolled = 0L
    private var flingForward = true

    /**
     * Rows a running fling sweeps and lands on, from the rows on screen to
     * the screen it projects to rest at (the platform spline): row indexes
     * [first, last] and adapter positions [first, last], or null at rest or
     * when the list has no row spans. One window request can then cover the
     * whole path instead of chasing the fling screen by screen.
     */
    fun flingPathRows(): IntArray? {
        if (flingPathPx <= 0f || scrollState != SCROLL_STATE_SETTLING || rowSpan == null || horizontal || inverted) return null
        val manager = layoutManager as? LinearLayoutManager ?: return null
        val remaining = flingPathPx.toInt() - abs(scrolledTotal - flingStartScrolled).toInt()
        if (remaining <= 0) return null
        val visible = visibleRows() ?: return null
        val edgePosition = if (flingForward) manager.findLastVisibleItemPosition() else manager.findFirstVisibleItemPosition()
        val edgeView = manager.findViewByPosition(edgePosition) ?: return null
        val firstPosition = manager.findFirstVisibleItemPosition()
        val lastPosition = manager.findLastVisibleItemPosition()
        val step = if (flingForward) 1 else -1
        var position = edgePosition
        // Px still hidden past the viewport edge inside the edge item.
        val hidden = if (flingForward) edgeView.bottom - (height - paddingBottom) else paddingTop - edgeView.top
        var left = remaining - hidden.coerceAtLeast(0)
        while (left > 0 && position + step in richIds.indices) {
            position += step
            left -= richExtents[richIds[position]] ?: dp(rowHeight)
        }
        val id = richIds.getOrNull(position) ?: return null
        val span = richSpans[id] ?: return null
        val rows = span[1].coerceAtLeast(1)
        val extent = (richExtents[id] ?: dp(rowHeight)).coerceAtLeast(1)
        // Where the leading viewport edge rests inside that item (px from
        // the item's edge it entered by).
        val into = (extent + left.coerceAtMost(0)).coerceIn(0, extent)
        val rowInto = (into.toLong() * rows / extent).toInt().coerceIn(0, rows - 1)
        val landing = if (flingForward) span[0] + rowInto else span[0] + rows - 1 - rowInto
        // The landing row is the leading edge of the screen it rests on.
        return if (flingForward) {
            intArrayOf(visible[0], max(visible[1], landing), min(firstPosition, position), max(lastPosition, position))
        } else {
            intArrayOf(min(visible[0], landing), visible[1], min(firstPosition, position), max(lastPosition, position))
        }
    }

    private fun flingStarted(started: Boolean) = started && scrollState == SCROLL_STATE_SETTLING

    private var flingStartPx = 0
    private var flingProjectedPx = 0f

    private fun axisOffsetPx(): Int = if (horizontal) computeHorizontalScrollOffset() else computeVerticalScrollOffset()

    /** Px a running fling toward the list's end still projects to travel (0 at rest). */
    fun flingRemainingPx(): Float {
        if (flingProjectedPx <= 0f || scrollState != SCROLL_STATE_SETTLING) return 0f
        val travelled = abs(axisOffsetPx() - flingStartPx).toFloat()
        return (flingProjectedPx - travelled).coerceAtLeast(0f)
    }

    /** Average px of the rows on screen along the axis (0 when none). */
    fun visibleRowPx(visibleRows: Int): Float =
        if (visibleRows <= 0) 0f else (if (horizontal) width else height).toFloat() / visibleRows

    /**
     * Rows bound off screen while the list moves (a fling laying out ahead, a
     * window slide rebinding rows) mount a few per frame; rows on screen
     * always mount before they draw.
     */
    private fun installOffscreenMounts(adapter: RichRecyclerAdapter) {
        adapter.shown = { position ->
            val manager = layoutManager as? LinearLayoutManager
            val first = manager?.findFirstVisibleItemPosition() ?: NO_POSITION
            val last = manager?.findLastVisibleItemPosition() ?: NO_POSITION
            // The row at each edge counts as shown: it is the next to enter.
            first == NO_POSITION || position in (first - 1)..(last + 1)
        }
        adapter.moving = { scrollState != SCROLL_STATE_IDLE }
        adapter.frameIntervalMs = {
            val rate = display?.refreshRate ?: 60f
            if (rate >= 20f) (1000f / rate).toLong().coerceAtLeast(4L) else 16L
        }
        // At rest too: the rows ahead grown by scheduleRampAhead.
        adapter.offscreenWhileMoving = { position -> !adapter.shown(position) }
    }

    fun setOnViewportChanged(listener: ((Float, Int, Int, Int) -> Unit)?) {
        viewportChanged = listener
    }

    /** App hidden: drop the offscreen rows waiting for reuse; budgets stay. */
    fun releaseRecycledViews() {
        recycledViewPool.clear()
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
        if (keyboardTaps?.observe(event) == true) {
            // keyboardShouldPersistTaps="never": the tap only dismissed the
            // keyboard; rows see the gesture cancelled, not clicked.
            val action = event.action
            event.action = MotionEvent.ACTION_CANCEL
            try {
                super.dispatchTouchEvent(event)
            } finally {
                event.action = action
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    /** RN `keyboardShouldPersistTaps`; no tracker for absent or `always`. */
    fun setKeyboardPersistTaps(value: Int) {
        val mode = KeyboardPersistTaps.normalize(value)
        if (keyboardTaps?.mode == mode) return
        keyboardTaps = if (KeyboardPersistTaps.tracks(mode)) KeyboardTapTracker(this, mode) else null
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
                // not turn a swipe past the last page into a tap (a vertical
                // video feed paused its last item on every swipe up).
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
        motionTrace = if (dev.pam.nativeapp.BuildConfig.DEBUG &&
            android.util.Log.isLoggable(MOTION_TRACE_TAG, android.util.Log.DEBUG)
        ) {
            MotionTrace()
        } else {
            null
        }
        repairEmptyRichHolders()
    }

    /**
     * Debug builds with `adb shell setprop log.tag.PamListTrace DEBUG`: each
     * drawn frame logs how far the rows on screen moved (median over rows
     * present in both frames, by stable id), so a jump, a snap or a content
     * swap shows exactly, without a screen recording. Off otherwise.
     */
    private var motionTrace: MotionTrace? = null

    /**
     * Rows on screen at the last traced frame, in reused arrays (no per-frame
     * allocation), and the pending log lines: they go out as one multi-line
     * log record every ~250 ms or when the list stops (one logger write per
     * frame was most of the trace's own UI-thread cost).
     */
    private class MotionTrace {
        var ids = LongArray(32)
        var tops = IntArray(32)
        var size = 0
        var nextIds = LongArray(32)
        var nextTops = IntArray(32)
        var deltas = IntArray(32)
        var count = -1
        val pending = StringBuilder()
        var pendingSince = 0L

        fun ensure(capacity: Int) {
            if (nextIds.size >= capacity) return
            val grown = maxOf(capacity, nextIds.size * 2)
            nextIds = LongArray(grown)
            nextTops = IntArray(grown)
            deltas = IntArray(grown)
            ids = ids.copyOf(grown)
            tops = tops.copyOf(grown)
        }

        fun topOf(id: Long): Int {
            for (index in 0 until size) if (ids[index] == id) return tops[index]
            return Int.MIN_VALUE
        }

        fun swap(newSize: Int) {
            val swapIds = ids
            ids = nextIds
            nextIds = swapIds
            val swapTops = tops
            tops = nextTops
            nextTops = swapTops
            size = newSize
        }

        fun flush() {
            if (pending.isEmpty()) return
            android.util.Log.d(MOTION_TRACE_TAG, pending.toString())
            pending.setLength(0)
        }
    }

    private fun traceMotion() {
        val trace = motionTrace ?: return
        trace.ensure(childCount)
        var shown = 0
        var common = 0
        var above = Int.MAX_VALUE
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.bottom <= 0 || child.top >= height) continue
            val position = getChildAdapterPosition(child)
            if (position != NO_POSITION && position < above) above = position
            val id = getChildItemId(child)
            if (id == NO_ID) continue
            val top = if (horizontal) child.left else child.top
            trace.nextIds[shown] = id
            trace.nextTops[shown] = top
            shown++
            val before = trace.topOf(id)
            if (before != Int.MIN_VALUE) trace.deltas[common++] = top - before
        }
        if (shown == 0) return
        java.util.Arrays.sort(trace.deltas, 0, common)
        val moved: Int? = if (common == 0) null else trace.deltas[common / 2]
        val count = adapter?.itemCount ?: 0
        val sameRows = common == shown && shown == trace.size
        val now = android.os.SystemClock.uptimeMillis()
        if (moved != 0 || !sameRows || count != trace.count) {
            if (trace.pending.isEmpty()) trace.pendingSince = now
            else trace.pending.append('\n')
            trace.pending.append("t=").append(now).append(" dy=").append(moved ?: "swap")
                .append(" common=").append(common).append(" shown=").append(shown)
                .append(" state=").append(scrollState)
                .append(" above=").append(if (above == Int.MAX_VALUE) -1 else above)
                .append(" count=").append(count)
        }
        trace.count = count
        trace.swap(shown)
        if (trace.pending.isNotEmpty() &&
            (scrollState == SCROLL_STATE_IDLE || now - trace.pendingSince >= 250L || trace.pending.length > 3000)
        ) {
            trace.flush()
        }
    }

    override fun onScrollStateChanged(state: Int) {
        super.onScrollStateChanged(state)
        if (state == SCROLL_STATE_IDLE) {
            motionTrace?.flush()
            scheduleRampAhead()
        }
    }

    override fun onDetachedFromWindow() {
        motionTrace?.flush()
        super.onDetachedFromWindow()
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
        if (motionTrace != null) traceMotion()
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
                ::restRowsAtEnd,
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
            extraLayoutStep = extent
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

    private fun followsEnd(): Boolean =
        followEndOnResize && !inverted && initialPositionApplied &&
            !isUserScrolling() &&
            (if (scrollMoved) scrolledToEnd else initialIndex > 0)

    /** A finger on the list or its fling: never pulled anywhere meanwhile. */
    private fun isUserScrolling(): Boolean = scrollState != SCROLL_STATE_IDLE

    /**
     * Runs a frame after a resize or re-measure that found the list resting
     * at its end. A drag or fling that started in between, or a scroll that
     * already left the end, wins: the list is not pulled back.
     */
    private fun keepEndAfterResize() {
        if (!followsEnd()) return
        val count = adapter?.itemCount ?: 0
        if (count > 0) (layoutManager as? LinearLayoutManager)?.scrollToPosition(count - 1)
    }

    /**
     * Explicit jumps move the list without a scroll delta: its end state is
     * re-read once the jump was laid out (see [onLayout]).
     */
    private var endStateAfterLayout = false

    private fun refreshEndStateAfterJump() {
        endStateAfterLayout = true
    }

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
                ?.scrollToPositionWithOffset(positionOfRow(initialIndex).coerceAtMost(itemCount - 1), 0)
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
        manager.scrollToPositionWithOffset(positionOfRow(initialIndex).coerceAtMost(itemCount - 1), 0)
        initialPositionApplied = true
    }

    /** Item (row node) id at adapter [position], or null outside the rows. */
    fun richIdAt(position: Int): Long? = richIds.getOrNull(position)

    /**
     * Rows of a windowed list: [rowSpan] gives an item's first row index and
     * how many rows it stands for (a placeholder run covers several, each
     * extent / rows tall), or null when it is not a row.
     */
    var rowSpan: ((Long) -> IntArray?)? = null

    /** [rowSpan] of each item as of the last [setRichItems] (what is on screen). */
    private var richSpans: Map<Long, IntArray> = emptyMap()

    /** A list row and how far the viewport's start edge is inside it (px). */
    class RowAnchor(val row: Int, val intoRow: Int, val itemId: Long, val intoItem: Int, val single: Boolean = false)

    /**
     * The row at the viewport's start edge (inside a placeholder run, the row
     * of the run under it), or null.
     */
    fun rowAnchor(): RowAnchor? = rowAt(atEnd = false)

    /** Logical rows of the list (a placeholder run counts its rows). */
    fun rowCount(): Int {
        val span = rowSpan ?: return richIds.size
        var rows = 0
        for (id in richIds) rows += span(id)?.get(1)?.coerceAtLeast(1) ?: 1
        return rows
    }

    /** First and last row index the viewport shows, or null. */
    fun visibleRows(): IntArray? {
        val first = rowAt(atEnd = false) ?: return null
        val last = rowAt(atEnd = true) ?: first
        return intArrayOf(first.row, last.row)
    }

    private fun rowAt(atEnd: Boolean): RowAnchor? {
        if (rowSpan == null) return null
        val span = richSpans::get
        if (horizontal) return null
        val manager = layoutManager as? LinearLayoutManager ?: return null
        val position = if (atEnd) manager.findLastVisibleItemPosition() else manager.findFirstVisibleItemPosition()
        if (position == NO_POSITION) return null
        val view = manager.findViewByPosition(position) ?: return null
        val id = richIds.getOrNull(position) ?: return null
        val (start, rows) = span(id)?.let { it[0] to it[1].coerceAtLeast(1) } ?: return null
        val extent = view.height
        // Distance from the item's start edge (layout direction) to the
        // viewport edge asked for.
        val edge = if (atEnd) {
            if (inverted) paddingTop else height - paddingBottom
        } else {
            if (inverted) height - paddingBottom else paddingTop
        }
        val into = (if (inverted) view.bottom - edge else edge - view.top).coerceIn(0, max(0, extent - 1))
        if (rows == 1 || extent <= 0) return RowAnchor(start, into, id, 0, single = rows == 1)
        val rowPx = extent.toFloat() / rows
        val row = (into / rowPx).toInt().coerceIn(0, rows - 1)
        val rowStart = (row * rowPx).toInt()
        return RowAnchor(start + row, into - rowStart, id, rowStart)
    }

    /**
     * After rows were replaced (a placeholder run split into rows), keeps
     * [anchor]'s row exactly where it was on screen. A fling in progress
     * continues: scrollToPositionWithOffset does not stop it.
     */
    fun restoreRowAnchor(anchor: RowAnchor) {
        if (rowSpan == null) return
        // The anchor exists for placeholder runs that split into rows. A
        // single row is kept by its stable id (RecyclerView, head-insert and
        // rebind anchors); if it left, its old row index names another row
        // now (rows joined above it), so restoring that index jumped the list
        // by those rows (an older page landing under the reader).
        if (anchor.single) return
        val span = richSpans::get
        val manager = layoutManager as? LinearLayoutManager ?: return
        for ((position, id) in richIds.withIndex()) {
            val item = span(id) ?: continue
            val start = item[0]
            val rows = item[1].coerceAtLeast(1)
            if (anchor.row < start || anchor.row >= start + rows) continue
            val extent = richExtents[id] ?: return
            val intoItem = if (rows == 1) 0 else ((anchor.row - start) * (extent.toFloat() / rows)).toInt()
            if (id == anchor.itemId && intoItem == anchor.intoItem) return
            manager.scrollToPositionWithOffset(position, -(intoItem + anchor.intoRow))
            return
        }
    }

    /** Adapter position of the item holding row [row] (windowed lists), or [row]. */
    private fun positionOfRow(row: Int): Int {
        if (rowSpan == null) return row
        val span = richSpans::get
        for ((position, id) in richIds.withIndex()) {
            val item = span(id) ?: continue
            if (row >= item[0] && row < item[0] + item[1].coerceAtLeast(1)) return position
        }
        return row
    }

    /** Reports the current visible range again (after rows were replaced). */
    fun reportViewport() {
        if (viewportChanged != null && isLaidOut) dispatchViewport()
    }

    private fun dispatchViewport() {
        val layout = layoutManager as? LinearLayoutManager ?: return
        val first = layout.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = layout.findLastVisibleItemPosition()
        val visible = if (last >= first) last - first + 1 else 0
        val total = adapter?.itemCount ?: 0
        val offset = logicalScrollOffsetPx() / resources.displayMetrics.density
        viewportChanged?.invoke(offset, first, visible, total)
    }

    /**
     * Scroll offset along the list axis. RecyclerView's own value is an
     * average-row estimate (smooth scrollbar): with rows of very different
     * heights a windowed list mounted rows far from the visible ones after a
     * fling. Rich rows have known extents, so the offset is their exact sum.
     */
    private fun logicalScrollOffsetPx(): Int {
        val estimated = if (horizontal) computeHorizontalScrollOffset() else computeVerticalScrollOffset()
        return exactRichScrollOffsetPx() ?: estimated
    }

    private fun exactRichScrollOffsetPx(): Int? {
        if (inverted || richIds.isEmpty()) return null
        val manager = layoutManager as? LinearLayoutManager ?: return null
        if (manager is GridLayoutManager) return null
        val first = manager.findFirstVisibleItemPosition()
        if (first == NO_POSITION || first >= richIds.size) return null
        val view = manager.findViewByPosition(first) ?: return null
        return exactListScrollOffset(
            extents = richIds.subList(0, first).map { richExtents[it] ?: return null },
            firstStart = if (horizontal) view.left - paddingLeft else view.top - paddingTop,
        )
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
        /** Off-screen space kept laid out on each side (the prefetched rows). */
        var extraLayoutSpace: Int
        /** Most the laid-out off-screen space grows in one layout or scroll pass (one row). */
        var extraLayoutStep: Int
        /**
         * Lays out only the viewport again on the given sides (the start is
         * where position 0 lies) and grows back toward [extraLayoutSpace] row
         * by row. The other side keeps its laid-out rows.
         */
        fun restartExtraLayoutRamp(start: Boolean = true, end: Boolean = true)

        /** Whether both sides already reached [extraLayoutSpace] (or [limit]). */
        fun extraLayoutRamped(limit: Int = Int.MAX_VALUE): Boolean
    }

    private class PamLinearLayoutManager(
        context: Context,
        orientation: Int,
        reverseLayout: Boolean,
        private val pins: StickyPins,
        private val beforeLayout: (LinearLayoutManager) -> Unit,
        private val afterLayout: (LinearLayoutManager, Recycler, State) -> Unit,
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
            afterLayout(this, recycler, state)
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
        override var extraLayoutStep = 0
        private var rampedStartSpace = 0
        private var rampedEndSpace = 0

        override fun restartExtraLayoutRamp(start: Boolean, end: Boolean) {
            if (start) rampedStartSpace = 0
            if (end) rampedEndSpace = 0
        }

        override fun extraLayoutRamped(limit: Int): Boolean =
            minOf(extraLayoutSpace, limit).let { rampedStartSpace >= it && rampedEndSpace >= it }

        override fun calculateExtraLayoutSpace(
            state: State,
            extraLayoutSpace: IntArray,
        ) {
            super.calculateExtraLayoutSpace(state, extraLayoutSpace)
            rampedStartSpace = rampExtraLayoutSpace(rampedStartSpace, this.extraLayoutSpace, extraLayoutStep)
            rampedEndSpace = rampExtraLayoutSpace(rampedEndSpace, this.extraLayoutSpace, extraLayoutStep)
            extraLayoutSpace[0] = max(extraLayoutSpace[0], rampedStartSpace)
            extraLayoutSpace[1] = max(extraLayoutSpace[1], rampedEndSpace)
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
        override var extraLayoutStep = 0
        private var rampedStartSpace = 0
        private var rampedEndSpace = 0

        override fun restartExtraLayoutRamp(start: Boolean, end: Boolean) {
            if (start) rampedStartSpace = 0
            if (end) rampedEndSpace = 0
        }

        override fun extraLayoutRamped(limit: Int): Boolean =
            minOf(extraLayoutSpace, limit).let { rampedStartSpace >= it && rampedEndSpace >= it }

        override fun calculateExtraLayoutSpace(
            state: State,
            extraLayoutSpace: IntArray,
        ) {
            super.calculateExtraLayoutSpace(state, extraLayoutSpace)
            rampedStartSpace = rampExtraLayoutSpace(rampedStartSpace, this.extraLayoutSpace, extraLayoutStep)
            rampedEndSpace = rampExtraLayoutSpace(rampedEndSpace, this.extraLayoutSpace, extraLayoutStep)
            extraLayoutSpace[0] = max(extraLayoutSpace[0], rampedStartSpace)
            extraLayoutSpace[1] = max(extraLayoutSpace[1], rampedEndSpace)
        }
    }

    private companion object {
        const val MAX_PREFETCH_ITEMS = 32
    }
}

/**
 * Off-screen layout space for the next pass: it shrinks at once but grows by
 * at most [step] per pass, so raising the prefetch distance (fast flings) or
 * appending a page binds about one more row per frame instead of many rows
 * in one frame. A step of zero applies [target] directly.
 */
internal fun rampExtraLayoutSpace(current: Int, target: Int, step: Int): Int =
    if (step <= 0 || target <= current) target else minOf(target, current + step)

/** True when [next] keeps every id of a non-empty [previous] in order and adds rows after them. */
internal fun isAppend(previous: List<Long>, next: List<Long>): Boolean =
    previous.isNotEmpty() && next.size > previous.size && next.subList(0, previous.size) == previous

/**
 * True when [next] adds new rows before the first row of [previous] it keeps
 * (older history prepended, even while a bounded window drops rows at its
 * other end).
 */
internal fun rowsAddedBefore(previous: List<Long>, next: List<Long>): Boolean {
    if (previous.isEmpty() || next.isEmpty()) return false
    val index = next.indexOf(previous.first())
    if (index <= 0) return false
    val kept = previous.toHashSet()
    return next.subList(0, index).none(kept::contains)
}

/**
 * The row to anchor when [next] inserts rows right after a leading row on
 * screen: scanning from the first visible row through the list's first
 * [HEAD_INSERT_LEADING_ROWS] + 1 rows, the first kept row with inserted rows
 * directly before it. Null when that is the first visible row itself
 * (RecyclerView keeps it) or nothing was inserted near the head.
 */
internal fun headInsertAnchor(previous: List<Long>, next: List<Long>, firstVisible: Int): Long? {
    if (firstVisible < 0 || firstVisible >= previous.size || firstVisible > HEAD_INSERT_LEADING_ROWS) return null
    val positions = HashMap<Long, Int>(next.size * 2)
    next.forEachIndexed { index, id -> positions[id] = index }
    val kept = previous.toHashSet()
    val last = minOf(previous.size - 1, HEAD_INSERT_LEADING_ROWS + 1)
    for (position in firstVisible..last) {
        val id = previous[position]
        val index = positions[id] ?: continue
        if (index > 0 && next[index - 1] !in kept) return if (position == firstVisible) null else id
    }
    return null
}

/** Leading rows (headers, loaders) a head insert may land after. */
internal const val HEAD_INSERT_LEADING_ROWS = 2

/** True when [next] adds new rows after the last row of [previous] it keeps. */
internal fun rowsAddedAfter(previous: List<Long>, next: List<Long>): Boolean {
    if (previous.isEmpty() || next.isEmpty()) return false
    val index = next.lastIndexOf(previous.last())
    if (index < 0 || index == next.lastIndex) return false
    val kept = previous.toHashSet()
    return next.subList(index + 1, next.size).none(kept::contains)
}

internal data class VirtualScrollPosition(val index: Int, val offset: Int)

/** First visible row of a keyed section and its offset from the list start edge. */
internal data class VirtualScrollAnchor(val id: Long, val offset: Int)

/** A keyed-section change of a list; [railId] is the last row shared above the sections. */
internal data class ListSectionSwitch(val from: String, val to: String, val railId: Long?)

/**
 * Rows a list shows for [activeSection]: unsectioned rows always, sectioned
 * rows only for the active section.
 */
internal fun visibleSectionItems(
    ids: List<Long>,
    activeSection: String,
    sectionOf: (Long) -> String?,
): List<Long> = ids.filter { id -> sectionOf(id).let { it == null || it == activeSection } }

/** Last unsectioned row before the first sectioned one (the tab rail), if any. */
internal fun sectionRailId(ids: List<Long>, sectionOf: (Long) -> String?): Long? {
    val first = ids.indexOfFirst { sectionOf(it) != null }
    return if (first > 0) ids[first - 1] else null
}

/**
 * Where a section switched in while the rail was pinned scrolls to: its
 * saved anchor when that row is still listed, otherwise the rail at the top
 * (the section's first row right below it).
 */
internal fun sectionScrollTarget(
    ids: List<Long>,
    saved: VirtualScrollAnchor?,
    railId: Long?,
): VirtualScrollPosition? {
    saved?.let { anchor ->
        val index = ids.indexOf(anchor.id)
        if (index >= 0) return VirtualScrollPosition(index, anchor.offset)
    }
    val rail = railId?.let(ids::indexOf) ?: return null
    return if (rail >= 0) VirtualScrollPosition(rail, 0) else null
}

/** Start offset that lays a row of [rowExtent] flush with the end of a [viewportExtent] list. */
internal fun pinnedEndOffset(viewportExtent: Int, padding: Int, rowExtent: Int): Int =
    viewportExtent - padding - rowExtent

internal fun virtualScrollPosition(extents: List<Int>, target: Int): VirtualScrollPosition {
    if (extents.isEmpty()) return VirtualScrollPosition(0, 0)
    var remaining = target.coerceAtLeast(0)
    for ((index, extent) in extents.withIndex()) {
        // Mounted empty cells occupy no space and cannot hold the target.
        if (extent <= 0 && index != extents.lastIndex) continue
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

internal class RichRecyclerAdapter(
    private val context: Context,
    ids: List<Long>,
    extents: Map<Long, Int>,
    private val mount: (Long, FrameLayout) -> Unit,
    private val unmount: (Long, FrameLayout) -> Unit,
    private val deferMounts: () -> Boolean = { false },
    /**
     * Builds part of a cell until a deadline (System.nanoTime), returning
     * whether it is complete; a later call resumes it. Without it a budgeted
     * row mounts whole, however long that takes.
     */
    private val mountSlice: ((Long, FrameLayout, Long) -> Boolean)? = null,
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

    /**
     * Set by the list: whether the row at a position is off screen while the
     * list moves. Such a row (bound ahead by a fling's layout or rebound by a
     * window slide) mounts within the per-frame budget instead of all at once
     * in one frame; it always mounts before it is drawn on screen
     * ([mountShown]).
     */
    var offscreenWhileMoving: (Int) -> Boolean = { false }

    /** Whether the list is moving (set by the list). */
    var moving: () -> Boolean = { false }

    /** Display frame interval in ms (set by the list). */
    var frameIntervalMs: () -> Long = { 16L }

    /**
     * Nanoseconds of the current frame still free for off-screen cell slices:
     * they may use up to half of the frame interval, counted from the frame's
     * vsync, minus what input, scrolling and layout already took. A frame that
     * started late or did heavy input work leaves nothing, so the slice waits
     * for a lighter frame instead of pushing this one past its deadline (one
     * late frame while the list moves leaves the window buffer-stuffed until
     * the list stops).
     */
    private fun frameRoomNanos(budget: Long): Long {
        val frame = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
        val elapsedMs = android.os.SystemClock.uptimeMillis() - frame
        val roomMs = frameIntervalMs() / 2 + 1 - elapsedMs
        return minOf(budget, roomMs * 1_000_000L).coerceAtLeast(0L)
    }

    /** Whether the row at a position is on screen (set by the list). */
    var shown: (Int) -> Boolean = { true }

    /**
     * Cells bound off screen whose content changed in a commit (a window
     * slide turning a placeholder into its row, rows gaining descendants):
     * their views are built within the per-frame budget instead of inside
     * the commit, and always before the row is drawn on screen.
     */
    private val pendingRepairs = LinkedHashMap<Long, Pair<RichHolder, (Long) -> Boolean>>()

    /**
     * Defers [repair] of cell [id] when it is bound off screen. Returns false
     * (the caller repairs now) when it is on screen or not bound.
     */
    fun deferRepair(id: Long, repair: (Long) -> Boolean): Boolean {
        val holder = boundHolders.firstOrNull { it.boundId == id } ?: return false
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || shown(position)) return false
        pendingRepairs[id] = holder to repair
        scheduleDrain()
        return true
    }

    /** Pending off-screen repairs (tests). */
    internal fun pendingRepairCount(): Int = pendingRepairs.size

    private fun repairShown() {
        if (pendingRepairs.isEmpty()) return
        val iterator = pendingRepairs.entries.iterator()
        while (iterator.hasNext()) {
            val (id, entry) = iterator.next()
            val (holder, repair) = entry
            if (holder.boundId != id) {
                iterator.remove()
                continue
            }
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || !shown(position)) continue
            iterator.remove()
            repair(Long.MAX_VALUE)
        }
    }

    /** Mounts, now, every pending row that is on screen: never drawn empty. */
    fun mountShown() {
        repairShown()
        if (pendingMounts.isEmpty()) return
        val iterator = pendingMounts.entries.iterator()
        while (iterator.hasNext()) {
            val (holder, id) = iterator.next()
            val position = holder.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION || !shown(position)) continue
            iterator.remove()
            // Pending rows are empty or partly built (a slice): finish them.
            if (holder.boundId == id && (mountSlice != null || holder.container.childCount == 0)) {
                android.os.Trace.beginSection("PamList.mountShown")
                try {
                    mount(id, holder.container)
                } finally {
                    android.os.Trace.endSection()
                }
            }
        }
    }

    private fun mountWithinBudget(holder: RichHolder, id: Long, position: Int = RecyclerView.NO_POSITION): Boolean {
        if (!deferMounts() && (position == RecyclerView.NO_POSITION || !offscreenWhileMoving(position))) {
            return mountNow(holder, id)
        }
        // One budget per frame: the animation clock is the frame's vsync time.
        val frame = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
        if (frame != mountWindowStart) {
            mountWindowStart = frame
            mountWindowUsed = 0L
        }
        val room = frameRoomNanos(MOUNT_BUDGET_NANOS - mountWindowUsed)
        if (mountWindowUsed >= MOUNT_BUDGET_NANOS || room <= 0L) {
            pendingMounts[holder] = id
            scheduleDrain()
            return false
        }
        val started = System.nanoTime()
        val complete = mountPart(holder, id, started + room)
        mountWindowUsed += System.nanoTime() - started
        if (!complete) {
            pendingMounts[holder] = id
            scheduleDrain()
        }
        return complete
    }

    /** Mounts [holder]'s cell until [deadline] when slices are supported; true once complete. */
    private fun mountPart(holder: RichHolder, id: Long, deadline: Long): Boolean {
        val slice = mountSlice ?: run {
            if (holder.container.childCount == 0) mount(id, holder.container)
            return true
        }
        return slice(id, holder.container, deadline)
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
            android.os.Trace.beginSection("PamList.slice")
            try {
                drain()
            } finally {
                android.os.Trace.endSection()
            }
        }
    }

    private fun drain() {
        run {
            drainScheduled = false
            mountShown()
            // Rows bound earlier in this frame (a scroll) share its budget.
            val frame = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
            if (frame != mountWindowStart) {
                mountWindowStart = frame
                mountWindowUsed = 0L
            }
            // While the list moves its frames are already busy: the small budget.
            val budget = if (deferMounts() || moving()) MOUNT_BUDGET_NANOS else SHOWN_MOUNT_BUDGET_NANOS
            // While the list moves the slice also fits in what the frame has left.
            val frameEnd = if (moving()) System.nanoTime() + frameRoomNanos(budget) else Long.MAX_VALUE
            val iterator = pendingMounts.entries.iterator()
            // At least one view per frame, so a slow stretch still makes progress.
            var sliced = false
            while (iterator.hasNext() && mountWindowUsed < budget && (!sliced || System.nanoTime() < frameEnd)) {
                val (holder, id) = iterator.next()
                if (holder.boundId != id) {
                    iterator.remove()
                    continue
                }
                val started = System.nanoTime()
                val complete = mountPart(holder, id, minOf(started + budget - mountWindowUsed, frameEnd))
                sliced = true
                mountWindowUsed += System.nanoTime() - started
                if (complete) iterator.remove()
            }
            // Off-screen repairs: within the smaller budget, at most one
            // cell per frame while the list moves (they are ahead of it).
            val repairs = pendingRepairs.entries.iterator()
            var repaired = 0
            while (repairs.hasNext() && mountWindowUsed < MOUNT_BUDGET_NANOS && System.nanoTime() < frameEnd) {
                if (repaired > 0 && moving()) break
                val (id, entry) = repairs.next()
                if (entry.first.boundId != id) {
                    repairs.remove()
                    continue
                }
                val started = System.nanoTime()
                val complete = entry.second(minOf(started + MOUNT_BUDGET_NANOS - mountWindowUsed, frameEnd))
                repaired++
                mountWindowUsed += System.nanoTime() - started
                if (complete) repairs.remove()
            }
            if (pendingMounts.isNotEmpty() || pendingRepairs.isNotEmpty()) scheduleDrain()
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

    /**
     * Keyed rows that kept their relative order (filtering, pages appended or
     * prepended, rows inserted or removed anywhere): range updates from one
     * O(N) walk, instead of DiffUtil's O(N + D^2) (a filter that dropped
     * 8,000 of 10,000 rows took seconds on the UI thread). Returns false when
     * rows moved relative to each other.
     */
    private fun submitKeyed(
        previous: List<Long>,
        previousExtents: Map<Long, Int>,
        replacement: List<Long>,
        replacementExtents: Map<Long, Int>,
    ): Boolean {
        val nextPositions = HashMap<Long, Int>(replacement.size * 2)
        replacement.forEachIndexed { position, id -> nextPositions[id] = position }
        val previousIds = HashSet<Long>(previous.size * 2)
        var lastCommon = -1
        for (id in previous) {
            previousIds += id
            val position = nextPositions[id] ?: continue
            if (position < lastCommon) return false
            lastCommon = position
        }
        // Count the runs first: too many scattered ranges rebind instead.
        var runs = 0
        run {
            var i = 0
            var j = 0
            var inRun = 0 // 1 removed, 2 inserted, 3 changed
            while (i < previous.size || j < replacement.size) {
                val kind = when {
                    i < previous.size && previous[i] !in nextPositions -> { i++; 1 }
                    j < replacement.size && replacement[j] !in previousIds -> { j++; 2 }
                    else -> {
                        val id = replacement[j]
                        i++
                        j++
                        if (previousExtents[id] != replacementExtents[id]) 3 else 0
                    }
                }
                if (kind != 0 && kind != inRun) runs++
                inRun = kind
            }
        }
        ids = replacement
        extents = replacementExtents
        if (runs > MAX_RANGE_UPDATES) {
            reboundAll = true
            @SuppressLint("NotifyDataSetChanged") // more range updates than a full rebind costs
            notifyDataSetChanged()
            return true
        }
        var i = 0
        var j = 0
        var position = 0
        while (i < previous.size || j < replacement.size) {
            if (i < previous.size && previous[i] !in nextPositions) {
                var count = 0
                while (i < previous.size && previous[i] !in nextPositions) {
                    i++
                    count++
                }
                // Rows replaced in place (a window slide swapping placeholders
                // and rows, a re-keyed row): a removed run right before an
                // inserted run rebinds the overlap in place instead of a
                // remove + insert, which forces a full relayout of every
                // attached row mid-fling and rebinds off-screen ones.
                var inserted = 0
                while (j + inserted < replacement.size && replacement[j + inserted] !in previousIds) inserted++
                val replaced = minOf(count, inserted)
                if (replaced > 0) {
                    notifyItemRangeChanged(position, replaced)
                    position += replaced
                    j += replaced
                }
                if (count > replaced) notifyItemRangeRemoved(position, count - replaced)
            } else if (j < replacement.size && replacement[j] !in previousIds) {
                var count = 0
                while (j < replacement.size && replacement[j] !in previousIds) {
                    j++
                    count++
                }
                notifyItemRangeInserted(position, count)
                position += count
            } else {
                var count = 0
                val start = position
                while (
                    i < previous.size && j < replacement.size &&
                    previous[i] == replacement[j] &&
                    previousExtents[replacement[j]] != replacementExtents[replacement[j]]
                ) {
                    i++
                    j++
                    count++
                }
                if (count > 0) {
                    notifyItemRangeChanged(start, count, PAYLOAD_LAYOUT)
                    position += count
                } else {
                    i++
                    j++
                    position++
                }
            }
        }
        return true
    }

    /** The last submit rebound every row (notifyDataSetChanged: positions, not ids, are kept). */
    var reboundAll = false

    fun submit(next: List<Long>, nextExtents: Map<Long, Int>) {
        reboundAll = false
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
        if (submitKeyed(previous, previousExtents, replacement, replacementExtents)) return
        if (previous.size + replacement.size > LARGE_DIFF_ROWS) {
            // Reordered rows in a long list: Myers with move detection is
            // O(N + D^2); rebinding by stable id is cheaper.
            ids = replacement
            extents = replacementExtents
            reboundAll = true
            @SuppressLint("NotifyDataSetChanged") // long reorders: rebinding by stable id beats Myers
            notifyDataSetChanged()
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
        bind(holder, ids[position], position)
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
            bind(holder, id, position)
        }
    }

    override fun onViewRecycled(holder: RichHolder) {
        pendingMounts.remove(holder)
        pendingRepairs.entries.removeAll { it.value.first === holder }
        boundHolders.remove(holder)
        emptyRemountAttempts.remove(holder)
        holder.boundId.takeIf { it != RecyclerView.NO_ID && ownsCell(holder, it) }?.let {
            unmount(it, holder.container)
        }
        holder.boundId = RecyclerView.NO_ID
        holder.container.removeAllViews()
        super.onViewRecycled(holder)
    }

    private fun bind(holder: RichHolder, id: Long, position: Int = RecyclerView.NO_POSITION) {
        val previous = holder.boundId
        pendingMounts.remove(holder)
        if (previous != RecyclerView.NO_ID && previous != id) {
            emptyRemountAttempts.remove(holder)
            if (ownsCell(holder, previous)) unmount(previous, holder.container)
            holder.container.removeAllViews()
        }
        applyLayout(holder.container, id)
        holder.boundId = id
        mountWithinBudget(holder, id, position)
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
        val cell = container as? RichCellContainer
        cell?.resizing = true
        try {
            container.requestLayout()
        } finally {
            cell?.resizing = false
        }
    }

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun isBound(id: Long): Boolean = boundHolders.any { it.boundId == id }

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
        /** More scattered range updates than this rebind by stable id instead. */
        const val MAX_RANGE_UPDATES = 64
        /** Reordered lists longer than this rebind instead of a Myers diff. */
        const val LARGE_DIFF_ROWS = 2_000
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

    /** Set while the adapter resizes this holder: that request must reach the list. */
    var resizing = false

    private var localLayoutPending = false
    private val localLayout = object : android.view.ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            localLayoutPending = false
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
            if (isLayoutRequested && isLaidOut && isAttachedToWindow && width > 0 && height > 0) {
                measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
                layout(left, top, right, bottom)
            }
            return true
        }
    }

    /**
     * A row's size never depends on its views (the engine sizes it), so a
     * view added, removed or changed inside it (a cell slice building it over
     * several frames, a text or image settling) lays out only this row, just
     * before the next draw, instead of asking the list for a full layout pass
     * of every row (5 to 9 ms per frame while a list moves). A resize from the
     * adapter, a row not laid out yet or a list already laying out goes the
     * normal way.
     */
    override fun requestLayout() {
        if (!resizing && canLayOutLocally()) {
            forceLayout()
            if (!localLayoutPending) {
                localLayoutPending = true
                viewTreeObserver.addOnPreDrawListener(localLayout)
                invalidate()
            }
            return
        }
        super.requestLayout()
    }

    private fun canLayOutLocally(): Boolean {
        val list = parent as? RecyclerView ?: return false
        if (!isAttachedToWindow || !isLaidOut || isInLayout || list.isInLayout || list.isLayoutRequested) return false
        val params = layoutParams ?: return false
        return fixedBy(params.width, width) && fixedBy(params.height, height)
    }

    private fun fixedBy(spec: Int, current: Int): Boolean =
        spec == ViewGroup.LayoutParams.MATCH_PARENT || spec >= 0 && spec == current

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

/** Offset of a list whose first visible row starts at [firstStart] px, after rows of [extents]. */
internal fun exactListScrollOffset(extents: List<Int>, firstStart: Int): Int =
    (extents.sum() - firstStart).coerceAtLeast(0)

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
