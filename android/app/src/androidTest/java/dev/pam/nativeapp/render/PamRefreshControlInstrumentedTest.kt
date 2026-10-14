package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
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
import java.util.Collections

/**
 * RN `RefreshControl` parity on Android (SwipeRefreshLayout): a pull from the
 * top refreshes once, also on content shorter than the viewport; a pull that
 * starts with the content scrolled down only scrolls; `enabled=false` blocks
 * pulls; the controlled `refreshing` value always wins.
 */
@RunWith(AndroidJUnit4::class)
class PamRefreshControlInstrumentedTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun pullOnShortContentRefreshesOnceAndReturnsToTheControlledValue() {
        withRefresh(contentHeight = 120f) { refresh, events, _ ->
            pull(refresh, 320f)
            waitUntil(3_000) { events.count { it == EventKind.REFRESH.value } == 1 }
            assertEquals(1, events.count { it == EventKind.REFRESH.value })
            // The handler never set refreshing=true: the spinner ends.
            waitUntil(3_000) { !refresh.isRefreshing }
            onMain { assertFalse(refresh.isRefreshing) }
        }
    }

    @Test
    fun emptyVirtualListCanStillBePulled() {
        withRefresh(contentHeight = 0f, scrollKind = NodeKind.VIRTUAL_LIST) { refresh, events, _ ->
            pull(refresh, 320f)
            waitUntil(3_000) { events.count { it == EventKind.REFRESH.value } == 1 }
            assertEquals(1, events.count { it == EventKind.REFRESH.value })
        }
    }

    @Test
    fun pullWithContentScrolledDownOnlyScrolls() {
        withRefresh(contentHeight = 3_000f) { refresh, events, _ ->
            // Scroll the content down first, then drag down less than the offset.
            drag(refresh, fromY = 520f, toY = 120f)
            awaitFrames(instrumentation, 20)
            pull(refresh, 160f)
            awaitFrames(instrumentation, 30)
            assertEquals(0, events.count { it == EventKind.REFRESH.value })
            onMain { assertFalse(refresh.isRefreshing) }
        }
    }

    @Test
    fun disabledControlNeverStartsAPull() {
        withRefresh(contentHeight = 120f, enabled = false) { refresh, events, _ ->
            pull(refresh, 320f)
            awaitFrames(instrumentation, 30)
            assertEquals(0, events.count { it == EventKind.REFRESH.value })
        }
    }

    @Test
    fun controlledRefreshingStartsAndEndsTheSpinner() {
        withRefresh(contentHeight = 120f) { refresh, _, renderer ->
            onMain {
                renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.REFRESHING, PropValue.Flag(true)))))
            }
            waitUntil(2_000) { refresh.isRefreshing }
            onMain { assertTrue(refresh.isRefreshing) }
            onMain {
                renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.REFRESHING, PropValue.Flag(false)))))
            }
            waitUntil(2_000) { !refresh.isRefreshing }
            onMain { assertFalse(refresh.isRefreshing) }
        }
    }

    @Test
    fun handlerSettingRefreshingKeepsTheSpinnerUntilFalse() {
        withRefresh(contentHeight = 120f) { refresh, events, renderer ->
            pull(refresh, 320f)
            waitUntil(3_000) { events.contains(EventKind.REFRESH.value) }
            onMain {
                renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.REFRESHING, PropValue.Flag(true)))))
            }
            SystemClock.sleep(PamRefreshContainer.CONTROLLED_SYNC_MS + 300)
            onMain { assertTrue(refresh.isRefreshing) }
            onMain {
                renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.REFRESHING, PropValue.Flag(false)))))
            }
            waitUntil(2_000) { !refresh.isRefreshing }
            onMain { assertFalse(refresh.isRefreshing) }
        }
    }

    private fun withRefresh(
        contentHeight: Float,
        enabled: Boolean = true,
        scrollKind: NodeKind = NodeKind.SCROLL,
        block: (PamRefreshContainer, MutableList<Int>, PamRenderer) -> Unit,
    ) {
        val activity = launchActivity()
        val events = Collections.synchronizedList(mutableListOf<Int>())
        lateinit var renderer: PamRenderer
        lateinit var refresh: PamRefreshContainer
        try {
            onMain {
                renderer = PamRenderer(activity, activity.host) { _, kind, _ -> events += kind }
                val refreshProps = buildMap<PropKey, PropValue> {
                    put(PropKey.REFRESHING, PropValue.Flag(false))
                    put(PropKey.ON_REFRESH, PropValue.Flag(true))
                    if (!enabled) put(PropKey.ENABLED, PropValue.Flag(false))
                }
                val mutations = mutableListOf<Mutation>(
                    Mutation.Create(NodeSpec(1, 0, 0, NodeKind.SCREEN, emptyMap())),
                    Mutation.Create(NodeSpec(2, 1, 0, NodeKind.REFRESH_CONTROL, refreshProps)),
                    Mutation.Create(NodeSpec(3, 2, 0, scrollKind, emptyMap())),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(3, Frame(0f, 0f, 360f, 640f)),
                )
                if (contentHeight > 0f) {
                    mutations += Mutation.Create(NodeSpec(4, 3, 0, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF3366CC),
                    )))
                    mutations += Mutation.Layout(4, Frame(0f, 0f, 360f, contentHeight))
                }
                renderer.commit(listOf(mutations + Mutation.SetRoot(1)))
                @Suppress("UNCHECKED_CAST")
                val views = PamRenderer::class.java.getDeclaredField("views").apply { isAccessible = true }
                    .get(renderer) as android.util.LongSparseArray<View>
                refresh = views[2] as PamRefreshContainer
            }
            instrumentation.waitForIdleSync()
            awaitFrames(instrumentation, 4)
            block(refresh, events, renderer)
        } finally {
            onMain {
                renderer.close()
                activity.finish()
            }
        }
    }

    private fun pull(view: View, distanceDp: Float) {
        drag(view, fromY = 80f, toY = 80f + distanceDp)
    }

    private fun drag(view: View, fromY: Float, toY: Float, steps: Int = 12) {
        val location = IntArray(2)
        onMain { view.getLocationOnScreen(location) }
        val density = view.resources.displayMetrics.density
        val x = location[0] + 180f * density
        val startY = location[1] + fromY * density
        val endY = location[1] + toY * density
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, startY, 0))
        for (step in 1..steps) {
            val now = down + step * 16L
            while (SystemClock.uptimeMillis() < now) SystemClock.sleep(1)
            val y = startY + (endY - startY) * step / steps
            instrumentation.sendPointerSync(MotionEvent.obtain(down, now, MotionEvent.ACTION_MOVE, x, y, 0))
        }
        val up = down + (steps + 1) * 16L
        while (SystemClock.uptimeMillis() < up) SystemClock.sleep(1)
        instrumentation.sendPointerSync(MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, x, endY, 0))
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var met = false
            onMain { met = condition() }
            if (met) return
            awaitFrames(instrumentation)
        }
    }

    private fun launchActivity(): PamTestActivity {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val deadline = SystemClock.uptimeMillis() + 15_000
        var focused = false
        while (!focused && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            onMain { focused = activity.hasWindowFocus() }
            if (!focused) awaitFrames(instrumentation)
        }
        assertTrue("Test activity never gained window focus", focused)
        return activity
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
