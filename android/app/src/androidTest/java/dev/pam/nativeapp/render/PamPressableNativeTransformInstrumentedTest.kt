package dev.pam.nativeapp.render

import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamPressableNativeTransformInstrumentedTest {
    @Test
    fun nativeInteractionKeepsAPressableEligibleForLongPress() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val pressable = PamPressable(instrumentation.targetContext).apply {
                setCallbacks(null, null, null, null, null)
                configureGesture(null, null)
            }
            assertTrue(!pressable.isClickable)

            pressable.setNativeInteractionEnabled(true)

            assertTrue(pressable.isClickable)
            assertTrue(pressable.isEnabled)
        }
    }

    @Test
    fun platformLongClickListenerRemainsHandledWithoutAPhpCallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            var invocations = 0
            val pressable = PamPressable(instrumentation.targetContext).apply {
                setOnLongClickListener {
                    invocations += 1
                    true
                }
            }

            assertTrue(pressable.performLongClick())
            assertEquals(1, invocations)
        }
    }

    @Test
    fun horizontalPanTransformsItsChildAndClampsTheRevealDistance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val child = View(context)
            val pressable = PamPressable(context).apply {
                addView(child)
                layout(0, 0, 360, 64)
                child.layout(0, 0, 360, 64)
                configureGesture(
                    config = PamGestureConfig(
                        type = 2,
                        enabled = true,
                        minPointers = 1,
                        maxPointers = 1,
                        direction = 2,
                        composition = 1,
                        minDistance = 24f,
                        minDurationMs = 0L,
                    ),
                    callback = {},
                    nativeTransform = true,
                    nativeTranslationLimitX = 96f,
                )
            }
            val downTime = 1_000L
            pressable.dispatchTouchEvent(MotionEvent.obtain(
                downTime, downTime, MotionEvent.ACTION_DOWN, 280f, 32f, 0,
            ))
            pressable.dispatchTouchEvent(MotionEvent.obtain(
                downTime, downTime + 32L, MotionEvent.ACTION_MOVE, 120f, 32f, 0,
            ))

            assertTrue(child.translationX < 0f)
            assertEquals(-96f, child.translationX, 0.01f)
            assertEquals(0f, pressable.translationX, 0.01f)

            pressable.dispatchTouchEvent(MotionEvent.obtain(
                downTime, downTime + 48L, MotionEvent.ACTION_UP, 120f, 32f, 0,
            ))
        }
    }
}
