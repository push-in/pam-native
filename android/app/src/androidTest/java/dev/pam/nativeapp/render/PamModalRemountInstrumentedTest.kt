package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
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
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * A Modal removed and mounted again with the same key gets the same node ids
 * (identity is derived from the key). The second mount must report
 * `on:layout` again and play its entrance animation again.
 */
@RunWith(AndroidJUnit4::class)
class PamModalRemountInstrumentedTest {
    @Test
    fun aModalRemountedWithTheSameKeyReportsLayoutAndAnimatesItsEntranceAgain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        val layouts = Collections.synchronizedList(ArrayList<Long>())
        PamMotionPolicy.reduceMotionOverride = false
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                    if (kind == EventKind.LAYOUT.value) layouts += id
                }
                renderer.commit(listOf(listOf(Mutation.Create(node(1, 0, NodeKind.SCREEN))) + modal() + listOf(
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.SetRoot(1),
                )))
            }
            awaitEntrance(instrumentation, renderer, "first mount")
            waitUntil(instrumentation, "first on:layout") { layouts.count { it == 3L } == 1 }

            instrumentation.runOnMainSync { renderer.commit(listOf(listOf(
                // The engine removes a subtree children first, one Remove per node.
                Mutation.Remove(4),
                Mutation.Remove(3),
                Mutation.Remove(2),
            ))) }
            waitUntil(instrumentation, "modal dismissed") { !renderer.hasPresentedModal() }
            Thread.sleep(300)

            instrumentation.runOnMainSync { renderer.commit(listOf(modal())) }
            awaitEntrance(instrumentation, renderer, "remount")
            waitUntil(instrumentation, "on:layout after the remount") { layouts.count { it == 3L } == 2 }
            Thread.sleep(200)
            assertEquals("one on:layout per mount", 2, layouts.count { it == 3L })
            instrumentation.runOnMainSync { renderer.close() }
        } finally {
            PamMotionPolicy.reduceMotionOverride = null
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun modal(): List<Mutation> = listOf(
        Mutation.Create(node(2, 1, NodeKind.MODAL, mapOf(
            PropKey.VISIBLE to PropValue.Flag(true),
            PropKey.MODAL_PRESENTATION to PropValue.Integer(1),
            PropKey.MODAL_ANIMATION_TYPE to PropValue.Integer(2), // slide
        ))),
        Mutation.Create(node(3, 2, NodeKind.COLUMN, mapOf(PropKey.ON_LAYOUT to PropValue.Flag(true)))),
        Mutation.Create(node(4, 3, NodeKind.VIEW, mapOf(
            PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFF),
        ))),
        Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
        Mutation.Layout(3, Frame(0f, 0f, 360f, 640f)),
        Mutation.Layout(4, Frame(0f, 0f, 360f, 320f)),
    )

    /** The slide entrance starts below its resting place and settles at 0. */
    private fun awaitEntrance(instrumentation: Instrumentation, renderer: PamRenderer, label: String) {
        waitUntil(instrumentation, "$label: entrance animation running") {
            (modalContent(renderer)?.translationY ?: 0f) > 0f
        }
        waitUntil(instrumentation, "$label: entrance settled") {
            renderer.hasPresentedModal() && modalContent(renderer)?.translationY == 0f
        }
    }

    private fun modalContent(renderer: PamRenderer): View? {
        val field = PamRenderer::class.java.getDeclaredField("views").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val views = field.get(renderer) as android.util.LongSparseArray<View>
        val modal = (0 until views.size()).mapNotNull { views.valueAt(it) as? PamModalHost }.firstOrNull()
            ?: return null
        val content = PamModalHost::class.java.getDeclaredField("content").apply { isAccessible = true }
        return content.get(modal) as? View
    }

    private fun waitUntil(
        instrumentation: Instrumentation,
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(8)
        }
        throw AssertionError("Timed out waiting for $description")
    }

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ): NodeSpec = NodeSpec(id = id, parent = parent, index = 0, kind = kind, properties = properties)
}
