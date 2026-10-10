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

    @Test
    fun panFollowsTheFingerUnderAScaledOrRotatedAncestor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            // A layer of a logical canvas drawn at half size (scaled ancestor) and
            // committed at 2x itself: the finger's 80 px are 80 / (0.5 * 2) units.
            val scaled = panChild(context, parentScale = 0.5f, ownScale = 2f, rotation = 0f)
            drag(scaled.first, 280f, 200f)
            assertEquals(-80f, scaled.second.translationX, 0.01f)
            assertEquals(0f, scaled.second.translationY, 0.01f)

            val halfScale = panChild(context, parentScale = 0.5f, ownScale = 1f, rotation = 0f)
            drag(halfScale.first, 280f, 200f)
            assertEquals(-160f, halfScale.second.translationX, 0.01f)

            // Rotated 90° clockwise: a leftward drag moves down the parent's y axis.
            val rotated = panChild(context, parentScale = 1f, ownScale = 1f, rotation = 90f)
            drag(rotated.first, 280f, 200f)
            assertEquals(0f, rotated.second.translationX, 0.01f)
            assertEquals(80f, rotated.second.translationY, 0.01f)
        }
    }

    private fun panChild(
        context: android.content.Context,
        parentScale: Float,
        ownScale: Float,
        rotation: Float,
    ): Pair<PamPressable, View> {
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
                    direction = 1,
                    composition = 1,
                    minDistance = 2f,
                    minDurationMs = 0L,
                ),
                callback = {},
                nativeTransform = true,
            )
        }
        // The committed layer scale sits on the layer's own view (the outer
        // detector in the app); a wrapper keeps it out of the press feedback.
        val layer = android.widget.FrameLayout(context).apply {
            scaleX = ownScale
            scaleY = ownScale
            addView(pressable, android.widget.FrameLayout.LayoutParams(360, 64))
        }
        android.widget.FrameLayout(context).apply {
            scaleX = parentScale
            scaleY = parentScale
            this.rotation = rotation
            addView(layer, android.widget.FrameLayout.LayoutParams(360, 64))
            measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(64, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 360, 64)
        }
        return pressable to child
    }

    private fun drag(pressable: PamPressable, fromX: Float, toX: Float) {
        val downTime = 1_000L
        pressable.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, fromX, 32f, 0))
        pressable.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime + 32L, MotionEvent.ACTION_MOVE, toX, 32f, 0))
    }
}
