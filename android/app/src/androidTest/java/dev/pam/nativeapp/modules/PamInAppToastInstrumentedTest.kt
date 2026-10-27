package dev.pam.nativeapp.modules

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Styled in-app toast: border, radius, no strip, leading icon, 2-line title, hugging width. */
@RunWith(AndroidJUnit4::class)
class PamInAppToastInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun spec(
        iconGlyph: String? = null,
        iconSource: String? = null,
        fitContent: Boolean = true,
        accentWidthDp: Float = 0f,
    ) = PamInAppToastSpec(
        title = "Link copiado para a área de transferência",
        message = "",
        accentColor = 0xFF69C779.toInt(),
        backgroundColor = 0xFF111111.toInt(),
        titleColor = 0xFFFFFFFF.toInt(),
        messageColor = 0xFF979797.toInt(),
        bottom = true,
        offsetDp = 24f,
        durationMs = 4_000,
        titleSize = 13.5f,
        messageSize = 10f,
        fontFamily = null,
        borderColor = 0xFF333333.toInt(),
        borderWidthDp = 1f,
        cornerRadiusDp = 16f,
        accentWidthDp = accentWidthDp,
        elevationDp = 6f,
        paddingHorizontalDp = 14f,
        paddingVerticalDp = 11f,
        minHeightDp = 0f,
        titleLines = 2,
        fitContent = fitContent,
        iconSource = iconSource,
        iconGlyph = iconGlyph,
        iconColor = 0xFF00C853.toInt(),
        iconSizeDp = 18f,
    )

    @Test fun aStyledToastDrawsItsBorderIconAndTwoLineTitle() = withActivity { activity ->
        main { PamInAppToast.show(activity, spec(iconGlyph = "✓")) { _, _ -> Typeface.DEFAULT } }
        val card = awaitCard(activity)
        main {
            val background = card.background as LayerDrawable
            assertEquals("fill + border, no accent strip", 2, background.numberOfLayers)
            val icon = card.getChildAt(0) as TextView
            assertEquals("✓", icon.text.toString())
            assertEquals(0xFF00C853.toInt(), icon.currentTextColor)
            val title = (card.getChildAt(1) as LinearLayout).getChildAt(0) as TextView
            assertEquals(2, title.maxLines)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, card.layoutParams.width)
            val density = activity.resources.displayMetrics.density
            assertTrue("hugging card stays inside the 16 dp gutters", card.width <= activity.window.decorView.width - (32 * density).toInt())
        }
    }

    @Test fun anAccentStripAddsItsLayer() = withActivity { activity ->
        main { PamInAppToast.show(activity, spec(fitContent = false, accentWidthDp = 5f)) { _, _ -> Typeface.DEFAULT } }
        val card = awaitCard(activity)
        main { assertEquals("accent + fill + border", 3, (card.background as LayerDrawable).numberOfLayers) }
    }

    @Test fun aMissingPngIconDrawsTheToastWithoutIcon() = withActivity { activity ->
        main { PamInAppToast.show(activity, spec(iconSource = "asset://icons/missing.png")) { _, _ -> Typeface.DEFAULT } }
        val card = awaitCard(activity)
        main { assertTrue(card.getChildAt(0) !is ImageView && card.getChildAt(0) is LinearLayout) }
    }

    private fun awaitCard(activity: PamTestActivity): LinearLayout {
        val deadline = SystemClock.uptimeMillis() + 3_000
        while (SystemClock.uptimeMillis() < deadline) {
            var card: LinearLayout? = null
            main {
                val container = (activity.window.decorView as ViewGroup).findViewWithTag<FrameLayout>("pam-in-app-toast")
                card = (container?.getChildAt(0) as? LinearLayout)?.takeIf { it.width > 0 }
            }
            card?.let { return it }
            SystemClock.sleep(16)
        }
        throw AssertionError("Toast was not shown")
    }

    private fun withActivity(block: (PamTestActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        try {
            block(activity)
        } finally {
            main { activity.finish() }
        }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
