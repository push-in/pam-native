package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * A slide only darkens the route it leaves toward the backdrop (18%
 * horizontal, 12% vertical). Over one known opaque backdrop the host draws
 * that color over the route instead of fading it: the same pixels with no
 * hardware layer, whose first paint cost a 30 ms frame on a Galaxy S10.
 */
@RunWith(AndroidJUnit4::class)
class PamTransitionVeilInstrumentedTest {
    @get:Rule
    val animations = PamAnimationsEnabledRule()

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private lateinit var navigation: PamNavigationHost
    private val routes = ArrayList<View>()
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
            activity.window.setBackgroundDrawable(ColorDrawable(BACKDROP))
            navigation = PamNavigationHost(activity)
            activity.host.addView(
                navigation,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            navigation.setGestureNavigation(
                enabled = true,
                edgeWidth = 24f,
                threshold = 0.35f,
                onPop = null,
                onTransitionEnd = { transitionEnds.incrementAndGet() },
                onGestureStart = null,
                onGestureEnd = null,
                onGestureCancel = null,
            )
            navigation.operation = OPERATION_PUSH
            navigation.transition = TRANSITION_NONE
            route(0, ROUTE_COLOR)
            navigation.navigate(++revision)
        }
        awaitFrames(instrumentation, 2)
        // Counted from here: the initial route's own (instant) navigation ended.
        transitionEnds.set(0)
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
    fun aHorizontalPushDimsTheRouteBelowUnderAVeilWithoutALayer() {
        val frames = samplePush(TRANSITION_SLIDE_FROM_RIGHT) { incoming ->
            1f - incoming.translationX / navigation.width
        }
        assertTrue("sampled the push in flight: $frames", frames >= 3)
        main { assertAtRest() }
    }

    @Test
    fun aVerticalPushDimsTheRouteBelowUnderAVeilToo() {
        val frames = samplePush(TRANSITION_SLIDE_FROM_BOTTOM, dim = 0.12f) { incoming ->
            1f - incoming.translationY / navigation.height
        }
        assertTrue("sampled the push in flight: $frames", frames >= 3)
        main { assertAtRest() }
    }

    @Test
    fun aPopKeepsTheLayerOfTheRouteThatFadesOverTheOneBelow() {
        main {
            route(1, Color.WHITE)
            navigation.navigate(++revision)
        }
        awaitFrames(instrumentation, 3)
        var sampled = 0
        val failures = ArrayList<String>()
        val sample = ViewTreeObserver.OnPreDrawListener {
            if (navigation.transitionAnimator() != null) {
                sampled++
                // Over the incoming route, not over the backdrop: no veil.
                if (navigation.veiledRoute() != null) failures += "a veil"
                if (navigation.layeredRoute() !== routes[1]) failures += "no layer on the top route"
                if (routes[1].layerType != View.LAYER_TYPE_HARDWARE) failures += "layer ${routes[1].layerType}"
            }
            true
        }
        main {
            navigation.viewTreeObserver.addOnPreDrawListener(sample)
            navigation.operation = OPERATION_POP
            navigation.transition = TRANSITION_SLIDE_FROM_RIGHT
            navigation.durationMs = DURATION_MS
            navigation.navigate(++revision)
        }
        awaitEnds(2)
        main {
            navigation.viewTreeObserver.removeOnPreDrawListener(sample)
            assertTrue(sampled > 0)
            assertTrue(failures.distinct().joinToString(), failures.isEmpty())
            assertEquals(View.LAYER_TYPE_NONE, routes[1].layerType)
        }
    }

