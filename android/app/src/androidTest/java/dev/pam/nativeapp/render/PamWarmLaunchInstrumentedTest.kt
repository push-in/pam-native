package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * A press declaring the destination a navigator holds warm
 * (`Pressable::prewarm()`/`navigatesTo()`) starts the push natively, on the
 * frame after the tap, before the runtime handled the press; the commit of
 * that push adopts the running animation instead of starting it again.
 */
@RunWith(AndroidJUnit4::class)
class PamWarmLaunchInstrumentedTest {
    @get:Rule
    val animations = PamAnimationsEnabledRule()

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private lateinit var navigation: PamNavigationHost
    private lateinit var current: FrameLayout
    private lateinit var warm: View
    private lateinit var press: PamPressable
    private val transitionEnds = AtomicInteger()
    private var revision = 1L

    @Before
    fun setUp() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        main {
            PamMotionPolicy.reduceMotionOverride = false
            navigation = PamNavigationHost(activity).apply {
                setGestureNavigation(
                    enabled = false,
                    edgeWidth = 24f,
                    threshold = 0.35f,
                    onPop = null,
                    onTransitionEnd = { transitionEnds.incrementAndGet() },
                    onGestureStart = null,
                    onGestureEnd = null,
                    onGestureCancel = null,
                )
            }
            activity.host.addView(navigation, FrameLayout.LayoutParams(WIDTH, HEIGHT))
            press = PamPressable(activity)
            current = FrameLayout(activity).apply { addView(press, FrameLayout.LayoutParams(200, 120)) }
            navigation.insert(current, 0)
            navigation.navigate(revision)
        }
        awaitFrames(instrumentation, 2)
        main {
            // Navigator::prewarm(): mounted hidden under the current route,
            // advertised with its push motion.
            warm = View(activity)
            navigation.insert(warm, 0)
            navigation.warmIntent = INTENT
            navigation.warmTransition = TRANSITION_SLIDE_FROM_RIGHT
            navigation.warmDurationMs = DURATION_MS
            press.navigationIntent = INTENT
        }
        awaitFrames(instrumentation, 3)
        main {
            assertEquals(View.INVISIBLE, warm.visibility)
            assertEquals(WIDTH, warm.width)
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
    fun pressStartsTheWarmPushOnTheNextFrameAndItsCommitAdoptsIt() {
        main {
            press.performClick()
            assertTrue("the press starts the push without a commit", navigation.isLaunchingWarm())
            assertEquals(View.VISIBLE, warm.visibility)
            assertTrue("the active route changes only on the commit", navigation.isActiveRoute(current))
        }
        // The animator's first frame pins its start; the next one moves the route.
        awaitFrames(instrumentation, 2)
        var beforeCommit = 0f
        main {
            beforeCommit = warm.translationX
            assertTrue("the warm route is moving in: $beforeCommit", beforeCommit < WIDTH && beforeCommit > 0f)
            // The commit of the push: the route moves to the top, a new revision.
            navigation.reorderRoute(warm, 1)
            navigation.operation = OPERATION_PUSH
            navigation.transition = TRANSITION_SLIDE_FROM_RIGHT
            navigation.durationMs = DURATION_MS
            navigation.warmIntent = null
            navigation.navigate(++revision)
        }
        awaitFrames(instrumentation)
        main {
            assertFalse(navigation.isLaunchingWarm())
            assertTrue("the commit makes the route active", navigation.isActiveRoute(warm))
            assertTrue(
                "the running push continues instead of starting again: ${warm.translationX} after $beforeCommit",
                warm.translationX < beforeCommit,
            )
        }
        awaitCondition("the adopted push ends once") { transitionEnds.get() == 1 }
        main {
            assertEquals(0f, warm.translationX)
            assertEquals(View.VISIBLE, warm.visibility)
            assertEquals(View.INVISIBLE, current.visibility)
        }
        awaitFrames(instrumentation, 3)
        assertEquals("no second transition", 1, transitionEnds.get())
    }

    @Test
    fun aPressThatNavigatesNowhereSlidesBack() {
        main { press.performClick() }
        awaitCondition("the unconfirmed push settles back", timeoutMs = DURATION_MS * 2 + 2_500L) {
            !navigation.isLaunchingWarm() && warm.visibility == View.INVISIBLE
        }
        awaitFrames(instrumentation)
        main {
            assertEquals(0, transitionEnds.get())
            assertTrue(navigation.isActiveRoute(current))
            assertEquals(View.VISIBLE, current.visibility)
            assertEquals(0f, warm.translationX)
            assertEquals(1f, current.alpha)
            assertEquals(0f, current.translationX)
        }
    }

    @Test
    fun anotherNavigationDropsTheLaunch() {
        lateinit var other: View
        main { press.performClick() }
        awaitFrames(instrumentation, 2)
        main {
            // The press pushed another route: the warm one was released.
            other = View(activity)
            navigation.removeRoute(warm)
            navigation.insert(other, 1)
            navigation.operation = OPERATION_PUSH
            navigation.transition = TRANSITION_NONE
            navigation.warmIntent = null
            navigation.navigate(++revision)
            assertFalse(navigation.isLaunchingWarm())
        }
        awaitCondition("the other push ends") { transitionEnds.get() == 1 }
        main {
            assertTrue(navigation.isActiveRoute(other))
            assertEquals(View.VISIBLE, other.visibility)
            assertEquals(View.INVISIBLE, current.visibility)
            assertEquals(0f, current.translationX)
        }
    }

    @Test
    fun anotherIntentOrAPendingNavigationDoesNotLaunch() {
        main {
            press.navigationIntent = "other"
            press.performClick()
            assertFalse(navigation.isLaunchingWarm())
            press.navigationIntent = INTENT
            navigation.warmDurationMs = 0L
            press.performClick()
            assertFalse("no motion: wait for the commit", navigation.isLaunchingWarm())
            assertEquals(View.INVISIBLE, warm.visibility)
        }
    }

    @Test
    fun theCommitOfAWarmPushStartsItsTransitionInTheCommitFrame() {
        var runningAtFirstDraw = false
        main {
            navigation.reorderRoute(warm, 1)
            navigation.operation = OPERATION_PUSH
            navigation.transition = TRANSITION_SLIDE_FROM_RIGHT
            navigation.durationMs = DURATION_MS
            navigation.navigate(++revision)
            navigation.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    runningAtFirstDraw = warm.visibility == View.VISIBLE && navigation.isActiveRoute(warm)
                    navigation.post { navigation.viewTreeObserver.removeOnDrawListener(this) }
                }
            })
        }
        awaitFrames(instrumentation)
        main { assertTrue("a warm route needs no extra layout frame", runningAtFirstDraw) }
        awaitCondition("the push ends") { transitionEnds.get() == 1 }
    }

    private fun awaitCondition(message: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            var met = false
            main { met = condition() }
            if (met) return
            check(SystemClock.uptimeMillis() < deadline) { message }
            awaitFrames(instrumentation)
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val WIDTH = 1_080
        const val HEIGHT = 1_920
        const val INTENT = "chat-7"
        const val DURATION_MS = 300L
        const val OPERATION_PUSH = 2
        const val TRANSITION_SLIDE_FROM_RIGHT = 2
        const val TRANSITION_NONE = 8
    }
}
