package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.util.LongSparseArray
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.EventKind
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

@RunWith(AndroidJUnit4::class)
class PamInitialInteractionPropertiesInstrumentedTest {
    @Test
    fun initialFeedbackAndPanUseAllPropertiesAndLaterUpdatesRemainImmediate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        try {
            instrumentation.runOnMainSync {
                val events = mutableListOf<Int>()
                val renderer = PamRenderer(activity, activity.host) { _, kind, _ -> events += kind }
                try {
                    renderer.commit(listOf(listOf(
                        create(1, 0, NodeKind.SCREEN),
                        create(2, 1, NodeKind.PRESSABLE, linkedMapOf(
                            PropKey.GESTURE_TYPE to PropValue.Integer(2),
                            PropKey.GESTURE_ENABLED to PropValue.Flag(true),
                            PropKey.GESTURE_DIRECTION to PropValue.Integer(2),
                            PropKey.GESTURE_MIN_POINTERS to PropValue.Integer(1),
                            PropKey.GESTURE_MAX_POINTERS to PropValue.Integer(1),
                            PropKey.GESTURE_MIN_DISTANCE to PropValue.Decimal(24.0),
                            PropKey.GESTURE_NATIVE_TRANSFORM to PropValue.Flag(true),
                            PropKey.GESTURE_NATIVE_TRANSLATION_LIMIT_X to PropValue.Decimal(96.0),
                            PropKey.ON_GESTURE_END to PropValue.Flag(true),
                            // These are assigned after the first gesture property.
                            PropKey.PRESS_OPACITY to PropValue.Decimal(0.42),
                            PropKey.PRESS_SCALE to PropValue.Decimal(0.91),
                            PropKey.ON_PRESS to PropValue.Flag(true),
                        )),
                        create(3, 2, NodeKind.ROW),
                        create(4, 3, NodeKind.TEXT, mapOf(PropKey.TEXT to PropValue.Text("Swipe"))),
                        Mutation.Layout(1, Frame(0f, 0f, 360f, 120f)),
                        Mutation.Layout(2, Frame(0f, 0f, 360f, 64f)),
                        Mutation.Layout(3, Frame(0f, 0f, 360f, 64f)),
                        Mutation.Layout(4, Frame(16f, 20f, 160f, 24f)),
                    )))
                    val views = views(renderer)
                    val gesture = views[2] as PamPressable
                    // The layout-only Row is flattened; the hosted foreground
                    // is its Text descendant, just as the gesture sees it.
                    val foreground = gesture.getChildAt(0)
                    assertEquals(0.42f, feedback(gesture, "pressOpacity"), 0.001f)
                    assertEquals(0.91f, feedback(gesture, "pressScale"), 0.001f)
                    pan(gesture)
                    assertEquals(-96f * activity.resources.displayMetrics.density, foreground.translationX, 0.5f)
                    assertTrue(events.contains(EventKind.GESTURE_END.value))

                    renderer.commit(listOf(listOf(
                        Mutation.Update(2, PropKey.PRESS_OPACITY, PropValue.Decimal(0.63)),
                        Mutation.Update(2, PropKey.GESTURE_NATIVE_TRANSLATION_LIMIT_X, PropValue.Decimal(48.0)),
                        Mutation.Update(2, PropKey.GESTURE_NATIVE_RESET_KEY, PropValue.Integer(1)),
                    )))
                    assertEquals(0.63f, feedback(gesture, "pressOpacity"), 0.001f)
                    pan(gesture)
                    assertEquals(-48f * activity.resources.displayMetrics.density, foreground.translationX, 0.5f)
                    gesture.performClick()
                    assertTrue(events.contains(EventKind.PRESS.value))
                } finally {
                    renderer.close()
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun pan(view: PamPressable) {
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to 320f, MotionEvent.ACTION_MOVE to 0f, MotionEvent.ACTION_UP to 0f)
            .forEachIndexed { index, (action, x) ->
                val event = MotionEvent.obtain(down, down + index * 32L, action, x, 32f, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
    }

    private fun feedback(view: PamPressable, name: String): Float =
        PamPressable::class.java.getDeclaredField(name).apply { isAccessible = true }.getFloat(view)

    @Suppress("UNCHECKED_CAST")
    private fun views(renderer: PamRenderer): LongSparseArray<View> =
        PamRenderer::class.java.getDeclaredField("views").apply { isAccessible = true }
            .get(renderer) as LongSparseArray<View>

    private fun create(id: Long, parent: Long, kind: NodeKind, properties: Map<PropKey, PropValue> = emptyMap()) =
        Mutation.Create(NodeSpec(id, parent, 0, kind, properties))
}
