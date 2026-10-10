package dev.pam.nativeapp.modules

import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** ViewCapture (1.37.0): a laid-out view tree, invisible on screen, written as a private image. */
@RunWith(AndroidJUnit4::class)
class ViewCaptureModuleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun capturesTheSubtreeOfAnInvisibleViewAtTheRequestedPixelRatio() {
        lateinit var target: View
        instrumentation.runOnMainSync {
            // An opacity-0 host keeps the sticker out of sight; the capture draws it anyway.
            val host = FrameLayout(context).apply { alpha = 0f }
            target = FrameLayout(context).apply {
                setBackgroundColor(Color.RED)
                addView(View(context).apply { setBackgroundColor(Color.BLUE) }, FrameLayout.LayoutParams(50, 100))
            }
            host.addView(target, FrameLayout.LayoutParams(100, 100))
            host.measure(
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
            )
            host.layout(0, 0, 100, 100)
        }
        val density = context.resources.displayMetrics.density
        val module = ViewCaptureModule(context.filesDir, { density }) { ref -> if (ref == "sticker") target else null }
        val result = capture(module, mapOf("ref" to WireValue.Text("sticker"), "pixelRatio" to WireValue.Decimal(density * 2.0), "directory" to WireValue.Text("tests/captures")))
        assertEquals(ModuleResultStatus.SUCCESS, result.first)
        val values = WireMap.decode(result.second)
        assertEquals(200L, (values["width"] as WireValue.Integer).value)
        assertEquals(200L, (values["height"] as WireValue.Integer).value)
        val file = File(File(context.filesDir, "pam-files"), (values["path"] as WireValue.Text).value)
        assertTrue(file.path.contains("tests/captures"))
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        assertEquals(Color.BLUE, bitmap.getPixel(40, 100))
        assertEquals(Color.RED, bitmap.getPixel(160, 100))
        file.delete()
        module.close()
    }

    @Test
    fun anUnknownOrUnmeasuredRefFails() {
        lateinit var unmeasured: View
        instrumentation.runOnMainSync { unmeasured = View(context) }
        val module = ViewCaptureModule(context.filesDir, { 2f }) { ref -> if (ref == "empty") unmeasured else null }
        val missing = capture(module, mapOf("ref" to WireValue.Text("missing")))
        assertEquals(ModuleResultStatus.FAILURE, missing.first)
        assertTrue(String(missing.second).contains("missing"))
        assertEquals(ModuleResultStatus.FAILURE, capture(module, mapOf("ref" to WireValue.Text("empty"))).first)
        module.close()
    }

    private fun capture(module: ViewCaptureModule, values: Map<String, WireValue>): Pair<ModuleResultStatus, ByteArray> {
        val latch = CountDownLatch(1)
        var outcome: Pair<ModuleResultStatus, ByteArray>? = null
        module.invoke("capture", WireMap.encode(values)) { status, payload ->
            outcome = status to payload
            latch.countDown()
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        return requireNotNull(outcome)
    }
}
