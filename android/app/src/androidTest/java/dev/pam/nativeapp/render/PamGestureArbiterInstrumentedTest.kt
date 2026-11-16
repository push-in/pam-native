package dev.pam.nativeapp.render

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.EventKind
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gesture arbiter: the navigator's back gesture yields to content under
 * the finger that owns the drag (a pager with a previous page), except from
 * the edge; pans, pull to refresh and the renderer's gesture options follow
 * the same rule (iOS require(toFail:), ViewPager.canScroll).
 */
@RunWith(AndroidJUnit4::class)
class PamGestureArbiterInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun fullScreenBackYieldsToAPagerThatHasAPreviousPage() {
        for (fromEdgeDp in listOf(40f, 95f, 200f)) {
            withPagerRoute(fullScreen = true, startPage = 1) { fixture ->
                val drag = fixture.dragRight(fromEdgeDp)
                assertEquals("$fromEdgeDp dp: the back gesture must not begin", 0, fixture.began)
                assertEquals("$fromEdgeDp dp: the route stays put", 0f, drag.routeTranslation, 0.01f)
                assertTrue(
                    "$fromEdgeDp dp: the pager turns toward the previous page (${drag.pagerScroll})",
                    drag.pagerScroll < fixture.width - fixture.dp(40f),
                )
            }
        }
    }

    @Test
    fun edgeBackLeavesThePagerItsDragAwayFromTheEdge() {
        for (fromEdgeDp in listOf(40f, 95f, 200f)) {
            withPagerRoute(fullScreen = false, startPage = 1) { fixture ->
                val drag = fixture.dragRight(fromEdgeDp)
                assertEquals("$fromEdgeDp dp: no back gesture", 0, fixture.began)
                assertTrue("$fromEdgeDp dp: the pager turns", drag.pagerScroll < fixture.width - fixture.dp(40f))
            }
        }
    }

    @Test
    fun backBeginsOnTheFirstPageWhereThePagerHasNothingToScroll() {
        for (fromEdgeDp in listOf(40f, 95f, 200f)) {
            withPagerRoute(fullScreen = true, startPage = 0) { fixture ->
                val drag = fixture.dragRight(fromEdgeDp)
                assertEquals("$fromEdgeDp dp: the back gesture begins", 1, fixture.began)
                assertTrue("$fromEdgeDp dp: the route follows the finger", drag.routeTranslation > 0f)
                assertEquals("$fromEdgeDp dp: the pager stays on its first page", 0, drag.pagerScroll)
            }
        }
    }

    @Test
    fun theEdgeOutranksThePager() {
        for (fullScreen in listOf(false, true)) {
            for (fromEdgeDp in listOf(4f, 16f, 22f)) {
                withPagerRoute(fullScreen = fullScreen, startPage = 1) { fixture ->
                    val drag = fixture.dragRight(fromEdgeDp)
                    assertEquals("fullScreen=$fullScreen $fromEdgeDp dp: back begins", 1, fixture.began)
                    assertTrue("fullScreen=$fullScreen $fromEdgeDp dp: route moves", drag.routeTranslation > 0f)
                }
            }
        }
    }

    @Test
    fun blocksNavigationGestureKeepsEvenTheEdge() {
        withPagerRoute(fullScreen = true, startPage = 0, blocker = true) { fixture ->
            val drag = fixture.dragRight(12f)
            assertEquals("A blocking view keeps the drag from the edge", 0, fixture.began)
            assertEquals(0f, drag.routeTranslation, 0.01f)
        }
    }

    @Test
    fun aListClaimForItsScrollAncestorDoesNotHideTheBackGesture() {
        // A vertical list claims every touch on DOWN for a platform
        // ScrollView ancestor's sake; a horizontal drag is still the back
        // gesture's, from the edge and in full-screen mode.
        for ((fullScreen, fromEdgeDp) in listOf(false to 12f, true to 95f)) {
            withClaimingRoute(fullScreen, ClaimingView.FOR_SCROLL_ANCESTOR) { fixture ->
                val drag = fixture.dragRight(fromEdgeDp)
                assertEquals("fullScreen=$fullScreen: back begins over the list", 1, fixture.began)
                assertTrue(drag.routeTranslation > 0f)
            }
        }
        // A view that took the touch on purpose (a slider, a canvas) keeps it.
        withClaimingRoute(fullScreen = false, claim = ClaimingView.FIRM) { fixture ->
            val drag = fixture.dragRight(12f)
            assertEquals("A firm claim keeps the touch", 0, fixture.began)
            assertEquals(0f, drag.routeTranslation, 0.01f)
        }
    }

    @Test
    fun horizontalPanOwnsAFullScreenDragButNotTheEdge() {
        withPanRoute(direction = 6) { fixture ->
            val drag = fixture.dragRight(95f)
            assertEquals("A horizontal pan keeps a full-screen drag", 0, fixture.began)
            assertEquals(0f, drag.routeTranslation, 0.01f)
        }
        withPanRoute(direction = 7) { fixture ->
            fixture.dragRight(95f)
            assertEquals("A vertical pan leaves a horizontal drag to the back gesture", 1, fixture.began)
        }
        withPanRoute(direction = 6) { fixture ->
            fixture.dragRight(12f)
            assertEquals("The edge outranks a pan", 1, fixture.began)
        }
    }

    @Test
    fun rendererAppliesAFullScreenChangeOnItsOwn() {
        // The stories viewer opened from a chat: only fullScreenGestureEnabled
        // changes on the push (gesture enabled, edge and threshold do not).
        val activity = launch()
        val events = mutableListOf<Int>()
        lateinit var renderer: PamRenderer
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                    if (id == 1L) events += kind
                }
                renderer.commit(listOf(listOf(
                    Mutation.Create(NodeSpec(id = 1, parent = 0, index = 0, kind = NodeKind.NAVIGATION_HOST, properties = mapOf(
                        PropKey.NAVIGATION_DURATION_MS to PropValue.Integer(0),
                        PropKey.NAVIGATION_GESTURE_ENABLED to PropValue.Flag(true),
                        PropKey.NAVIGATION_FULL_SCREEN_GESTURE_ENABLED to PropValue.Flag(true),
                        PropKey.ON_GESTURE_BEGIN to PropValue.Flag(true),
                    ))),
                    Mutation.Create(NodeSpec(id = 2, parent = 1, index = 0, kind = NodeKind.SCREEN, properties = emptyMap())),
                    Mutation.Create(NodeSpec(id = 3, parent = 1, index = 1, kind = NodeKind.SCREEN, properties = emptyMap())),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 720f)),
                    Mutation.Layout(3, Frame(0f, 0f, 360f, 720f)),
                    Mutation.Update(1, PropKey.NAVIGATION_OPERATION, PropValue.Integer(2)),
                    Mutation.Update(1, PropKey.NAVIGATION_REVISION, PropValue.Integer(1)),
                    Mutation.SetRoot(1),
                )))
            }
            settle()
            val navigation = findNavigation(activity.host)
            val began = { events.count { it == EventKind.GESTURE_BEGIN.value } }
            drag(navigation, navigation.px(95f), navigation.height / 2f, navigation.px(60f))
            settle()
            assertEquals("Full screen: a drag from 95 dp begins the back gesture", 1, began())
            instrumentation.runOnMainSync {
                renderer.commit(listOf(listOf(
                    Mutation.Update(1, PropKey.NAVIGATION_FULL_SCREEN_GESTURE_ENABLED, PropValue.Flag(false)),
                )))
            }
            settle()
            drag(navigation, navigation.px(95f), navigation.height / 2f, navigation.px(60f))
            settle()
            assertEquals("Edge only: the same drag must not begin it", 1, began())
            drag(navigation, navigation.px(10f), navigation.height / 2f, navigation.px(60f))
            settle()
            assertEquals("Edge only: a drag from the edge still does", 2, began())
        } finally {
            instrumentation.runOnMainSync {
                renderer.close()
                activity.finish()
            }
        }
    }

    @Test
    fun simultaneousDismissPanLetsAScrolledListScrollFirst() {
        val activity = launch()
        try {
            var began = 0
            lateinit var pressable: PamPressable
            lateinit var list: ScrollView
            instrumentation.runOnMainSync {
                pressable = PamPressable(activity)
                // Stories' dismiss: a vertical, simultaneous pan.
                pressable.configureGesture(PamGestureConfig(2, true, 1, 1, 7, 2, 24f, 0L), callback = {
                    if (it.state == 1) began += 1
                })
                val scroll = PamScrollContainer(activity)
                scroll.insert(View(activity).apply { layoutParams = FrameLayout.LayoutParams(600, 3_000) })
                pressable.addView(scroll, FrameLayout.LayoutParams(600, 1_000))
                activity.host.addView(pressable, FrameLayout.LayoutParams(600, 1_000))
                layoutExactly(pressable, 600, 1_000)
                list = descendant(scroll)
                list.scrollTo(0, 800)
            }
            drag(pressable, 300f, 300f, 0f, dy = 360f)
            instrumentation.runOnMainSync {
                assertEquals("The list scrolled down keeps the drag", 0, began)
                assertTrue("The list scrolls up (${list.scrollY})", list.scrollY < 800)
                list.scrollTo(0, 0)
            }
            drag(pressable, 300f, 300f, 0f, dy = 360f)
            instrumentation.runOnMainSync {
                assertEquals("At its top the list leaves the drag to the dismiss pan", 1, began)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun pullToRefreshDefersToANestedListThatCanScrollUp() {
        val activity = launch()
        try {
            lateinit var refresh: PamRefreshContainer
            lateinit var list: ScrollView
            instrumentation.runOnMainSync {
                refresh = PamRefreshContainer(activity).apply { setOnRefresh {} }
                // The target is a wrapper; the scroller is deeper (a page).
                val wrapper = FrameLayout(activity)
                val scroll = PamScrollContainer(activity)
                scroll.insert(View(activity).apply { layoutParams = FrameLayout.LayoutParams(600, 3_000) })
                wrapper.addView(scroll, FrameLayout.LayoutParams(600, 1_000))
                refresh.insert(wrapper, 0)
                activity.host.addView(refresh, FrameLayout.LayoutParams(600, 1_000))
                layoutExactly(refresh, 600, 1_000)
                list = descendant(scroll)
                list.scrollTo(0, 800)
            }
            instrumentation.runOnMainSync {
                val down = SystemClock.uptimeMillis()
                dispatch(refresh, down, down, MotionEvent.ACTION_DOWN, 300f, 300f)
                assertTrue("A nested list scrolled down keeps the pull", refresh.canChildScrollUp())
                list.scrollTo(0, 0)
                assertFalse("At its top the pull may start", refresh.canChildScrollUp())
                dispatch(refresh, down, down + 16, MotionEvent.ACTION_CANCEL, 300f, 300f)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    // --- Fixtures --------------------------------------------------------------

    private data class DragResult(val routeTranslation: Float, val pagerScroll: Int)

    private inner class NavigationFixture(
        val navigation: PamNavigationHost,
        val route: ViewGroup,
    ) {
        var began = 0
        var pager: HorizontalScrollView? = null
        val width: Int get() = navigation.width

        fun dp(value: Float): Float = navigation.px(value)

        /** Drags right from [fromEdgeDp]; reads the frame before the release. */
        fun dragRight(fromEdgeDp: Float): DragResult {
            var result = DragResult(0f, 0)
            val start = dp(fromEdgeDp)
            val distance = minOf(dp(140f), width - start - 2f)
            drag(navigation, start, navigation.height / 2f, distance) {
                result = DragResult(route.translationX, pager?.scrollX ?: 0)
            }
            return result
        }
    }

    private fun withPagerRoute(
        fullScreen: Boolean,
        startPage: Int,
        blocker: Boolean = false,
        block: (NavigationFixture) -> Unit,
    ) = withRoute(fullScreen, { activity, width, height ->
        val scroll = PamScrollContainer(activity, initialHorizontal = true).apply { setPagingEnabled(true) }
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(3) {
            // Story pages take presses: the pager intercepts the drag.
            row.addView(View(activity).apply { setOnClickListener {} }, LinearLayout.LayoutParams(width, height))
        }
        scroll.insert(row)
        if (blocker) PamGestureArbiter.setBlocksNavigationGesture(scroll, true)
        scroll
    }, after = { content, width ->
        val pager = descendant<HorizontalScrollView>(content as ViewGroup)
        pager.scrollTo(width * startPage, 0)
        pager
    }, block)

    private fun withClaimingRoute(fullScreen: Boolean, claim: Int, block: (NavigationFixture) -> Unit) =
        withRoute(fullScreen, { activity, _, _ -> ClaimingView(activity, claim) }, after = { _, _ -> null }, block)

    private fun withPanRoute(direction: Int, block: (NavigationFixture) -> Unit) =
        withRoute(true, { activity, _, _ ->
            PamPressable(activity).apply {
                addView(View(activity), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                configureGesture(PamGestureConfig(2, true, 1, 1, direction, 2, 24f, 0L), callback = {})
            }
        }, after = { _, _ -> null }, block)

    private fun withRoute(
        fullScreen: Boolean,
        content: (Context, Int, Int) -> View,
        after: (View, Int) -> HorizontalScrollView?,
        block: (NavigationFixture) -> Unit,
    ) {
        val activity = launch()
        try {
            lateinit var fixture: NavigationFixture
            val width = 1_080
            val height = 1_920
            instrumentation.runOnMainSync {
                val navigation = PamNavigationHost(activity).apply {
                    operation = OPERATION_PUSH
                    transition = TRANSITION_NONE
                }
                val route = FrameLayout(activity)
                route.addView(content(activity, width, height), FrameLayout.LayoutParams(width, height))
                navigation.insert(View(activity), 0)
                navigation.insert(route, 1)
                activity.host.addView(navigation, FrameLayout.LayoutParams(width, height))
                layoutExactly(navigation, width, height)
                navigation.navigate(1)
                val created = NavigationFixture(navigation, route)
                navigation.setGestureNavigation(
                    enabled = true,
                    edgeWidth = 24f,
                    threshold = 0.35f,
                    fullScreen = fullScreen,
                    onPop = null,
                    onTransitionEnd = null,
                    onGestureStart = { created.began += 1 },
                    onGestureEnd = null,
                    onGestureCancel = null,
                )
                fixture = created
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                layoutExactly(fixture.navigation, width, height)
                fixture.pager = after(fixture.route.getChildAt(0), width)
            }
            block(fixture)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** DOWN at ([x], [y]), moves by ([dx], [dy]) in 16 ms steps, reads [beforeUp], then UP. */
    private fun drag(
        target: View,
        x: Float,
        y: Float,
        dx: Float,
        dy: Float = 0f,
        beforeUp: () -> Unit = {},
    ) {
        instrumentation.runOnMainSync {
            layoutExactly(target, target.width, target.height)
            val down = SystemClock.uptimeMillis()
            dispatch(target, down, down, MotionEvent.ACTION_DOWN, x, y)
            val steps = 24
            for (step in 1..steps) {
                val fraction = step.toFloat() / steps
                dispatch(target, down, down + step * 16L, MotionEvent.ACTION_MOVE, x + dx * fraction, y + dy * fraction)
            }
            beforeUp()
            dispatch(target, down, down + (steps + 1) * 16L, MotionEvent.ACTION_UP, x + dx, y + dy)
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(350)
        instrumentation.waitForIdleSync()
    }

    private fun dispatch(target: View, down: Long, time: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        try { target.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun layoutExactly(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(view.left, view.top, view.left + width, view.top + height)
    }

    private inline fun <reified T : View> descendant(root: ViewGroup): T {
        val pending = ArrayDeque<View>().apply { add(root) }
        while (pending.isNotEmpty()) {
            val view = pending.removeFirst()
            if (view is T && view !== root) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) pending += view.getChildAt(index)
        }
        error("No ${T::class.java.simpleName} under $root")
    }

    private fun findNavigation(root: ViewGroup): PamNavigationHost {
        lateinit var found: PamNavigationHost
        instrumentation.runOnMainSync { found = descendant(root) }
        return found
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        instrumentation.waitForIdleSync()
    }

    private fun launch(): PamTestActivity = instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    ) as PamTestActivity

    private fun View.px(dp: Float): Float = dp * resources.displayMetrics.density

    private companion object {
        const val OPERATION_PUSH = 2
        const val TRANSITION_NONE = 8
    }

    /** A view that claims every touch on DOWN, like a list for its ScrollView ancestor or a slider. */
    private class ClaimingView(context: Context, private val claim: Int) : View(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (claim == FOR_SCROLL_ANCESTOR) {
                    PamGestureArbiter.claimForScrollAncestor(this, true)
                } else {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            return super.dispatchTouchEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = true

        companion object {
            const val FOR_SCROLL_ANCESTOR = 1
            const val FIRM = 2
        }
    }
}
