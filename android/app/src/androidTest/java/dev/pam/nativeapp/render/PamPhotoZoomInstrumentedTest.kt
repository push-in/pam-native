package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
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
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Same stable pan -> translation -> pinch -> scale -> image topology as the photo viewer. */
@RunWith(AndroidJUnit4::class)
class PamPhotoZoomInstrumentedTest {
    /** Zoom transitions are observed in flight; CI emulators disable animations. */
    @get:Rule
    val animations = PamAnimationsEnabledRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun nativeZoomInterpolatesAndKeepsDecodedPhotoWithoutPhpFrameEvents() = withPhoto { photo ->
        val samples = mutableListOf<Float>()
        var lostPhoto = false
        val listener = ViewTreeObserver.OnDrawListener {
            samples += photo.scale.scaleX
            lostPhoto = lostPhoto || photo.drawable !== photo.image.drawable || photo.image !== photo.renderer.viewForNode(IMAGE)
        }
        onMain {
            photo.scale.viewTreeObserver.addOnDrawListener(listener)
            photo.zoom(2.4f)
            assertEquals("No jump in the committing frame", 1f, photo.scale.scaleX, 0.01f)
        }
        waitUntil { photo.scale.scaleX >= 2.39f }
        onMain {
            photo.scale.viewTreeObserver.removeOnDrawListener(listener)
            assertTrue("Every frame retains the decoded photo", !lostPhoto)
            assertTrue("Native animation must have intermediate frames: $samples", samples.count { it > 1.02f && it < 2.38f } >= 3)
            assertTrue("Animation never emits PHP frame events", photo.events.isEmpty())
            photo.zoom(1f)
        }
        waitUntil { photo.scale.scaleX <= 1.01f }
        onMain {
            assertSame(photo.drawable, photo.image.drawable)
            assertSame(photo.image, photo.renderer.viewForNode(IMAGE))
        }
    }

    @Test
    fun pinchTakesOverAnInFlightZoomAndCommitsItsAppliedScaleAcrossTwoGestures() = withPhoto { photo ->
        onMain { photo.zoom(2.4f) }
        waitUntil { photo.scale.scaleX > 1.08f }
        repeat(2) { gesture ->
            var heldScale = 0f
            val distance = if (gesture == 0) 160f else 130f
            val start = SystemClock.uptimeMillis()
            onMain {
                photo.touch(start, MotionEvent.ACTION_DOWN, 1, 100f)
                photo.touch(start, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 100f)
                photo.touch(start, MotionEvent.ACTION_MOVE, 2, distance)
                heldScale = photo.scale.scaleX
            }
            awaitFrames(instrumentation, 15)
            onMain {
                assertEquals("A stationary pinch owns scale; the previous zoom must stop writing", heldScale, photo.scale.scaleX, 0.015f)
                photo.touch(start, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, distance)
                photo.touch(start, MotionEvent.ACTION_UP, 1, distance)
                val ended = photo.payloads.last()
                val applied = (ended["nativeScale"] as WireValue.Decimal).value.toFloat()
                assertEquals("Relative scale remains backward compatible", distance / 100.0, (ended["scale"] as WireValue.Decimal).value, 0.01)
                assertEquals(heldScale, applied, 0.015f)
                // Model PHP adopting the authoritative native result, in reverse prop order.
                photo.renderer.commit(listOf(listOf(
                    Mutation.Update(SCALE, PropKey.SCALE_Y, PropValue.Decimal(applied.toDouble())),
                    Mutation.Update(SCALE, PropKey.SCALE_X, PropValue.Decimal(applied.toDouble())),
                )))
                assertEquals("Committing the result must not jump to the old authored target", heldScale, photo.scale.scaleX, 0.015f)
                assertSame(photo.drawable, photo.image.drawable)
            }
            awaitFrames(instrumentation, 6)
            onMain { assertEquals("Committed scale stays unchanged", heldScale, photo.scale.scaleX, 0.015f) }
        }
        onMain {
            assertEquals(listOf(EventKind.GESTURE_BEGIN.value, EventKind.GESTURE_END.value,
                EventKind.GESTURE_BEGIN.value, EventKind.GESTURE_END.value), photo.events)
        }
    }

