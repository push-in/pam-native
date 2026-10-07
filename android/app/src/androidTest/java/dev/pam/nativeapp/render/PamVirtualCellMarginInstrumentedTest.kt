package dev.pam.nativeapp.render

import android.content.Intent
import android.view.View
import android.view.ViewGroup
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
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * React Native/Yoga parity: a VirtualizedList/VirtualGrid cell is the margin
 * box of its root. The engine insets the root frame by its margins; the host
 * slot (row holder) spans the margins and the root sits at them. Sticky
 * ScrollView children pin and are pushed by their margin box.
 */
@RunWith(AndroidJUnit4::class)
class PamVirtualCellMarginInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val density = instrumentation.targetContext.resources.displayMetrics.density

    private val cellMargins = mapOf(
        PropKey.MARGIN_TOP to PropValue.Decimal(6.0),
        PropKey.MARGIN_BOTTOM to PropValue.Decimal(10.0),
        PropKey.MARGIN_HORIZONTAL to PropValue.Decimal(12.0),
    )

    @Test
    fun verticalCellSlotsIncludeRootMarginsAndOffsetTheRoot() = withRenderer(
        listProps = emptyMap(),
        cells = listOf(
            Triple(3L, cellMargins, Frame(12f, 6f, 296f, 40f)),
            Triple(5L, emptyMap(), Frame(0f, 56f, 320f, 30f)),
            Triple(
                6L,
                mapOf(PropKey.MARGIN to PropValue.Decimal(8.0), PropKey.HEIGHT to PropValue.Decimal(20.0)),
                Frame(8f, 94f, 304f, 20f),
            ),
            Triple(7L, emptyMap(), Frame(0f, 122f, 320f, 30f)),
        ),
    ) { renderer, list ->
        val first = renderer.viewForNode(3)!!
        val holder = first.holderIn(list)
        assertSame("the cell root is hosted directly in its holder", holder, first.parent)
        assertEquals("slot = 6 + 40 + 10", dp(56f), holder.height.toFloat(), 1.5f)
        assertEquals(dp(12f), first.left.toFloat(), 1.5f)
        assertEquals(dp(6f), first.top.toFloat(), 1.5f)
        assertEquals(dp(296f), first.width.toFloat(), 1.5f)
        assertEquals(dp(40f), first.height.toFloat(), 1.5f)
        assertEquals(dp(56f), renderer.viewForNode(5)!!.holderIn(list).top.toFloat(), 1.5f)
        val explicit = renderer.viewForNode(6)!!
        assertEquals("explicit height keeps the root's own height", dp(20f), explicit.height.toFloat(), 1.5f)
        assertEquals(dp(36f), explicit.holderIn(list).height.toFloat(), 1.5f)
        assertEquals(dp(122f), renderer.viewForNode(7)!!.holderIn(list).top.toFloat(), 1.5f)
    }

    @Test
    fun gridAndFullSpanSlotsIncludeRootMargins() = withRenderer(
        listProps = mapOf(PropKey.LIST_NUM_COLUMNS to PropValue.Integer(2)),
        cells = listOf(
            Triple(
                3L,
                cellMargins + (PropKey.LIST_FULL_SPAN to PropValue.Flag(true)),
                Frame(12f, 6f, 296f, 20f),
            ),
            Triple(5L, cellMargins, Frame(12f, 42f, 136f, 50f)),
            Triple(6L, emptyMap(), Frame(160f, 36f, 160f, 66f)),
            Triple(7L, emptyMap(), Frame(0f, 102f, 160f, 30f)),
        ),
    ) { renderer, list ->
        val header = renderer.viewForNode(3)!!
        assertEquals("full-span slot = 6 + 20 + 10", dp(36f), header.holderIn(list).height.toFloat(), 1.5f)
        assertEquals(dp(12f), header.left.toFloat(), 1.5f)
        assertEquals(dp(296f), header.width.toFloat(), 1.5f)
        val cell = renderer.viewForNode(5)!!
        val cellHolder = cell.holderIn(list)
        assertEquals(dp(36f), cellHolder.top.toFloat(), 1.5f)
        assertEquals("grid slot = 6 + 50 + 10", dp(66f), cellHolder.height.toFloat(), 1.5f)
        assertEquals(dp(12f), cell.left.toFloat(), 1.5f)
        assertEquals(dp(6f), cell.top.toFloat(), 1.5f)
        assertEquals(dp(136f), cell.width.toFloat(), 1.5f)
        assertEquals(dp(102f), renderer.viewForNode(7)!!.holderIn(list).top.toFloat(), 1.5f)
    }

    @Test
    fun horizontalCellSlotsIncludeRootMargins() = withRenderer(
        listProps = mapOf(PropKey.LIST_HORIZONTAL to PropValue.Flag(true)),
        cells = listOf(
            Triple(3L, cellMargins, Frame(12f, 6f, 100f, 464f)),
            Triple(5L, emptyMap(), Frame(124f, 0f, 80f, 480f)),
        ),
    ) { renderer, list ->
        val first = renderer.viewForNode(3)!!
        assertEquals("slot = 12 + 100 + 12", dp(124f), first.holderIn(list).width.toFloat(), 1.5f)
        assertEquals(dp(12f), first.left.toFloat(), 1.5f)
        assertEquals(dp(6f), first.top.toFloat(), 1.5f)
        assertEquals(dp(124f), renderer.viewForNode(5)!!.holderIn(list).left.toFloat(), 1.5f)
    }

    @Test
    fun stickyScrollChildPinsAndIsPushedByItsMarginBox() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        try {
            val sticky = mapOf(
                PropKey.STICKY_HEADER to PropValue.Flag(true),
                PropKey.MARGIN_TOP to PropValue.Decimal(8.0),
                PropKey.MARGIN_BOTTOM to PropValue.Decimal(4.0),
                PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF00FF00),
            )
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(
                    listOf(
                        buildList {
                            add(Mutation.Create(spec(1, 0, 0, NodeKind.SCREEN)))
                            add(Mutation.Create(spec(2, 1, 0, NodeKind.SCROLL)))
                            add(Mutation.Create(spec(3, 2, 0, NodeKind.COLUMN, BACKGROUND)))
                            add(Mutation.Create(spec(4, 2, 1, NodeKind.COLUMN, sticky)))
                            add(Mutation.Create(spec(5, 2, 2, NodeKind.COLUMN, BACKGROUND)))
                            add(Mutation.Create(spec(6, 2, 3, NodeKind.COLUMN, sticky)))
                            add(Mutation.Create(spec(7, 2, 4, NodeKind.COLUMN, BACKGROUND)))
                            add(Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)))
                            add(Mutation.Layout(2, Frame(0f, 0f, 300f, 400f)))
                            add(Mutation.Layout(3, Frame(0f, 0f, 300f, 92f)))
                            // Margin box 92..144, then content up to 392.
                            add(Mutation.Layout(4, Frame(0f, 100f, 300f, 40f)))
                            add(Mutation.Layout(5, Frame(0f, 144f, 300f, 248f)))
                            add(Mutation.Layout(6, Frame(0f, 400f, 300f, 40f)))
                            add(Mutation.Layout(7, Frame(0f, 444f, 300f, 1200f)))
                            add(Mutation.SetRoot(1))
                        },
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val scroll = renderer.viewForNode(2) as PamScrollContainer
                val header = renderer.viewForNode(4)!!
                val active = scroll.getChildAt(0) as ViewGroup
                active.scrollTo(0, dp(300f).toInt())
                // Pinned with its top margin kept above it: visual top 308.
                assertEquals(dp(308f), header.top + header.translationY, 2f)
                active.scrollTo(0, dp(360f).toInt())
                // The next margin box (392) pushes this one (52 tall): 392 - 52 + 8.
                assertEquals(dp(348f), header.top + header.translationY, 2f)
                active.scrollTo(0, dp(50f).toInt())
                assertEquals(0f, header.translationY)
            }
        } finally {
            instrumentation.runOnMainSync {
                renderer.close()
                activity.finish()
            }
        }
    }

    private fun withRenderer(
        listProps: Map<PropKey, PropValue>,
        cells: List<Triple<Long, Map<PropKey, PropValue>, Frame>>,
        assertions: (PamRenderer, PamRecyclerList) -> Unit,
    ) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(
                    listOf(
                        buildList {
                            add(Mutation.Create(spec(1, 0, 0, NodeKind.SCREEN)))
                            add(Mutation.Create(spec(2, 1, 0, NodeKind.VIRTUAL_LIST, listProps)))
                            add(Mutation.Layout(1, Frame(0f, 0f, 320f, 480f)))
                            add(Mutation.Layout(2, Frame(0f, 0f, 320f, 480f)))
                            cells.forEachIndexed { index, (id, props, frame) ->
                                add(Mutation.Create(spec(id, 2, index, NodeKind.VIEW, props + BACKGROUND)))
                                add(Mutation.Layout(id, frame))
                            }
                            add(Mutation.SetRoot(1))
                        },
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            waitFrames(4)
            instrumentation.runOnMainSync {
                assertions(renderer, activity.host.findListView())
            }
        } finally {
            instrumentation.runOnMainSync {
                renderer.close()
                activity.finish()
            }
        }
    }

    private fun waitFrames(count: Int) {
        repeat(count) {
            val latch = java.util.concurrent.CountDownLatch(1)
            instrumentation.runOnMainSync {
                android.view.Choreographer.getInstance().postFrameCallback { latch.countDown() }
            }
            latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    private fun dp(value: Float): Float = value * density

    private fun View.holderIn(list: PamRecyclerList): View {
        var current: View = this
        while (current.parent !== list) current = current.parent as View
        return current
    }

    private fun View.findListView(): PamRecyclerList {
        if (this is PamRecyclerList) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                runCatching { return getChildAt(index).findListView() }
            }
        }
        error("No PamRecyclerList mounted")
    }

    private fun spec(
        id: Long,
        parent: Long,
        index: Int,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ) = NodeSpec(id = id, parent = parent, index = index, kind = kind, properties = properties)

    private companion object {
        val BACKGROUND = mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFEEEEEE))
    }
}
