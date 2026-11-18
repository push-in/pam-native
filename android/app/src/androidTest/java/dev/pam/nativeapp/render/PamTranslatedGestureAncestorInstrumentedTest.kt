package dev.pam.nativeapp.render

import android.app.Instrumentation
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Zé chat's voice button: a pan GestureDetector (simultaneous) around the
 * send/mic Pressable inside a panning KeyboardAvoidingView translated by the
 * IME. The translated-target dispatcher must run the gesture through the
 * detector as Android does with the keyboard closed: a drag from the button
 * reaches gestureUpdate/gestureEnd and releases the button (pressOut), a tap
 * stays a plain press, a hold then slide (slide to cancel) gives longPress,
 * gesture updates and pressOut.
 */
@RunWith(AndroidJUnit4::class)
class PamTranslatedGestureAncestorInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Volatile
    private var lastGeometry = ""

    @Test
    fun aPanAroundATranslatedPressableReceivesTheDragAndThePressableKeepsItsGesture() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val button = Collections.synchronizedList(ArrayList<Int>())
        val pan = Collections.synchronizedList(ArrayList<Int>())
        lateinit var renderer: PamRenderer
        var created = false
        try {
            waitUntil("host laid out") { activity.host.isLaidOut && activity.host.height > 0 }
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                    if (id == BUTTON) button += kind
                    if (id == GESTURE) pan += kind
                }
                created = true
                val density = activity.host.resources.displayMetrics.density
                val width = activity.host.width / density
                val height = activity.host.height / density
                renderer.commit(listOf(listOf(
                    Mutation.Create(spec(SCREEN, 0, 0, NodeKind.SCREEN)),
                    Mutation.Create(spec(COLUMN, SCREEN, 0, NodeKind.COLUMN)),
                    Mutation.Create(spec(TIMELINE, COLUMN, 0, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFF7F6F2),
                    ))),
                    Mutation.Create(spec(COMPOSER, COLUMN, 1, NodeKind.KEYBOARD_AVOIDING_VIEW, mapOf(
                        PropKey.KEYBOARD_BEHAVIOR to PropValue.Integer(2), // pan
                        PropKey.TEST_ID to PropValue.Text("tg-composer"),
                    ))),
                    Mutation.Create(spec(INPUT, COMPOSER, 0, NodeKind.INPUT, mapOf(
                        PropKey.TEST_ID to PropValue.Text("tg-input"),
                    ))),
                    Mutation.Create(spec(GESTURE, COMPOSER, 1, NodeKind.PRESSABLE, mapOf(
                        PropKey.GESTURE_TYPE to PropValue.Integer(2), // pan
                        PropKey.GESTURE_COMPOSITION to PropValue.Integer(2), // simultaneous
                        PropKey.GESTURE_MIN_DISTANCE to PropValue.Decimal(8.0),
                        PropKey.ON_GESTURE_BEGIN to PropValue.Flag(true),
                        PropKey.ON_GESTURE_UPDATE to PropValue.Flag(true),
                        PropKey.ON_GESTURE_END to PropValue.Flag(true),
                        PropKey.ON_GESTURE_CANCEL to PropValue.Flag(true),
                        PropKey.TEST_ID to PropValue.Text("tg-pan"),
                    ))),
                    Mutation.Create(spec(BUTTON, GESTURE, 0, NodeKind.PRESSABLE, mapOf(
                        PropKey.ON_PRESS to PropValue.Flag(true),
                        PropKey.ON_LONG_PRESS to PropValue.Flag(true),
                        PropKey.ON_PRESS_IN to PropValue.Flag(true),
                        PropKey.ON_PRESS_OUT to PropValue.Flag(true),
                        PropKey.PRESS_DELAY_LONG_MS to PropValue.Integer(LONG_PRESS_MS),
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF2E7D32),
                        PropKey.TEST_ID to PropValue.Text("tg-send"),
                    ))),
                    Mutation.Layout(SCREEN, Frame(0f, 0f, width, height)),
                    Mutation.Layout(COLUMN, Frame(0f, 0f, width, height)),
                    Mutation.Layout(TIMELINE, Frame(0f, 0f, width, height - 72f)),
                    Mutation.Layout(COMPOSER, Frame(0f, height - 72f, width, 72f)),
                    // Frames are in surface coordinates.
                    Mutation.Layout(INPUT, Frame(8f, height - 64f, width - 80f, 56f)),
                    Mutation.Layout(GESTURE, Frame(width - 64f, height - 64f, 56f, 56f)),
                    Mutation.Layout(BUTTON, Frame(width - 64f, height - 64f, 56f, 56f)),
                    Mutation.SetRoot(SCREEN),
                )))
            }
            instrumentation.waitForIdleSync()
            lateinit var composer: View
            lateinit var input: EditText
            lateinit var send: PamPressable
            instrumentation.runOnMainSync {
                composer = requireNotNull(activity.host.findTagged("tg-composer"))
                input = requireNotNull(activity.host.findTagged("tg-input")) as EditText
                send = requireNotNull(activity.host.findTagged("tg-send")) as PamPressable
            }
            waitUntil("input has window focus") { input.isShown && input.hasWindowFocus() }
            instrumentation.runOnMainSync {
                input.requestFocus()
                input.context.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    input.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
                }
            }
            waitUntil("composer panned above the IME and the button routed as a translated target") {
                lastGeometry = "ty=${composer.translationY} ime=${activity.host.imeBottomInset} " +
                    "routed=${activity.host.isTranslatedTouchTarget(send)} host=${activity.host.height}"
                composer.translationY < 0f && activity.host.isTranslatedTouchTarget(send)
            }
            SystemClock.sleep(800)
            instrumentation.waitForIdleSync()

            // A quick tap: the button presses, the pan never starts.
            reset(button, pan)
            touch(send, holdMs = 40)
            SystemClock.sleep(LONG_PRESS_MS + 400L)
            instrumentation.waitForIdleSync()
            assertEquals("tap button $lastGeometry", listOf(PRESS_IN, PRESS_OUT, PRESS), button.toList())
            assertFalse("tap pan ${pan.toList()}", pan.contains(GESTURE_UPDATE) || pan.contains(GESTURE_END))
            instrumentation.runOnMainSync { assertFalse("button released after tap", send.isPressed) }

            // A drag from the button: the enclosing pan tracks it, the button
            // releases without press or long press.
            reset(button, pan)
            touch(send, holdMs = 120, slideOffPx = 300f)
            SystemClock.sleep(LONG_PRESS_MS + 400L)
            instrumentation.waitForIdleSync()
            assertTrue("drag pan ${pan.toList()} $lastGeometry", pan.contains(GESTURE_UPDATE))
            assertEquals("drag pan ends ${pan.toList()}", GESTURE_END, pan.lastOrNull())
            assertEquals("drag button", listOf(PRESS_IN, PRESS_OUT), button.toList())
            instrumentation.runOnMainSync { assertFalse("button released after drag", send.isPressed) }

            // Hold, then slide (slide to cancel a recording): long press,
            // then the pan follows the finger and the button releases.
            reset(button, pan)
            touch(send, holdMs = 120, slideOffPx = 300f, holdBeforeSlideMs = LONG_PRESS_MS + 300L)
            SystemClock.sleep(400)
            instrumentation.waitForIdleSync()
            assertEquals("hold+slide button", listOf(PRESS_IN, LONG_PRESS, PRESS_OUT), button.toList())
            assertTrue("hold+slide pan ${pan.toList()}", pan.contains(GESTURE_UPDATE))
            assertEquals("hold+slide pan ends ${pan.toList()}", GESTURE_END, pan.lastOrNull())
            instrumentation.runOnMainSync {
                assertFalse("button released after hold+slide", send.isPressed)
                assertTrue("IME still up", composer.translationY < 0f)
            }
        } finally {
            instrumentation.runOnMainSync {
                if (created) renderer.close()
                activity.finish()
            }
        }
    }

    private fun reset(vararg lists: MutableList<Int>) = lists.forEach { it.clear() }

    /** Real injected input (window -> PamRootHost) from the button's on-screen centre. */
    private fun touch(view: View, holdMs: Long, slideOffPx: Float = 0f, holdBeforeSlideMs: Long = 0L) {
        var x = 0f
        var y = 0f
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            x = location[0] + view.width / 2f
            y = location[1] + view.height / 2f
        }
        val downAt = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0))
        if (holdBeforeSlideMs > 0) SystemClock.sleep(holdBeforeSlideMs)
        if (slideOffPx == 0f) {
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
                    x - slideOffPx * step / steps,
                    y,
                    0,
                ),
            )
        }
        inject(MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x - slideOffPx, y, 0))
    }

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
        const val GESTURE = 6L
        const val BUTTON = 7L
        const val LONG_PRESS_MS = 400L
        val PRESS = EventKind.PRESS.value
        val LONG_PRESS = EventKind.LONG_PRESS.value
        val PRESS_IN = EventKind.PRESS_IN.value
        val PRESS_OUT = EventKind.PRESS_OUT.value
        val GESTURE_UPDATE = EventKind.GESTURE_UPDATE.value
        val GESTURE_END = EventKind.GESTURE_END.value
    }
}
