package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Pixel contract for CSS gradients, box shadows, filter and backdrop-filter. */
@RunWith(AndroidJUnit4::class)
class PamCssEffectsInstrumentedTest {
    @Test
    fun linearGradientStopsInterpolateLikeBrowsers() {
        val gradient = """[{"t":1,"r":0,"m":1,"a":90,"s":[[4294901760,0,0],[4278190335,0,0]]}]"""
        render(listOf(child(2, Frame(0f, 0f, 200f, 50f), mapOf(PropKey.BACKGROUND_GRADIENT to text(gradient))))) { _, views ->
            val bitmap = software(views.getValue(2))
            val left = bitmap.getPixel(1, bitmap.height / 2)
            val right = bitmap.getPixel(bitmap.width - 2, bitmap.height / 2)
            val middle = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue("left edge is red: ${hex(left)}", Color.red(left) > 245 && Color.blue(left) < 10)
            assertTrue("right edge is blue: ${hex(right)}", Color.blue(right) > 245 && Color.red(right) < 10)
            assertNear("midpoint mixes red and blue", 128, Color.red(middle), 8)
            assertNear("midpoint mixes red and blue", 128, Color.blue(middle), 8)
        }
    }

    @Test
    fun transparentStopsFadeWithoutDarkening() {
        // to right: red → transparent; premultiplied interpolation keeps pure red.
        val gradient = """[{"t":1,"r":0,"m":1,"a":90,"s":[[4294901760,0,0],[0,0,0]]}]"""
        render(listOf(child(2, Frame(0f, 0f, 200f, 50f), mapOf(PropKey.BACKGROUND_GRADIENT to text(gradient))))) { _, views ->
            val bitmap = software(views.getValue(2))
            val middle = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertNear("half alpha", 128, Color.alpha(middle), 10)
            assertTrue("no dark fringe: ${hex(middle)}", Color.red(middle) > 240)
        }
    }

    @Test
    fun hardStopsPositionsAndRadiusClipping() {
        // linear-gradient(to right, red 50%, blue 50%) with a pill radius.
        val gradient = """[{"t":1,"r":0,"m":1,"a":90,"s":[[4294901760,0.5,1],[4278190335,0.5,1]]}]"""
        render(
            listOf(
                child(
                    2,
                    Frame(0f, 0f, 200f, 100f),
                    mapOf(
                        PropKey.BACKGROUND_GRADIENT to text(gradient),
                        PropKey.BORDER_RADIUS to PropValue.Decimal(50.0),
                    ),
                ),
            ),
        ) { _, views ->
            val bitmap = software(views.getValue(2))
            val y = bitmap.height / 2
            assertEquals(Color.RED, bitmap.getPixel((bitmap.width * 0.45f).toInt(), y))
            assertEquals(Color.BLUE, bitmap.getPixel((bitmap.width * 0.55f).toInt(), y))
            assertEquals("rounded corner is clipped", 0, Color.alpha(bitmap.getPixel(1, 1)))
        }
    }

    @Test
    fun radialGradientAndLinearGradientComponentPoints() {
        // radial-gradient(circle closest-side, white, black) and expo start/end points.
        val radial = """[{"t":2,"r":0,"e":0,"z":1,"c":[[0.5,1],[0.5,1]],"s":[[4294967295,0,0],[4278190080,0,0]]}]"""
        val points = """[{"t":1,"r":0,"m":3,"p":[0.0,1.0,1.0,0.0],"s":[[4294901760,0,1],[4278255360,1,1]]}]"""
        render(
            listOf(
                child(2, Frame(0f, 0f, 100f, 100f), mapOf(PropKey.BACKGROUND_GRADIENT to text(radial))),
                child(3, Frame(0f, 120f, 100f, 100f), mapOf(PropKey.BACKGROUND_GRADIENT to text(points))),
            ),
        ) { _, views ->
            val circle = software(views.getValue(2))
            val center = circle.getPixel(circle.width / 2, circle.height / 2)
            val edge = circle.getPixel(1, circle.height / 2)
            assertTrue("radial center is white: ${hex(center)}", Color.red(center) > 245)
            assertTrue("radial edge is black: ${hex(edge)}", Color.red(edge) < 10)
            val diagonal = software(views.getValue(3))
            val bottomLeft = diagonal.getPixel(1, diagonal.height - 2)
            val topRight = diagonal.getPixel(diagonal.width - 2, 1)
            assertTrue("start point is red: ${hex(bottomLeft)}", Color.red(bottomLeft) > 240 && Color.green(bottomLeft) < 15)
            assertTrue("end point is green: ${hex(topRight)}", Color.green(topRight) > 240 && Color.red(topRight) < 15)
        }
    }

