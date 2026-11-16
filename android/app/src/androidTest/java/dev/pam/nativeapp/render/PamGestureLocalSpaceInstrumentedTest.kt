package dev.pam.nativeapp.render

import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Pan translations are in the detector's space, like iOS
 * `translation(in: view)`: under a scaled or rotated ancestor (a story
 * sticker box) the finger's screen delta is mapped back, so `x - translation`
 * stays the touch-down point and a drag target follows the finger. Without
 * scale or rotation the translation is the screen delta, bit for bit.
 *
 * Events go through a root that hit-tests the transformed box, so the
 * detector sees local `x`/`y` as on a device while `rawX`/`rawY` stay screen
 * coordinates.
 */
@RunWith(AndroidJUnit4::class)
class PamGestureLocalSpaceInstrumentedTest {
    @Test
    fun panUnderAScaledRotatedBoxReportsTheDetectorSpace() {
        // Box at 2x, rotated 90° clockwise around its centre (100, 50): the
        // detector's x axis points down the screen at 2 px per unit.
        val f = fixture(scale = 2f, rotation = 90f)
        main {
            f.send(MotionEvent.ACTION_DOWN, 100f, 50f, 0L)
            for (step in 1..5) f.send(MotionEvent.ACTION_MOVE, 100f, 50f + 8f * step, 8L * step)
            f.send(MotionEvent.ACTION_UP, 100f, 98f, 48L)
        }
        val end = f.payloads.last { it.state == STATE_ENDED }
        assertEquals("48 px down the screen are 24 units along the detector's x", 24f, end.translationX, 0.01f)
        assertEquals(0f, end.translationY, 0.01f)
        for (payload in f.payloads) {
            assertEquals("x - translationX is the touch-down point", 100f, payload.x - payload.translationX, 0.01f)
            assertEquals(50f, payload.y - payload.translationY, 0.01f)
        }
        assertTrue("Velocity is local too: along +x (${end.velocityX}, ${end.velocityY})", end.velocityX > 0f)
        assertTrue(abs(end.velocityY) < end.velocityX / 10f)
    }

    @Test
    fun withoutScaleOrRotationTheTranslationIsTheScreenDelta() {
        // Offsets (a translated box) never apply to a displacement.
        val f = fixture(scale = 1f, rotation = 0f, boxTranslationX = 30f)
        main {
            f.send(MotionEvent.ACTION_DOWN, 130f, 50f, 0L)
            for (step in 1..5) f.send(MotionEvent.ACTION_MOVE, 130f + 8f * step, 53f, 8L * step)
            f.send(MotionEvent.ACTION_UP, 177f, 53f, 48L)
        }
        val end = f.payloads.last { it.state == STATE_ENDED }
        assertEquals(47f, end.translationX, 0f)
        assertEquals(3f, end.translationY, 0f)
        assertEquals(100f, end.x - end.translationX, 0.001f)
        assertEquals(177f, end.pageX, 0f)
        assertTrue(end.velocityX > 0f)
    }

    @Test
    fun atTouchDragFollowsTheFingerUnderAScaledBox() {
        // Box at 2x around (100, 50): local x = (screen x + 100) / 2.
        val drag = requireNotNull(PamDragConfig.parse("axis=x\ntouch=0\nmin=0\nmax=1000\nsnapOnRelease=0"))
        val f = fixture(scale = 2f, rotation = 0f, drag = drag)
        main {
            f.send(MotionEvent.ACTION_DOWN, 60f, 50f, 0L)
            f.send(MotionEvent.ACTION_MOVE, 100f, 50f, 16L)
            f.send(MotionEvent.ACTION_MOVE, 180f, 50f, 32L)
            f.send(MotionEvent.ACTION_UP, 180f, 50f, 40L)
        }
        // Screen-space deltas anchored the thumb at 60 and moved it 120 (180).
        assertEquals("The thumb sits under the finger (local 140)", 140f, f.target.translationX, 0.01f)
    }

    @Test
    fun atTouchDragWithoutTransformIsUnchanged() {
        val drag = requireNotNull(PamDragConfig.parse("axis=x\ntouch=0\nmin=0\nmax=1000\nsnapOnRelease=0"))
        val f = fixture(scale = 1f, rotation = 0f, drag = drag)
        main {
            f.send(MotionEvent.ACTION_DOWN, 60f, 50f, 0L)
            f.send(MotionEvent.ACTION_MOVE, 100f, 50f, 16L)
            f.send(MotionEvent.ACTION_MOVE, 180f, 50f, 32L)
            f.send(MotionEvent.ACTION_UP, 180f, 50f, 40L)
        }
        assertEquals(180f, f.target.translationX, 0f)
    }

    private class Fixture(
        val root: FrameLayout,
        val target: View,
        val payloads: MutableList<PamGesturePayload>,
    ) {
        fun send(action: Int, x: Float, y: Float, at: Long) {
            val event = MotionEvent.obtain(DOWN_TIME, DOWN_TIME + at, action, x, y, 0)
            try { root.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    private fun fixture(
        scale: Float,
        rotation: Float,
        boxTranslationX: Float = 0f,
        drag: PamDragConfig? = null,
    ): Fixture {
        lateinit var fixture: Fixture
        main {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val payloads = mutableListOf<PamGesturePayload>()
            val target = View(context)
            val detector = PamPressable(context).apply {
                addView(target, FrameLayout.LayoutParams(20, 100))
                configureGesture(
                    PamGestureConfig(
                        type = 2,
                        enabled = true,
                        minPointers = 1,
                        maxPointers = 1,
                        direction = 1,
                        composition = 1,
                        minDistance = 1f,
                        minDurationMs = 0L,
                    ),
                    callback = { payloads += it },
                )
                if (drag != null) configureDrag(drag, null)
            }
            val box = FrameLayout(context).apply {
                scaleX = scale
                scaleY = scale
                this.rotation = rotation
                translationX = boxTranslationX
                addView(detector, FrameLayout.LayoutParams(200, 100))
            }
            val root = FrameLayout(context).apply {
                addView(box, FrameLayout.LayoutParams(200, 100))
                measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                )
                layout(0, 0, 400, 400)
            }
            fixture = Fixture(root, target, payloads)
        }
        return fixture
    }

    private fun main(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private companion object {
        const val DOWN_TIME = 1_000L
        const val STATE_ENDED = 3
    }
}