    @Test
    fun panTakesOverTranslationAndCommitsAbsoluteDpWithoutCancellingOpacity() = withPhoto { photo ->
        onMain {
            photo.renderer.commit(listOf(listOf(
                Mutation.Update(3, PropKey.TRANSLATION_X, PropValue.Decimal(80.0)),
                Mutation.Update(3, PropKey.TRANSLATION_Y, PropValue.Decimal(50.0)),
                Mutation.Update(3, PropKey.ANIMATION_PROGRAM, PropValue.Text(
                    "pam-motion 1 id=301 iterations=1\n0 opacity timing(0.5,700,linear,0)")),
            )))
        }
        val translation = requireNotNull(photo.renderer.viewForNode(3))
        waitUntil { translation.translationX > 2f }
        val start = SystemClock.uptimeMillis()
        var heldX = 0f
        var heldY = 0f
        onMain {
            photo.pan(start, MotionEvent.ACTION_DOWN, 80f, 100f)
            photo.pan(start, MotionEvent.ACTION_MOVE, 120f, 125f)
            heldX = translation.translationX
            heldY = translation.translationY
        }
        awaitFrames(instrumentation, 10)
        onMain {
            assertEquals(heldX, translation.translationX, 0.1f)
            assertEquals(heldY, translation.translationY, 0.1f)
            photo.pan(start, MotionEvent.ACTION_UP, 120f, 125f)
            val end = photo.payloads.last()
            val x = (end["nativeTranslationX"] as WireValue.Decimal).value
            val y = (end["nativeTranslationY"] as WireValue.Decimal).value
            val density = translation.resources.displayMetrics.density
            assertEquals(heldX.toDouble(), x * density, 0.1)
            assertEquals(heldY.toDouble(), y * density, 0.1)
            assertEquals(40.0, (end["translationX"] as WireValue.Decimal).value, 0.1)
            photo.renderer.commit(listOf(listOf(
                Mutation.Update(3, PropKey.TRANSLATION_Y, PropValue.Decimal(y)),
                Mutation.Update(3, PropKey.TRANSLATION_X, PropValue.Decimal(x)),
            )))
            assertSame(photo.drawable, photo.image.drawable)
        }
        waitUntil {
            translation.alpha <= 0.501f &&
                kotlin.math.abs(translation.translationX - (heldX + 0.5f).toInt()) < 0.005f &&
                kotlin.math.abs(translation.translationY - (heldY + 0.5f).toInt()) < 0.005f
        }
        onMain {
            assertEquals("Opacity remains independent of transform takeover", 0.5f, translation.alpha, 0.01f)
            assertEquals((heldX + 0.5f).toInt().toFloat(), translation.translationX, 0.01f)
            assertEquals((heldY + 0.5f).toInt().toFloat(), translation.translationY, 0.01f)
            assertEquals("An intermediate View preserves independent targets", 0f, photo.scale.translationX, 0.01f)
            assertEquals(0f, photo.scale.translationY, 0.01f)
            assertEquals(listOf(EventKind.GESTURE_BEGIN.value, EventKind.GESTURE_END.value), photo.events)
        }
    }

