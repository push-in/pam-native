package dev.pam.nativeapp.render

import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A window slide swaps placeholder rows and real rows at the same positions:
 * the list rebinds them in place (a change) instead of a remove + insert,
 * which would relayout every attached row mid-fling.
 */
@RunWith(AndroidJUnit4::class)
class PamListWindowSlideRebindTest {
    @Test
    fun rowsReplacedInPlaceAreChangesAndRealInsertsStayInserts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ops = mutableListOf<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val extents = (1L..20L).associateWith { 600 }
            val adapter = RichRecyclerAdapter(context, listOf(1L, 2L, 3L, 4L, 5L), extents, { _, _ -> }, { _, _ -> })
            adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) { ops += "change $positionStart+$itemCount" }
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { ops += "change $positionStart+$itemCount" }
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) { ops += "insert $positionStart+$itemCount" }
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) { ops += "remove $positionStart+$itemCount" }
                override fun onChanged() { ops += "all" }
            })
            // Rows 2..3 (placeholders) become real rows 12..13; row 5 released to placeholder 15.
            adapter.submit(listOf(1L, 12L, 13L, 4L, 15L), extents)
            assertEquals(listOf("change 1+2", "change 4+1"), ops)
            ops.clear()
            // A page appended and a row dropped elsewhere keep their own kinds.
            adapter.submit(listOf(1L, 13L, 4L, 15L, 16L, 17L), extents)
            assertEquals(listOf("remove 1+1", "insert 4+2"), ops)
            ops.clear()
            // A longer replacement: overlap changed, the rest inserted.
            adapter.submit(listOf(1L, 18L, 19L, 20L, 4L, 15L, 16L, 17L), extents)
            assertEquals(listOf("change 1+1", "insert 2+2"), ops)
        }
    }
}
