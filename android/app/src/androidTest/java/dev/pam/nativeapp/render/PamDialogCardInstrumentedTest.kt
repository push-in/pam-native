package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.BatchDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A centred dialog card (Modal presentation 2) keeps the engine's width: the
 * PAM Native UI confirm dialog came out narrower than its 320 dp card, with
 * "Cancel" and "Delete" ellipsized to "C..." and "...".
 */
@RunWith(AndroidJUnit4::class)
class PamDialogCardInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private var renderer: PamRenderer? = null

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { renderer?.close() }
        if (::activity.isInitialized) activity.finish()
    }

    @Test
    fun aDialogCardKeepsItsEngineWidthAndItsActionsAreNotEllipsized() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        instrumentation.waitForIdleSync()
        var widthDp = 0f
        var heightDp = 0f
        var density = 1f
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            density = activity.resources.displayMetrics.density
            widthDp = decor.width / density
            heightDp = decor.height / density
        }
        val nodes = listOf(
            Node(1, 0, 0, KIND_SCREEN),
            Node(MODAL, 1, 0, KIND_MODAL, mapOf(KEY_MODAL_PRESENTATION to 2L)),
            Node(CARD, MODAL, 0, KIND_COLUMN, mapOf(KEY_WIDTH to 320.0, KEY_MAX_WIDTH_PERCENT to 90.0, KEY_PADDING to 24.0, KEY_GAP to 12.0, KEY_BACKGROUND_COLOR to 0xFFFFFFFFL)),
            Node(20, CARD, 0, KIND_TEXT, mapOf(KEY_TEXT to "Delete project?", KEY_FONT_SIZE to 22.0)),
            Node(21, CARD, 1, KIND_TEXT, mapOf(KEY_TEXT to "This removes the project and its history. It cannot be undone.", KEY_FONT_SIZE to 15.0)),
            Node(ACTIONS, CARD, 2, KIND_ROW, mapOf(KEY_JUSTIFY_CONTENT to 3L, KEY_GAP to 8.0)),
            Node(CANCEL, ACTIONS, 0, KIND_TEXT, mapOf(KEY_TEXT to "Cancel", KEY_FONT_SIZE to 15.0, KEY_PADDING_HORIZONTAL to 16.0)),
            Node(DELETE, ACTIONS, 1, KIND_TEXT, mapOf(KEY_TEXT to "Delete", KEY_FONT_SIZE to 15.0, KEY_PADDING_HORIZONTAL to 16.0)),
        )
        instrumentation.runOnMainSync {
            val batch = PamEngineLayoutProbe.nativeLayout(
                encodeTree(nodes), widthDp, heightDp, floatArrayOf(0f, 0f, 0f, 0f), modalWindowSurfacePolicy(activity),
            )
            assertNotNull("engine layout probe failed", batch)
            val mutations = BatchDecoder.decode(ByteBuffer.wrap(batch!!).asReadOnlyBuffer())
            renderer = PamRenderer(activity, activity.host) { _, _, _ -> }.also {
                it.engineManagedSafeArea = true
                it.commit(listOf(mutations))
            }
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val view = renderer?.viewForNode(DELETE)
                ready = view != null && view.isAttachedToWindow && view.width > 0 && view.isShown
            }
            if (!ready) SystemClock.sleep(50)
        }
        assertTrue("dialog was not shown", ready)
        SystemClock.sleep(500)
        instrumentation.runOnMainSync {
            val card = renderer!!.viewForNode(CARD)!!
            val expected = (minOf(320f, widthDp * 0.9f) * density)
            assertEquals("card width (px)", expected, card.width.toFloat(), 1.5f)
            for (id in listOf(CANCEL, DELETE)) {
                val label = renderer!!.viewForNode(id) as TextView
                val layout = label.layout
                assertNotNull(layout)
                assertEquals("${label.text} is ellipsized", 0, layout!!.getEllipsisCount(0))
                val location = IntArray(2).also(label::getLocationOnScreen)
                val cardLocation = IntArray(2).also(card::getLocationOnScreen)
                assertTrue(
                    "${label.text} ends at ${location[0] + label.width}, outside the card (${cardLocation[0] + card.width})",
                    location[0] + label.width <= cardLocation[0] + card.width,
                )
            }
        }
    }

    private data class Node(
        val id: Long,
        val parent: Long,
        val index: Int,
        val kind: Int,
        val properties: Map<Int, Any> = emptyMap(),
    )

    /** PNT1 tree frame (see pam-native-protocol `Tree::encode`). */
    private fun encodeTree(nodes: List<Node>): ByteArray {
        val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("PNT1".toByteArray(Charsets.US_ASCII))
        buffer.putShort(1)
        buffer.putLong(nodes.first { it.parent == 0L }.id)
        buffer.putInt(nodes.size)
        for (node in nodes) {
            buffer.putLong(node.id)
            buffer.putLong(node.parent)
            buffer.putInt(node.index)
            buffer.put(node.kind.toByte())
            buffer.putShort(node.properties.size.toShort())
            for ((key, value) in node.properties) {
                buffer.putShort(key.toShort())
                when (value) {
                    is String -> value.toByteArray(Charsets.UTF_8).let { buffer.put(1).putInt(it.size).put(it) }
                    is Long -> buffer.put(2).putLong(value)
                    is Double -> buffer.put(3).putDouble(value)
                    is Boolean -> buffer.put(4).put(if (value) 1 else 0)
                    is ByteArray -> buffer.put(5).putInt(value.size).put(value)
                    else -> error("unsupported property value $value")
                }
            }
        }
        return buffer.array().copyOf(buffer.position())
    }

    private companion object {
        const val MODAL = 10L
        const val CARD = 11L
        const val ACTIONS = 12L
        const val CANCEL = 13L
        const val DELETE = 14L
        const val KIND_SCREEN = 1
        const val KIND_COLUMN = 2
        const val KIND_ROW = 3
        const val KIND_TEXT = 4
        const val KIND_MODAL = 15
        const val KEY_TEXT = 1
        const val KEY_WIDTH = 5
        const val KEY_PADDING = 8
        const val KEY_GAP = 9
        const val KEY_BACKGROUND_COLOR = 10
        const val KEY_FONT_SIZE = 12
        const val KEY_PADDING_HORIZONTAL = 29
        const val KEY_JUSTIFY_CONTENT = 41
        const val KEY_MODAL_PRESENTATION = 58
        const val KEY_MAX_WIDTH_PERCENT = 131
    }
}