    @Test
    fun multipleOuterShadowsAndInsetShadowPaint() {
        // box-shadow: 0 0 0 10px red, 0 30px 0 0 blue, inset 0 0 0 10px green.
        val shadows = "[[0,0,0,10,4294901760,0],[0,30,0,0,4278190335,0],[0,0,0,10,4278255360,1]]"
        render(
            listOf(
                child(
                    2,
                    Frame(40f, 40f, 200f, 100f),
                    mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFF),
                        PropKey.BOX_SHADOWS to text(shadows),
                    ),
                ),
            ),
        ) { parent, views ->
            val view = views.getValue(2)
            val bitmap = software(parent)
            val y = view.top + view.height / 2
            assertEquals("spread ring left of the box", Color.RED, bitmap.getPixel(view.left - dp(view, 5f), y))
            assertEquals(
                "offset shadow below the spread ring",
                Color.BLUE,
                bitmap.getPixel(view.left + view.width / 2, view.bottom + dp(view, 20f)),
            )
            assertEquals("inset spread inside the edge", Color.GREEN, bitmap.getPixel(view.left + dp(view, 5f), y))
            assertEquals("background in the middle", Color.WHITE, bitmap.getPixel(view.left + view.width / 2, y))
        }
    }

    @Test
    fun blurredShadowFadesOutsideTheBox() {
        val shadows = "[[0,0,20,0,4278190080,0],[0,0,20,0,4278190080,0]]"
        render(
            listOf(
                child(
                    2,
                    Frame(60f, 60f, 120f, 120f),
                    mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFF),
                        PropKey.BORDER_RADIUS to PropValue.Decimal(16.0),
                        PropKey.BOX_SHADOWS to text(shadows),
                    ),
                ),
            ),
        ) { parent, views ->
            val view = views.getValue(2)
            val bitmap = software(parent)
            val y = view.top + view.height / 2
            // Shadow coverage over the white screen = 255 - red channel.
            val near = 255 - Color.red(bitmap.getPixel(view.left - dp(view, 2f), y))
            val far = 255 - Color.red(bitmap.getPixel(view.left - dp(view, 18f), y))
            assertTrue("blur is soft near the edge: $near", near in 60..250)
            assertTrue("blur fades with distance: $far < $near", far < near)
            assertEquals("blur stays inside 3 sigma", Color.WHITE, bitmap.getPixel(view.left - dp(view, 40f), y))
        }
    }

    @Test
    fun colorMatrixFilterRendersGrayscale() {
        val grayscale = "0.2126,0.7152,0.0722,0,0,0.2126,0.7152,0.0722,0,0,0.2126,0.7152,0.0722,0,0,0,0,0,1,0"
        render(
            listOf(
                child(
                    2,
                    Frame(0f, 0f, 120f, 120f),
                    mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFF0000),
                        PropKey.FILTER_COLOR_MATRIX to text(grayscale),
                    ),
                ),
            ),
        ) { _, _ -> }
        val pixel = lastActivityPixel { view -> view.width / 2 to view.height / 2 }
        assertNear("grayscale red", 54, Color.red(pixel), 6)
        assertNear("grayscale equal channels", Color.red(pixel), Color.green(pixel), 3)
    }

    @Test
    fun blurFilterSoftensEdgesOnApi31() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val gradient = """[{"t":1,"r":0,"m":1,"a":90,"s":[[4294901760,0.5,1],[4278190335,0.5,1]]}]"""
        render(
            listOf(
                child(
                    2,
                    Frame(0f, 0f, 200f, 120f),
                    mapOf(
                        PropKey.BACKGROUND_GRADIENT to text(gradient),
                        PropKey.BLUR_RADIUS to PropValue.Decimal(8.0),
                    ),
                ),
            ),
        ) { _, _ -> }
        val pixel = lastActivityPixel { view -> view.width / 2 to view.height / 2 }
        assertTrue("blur mixes the hard stop: ${hex(pixel)}", Color.red(pixel) in 60..200 && Color.blue(pixel) in 60..200)
    }

    @Test
    fun backdropFilterBlursContentBehindOnApi31() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val gradient = """[{"t":1,"r":0,"m":1,"a":90,"s":[[4294901760,0.5,1],[4278190335,0.5,1]]}]"""
        render(
            listOf(
                child(2, Frame(0f, 0f, 240f, 200f), mapOf(PropKey.BACKGROUND_GRADIENT to text(gradient))),
                child(
                    3,
                    Frame(60f, 40f, 120f, 120f),
                    mapOf(
                        PropKey.BACKDROP_BLUR_RADIUS to PropValue.Decimal(12.0),
                        PropKey.BORDER_RADIUS to PropValue.Decimal(24.0),
                    ),
                ),
            ),
        ) { _, _ -> }
        val pixel = lastActivityPixel(viewId = 3) { view -> view.width / 2 to view.height / 2 }
        assertTrue("backdrop blur mixes the colors behind: ${hex(pixel)}", Color.red(pixel) in 60..200 && Color.blue(pixel) in 60..200)
        val outside = lastActivityPixel(viewId = 2) { view -> view.width / 2 to dp(view, 10f) }
        assertTrue("outside the glass stays sharp: ${hex(outside)}", Color.red(outside) > 240 || Color.blue(outside) > 240)
    }

    private var activity: PamTestActivity? = null
    private val viewsById = HashMap<Long, View>()

    private fun render(
        children: List<NodeSpec>,
        assertions: (ViewGroup, Map<Long, View>) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activity?.let { previous -> onMain(instrumentation) { previous.finish() } }
        val launched = launchActivity(instrumentation)
        activity = launched
        onMain(instrumentation) {
            val renderer = PamRenderer(launched, launched.host) { _, _, _ -> }
            renderer.commit(
                listOf(
                    buildList {
                        add(Mutation.Create(NodeSpec(id = 1, parent = 0, index = 0, kind = NodeKind.SCREEN, properties = mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFF)))))
                        children.forEach { add(Mutation.Create(it)) }
                        add(Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)))
                        children.forEach { add(Mutation.Layout(it.id, frames.getValue(it.id))) }
                        add(Mutation.SetRoot(1))
                    },
                ),
            )
        }
        instrumentation.waitForIdleSync()
        onMain(instrumentation) {
            val parent = launched.host.getChildAt(0) as ViewGroup
            viewsById.clear()
            children.forEachIndexed { index, spec -> viewsById[spec.id] = parent.getChildAt(index) }
            assertions(parent, viewsById)
        }
    }

    private val frames = HashMap<Long, Frame>()

    private fun child(id: Long, frame: Frame, properties: Map<PropKey, PropValue>): NodeSpec {
        frames[id] = frame
        return NodeSpec(id = id, parent = 1, index = (id - 2).toInt(), kind = NodeKind.COLUMN, properties = properties)
    }

    /** Reads one composited window pixel (hardware render effects included). */
    private fun lastActivityPixel(viewId: Long = 2, point: (View) -> Pair<Int, Int>): Int {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val current = activity ?: error("No activity")
        Thread.sleep(400)
        instrumentation.waitForIdleSync()
        var rect = Rect()
        onMain(instrumentation) {
            val view = viewsById.getValue(viewId)
            val location = IntArray(2)
            view.getLocationInWindow(location)
            val (x, y) = point(view)
            rect = Rect(location[0] + x, location[1] + y, location[0] + x + 1, location[1] + y + 1)
        }
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val thread = HandlerThread("pam-pixel-copy").also { it.start() }
        try {
            val latch = CountDownLatch(1)
            var result = PixelCopy.ERROR_UNKNOWN
            PixelCopy.request(current.window, rect, bitmap, { code ->
                result = code
                latch.countDown()
            }, Handler(thread.looper))
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, result)
        } finally {
            thread.quitSafely()
        }
        return bitmap.getPixel(0, 0)
    }

    private fun software(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun text(value: String): PropValue = PropValue.Text(value)

    private fun hex(color: Int): String = String.format("#%08X", color)

    private fun assertNear(message: String, expected: Int, actual: Int, tolerance: Int) {
        assertTrue("$message: expected $expected±$tolerance, got $actual", abs(expected - actual) <= tolerance)
    }

    private fun launchActivity(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as PamTestActivity

    private fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun dp(view: View, value: Float): Int = (value * view.resources.displayMetrics.density).toInt()

    @org.junit.After
    fun finishActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        activity?.let { current -> onMain(instrumentation) { current.finish() } }
        activity = null
    }
}
