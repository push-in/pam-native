package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

/**
 * Zé's message-actions overlay: a backdrop Pressable wraps a panel Pressable
 * with a ScrollView of action tiles. Every tile tap must dispatch that tile's
 * own node id (the platform TouchDelegate used to re-centre taps on the
 * panel, so every tile fired the one in the middle).
 */
@RunWith(AndroidJUnit4::class)
class PamNestedPressableInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun everyTileInsideNestedPressablesDispatchesItsOwnPress() = assertDistinctTilePresses(inModal = false)

    @Test
    fun everyTileInsideAModalDispatchesItsOwnPressAcrossReopens() = assertDistinctTilePresses(inModal = true)

    private fun assertDistinctTilePresses(inModal: Boolean) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val presses = mutableListOf<Long>()
        lateinit var renderer: PamRenderer
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                    if (kind == EventKind.PRESS.value) presses += id
                }
            }
            repeat(if (inModal) 2 else 1) { round ->
                instrumentation.runOnMainSync { renderer.commit(listOf(overlay(inModal, round))) }
                instrumentation.waitForIdleSync()
                // Hit-slop delegates are computed after layout (posted).
                SystemClock.sleep(150)
                instrumentation.waitForIdleSync()
                presses.clear()
                val tapped = mutableListOf<Long>()
                for (index in 0 until TILES) {
                    val id = tileId(round, index)
                    val reachable = tap(activity, "tile-$round-$index")
                    if (reachable) tapped += id
                }
                assertEquals("round $round taps", tapped, presses.toList())
                assertEquals(true, tapped.size >= 6)
                // The backdrop outside the panel still closes the overlay.
                presses.clear()
                tap(activity, "backdrop-$round", corner = true)
                assertEquals(listOf(BACKDROP + round), presses.toList())
                if (inModal) {
                    instrumentation.runOnMainSync {
                        renderer.commit(listOf(listOf(Mutation.Remove(MODAL + round))))
                    }
                    instrumentation.waitForIdleSync()
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                renderer.close()
                activity.finish()
            }
        }
    }

    /** Taps the centre (or top-left corner) of the view tagged [testId]; false when off screen. */
    private fun tap(activity: PamTestActivity, testId: String, corner: Boolean = false): Boolean {
        var root: View? = null
        var x = 0f
        var y = 0f
        instrumentation.runOnMainSync {
            val target = findAnywhere(activity, testId) ?: return@runOnMainSync
            val location = IntArray(2)
            target.getLocationOnScreen(location)
            val rootView = target.rootView
            val rootLocation = IntArray(2)
            rootView.getLocationOnScreen(rootLocation)
            val visible = android.graphics.Rect()
            if (!target.getGlobalVisibleRect(visible) || visible.height() < target.height) return@runOnMainSync
            x = location[0] - rootLocation[0] + if (corner) 4f else target.width / 2f
            y = location[1] - rootLocation[1] + if (corner) 4f else target.height / 2f
            root = rootView
        }
        val dispatchRoot = root ?: return false
        instrumentation.runOnMainSync {
            val downAt = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(downAt, downAt, MotionEvent.ACTION_DOWN, x, y, 0)
            val up = MotionEvent.obtain(downAt, downAt + 40, MotionEvent.ACTION_UP, x, y, 0)
            dispatchRoot.dispatchTouchEvent(down)
            dispatchRoot.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }
        instrumentation.waitForIdleSync()
        return true
    }

    private fun findAnywhere(activity: PamTestActivity, testId: String): View? {
        activity.host.rootView.findTagged(testId)?.let { return it }
        val roots = runCatching {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val instance = global.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            (global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>)
        }.getOrDefault(emptyList())
        return roots.asReversed().firstNotNullOfOrNull { it.findTagged(testId) }
    }

    private fun View.findTagged(testId: String): View? {
        if (transitionName == testId) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).findTagged(testId)?.let { return it }
        }
        return null
    }

    private fun overlay(inModal: Boolean, round: Int): List<Mutation> {
        val mutations = mutableListOf<Mutation>()
        val backdropParent: Long
        if (round == 0) {
            mutations += Mutation.Create(spec(SCREEN, 0, 0, NodeKind.SCREEN))
            mutations += Mutation.Layout(SCREEN, Frame(0f, 0f, 360f, 720f))
        }
        if (inModal) {
            mutations += Mutation.Create(
                spec(
                    MODAL + round, SCREEN, 0, NodeKind.MODAL,
                    mapOf(
                        PropKey.VISIBLE to PropValue.Flag(true),
                        PropKey.MODAL_PRESENTATION to PropValue.Integer(1),
                    ),
                ),
            )
            mutations += Mutation.Layout(MODAL + round, Frame(0f, 0f, 360f, 720f))
            backdropParent = MODAL + round
        } else {
            backdropParent = SCREEN
        }
        val backdrop = BACKDROP + round
        val panel = PANEL + round
        val scroll = SCROLL + round
        val column = COLUMN + round
        mutations += Mutation.Create(spec(backdrop, backdropParent, 0, NodeKind.PRESSABLE, pressable("backdrop-$round")))
        mutations += Mutation.Create(spec(panel, backdrop, 0, NodeKind.PRESSABLE, pressable("panel-$round")))
        mutations += Mutation.Create(spec(scroll, panel, 0, NodeKind.SCROLL))
        mutations += Mutation.Create(spec(column, scroll, 0, NodeKind.COLUMN))
        mutations += Mutation.Layout(backdrop, Frame(0f, 0f, 360f, 720f))
        mutations += Mutation.Layout(panel, Frame(20f, 100f, 320f, 480f))
        mutations += Mutation.Layout(scroll, Frame(20f, 100f, 320f, 480f))
        mutations += Mutation.Layout(column, Frame(20f, 100f, 320f, TILES * 48f + 8f))
        repeat(TILES) { index ->
            val tile = tileId(round, index)
            val label = tile + 10_000
            mutations += Mutation.Create(spec(tile, column, index, NodeKind.PRESSABLE, pressable("tile-$round-$index")))
            mutations += Mutation.Create(
                spec(label, tile, 0, NodeKind.TEXT, mapOf(PropKey.TEXT to PropValue.Text("Action $index"))),
            )
            mutations += Mutation.Layout(tile, Frame(20f, 104f + index * 48f, 320f, 48f))
            mutations += Mutation.Layout(label, Frame(36f, 118f + index * 48f, 200f, 20f))
        }
        if (round == 0) mutations += Mutation.SetRoot(SCREEN)
        return mutations
    }

    private fun pressable(testId: String) = mapOf(
        PropKey.ON_PRESS to PropValue.Flag(true),
        PropKey.TEST_ID to PropValue.Text(testId),
    )

    private fun tileId(round: Int, index: Int): Long = TILE_BASE + round * 100L + index

    private fun spec(
        id: Long,
        parent: Long,
        index: Int,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ) = NodeSpec(id = id, parent = parent, index = index, kind = kind, properties = properties)

    private companion object {
        const val TILES = 12
        const val SCREEN = 1L
        const val MODAL = 10L
        const val BACKDROP = 20L
        const val PANEL = 30L
        const val SCROLL = 40L
        const val COLUMN = 50L
        const val TILE_BASE = 1_000L
    }
}