    @Test
    fun nestedNativeDetectorsShareOffCenterZoomAndCommitBothDimensions() = withPhoto(sharedTarget = true) { photo ->
        val surface = photo.scale
        onMain {
            photo.onGesture = { kind, payload ->
                if (kind == EventKind.GESTURE_BEGIN.value) {
                    // Model PHP begin: animation class only; the native gesture
                    // has already stopped every transform before this callback.
                    photo.renderer.commit(listOf(listOf(
                        Mutation.Update(SCALE, PropKey.TRANSITION_SPEC, null),
                        Mutation.Update(SCALE, PropKey.ANIMATE_CHANGES, null),
                    )))
                } else if (kind == EventKind.GESTURE_END.value) {
                    val scale = (payload["nativeScale"] as WireValue.Decimal).value
                    val x = (payload["nativeTranslationX"] as WireValue.Decimal).value
                    val y = (payload["nativeTranslationY"] as WireValue.Decimal).value
                    photo.renderer.commit(listOf(listOf(
                        Mutation.Update(SCALE, PropKey.TRANSITION_SPEC, PropValue.Text(TRANSITION)),
                        Mutation.Update(SCALE, PropKey.ANIMATE_CHANGES, PropValue.Flag(true)),
                        Mutation.Update(SCALE, PropKey.TRANSLATION_Y, PropValue.Decimal(y)),
                        Mutation.Update(SCALE, PropKey.SCALE_Y, PropValue.Decimal(scale)),
                        Mutation.Update(SCALE, PropKey.TRANSLATION_X, PropValue.Decimal(x)),
                        Mutation.Update(SCALE, PropKey.SCALE_X, PropValue.Decimal(scale)),
                    )))
                }
            }
        }
        repeat(2) { gesture ->
            // Each gesture interrupts a new off-center zoom in flight.
            onMain {
                photo.renderer.commit(listOf(listOf(
                    Mutation.Update(SCALE, PropKey.TRANSLATION_X, PropValue.Decimal(if (gesture == 0) 80.0 else 140.0)),
                    Mutation.Update(SCALE, PropKey.TRANSLATION_Y, PropValue.Decimal(if (gesture == 0) 50.0 else 90.0)),
                )))
                photo.zoom(if (gesture == 0) 2.4f else 3.2f)
            }
            var initialScale = 1f
            onMain { initialScale = surface.scaleX }
            waitUntil { surface.scaleX > initialScale + 0.05f && surface.translationX > 2f }
            val start = SystemClock.uptimeMillis()
            var scale = 0f
            var x = 0f
            var y = 0f
            onMain {
                if (gesture == 0) {
                    photo.touch(start, MotionEvent.ACTION_DOWN, 1, 100f)
                    photo.touch(start, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 100f)
                    photo.touch(start, MotionEvent.ACTION_MOVE, 2, 140f)
                } else {
                    photo.pan(start, MotionEvent.ACTION_DOWN, 80f, 100f)
                    photo.pan(start, MotionEvent.ACTION_MOVE, 120f, 125f)
                }
                scale = surface.scaleX
                x = surface.translationX
                y = surface.translationY
            }
            awaitFrames(instrumentation, 11)
            onMain {
                assertEquals("Pinch and pan must stop all in-flight transforms", x, surface.translationX, 0.01f)
                assertEquals(y, surface.translationY, 0.01f)
                assertEquals(scale, surface.scaleX, 0.015f)
                if (gesture == 0) {
                    photo.touch(start, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, 140f)
                    photo.touch(start, MotionEvent.ACTION_UP, 1, 140f)
                } else photo.pan(start, MotionEvent.ACTION_UP, 120f, 125f)
                val ended = photo.payloads.last()
                val density = surface.resources.displayMetrics.density
                assertEquals(scale.toDouble(), (ended["nativeScale"] as WireValue.Decimal).value, 0.015)
                assertEquals(x.toDouble(), (ended["nativeTranslationX"] as WireValue.Decimal).value * density, 0.01)
                assertEquals(y.toDouble(), (ended["nativeTranslationY"] as WireValue.Decimal).value * density, 0.01)
            }
            awaitFrames(instrumentation, 52)
            onMain {
                assertEquals("Release adopts the actual scale, including after a pan", scale, surface.scaleX, 0.015f)
                // Existing authored lengths use integer px; verify that contract exactly.
                assertEquals((x + 0.5f).toInt().toFloat(), surface.translationX, 0.01f)
                assertEquals((y + 0.5f).toInt().toFloat(), surface.translationY, 0.01f)
                assertSame(photo.drawable, photo.image.drawable)
                assertSame(photo.image, photo.renderer.viewForNode(IMAGE))
            }
        }
    }

    private inner class Photo(val renderer: PamRenderer, val events: MutableList<Int>, val payloads: MutableList<Map<String, WireValue>>) {
        var onGesture: ((Int, Map<String, WireValue>) -> Unit)? = null
        val scale = requireNotNull(renderer.viewForNode(SCALE))
        val image = renderer.viewForNode(IMAGE) as PamImageView
        val pinch = renderer.viewForNode(PINCH) as PamPressable
        val drawable = image.drawable

        fun zoom(value: Float) {
            renderer.commit(listOf(listOf(
                Mutation.Update(SCALE, PropKey.SCALE_X, PropValue.Decimal(value.toDouble())),
                Mutation.Update(SCALE, PropKey.SCALE_Y, PropValue.Decimal(value.toDouble())),
            )))
        }

        fun pan(start: Long, action: Int, x: Float, y: Float) {
            val target = requireNotNull(renderer.viewForNode(2))
            val density = target.resources.displayMetrics.density
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x * density, y * density, 0).also {
                target.dispatchTouchEvent(it)
                it.recycle()
            }
        }

