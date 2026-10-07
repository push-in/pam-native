package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Keyed list sections (`listSection` + `activeSection`): switching tabs over
 * one VirtualizedList swaps the adapter rows natively. The rows of an
 * inactive section keep their native views (no remount when switching
 * back) and every section keeps its own scroll position while the tab rail
 * is pinned, like React Native tabs over one FlatList.
 */
@RunWith(AndroidJUnit4::class)
class PamVirtualListSectionsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val density = instrumentation.targetContext.resources.displayMetrics.density

    @Test
    fun switchingBackReattachesTheSameViewsWithoutRemounting() = withList { list, renderer ->
        val mediaContent = onMain { requireNotNull(renderer.viewForNode(content(MEDIA, 0))) }
        assertTrue(onMain { mediaContent.isAttachedToWindow })

        select(renderer, "files")
        onMain {
            assertFalse("the inactive section is not displayed", mediaContent.isAttachedToWindow)
            assertSame("its views stay materialized", mediaContent, renderer.viewForNode(content(MEDIA, 0)))
            val filesContent = requireNotNull(renderer.viewForNode(content(FILES, 0)))
            assertTrue("the active section is displayed", filesContent.isAttachedToWindow)
            assertTrue("only shared and active rows are listed", list.adapter!!.itemCount == 2 + ROWS)
        }
        val filesContent = onMain { renderer.viewForNode(content(FILES, 0))!! }

        select(renderer, "media")
        onMain {
            assertSame("switching back reuses the same views", mediaContent, renderer.viewForNode(content(MEDIA, 0)))
            assertTrue(mediaContent.isAttachedToWindow)
            assertFalse(filesContent.isAttachedToWindow)
            assertNoBlankRows(list)
        }
        select(renderer, "files")
        onMain { assertSame(filesContent, renderer.viewForNode(content(FILES, 0))) }
    }

    @Test
    fun eachSectionKeepsItsScrollPositionWhileTheRailIsPinned() = withList { list, renderer ->
        scrollBy(list, 600f)
        val mediaTop = onMain { firstRow(list) }
        assertTrue("the rail is pinned", onMain { list.pinnedStickyHeader() != null })

        select(renderer, "files")
        onMain {
            val (id, top) = firstRow(list)
            // The rail sits at the top, the section's first row right below it.
            assertEquals(RAIL, id)
            assertEquals(0, top)
            val firstFiles = renderer.viewForNode(content(FILES, 0))!!.holderIn(list)
            assertEquals(dp(RAIL_HEIGHT).toFloat(), firstFiles.top.toFloat(), 2f)
        }
        scrollBy(list, 200f)
        val filesTop = onMain { firstRow(list) }

        select(renderer, "media")
        onMain {
            assertEquals("media returns to its own offset", mediaTop, firstRow(list))
            assertNoBlankRows(list)
        }
        select(renderer, "files")
        onMain { assertEquals("files returns to its own offset", filesTop, firstRow(list)) }
    }

    @Test
    fun sectionsStartBelowAVisibleHeaderAtTheCurrentOffset() = withList { list, renderer ->
        scrollBy(list, 20f)
        val before = onMain { firstRow(list) }
        select(renderer, "files")
        onMain {
            assertEquals("the shared header does not move", before, firstRow(list))
            assertNotNull(renderer.viewForNode(content(FILES, 0))?.takeIf { it.isAttachedToWindow })
        }
    }

    @Test
    fun removingAParkedSectionReleasesItsViews() = withList { _, renderer ->
        select(renderer, "files")
        onMain {
            renderer.commit(
                listOf(
                    (0 until ROWS).flatMap { row ->
                        listOf(Mutation.Remove(content(MEDIA, row)), Mutation.Remove(MEDIA + row))
                    },
                ),
            )
        }
        waitFrames(3)
        onMain {
            assertEquals(null, renderer.viewForNode(content(MEDIA, 0)))
            assertTrue(renderer.viewForNode(content(FILES, 0))!!.isAttachedToWindow)
        }
    }

    private fun select(renderer: PamRenderer, section: String) {
        onMain {
            renderer.commit(listOf(listOf(Mutation.Update(LIST, PropKey.LIST_ACTIVE_SECTION, PropValue.Text(section)))))
        }
        waitFrames(4)
    }

    /** First laid-out row (adapter item id) and its top in px. */
    private fun firstRow(list: PamRecyclerList): Pair<Long, Int> {
        val manager = list.layoutManager as androidx.recyclerview.widget.LinearLayoutManager
        val position = manager.findFirstVisibleItemPosition()
        val view = manager.findViewByPosition(position)!!
        return list.adapter!!.getItemId(position) to view.top
    }

    private fun assertNoBlankRows(list: PamRecyclerList) {
        val pinned = list.pinnedStickyHeader()
        for (index in 0 until list.childCount) {
            val holder = list.getChildAt(index) as ViewGroup
            if (holder === pinned || holder.bottom <= 0 || holder.top >= list.height) continue
            if (pinned != null && holder.bottom <= pinned.bottom) continue
            assertTrue("row ${list.getChildAdapterPosition(holder)} is blank", holder.childCount > 0)
        }
    }

    private fun withList(block: (PamRecyclerList, PamRenderer) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        lateinit var list: PamRecyclerList
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(listOf(mutations()))
                list = activity.host.findListView()
            }
            instrumentation.waitForIdleSync()
            waitFrames(4)
            block(list, renderer)
        } finally {
            instrumentation.runOnMainSync {
                renderer.close()
                activity.finish()
            }
        }
    }

    private fun mutations(): List<Mutation> = buildList {
        add(Mutation.Create(spec(1, 0, 0, NodeKind.SCREEN)))
        add(Mutation.Create(spec(LIST, 1, 0, NodeKind.VIRTUAL_LIST, mapOf(PropKey.LIST_ACTIVE_SECTION to PropValue.Text("media")))))
        add(Mutation.Layout(1, Frame(0f, 0f, 320f, 480f)))
        add(Mutation.Layout(LIST, Frame(0f, 0f, 320f, 480f)))
        add(Mutation.Create(spec(HEADER, LIST, 0, NodeKind.VIEW, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF00FF00)))))
        add(Mutation.Layout(HEADER, Frame(0f, 0f, 320f, HEADER_HEIGHT)))
        add(
            Mutation.Create(
                spec(
                    RAIL, LIST, 1, NodeKind.VIEW,
                    mapOf(
                        PropKey.STICKY_HEADER to PropValue.Flag(true),
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF0000FF),
                    ),
                ),
            ),
        )
        add(Mutation.Layout(RAIL, Frame(0f, HEADER_HEIGHT, 320f, RAIL_HEIGHT)))
        var index = 2
        // Like the engine: every section of the block starts at its origin.
        for ((base, key, extent) in listOf(Triple(MEDIA, "media", 72f), Triple(FILES, "files", 56f))) {
            var y = HEADER_HEIGHT + RAIL_HEIGHT
            for (row in 0 until ROWS) {
                val id = base + row
                add(
                    Mutation.Create(
                        spec(
                            id, LIST, index++, NodeKind.VIEW,
                            mapOf(
                                PropKey.LIST_SECTION to PropValue.Text(key),
                                PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFEEEEEE),
                            ),
                        ),
                    ),
                )
                add(Mutation.Create(spec(content(base, row), id, 0, NodeKind.VIEW, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF333333)))))
                add(Mutation.Layout(id, Frame(0f, y, 320f, extent)))
                add(Mutation.Layout(content(base, row), Frame(16f, y + 8f, 120f, extent - 16f)))
                y += extent
            }
        }
        add(Mutation.SetRoot(1))
    }

    private fun scrollBy(list: PamRecyclerList, dpDelta: Float) {
        onMain { list.scrollBy(0, dp(dpDelta).toInt()) }
        waitFrames(4)
    }

    private fun dp(value: Float): Int = (value * density + 0.5f).toInt()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
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

    private fun content(base: Long, row: Int): Long = base + 500L + row

    private companion object {
        const val ROWS = 30
        const val LIST = 2L
        const val HEADER = 3L
        const val RAIL = 4L
        const val MEDIA = 1_000L
        const val FILES = 2_000L
        const val HEADER_HEIGHT = 160f
        const val RAIL_HEIGHT = 48f
    }
}
