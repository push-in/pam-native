package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.util.LongSparseArray
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PamVirtualListContinuityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun loadingHeaderAndPrependingPagesKeepVisibleRowsAndTheirDecodedImages() {
        val source = "data:image/png;base64," + Base64.encodeToString(png(), Base64.NO_WRAP)
        withList(source) { renderer, list ->
            awaitImage(renderer)
            val failures = mutableListOf<String>()
            val listener = watchDraws(list, failures)
            try {
                for (prefix in listOf(1, 5)) {
                    instrumentation.runOnMainSync {
                        val oldViews = views(renderer)
                        val visible = visibleRows(list).associateWith { requireNotNull(oldViews[CELL + it]) }
                        val image = oldViews[IMAGE] as PamImageView
                        val drawable = requireNotNull(image.drawable)
                        val mutations = mutableListOf<Mutation>()
                        if (prefix == 1) {
                            mutations += create(90, 2, 0, NodeKind.VIEW)
                            mutations += Mutation.Layout(90, Frame(0f, 0f, 320f, 24f))
                        } else {
                            mutations += Mutation.Remove(90)
                            repeat(prefix) { index ->
                                mutations += create(300L + index, 2, index, NodeKind.VIEW)
                                mutations += create(400L + index, 300L + index, 0, NodeKind.TEXT,
                                    mapOf(PropKey.TEXT to PropValue.Text("Older $index")))
                                mutations += Mutation.Layout(300L + index, Frame(0f, index * 64f, 320f, 64f))
                                mutations += Mutation.Layout(400L + index, Frame(8f, index * 64f + 8f, 180f, 40f))
                            }
                        }
                        val offset = if (prefix == 1) 24f else prefix * 64f
                        repeat(COUNT) { index ->
                            mutations += Mutation.Move(CELL + index, 2, index + prefix)
                            mutations += rowLayouts(index, offset)
                        }
                        renderer.commit(listOf(mutations))
                        visible.forEach { (index, old) ->
                            assertSame("keyed row $index must not be rebuilt", old, views(renderer)[CELL + index])
                        }
                        assertSame("decoded image view survives reindexing", image, views(renderer)[IMAGE])
                        assertSame("decoded drawable survives reindexing", drawable, image.drawable)
                    }
                    waitFrames(6)
                    instrumentation.runOnMainSync {
                        assertTrue("visible messages must remain", visibleRows(list).isNotEmpty())
                    }
                }
                assertEquals("every drawn frame keeps its visible rows", emptyList<String>(), failures)
            } finally {
                instrumentation.runOnMainSync { list.viewTreeObserver.removeOnDrawListener(listener) }
            }
        }
    }

    @Test
    fun anAsyncImageCompletionRelayoutKeepsEveryOtherVisibleRowMounted() {
        val server = DelayedImage(png())
        try {
            withList(server.url) { renderer, list ->
                assertTrue("native loader requested the image", server.requested.await(5, TimeUnit.SECONDS))
                val failures = mutableListOf<String>()
                val listener = watchDraws(list, failures)
                lateinit var original: Map<Long, View>
                instrumentation.runOnMainSync {
                    original = visibleRows(list).associate { CELL + it to requireNotNull(views(renderer)[CELL + it]) }
                    assertTrue(original.size >= 5)
                    assertEquals(null, (views(renderer)[IMAGE] as PamImageView).drawable)
                }
                try {
                    server.release.countDown()
                    awaitImage(renderer)
                    waitFrames(6)
                    instrumentation.runOnMainSync {
                        original.forEach { (id, view) -> assertSame("image completion must keep row $id", view, views(renderer)[id]) }
                        assertNotNull((views(renderer)[IMAGE] as PamImageView).drawable)
                    }
                    assertEquals(emptyList<String>(), failures)
                } finally {
                    instrumentation.runOnMainSync { list.viewTreeObserver.removeOnDrawListener(listener) }
                }
            }
        } finally { server.close() }
    }

    private fun withList(source: String, assertions: (PamRenderer, PamRecyclerList) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PamTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PamTestActivity
        lateinit var renderer: PamRenderer
        lateinit var list: PamRecyclerList
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                val mutations = mutableListOf<Mutation>(create(1, 0, 0, NodeKind.SCREEN), create(2, 1, 0, NodeKind.VIRTUAL_LIST),
                    Mutation.Layout(1, Frame(0f, 0f, 320f, 400f)), Mutation.Layout(2, Frame(0f, 0f, 320f, 400f)))
                repeat(COUNT) { index ->
                    mutations += create(CELL + index, 2, index, NodeKind.PRESSABLE,
                        mapOf(PropKey.ON_PRESS to PropValue.Flag(true)))
                    mutations += create(TEXT + index, CELL + index, 0, NodeKind.TEXT,
                        mapOf(PropKey.TEXT to PropValue.Text("Message $index")))
                    if (index == 2) mutations += create(IMAGE, CELL + index, 1, NodeKind.IMAGE, mapOf(
                        PropKey.SOURCE to PropValue.Text(source), PropKey.IMAGE_FADE_DURATION_MS to PropValue.Integer(0),
                    ))
                    mutations += rowLayouts(index, 0f)
                }
                renderer.commit(listOf(mutations + Mutation.SetRoot(1)))
                list = views(renderer)[2] as PamRecyclerList
            }
            waitFrames(6)
            assertions(renderer, list)
        } finally {
            instrumentation.runOnMainSync { renderer.close(); activity.finish() }
        }
    }

    private fun rowLayouts(index: Int, offset: Float): List<Mutation> = buildList {
        val y = offset + index * 64f
        add(Mutation.Layout(CELL + index, Frame(0f, y, 320f, 64f)))
        add(Mutation.Layout(TEXT + index, Frame(8f, y + 8f, 180f, 40f)))
        if (index == 2) add(Mutation.Layout(IMAGE, Frame(220f, y + 8f, 48f, 48f)))
    }

    private fun watchDraws(list: PamRecyclerList, failures: MutableList<String>): ViewTreeObserver.OnDrawListener {
        val listener = ViewTreeObserver.OnDrawListener {
            for (index in 0 until list.childCount) {
                val row = list.getChildAt(index) as ViewGroup
                if (row.bottom <= 0 || row.top >= list.height) continue
                val id = list.getChildViewHolder(row).itemId
                if (id in CELL until CELL + COUNT && !hasText(row)) failures += "empty visible row $id"
            }
        }
        instrumentation.runOnMainSync { list.viewTreeObserver.addOnDrawListener(listener) }
        return listener
    }

    private fun hasText(view: View): Boolean = when (view) {
        is TextView -> view.text.isNotEmpty()
        is ViewGroup -> (0 until view.childCount).any { hasText(view.getChildAt(it)) }
        else -> false
    }

    private fun visibleRows(list: PamRecyclerList): List<Int> = (0 until list.childCount).map(list::getChildAt)
        .filter { it.bottom > 0 && it.top < list.height }
        .map { list.getChildViewHolder(it).itemId }.filter { it in CELL until CELL + COUNT }.map { (it - CELL).toInt() }

    private fun awaitImage(renderer: PamRenderer) {
        repeat(120) {
            var loaded = false
            instrumentation.runOnMainSync { loaded = (views(renderer)[IMAGE] as? PamImageView)?.drawable != null }
            if (loaded) return
            waitFrames(1)
        }
        error("Image did not finish loading")
    }

    private fun waitFrames(count: Int) {
        repeat(count) {
            val latch = CountDownLatch(1)
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
            assertTrue("frame callback", latch.await(2, TimeUnit.SECONDS))
        }
    }

    private fun png(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(19, 13, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            .compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }

    private class DelayedImage(private val bytes: ByteArray) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val requested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val url = "http://127.0.0.1:${server.localPort}/image.png"
        private val worker = Thread {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* Read HTTP headers. */ }
                    requested.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { release.countDown(); server.close(); worker.join(1000) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun views(renderer: PamRenderer): LongSparseArray<View> = PamRenderer::class.java
        .getDeclaredField("views").apply { isAccessible = true }.get(renderer) as LongSparseArray<View>

    private fun create(id: Long, parent: Long, index: Int, kind: NodeKind, props: Map<PropKey, PropValue> = emptyMap()) =
        Mutation.Create(NodeSpec(id, parent, index, kind, props))

    private companion object { const val COUNT = 12; const val CELL = 100L; const val TEXT = 1000L; const val IMAGE = 2002L }
}
