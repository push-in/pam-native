package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real-world app: after the keyboard closed, the whole screen stayed ~8% darker. The
 * root host absorbs the blurred input's focus and, out of touch mode, Android
 * 8+ painted its default focus highlight over it.
 */
@RunWith(AndroidJUnit4::class)
class PamImeFocusHighlightInstrumentedTest {
    @Test
    fun closingTheKeyboardLeavesNoFocusHighlightOverTheScreen() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.COLUMN, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFF),
                        PropKey.TEST_ID to PropValue.Text("veil-background"),
                    ))),
                    Mutation.Create(node(3, 2, NodeKind.INPUT, mapOf(
                        PropKey.ON_BLUR to PropValue.Flag(true),
                        PropKey.TEST_ID to PropValue.Text("veil-input"),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(3, Frame(16f, 400f, 328f, 56f)),
                    Mutation.SetRoot(1),
                )))
            }
            instrumentation.waitForIdleSync()
            val before = backgroundPixel(instrumentation, activity)
            assertEquals("white background before the IME", Color.WHITE, before or 0xFF000000.toInt())

            lateinit var input: EditText
            instrumentation.runOnMainSync {
                input = requireNotNull(activity.host.findByTransitionName("veil-input")) as EditText
                input.requestFocus()
                input.context.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
            waitUntil(instrumentation, "IME shown") { activity.host.imeBottomInset > 0 }
            Thread.sleep(500)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil(instrumentation, "input blurred after IME hide") {
                activity.host.imeBottomInset == 0 && !input.hasFocus()
            }
            // A hardware/D-pad key or Back from some IMEs leaves touch mode:
            // the state in which the default focus highlight is drawn.
            instrumentation.setInTouchMode(false)
            awaitFrames(instrumentation, 3)
            var focusableInTouchMode = true
            instrumentation.runOnMainSync { focusableInTouchMode = activity.host.isFocusableInTouchMode }
            assertFalse("root must not keep taking focus on touch", focusableInTouchMode)
            val after = backgroundPixel(instrumentation, activity)
            assertEquals(
                "background after the IME closed: %08x vs %08x".format(after, before),
                before,
                after,
            )
            instrumentation.runOnMainSync { renderer.close() }
        } finally {
            instrumentation.setInTouchMode(true)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun backgroundPixel(instrumentation: Instrumentation, activity: PamTestActivity): Int {
        val location = IntArray(2)
        var width = 0
        var height = 0
        instrumentation.runOnMainSync {
            val background = requireNotNull(activity.host.findByTransitionName("veil-background"))
            background.getLocationOnScreen(location)
            width = background.width
            height = background.height
        }
        val capture = stableWindowCapture(instrumentation)
        return capture.getPixel(location[0] + width / 2, location[1] + height / 4)
            .also { capture.recycle() }
    }

    private fun waitUntil(
        instrumentation: Instrumentation,
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            awaitFrames(instrumentation)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ): NodeSpec = NodeSpec(id = id, parent = parent, index = 0, kind = kind, properties = properties)

    private fun View.findByTransitionName(name: String): View? {
        if (transitionName == name) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findByTransitionName(name)?.let { return it }
        }
        return null
    }
}
