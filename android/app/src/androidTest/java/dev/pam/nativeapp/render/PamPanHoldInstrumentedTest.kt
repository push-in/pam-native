package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import dev.pam.nativeapp.PamTestActivity
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamPanHoldInstrumentedTest {
    @Test
    fun explicitHoldDefersPanRecognitionUntilDeadline() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val recognizer = recognizer(180L)
            event(recognizer, MotionEvent.ACTION_DOWN, 0, 0f)
            event(recognizer, MotionEvent.ACTION_MOVE, 179, 80f)
            assertFalse(recognizer.hasRecognized())
            event(recognizer, MotionEvent.ACTION_MOVE, 180, 81f)
            assertTrue(recognizer.hasRecognized())
            event(recognizer, MotionEvent.ACTION_UP, 200, 81f)
        }
    }

    @Test
    fun defaultPanStillRecognizesImmediatelyAndEarlyReleaseDoesNotDrag() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val immediate = recognizer(0L)
            event(immediate, MotionEvent.ACTION_DOWN, 0, 0f)
            event(immediate, MotionEvent.ACTION_MOVE, 16, 40f)
            assertTrue(immediate.hasRecognized())
            event(immediate, MotionEvent.ACTION_UP, 30, 40f)
            val held = recognizer(180L)
            event(held, MotionEvent.ACTION_DOWN, 0, 0f)
            event(held, MotionEvent.ACTION_MOVE, 16, 40f)
            event(held, MotionEvent.ACTION_UP, 100, 40f)
            assertFalse(held.hasRecognized())
        }
    }

    @Test
    fun cancellationAndANewTouchResetTheHoldClock() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val recognizer = recognizer(180L)
            event(recognizer, MotionEvent.ACTION_DOWN, 0, 0f)
            event(recognizer, MotionEvent.ACTION_MOVE, 100, 30f)
            event(recognizer, MotionEvent.ACTION_CANCEL, 110, 30f)
            event(recognizer, MotionEvent.ACTION_MOVE, 300, 70f)
            assertFalse(recognizer.hasRecognized())
            event(recognizer, MotionEvent.ACTION_DOWN, 400, 0f)
            event(recognizer, MotionEvent.ACTION_MOVE, 500, 50f)
            assertFalse(recognizer.hasRecognized())
            event(recognizer, MotionEvent.ACTION_MOVE, 581, 60f)
            assertTrue(recognizer.hasRecognized())
            event(recognizer, MotionEvent.ACTION_UP, 600, 60f)
        }
    }

    @Test
    fun holdingInsideHorizontalScrollKeepsTheFirstDragMove() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var scroll: HorizontalScrollView
        var began = 0
        val downAt = SystemClock.uptimeMillis()
        fun dispatch(action: Int, x: Float) {
            val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, x, 40f, 0)
            try { scroll.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        try {
            instrumentation.runOnMainSync {
                scroll = HorizontalScrollView(activity)
                val row = FrameLayout(activity)
                val thumbnail = PamPressable(activity).apply {
                    addView(View(activity), FrameLayout.LayoutParams(300, 100))
                    configureGesture(PamGestureConfig(2, true, 1, 1, 6, 3, 8f, 180L),
                        callback = { if (it.state == 1) began += 1 }, nativeTransform = true)
                }
                row.addView(thumbnail, FrameLayout.LayoutParams(300, 100))
                scroll.addView(row, FrameLayout.LayoutParams(1_080, 100))
                activity.host.addView(scroll, FrameLayout.LayoutParams(360, 100))
                scroll.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY))
                scroll.layout(0, 0, 360, 100)
                dispatch(MotionEvent.ACTION_DOWN, 220f)
            }
            SystemClock.sleep(220)
            instrumentation.runOnMainSync {
                assertTrue("Holding must not emit PHP callbacks", began == 0)
                dispatch(MotionEvent.ACTION_MOVE, 80f)
                assertTrue("The held item receives the first move before its scroll parent", began == 1)
                assertTrue("The strip stays still during reorder", scroll.scrollX == 0)
                dispatch(MotionEvent.ACTION_UP, 80f)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun recognizer(hold: Long) = PamGestureRecognizer(
        View(InstrumentationRegistry.getInstrumentation().targetContext),
    ).apply {
        configure(PamGestureConfig(2, true, 1, 1, 6, 3, 8f, hold)) {}
    }

    private fun event(recognizer: PamGestureRecognizer, action: Int, elapsed: Long, x: Float) {
        val event = MotionEvent.obtain(1_000L, 1_000L + elapsed, action, x, 20f, 0)
        try { recognizer.onTouch(event) } finally { event.recycle() }
    }
}
