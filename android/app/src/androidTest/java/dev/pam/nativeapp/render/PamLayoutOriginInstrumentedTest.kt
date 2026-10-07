package dev.pam.nativeapp.render

import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.BatchDecoder
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import java.nio.ByteBuffer
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CSS offsets and margins move boxes outside their parent. A negative layout
 * origin must mount, and a host tree left diverged by a failed batch must be
 * cleared before the engine's retained tree is replayed.
 */
@RunWith(AndroidJUnit4::class)
class PamLayoutOriginInstrumentedTest {
    @Test
    fun negativeOriginFromTheEngineMountsOutsideTheRoot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity()
        lateinit var renderer: PamRenderer
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                // Rust golden: Layout(2, x = -34, y = -200, 390 x 200).
                val layout = BatchDecoder.decode(
                    ByteBuffer.wrap(
                        hex("504e4231010001000000050200000000000000000008c2000048c30000c34300004843"),
                    ),
                )
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(screen()),
                            Mutation.Create(box(2, 1)),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                        ) + layout + Mutation.SetRoot(1),
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val root = activity.host.getChildAt(0) as ViewGroup
                val child = root.getChildAt(0)
                val density = child.resources.displayMetrics.density
                // Both edges snap to the pixel grid independently (Yoga), so
                // the size is the distance between the snapped edges: at
                // density 2.75 a 390 dp box from -34 dp spans 1072 px, not
                // round(1072.5).
                fun edge(dp: Float) = (dp * density).roundToInt()
                assertEquals(edge(-34f), child.left)
                assertEquals(edge(-200f), child.top)
                assertEquals(edge(-34f + 390f) - edge(-34f), child.width)
                assertEquals(edge(-200f + 200f) - edge(-200f), child.height)
                renderer.close()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun resetTreeClearsAHalfAppliedTreeBeforeARemount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity()
        try {
            instrumentation.runOnMainSync {
                val renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                try {
                    renderer.commit(listOf(fullTree(childIds = listOf(2L))))
                    // Node 9's parent was in a batch this host never applied:
                    // the commit throws after mounting node 3.
                    val failure = runCatching {
                        renderer.commit(
                            listOf(
                                listOf(
                                    Mutation.Create(box(3, 1)),
                                    Mutation.Create(box(9, 8)),
                                ),
                            ),
                        )
                    }.exceptionOrNull()
                    assertTrue(failure?.message.orEmpty(), failure?.message?.contains("cannot contain children") == true)
                    assertTrue(renderer.hasNode(3))

                    renderer.resetTree()
                    assertFalse(renderer.hasNode(1))
                    assertFalse(renderer.hasNode(3))
                    assertEquals(0, activity.host.childCount)

                    // The engine's remount replays its retained tree (2 and 4).
                    renderer.commit(listOf(fullTree(childIds = listOf(2L, 4L))))
                    assertTrue(renderer.hasNode(2))
                    assertTrue(renderer.hasNode(4))
                    assertFalse(renderer.hasNode(3))
                    assertEquals(1, activity.host.childCount)
                    assertEquals(2, (activity.host.getChildAt(0) as ViewGroup).childCount)

                    // Later patches apply on the replayed tree.
                    renderer.commit(listOf(listOf(Mutation.Create(box(5, 4)))))
                    assertTrue(renderer.hasNode(5))
                } finally {
                    renderer.close()
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun fullTree(childIds: List<Long>): List<Mutation> = buildList {
        add(Mutation.SetRoot(1))
        add(Mutation.Create(screen()))
        childIds.forEachIndexed { index, id -> add(Mutation.Create(box(id, 1, index))) }
        add(Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)))
        childIds.forEachIndexed { index, id ->
            add(Mutation.Layout(id, Frame(-10f, index * 40f - 14f, 100f, 40f)))
        }
    }

    private fun screen(): NodeSpec = NodeSpec(
        id = 1,
        parent = 0,
        index = 0,
        kind = NodeKind.SCREEN,
        properties = mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFF)),
    )

    private fun box(id: Long, parent: Long, index: Int = 0): NodeSpec = NodeSpec(
        id = id,
        parent = parent,
        index = index,
        kind = NodeKind.COLUMN,
        properties = mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFF0000)),
    )

    private fun launchActivity(): PamTestActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return instrumentation.startActivitySync(
            android.content.Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as PamTestActivity
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
