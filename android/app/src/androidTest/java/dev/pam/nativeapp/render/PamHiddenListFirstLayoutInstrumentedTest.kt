package dev.pam.nativeapp.render

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
 * A pushed screen lays out hidden, one frame before its transition shows
 * it. Its list rows on screen mount while they are bound, inside that
 * layout: rows filled in after the list laid them out (at the end of the
 * layout) made each of their views request a second measure and layout
 * pass of the whole window (a warning logged per view, ~65 per screen).
 */
@RunWith(AndroidJUnit4::class)
class PamHiddenListFirstLayoutInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun aHiddenListMountsItsOnScreenRowsAsItBindsThem() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        lateinit var list: PamRecyclerList
        lateinit var screen: View
        // Adapter position -> whether the row had its views when the list attached it.
        val attachedWithContent = LinkedHashMap<Int, Boolean>()
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                val mutations = mutableListOf<Mutation>(
                    Mutation.Create(NodeSpec(1, 0, 0, NodeKind.SCREEN, emptyMap())),
                    Mutation.Create(NodeSpec(2, 1, 0, NodeKind.VIRTUAL_LIST, emptyMap())),
                    Mutation.Layout(1, Frame(0f, 0f, 320f, 480f)),
                    Mutation.Layout(2, Frame(0f, 0f, 320f, 480f)),
                )
                repeat(ROWS) { index ->
                    val cell = CELL_BASE + index
                    val text = TEXT_BASE + index
                    mutations += Mutation.Create(
                        NodeSpec(cell, 2, index, NodeKind.VIEW, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFF7F6F2L))),
                    )
                    mutations += Mutation.Create(
                        NodeSpec(text, cell, 0, NodeKind.TEXT, mapOf(PropKey.TEXT to PropValue.Text("Setting $index"))),
                    )
                    mutations += Mutation.Layout(cell, Frame(0f, index * 56f, 320f, 56f))
                    mutations += Mutation.Layout(text, Frame(16f, 16f, 200f, 24f))
                }
                mutations += Mutation.SetRoot(1)
                renderer.commit(listOf(mutations))
                screen = requireNotNull(renderer.viewForNode(1))
                list = requireNotNull(renderer.viewForNode(2)) as PamRecyclerList
                list.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) {
                        val position = list.getChildAdapterPosition(view)
                        if (position != RecyclerView.NO_POSITION && position !in attachedWithContent) {
                            attachedWithContent[position] = (view as ViewGroup).childCount > 0
                        }
                    }

                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                })
                // A pushed route: laid out hidden, shown at the pre-draw of that frame.
                screen.visibility = View.INVISIBLE
                val observer = screen.viewTreeObserver
                observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        observer.removeOnPreDrawListener(this)
                        screen.visibility = View.VISIBLE
                        return true
                    }
                })
            }
            awaitFrames(instrumentation, 6)
            instrumentation.runOnMainSync {
                val manager = list.layoutManager as LinearLayoutManager
                val last = manager.findLastVisibleItemPosition()
                assertTrue("rows fill the viewport: $last", last in 5 until ROWS - 1)
                val onScreen = (0..last).toList()
                assertEquals(
                    "every row on screen had its views when the hidden list laid it out: $attachedWithContent",
                    onScreen.map { true },
                    onScreen.map { attachedWithContent[it] },
                )
                // And nothing on screen is left empty once shown.
                (0 until list.childCount).map(list::getChildAt).forEach { holder ->
                    val position = list.getChildAdapterPosition(holder)
                    if (position in onScreen) {
                        val text = (holder as ViewGroup).findText()
                        assertEquals("Setting $position", text?.text?.toString())
                        assertTrue(text!!.isLaidOut && text.width > 0)
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                renderer.close()
                activity.finish()
            }
        }
    }

    private fun View.findText(): TextView? {
        if (this is TextView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) getChildAt(index).findText()?.let { return it }
        return null
    }

    private companion object {
        const val ROWS = 30
        const val CELL_BASE = 100L
        const val TEXT_BASE = 1_000L
    }
}
