package dev.pam.nativeapp

import android.content.Intent
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.render.PamRenderer
import dev.pam.nativeapp.render.PamScrollContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The runtime's surface hand-offs, with real PHP and the real engine:
 *
 * - a recreated Activity (PamRuntime.detach with keepViewState, then attach
 *   with a new renderer) opens with the ScrollView on the row the reader saw;
 * - `App::reload()` (`reload(fresh = true)`) relaunches like a cold start:
 *   the new request's tree is mounted from scratch, so a ScrollView whose node
 *   id the new request reuses starts at its top again;
 * - the development hot reload keeps the mounted views (Fast Refresh).
 *
 * The PHP entry is a stand-in for the SDK: it commits a fixed tree frame.
 * Run alone: embedded PHP is process-scoped.
 */
@RunWith(AndroidJUnit4::class)
class PamRuntimeRemountViewStateInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun recreationKeepsTheReadersRowAndAppReloadStartsFresh() {
        val directory = File(context.cacheDir, "pam-remount-view-state-test").apply {
            deleteRecursively()
            mkdirs()
        }
        File(directory, "tree.bin").writeBytes(treeFrame())
        val entry = File(directory, "entry.php").apply { writeText(ENTRY.replace('§', '$')) }
        val mounts = CountDownLatch(1)
        val reports = java.util.Collections.synchronizedList(ArrayList<String>())
        val report: (String) -> Unit = { message ->
            reports += message
            if (message.contains("MOUNTED")) mounts.countDown()
        }
        var first = startActivity()
        lateinit var runtime: PamRuntime
        lateinit var renderer: PamRenderer
        var second: PamTestActivity? = null
        try {
            main {
                renderer = PamRenderer(first, first.host) { _, _, _ -> }
                runtime = PamRuntime(first, renderer, report, installModules = false).also { it.installModules() }
                val density = first.resources.displayMetrics.density
                runtime.start(
                    entry,
                    widthDp = first.host.width / density,
                    heightDp = first.host.height / density,
                    textScale = 1f,
                    darkAppearance = false,
                )
            }
            assertTrue("PHP never mounted", mounts.await(30, TimeUnit.SECONDS))
            if (!waitUntil { row(renderer, ROW_BASE) != null }) {
                throw AssertionError("rows never mounted; reports=$reports scroll=${main { renderer.viewForNode(SCROLL_ID) }}")
            }

            // Scroll into the middle: row 30, 40 % of it above the top edge.
            val density = main { first.resources.displayMetrics.density }
            main { scroller(renderer).scrollBy(0, ((rowsTop(30) + rowHeight(30) * 0.4f) * density).toInt()) }
            waitFrames(6)
            val before = main { topRow(renderer) }
            assertTrue("scrolled to the middle ($before)", before.first >= ROW_BASE + 25)

            // Activity recreation: the surface goes, PHP stays, a new one attaches.
            main {
                runtime.detach(first, keepViewState = true)
                first.finish()
            }
            val recreated = startActivity()
            second = recreated
            first = recreated
            main {
                renderer = PamRenderer(recreated, recreated.host) { _, _, _ -> }
                runtime.attach(recreated, renderer, report)
            }
            awaitTrue("the retained tree remounted") { row(renderer, before.first)?.isShown == true }
            waitFrames(8)
            main {
                val after = topRow(renderer)
                assertEquals("the same row under the top edge after the recreation", before.first, after.first)
                assertTrue("at the same depth ($before, now $after)", abs(after.second - before.second) <= 0.05f)
            }

            // Hot reload (pam dev): views and their offsets stay.
            val mounted = main { renderer.viewForNode(SCROLL_ID) }
            main { runtime.reload(entry.absolutePath) }
            waitFrames(30)
            SystemClock.sleep(500)
            main {
                assertSame("hot reload keeps the mounted ScrollView", mounted, renderer.viewForNode(SCROLL_ID))
                assertEquals("hot reload keeps the row", before.first, topRow(renderer).first)
            }

            // App::reload(): a cold start, even though the new request
            // renders the same node ids.
            main { runtime.reload(entry.absolutePath, fresh = true) }
            awaitTrue("App::reload() mounted a new ScrollView") {
                val now = renderer.viewForNode(SCROLL_ID)
                now != null && now !== mounted && row(renderer, ROW_BASE)?.isShown == true
            }
            waitFrames(8)
            main {
                assertNotSame(mounted, renderer.viewForNode(SCROLL_ID))
                assertEquals("App::reload() starts at the top", 0, (renderer.viewForNode(SCROLL_ID) as PamScrollContainer).snapshotOffsetPixels().second)
            }
        } finally {
            main {
                runCatching { runtime.close() }
                first.finish()
                second?.finish()
            }
            directory.deleteRecursively()
        }
    }

    private fun startActivity(): PamTestActivity {
        val activity = instrumentation.startActivitySync(
            Intent(context, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        instrumentation.waitForIdleSync()
        awaitTrue("host laid out") { activity.host.width > 0 && activity.host.height > 0 }
        return activity
    }

    private fun scroller(renderer: PamRenderer): View = (renderer.viewForNode(SCROLL_ID) as PamScrollContainer).getChildAt(0)

    private fun row(renderer: PamRenderer, id: Long): View? = renderer.viewForNode(id)?.takeIf { it.isAttachedToWindow }

    /** The row under the ScrollView's top edge and the fraction of it above that edge (main thread). */
    private fun topRow(renderer: PamRenderer): Pair<Long, Float> {
        val scroller = scroller(renderer)
        val origin = IntArray(2).also(scroller::getLocationInWindow)[1] + scroller.paddingTop
        return (ROW_BASE until ROW_BASE + ROWS).firstNotNullOf { id ->
            val view = row(renderer, id) ?: return@firstNotNullOf null
            if (!view.isShown || view.height == 0) return@firstNotNullOf null
            val top = IntArray(2).also(view::getLocationInWindow)[1] - origin
            if (top <= 0 && top + view.height > 0) id to -top.toFloat() / view.height else null
        }
    }

    private fun rowHeight(index: Int): Float = 48f + (index % 5) * 14f

    private fun rowsTop(index: Int): Float = (0 until index).sumOf { rowHeight(it).toDouble() }.toFloat()

    /** A `PNT1` tree frame: Screen > ScrollView (flex 1) > content Column > rows of fixed heights. */
    private fun treeFrame(): ByteArray {
        val out = ByteArrayOutputStream()
        fun bytes(size: Int, fill: ByteBuffer.() -> Unit) {
            out.write(ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).apply(fill).array())
        }
        fun node(id: Long, parent: Long, index: Int, kind: NodeKind, floats: Map<PropKey, Double>, color: Long? = null) {
            bytes(8 + 8 + 4 + 1 + 2) {
                putLong(id)
                putLong(parent)
                putInt(index)
                put(kind.value.toByte())
                putShort((floats.size + if (color != null) 1 else 0).toShort())
            }
            floats.forEach { (key, value) ->
                bytes(2 + 1 + 8) {
                    putShort(key.value.toShort())
                    put(FLOAT_TAG)
                    putDouble(value)
                }
            }
            // A painted row is a real view (a layout-only one is flattened away).
            if (color != null) {
                bytes(2 + 1 + 8) {
                    putShort(PropKey.BACKGROUND_COLOR.value.toShort())
                    put(INTEGER_TAG)
                    putLong(color)
                }
            }
        }
        out.write("PNT1".toByteArray())
        bytes(2 + 8 + 4) {
            putShort(1)
            putLong(SCREEN_ID)
            putInt(3 + ROWS)
        }
        node(SCREEN_ID, 0, 0, NodeKind.SCREEN, emptyMap())
        node(SCROLL_ID, SCREEN_ID, 0, NodeKind.SCROLL, mapOf(PropKey.FLEX_GROW to 1.0))
        node(CONTENT_ID, SCROLL_ID, 0, NodeKind.COLUMN, emptyMap())
        repeat(ROWS) { index ->
            node(
                ROW_BASE + index,
                CONTENT_ID,
                index,
                NodeKind.VIEW,
                mapOf(PropKey.HEIGHT to rowHeight(index).toDouble()),
                color = if (index % 2 == 0) 0xFFEFEFEFL else 0xFFDADADAL,
            )
        }
        return out.toByteArray()
    }

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < until) {
            if (main(condition)) return true
            SystemClock.sleep(32)
        }
        return false
    }

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        if (!waitUntil(condition)) throw AssertionError("timed out: $description")
    }

    private fun waitFrames(count: Int) {
        repeat(count) {
            val latch = CountDownLatch(1)
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
            latch.await(1, TimeUnit.SECONDS)
        }
        instrumentation.waitForIdleSync()
    }

    private companion object {
        const val INTEGER_TAG: Byte = 2
        const val FLOAT_TAG: Byte = 3
        const val SCREEN_ID = 1L
        const val SCROLL_ID = 2L
        const val CONTENT_ID = 3L
        const val ROW_BASE = 100L
        const val ROWS = 80

        // A stand-in for the SDK's Runtime: each request commits the same
        // tree (so App::reload()'s new request reuses every node id).
        val ENTRY = """
            <?php
            namespace Pam\Native\Internal {
                final class Runtime {
                    public static function dispatchEvent(int §id, int §kind, string §payload): void {}
                    public static function dispatchModuleResult(int §id, int §status, string §payload): void {}
                    public static function shutdown(): void {}
                }
            }
            namespace {
                pam_native_commit(file_get_contents(__DIR__.'/tree.bin'));
                pam_native_error('MOUNTED');
            }
        """.trimIndent()
    }
}
