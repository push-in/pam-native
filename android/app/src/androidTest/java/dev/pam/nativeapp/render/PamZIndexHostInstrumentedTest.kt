package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Transparent overlay wrappers must retain their authored stacking context. */
@RunWith(AndroidJUnit4::class)
class PamZIndexHostInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun initialPositiveZIndexKeepsTransparentOverlayAboveLaterSibling() = withScene(2.0) { activity, renderer ->
        instrumentation.runOnMainSync {
            assertNotNull("a nonzero zIndex needs a native compositing host", renderer.viewForNode(2))
            assertSame(renderer.viewForNode(2), renderer.viewForNode(3)?.parent)
        }
        assertPixel(activity, Color.RED)
    }

    @Test
    fun updatingZIndexPromotesAndDemotesWithoutReplacingDescendant() = withScene(0.0) { activity, renderer ->
        val child = renderer.viewForNode(3)
        instrumentation.runOnMainSync {
            assertNull("zero zIndex still permits layout-only elision", renderer.viewForNode(2))
            renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.Z_INDEX, PropValue.Decimal(2.0)))))
            assertNotNull("zIndex added after mount must promote the wrapper", renderer.viewForNode(2))
            assertSame(child, renderer.viewForNode(3))
            assertSame(renderer.viewForNode(2), child?.parent)
        }
        assertPixel(activity, Color.RED)
        instrumentation.runOnMainSync {
            renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.Z_INDEX, PropValue.Decimal(-2.0)))))
        }
        assertPixel(activity, Color.GREEN)
        instrumentation.runOnMainSync {
            renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.Z_INDEX, null))))
            assertNull("removing zIndex restores elision", renderer.viewForNode(2))
            assertSame("demotion retains descendant identity", child, renderer.viewForNode(3))
        }
    }

    private fun withScene(zIndex: Double, check: (PamTestActivity, PamRenderer) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(listOf(listOf(
                    Mutation.Create(NodeSpec(1, 0, 0, NodeKind.SCREEN, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFF)))),
                    Mutation.Create(NodeSpec(2, 1, 0, NodeKind.COLUMN, mapOf(PropKey.Z_INDEX to PropValue.Decimal(zIndex)))),
                    Mutation.Create(NodeSpec(3, 2, 0, NodeKind.VIEW, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFF0000)))),
                    Mutation.Create(NodeSpec(4, 1, 1, NodeKind.VIEW, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF00FF00)))),
                    Mutation.Layout(1, Frame(0f, 0f, 320f, 480f)),
                    Mutation.Layout(2, Frame(20f, 20f, 160f, 120f)),
                    Mutation.Layout(3, Frame(20f, 20f, 160f, 120f)),
                    Mutation.Layout(4, Frame(20f, 20f, 160f, 120f)),
                    Mutation.SetRoot(1),
                )))
            }
            instrumentation.waitForIdleSync()
            check(activity, renderer)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun assertPixel(activity: PamTestActivity, expected: Int) {
        instrumentation.waitForIdleSync()
        Thread.sleep(150)
        lateinit var bitmap: Bitmap
        val origin = IntArray(2)
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            activity.host.getChildAt(0).getLocationInWindow(origin)
        }
        val thread = HandlerThread("pam-z-index-pixel").apply { start() }
        try {
            val done = CountDownLatch(1)
            var status = -1
            PixelCopy.request(activity.window, bitmap, { status = it; done.countDown() }, Handler(thread.looper))
            assertTrue("PixelCopy completed", done.await(5, TimeUnit.SECONDS))
            assertEquals(PixelCopy.SUCCESS, status)
            val density = activity.resources.displayMetrics.density
            assertEquals("native compositing follows the authored zIndex", expected,
                bitmap.getPixel(origin[0] + (80 * density).roundToInt(), origin[1] + (80 * density).roundToInt()))
        } finally {
            bitmap.recycle()
            thread.quitSafely()
        }
    }
}
