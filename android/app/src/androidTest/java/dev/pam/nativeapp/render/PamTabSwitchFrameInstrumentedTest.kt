package dev.pam.nativeapp.render

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A bottom-tab switch between two kept-alive routes (Zé Chat's Conversas →
 * Feed: `navigate()` with no transition) is a reorder of mounted routes and
 * a visibility swap. It removes no view, the screen shown again records only
 * its root (HWUI keeps its display lists, styled views ignore the visibility
 * flip of their drawables) and the frame fits the 60 Hz budget.
 */
@RunWith(AndroidJUnit4::class)
class PamTabSwitchFrameInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private lateinit var host: PamNavigationHost
    private var revision = 0L
    private val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }

    /** A styled box that counts the recordings of its display list. */
    private class CountingBox(context: Context) : PamContainer(context) {
        var records = 0

        override fun dispatchDraw(canvas: Canvas) {
            records++
            super.dispatchDraw(canvas)
        }
    }

    private class Screen(val root: CountingBox, val boxes: List<CountingBox>) {
        val views = ArrayList<View>()
        fun records(): Int = boxes.sumOf { it.records }
        fun reset() {
            root.records = 0
            boxes.forEach { it.records = 0 }
        }
    }

    @Before fun setUp() {
        assumeTrue("Display lists are kept from Android 10", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        main {
            PamMotionPolicy.reduceMotionOverride = true
            host = PamNavigationHost(activity)
            activity.host.addView(host, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    @After fun tearDown() {
        if (!::activity.isInitialized) return
        main {
            PamMotionPolicy.reduceMotionOverride = null
            activity.finish()
        }
    }

    @Test fun aTabSwitchRemovesNothingRecordsOnlyTheRootAndFitsAFrame() {
        val inbox = heavyScreen(Color.WHITE)
        val feed = heavyScreen(Color.rgb(15, 20, 16))
        visitBothTabs(inbox, feed)

        val detached = ArrayList<View>()
        val watcher = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { detached += view }
        }
        main {
            (inbox.views + feed.views).forEach { it.addOnAttachStateChangeListener(watcher) }
            inbox.reset()
            feed.reset()
        }
        val frames = recordFrames {
            // What the commit of navigate('feed') does: the parked feed moves
            // above the inbox and the host shows it without a transition.
            switchTo(feed.root, index = 1)
            await("the feed is shown") { feed.root.visibility == View.VISIBLE && inbox.root.visibility == View.INVISIBLE }
            waitFrames(4)
        }
        main {
            assertEquals("No route left the host", 2, host.childCount)
            assertTrue("No view was detached: $detached", detached.isEmpty())
            assertTrue("The feed recorded its root again", feed.root.records >= 1)
            assertEquals("The feed shown again recorded none of its ${feed.boxes.size} styled views", 0, feed.records())
            assertEquals("The hidden inbox recorded nothing either", 0, inbox.records())
            (inbox.views + feed.views).forEach { it.removeOnAttachStateChangeListener(watcher) }
        }
        val budgetNs = 16_666_667L
        Log.i(TAG, "tab switch frames (ui+issue ms): " + frames.joinToString { "%.2f".format(it.cpu / 1e6) })
        assertTrue("The switch drew at least one frame", frames.isNotEmpty())
        frames.forEach { frame ->
            assertTrue(
                "Every frame of the switch fits 60 Hz on the UI and render threads (${frame.cpu / 1e6} ms)",
                frame.cpu < budgetNs,
            )
        }
    }

    /**
     * Control: a route holding a SurfaceView is not kept drawn at zero alpha
     * (its surface would follow it), so HWUI drops its display lists while
     * hidden and it records again when shown, as every hidden route did.
     */
    @Test fun aRouteWithASurfaceIsNotKeptAndRecordsAgain() {
        val inbox = heavyScreen(Color.WHITE)
        val camera = heavyScreen(Color.BLACK)
        main { camera.root.addView(SurfaceView(activity), FrameLayout.LayoutParams(40, 40, Gravity.BOTTOM)) }
        visitBothTabs(inbox, camera)
        main {
            assertEquals("Only the route without a surface is kept", 0, host.retainedHiddenRoutes())
            camera.reset()
        }
        switchTo(camera.root, index = 1)
        await("the route is shown") { camera.root.visibility == View.VISIBLE }
        waitFrames(4)
        main { assertTrue("Not kept, it records its views again", camera.records() > 0) }
    }

    /** Only the two routes shown last stay kept; an older one records again when shown. */
    @Test fun onlyTheTwoRoutesShownLastAreKept() {
        val screens = listOf(Color.WHITE, Color.LTGRAY, Color.GRAY, Color.DKGRAY).map(::heavyScreen)
        main { host.insert(screens[0].root, 0) }
        await("first route laid out") { screens[0].root.isLaidOut }
        // Tabs visited one after the other: A, B, C, D.
        for (index in 1..3) {
            push(screens[index].root, index)
            await("route $index shown") { screens[index].root.visibility == View.VISIBLE }
            waitFrames(3)
        }
        main {
            assertEquals(2, host.retainedHiddenRoutes())
            screens.forEach(Screen::reset)
        }
        // A was shown before B and C: no longer kept, it records again.
        switchTo(screens[0].root, index = 3)
        await("A shown") { screens[0].root.visibility == View.VISIBLE }
        waitFrames(4)
        main {
            assertTrue("The oldest hidden route was not kept", screens[0].records() > 0)
            screens.forEach(Screen::reset)
        }
        // Hidden now, most recent first: D, C, B. C is kept.
        switchTo(screens[2].root, index = 3)
        await("C shown") { screens[2].root.visibility == View.VISIBLE }
        waitFrames(4)
        main { assertEquals("A route among the two shown last is kept", 0, screens[2].records()) }
    }

    /** Inbox shown, feed pushed and shown, back to the inbox with the feed parked below it. */
    private fun visitBothTabs(inbox: Screen, feed: Screen) {
        main { host.insert(inbox.root, 0) }
        await("inbox laid out") { inbox.root.isLaidOut }
        push(feed.root, 1)
        await("feed shown") { feed.root.visibility == View.VISIBLE }
        waitFrames(3)
        main { assertTrue("A first visit records every view", feed.records() >= feed.boxes.size) }
        // navigate('inbox'): the feed pops, then (transition end) is parked under the inbox.
        main {
            host.operation = PamNavigationHost.OPERATION_POP
            host.transition = PamNavigationHost.TRANSITION_NONE
            host.durationMs = 0L
            host.navigate(++revision)
        }
        await("inbox shown again") { inbox.root.visibility == View.VISIBLE && feed.root.visibility == View.INVISIBLE }
        main { host.reorderRoute(feed.root, 0) }
        waitFrames(4)
    }

    private fun push(route: View, index: Int) = main {
        host.operation = PamNavigationHost.OPERATION_PUSH
        host.transition = PamNavigationHost.TRANSITION_NONE
        host.durationMs = 0L
        host.insert(route, index)
        host.navigate(++revision)
    }

    private fun switchTo(route: View, index: Int) = main {
        host.reorderRoute(route, index)
        host.operation = PamNavigationHost.OPERATION_PUSH
        host.transition = PamNavigationHost.TRANSITION_NONE
        host.durationMs = 0L
        host.navigate(++revision)
    }

    /** A screen of styled rows: rounded boxes with text and an image, like a feed or a chat list. */
    private fun heavyScreen(background: Int): Screen {
        lateinit var screen: Screen
        main {
            val metrics = activity.resources.displayMetrics
            val density = metrics.density
            val root = CountingBox(activity).apply { this.background = ColorDrawable(background) }
            val boxes = ArrayList<CountingBox>()
            val views = ArrayList<View>()
            // Every box inside the window: an offscreen one is never drawn.
            val rowHeight = metrics.heightPixels / (ROWS + 2)
            val columnWidth = metrics.widthPixels / COLUMNS
            for (row in 0 until ROWS) {
                for (column in 0 until COLUMNS) {
                    val box = CountingBox(activity).apply {
                        this.background = GradientDrawable().apply {
                            cornerRadius = 8 * density
                            setColor(Color.rgb((40 + row * 7) % 255, 120, (60 + column * 30) % 255))
                            setStroke(density.toInt().coerceAtLeast(1), Color.DKGRAY)
                        }
                    }
                    box.addView(
                        PamTextView(activity).apply {
                            text = "Row $row · $column"
                            textSize = 10f
                            setTextColor(Color.WHITE)
                        },
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
                    )
                    box.addView(
                        PamImageView(activity).apply { setImageDrawable(BitmapDrawable(activity.resources, bitmap)) },
                        FrameLayout.LayoutParams((12 * density).toInt(), (12 * density).toInt(), Gravity.START or Gravity.TOP),
                    )
                    root.addView(
                        box,
                        FrameLayout.LayoutParams(columnWidth, rowHeight, Gravity.START or Gravity.TOP).apply {
                            leftMargin = column * columnWidth
                            topMargin = row * rowHeight
                        },
                    )
                    boxes += box
                    views += box
                    for (index in 0 until box.childCount) views += box.getChildAt(index)
                }
            }
            screen = Screen(root, boxes)
            screen.views += root
            screen.views += views
        }
        return screen
    }

    private class FrameCost(val cpu: Long)

    /** Frames drawn while [block] runs, with their UI-thread and command-issue time. */
    private fun recordFrames(block: () -> Unit): List<FrameCost> {
        val frames = ArrayList<FrameCost>()
        val thread = HandlerThread("pam-tab-switch-metrics").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val cpu = metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                metrics.getMetric(FrameMetrics.DRAW_DURATION) +
                metrics.getMetric(FrameMetrics.SYNC_DURATION) +
                metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)
            synchronized(frames) { frames += FrameCost(cpu) }
        }
        main { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        try {
            block()
            SystemClock.sleep(200)
        } finally {
            main { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
        return synchronized(frames) { frames.toList() }
    }

    private fun waitFrames(count: Int) {
        val latch = CountDownLatch(count)
        main {
            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    latch.countDown()
                    if (latch.count > 0) {
                        host.invalidate()
                        Choreographer.getInstance().postFrameCallback(this)
                    }
                }
            }
            Choreographer.getInstance().postFrameCallback(callback)
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
    }

    private fun await(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var met = false
            main { met = condition() }
            if (met) return
            SystemClock.sleep(16)
        }
        throw AssertionError("Timed out: $what")
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val TAG = "PamTabSwitch"
        const val ROWS = 24
        const val COLUMNS = 6
    }
}