        fun touch(start: Long, action: Int, count: Int, distance: Float) {
            val density = pinch.resources.displayMetrics.density
            val properties = Array(count) { index -> MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            } }
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                x = (160f + if (index == 0) -distance / 2 else distance / 2) * density
                y = 240f * density
                pressure = 1f
                size = 1f
            } }
            MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0).also {
                pinch.dispatchTouchEvent(it)
                it.recycle()
            }
        }
    }

    private fun withPhoto(sharedTarget: Boolean = false, block: (Photo) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PamTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PamTestActivity
        val file = File(activity.cacheDir, "photo-zoom-${System.nanoTime()}.png")
        Bitmap.createBitmap(960, 1440, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.CYAN)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        lateinit var renderer: PamRenderer
        val events = mutableListOf<Int>()
        val payloads = mutableListOf<Map<String, WireValue>>()
        var photo: Photo? = null
        try {
            onMain {
                renderer = PamRenderer(activity, activity.host) { _, kind, payload ->
                    events += kind
                    val decoded = if (payload.isEmpty()) emptyMap() else WireMap.decode(payload)
                    payloads += decoded
                    photo?.onGesture?.invoke(kind, decoded)
                }
                val transition = mapOf(
                    PropKey.COLLAPSABLE to PropValue.Flag(false),
                    PropKey.ANIMATE_CHANGES to PropValue.Flag(true),
                    PropKey.TRANSITION_SPEC to PropValue.Text(TRANSITION),
                )
                val mutations = mutableListOf<Mutation>(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.PRESSABLE, gesture(2))),
                    Mutation.Create(node(PINCH, if (sharedTarget) 2 else 3, NodeKind.PRESSABLE, gesture(3))),
                    Mutation.Create(node(SCALE, PINCH, NodeKind.VIEW, transition)),
                    Mutation.Create(node(6, SCALE, NodeKind.PRESSABLE, mapOf(
                        PropKey.PRESS_OPACITY to PropValue.Decimal(1.0),
                        PropKey.PRESS_SCALE to PropValue.Decimal(1.0),
                    ))),
                    Mutation.Create(node(IMAGE, 6, NodeKind.IMAGE, mapOf(
                        PropKey.SOURCE to PropValue.Text(file.toURI().toString()),
                        PropKey.IMAGE_FADE_DURATION_MS to PropValue.Integer(0),
                        PropKey.IMAGE_FIT to PropValue.Integer(2),
                    ))),
                )
                if (!sharedTarget) mutations.add(2, Mutation.Create(node(3, 2, NodeKind.VIEW, transition)))
                (1L..IMAGE).filter { !sharedTarget || it != 3L }.forEach {
                    mutations += Mutation.Layout(it, Frame(0f, 0f, 320f, 480f))
                }
                mutations += Mutation.SetRoot(1)
                renderer.commit(listOf(mutations))
            }
            instrumentation.waitForIdleSync()
            waitUntil { (renderer.viewForNode(IMAGE) as? PamImageView)?.drawable != null }
            onMain { photo = Photo(renderer, events, payloads) }
            block(requireNotNull(photo))
        } finally {
            onMain {
                renderer.close()
                activity.finish()
            }
            file.delete()
        }
    }

    private fun gesture(type: Long) = mapOf(
        PropKey.GESTURE_TYPE to PropValue.Integer(type),
        PropKey.GESTURE_COMPOSITION to PropValue.Integer(2),
        PropKey.GESTURE_MIN_POINTERS to PropValue.Integer(if (type == 3L) 2 else 1),
        PropKey.GESTURE_MAX_POINTERS to PropValue.Integer(if (type == 3L) 2 else 1),
        PropKey.GESTURE_NATIVE_TRANSFORM to PropValue.Flag(true),
        PropKey.ON_GESTURE_BEGIN to PropValue.Flag(true),
        PropKey.ON_GESTURE_END to PropValue.Flag(true),
    )

    private fun node(id: Long, parent: Long, kind: NodeKind, props: Map<PropKey, PropValue> = emptyMap()) =
        NodeSpec(id = id, parent = parent, index = 0, kind = kind, properties = props)

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitUntil(predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 5_000
        do {
            var ready = false
            onMain { ready = predicate() }
            if (ready) return
            awaitFrames(instrumentation)
        } while (SystemClock.uptimeMillis() < until)
        error("Photo zoom condition did not settle")
    }

    private companion object {
        const val PINCH = 4L
        const val SCALE = 5L
        const val IMAGE = 7L
        const val TRANSITION = "@property transform\n@duration 800\n@timing linear\n@delay 0"
    }
}
