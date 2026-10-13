package dev.pam.nativeapp.render

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.KeyboardVisibility
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** API26 has no Type.ime(): visibility must work without a KAV or scroller. */
@RunWith(AndroidJUnit4::class)
class PamLegacyKeyboardVisibilityInstrumentedTest {
    @Test fun plainRootInputReportsVisibilityWithoutKeyboardAvoidingView() = exercise(false)
    @Test fun plainModalInputReportsVisibilityWithoutKeyboardAvoidingView() = exercise(true)

    private fun exercise(modal: Boolean) {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val events = Collections.synchronizedList(ArrayList<Boolean>())
        val visibility = KeyboardVisibility { events.add(it) }
        lateinit var renderer: PamRenderer
        var created = false
        lateinit var input: EditText
        try {
            instrumentation.runOnMainSync {
                @Suppress("DEPRECATION")
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                created = true
                renderer.onKeyboardVisibility = { surface, visible -> visibility.update(surface, if (visible) 1f else 0f) }
                renderer.commit(listOf(listOf(
                    Mutation.Create(NodeSpec(1, 0, 0, NodeKind.SCREEN, emptyMap())),
                    Mutation.Create(NodeSpec(2, 1, 0, (if (modal) NodeKind.MODAL else NodeKind.COLUMN),
                        if (modal) mapOf(PropKey.VISIBLE to PropValue.Flag(true), PropKey.MODAL_PRESENTATION to PropValue.Integer(1)) else emptyMap())),
                    Mutation.Create(NodeSpec(3, 2, 0, NodeKind.INPUT, emptyMap())),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(3, Frame(16f, 80f, 328f, 56f)),
                    Mutation.SetRoot(1),
                )))
                input = renderer.viewForNode(3) as EditText
            }
            fun await(label: String, condition: () -> Boolean) {
                val end = SystemClock.uptimeMillis() + 10_000
                while (SystemClock.uptimeMillis() < end) {
                    var ready = false
                    instrumentation.runOnMainSync { ready = condition() }
                    if (ready) return
                    SystemClock.sleep(30)
                }
                throw AssertionError("$label; modal=$modal events=$events")
            }
            await("input has window focus") { input.isShown && input.hasWindowFocus() }
            instrumentation.runOnMainSync {
                input.requestFocus()
                input.context.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
            await("legacy visible transition") { events.lastOrNull() == true }
            SystemClock.sleep(500)
            assertEquals(listOf(true), events)
            instrumentation.runOnMainSync {
                visibility.setActive(false)
                renderer.setKeyboardVisibilityActive(false)
            }
            assertEquals(listOf(true, false), events)
            instrumentation.runOnMainSync {
                visibility.setActive(true)
                renderer.setKeyboardVisibilityActive(true)
            }
            await("resume reconciles still-visible IME without blur") { events.lastOrNull() == true }
            assertEquals(listOf(true, false, true), events)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await("legacy hidden transition after Back") { events.lastOrNull() == false }
            assertEquals(listOf(true, false, true, false), events)
            instrumentation.runOnMainSync {
                assertTrue("Back hides IME without requiring input blur", input.hasFocus())
                visibility.setActive(false)
                renderer.setKeyboardVisibilityActive(false)
            }
            SystemClock.sleep(250)
            assertEquals("inactive observer emits no stale visibility", listOf(true, false, true, false), events)
        } finally {
            instrumentation.runOnMainSync {
                if (created) renderer.close()
                activity.finish()
            }
        }
    }
}
