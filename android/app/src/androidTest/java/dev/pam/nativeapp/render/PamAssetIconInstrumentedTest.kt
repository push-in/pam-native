package dev.pam.nativeapp.render

import android.content.Intent
import android.util.LongSparseArray
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Icons referenced by path (`asset://….png`, at most 16 KiB) paint in the
 * frame that lays their view out, like a `data:` icon, instead of a frame or
 * more later behind photos; a larger asset still loads off the main thread.
 */
@RunWith(AndroidJUnit4::class)
class PamAssetIconInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun aSmallPackagedIconPaintsInTheFrameThatLaysItOut() = withImage("asset://icons/small-dot.png") { image ->
        assertNotNull("a ≤16 KiB asset decodes synchronously", image.drawable)
    }

    @Test fun aLargePackagedImageStillLoadsAsynchronously() = withImage("asset://icons/large-noise.png") { image ->
        assertNull("a >16 KiB asset never decodes on the main thread", image.drawable)
    }

    private fun withImage(source: String, block: (PamImageView) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        try {
            var failure: Throwable? = null
            lateinit var image: PamImageView
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(listOf(listOf(
                    Mutation.Create(NodeSpec(1, 0, 0, NodeKind.SCREEN, emptyMap())),
                    Mutation.Create(NodeSpec(2, 1, 0, NodeKind.IMAGE, mapOf(
                        PropKey.SOURCE to PropValue.Text(source),
                        PropKey.IMAGE_FADE_DURATION_MS to PropValue.Integer(0),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(16f, 16f, 24f, 24f)),
                    Mutation.SetRoot(1),
                )))
                // Lay the host out now, inside this main-thread turn: an
                // async decode cannot have posted its result yet.
                val host = activity.host
                host.measure(
                    View.MeasureSpec.makeMeasureSpec(host.width.coerceAtLeast(360), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(host.height.coerceAtLeast(640), View.MeasureSpec.EXACTLY),
                )
                host.layout(host.left, host.top, host.left + host.measuredWidth, host.top + host.measuredHeight)
                image = views(renderer)[2] as PamImageView
                failure = runCatching { block(image) }.exceptionOrNull()
            }
            failure?.let { throw it }
            if (source.contains("large")) {
                repeat(200) {
                    var loaded = false
                    instrumentation.runOnMainSync { loaded = image.drawable != null }
                    if (loaded) return
                    Thread.sleep(16)
                }
                error("Large asset did not finish loading")
            }
        } finally {
            instrumentation.runOnMainSync {
                runCatching { renderer.close() }
                activity.finish()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun views(renderer: PamRenderer): LongSparseArray<View> = PamRenderer::class.java
        .getDeclaredField("views").apply { isAccessible = true }.get(renderer) as LongSparseArray<View>
}
