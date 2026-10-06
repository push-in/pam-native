package dev.pam.nativeapp.render

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.EnumMap

@RunWith(AndroidJUnit4::class)
class PamInitialBackgroundPropertiesInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun initialContainerUsesOneDrawableAndMatchesTheUnbatchedPropertyPassExactly() {
        val cases = listOf(
            properties(),
            properties() + (PropKey.BORDER_STYLE to PropValue.Integer(3)),
            properties() + mapOf(
                PropKey.BORDER_LEFT_WIDTH to PropValue.Decimal(3.0),
                PropKey.BORDER_LEFT_COLOR to PropValue.Integer(Color.RED.toLong()),
            ),
        )
        for (properties in cases) {
            scene(properties) { renderer, parent, target ->
                val container = target as CountingContainer
                assertEquals("one final background assignment", 1, container.assignments)
                val before = pixels(parent)
                val flags = flags(container)
                val shadows = shadows(container)
                assertTrue(container.clipChildren)
                assertFalse(container.clipToPadding)
                assertTrue("outer shadow remains registered", shadows.isNotEmpty())

                container.assignments = 0
                replayUnbatched(renderer, target, properties)
                assertTrue("old per-property path performs repeated assignments", container.assignments >= 6)
                assertEquals(flags, flags(container))
                assertEquals(shadows, shadows(container))
                assertTrue("all scene pixels, borders, clip and shadows must match", before.sameAs(pixels(parent)))

                val old = target.background
                val count = container.assignments
                renderer.commit(listOf(listOf(
                    Mutation.Update(2, PropKey.BACKGROUND_COLOR, PropValue.Integer(Color.GREEN.toLong())),
                )))
                assertEquals("incremental update is immediate", count + 1, container.assignments)
                assertNotSame(old, target.background)
                assertFalse("changed fill must draw", before.sameAs(pixels(parent)))
                renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.OVERFLOW, null))))
                assertFalse(container.clipChildren)
                assertEquals("unclipped child reaches the square corner", Color.BLUE, pixels(target).getPixel(0, 0))
            }
        }
    }

    @Test
    fun rippleFlagsAndNativePressedColorsSurviveInitializationAndUpdates() {
        for ((foreground, borderless) in listOf(false to false, true to false, false to true)) {
            val base = linkedMapOf(
                PropKey.BACKGROUND_COLOR to PropValue.Integer(Color.GREEN.toLong()),
                PropKey.BORDER_RADIUS to PropValue.Decimal(14.0),
                PropKey.BORDER_BOTTOM_RIGHT_RADIUS to PropValue.Decimal(4.0),
                PropKey.RIPPLE_COLOR to PropValue.Integer(Color.BLACK.toLong()),
                PropKey.RIPPLE_ALPHA to PropValue.Decimal(0.22),
                PropKey.RIPPLE_RADIUS to PropValue.Decimal(24.0),
                PropKey.RIPPLE_FOREGROUND to PropValue.Flag(foreground),
                PropKey.RIPPLE_BORDERLESS to PropValue.Flag(borderless),
                PropKey.ON_PRESS to PropValue.Flag(true),
                PropKey.NATIVE_STATE_STYLES to PropValue.Text(
                    """{"1":{"${PropKey.BACKGROUND_COLOR.value}":${Color.RED.toLong()}}}""",
                ),
            )
            scene(base, NodeKind.PRESSABLE, child = false) { renderer, _, target ->
                val pressable = target as PamPressable
                val overlay = if (foreground || borderless) target.foreground else target.background
                assertTrue(overlay is RippleDrawable)
                val ripple = overlay as RippleDrawable
                assertEquals((24f * target.resources.displayMetrics.density + 0.5f).toInt(), ripple.radius)
                assertEquals(!borderless, ripple.findDrawableByLayerId(android.R.id.mask) != null)
                assertTrue(ripple.isStateful)
                val flags = flags(pressable)
                val before = pixels(target)
                replayUnbatched(renderer, target, base)
                assertEquals(flags, flags(pressable))
                assertTrue(before.sameAs(pixels(target)))
                pressable.onPressedStateChanged?.invoke(true)
                assertEquals("native pressed override", Color.RED, center(target))
                pressable.onPressedStateChanged?.invoke(false)
                assertEquals("native release restores authored fill", Color.GREEN, center(target))
                renderer.commit(listOf(listOf(
                    Mutation.Update(2, PropKey.BACKGROUND_COLOR, PropValue.Integer(Color.YELLOW.toLong())),
                )))
                assertEquals("later mutation remains immediate", Color.YELLOW, center(target))
            }
        }
    }

    private fun properties() = linkedMapOf(
        PropKey.BACKGROUND_COLOR to PropValue.Integer(0x661B7A4E),
        PropKey.BORDER_WIDTH to PropValue.Decimal(1.0),
        PropKey.BORDER_COLOR to PropValue.Integer(0x660F1410),
        PropKey.BORDER_RADIUS to PropValue.Decimal(14.0),
        PropKey.BORDER_BOTTOM_RIGHT_RADIUS to PropValue.Decimal(4.0),
        PropKey.OVERFLOW to PropValue.Integer(2),
        PropKey.BOX_SHADOWS to PropValue.Text("[[0,2,0,3,1727987712,0],[0,0,0,2,1711341312,1]]"),
    )

    private fun scene(
        properties: Map<PropKey, PropValue>,
        kind: NodeKind = NodeKind.VIEW,
        child: Boolean = true,
        check: (PamRenderer, View, View) -> Unit,
    ) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                if (kind == NodeKind.VIEW) {
                    val container = CountingContainer(activity).apply { assignments = 0 }
                    pool(renderer)[PrewarmPool.CONTAINER] = ArrayDeque<View>().apply {
                        add(PamContainer(activity))
                        add(container)
                    }
                }
                val mutations = mutableListOf<Mutation>(
                    Mutation.Create(NodeSpec(1, 0, 0, NodeKind.SCREEN, emptyMap())),
                    Mutation.Create(NodeSpec(2, 1, 0, kind, properties)),
                    Mutation.Layout(1, Frame(0f, 0f, 240f, 160f)),
                    Mutation.Layout(2, Frame(20f, 20f, 140f, 64f)),
                )
                if (child) mutations += listOf(
                    Mutation.Create(NodeSpec(3, 2, 0, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(Color.BLUE.toLong()),
                    ))),
                    Mutation.Layout(3, Frame(18f, 18f, 54f, 68f)),
                )
                renderer.commit(listOf(mutations + Mutation.SetRoot(1)))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val views = field(renderer, "views") as LongSparseArray<*>
                check(renderer, views[1] as View, views[2] as View)
            }
        } finally {
            instrumentation.runOnMainSync { renderer.close(); activity.finish() }
        }
    }

    /** Exercise the previous immediate path against the same complete state. */
    private fun replayUnbatched(renderer: PamRenderer, view: View, properties: Map<PropKey, PropValue>) {
        val state = (field(renderer, "nodes") as LongSparseArray<*>)[2]
        val apply = PamRenderer::class.java.declaredMethods.single { it.name == "applyProperty" }.apply { isAccessible = true }
        properties.forEach { (key, value) -> apply.invoke(renderer, view, state, key, value) }
    }

    private fun pixels(view: View) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }

    private fun center(view: View): Int = pixels(view).getPixel(view.width / 2, view.height / 2)

    private fun flags(view: PamContainer) = listOf(
        view.background?.javaClass?.name, view.foreground?.javaClass?.name,
        view.background?.isStateful, view.foreground?.isStateful,
        view.clipChildren, view.clipToPadding,
        (field(view, "overflowClipRadii", PamContainer::class.java) as FloatArray).contentToString(),
        (field(view, "overflowClipInsets", PamContainer::class.java) as FloatArray).contentToString(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun shadows(view: View): List<String> =
        ((field(PamBoxShadows, "values") as Map<View, List<PamBoxShadow>>)[view] ?: emptyList()).map {
            "${it.offsetX},${it.offsetY},${it.blurRadius},${it.spreadRadius},${it.color},${it.cornerRadii.contentToString()}"
        }

    private fun field(owner: Any, name: String, type: Class<*> = owner.javaClass): Any? =
        type.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    @Suppress("UNCHECKED_CAST")
    private fun pool(renderer: PamRenderer) = field(renderer, "prewarmedViews") as EnumMap<PrewarmPool, ArrayDeque<View>>

    private class CountingContainer(context: Context) : PamContainer(context) {
        var assignments = 0
        @Suppress("DEPRECATION")
        override fun setBackgroundDrawable(background: Drawable?) {
            assignments++
            super.setBackgroundDrawable(background)
        }
    }
}
