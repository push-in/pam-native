package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A gesture that starts on a Pressable inside a panning KeyboardAvoidingView
 * gives the same events with the IME open (the container translated, the
 * touch routed by PamRootHost) as with the IME closed (Android's regular
 * dispatch): every gesture ancestor of the Pressable sees the drag, not only
 * the Pressables inside the panned container.
 *
 * - A pan GestureDetector around the whole panned container (its own bounds
 *   stay behind the keyboard while the container is translated).
 * - A horizontal ScrollView inside the panned container scrolls when the drag
 *   starts on a Pressable chip in it (regression guard: the gesture now enters
 *   above the chip, so the ScrollView intercepts it as with the IME closed).
 */
@RunWith(AndroidJUnit4::class)
class PamTranslatedGestureParityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Volatile
    private var lastGeometry = ""

    private var rootHost: PamRootHost? = null

    @Test
    fun aPanAroundThePannedContainerGetsTheSameDragWithTheKeyboardOpen() {
        val button = Collections.synchronizedList(ArrayList<Int>())
        val pan = Collections.synchronizedList(ArrayList<Int>())
        withComposer(
            events = { id, kind ->
                if (id == BUTTON) button += kind
                if (id == OUTER_PAN) pan += kind
            },
            tree = { width, height ->
                listOf(
                    Mutation.Create(spec(OUTER_PAN, COLUMN, 1, NodeKind.PRESSABLE, panProps("tp-outer-pan"))),
                    Mutation.Create(spec(COMPOSER, OUTER_PAN, 0, NodeKind.KEYBOARD_AVOIDING_VIEW, composerProps())),
                    Mutation.Create(spec(INPUT, COMPOSER, 0, NodeKind.INPUT, mapOf(PropKey.TEST_ID to PropValue.Text("tp-input")))),
                    Mutation.Create(spec(BUTTON, COMPOSER, 1, NodeKind.PRESSABLE, buttonProps())),
                    Mutation.Layout(OUTER_PAN, Frame(0f, height - 72f, width, 72f)),
                    Mutation.Layout(COMPOSER, Frame(0f, height - 72f, width, 72f)),
                    Mutation.Layout(INPUT, Frame(8f, height - 64f, width - 80f, 56f)),
                    Mutation.Layout(BUTTON, Frame(width - 64f, height - 64f, 56f, 56f)),
                )
            },
        ) { activity, keyboardOpen ->
            val send = activity.host.findTagged("tp-send")!!
            val runs = LinkedHashMap<String, Pair<List<Int>, List<Int>>>()
            fun run(name: String, gesture: () -> Unit) {
                button.clear()
                pan.clear()
                gesture()
                SystemClock.sleep(LONG_PRESS_MS + 400L)
                instrumentation.waitForIdleSync()
                runs[name] = button.toList() to pan.toList()
            }
            run("tap") { touch(send, holdMs = 40) }
            run("drag") { touch(send, holdMs = 120, slideByPx = -300f) }
            run("hold+slide") {
                touch(send, holdMs = 120, slideByPx = -300f, holdBeforeSlideMs = LONG_PRESS_MS + 300L)
            }
            assertTrue("drag reaches the outer pan $runs $lastGeometry", runs["drag"]!!.second.contains(GESTURE_UPDATE))
            if (keyboardOpen) {
                instrumentation.runOnMainSync {
                    assertTrue("IME still up", activity.host.findTagged("tp-composer")!!.translationY < 0f)
                }
            }
            runs
        }
    }

    @Test
    fun aHorizontalScrollInsideThePannedContainerScrollsFromAPressableWithTheKeyboardOpen() {
        val chip = Collections.synchronizedList(ArrayList<Int>())
        withComposer(
            events = { id, kind -> if (id == CHIP) chip += kind },
            tree = { width, height ->
                listOf(
                    Mutation.Create(spec(COMPOSER, COLUMN, 1, NodeKind.KEYBOARD_AVOIDING_VIEW, composerProps())),
                    Mutation.Create(spec(INPUT, COMPOSER, 0, NodeKind.INPUT, mapOf(PropKey.TEST_ID to PropValue.Text("tp-input")))),
                    Mutation.Create(spec(STRIP, COMPOSER, 1, NodeKind.SCROLL, mapOf(
                        PropKey.SCROLL_HORIZONTAL to PropValue.Flag(true),
                        PropKey.TEST_ID to PropValue.Text("tp-strip"),
                    ))),
                    Mutation.Create(spec(STRIP_CONTENT, STRIP, 0, NodeKind.ROW)),
                    // One chip wider than the strip: the finger is always on it.
                    Mutation.Create(spec(CHIP, STRIP_CONTENT, 0, NodeKind.PRESSABLE, mapOf(
                        PropKey.ON_PRESS to PropValue.Flag(true),
                        PropKey.ON_PRESS_IN to PropValue.Flag(true),
                        PropKey.ON_PRESS_OUT to PropValue.Flag(true),
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF1565C0),
                        PropKey.TEST_ID to PropValue.Text("tp-chip"),
                    ))),
                    Mutation.Layout(COMPOSER, Frame(0f, height - 120f, width, 120f)),
                    Mutation.Layout(INPUT, Frame(8f, height - 112f, width - 16f, 48f)),
                    Mutation.Layout(STRIP, Frame(0f, height - 56f, width, 48f)),
                    Mutation.Layout(STRIP_CONTENT, Frame(0f, 0f, width * 4f, 48f)),
                    Mutation.Layout(CHIP, Frame(0f, 0f, width * 4f, 48f)),
                )
            },
        ) { activity, keyboardOpen ->
            val strip = activity.host.findTagged("tp-strip")!!
            val chipView = activity.host.findTagged("tp-chip")!!
            val runs = LinkedHashMap<String, Pair<List<Int>, List<Int>>>()
            fun chipLeft(): Int {
                var left = 0
                instrumentation.runOnMainSync {
                    val location = IntArray(2)
                    chipView.getLocationOnScreen(location)
                    left = location[0]
                }
                return left
            }
            chip.clear()
            touch(strip, holdMs = 40)
            SystemClock.sleep(400)
            instrumentation.waitForIdleSync()
            runs["tap"] = chip.toList() to emptyList()
            // Closed: scroll forward; open: scroll back to the start.
            val slide = if (keyboardOpen) 400f else -400f
            val before = chipLeft()
            chip.clear()
            touch(strip, holdMs = 160, slideByPx = slide)
            SystemClock.sleep(800)
            instrumentation.waitForIdleSync()
            val moved = chipLeft() - before
            assertTrue(
                "drag from the chip scrolls the strip (moved=$moved keyboard=$keyboardOpen $lastGeometry)",
                if (keyboardOpen) moved > 100 else moved < -100,
            )
            runs["drag"] = chip.toList() to emptyList()
            if (keyboardOpen) {
                instrumentation.runOnMainSync {
                    assertTrue("IME still up", activity.host.findTagged("tp-composer")!!.translationY < 0f)
                }
            }
            runs
        }
    }

    /**
     * Mounts a chat screen (timeline over a panning composer built by
     * [tree]), runs [gestures] with the IME closed and again with it open and
     * the composer translated, and asserts both runs gave the same events.
     */
    private fun withComposer(
        events: (Long, Int) -> Unit,
        tree: (Float, Float) -> List<Mutation>,
        gestures: (PamTestActivity, Boolean) -> Map<String, Pair<List<Int>, List<Int>>>,
    ) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        var created = false
        rootHost = activity.host
        try {
            waitUntil("host laid out") { activity.host.isLaidOut && activity.host.height > 0 }
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                renderer = PamRenderer(activity, activity.host) { id, kind, _ -> events(id, kind) }
                created = true
                val density = activity.host.resources.displayMetrics.density
                val width = activity.host.width / density
                val height = activity.host.height / density
                // The composer rests above the bottom system bar: below API 29
                // the navigation bar is its own window and a touch injected
                // there is refused (INJECT_EVENTS).
                val bottom = height - systemBarBottom(activity.host) / density
                val composerHeight = 120f
                renderer.commit(listOf(buildList {
                    add(Mutation.Create(spec(SCREEN, 0, 0, NodeKind.SCREEN)))
                    add(Mutation.Create(spec(COLUMN, SCREEN, 0, NodeKind.COLUMN)))
                    add(Mutation.Create(spec(TIMELINE, COLUMN, 0, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFF7F6F2),
                    ))))
                    addAll(tree(width, bottom))
                    add(Mutation.Layout(SCREEN, Frame(0f, 0f, width, height)))
                    add(Mutation.Layout(COLUMN, Frame(0f, 0f, width, height)))
                    add(Mutation.Layout(TIMELINE, Frame(0f, 0f, width, bottom - composerHeight)))
                    add(Mutation.SetRoot(SCREEN))
                }))
            }
            instrumentation.waitForIdleSync()
            lateinit var composer: View
            lateinit var input: EditText
            instrumentation.runOnMainSync {
                composer = requireNotNull(activity.host.findTagged("tp-composer"))
                input = requireNotNull(activity.host.findTagged("tp-input")) as EditText
            }
            waitUntil("input has window focus") { input.isShown && input.hasWindowFocus() }
            // The closed run needs the IME really hidden: some releases (API
            // 26) show it on their own when the input takes the initial focus.
            instrumentation.runOnMainSync {
                input.clearFocus()
                input.context.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(input.windowToken, 0)
            }
            waitUntil("IME hidden and the composer at rest") {
                lastGeometry = "ty=${composer.translationY} ime=${activity.host.imeBottomInset} " +
                    "host=${activity.host.height}"
                activity.host.imeBottomInset == 0 && composer.translationY == 0f
            }
            SystemClock.sleep(300)
            instrumentation.waitForIdleSync()
            val closed = gestures(activity, false)

            instrumentation.runOnMainSync {
                input.requestFocus()
                input.context.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    input.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
                }
            }
            waitUntil("composer panned above the IME") {
                lastGeometry = "ty=${composer.translationY} ime=${activity.host.imeBottomInset} " +
                    "host=${activity.host.height}"
                composer.translationY < 0f
            }
            SystemClock.sleep(800)
            instrumentation.waitForIdleSync()
            val open = gestures(activity, true)
            assertEquals("keyboard open gives the keyboard closed events $lastGeometry", closed, open)
        } finally {
            instrumentation.runOnMainSync {
                if (created) renderer.close()
                activity.finish()
            }
        }
    }

    private fun composerProps() = mapOf(
        PropKey.KEYBOARD_BEHAVIOR to PropValue.Integer(2), // pan
        PropKey.TEST_ID to PropValue.Text("tp-composer"),
    )

    private fun panProps(testId: String) = mapOf(
        PropKey.GESTURE_TYPE to PropValue.Integer(2), // pan
        PropKey.GESTURE_COMPOSITION to PropValue.Integer(2), // simultaneous
        PropKey.GESTURE_MIN_DISTANCE to PropValue.Decimal(8.0),
        PropKey.ON_GESTURE_BEGIN to PropValue.Flag(true),
        PropKey.ON_GESTURE_UPDATE to PropValue.Flag(true),
        PropKey.ON_GESTURE_END to PropValue.Flag(true),
        PropKey.ON_GESTURE_CANCEL to PropValue.Flag(true),
        PropKey.TEST_ID to PropValue.Text(testId),
    )

    private fun buttonProps() = mapOf(
        PropKey.ON_PRESS to PropValue.Flag(true),
        PropKey.ON_LONG_PRESS to PropValue.Flag(true),
        PropKey.ON_PRESS_IN to PropValue.Flag(true),
        PropKey.ON_PRESS_OUT to PropValue.Flag(true),
        PropKey.PRESS_DELAY_LONG_MS to PropValue.Integer(LONG_PRESS_MS),
        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF2E7D32),
        PropKey.TEST_ID to PropValue.Text("tp-send"),
    )

    /** Real injected input (window -> PamRootHost) from the view's on-screen centre, sliding horizontally. */
    private fun touch(view: View, holdMs: Long, slideByPx: Float = 0f, holdBeforeSlideMs: Long = 0L) {
        var x = 0f
        var y = 0f
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            x = location[0] + view.width / 2f
            y = location[1] + view.height / 2f
            val host = requireNotNull(rootHost)
            val hostLocation = IntArray(2)
            host.getLocationOnScreen(hostLocation)
            val covered = maxOf(host.imeBottomInset, systemBarBottom(host))
            assertTrue(
                "touch ($x, $y) inside the app window above the IME/system bar " +
                    "(host bottom ${hostLocation[1] + host.height}, covered $covered) $lastGeometry",
                y < hostLocation[1] + host.height - covered,
            )
        }
        val downAt = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0))
        if (holdBeforeSlideMs > 0) SystemClock.sleep(holdBeforeSlideMs)
        if (slideByPx == 0f) {
            SystemClock.sleep(holdMs)
            inject(MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
            return
        }
        val steps = 8
        for (step in 1..steps) {
            SystemClock.sleep(holdMs / steps)
            inject(
                MotionEvent.obtain(
                    downAt,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE,
                    x + slideByPx * step / steps,
                    y,
                    0,
                ),
            )
        }
        inject(MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x + slideByPx, y, 0))
    }

    private fun systemBarBottom(host: View): Int =
        androidx.core.view.ViewCompat.getRootWindowInsets(host)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.bottom ?: 0

    private fun inject(event: MotionEvent) {
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private fun waitUntil(description: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(30)
        }
        throw AssertionError("Timed out waiting for $description ($lastGeometry)")
    }

    private fun View.findTagged(testId: String): View? {
        if (transitionName == testId) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).findTagged(testId)?.let { return it }
        }
        return null
    }

    private fun spec(
        id: Long,
        parent: Long,
        index: Int,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ) = NodeSpec(id = id, parent = parent, index = index, kind = kind, properties = properties)

    private companion object {
        const val SCREEN = 1L
        const val COLUMN = 2L
        const val TIMELINE = 3L
        const val COMPOSER = 4L
        const val INPUT = 5L
        const val OUTER_PAN = 6L
        const val BUTTON = 7L
        const val STRIP = 8L
        const val STRIP_CONTENT = 9L
        const val CHIP = 10L
        const val LONG_PRESS_MS = 400L
        val GESTURE_UPDATE = EventKind.GESTURE_UPDATE.value
    }
}
