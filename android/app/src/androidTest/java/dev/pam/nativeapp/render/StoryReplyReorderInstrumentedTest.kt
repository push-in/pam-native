package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.inputmethod.EditorInfo
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real encoder sequence from StoryFooter: caption disappears on focus, then
 * quick reactions disappear after the first character. Input identity and both
 * ancestor parents remain unchanged throughout these sibling reorders. */
@RunWith(AndroidJUnit4::class)
class StoryReplyReorderInstrumentedTest {
    @Test
    fun focusedReplySurvivesAncestorSiblingMovesWithoutBlurOrLostSelection() = verifyReorder(false)

    @Test
    fun materializedAncestorsPreserveReplyFocusAndSelectionAcrossSiblingMoves() = verifyReorder(true)

    private fun verifyReorder(materialized: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        lateinit var input: EditText
        var rendererCreated = false
        var blurCount = 0
        val manager = activity.getSystemService(InputMethodManager::class.java)
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun node(id: Long, parent: Long, index: Int, kind: NodeKind,
                 props: Map<PropKey, PropValue> = emptyMap()) =
            Mutation.Create(NodeSpec(id, parent, index, kind, props))
        fun assertEditing(label: String) {
            assertTrue("$label retains focus", input.hasFocus())
            assertTrue("$label retains attachment", input.isAttachedToWindow)
            assertEquals("$label emits no spurious blur", 0, blurCount)
            assertEquals("PAM reply", input.text.toString())
            assertEquals(4, input.selectionStart)
            assertEquals(9, input.selectionEnd)
            assertTrue("$label retains the served IME input", manager.isActive(input))
        }
        try {
            onMain {
                renderer = PamRenderer(activity, activity.host) { id, event, _ ->
                    if (id == 7L && event == EventKind.BLUR.value) blurCount++
                }
                rendererCreated = true
                renderer.commit(listOf(listOf(
                    node(1, 0, 0, NodeKind.SCREEN),
                    node(2, 1, 0, NodeKind.COLUMN),
                    node(3, 2, 0, NodeKind.TEXT, mapOf(PropKey.TEXT to PropValue.Text("Story caption"))),
                    node(4, 2, 1, NodeKind.COLUMN, if (materialized) mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFF7F6F2L)) else emptyMap()),
                    node(5, 4, 0, NodeKind.ROW),
                    node(6, 5, 0, NodeKind.COLUMN, if (materialized) mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFFL)) else emptyMap()),
                    node(7, 6, 0, NodeKind.INPUT, mapOf(
                        PropKey.VALUE to PropValue.Text(""),
                        PropKey.ON_BLUR to PropValue.Flag(true),
                        PropKey.ON_FOCUS to PropValue.Flag(true),
                    )),
                    Mutation.Layout(1, Frame(0f, 0f, 320f, 480f)),
                    Mutation.Layout(2, Frame(0f, 0f, 320f, 240f)),
                    Mutation.Layout(3, Frame(0f, 0f, 320f, 40f)),
                    Mutation.Layout(4, Frame(0f, 40f, 320f, 180f)),
                    Mutation.Layout(5, Frame(0f, 0f, 320f, 60f)),
                    Mutation.Layout(6, Frame(0f, 0f, 300f, 60f)),
                    Mutation.Layout(7, Frame(0f, 0f, 300f, 60f)),
                    Mutation.SetRoot(1),
                )))
                val field = PamRenderer::class.java.getDeclaredField("views").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val views = field.get(renderer) as android.util.LongSparseArray<View>
                input = views[7] as EditText
                input.requestFocus()
                manager.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
            val deadline = SystemClock.uptimeMillis() + 5_000
            var served = false
            while (!served && SystemClock.uptimeMillis() < deadline) {
                onMain { served = input.hasWindowFocus() && manager.isActive(input) }
                if (!served) SystemClock.sleep(40)
            }
            assertTrue("Visible reply establishes an IME connection before mutations", served)
            onMain {
                input.setText("PAM reply")
                input.setSelection(4, 9)
                assertEditing("Initial input")
                renderer.commit(listOf(listOf(
                    Mutation.Remove(3),
                    Mutation.Move(4, 2, 0),
                    node(8, 4, 0, NodeKind.ROW),
                    node(9, 8, 0, NodeKind.TEXT, mapOf(PropKey.TEXT to PropValue.Text("Quick reactions"))),
                    Mutation.Layout(8, Frame(0f, 0f, 300f, 40f)),
                    Mutation.Layout(9, Frame(0f, 0f, 300f, 40f)),
                    Mutation.Move(5, 4, 1),
                )))
                assertEditing("Focus layout")
                renderer.commit(listOf(listOf(Mutation.Remove(8), Mutation.Move(5, 4, 0))))
                assertEditing("First-character layout")
                val connection = input.onCreateInputConnection(EditorInfo())
                assertNotNull("Reply still accepts IME text", connection)
                assertTrue(connection!!.commitText("answer", 1))
                assertEquals("PAM answer", input.text.toString())
                assertEquals(0, blurCount)
                // A genuine focus exit must still dispatch blur: preserving a
                // move must not globally suppress focus lifecycle callbacks.
                input.clearFocus()
                assertEquals(1, blurCount)
            }
        } finally {
            onMain {
                if (rendererCreated) renderer.close()
                activity.finish()
            }
        }
    }
}
