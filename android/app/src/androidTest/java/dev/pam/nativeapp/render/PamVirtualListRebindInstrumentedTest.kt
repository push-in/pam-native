package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One commit that changes props of every visible keyed cell (a timezone
 * change rewriting every timestamp, a refresh) must never draw an empty list
 * viewport: cells rebind in place and keep their content on every frame.
 */
@RunWith(AndroidJUnit4::class)
class PamVirtualListRebindInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun updatingEveryVisibleCellInOneCommitNeverDrawsABlankViewport() {
        assertNoBlankFrames { index ->
            listOf(
                Mutation.Update(TEXT_BASE + index, PropKey.TEXT, PropValue.Text("${10 + index}:4$index · read")),
                Mutation.Layout(TEXT_BASE + index, Frame(8f, 8f, 180f + index, 40f)),
            )
        }
    }

    @Test
    fun promotingEveryVisibleCellRootInOneCommitNeverDrawsABlankViewport() {
        // A host-only prop turns the layout-only cell root into a real view,
        // which re-materializes the whole cell.
        assertNoBlankFrames { index ->
            listOf(
                Mutation.Update(CELL_BASE + index, PropKey.BACKGROUND_COLOR, PropValue.Integer(0xFFEFEFEFL)),
                Mutation.Update(TEXT_BASE + index, PropKey.TEXT, PropValue.Text("Updated $index")),
            )
        }
    }

    @Test
    fun resizingEveryCellOfAListRestingAtItsEndNeverDrawsABlankViewport() {
        assertNoBlankFrames(restAtEnd = true) { index ->
            listOf(
                Mutation.Update(TEXT_BASE + index, PropKey.TEXT, PropValue.Text("${10 + index}:4$index\nedited")),
                Mutation.Layout(CELL_BASE + index, Frame(0f, index * 64f, 320f, 64f)),
                Mutation.Layout(TEXT_BASE + index, Frame(8f, 8f, 200f, 48f)),
            )
        }
    }

    @Test
    fun rekeyingEveryCellInOneCommitNeverDrawsABlankViewport() {
        // Keys derived from content (for example a formatted timestamp)
        // replace every row: removals and insertions land in one commit.
        assertNoBlankFrames { index ->
            val cell = CELL_BASE + 500 + index
            val text = TEXT_BASE + 500 + index
            listOf(
                Mutation.Remove(TEXT_BASE + index),
                Mutation.Remove(CELL_BASE + index),
                Mutation.Create(spec(cell, 2, index, NodeKind.VIEW)),
                Mutation.Create(spec(text, cell, 0, NodeKind.TEXT, mapOf(PropKey.TEXT to PropValue.Text("New $index")))),
                Mutation.Layout(cell, Frame(0f, index * 56f, 320f, 56f)),
                Mutation.Layout(text, Frame(8f, 8f, 160f, 40f)),
            )
        }
    }

    private fun assertNoBlankFrames(restAtEnd: Boolean = false, change: (Int) -> List<Mutation>) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        lateinit var list: PamRecyclerList
        val blankFrames = mutableListOf<String>()
        var observedFrames = 0
        var watching = false
        val listener = ViewTreeObserver.OnDrawListener {
            if (!watching) return@OnDrawListener
            observedFrames++
            val empty = emptyVisibleCells(list)
            if (empty.isNotEmpty()) blankFrames += "frame $observedFrames: empty cells $empty"
        }
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                val mutations = mutableListOf<Mutation>(
                    Mutation.Create(spec(1, 0, 0, NodeKind.SCREEN)),
                    Mutation.Create(spec(2, 1, 0, NodeKind.VIRTUAL_LIST)),
                    Mutation.Layout(1, Frame(0f, 0f, 320f, 480f)),
                    Mutation.Layout(2, Frame(0f, 0f, 320f, 480f)),
                )
                repeat(CELLS) { index ->
                    mutations += Mutation.Create(spec(CELL_BASE + index, 2, index, NodeKind.VIEW))
                    mutations += Mutation.Create(
                        spec(
                            TEXT_BASE + index,
                            CELL_BASE + index,
                            0,
                            NodeKind.TEXT,
                            mapOf(PropKey.TEXT to PropValue.Text("09:4$index")),
                        ),
                    )
                    mutations += Mutation.Layout(CELL_BASE + index, Frame(0f, index * 56f, 320f, 56f))
                    mutations += Mutation.Layout(TEXT_BASE + index, Frame(8f, 8f, 160f, 40f))
                }
                mutations += Mutation.SetRoot(1)
                renderer.commit(listOf(mutations))
                list = activity.host.findListView()
                list.viewTreeObserver.addOnDrawListener(listener)
            }
            instrumentation.waitForIdleSync()
            waitFrames(6)
            if (restAtEnd) {
                instrumentation.runOnMainSync { list.scrollBy(0, 100_000) }
                waitFrames(6)
            }
            instrumentation.runOnMainSync {
                assertEquals("cells must be visible before the change", emptyList<Long>(), emptyVisibleCells(list))
                assertTrue(visibleHolders(list).size >= 5)
                watching = true
                renderer.commit(listOf((0 until CELLS).flatMap(change)))
            }
            waitFrames(8)
            instrumentation.runOnMainSync { watching = false }
            assertTrue("no frame was drawn after the commit", observedFrames > 0)
            assertEquals(emptyList<String>(), blankFrames)
        } finally {
            instrumentation.runOnMainSync {
                list.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(listener)
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
        SystemClock.sleep(16)
    }

    private fun visibleHolders(list: ViewGroup): List<ViewGroup> {
        val viewport = Rect(0, 0, list.width, list.height)
        return (0 until list.childCount)
            .map(list::getChildAt)
            .filterIsInstance<ViewGroup>()
            .filter { holder ->
                holder.visibility == View.VISIBLE &&
                    Rect(holder.left, holder.top, holder.right, holder.bottom).intersect(viewport)
            }
    }

    /** Ids (adapter positions) of visible cells with no drawable text. */
    private fun emptyVisibleCells(list: PamRecyclerList): List<Int> =
        visibleHolders(list)
            .filterNot { holder -> holder.hasVisibleText() }
            .map { holder -> list.getChildAdapterPosition(holder) }

    private fun View.hasVisibleText(): Boolean {
        if (visibility != View.VISIBLE || alpha == 0f) return false
        if (this is TextView) return text.isNotEmpty() && width > 0 && height > 0 && isLaidOut
        if (this !is ViewGroup) return false
        return (0 until childCount).any { getChildAt(it).hasVisibleText() }
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
        const val CELLS = 12
        const val CELL_BASE = 100L
        const val TEXT_BASE = 1_000L
    }
}