    @Test
    fun anUnknownBackdropKeepsTheLayer() {
        main {
            // A gradient paints the host's box: not one known color.
            navigation.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(BACKDROP, Color.BLACK),
            )
            route(1, Color.WHITE)
            navigation.transition = TRANSITION_SLIDE_FROM_RIGHT
            navigation.durationMs = DURATION_MS
            navigation.navigate(++revision)
        }
        awaitCondition("the route below fades itself") { routes[0].alpha < 1f }
        main {
            assertNull(navigation.veiledRoute())
            assertEquals(View.LAYER_TYPE_HARDWARE, routes[0].layerType)
        }
        awaitEnds(1)
        main { assertAtRest() }
    }

    @Test
    fun theBackdropIsTheFirstFillUpTheTreeAndOnlyAnOpaqueOne() {
        main {
            val route = routes[0]
            val decor = activity.window.decorView
            assertSame("the window's background", decor, navigation.backdropOf(route, 0f, 0f))

            navigation.background = ColorDrawable(Color.RED)
            assertSame("the host's own background first", navigation, navigation.backdropOf(route, 0f, 0f))

            navigation.background = ColorDrawable(0x80FF0000.toInt())
            assertNull("a translucent fill blends with what is behind it", navigation.backdropOf(route, 0f, 0f))

            navigation.background = ColorDrawable(Color.TRANSPARENT)
            assertSame("a transparent fill paints nothing", decor, navigation.backdropOf(route, 0f, 0f))

            // A renderer plain fill (tagged) counts; any other shape does not.
            val plain = GradientDrawable().apply { setColor(Color.GREEN) }
            navigation.background = plain
            assertNull(navigation.backdropOf(route, 0f, 0f))
            navigation.setTag(dev.pam.nativeapp.R.id.pam_plain_fill, plain)
            assertSame(navigation, navigation.backdropOf(route, 0f, 0f))
            navigation.setTag(dev.pam.nativeapp.R.id.pam_plain_fill, null)
            navigation.background = null

            // Something drawn before the host where the route moves.
            val below = View(activity).apply { setBackgroundColor(Color.BLUE) }
            activity.host.addView(below, 0, FrameLayout.LayoutParams(100, 100))
            below.layout(0, 0, 100, 100)
            assertNull(navigation.backdropOf(route, 0f, 0f))
            below.visibility = View.INVISIBLE
            assertSame(decor, navigation.backdropOf(route, 0f, 0f))
            activity.host.removeView(below)

            activity.window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            assertNull("a see-through window", navigation.backdropOf(route, 0f, 0f))
            activity.window.setBackgroundDrawable(ColorDrawable(BACKDROP))
        }
    }

    @Test
    fun aBackGestureVeilsAGroupCompositedRouteAndKeepsThePerDrawAlphaOfAPamScreen() {
        main {
            route(1, Color.WHITE)
            navigation.navigate(++revision)
        }
        awaitFrames(instrumentation, 3)
        main {
            assertTrue(navigation.startPredictiveBack())
            navigation.updatePredictiveBack(0.5f)
            assertSame(routes[0], navigation.veiledRoute())
            assertEquals(1f, routes[0].alpha)
            assertEquals(0.18f * 0.5f, navigation.veilAmount(), 1e-4f)
            assertEquals(BACKDROP, navigation.veilColor())
            navigation.cancelPredictiveBack()
        }
        awaitRest()

        // A PAM screen applies its alpha to each draw (no offscreen pass):
        // the gesture keeps dimming it by alpha.
        lateinit var screen: PamContainer
        main {
            screen = PamContainer(activity).apply { setBackgroundColor(ROUTE_COLOR) }
            routes += screen
            // Right under the top route: the one a back gesture reveals.
            navigation.insert(screen, 1)
        }
        awaitFrames(instrumentation, 3)
        main {
            assertTrue(navigation.startPredictiveBack())
            navigation.updatePredictiveBack(0.5f)
            assertNull(navigation.veiledRoute())
            assertEquals(1f - 0.18f * 0.5f, screen.alpha, 1e-4f)
            navigation.cancelPredictiveBack()
        }
        awaitRest()
    }

    /**
     * The veil gives the pixels the faded layer gave: the same push frozen
     * at the same fraction, once over the window's color (veil) and once
     * over a gradient of that same color (unknown backdrop: the old layer).
     * A vertical push leaves the route below in place: every pixel, text
     * included, matches.
     */
    @Test
    fun theVeilDrawsThePixelsOfTheFadedLayer() {
        val result = compareFrozenPush(TRANSITION_SLIDE_FROM_BOTTOM, flatOnly = false)
        assertTrue(result.message, result.worst <= 1)
        assertTrue(result.message, channelDelta(result.spot, result.expectedSpot) <= 1)
    }

    /**
     * A horizontal push moves the route below by a fractional offset: the
     * layer was a bitmap resampled there, the veiled route draws its glyph
     * and shape edges right at the offset (as every route at rest does), so
     * only edge pixels may differ; every flat area matches.
     */
    @Test
    fun aHorizontalVeilMatchesTheFadedLayerOnEveryFlatArea() {
        val result = compareFrozenPush(TRANSITION_SLIDE_FROM_RIGHT, flatOnly = true)
        assertTrue(result.message, result.worst <= 1)
        assertTrue(result.message, channelDelta(result.spot, result.expectedSpot) <= 1)
    }

    private class Comparison(val worst: Int, val spot: Int, val expectedSpot: Int, val message: String)

    private fun compareFrozenPush(kind: Int, flatOnly: Boolean): Comparison {
        main {
            routes[0].let { it as FrameLayout }.addView(
                TextView(activity).apply {
                    text = "Configurações — Conta, Privacidade, Notificações"
                    textSize = 22f
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 400, Gravity.TOP).apply {
                    topMargin = 120
                },
            )
            route(1, Color.WHITE)
        }
        val veiled = frozenPush(kind, sameColorGradient = false)
        main {
            assertSame(routes[0], navigation.veiledRoute())
            assertEquals(View.LAYER_TYPE_NONE, routes[0].layerType)
        }
        val amount = run { var value = 0f; main { value = navigation.veilAmount() }; value }
        finishFrozen(1)

        main {
            // Back to the first route, then the same push over an unknown backdrop.
            navigation.removeRoute(routes[1])
            routes.removeAt(1)
            navigation.operation = OPERATION_PUSH
            navigation.transition = TRANSITION_NONE
            navigation.navigate(++revision)
        }
        awaitFrames(instrumentation, 3)
        main { route(1, Color.WHITE) }
        val layered = frozenPush(kind, sameColorGradient = true)
        main {
            assertNull(navigation.veiledRoute())
            assertEquals(View.LAYER_TYPE_HARDWARE, routes[0].layerType)
            assertEquals(1f - amount, routes[0].alpha, 1e-5f)
        }
        finishFrozen(3)

        assertEquals(veiled.width, layered.width)
        var compared = 0
        var differing = 0
        var worst = 0
        val box = Rect(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1)
        var sample = ""
        for (y in 1 until veiled.height - 1) {
            for (x in 1 until veiled.width - 1) {
                if (flatOnly && !(flat(veiled, x, y) && flat(layered, x, y))) continue
                compared++
                val a = veiled.getPixel(x, y)
                val b = layered.getPixel(x, y)
                val delta = channelDelta(a, b)
                if (delta > 0) {
                    differing++
                    box.union(x, y)
                }
                if (delta > worst) {
                    worst = delta
                    sample = "($x,$y) ${Integer.toHexString(a)} vs ${Integer.toHexString(b)}"
                }
            }
        }
        // A solid spot of the route below: (1 - d)·route + d·backdrop.
        val spot = veiled.getPixel(veiled.width / 20, 60)
        val expected = blend(ROUTE_COLOR, BACKDROP, amount)
        val message = "dim $amount: worst channel delta $worst over $differing of $compared pixels in $box, " +
            "worst at $sample; spot ${Integer.toHexString(spot)} vs ${Integer.toHexString(expected)}"
        veiled.recycle()
        layered.recycle()
        android.util.Log.i("PamTransitionVeil", message)
        return Comparison(worst, spot, expected, message)
    }

    /** Whether the 3x3 neighbourhood of ([x], [y]) is one color (within 1). */
    private fun flat(bitmap: Bitmap, x: Int, y: Int): Boolean {
        val center = bitmap.getPixel(x, y)
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (channelDelta(bitmap.getPixel(x + dx, y + dy), center) > 1) return false
            }
        }
        return true
    }

    /** Pushes a new route with [kind] and checks every frame of it; returns the frames sampled. */
    private fun samplePush(kind: Int, dim: Float = 0.18f, progressOf: (View) -> Float): Int {
        val sampled = AtomicInteger()
        val failures = ArrayList<String>()
        lateinit var incoming: View
        val sample = ViewTreeObserver.OnPreDrawListener {
            if (navigation.transitionAnimator() != null) {
                val below = routes[0]
                val progress = progressOf(incoming)
                val expected = dim * progress
                val amount = navigation.veilAmount()
                if (navigation.veiledRoute() !== below) failures += "no veil over the route below"
                if (below.layerType != View.LAYER_TYPE_NONE) failures += "a layer on the route below"
                if (below.alpha != 1f) failures += "alpha ${below.alpha}"
                if (navigation.veilColor() != BACKDROP) failures += "veil ${Integer.toHexString(navigation.veilColor())}"
                if (abs(amount - expected) > 1e-3f) failures += "veil $amount at progress $progress"
                sampled.incrementAndGet()
            }
            true
        }
        main {
            incoming = route(1, Color.WHITE)
            navigation.viewTreeObserver.addOnPreDrawListener(sample)
            navigation.transition = kind
            navigation.durationMs = 400
            navigation.navigate(++revision)
        }
        awaitEnds(1)
        main { navigation.viewTreeObserver.removeOnPreDrawListener(sample) }
        assertTrue(failures.distinct().joinToString(), failures.isEmpty())
        return sampled.get()
    }

    /** Starts a [kind] push of [routes]`[1]`, freezes it at 30% of its time and captures the window. */
    private fun frozenPush(kind: Int, sameColorGradient: Boolean): Bitmap {
        main {
            navigation.background = if (sameColorGradient) {
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BACKDROP, BACKDROP))
            } else {
                null
            }
            navigation.operation = OPERATION_PUSH
            navigation.transition = kind
            navigation.durationMs = 60_000
            navigation.navigate(++revision)
        }
        awaitCondition("the push starts") { navigation.transitionAnimator() != null }
        main {
            val animator = requireNotNull(navigation.transitionAnimator())
            animator.pause()
            // The incoming route covers 71% of the screen; the route below shows dimmed.
            animator.setCurrentFraction(0.3f)
        }
        awaitFrames(instrumentation, 4)
        return capture()
    }

    private fun finishFrozen(ends: Int) {
        main { navigation.transitionAnimator()?.end() }
        awaitEnds(ends)
        main { navigation.background = null }
        awaitFrames(instrumentation, 2)
    }

    private fun capture(): Bitmap {
        val decor = activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = -1
        PixelCopy.request(
            activity.window,
            Rect(0, 0, decor.width, decor.height),
            bitmap,
            { code -> result = code; done.countDown() },
            Handler(Looper.getMainLooper()),
        )
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        return bitmap
    }

    private fun route(index: Int, color: Int): View {
        val view = FrameLayout(activity).apply { setBackgroundColor(color) }
        routes += view
        navigation.insert(view, index)
        return view
    }

    private fun assertAtRest() {
        assertNull("no veil at rest", navigation.veiledRoute())
        assertNull(navigation.layeredRoute())
        routes.forEach { route ->
            assertEquals(1f, route.alpha, 0f)
            assertEquals(View.LAYER_TYPE_NONE, route.layerType)
        }
    }

    private fun awaitRest() {
        awaitCondition("the routes come to rest") {
            navigation.veiledRoute() == null && routes.all { it.alpha == 1f && it.translationX == 0f }
        }
    }

    private fun awaitEnds(count: Int) {
        awaitCondition("$count transition end(s)") { transitionEnds.get() >= count }
    }

    private fun awaitCondition(message: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            var met = false
            main { met = condition() }
            if (met) return
            check(SystemClock.uptimeMillis() < deadline) { message }
            awaitFrames(instrumentation)
        }
    }

    private fun blend(color: Int, backdrop: Int, amount: Float): Int {
        fun channel(a: Int, b: Int) = Math.round(a * (1f - amount) + b * amount)
        return Color.rgb(
            channel(Color.red(color), Color.red(backdrop)),
            channel(Color.green(color), Color.green(backdrop)),
            channel(Color.blue(color), Color.blue(backdrop)),
        )
    }

    private fun channelDelta(a: Int, b: Int): Int = maxOf(
        abs(Color.red(a) - Color.red(b)),
        abs(Color.green(a) - Color.green(b)),
        abs(Color.blue(a) - Color.blue(b)),
    )

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private companion object {
        const val BACKDROP = 0xFF101418.toInt()
        const val ROUTE_COLOR = 0xFFE8F0FE.toInt()
        const val DURATION_MS = 300L
        const val OPERATION_PUSH = 2
        const val OPERATION_POP = 3
        const val TRANSITION_SLIDE_FROM_RIGHT = 2
        const val TRANSITION_SLIDE_FROM_BOTTOM = 4
        const val TRANSITION_NONE = 8
    }
}
