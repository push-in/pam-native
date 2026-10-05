package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
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
import org.junit.Test
import org.junit.runner.RunWith

/** React Native layout/paint parity: borders over clipped content, sticky headers, pressed transforms. */
@RunWith(AndroidJUnit4::class)
class PamLayoutParityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val density = instrumentation.targetContext.resources.displayMetrics.density

    @Test
    fun avatarBorderRingStaysVisibleAroundClippedContent() {
        render(
            listOf(
                NodeSpec(
                    2, 1, 0, NodeKind.COLUMN,
                    mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFF),
                        PropKey.BORDER_WIDTH to PropValue.Decimal(4.0),
                        PropKey.BORDER_COLOR to PropValue.Integer(0xFFFF0000),
                        PropKey.BORDER_RADIUS to PropValue.Decimal(40.0),
                        PropKey.OVERFLOW to PropValue.Integer(2),
                    ),
                ) to Frame(20f, 20f, 80f, 80f),
                // Engine frame inset by the 4 pt border (Yoga padding box).
                NodeSpec(3, 2, 0, NodeKind.COLUMN, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF0000FF))) to
                    Frame(24f, 24f, 72f, 72f),
            ),
        ) { views ->
            val avatar = views.getValue(2)
            val bitmap = Bitmap.createBitmap(avatar.width, avatar.height, Bitmap.Config.ARGB_8888)
            avatar.draw(Canvas(bitmap))
            val ring = bitmap.getPixel((2 * density).toInt(), avatar.height / 2)
            val content = bitmap.getPixel(avatar.width / 2, avatar.height / 2)
            // Inner-radius clip: the content's square corner never covers the ring.
            val diagonal = (avatar.width / 2 + (avatar.width / 2 - 3 * density) * 0.7071f).toInt()
            val nearRingDiagonal = bitmap.getPixel(diagonal, diagonal)
            assertEquals(Color.RED, ring)
            assertEquals(Color.BLUE, content)
            assertTrue("diagonal ring pixel must not be content: ${Integer.toHexString(nearRingDiagonal)}", Color.blue(nearRingDiagonal) < 128)
        }
    }

    @Test
    fun stickyHeaderPinsToTheTopUntilTheNextHeaderPushesIt() {
        val children = buildList {
            add(NodeSpec(3, 2, 0, NodeKind.COLUMN, mapOf(PropKey.STICKY_HEADER to PropValue.Flag(true), PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF00FF00))) to Frame(0f, 0f, 300f, 40f))
            for (index in 0 until 10) {
                add(NodeSpec(10L + index, 2, index + 1, NodeKind.COLUMN, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFEEEEEE))) to Frame(0f, 40f + index * 60f, 300f, 60f))
            }
            add(NodeSpec(4, 2, 11, NodeKind.COLUMN, mapOf(PropKey.STICKY_HEADER to PropValue.Flag(true))) to Frame(0f, 640f, 300f, 40f))
            for (index in 0 until 10) {
                add(NodeSpec(30L + index, 2, index + 12, NodeKind.COLUMN, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFDDDDDD))) to Frame(0f, 680f + index * 60f, 300f, 60f))
            }
        }
        render(listOf(NodeSpec(2, 1, 0, NodeKind.SCROLL, emptyMap()) to Frame(0f, 0f, 300f, 400f)) + children) { views ->
            val scroll = views.getValue(2) as PamScrollContainer
            val header = views.getValue(3)
            val active = scroll.getChildAt(0) as ViewGroup
            active.scrollTo(0, (200 * density).toInt())
            assertEquals(200 * density, header.translationY, 1.5f)
            active.scrollTo(0, (620 * density).toInt())
            // The second header (at 640) pushes the first one up.
            assertEquals((640 - 40) * density - header.top, header.translationY, 1.5f)
            active.scrollTo(0, 0)
            assertEquals(0f, header.translationY)
        }
    }

    @Test
    fun pressedStateTranslationIsAuthoredInPoints() {
        val states = """{"1":{"${PropKey.TRANSLATION_Y.value}":1.1}}"""
        render(
            listOf(
                NodeSpec(
                    2, 1, 0, NodeKind.PRESSABLE,
                    mapOf(
                        PropKey.ON_PRESS to PropValue.Flag(true),
                        PropKey.NATIVE_STATE_STYLES to PropValue.Text(states),
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF1B7A4E),
                    ),
                ) to Frame(20f, 20f, 200f, 48f),
            ),
        ) { views ->
            val button = views.getValue(2)
            val now = SystemClock.uptimeMillis()
            button.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
            assertEquals(1.1f * density, button.translationY, 0.01f)
            button.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, 10f, 10f, 0))
        }
    }

    @Test
    fun engineManagedSafeAreaNeverInsetsTwiceOrDescendants() {
        // Zé chat: root SafeAreaView (top edge on, bottom off) → stage → header.
        val top = 24f
        val nodes = listOf(
            NodeSpec(2, 1, 0, NodeKind.SAFE_AREA_VIEW, mapOf(PropKey.SAFE_AREA_BOTTOM_EDGE to PropValue.Flag(false))) to Frame(0f, 0f, 360f, 720f),
            NodeSpec(3, 2, 0, NodeKind.COLUMN, mapOf(PropKey.OVERFLOW to PropValue.Integer(2))) to Frame(0f, top, 360f, 720f - top),
            NodeSpec(4, 3, 0, NodeKind.ROW, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFF))) to Frame(0f, top, 360f, 52f),
            NodeSpec(5, 4, 0, NodeKind.PRESSABLE, mapOf(PropKey.ON_PRESS to PropValue.Flag(true))) to Frame(38f, top + 2f, 300f, 48f),
            NodeSpec(6, 5, 0, NodeKind.COLUMN, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF00FF00))) to Frame(74f, top + 4.3f, 200f, 43.4f),
        )
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        try {
            lateinit var renderer: PamRenderer
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                // Legacy first frame (native SafeAreaView padding), then the
                // engine takes over the insets: the order seen on slow starts.
                renderer.commit(
                    listOf(
                        buildList {
                            add(Mutation.Create(NodeSpec(1, 0, 0, NodeKind.SCREEN, emptyMap())))
                            nodes.forEach { (spec, _) -> add(Mutation.Create(spec)) }
                            add(Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)))
                            nodes.forEach { (spec, frame) ->
                                add(Mutation.Layout(spec.id, if (spec.id == 2L) frame else frame.copy(y = frame.y - top)))
                            }
                            add(Mutation.SetRoot(1))
                        },
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                renderer.engineManagedSafeArea = true
                renderer.onEngineSafeAreaChanged()
                renderer.commit(listOf(nodes.map { (spec, frame) -> Mutation.Layout(spec.id, frame) }))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val root = renderer.viewForNode(2)!!
                val header = renderer.viewForNode(4)!!
                val identity = renderer.viewForNode(5)!!
                fun screenTop(view: View) = IntArray(2).also(view::getLocationOnScreen)[1] - IntArray(2).also(root::getLocationOnScreen)[1]
                assertEquals(0, root.paddingTop)
                assertEquals(Math.round(top * density), screenTop(header))
                assertEquals(Math.round((top + 2f) * density), screenTop(identity))
                assertEquals(Math.round(48f * density), identity.height)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun render(nodes: List<Pair<NodeSpec, Frame>>, assertions: (Map<Long, View>) -> Unit) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        try {
            lateinit var renderer: PamRenderer
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(
                    listOf(
                        buildList {
                            add(Mutation.Create(NodeSpec(1, 0, 0, NodeKind.SCREEN, emptyMap())))
                            nodes.forEach { (spec, _) -> add(Mutation.Create(spec)) }
                            add(Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)))
                            nodes.forEach { (spec, frame) -> add(Mutation.Layout(spec.id, frame)) }
                            add(Mutation.SetRoot(1))
                        },
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertions(nodes.mapNotNull { (spec, _) -> renderer.viewForNode(spec.id)?.let { spec.id to it } }.toMap())
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
