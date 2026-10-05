package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import org.junit.Test
import org.junit.runner.RunWith

/** CSS paint properties compiled by the PHP style compiler reach Android views. */
@RunWith(AndroidJUnit4::class)
class PamCssPaintInstrumentedTest {
    @Test
    fun perSideBorderColorsPaintEachEdge() {
        render(
            mapOf(
                PropKey.BACKGROUND_COLOR to PropValue.Integer(Color.WHITE.toLong() and 0xFFFFFFFF),
                PropKey.BORDER_TOP_WIDTH to PropValue.Decimal(8.0),
                PropKey.BORDER_BOTTOM_WIDTH to PropValue.Decimal(8.0),
                PropKey.BORDER_COLOR to PropValue.Integer(0xFF00FF00),
                PropKey.BORDER_TOP_COLOR to PropValue.Integer(0xFFFF0000),
                PropKey.BORDER_BOTTOM_COLOR to PropValue.Integer(0xFF0000FF),
            ),
            NodeKind.COLUMN,
        ) { view ->
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val inset = dp(view, 2f)
            assertEquals(Color.RED, bitmap.getPixel(view.width / 2, inset))
            assertEquals(Color.BLUE, bitmap.getPixel(view.width / 2, view.height - inset))
            assertEquals(Color.WHITE, bitmap.getPixel(view.width / 2, view.height / 2))
        }
    }

    @Test
    fun textShadowFontFeaturesAndJustifyApply() {
        render(
            mapOf(
                PropKey.TEXT to PropValue.Text("0123456789 justified text that wraps across lines"),
                PropKey.TEXT_SHADOW_OFFSET_X to PropValue.Decimal(1.0),
                PropKey.TEXT_SHADOW_OFFSET_Y to PropValue.Decimal(2.0),
                PropKey.TEXT_SHADOW_RADIUS to PropValue.Decimal(3.0),
                PropKey.TEXT_SHADOW_COLOR to PropValue.Integer(0x80000000),
                PropKey.FONT_FEATURE_SETTINGS to PropValue.Text("'tnum' 1"),
                PropKey.TEXT_ALIGN to PropValue.Integer(4),
            ),
            NodeKind.TEXT,
        ) { view ->
            val text = view as TextView
            assertEquals(0x80000000.toInt(), text.shadowColor)
            assertEquals(dp(view, 1f).toFloat(), text.shadowDx, 1f)
            assertEquals(dp(view, 2f).toFloat(), text.shadowDy, 1f)
            assertEquals(dp(view, 3f).toFloat(), text.shadowRadius, 1f)
            assertEquals("'tnum' 1", text.fontFeatureSettings)
            assertEquals(Layout.JUSTIFICATION_MODE_INTER_WORD, text.justificationMode)
        }
    }

    @Test
    fun transformOriginAndPercentTranslationUseTheViewBox() {
        render(
            mapOf(
                PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF101010),
                PropKey.TRANSFORM_ORIGIN_X to PropValue.Decimal(0.0),
                PropKey.TRANSFORM_ORIGIN_Y to PropValue.Decimal(100.0),
                PropKey.TRANSLATION_Y_PERCENT to PropValue.Decimal(-50.0),
                PropKey.ROTATION to PropValue.Decimal(10.0),
            ),
            NodeKind.COLUMN,
        ) { view ->
            assertEquals(0f, view.pivotX, 0.01f)
            assertEquals(view.height.toFloat(), view.pivotY, 0.01f)
            assertEquals(-view.height / 2f, view.translationY, 1f)
            assertTrue(view.rotation > 9f)
        }
    }

    private fun render(
        properties: Map<PropKey, PropValue>,
        kind: NodeKind,
        assertions: (View) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                            Mutation.Create(node(2, 1, kind, properties)),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(20f, 20f, 200f, 100f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                val view = (activity.host.getChildAt(0) as ViewGroup).getChildAt(0)
                assertions(view)
            }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ): NodeSpec = NodeSpec(id = id, parent = parent, index = 0, kind = kind, properties = properties)

    private fun launchActivity(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as PamTestActivity

    private fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun dp(view: View, value: Float): Int =
        (value * view.resources.displayMetrics.density).toInt()
}
