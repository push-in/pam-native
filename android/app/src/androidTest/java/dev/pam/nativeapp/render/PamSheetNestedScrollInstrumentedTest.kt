package dev.pam.nativeapp.render

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A bottom sheet may only start its drag-to-close when the scrollable under
 * the finger is at its top (gorhom/react-native-bottom-sheet semantics), not
 * when the sheet's direct child (usually a non-scrolling Column) is.
 */
@RunWith(AndroidJUnit4::class)
class PamSheetNestedScrollInstrumentedTest {
    @Test
    fun resolvesTheNestedScrollableUnderTheFinger() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val content = FrameLayout(context)
            val sheet = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val header = View(context)
            val list = ScrollView(context)
            val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            repeat(40) { rows.addView(View(context), LinearLayout.LayoutParams(400, 100)) }
            list.addView(rows)
            sheet.addView(header, LinearLayout.LayoutParams(400, 100))
            sheet.addView(list, LinearLayout.LayoutParams(400, 500))
            content.addView(sheet, FrameLayout.LayoutParams(400, 600).apply { topMargin = 400 })
            content.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
            )
            content.layout(0, 0, 400, 1000)

            assertFalse("the sheet's direct child never scrolls", sheet.canScrollVertically(-1))

            list.scrollTo(0, 800)
            val overList = verticalScrollChainAt(content, 200f, 700f)
            assertEquals(listOf<View>(list), overList)
            assertTrue("scrolled list blocks the sheet drag", overList.any { it.canScrollVertically(-1) })

            list.scrollTo(0, 0)
            assertFalse(verticalScrollChainAt(content, 200f, 700f).any { it.canScrollVertically(-1) })

            list.scrollTo(0, 800)
            assertTrue("the header is outside the list", verticalScrollChainAt(content, 200f, 450f).isEmpty())
        }
    }
}
