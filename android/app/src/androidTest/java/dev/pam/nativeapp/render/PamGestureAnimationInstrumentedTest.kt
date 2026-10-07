package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.TextView
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
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Gesture and animation primitives run entirely on the UI thread: during a
 * drag or animation the renderer must not call back into PHP (the only way
 * PHP can re-render) and frames must keep the display cadence.
 */
@RunWith(AndroidJUnit4::class)
class PamGestureAnimationInstrumentedTest {
    /** These tests observe motion in flight; CI emulators disable animations. */
    @get:Rule
    val animations = PamAnimationsEnabledRule()

    private data class Dispatched(val id: Long, val kind: Int, val payload: Map<String, WireValue>, val atMs: Long)

    @Test
    fun storyDragToDismissStaysOnTheUiThreadUntilItSettles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val events = Collections.synchronizedList(mutableListOf<Dispatched>())
        lateinit var renderer: PamRenderer
        lateinit var page: View
        val frames = FrameRecorder()
        try {
            onMain(instrumentation) {
                renderer = recordingRenderer(activity, events)
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.PRESSABLE, mapOf(
                        PropKey.GESTURE_TYPE to PropValue.Integer(2),
                        PropKey.GESTURE_DIRECTION to PropValue.Integer(7),
                        PropKey.GESTURE_MIN_DISTANCE to PropValue.Decimal(8.0),
                        PropKey.GESTURE_DRAG to PropValue.Text(
                            listOf(
                                "axis=y", "min=0", "snaps=0,100%", "settle=spring:230:22:0.72",
                                "settle.1=timing:190:ease-out", "threshold=120", "velocity=900",
                                "drive=|scale|0,50%|1,0.955",
                            ).joinToString("\n"),
                        ),
                        PropKey.ON_GESTURE_BEGIN to PropValue.Flag(true),
                        PropKey.ON_GESTURE_END to PropValue.Flag(true),
                        PropKey.ON_GESTURE_SETTLE to PropValue.Flag(true),
                    ))),
                    Mutation.Create(node(3, 2, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF203040),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(3, Frame(0f, 0f, 360f, 640f)),
                    Mutation.SetRoot(1),
                )))
                page = views(renderer)[3]
                frames.attach(activity.window)
            }
            instrumentation.waitForIdleSync()
            val start = locationOf(instrumentation, activity.host)
            val x = start.first + 180f.dp(page)
            val y = start.second + 120f.dp(page)
            // A 300dp downward drag over ~20 frames, injected at display cadence.
            var midDragEvents = 0
            var followed = false
            drag(instrumentation, x, y, x, y + 300f.dp(page), steps = 20, release = false)
            onMain(instrumentation) {
                midDragEvents = events.count { it.kind != EventKind.GESTURE_BEGIN.value }
                followed = page.translationY > 250f.dp(page)
            }
            release(instrumentation, x, y + 300f.dp(page))
            waitUntil(5_000) { events.any { it.kind == EventKind.GESTURE_SETTLE.value } }
            onMain(instrumentation) {
                frames.detach(activity.window)
                val kinds = events.map { it.kind }
                assertEquals(
                    "PHP must only see begin, end and settle: $kinds",
                    listOf(EventKind.GESTURE_BEGIN.value, EventKind.GESTURE_END.value, EventKind.GESTURE_SETTLE.value),
                    kinds,
                )
                assertEquals("No PHP events while the finger moves", 0, midDragEvents)
                assertTrue("The page follows the finger on the UI thread", followed)
                val end = events[1].payload
                assertEquals(1L, (end["snapIndex"] as WireValue.Integer).value)
                assertEquals(true, (end["thresholdReached"] as WireValue.Flag).value)
                assertEquals(page.height.toFloat(), page.translationY, 1.5f)
                assertEquals(0.955f, page.scaleX, 0.01f)
                frames.assertCadence("story drag")
            }
        } finally {
            onMain(instrumentation) {
                renderer.close()
                activity.finish()
            }
        }
    }

    @Test
    fun reducedMotionDragReportsItsSettleAfterTheEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val events = Collections.synchronizedList(mutableListOf<Dispatched>())
        lateinit var renderer: PamRenderer
        lateinit var page: View
        try {
            onMain(instrumentation) {
                // Reduced motion settles within the release itself.
                PamMotionPolicy.reduceMotionOverride = true
                renderer = recordingRenderer(activity, events)
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.PRESSABLE, mapOf(
                        PropKey.GESTURE_TYPE to PropValue.Integer(2),
                        PropKey.GESTURE_DIRECTION to PropValue.Integer(7),
                        PropKey.GESTURE_MIN_DISTANCE to PropValue.Decimal(8.0),
                        PropKey.GESTURE_DRAG to PropValue.Text(
                            "axis=y\nmin=0\nsnaps=0,100%\nsettle=spring:230:22:0.72\nthreshold=120\nvelocity=900",
                        ),
                        PropKey.ON_GESTURE_BEGIN to PropValue.Flag(true),
                        PropKey.ON_GESTURE_END to PropValue.Flag(true),
                        PropKey.ON_GESTURE_SETTLE to PropValue.Flag(true),
                    ))),
                    Mutation.Create(node(3, 2, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF203040),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(3, Frame(0f, 0f, 360f, 640f)),
                    Mutation.SetRoot(1),
                )))
                page = views(renderer)[3]
            }
            instrumentation.waitForIdleSync()
            val start = locationOf(instrumentation, activity.host)
            val x = start.first + 180f.dp(page)
            val y = start.second + 120f.dp(page)
            drag(instrumentation, x, y, x, y + 300f.dp(page), steps = 10)
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(
                    "settle must follow end",
                    listOf(EventKind.GESTURE_BEGIN.value, EventKind.GESTURE_END.value, EventKind.GESTURE_SETTLE.value),
                    events.map { it.kind },
                )
                assertEquals(1L, (events[2].payload["snapIndex"] as WireValue.Integer).value)
                assertEquals(page.height.toFloat(), page.translationY, 1.5f)
            }
        } finally {
            onMain(instrumentation) {
                PamMotionPolicy.reduceMotionOverride = null
                renderer.close()
                activity.finish()
            }
        }
    }

    @Test
    fun swipeToReplySpringsBackAndReportsTheThresholdOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val events = Collections.synchronizedList(mutableListOf<Dispatched>())
        lateinit var renderer: PamRenderer
        lateinit var bubble: View
        lateinit var icon: View
        val translations = mutableListOf<Float>()
        try {
            onMain(instrumentation) {
                renderer = recordingRenderer(activity, events)
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.PRESSABLE, mapOf(
                        PropKey.GESTURE_TYPE to PropValue.Integer(2),
                        PropKey.GESTURE_DIRECTION to PropValue.Integer(6),
                        PropKey.GESTURE_COMPOSITION to PropValue.Integer(3),
                        PropKey.GESTURE_MIN_DISTANCE to PropValue.Decimal(12.0),
                        PropKey.GESTURE_DRAG to PropValue.Text(
                            "axis=x\nmin=0\nmax=72\ntarget=bubble\nsnaps=0\nthreshold=46\nhaptic=1\n" +
                                "settle=spring:260:18:1\ndrive=reply|opacity|0,46|0,1",
                        ),
                        PropKey.ON_GESTURE_END to PropValue.Flag(true),
                    ))),
                    Mutation.Create(NodeSpec(3, 2, 0, NodeKind.VIEW, mapOf(
                        PropKey.NATIVE_REF to PropValue.Text("reply"),
                        PropKey.OPACITY to PropValue.Decimal(0.0),
                    ))),
                    Mutation.Create(NodeSpec(4, 2, 1, NodeKind.VIEW, mapOf(
                        PropKey.NATIVE_REF to PropValue.Text("bubble"),
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF2E7D32),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 100f, 360f, 64f)),
                    Mutation.Layout(3, Frame(8f, 16f, 32f, 32f)),
                    Mutation.Layout(4, Frame(0f, 0f, 300f, 64f)),
                    Mutation.SetRoot(1),
                )))
                bubble = views(renderer)[4]
                icon = views(renderer)[3]
            }
            instrumentation.waitForIdleSync()
            // The row is laid out at y=100dp inside the root host.
            val host = locationOf(instrumentation, activity.host)
            val start = host.first to host.second + 100f.dp(bubble)
            val x = start.first + 40f.dp(bubble)
            val y = start.second + 32f.dp(bubble)
            drag(instrumentation, x, y, x + 140f.dp(bubble), y, steps = 12, release = false) {
                onMain(instrumentation) { translations += bubble.translationX }
            }
            onMain(instrumentation) {
                val pressable = bubble.parent as View
                assertEquals(
                    "bubble clamps to the reveal limit (clickable=${pressable.isClickable} " +
                        "start=$start translations=$translations events=${events.map { it.kind }})",
                    72f.dp(bubble),
                    bubble.translationX,
                    1f,
                )
                assertEquals("reply icon is fully driven", 1f, icon.alpha, 0.01f)
                assertTrue("No PHP events during the swipe", events.isEmpty())
            }
            release(instrumentation, x + 140f.dp(bubble), y)
            waitUntil(5_000) { bubble.translationX == 0f }
            onMain(instrumentation) {
                assertEquals(1, events.size)
                assertEquals(EventKind.GESTURE_END.value, events[0].kind)
                assertEquals(true, (events[0].payload["thresholdReached"] as WireValue.Flag).value)
                assertEquals(0L, (events[0].payload["snapIndex"] as WireValue.Integer).value)
                assertEquals(0f, icon.alpha, 0.01f)
                assertTrue("translation follows the finger monotonically", translations.zipWithNext().all { (a, b) -> b >= a })
            }
        } finally {
            onMain(instrumentation) {
                renderer.close()
                activity.finish()
            }
        }
    }

    @Test
    fun doubleTapCancelsTheSinglePressAndPlaysTheTapEffectAtTheTouchPoint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val events = Collections.synchronizedList(mutableListOf<Dispatched>())
        lateinit var renderer: PamRenderer
        lateinit var surface: View
        lateinit var heart: View
        try {
            onMain(instrumentation) {
                renderer = recordingRenderer(activity, events)
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.PRESSABLE, mapOf(
                        PropKey.ON_PRESS to PropValue.Flag(true),
                        PropKey.ON_DOUBLE_TAP to PropValue.Flag(true),
                        PropKey.PRESS_DOUBLE_TAP_DELAY_MS to PropValue.Integer(300),
                        PropKey.PRESS_OPACITY to PropValue.Decimal(1.0),
                        PropKey.PRESS_TAP_EFFECT to PropValue.Text(
                            "ref=heart;tilt=0\npam-motion 1 id=5 iterations=1\n" +
                                "0 scale set(0.5) spring(1.1,300,7,0.5,0,0) timing(1,120,ease-in-out-quad,0)",
                        ),
                    ))),
                    Mutation.Create(NodeSpec(3, 2, 0, NodeKind.VIEW, mapOf(
                        PropKey.NATIVE_REF to PropValue.Text("heart"),
                    ))),
                    Mutation.Create(node(4, 3, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFE53935),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(3, Frame(0f, 0f, 96f, 96f)),
                    Mutation.Layout(4, Frame(0f, 0f, 96f, 96f)),
                    Mutation.SetRoot(1),
                )))
                surface = views(renderer)[2]
                heart = views(renderer)[3]
            }
            instrumentation.waitForIdleSync()
            val origin = locationOf(instrumentation, activity.host)
            val x = origin.first + 200f.dp(surface)
            val y = origin.second + 300f.dp(surface)
            onMain(instrumentation) {
                // Both taps (90 ms apart in event time) reach the window in one
                // main-thread message: the pending single press can never run
                // between them, however late the host schedules the injection.
                val window = IntArray(2).also(activity.host::getLocationInWindow)
                val screen = IntArray(2).also(activity.host::getLocationOnScreen)
                val dx = (window[0] - screen[0]).toFloat()
                val dy = (window[1] - screen[1]).toFloat()
                val first = SystemClock.uptimeMillis()
                windowTap(activity, first, x + dx, y + dy)
                windowTap(activity, first + 90L, x + 4f + dx, y + 4f + dy)
                val kinds = events.map { it.kind }
                assertEquals("double tap replaces the single press: $kinds", listOf(EventKind.DOUBLE_TAP.value), kinds)
                val payload = events[0].payload
                val density = surface.resources.displayMetrics.density
                val tapX = (payload["x"] as WireValue.Decimal).value.toFloat() * density
                val tapY = (payload["y"] as WireValue.Decimal).value.toFloat() * density
                assertEquals(tapX, heart.left + heart.translationX + heart.width / 2f, 2f)
                assertEquals(tapY, heart.top + heart.translationY + heart.height / 2f, 2f)
            }
            events.clear()
            tap(instrumentation, x, y)
            waitUntil(1_500) { events.isNotEmpty() }
            onMain(instrumentation) {
                assertEquals(listOf(EventKind.PRESS.value), events.map { it.kind })
                val pressX = (events[0].payload["x"] as WireValue.Decimal).value
                assertTrue("press carries locationX", pressX > 150.0)
            }
        } finally {
            onMain(instrumentation) {
                renderer.close()
                activity.finish()
            }
        }
    }

    @Test
    fun motionProgramsAndSpringTransitionsAnimateWithoutPhp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val events = Collections.synchronizedList(mutableListOf<Dispatched>())
        lateinit var renderer: PamRenderer
        lateinit var badge: View
        lateinit var bubble: View
        val frames = FrameRecorder()
        try {
            onMain(instrumentation) {
                renderer = recordingRenderer(activity, events)
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(NodeSpec(2, 1, 0, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF1565C0),
                        PropKey.ON_ANIMATION_COMPLETE to PropValue.Flag(true),
                    ))),
                    Mutation.Create(NodeSpec(3, 1, 1, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF6A1B9A),
                        PropKey.ANIMATE_CHANGES to PropValue.Flag(true),
                        PropKey.TRANSITION_SPEC to PropValue.Text(
                            "@property transform,opacity\n@duration 0,400\n@timing spring:260:18:1,linear\n@delay 0,0",
                        ),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(16f, 16f, 64f, 64f)),
                    Mutation.Layout(3, Frame(16f, 200f, 200f, 64f)),
                    Mutation.SetRoot(1),
                )))
                badge = views(renderer)[2]
                bubble = views(renderer)[3]
            }
            instrumentation.waitForIdleSync()
            var springInFlight = false
            var opacityInFlight = false
            val sample = android.view.ViewTreeObserver.OnPreDrawListener {
                if (bubble.translationX > 0f && bubble.translationX < 80f.dp(bubble) * 1.3f) springInFlight = true
                if (bubble.alpha < 1f && bubble.alpha > 0.2f) opacityInFlight = true
                true
            }
            onMain(instrumentation) {
                activity.host.viewTreeObserver.addOnPreDrawListener(sample)
                frames.attach(activity.window)
                renderer.commit(listOf(listOf(
                    Mutation.Update(2, PropKey.ANIMATION_PROGRAM, PropValue.Text(
                        "pam-motion 1 id=77 iterations=1\n" +
                            "0 scale timing(0.82,70,ease-out-quad,0) spring(1,420,9,0.6,0,0)\n" +
                            "0 translateX timing(40,300,linear,0)\n" +
                            "1 opacity timing(0.5,100,linear,0)",
                    )),
                    Mutation.Update(3, PropKey.TRANSLATION_X, PropValue.Decimal(80.0)),
                    Mutation.Update(3, PropKey.OPACITY, PropValue.Decimal(0.2)),
                )))
            }
            waitUntil(5_000) { events.any { it.kind == EventKind.ANIMATION_COMPLETE.value } }
            // The bubble's spring may still be settling after the program ends.
            waitUntil(5_000) {
                kotlin.math.abs(bubble.translationX - 80f.dp(bubble)) <= 0.5f && kotlin.math.abs(bubble.alpha - 0.2f) <= 0.001f
            }
            onMain(instrumentation) {
                activity.host.viewTreeObserver.removeOnPreDrawListener(sample)
                assertTrue("spring transition is in flight", springInFlight)
                assertTrue("timed opacity transition is in flight", opacityInFlight)
                frames.detach(activity.window)
                assertEquals(listOf(EventKind.ANIMATION_COMPLETE.value), events.map { it.kind })
                assertEquals(1f, badge.scaleX, 0.001f)
                assertEquals(40f.dp(badge), badge.translationX, 0.5f)
                assertEquals(0.5f, badge.alpha, 0.001f)
                assertEquals(80f.dp(bubble), bubble.translationX, 0.5f)
                assertEquals(0.2f, bubble.alpha, 0.001f)
                frames.assertCadence("motion program")
            }
            // Re-sending the same program id must not replay it.
            onMain(instrumentation) {
                renderer.commit(listOf(listOf(
                    Mutation.Update(2, PropKey.ANIMATION_PROGRAM, PropValue.Text(
                        "pam-motion 1 id=77 iterations=1\n0 opacity timing(1,100,linear,0)",
                    )),
                )))
                assertEquals(0.5f, badge.alpha, 0.001f)
            }
        } finally {
            onMain(instrumentation) {
                renderer.close()
                activity.finish()
            }
        }
    }

    @Test
    fun horizontalPagingReportsMomentumEndWithThePageIndex() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val events = Collections.synchronizedList(mutableListOf<Dispatched>())
        lateinit var renderer: PamRenderer
        lateinit var scroll: View
        try {
            onMain(instrumentation) {
                renderer = recordingRenderer(activity, events)
                val mutations = mutableListOf<Mutation>(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.SCROLL, mapOf(
                        PropKey.SCROLL_HORIZONTAL to PropValue.Flag(true),
                        PropKey.SCROLL_PAGING_ENABLED to PropValue.Flag(true),
                        PropKey.SCROLL_DECELERATION_RATE to PropValue.Decimal(0.99),
                        PropKey.ON_SCROLL_BEGIN_DRAG to PropValue.Flag(true),
                        PropKey.ON_SCROLL_END_DRAG to PropValue.Flag(true),
                        PropKey.ON_MOMENTUM_SCROLL_END to PropValue.Flag(true),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                )
                repeat(3) { index ->
                    val id = index + 3L
                    mutations += Mutation.Create(NodeSpec(id, 2, index, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF000000 or (0x404040L * (index + 1))),
                    )))
                    mutations += Mutation.Layout(id, Frame(index * 360f, 0f, 360f, 640f))
                }
                mutations += Mutation.SetRoot(1)
                renderer.commit(listOf(mutations))
                scroll = views(renderer)[2]
            }
            instrumentation.waitForIdleSync()
            val origin = locationOf(instrumentation, activity.host)
            val y = origin.second + 320f.dp(scroll)
            drag(instrumentation, origin.first + 300f.dp(scroll), y, origin.first + 140f.dp(scroll), y, steps = 6)
            waitUntil(5_000) { events.any { it.kind == EventKind.MOMENTUM_SCROLL_END.value } }
            onMain(instrumentation) {
                val kinds = events.map { it.kind }
                assertEquals(
                    listOf(
                        EventKind.SCROLL_BEGIN_DRAG.value,
                        EventKind.SCROLL_END_DRAG.value,
                        EventKind.MOMENTUM_SCROLL_END.value,
                    ),
                    kinds,
                )
                val momentum = events.last().payload
                assertEquals(1L, (momentum["page"] as WireValue.Integer).value)
                assertEquals(360.0, (momentum["x"] as WireValue.Decimal).value, 1.0)
            }
        } finally {
            onMain(instrumentation) {
                renderer.close()
                activity.finish()
            }
        }
    }

    @Test
    fun textLayoutReportsWrappedLinesBeyondNumberOfLines() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation, requireFocus = false)
        val events = Collections.synchronizedList(mutableListOf<Dispatched>())
        lateinit var renderer: PamRenderer
        try {
            onMain(instrumentation) {
                renderer = recordingRenderer(activity, events)
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.TEXT, mapOf(
                        PropKey.TEXT to PropValue.Text(
                            "Legenda longa do reel que precisa quebrar em várias linhas para exibir o mais. ".repeat(4),
                        ),
                        PropKey.NUMBER_OF_LINES to PropValue.Integer(2),
                        PropKey.ON_TEXT_LAYOUT to PropValue.Flag(true),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 240f, 40f)),
                    Mutation.SetRoot(1),
                )))
            }
            waitUntil(5_000) { events.any { it.kind == EventKind.TEXT_LAYOUT.value } }
            onMain(instrumentation) {
                val payload = events.last { it.kind == EventKind.TEXT_LAYOUT.value }.payload
                val lines = (payload["lines"] as WireValue.Integer).value
                assertTrue("full caption wraps past two lines: $lines", lines > 2)
                assertEquals(2L, (payload["visibleLines"] as WireValue.Integer).value)
                assertEquals(true, (payload["truncated"] as WireValue.Flag).value)
                val widths = (payload["lineWidths"] as WireValue.Text).value
                assertEquals(lines.toInt(), widths.trim('[', ']').split(',').size)
                val text = views(renderer)[2] as TextView
                assertEquals(2, text.maxLines)
            }
        } finally {
            onMain(instrumentation) {
                renderer.close()
                activity.finish()
            }
        }
    }

    @Test
    fun slideFadeModalFadesTheBackdropWhileTheSheetSlidesIndependently() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation, requireFocus = false)
        val events = Collections.synchronizedList(mutableListOf<Dispatched>())
        lateinit var renderer: PamRenderer
        lateinit var sheet: View
        try {
            onMain(instrumentation) {
                renderer = recordingRenderer(activity, events)
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.MODAL, mapOf(
                        PropKey.VISIBLE to PropValue.Flag(false),
                        PropKey.MODAL_PRESENTATION to PropValue.Integer(1),
                        PropKey.MODAL_ANIMATION_TYPE to PropValue.Integer(4),
                    ))),
                    Mutation.Create(node(3, 2, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFFL),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(3, Frame(0f, 360f, 360f, 280f)),
                    Mutation.SetRoot(1),
                )))
                sheet = views(renderer)[3]
                renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.VISIBLE, PropValue.Flag(true)))))
            }
            var sawIndependentMotion = false
            // Sampled on every frame of the modal window until it settles.
            waitUntil(5_000) {
                val content = sheet.parent as View
                val backdropAlpha = (content.background as? android.graphics.drawable.ColorDrawable)?.alpha ?: 255
                // Mid-flight: the scrim is partially visible while the sheet is still below its frame,
                // and the scrim container itself never translates.
                if (backdropAlpha in 1 until DEFAULT_BACKDROP_ALPHA && sheet.translationY > 0f && content.translationY == 0f) {
                    sawIndependentMotion = true
                }
                // Before the dialog presents, the sheet also rests at 0 with the
                // default scrim: only a settle after the motion ends the wait.
                sawIndependentMotion && sheet.translationY == 0f && backdropAlpha == DEFAULT_BACKDROP_ALPHA
            }
            onMain(instrumentation) {
                val content = sheet.parent as View
                assertTrue("backdrop and sheet must animate independently", sawIndependentMotion)
                assertEquals(0f, sheet.translationY, 0.5f)
                assertEquals(DEFAULT_BACKDROP_ALPHA, (content.background as android.graphics.drawable.ColorDrawable).alpha)
                assertTrue("presentation does not call PHP", events.isEmpty())
            }
        } finally {
            onMain(instrumentation) {
                renderer.close()
                activity.finish()
            }
        }
    }

    /**
     * Records per-frame UI-thread work (input, animation, measure/layout and
     * draw recording) from FrameMetrics. That is the part gesture and motion
     * code controls; GPU/rasterisation time on a software-rendered emulator is
     * reported in the message but not asserted.
     */
    private class FrameRecorder {
        private val uiThread = Collections.synchronizedList(mutableListOf<Long>())
        private val total = Collections.synchronizedList(mutableListOf<Long>())
        private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                uiThread += metrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                    metrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                    metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                    metrics.getMetric(FrameMetrics.DRAW_DURATION)
                total += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            }
        }

        fun attach(window: Window) {
            window.addOnFrameMetricsAvailableListener(listener, android.os.Handler(android.os.Looper.getMainLooper()))
        }

        fun detach(window: Window) {
            runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
        }

        fun assertCadence(label: String) {
            val work = synchronized(uiThread) { uiThread.toList() }
            val totals = synchronized(total) { total.toList() }
            assertTrue("$label produced too few frames: ${work.size}", work.size >= 8)
            val budgetNs = 16_666_667L
            val slow = work.count { it > budgetNs }
            val p50 = work.sorted()[work.size / 2] / 1_000_000.0
            val p95 = work.sorted()[(work.size * 95) / 100] / 1_000_000.0
            val totalP50 = totals.sorted()[totals.size / 2] / 1_000_000.0
            android.util.Log.i(
                "PamMotionFrames",
                "$label frames=${work.size} uiP50=${"%.2f".format(p50)}ms uiP95=${"%.2f".format(p95)}ms " +
                    "over16ms=$slow totalP50=${"%.2f".format(totalP50)}ms",
            )
            assertTrue(
                "$label UI-thread work exceeded a 60 Hz frame in $slow of ${work.size} frames " +
                    "(p50=${"%.2f".format(p50)}ms p95=${"%.2f".format(p95)}ms, total p50=${"%.2f".format(totalP50)}ms)",
                slow * 20 <= work.size,
            )
        }
    }

    private companion object {
        /** PamModalHost's default scrim is argb(82, 0, 0, 0). */
        const val DEFAULT_BACKDROP_ALPHA = 82
    }

    private fun recordingRenderer(activity: PamTestActivity, events: MutableList<Dispatched>): PamRenderer =
        PamRenderer(activity, activity.host) { id, kind, payload ->
            val decoded = if (payload.isEmpty()) emptyMap() else runCatching { WireMap.decode(payload) }.getOrDefault(emptyMap())
            events += Dispatched(id, kind, decoded, SystemClock.uptimeMillis())
        }

    @Suppress("UNCHECKED_CAST")
    private fun views(renderer: PamRenderer): android.util.LongSparseArray<View> =
        PamRenderer::class.java.getDeclaredField("views").apply { isAccessible = true }
            .get(renderer) as android.util.LongSparseArray<View>

    private fun locationOf(instrumentation: Instrumentation, view: View): Pair<Float, Float> {
        val location = IntArray(2)
        onMain(instrumentation) { view.getLocationOnScreen(location) }
        return location[0].toFloat() to location[1].toFloat()
    }

    private fun drag(
        instrumentation: Instrumentation,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        steps: Int,
        release: Boolean = true,
        afterMove: (() -> Unit)? = null,
    ) {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, fromX, fromY, 0))
        for (step in 1..steps) {
            val progress = step / steps.toFloat()
            val now = down + step * 16L
            while (SystemClock.uptimeMillis() < now) SystemClock.sleep(1)
            instrumentation.sendPointerSync(MotionEvent.obtain(
                down, now, MotionEvent.ACTION_MOVE,
                fromX + (toX - fromX) * progress,
                fromY + (toY - fromY) * progress,
                0,
            ))
            afterMove?.invoke()
        }
        if (release) {
            val up = down + (steps + 1) * 16L
            instrumentation.sendPointerSync(MotionEvent.obtain(down, up, MotionEvent.ACTION_UP, toX, toY, 0))
        }
        lastDown = down
    }

    private var lastDown = 0L

    private fun release(instrumentation: Instrumentation, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(lastDown, now, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun tap(instrumentation: Instrumentation, x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        instrumentation.sendPointerSync(MotionEvent.obtain(down, down + 40L, MotionEvent.ACTION_UP, x, y, 0))
    }

    /** Dispatches a 40 ms tap at window coordinates on the main thread. */
    private fun windowTap(activity: PamTestActivity, down: Long, x: Float, y: Float) {
        val root = activity.window.decorView
        listOf(
            MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0),
            MotionEvent.obtain(down, down + 40L, MotionEvent.ACTION_UP, x, y, 0),
        ).forEach { event ->
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            root.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var met = false
            onMain(instrumentation) { met = condition() }
            if (met) return
            awaitFrames(instrumentation)
        }
    }

    private fun Float.dp(view: View): Float = this * view.resources.displayMetrics.density

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ): NodeSpec = NodeSpec(id = id, parent = parent, index = 0, kind = kind, properties = properties)

    /**
     * Injected touches and FrameMetrics both require the test window to be the
     * focused, drawing window (a stray system dialog would receive them).
     */
    private fun launchActivity(instrumentation: Instrumentation, requireFocus: Boolean = true): PamTestActivity {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as PamTestActivity
        if (!requireFocus) return activity
        val deadline = SystemClock.uptimeMillis() + 15_000
        var focused = false
        while (!focused && SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            onMain(instrumentation) { focused = activity.hasWindowFocus() }
            if (!focused) awaitFrames(instrumentation)
        }
        assertTrue("Test activity never gained window focus", focused)
        return activity
    }

    private fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }
}
