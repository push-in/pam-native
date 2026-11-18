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
 * Zé chat's send/mic button: a Pressable with press and longPress inside a
 * panning KeyboardAvoidingView translated by the IME. Touches on it are
 * routed by PamRootHost's translated-target dispatcher, which must deliver
 * the whole gesture: a tap yields only press (with pressIn/pressOut), a hold
 * only longPress, and the button never stays pressed.
 */
@RunWith(AndroidJUnit4::class)
class PamTranslatedPressableInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Volatile
    private var lastGeometry = ""

    @Volatile
    private var lastTouch = ""

    @Test
    fun tapAndHoldOnAPannedPressableEachYieldOneCallbackAndReleaseThePress() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val events = Collections.synchronizedList(ArrayList<Int>())
        lateinit var renderer: PamRenderer
        var created = false
        try {
            waitUntil("host laid out") { activity.host.isLaidOut && activity.host.height > 0 }
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                    if (id == BUTTON) events += kind
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
                        PropKey.TEST_ID to PropValue.Text("tp-composer"),
                    ))),
                    Mutation.Create(spec(INPUT, COMPOSER, 0, NodeKind.INPUT, mapOf(
                        PropKey.TEST_ID to PropValue.Text("tp-input"),
                    ))),
                    Mutation.Create(spec(BUTTON, COMPOSER, 1, NodeKind.PRESSABLE, mapOf(
                        PropKey.ON_PRESS to PropValue.Flag(true),
                        PropKey.ON_LONG_PRESS to PropValue.Flag(true),
                        PropKey.ON_PRESS_IN to PropValue.Flag(true),
                        PropKey.ON_PRESS_OUT to PropValue.Flag(true),
                        PropKey.PRESS_DELAY_LONG_MS to PropValue.Integer(LONG_PRESS_MS),
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF2E7D32),
                        PropKey.TEST_ID to PropValue.Text("tp-send"),
                    ))),
                    Mutation.Layout(SCREEN, Frame(0f, 0f, width, height)),
                    Mutation.Layout(COLUMN, Frame(0f, 0f, width, height)),
                    Mutation.Layout(TIMELINE, Frame(0f, 0f, width, height - 72f)),
                    Mutation.Layout(COMPOSER, Frame(0f, height - 72f, width, 72f)),
                    // Frames are in surface coordinates.
                    Mutation.Layout(INPUT, Frame(8f, height - 64f, width - 80f, 56f)),
                    Mutation.Layout(BUTTON, Frame(width - 64f, height - 64f, 56f, 56f)),
                    Mutation.SetRoot(SCREEN),
                )))
            }
            instrumentation.waitForIdleSync()
            lateinit var composer: View
            lateinit var input: EditText
            lateinit var button: PamPressable
            instrumentation.runOnMainSync {
                composer = requireNotNull(activity.host.findTagged("tp-composer"))
                input = requireNotNull(activity.host.findTagged("tp-input")) as EditText
                button = requireNotNull(activity.host.findTagged("tp-send")) as PamPressable
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
                    "legacy=${activity.host.legacyImeInsets.bottom} " +
                    "routed=${activity.host.isTranslatedTouchTarget(button)} " +
                    "host=${activity.host.height} top=${composer.top}"
                composer.translationY < 0f && activity.host.isTranslatedTouchTarget(button)
            }
            // Let the IME animation and the posted keyboard reconciles settle.
            SystemClock.sleep(800)
            instrumentation.waitForIdleSync()

            // A quick tap: press only, then the long-press delay elapses idle.
            events.clear()
            touch(button, holdMs = 40)
            SystemClock.sleep(LONG_PRESS_MS + 400L)
            instrumentation.waitForIdleSync()
            assertEquals("tap $lastGeometry touch=$lastTouch", listOf(PRESS_IN, PRESS_OUT, PRESS), events.toList())
            instrumentation.runOnMainSync {
                assertFalse("button released after tap", button.isPressed)
                assertTrue("IME still up after tap", composer.translationY < 0f)
            }

            // A hold: long press only, and the release ends the press.
            events.clear()
            touch(button, holdMs = LONG_PRESS_MS + 300L)
            SystemClock.sleep(400)
            instrumentation.waitForIdleSync()
            assertEquals("hold $lastGeometry", listOf(PRESS_IN, LONG_PRESS, PRESS_OUT), events.toList())
            instrumentation.runOnMainSync {
                assertFalse("button released after hold", button.isPressed)
            }

            // A drag off the button cancels: neither press nor long press.
            events.clear()
            touch(button, holdMs = 40, slideOffPx = 400f)
            SystemClock.sleep(LONG_PRESS_MS + 400L)
            instrumentation.waitForIdleSync()
            assertEquals("slide off $lastGeometry", listOf(PRESS_IN, PRESS_OUT), events.toList())
        } finally {
            instrumentation.runOnMainSync {
                if (created) renderer.close()
                activity.finish()
            }
        }
    }

    /** Real injected input (window → PamRootHost) at the button's on-screen centre. */
    private fun touch(button: View, holdMs: Long, slideOffPx: Float = 0f) {
        var x = 0f
        var y = 0f
        instrumentation.runOnMainSync {
            val location = IntArray(2)
            button.getLocationOnScreen(location)
            x = location[0] + button.width / 2f
            y = location[1] + button.height / 2f
        }
        val downAt = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0))
        if (slideOffPx != 0f) {
            SystemClock.sleep(holdMs / 2)
            val now = SystemClock.uptimeMillis()
            inject(MotionEvent.obtain(downAt, now, MotionEvent.ACTION_MOVE, x - slideOffPx / 2f, y, 0))
            inject(MotionEvent.obtain(downAt, now + 8, MotionEvent.ACTION_MOVE, x - slideOffPx, y, 0))
            SystemClock.sleep(holdMs / 2)
            val upAt = SystemClock.uptimeMillis()
            inject(MotionEvent.obtain(downAt, upAt, MotionEvent.ACTION_UP, x - slideOffPx, y, 0))
            return
        }
        SystemClock.sleep(holdMs)
        val upAt = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(downAt, upAt, MotionEvent.ACTION_UP, x, y, 0))
    }

    private fun inject(event: MotionEvent) {
        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        try {
            lastTouch = "${event.actionMasked}@${event.rawX},${event.rawY}"
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
        const val BUTTON = 6L
        const val LONG_PRESS_MS = 400L
        val PRESS = EventKind.PRESS.value
        val LONG_PRESS = EventKind.LONG_PRESS.value
        val PRESS_IN = EventKind.PRESS_IN.value
        val PRESS_OUT = EventKind.PRESS_OUT.value
    }
}
