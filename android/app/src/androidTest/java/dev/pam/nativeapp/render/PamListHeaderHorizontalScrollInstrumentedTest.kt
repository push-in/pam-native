package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Contacts-style header: a 412 dp strip of buttons inside a 360 dp vertical list. */
@RunWith(AndroidJUnit4::class)
class PamListHeaderHorizontalScrollInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun horizontalDragOverAHeaderButtonReachesTheLastAction() = withHeader { list, strip, scale ->
        drag(list, scale, deltaX = -160f, deltaY = 0f)
        assertTrue("horizontal header must scroll", strip.snapshotOffsetPixels().first > 0)
        assertEquals("list must stay at its first row", 0, list.computeVerticalScrollOffset())
    }

    @Test
    fun horizontalDominantDiagonalOverAHeaderButtonKeepsTheHorizontalGesture() = withHeader { list, strip, scale ->
        drag(list, scale, deltaX = -160f, deltaY = -60f)
        assertTrue("horizontal-dominant drag must reach the overflow actions", strip.snapshotOffsetPixels().first > 0)
        assertEquals("diagonal header swipe must not move the vertical list", 0, list.computeVerticalScrollOffset())
    }

    @Test
    fun verticalDragOverAHeaderButtonStillScrollsTheContacts() = withHeader { list, strip, scale ->
        drag(list, scale, deltaX = 0f, deltaY = -60f)
        assertTrue("vertical list must retain vertical drags", list.computeVerticalScrollOffset() > 0)
        assertEquals(0, strip.snapshotOffsetPixels().first)
    }

    @Test
    fun diagonalDragWithAlphabetRailGutterStillReachesTheLastAction() = withHeader(viewportWidth = 338) { list, strip, scale ->
        drag(list, scale, deltaX = -160f, deltaY = -60f)
        assertTrue("rail gutter must not prevent horizontal scrolling", strip.snapshotOffsetPixels().first > 0)
        assertEquals(0, list.computeVerticalScrollOffset())
    }

    private fun withHeader(viewportWidth: Int = 360, block: (PamRecyclerList, PamScrollContainer, Float) -> Unit) {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val scale = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * scale).roundToInt()
        lateinit var list: PamRecyclerList
        lateinit var strip: PamScrollContainer
        var presses = 0
        try {
            instrumentation.runOnMainSync {
                list = PamRecyclerList(activity)
                activity.host.addView(list, FrameLayout.LayoutParams(dp(viewportWidth), dp(500)))
                val ids = (0L..24L).toList()
                list.setRichItems(
                    ids,
                    ids.associateWith { if (it == 0L) 100f else 66f },
                    { id, holder ->
                        if (id == 0L) {
                            strip = PamScrollContainer(activity, initialHorizontal = true)
                            strip.setEngineContentExtent(dp(412), dp(84))
                            val row = FrameLayout(activity).apply {
                                layoutParams = FrameLayout.LayoutParams(dp(412), dp(84))
                            }
                            repeat(5) { index ->
                                row.addView(
                                    PamPressable(activity).apply { setLocalOnPress { presses++ } },
                                    FrameLayout.LayoutParams(dp(68), dp(84)).apply {
                                        leftMargin = dp(16 + index * 78)
                                    },
                                )
                            }
                            strip.insert(row)
                            holder.addView(strip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(84)))
                        } else {
                            holder.addView(View(activity), FrameLayout.LayoutParams(dp(viewportWidth), dp(66)))
                        }
                    },
                    { _, holder -> holder.removeAllViews() },
                )
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                list.measure(exactly(dp(viewportWidth)), exactly(dp(500)))
                list.layout(0, 0, dp(viewportWidth), dp(500))
                assertEquals(dp(viewportWidth), strip.width)
                assertTrue("fixture needs horizontal overflow", strip.getChildAt(0).canScrollHorizontally(1))
                block(list, strip, scale)
                assertEquals("drag must not activate a quick action", 0, presses)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /** One event crosses both touch-slop axes: how a real diagonal first MOVE can arrive. */
    private fun drag(list: View, scale: Float, deltaX: Float, deltaY: Float) {
        val start = SystemClock.uptimeMillis()
        for (step in 0..5) {
            val fraction = minOf(step, 4) / 4f
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                5 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            MotionEvent.obtain(start, start + step * 32L, action,
                (260f + deltaX * fraction) * scale, (70f + deltaY * fraction) * scale, 0,
            ).also {
                list.dispatchTouchEvent(it)
                it.recycle()
            }
        }
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
