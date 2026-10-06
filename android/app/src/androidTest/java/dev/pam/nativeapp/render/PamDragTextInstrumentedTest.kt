package dev.pam.nativeapp.render

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamDragTextInstrumentedTest {
    @Test fun continuousDragUpdatesItsClockAndRetainsTheReleasedPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val host = FrameLayout(context)
            val thumb = View(context).apply { setTag(R.id.pam_native_ref, "thumb") }
            val clock = TextView(context).apply { setTag(R.id.pam_native_ref, "clock") }
            host.addView(thumb, FrameLayout.LayoutParams(14, 14))
            host.addView(clock, FrameLayout.LayoutParams(116, 32))
            val drag = PamDragController(host)
            drag.configure(requireNotNull(PamDragConfig.parse(
                "axis=x\ntarget=thumb\ntouch=14\nmin=0\nmax=360\nsnapOnRelease=0\ntext=clock|3|0|0,360|0,125",
            )), null)
            // The pan recognizes after moving16dp from the original14dp touch.
            drag.begin(30f * density, 0f, 16f * density, 0f)
            drag.update(180f * density, 0f)
            assertEquals(180f * density, thumb.translationX, 0.01f)
            assertEquals("1:02 / 2:05", clock.text.toString())
            assertEquals(-1, drag.end(0f, 0f, false)?.snapIndex)
            assertEquals(180f * density, thumb.translationX, 0.01f)
            drag.update(500f * density, 0f)
            assertEquals("2:05 / 2:05", clock.text.toString())
            drag.detach()
        }
    }
}
