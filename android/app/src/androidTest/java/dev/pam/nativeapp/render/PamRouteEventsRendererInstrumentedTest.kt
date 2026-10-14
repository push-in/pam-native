package dev.pam.nativeapp.render

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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** `on:routeCovered` / `on:routeFocused` through the renderer, once per change. */
@RunWith(AndroidJUnit4::class)
class PamRouteEventsRendererInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun pushAndPopDispatchCoveredThenFocusedOnce() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val events = mutableListOf<Pair<Long, Int>>()
        lateinit var renderer: PamRenderer
        try {
            main {
                renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                    if (kind == EventKind.ROUTE_COVERED.value || kind == EventKind.ROUTE_FOCUSED.value) events += id to kind
                }
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.NAVIGATION_HOST, mapOf(PropKey.NAVIGATION_DURATION_MS to PropValue.Integer(0)))),
                    Mutation.Create(node(2, 1, NodeKind.SCREEN)),
                    Mutation.Create(node(3, 2, NodeKind.VIEW, mapOf(
                        PropKey.ON_ROUTE_COVERED to PropValue.Flag(true),
                        PropKey.ON_ROUTE_FOCUSED to PropValue.Flag(true),
                    ))),
                    Mutation.Create(node(6, 2, NodeKind.TEXT, mapOf(
                        PropKey.TEXT to PropValue.Text("0:00"),
                        PropKey.MEDIA_CLOCK to PropValue.Text("reel"),
                        PropKey.MEDIA_CLOCK_FORMAT to PropValue.Integer(2),
                    ), index = 1)),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 720f)),
                    Mutation.Layout(3, Frame(0f, 0f, 360f, 100f)),
                    Mutation.Layout(6, Frame(0f, 100f, 360f, 40f)),
                    Mutation.SetRoot(1),
                )))
            }
            settle()
            main {
                assertEquals("A focused mount reports nothing", emptyList<Pair<Long, Int>>(), events.toList())
                val bound = PamRenderer::class.java.getDeclaredField("mediaBoundIds").apply { isAccessible = true }.get(renderer) as Set<*>
                assertTrue("mediaClock registers its text", 6L in bound)
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(4, 1, NodeKind.SCREEN, index = 1)),
                    Mutation.Layout(4, Frame(0f, 0f, 360f, 720f)),
                    Mutation.Update(1, PropKey.NAVIGATION_OPERATION, PropValue.Integer(2)),
                    Mutation.Update(1, PropKey.NAVIGATION_REVISION, PropValue.Integer(1)),
                )))
            }
            await { events.size == 1 }
            settle()
            main {
                assertEquals(listOf(3L to EventKind.ROUTE_COVERED.value), events.toList())
                renderer.commit(listOf(listOf(
                    Mutation.Update(1, PropKey.NAVIGATION_OPERATION, PropValue.Integer(3)),
                    Mutation.Update(1, PropKey.NAVIGATION_REVISION, PropValue.Integer(2)),
                )))
            }
            await { events.size == 2 }
            settle()
            main {
                assertEquals(
                    listOf(3L to EventKind.ROUTE_COVERED.value, 3L to EventKind.ROUTE_FOCUSED.value),
                    events.toList(),
                )
            }
        } finally {
            main {
                renderer.close()
                activity.finish()
            }
        }
    }

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
        index: Int = 0,
    ) = NodeSpec(id = id, parent = parent, index = index, kind = kind, properties = properties)

    private fun settle() {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        instrumentation.waitForIdleSync()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            main { done = condition() }
            if (done) return
            SystemClock.sleep(25)
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
