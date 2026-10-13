package dev.pam.nativeapp.render

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamKeyboardDismissInstrumentedTest {
    @Test
    fun explicitBackKeepsImeClosedWithRetainedHeightAvoidingInput() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        lateinit var input: EditText
        var created = false
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun visible(): Boolean = activity.host.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        fun await(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 6_000
            var passed = false
            while (!passed && SystemClock.uptimeMillis() < deadline) {
                onMain { passed = condition() }
                if (!passed) SystemClock.sleep(40)
            }
            assertTrue(label, passed)
        }
        fun node(id: Long, parent: Long, kind: NodeKind, props: Map<PropKey, PropValue> = emptyMap()) =
            Mutation.Create(NodeSpec(id, parent, 0, kind, props))
        try {
            await("Host is laid out") { activity.host.height > 0 }
            onMain {
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                created = true
                val density = activity.resources.displayMetrics.density
                val width = activity.host.width / density
                val height = activity.host.height / density
                renderer.commit(listOf(listOf(
                    node(1, 0, NodeKind.SCREEN),
                    node(2, 1, NodeKind.KEYBOARD_AVOIDING_VIEW, mapOf(PropKey.KEYBOARD_BEHAVIOR to PropValue.Integer(1))),
                    node(3, 2, NodeKind.SCROLL),
                    node(4, 3, NodeKind.INPUT),
                    Mutation.Layout(1, Frame(0f, 0f, width, height)),
                    Mutation.Layout(2, Frame(0f, 0f, width, height)),
                    Mutation.Layout(3, Frame(0f, 0f, width, height)),
                    Mutation.Layout(4, Frame(16f, height - 100f, width - 32f, 52f)),
                    Mutation.SetRoot(1),
                )))
                val field = PamRenderer::class.java.getDeclaredField("views").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val views = field.get(renderer) as android.util.LongSparseArray<View>
                input = views[4] as EditText
                input.setText("bounded fixture draft")
            }
            await("Input window ready") { input.isAttachedToWindow && input.hasWindowFocus() }
            onMain {
                input.requestFocus()
                activity.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
            await("IME initially visible") { visible() }
            SystemClock.sleep(500)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("BACK hides the IME") { !visible() }
            // Include delayed KAV reconciliation and animation completion.
            val end = SystemClock.uptimeMillis() + 1_200
            while (SystemClock.uptimeMillis() < end) {
                onMain {
                    assertFalse("Explicitly dismissed IME must not reopen", visible())
                    assertEquals("bounded fixture draft", input.text.toString())
                }
                SystemClock.sleep(40)
            }
        } finally {
            onMain {
                if (created) renderer.close()
                activity.finish()
            }
        }
    }
}
