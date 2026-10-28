package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Every way a transition or a back gesture can end (finished, cancelled,
 * interrupted by another navigation, released early, the app going to the
 * background) leaves every route at rest: alpha 1, no translation, scale or
 * rotation. A route left at the gesture's 0.82–1 alpha over the window's
 * dark background showed the whole screen ~8% darker until a relaunch.
 */
@RunWith(AndroidJUnit4::class)
class PamNavigationRestInstrumentedTest {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private lateinit var navigation: PamNavigationHost
    private val routes = ArrayList<View>()
    private val gestureStarts = AtomicInteger()
    private val gestureCancels = AtomicInteger()
    private val transitionEnds = AtomicInteger()
    private var revision = 0L

    @Before
    fun setUp() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        main {
            PamMotionPolicy.reduceMotionOverride = false
            navigation = PamNavigationHost(activity)
            activity.host.addView(navigation, FrameLayout.LayoutParams(1_080, 1_920))
            configureGesture(fullScreen = false)
            navigation.operation = OPERATION_PUSH
            navigation.transition = TRANSITION_NONE
            route(0)
            route(1)
            navigation.navigate(++revision)
        }
        instrumentation.waitForIdleSync()
        main {
            assertEquals(View.VISIBLE, routes[1].visibility)
            assertEquals(View.INVISIBLE, routes[0].visibility)
        }
    }

    @After
    fun tearDown() {
        main {
            PamMotionPolicy.reduceMotionOverride = null
            activity.host.removeAllViews()
            activity.finish()
        }
    }

    @Test
    fun aTapWhereABackGestureCanStartChangesNothing() {
        main { configureGesture(fullScreen = true) }
        touch(MotionEvent.ACTION_DOWN, 300f)
        touch(MotionEvent.ACTION_UP, 300f)
        main {
            assertEquals("a tap is not a gesture", 0, gestureStarts.get())
            assertEquals(View.INVISIBLE, routes[0].visibility)
            assertTrue(navigation.isActiveRoute(routes[1]))
            assertAtRest()
        }
    }

    @Test
    fun aVerticalScrollIsNotABackGesture() {
        main { configureGesture(fullScreen = true) }
        touch(MotionEvent.ACTION_DOWN, 300f, 900f)
        touch(MotionEvent.ACTION_MOVE, 330f, 600f)
        touch(MotionEvent.ACTION_MOVE, 420f, 300f)
        touch(MotionEvent.ACTION_UP, 420f, 300f)
        main {
            assertEquals(0, gestureStarts.get())
            assertEquals(View.INVISIBLE, routes[0].visibility)
            assertAtRest()
        }
    }

    @Test
    fun anEdgeDragReleasedEarlyRestoresBothRoutes() {
        drag(to = 200f)
        main { assertTrue("the gesture fades the route below", routes[0].alpha < 1f) }
        touch(MotionEvent.ACTION_UP, 200f)
        awaitRest()
        main {
            assertEquals(1, gestureCancels.get())
            assertEquals(View.INVISIBLE, routes[0].visibility)
            assertTrue(navigation.isActiveRoute(routes[1]))
        }
    }

    @Test
    fun aCancelledEdgeDragRestoresBothRoutes() {
        drag(to = 500f)
        touch(MotionEvent.ACTION_CANCEL, 500f)
        awaitRest()
        main { assertEquals(View.INVISIBLE, routes[0].visibility) }
    }

    @Test
    fun aNavigationDuringADragRestoresEveryRoute() {
        drag(to = 400f)
        val ended = CountDownLatch(1)
        main {
            configureGesture(fullScreen = false) { ended.countDown() }
            navigation.transition = TRANSITION_SLIDE_FROM_RIGHT
            navigation.durationMs = 200
            route(2)
            navigation.navigate(++revision)
        }
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        // The finger lifts after the push took over: nothing settles.
        touch(MotionEvent.ACTION_UP, 600f)
        awaitRest()
        main {
            assertEquals(1, gestureCancels.get())
            assertEquals(View.VISIBLE, routes[2].visibility)
            assertTrue(navigation.isActiveRoute(routes[2]))
        }
    }

    @Test
    fun aNavigationWhileAGestureSettlesRestoresEveryRoute() {
        drag(to = 300f)
        val ended = CountDownLatch(1)
        main {
            navigation.dispatchTouchEvent(event(MotionEvent.ACTION_UP, 300f))
            // The settle animation is running; a push arrives in the same frame.
            configureGesture(fullScreen = false) { ended.countDown() }
            navigation.transition = TRANSITION_FADE
            navigation.durationMs = 200
            route(2)
            navigation.navigate(++revision)
        }
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        awaitRest()
        main { assertTrue(navigation.isActiveRoute(routes[2])) }
    }

    @Test
    fun aTransitionInterruptedByAnotherNavigationEndsAtRest() {
        main {
            navigation.transition = TRANSITION_SLIDE_FROM_RIGHT
            navigation.durationMs = 1_500
            route(2)
            navigation.navigate(++revision)
        }
        SystemClock.sleep(120)
        main {
            assertTrue("the push is running", routes[1].alpha < 1f || routes[2].translationX > 0f)
            navigation.operation = OPERATION_POP
            navigation.transition = TRANSITION_FADE
            navigation.durationMs = 150
            navigation.navigate(++revision)
        }
        SystemClock.sleep(80)
        main {
            navigation.operation = OPERATION_PUSH
            navigation.transition = TRANSITION_SCALE
            navigation.navigate(++revision)
        }
        awaitRest()
    }

    @Test
    fun goingToTheBackgroundMidTransitionEndsItAtRest() {
        main {
            navigation.transition = TRANSITION_SLIDE_FROM_RIGHT
            navigation.durationMs = 2_000
            route(2)
            navigation.navigate(++revision)
        }
        SystemClock.sleep(150)
        main {
            val ends = transitionEnds.get()
            navigation.dispatchWindowVisibilityChanged(View.GONE)
            assertEquals("the transition ended at once", ends + 1, transitionEnds.get())
            assertAtRest()
            assertEquals(View.VISIBLE, routes[2].visibility)
            navigation.dispatchWindowVisibilityChanged(View.VISIBLE)
        }
    }

    @Test
    fun goingToTheBackgroundMidGestureRestoresBothRoutes() {
        drag(to = 450f)
        main {
            navigation.dispatchWindowVisibilityChanged(View.GONE)
            assertAtRest()
            assertEquals(1, gestureCancels.get())
            assertTrue(navigation.isActiveRoute(routes[1]))
            navigation.dispatchWindowVisibilityChanged(View.VISIBLE)
        }
        touch(MotionEvent.ACTION_UP, 450f)
        awaitRest()
    }

    private fun configureGesture(fullScreen: Boolean, onEnd: (() -> Unit)? = null) {
        navigation.setGestureNavigation(
            enabled = true,
            edgeWidth = 24f,
            threshold = 0.35f,
            fullScreen = fullScreen,
            onPop = null,
            onTransitionEnd = {
                transitionEnds.incrementAndGet()
                onEnd?.invoke()
            },
            onGestureStart = { gestureStarts.incrementAndGet() },
            onGestureEnd = null,
            onGestureCancel = { gestureCancels.incrementAndGet() },
        )
    }

    private fun route(index: Int) {
        val view = View(activity).apply {
            setBackgroundColor(Color.WHITE)
            // A clickable screen takes the touch, so the host only sees the
            // gesture through onInterceptTouchEvent.
            isClickable = true
        }
        routes += view
        navigation.insert(view, index)
    }

    private fun drag(to: Float) {
        touch(MotionEvent.ACTION_DOWN, 10f)
        val steps = 6
        for (step in 1..steps) touch(MotionEvent.ACTION_MOVE, 10f + (to - 10f) * step / steps)
        main { assertEquals("the edge drag started a gesture", 1, gestureStarts.get()) }
    }

    private var downTime = 0L
    private var eventTime = 0L

    /** A slow finger: 100 ms between events, so release velocity stays low. */
    private fun event(action: Int, x: Float, y: Float = 900f): MotionEvent {
        if (action == MotionEvent.ACTION_DOWN) {
            downTime = SystemClock.uptimeMillis()
            eventTime = downTime
        } else {
            eventTime += 100
        }
        return MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
    }

    private fun touch(action: Int, x: Float, y: Float = 900f) {
        main {
            val event = event(action, x, y)
            navigation.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun assertAtRest() {
        routes.forEachIndexed { index, route ->
            assertEquals("route $index alpha", 1f, route.alpha, 0f)
            assertEquals("route $index translationX", 0f, route.translationX, 0f)
            assertEquals("route $index translationY", 0f, route.translationY, 0f)
            assertEquals("route $index scaleX", 1f, route.scaleX, 0f)
            assertEquals("route $index scaleY", 1f, route.scaleY, 0f)
            assertEquals("route $index rotationY", 0f, route.rotationY, 0f)
        }
    }

    private fun awaitRest() {
        val deadline = SystemClock.uptimeMillis() + 3_000
        var failure: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            failure = null
            main { failure = runCatching { assertAtRest() }.exceptionOrNull() }
            if (failure == null) return
            SystemClock.sleep(16)
        }
        throw failure!!
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val OPERATION_PUSH = 2
        const val OPERATION_POP = 3
        const val TRANSITION_SLIDE_FROM_RIGHT = 2
        const val TRANSITION_FADE = 5
        const val TRANSITION_SCALE = 7
        const val TRANSITION_NONE = 8
    }
}
