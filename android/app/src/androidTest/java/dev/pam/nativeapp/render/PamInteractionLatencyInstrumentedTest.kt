package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Same-window Button/PAM comparison. Uses only APIs present in 1.54.10 so
 * the same test APK runs against the old app and the patched app. Reports
 * latency instead of imposing a hardware-dependent millisecond threshold.
 * First draw means UI draw submission, not presentation on a physical panel.
 */
@RunWith(AndroidJUnit4::class)
class PamInteractionLatencyInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private class DrawProbe(context: android.content.Context) : View(context) {
        var armed = false
        var started = 0L
        var callbackNanos = 0L
        var drawNanos = 0L
        var done = CountDownLatch(1)
        private var color = Color.BLACK

        fun changed() {
            if (!armed) return
            callbackNanos = System.nanoTime() - started
            color = if (color == Color.BLACK) Color.WHITE else Color.BLACK
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(color)
            if (armed && callbackNanos > 0) {
                drawNanos = System.nanoTime() - started
                armed = false
                done.countDown()
            }
        }
    }

    @Test
    fun reportsTapToCallbackAndChangedDrawAgainstPlatformButton() {
        val report = JSONArray()
        for (retained in listOf(0, 1_000, 10_000)) {
            for (inputCount in listOf(0, 1, 4)) {
                val activity = launch()
                lateinit var renderer: PamRenderer
                lateinit var probe: DrawProbe
                lateinit var platform: Button
                lateinit var press: View
                val inputs = ArrayList<EditText>()
                try {
                    main {
                        renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                            if (id == PRESS && kind == EventKind.PRESS.value) probe.changed()
                        }
                        renderer.commit(listOf(fixture(retained, inputCount)))
                        press = requireNotNull(find(activity.host, "latency-press"))
                        repeat(inputCount) { index ->
                            inputs += requireNotNull(find(activity.host, "latency-input-$index")) as EditText
                        }
                        probe = DrawProbe(activity)
                        platform = Button(activity).apply { text = "Platform"; setOnClickListener { probe.changed() } }
                        activity.host.addView(platform, FrameLayout.LayoutParams(180, 80).apply { leftMargin = 200 })
                        activity.host.addView(probe, FrameLayout.LayoutParams(40, 40).apply { topMargin = 90 })
                    }
                    awaitFrames(instrumentation, 3)
                    for ((name, target) in listOf("platformButton" to platform, "pamPressable" to press)) {
                        val callbacks = ArrayList<Long>()
                        val draws = ArrayList<Long>()
                        repeat(35) { sample ->
                            main {
                                // Setup stays outside the timed interval. Each PAM press must
                                // drain the newest pending edits before its own callback.
                                inputs.forEach { it.setText("sample-$sample") }
                                probe.done = CountDownLatch(1)
                                probe.callbackNanos = 0
                                probe.drawNanos = 0
                                val down = SystemClock.uptimeMillis()
                                val x = target.width / 2f
                                val y = target.height / 2f
                                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0).also {
                                    target.dispatchTouchEvent(it); it.recycle()
                                }
                                probe.armed = true
                                probe.started = System.nanoTime()
                                MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0).also {
                                    target.dispatchTouchEvent(it); it.recycle()
                                }
                            }
                            assertTrue("$name produced a changed draw", probe.done.await(5, TimeUnit.SECONDS))
                            if (sample >= 5) {
                                callbacks += probe.callbackNanos
                                draws += probe.drawNanos
                            }
                        }
                        report.put(JSONObject().put("control", name).put("retainedNodes", retained)
                            .put("inputs", inputCount).put("samples", callbacks.size)
                            .put("upToCallbackNs", quantiles(callbacks)).put("upToDrawSubmissionNs", quantiles(draws)))
                    }
                } finally {
                    main { renderer.close(); activity.finish() }
                }
            }
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "\nPAM_INTERACTION_LATENCY=" + report.toString() + "\n")
        })
    }

    @Test
    fun nativeFocusedTextIsDeliveredBeforePressAndBlurDoesNotLeaveAPendingCopy() {
        val activity = launch()
        lateinit var renderer: PamRenderer
        val events = ArrayList<Pair<Int, String>>()
        try {
            main {
                renderer = PamRenderer(activity, activity.host) { id, kind, payload ->
                    if (id == INPUT || id == PRESS) events += kind to payload.toString(Charsets.UTF_8)
                }
                renderer.commit(listOf(fixture(0, 1)))
                renderer.commit(listOf(listOf(Mutation.Update(INPUT, PropKey.INPUT_SYNC_MODE, PropValue.Integer(1)))))
            }
            awaitFrames(instrumentation, 2)
            main {
                val input = requireNotNull(find(activity.host, "latency-input-0")) as EditText
                input.showSoftInputOnFocus = false
                input.requestFocus()
                input.setText("last visible character")
                events.clear()
                requireNotNull(find(activity.host, "latency-press")).performClick()
                assertEquals(listOf(EventKind.CHANGE.value, EventKind.PRESS.value), events.map { it.first })
                assertEquals("last visible character", events.first().second)
                input.clearFocus()
                events.clear()
                requireNotNull(find(activity.host, "latency-press")).performClick()
                assertEquals(listOf(EventKind.PRESS.value), events.map { it.first })
            }
        } finally { main { renderer.close(); activity.finish() } }
    }

    @Test
    fun pendingEditsReachTheHandlerBeforePressIncludingReentrantRemoval() {
        val activity = launch()
        lateinit var renderer: PamRenderer
        val events = ArrayList<Pair<Long, Int>>()
        var removeSecond = false
        try {
            main {
                renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                    events += id to kind
                    if (removeSecond && id == INPUT && kind == EventKind.CHANGE.value) {
                        renderer.commit(listOf(listOf(Mutation.Remove(INPUT + 1))))
                    }
                }
                renderer.commit(listOf(fixture(1000, 2)))
            }
            awaitFrames(instrumentation, 2)
            main {
                val first = requireNotNull(find(activity.host, "latency-input-0")) as EditText
                val second = requireNotNull(find(activity.host, "latency-input-1")) as EditText
                val press = requireNotNull(find(activity.host, "latency-press"))
                first.setText("first"); second.setText("second"); events.clear()
                press.performClick()
                assertEquals(listOf(INPUT to EventKind.CHANGE.value, INPUT + 1 to EventKind.CHANGE.value,
                    PRESS to EventKind.PRESS.value), events)
                first.setText("last-first"); second.setText("last-second"); events.clear(); removeSecond = true
                press.performClick()
                assertEquals(listOf(INPUT to EventKind.CHANGE.value, PRESS to EventKind.PRESS.value), events)
            }
        } finally { main { renderer.close(); activity.finish() } }
    }

    @Test
    fun rematerializedEditorsObserveNewTextAndIgnoreTheDetachedEditor() {
        val activity = launch()
        lateinit var renderer: PamRenderer
        val changes = ArrayList<String>()
        try {
            main {
                renderer = PamRenderer(activity, activity.host) { id, kind, payload ->
                    if (id == INPUT && kind == EventKind.CHANGE.value) changes += payload.toString(Charsets.UTF_8)
                }
                renderer.commit(listOf(fixture(0, 1)))
                renderer.commit(listOf(listOf(Mutation.Update(INPUT, PropKey.INPUT_SYNC_MODE, PropValue.Integer(3)))))
            }
            awaitFrames(instrumentation, 2)
            main {
                val old = requireNotNull(find(activity.host, "latency-input-0")) as EditText
                old.setText("before recycle")
                assertEquals(listOf("before recycle"), changes)
                PamRenderer::class.java.getDeclaredMethod("dematerializeSubtree", java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(renderer, INPUT)
                val nodes = PamRenderer::class.java.getDeclaredField("nodes")
                    .apply { isAccessible = true }.get(renderer)
                val node = nodes.javaClass.getDeclaredMethod("get", java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(nodes, INPUT)
                PamRenderer::class.java.declaredMethods.single { it.name == "buildDeferredView" }
                    .apply { isAccessible = true }.invoke(renderer, node)
                renderer.commit(listOf(listOf(Mutation.Layout(INPUT, Frame(0f, 150f, 160f, 48f)))))
                val current = requireNotNull(find(activity.host, "latency-input-0")) as EditText
                assertTrue("A fresh native editor replaced the recycled view", current !== old)
                changes.clear()
                current.setText("after recycle")
                old.setText("late detached IME text")
                assertEquals(listOf("after recycle"), changes)
            }
        } finally { main { renderer.close(); activity.finish() } }
    }

    @Test
    fun closeCancelsDebouncedInputWork() {
        val activity = launch()
        lateinit var renderer: PamRenderer
        val changes = ArrayList<String>()
        val settled = CountDownLatch(1)
        try {
            main {
                renderer = PamRenderer(activity, activity.host) { id, kind, payload ->
                    if (id == INPUT && kind == EventKind.CHANGE.value) changes += payload.toString(Charsets.UTF_8)
                }
                renderer.commit(listOf(fixture(0, 1)))
                renderer.commit(listOf(listOf(Mutation.Update(INPUT, PropKey.INPUT_DEBOUNCE_MS, PropValue.Integer(80)))))
            }
            awaitFrames(instrumentation, 2)
            main {
                (requireNotNull(find(activity.host, "latency-input-0")) as EditText).setText("pending close")
                renderer.close()
                Handler(Looper.getMainLooper()).postDelayed({ settled.countDown() }, 180)
            }
            assertTrue(settled.await(3, TimeUnit.SECONDS))
            main { assertTrue("Closed renderer must emit no delayed edit", changes.isEmpty()) }
        } finally { main { activity.finish() } }
    }

    @Test
    fun aDematerializedPendingEditStillReachesTheNextPress() {
        val activity = launch()
        lateinit var renderer: PamRenderer
        val events = ArrayList<Pair<Long, Int>>()
        try {
            main {
                renderer = PamRenderer(activity, activity.host) { id, kind, _ -> events += id to kind }
                renderer.commit(listOf(fixture(0, 1)))
            }
            awaitFrames(instrumentation, 2)
            main {
                val input = requireNotNull(find(activity.host, "latency-input-0")) as EditText
                input.setText("pending retained value")
                // Models list-cell dematerialization without a timing-dependent scroll.
                PamRenderer::class.java.getDeclaredMethod("dematerializeSubtree", java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(renderer, INPUT)
                events.clear()
                requireNotNull(find(activity.host, "latency-press")).performClick()
                assertEquals(listOf(INPUT to EventKind.CHANGE.value, PRESS to EventKind.PRESS.value), events)
                events.clear()
                requireNotNull(find(activity.host, "latency-press")).performClick()
                assertEquals(listOf(PRESS to EventKind.PRESS.value), events)
            }
        } finally { main { renderer.close(); activity.finish() } }
    }

    private fun fixture(retained: Int, inputs: Int): List<Mutation> = buildList {
        add(Mutation.Create(NodeSpec(ROOT, 0, 0, NodeKind.SCREEN, emptyMap())))
        add(Mutation.Create(NodeSpec(PRESS, ROOT, 0, NodeKind.PRESSABLE, mapOf(
            PropKey.ON_PRESS to PropValue.Flag(true), PropKey.TEST_ID to PropValue.Text("latency-press"),
        ))))
        add(Mutation.Layout(ROOT, Frame(0f, 0f, 400f, 640f)))
        add(Mutation.Layout(PRESS, Frame(0f, 0f, 160f, 64f)))
        repeat(inputs) { index ->
            add(Mutation.Create(NodeSpec(INPUT + index, ROOT, index + 1, NodeKind.INPUT, mapOf(
                PropKey.ON_CHANGE to PropValue.Flag(true), PropKey.INPUT_SYNC_MODE to PropValue.Integer(2),
                PropKey.INPUT_DEBOUNCE_MS to PropValue.Integer(5_000),
                PropKey.TEST_ID to PropValue.Text("latency-input-$index"),
            ))))
            add(Mutation.Layout(INPUT + index, Frame(0f, 150f + index * 56f, 160f, 48f)))
        }
        // Layout-only nodes retain engine/renderer state without allocating
        // ten thousand Android Views or consuming a large bitmap fixture.
        repeat(retained) { index ->
            add(Mutation.Create(NodeSpec(10_000L + index, ROOT, inputs + index + 1, NodeKind.VIEW, emptyMap())))
        }
        add(Mutation.SetRoot(ROOT))
    }

    private fun quantiles(values: List<Long>): JSONObject {
        val sorted = values.sorted()
        return JSONObject().put("p50", sorted[sorted.size / 2])
            .put("p95", sorted[(kotlin.math.ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)])
    }

    private fun find(view: View, name: String): View? {
        if (view.transitionName == name) return view
        if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
            find(view.getChildAt(index), name)?.let { return it }
        }
        return null
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun launch() = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PamTestActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PamTestActivity

    private companion object {
        const val ROOT = 1L
        const val PRESS = 2L
        const val INPUT = 10L
    }
}
