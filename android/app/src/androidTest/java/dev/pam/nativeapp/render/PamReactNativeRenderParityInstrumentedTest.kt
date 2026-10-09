package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/** Renderer differences found while porting the React Native Zé Chat to PAM (1.34.0). */
@RunWith(AndroidJUnit4::class)
class PamReactNativeRenderParityInstrumentedTest {
    @get:Rule
    val animations = PamAnimationsEnabledRule()

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()

    /**
     * RN Android `ReactViewGroup.hasOverlappingRendering()` is false unless
     * `needsOffscreenAlphaCompositing`: a faded button draws its fill and then
     * its label with the opacity, so a white label blends with the faded fill
     * (it does not stay white over the screen as one flattened layer would).
     */
    @Test
    fun containerOpacityFadesEachDrawOfTheSubtreeLikeReactNative() {
        val activity = launchActivity()
        try {
            instrumentation.runOnMainSync {
                PamRenderer(activity, activity.host) { _, _, _ -> }.commit(
                    listOf(
                        listOf(
                            Mutation.Create(node(1, 0, NodeKind.SCREEN, mapOf(PropKey.BACKGROUND_COLOR to color(0xFF0000FF)))),
                            Mutation.Create(
                                node(
                                    2, 1, NodeKind.PRESSABLE,
                                    mapOf(
                                        PropKey.BACKGROUND_COLOR to color(0xFF000000),
                                        PropKey.OPACITY to PropValue.Decimal(0.5),
                                        PropKey.ON_PRESS to PropValue.Flag(true),
                                    ),
                                ),
                            ),
                            Mutation.Create(node(3, 2, NodeKind.VIEW, mapOf(PropKey.BACKGROUND_COLOR to color(0xFFFFFFFF)))),
                            Mutation.Create(
                                node(
                                    4, 1, NodeKind.VIEW,
                                    mapOf(
                                        PropKey.BACKGROUND_COLOR to color(0xFF000000),
                                        PropKey.OPACITY to PropValue.Decimal(0.5),
                                        PropKey.NEEDS_OFFSCREEN_ALPHA_COMPOSITING to PropValue.Flag(true),
                                    ),
                                ),
                            ),
                            Mutation.Create(node(5, 4, NodeKind.VIEW, mapOf(PropKey.BACKGROUND_COLOR to color(0xFFFFFFFF)))),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                            Mutation.Layout(2, Frame(20f, 20f, 200f, 80f)),
                            Mutation.Layout(3, Frame(40f, 40f, 160f, 40f)),
                            Mutation.Layout(4, Frame(20f, 200f, 200f, 80f)),
                            Mutation.Layout(5, Frame(40f, 220f, 160f, 40f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
            }
            // The non-overlapping alpha path only exists in hardware
            // rendering: read the composited window, not a software canvas.
            val (bitmap, origin) = windowPixels(activity)
            val density = activity.resources.displayMetrics.density
            fun pixel(x: Float, y: Float) =
                bitmap.getPixel(origin[0] + (x * density).roundToInt(), origin[1] + (y * density).roundToInt())
            // Fill: black at 0.5 over blue = (0, 0, 128); label: white at 0.5 over that.
            assertColor("faded fill", Color.rgb(0, 0, 128), pixel(25f, 25f))
            assertColor("label blends with the faded fill", Color.rgb(128, 128, 191), pixel(100f, 60f))
            // Opt-in: one flattened layer, the label is white over the screen.
            assertColor("offscreen compositing", Color.rgb(128, 128, 255), pixel(100f, 240f))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun windowPixels(activity: PamTestActivity): Pair<Bitmap, IntArray> {
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
        instrumentation.waitForIdleSync()
        val origin = IntArray(2)
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            activity.host.getChildAt(0).getLocationInWindow(origin)
        }
        val thread = android.os.HandlerThread("pam-pixel-copy").apply { start() }
        try {
            val done = java.util.concurrent.CountDownLatch(1)
            var result = -1
            android.view.PixelCopy.request(activity.window, bitmap, { code ->
                result = code
                done.countDown()
            }, android.os.Handler(thread.looper))
            assertTrue("PixelCopy timed out", done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("PixelCopy result", android.view.PixelCopy.SUCCESS, result)
        } finally {
            thread.quitSafely()
        }
        return bitmap to origin
    }

    /**
     * Removing the class that set a transform runs the declared
     * `transition: transform` back to the default, and re-adding it before a
     * frame keeps the box where it was instead of snapping to 0 and sliding.
     */
    @Test
    fun transformTransitionSurvivesRemovingAndReaddingAClassWithinAFrame() {
        val transition = "@property transform\n@duration 400\n@timing linear\n@delay 0"
        val activity = launchActivity()
        lateinit var renderer: PamRenderer
        lateinit var box: View
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                            Mutation.Create(
                                node(
                                    2, 1, NodeKind.VIEW,
                                    mapOf(
                                        PropKey.BACKGROUND_COLOR to color(0xFF6A1B9A),
                                        PropKey.ANIMATE_CHANGES to PropValue.Flag(true),
                                        PropKey.TRANSITION_SPEC to PropValue.Text(transition),
                                        PropKey.TRANSLATION_X to PropValue.Decimal(80.0),
                                    ),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                            Mutation.Layout(2, Frame(16f, 16f, 100f, 60f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            var failure: Throwable? = null
            instrumentation.runOnMainSync {
                runCatching {
                    box = views(renderer)[2]
                    val moved = 80f * box.resources.displayMetrics.density
                    assertEquals(moved, box.translationX, 0.5f)
                    renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.TRANSLATION_X, null))))
                    assertTrue(
                        "removing the class must transition, not snap: ${box.translationX}",
                        box.translationX > moved * 0.9f,
                    )
                    renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.TRANSLATION_X, PropValue.Decimal(80.0)))))
                    assertEquals("re-added within the frame: no visible jump", moved, box.translationX, 0.5f)
                    renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.TRANSLATION_X, null))))
                }.onFailure { failure = it }
            }
            failure?.let { throw it }
            val deadline = android.os.SystemClock.uptimeMillis() + 3_000
            var settled = false
            while (!settled && android.os.SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync { settled = abs(box.translationX) < 0.5f }
                if (!settled) Thread.sleep(16)
            }
            assertTrue("the removed transform transitions back to 0", settled)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    /**
     * RN's Switch (AppCompat SwitchCompat) draws a 14 dp track under its
     * 20 dp thumb; the platform Switch stretched PAM's track drawable to the
     * full 20 dp switch height.
     */
    @Test
    fun switchTrackIsFourteenDpThickLikeTheReactNativeSwitch() {
        val activity = launchActivity()
        try {
            var thickness = 0f
            var thumb = 0f
            var density = 1f
            var debug = ""
            instrumentation.runOnMainSync {
                val switch = PamSwitch(activity)
                density = activity.resources.displayMetrics.density
                switch.setTrackOffColor(Color.BLACK)
                switch.setThumbColor(Color.TRANSPARENT)
                switch.measure(
                    View.MeasureSpec.makeMeasureSpec((46.5f * density).roundToInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((27f * density).roundToInt(), View.MeasureSpec.EXACTLY),
                )
                switch.layout(0, 0, switch.measuredWidth, switch.measuredHeight)
                val bitmap = Bitmap.createBitmap(switch.width, switch.height, Bitmap.Config.ARGB_8888)
                switch.draw(Canvas(bitmap))
                // The middle of the 40 dp switch area (right-aligned), away from the round caps.
                val x = switch.width - (20f * density).roundToInt()
                // Coverage in pixels: anti-aliased edges count by their alpha.
                thickness = (0 until bitmap.height).sumOf { Color.alpha(bitmap.getPixel(x, it)) } / 255f
                debug = "switch ${switch.width}x${switch.height}, track bounds ${switch.trackDrawable?.bounds}"
                switch.setThumbColor(Color.RED)
                bitmap.eraseColor(Color.TRANSPARENT)
                switch.setTrackOffColor(Color.TRANSPARENT)
                switch.draw(Canvas(bitmap))
                val left = switch.width - (40f * density).roundToInt() + (10f * density).roundToInt()
                thumb = (0 until bitmap.height).sumOf { Color.alpha(bitmap.getPixel(left, it)) } / 255f
            }
            assertEquals("track thickness (px) $debug", 14f * density, thickness, 1.5f)
            assertEquals("thumb diameter (px)", 20f * density, thumb, 1.5f)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun assertColor(label: String, expected: Int, actual: Int) {
        val delta = maxOf(
            abs(Color.red(expected) - Color.red(actual)),
            abs(Color.green(expected) - Color.green(actual)),
            abs(Color.blue(expected) - Color.blue(actual)),
        )
        assertTrue("$label: expected #%06X, was #%06X".format(expected and 0xFFFFFF, actual and 0xFFFFFF), delta <= 3)
    }

    @Suppress("UNCHECKED_CAST")
    private fun views(renderer: PamRenderer): android.util.LongSparseArray<View> =
        PamRenderer::class.java.getDeclaredField("views").apply { isAccessible = true }
            .get(renderer) as android.util.LongSparseArray<View>

    private fun color(value: Long) = PropValue.Integer(value)

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ): NodeSpec = NodeSpec(id = id, parent = parent, index = 0, kind = kind, properties = properties)

    private fun launchActivity(): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as PamTestActivity
}
