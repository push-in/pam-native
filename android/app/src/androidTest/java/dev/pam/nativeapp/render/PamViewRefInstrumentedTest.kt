package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** `ViewRef` imperative input operations and measurement by `nativeRef`. */
@RunWith(AndroidJUnit4::class)
class PamViewRefInstrumentedTest {
    @Test
    fun focusSelectionClearBlurAndMeasureByNativeRef() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        lateinit var input: EditText
        var created = false
        val events = mutableListOf<Pair<Int, String>>()
        fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)
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
                renderer = PamRenderer(activity, activity.host) { _, kind, payload ->
                    events += kind to String(payload)
                }
                created = true
                val density = activity.resources.displayMetrics.density
                val width = activity.host.width / density
                val height = activity.host.height / density
                renderer.commit(listOf(listOf(
                    node(1, 0, NodeKind.SCREEN),
                    node(2, 1, NodeKind.VIEW, mapOf(PropKey.NATIVE_REF to PropValue.Text("card"))),
                    node(3, 2, NodeKind.INPUT, mapOf(
                        PropKey.NATIVE_REF to PropValue.Text("composer"),
                        PropKey.ON_CHANGE to PropValue.Flag(true),
                    )),
                    Mutation.Layout(1, Frame(0f, 0f, width, height)),
                    Mutation.Layout(2, Frame(10f, 40f, 200f, 120f)),
                    Mutation.Layout(3, Frame(8f, 12f, 180f, 48f)),
                    Mutation.SetRoot(1),
                )))
                val field = PamRenderer::class.java.getDeclaredField("views").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val views = field.get(renderer) as android.util.LongSparseArray<View>
                input = views[3] as EditText
                input.setText("olá mundo")
            }
            await("Input window ready") { input.isAttachedToWindow && input.hasWindowFocus() && input.isLaidOut }
            onMain { renderer.nativeRefInputOperation("composer", "focus") }
            await("focus() focuses the input") { input.hasFocus() }
            onMain {
                assertTrue(renderer.nativeRefInputFocused("composer"))
                renderer.nativeRefInputOperation("composer", "setSelection", 2, 99)
                assertEquals(2, input.selectionStart)
                assertEquals(input.text.length, input.selectionEnd)
                val frame = renderer.nativeRefFrame("card")
                assertEquals(10.0, frame[0], 0.5)
                assertEquals(40.0, frame[1], 0.5)
                assertEquals(200.0, frame[2], 0.5)
                assertEquals(120.0, frame[3], 0.5)
                val inputFrame = renderer.nativeRefFrame("composer")
                assertEquals(18.0, inputFrame[6], 0.5)
                assertEquals(52.0, inputFrame[7], 0.5)
                assertTrue("window y includes the root offset", inputFrame[5] >= inputFrame[7] - 0.5)
                renderer.nativeRefInputOperation("composer", "clear")
                assertEquals("", input.text.toString())
            }
            await("clear() reports the empty value through on:change") {
                events.any { it.first == EventKind.CHANGE.value && it.second.isEmpty() }
            }
            onMain { renderer.nativeRefInputOperation("composer", "blur") }
            await("blur() removes focus") { !input.hasFocus() }
            onMain {
                val missing = runCatching { renderer.nativeRefFrame("missing") }.exceptionOrNull()
                assertTrue(missing?.message.orEmpty().contains("No mounted view has nativeRef missing"))
                val notInput = runCatching { renderer.nativeRefInputOperation("card", "focus") }.exceptionOrNull()
                assertTrue(notInput?.message.orEmpty().contains("not a text input"))
            }
        } finally {
            onMain {
                if (created) renderer.close()
                activity.finish()
            }
        }
    }
}
