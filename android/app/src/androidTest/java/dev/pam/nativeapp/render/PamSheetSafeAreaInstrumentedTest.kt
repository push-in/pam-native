package dev.pam.nativeapp.render

import android.app.Dialog
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.BatchDecoder
import dev.pam.nativeapp.protocol.Mutation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Safe area by default, on the real engine, renderer and windows, with
 * three-button navigation (48 dp bar) and gesture navigation (handle):
 *
 * - a bottom sheet without any SafeAreaView or bottom padding (a real-world app's
 *   "Anexar" sheet shape) ends its last card above the navigation bar, both
 *   sized to fit its content and with a fixed detent; its background still
 *   reaches the screen bottom;
 * - a scrolling sheet scrolled to its end shows its last row above the bar;
 * - a screen without SafeAreaView keeps its title below the status bar (and
 *   a display cutout), its button and its floating action button above the
 *   navigation bar;
 * - the debug inspector finds no view inside an inset.
 *
 * The navigation mode is switched with the system overlays
 * (`cmd overlay enable-exclusive --category`) and restored afterwards.
 */
@RunWith(AndroidJUnit4::class)
class PamSheetSafeAreaInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private var renderer: PamRenderer? = null
    private var originalNavigation: String? = null

    @Before
    fun rememberNavigationMode() {
        originalNavigation = shell("cmd overlay list --user current android")
            .lines()
            .firstOrNull { it.startsWith("[x]") && NAVIGATION_OVERLAY_PREFIX in it }
            ?.substringAfter("] ")
            ?.trim()
    }

    @After
    fun restore() {
        renderer?.let { active -> instrumentation.runOnMainSync { active.close() } }
        renderer = null
        if (::activity.isInitialized) instrumentation.runOnMainSync { activity.finish() }
        originalNavigation?.let { selectNavigation(it) }
        shell("cmd overlay disable --user current $CUTOUT_OVERLAY")
    }

    @Test
    fun fittedAttachmentSheetEndsAboveThreeButtonNavigation() =
        assertAttachmentSheet(THREE_BUTTON, dynamic = true)

    @Test
    fun fittedAttachmentSheetEndsAboveTheGestureHandle() =
        assertAttachmentSheet(GESTURAL, dynamic = true)

    @Test
    fun fixedDetentAttachmentSheetEndsAboveThreeButtonNavigation() =
        assertAttachmentSheet(THREE_BUTTON, dynamic = false)

    @Test
    fun fixedDetentAttachmentSheetEndsAboveTheGestureHandle() =
        assertAttachmentSheet(GESTURAL, dynamic = false)

    @Test
    fun scrollingSheetEndsItsLastRowAboveThreeButtonNavigation() = assertScrollingSheet(THREE_BUTTON)

    @Test
    fun scrollingSheetEndsItsLastRowAboveTheGestureHandle() = assertScrollingSheet(GESTURAL)

    @Test
    fun plainScreenClearsTheBarsWithThreeButtonNavigation() = assertPlainScreen(THREE_BUTTON, cutout = false)

    @Test
    fun plainScreenClearsTheBarsWithGestureNavigation() = assertPlainScreen(GESTURAL, cutout = false)

    @Test
    fun plainScreenClearsATallDisplayCutout() = assertPlainScreen(THREE_BUTTON, cutout = true)

    /**
     * The explicit full-bleed opt-out (a SafeAreaView with every edge off)
     * keeps the layout edge to edge, and the inspector reports the button
     * left behind the navigation bar.
     */
    @Test
    fun fullBleedOptOutKeepsTheLayoutAndTheInspectorReportsTheControl() {
        selectNavigation(THREE_BUTTON)
        launch()
        val geometry = windowGeometry()
        val nodes = listOf(
            Node(1, 0, 0, KIND_SCREEN),
            Node(
                SURFACE, 1, 0, KIND_SAFE_AREA_VIEW,
                mapOf(
                    KEY_FLEX_GROW to 1.0,
                    KEY_SAFE_AREA_TOP to false,
                    KEY_SAFE_AREA_RIGHT to false,
                    KEY_SAFE_AREA_BOTTOM_EDGE to false,
                    KEY_SAFE_AREA_LEFT to false,
                ),
            ),
            Node(51, SURFACE, 0, KIND_VIEW, mapOf(KEY_FLEX_GROW to 1.0, KEY_BACKGROUND_COLOR to 0xFF000000L)),
            Node(LAST_CARD, SURFACE, 1, KIND_TEXT, mapOf(KEY_TEXT to "Enviar", KEY_HEIGHT to 40.0)),
        )
        present(nodes, geometry)
        val active = renderer!!
        assertTrue("screen was not laid out", waitUntil { shown(active, LAST_CARD) })
        SystemClock.sleep(600)
        instrumentation.runOnMainSync {
            val button = active.viewForNode(LAST_CARD)!!
            assertNear("opt-out stays edge to edge", geometry.bottom, screenTop(button) + button.height)
            val violations = PamSafeAreaInspector.violations(activity.window)
            assertTrue("inspector must report the control: $violations", violations.any { it.view === button && it.edge == 3 })
        }
    }

    private fun assertAttachmentSheet(navigation: String, dynamic: Boolean) {
        selectNavigation(navigation)
        launch()
        val geometry = windowGeometry()
        val nodes = mutableListOf(
            Node(1, 0, 0, KIND_SCREEN),
            Node(
                SHEET, 1, 0, KIND_MODAL,
                mapOf(
                    KEY_MODAL_PRESENTATION to 3L,
                    KEY_SNAP_POINTS to snapPoints(if (dynamic) emptyList() else listOf(0.62)),
                ),
            ),
            // No SafeAreaView and no bottom padding anywhere.
            Node(SURFACE, SHEET, 0, KIND_COLUMN, mapOf(KEY_FLEX_GROW to 1.0, KEY_BACKGROUND_COLOR to 0xFFFFFFFFL)),
            Node(CONTENT, SURFACE, 0, KIND_COLUMN, mapOf(KEY_FLEX_GROW to 1.0, KEY_PADDING_TOP to 8.0)),
            Node(30, CONTENT, 0, KIND_TEXT, mapOf(KEY_TEXT to "Anexar", KEY_HEIGHT to 26.0)),
            Node(31, CONTENT, 1, KIND_VIEW, mapOf(KEY_HEIGHT to 98.0, KEY_BACKGROUND_COLOR to 0xFFE7E5DDL)),
            Node(32, CONTENT, 2, KIND_VIEW, mapOf(KEY_HEIGHT to 192.0)),
            Node(LAST_CARD, CONTENT, 3, KIND_TEXT, mapOf(KEY_TEXT to "Enviar em qualidade original", KEY_HEIGHT to 64.0)),
        )
        present(nodes, geometry)
        val active = renderer!!
        assertTrue("sheet window was not shown", waitUntil { shown(active, LAST_CARD) })
        SystemClock.sleep(600)
        instrumentation.runOnMainSync {
            val card = active.viewForNode(LAST_CARD)!!
            val surface = active.viewForNode(SURFACE)!!
            val cardBottom = screenTop(card) + card.height
            assertTrue(
                "$navigation: last card ends at $cardBottom px, navigation bar starts at ${geometry.navigationTop} px",
                cardBottom <= geometry.navigationTop + 1,
            )
            assertNear("$navigation: the sheet background reaches the screen bottom", geometry.bottom, screenTop(surface) + surface.height)
            if (dynamic) {
                val fitted = ((8 + 26 + 98 + 192 + 64) * geometry.density).toInt() + geometry.navigationHeight
                assertNear("$navigation: fitted sheet height", fitted, surface.height)
            }
            val window = dialogOf(active)!!.window!!
            val violations = PamSafeAreaInspector.violations(window)
            assertTrue("$navigation: inspector found $violations", violations.isEmpty())
        }
    }

    private fun assertScrollingSheet(navigation: String) {
        selectNavigation(navigation)
        launch()
        val geometry = windowGeometry()
        val nodes = mutableListOf(
            Node(1, 0, 0, KIND_SCREEN),
            Node(SHEET, 1, 0, KIND_MODAL, mapOf(KEY_MODAL_PRESENTATION to 3L, KEY_SNAP_POINTS to snapPoints(listOf(0.5)))),
            Node(SURFACE, SHEET, 0, KIND_COLUMN, mapOf(KEY_FLEX_GROW to 1.0, KEY_BACKGROUND_COLOR to 0xFFFFFFFFL)),
            Node(30, SURFACE, 0, KIND_TEXT, mapOf(KEY_TEXT to "Contatos", KEY_HEIGHT to 40.0)),
            Node(SCROLL, SURFACE, 1, KIND_SCROLL, mapOf(KEY_FLEX_GROW to 1.0, KEY_MIN_HEIGHT to 0.0)),
            Node(41, SCROLL, 0, KIND_COLUMN),
        )
        for (row in 0 until ROWS) {
            nodes += Node(100L + row, 41, row, KIND_TEXT, mapOf(KEY_TEXT to "Contato $row", KEY_HEIGHT to 48.0))
        }
        present(nodes, geometry)
        val active = renderer!!
        val last = 100L + ROWS - 1
        assertTrue("sheet window was not shown", waitUntil { shown(active, SCROLL) })
        instrumentation.runOnMainSync { (active.viewForNode(SCROLL) as PamScrollContainer).setContentOffsetY(100_000f) }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(600)
        instrumentation.runOnMainSync {
            val row = active.viewForNode(last)!!
            val rowBottom = screenTop(row) + row.height
            assertTrue(
                "$navigation: last row ends at $rowBottom px, navigation bar starts at ${geometry.navigationTop} px",
                rowBottom <= geometry.navigationTop + 1,
            )
            val violations = PamSafeAreaInspector.violations(dialogOf(active)!!.window!!)
            assertTrue("$navigation: inspector found $violations", violations.isEmpty())
        }
    }

    private fun assertPlainScreen(navigation: String, cutout: Boolean) {
        selectNavigation(navigation)
        if (cutout) {
            shell("cmd overlay enable --user current $CUTOUT_OVERLAY")
            SystemClock.sleep(2_000)
        }
        launch()
        val geometry = windowGeometry()
        if (cutout) assumeTrue("the emulator applies the tall cutout", geometry.cutoutTop > 0)
        val nodes = listOf(
            Node(1, 0, 0, KIND_SCREEN),
            Node(SURFACE, 1, 0, KIND_COLUMN, mapOf(KEY_FLEX_GROW to 1.0, KEY_BACKGROUND_COLOR to 0xFFF7F6F2L)),
            Node(TITLE, SURFACE, 0, KIND_TEXT, mapOf(KEY_TEXT to "Conversas", KEY_HEIGHT to 52.0)),
            Node(51, SURFACE, 1, KIND_VIEW, mapOf(KEY_FLEX_GROW to 1.0)),
            Node(LAST_CARD, SURFACE, 2, KIND_TEXT, mapOf(KEY_TEXT to "Enviar", KEY_HEIGHT to 56.0)),
            Node(
                FAB, SURFACE, 3, KIND_TEXT,
                mapOf(
                    KEY_TEXT to "+",
                    KEY_POSITION_TYPE to 2L,
                    KEY_RIGHT to 16.0,
                    KEY_BOTTOM to 16.0,
                    KEY_WIDTH to 56.0,
                    KEY_HEIGHT to 56.0,
                ),
            ),
        )
        present(nodes, geometry)
        val active = renderer!!
        assertTrue("screen was not laid out", waitUntil { shown(active, LAST_CARD) })
        SystemClock.sleep(600)
        instrumentation.runOnMainSync {
            val title = active.viewForNode(TITLE)!!
            val button = active.viewForNode(LAST_CARD)!!
            val fab = active.viewForNode(FAB)!!
            val surface = active.viewForNode(SURFACE)!!
            assertTrue("title below the status bar/cutout", screenTop(title) >= geometry.statusTop - 1)
            assertTrue(
                "$navigation: button ends at ${screenTop(button) + button.height}, bar at ${geometry.navigationTop}",
                screenTop(button) + button.height <= geometry.navigationTop + 1,
            )
            assertTrue("$navigation: FAB above the bar", screenTop(fab) + fab.height <= geometry.navigationTop + 1)
            assertNear("background still reaches the top", geometry.top, screenTop(surface))
            assertNear("background still reaches the bottom", geometry.bottom, screenTop(surface) + surface.height)
            val violations = PamSafeAreaInspector.violations(activity.window)
            assertTrue("$navigation: inspector found $violations", violations.isEmpty())
        }
    }

    private fun launch() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
    }

    private data class Geometry(
        val top: Int,
        val bottom: Int,
        val statusTop: Int,
        val navigationTop: Int,
        val navigationHeight: Int,
        val cutoutTop: Int,
        val density: Float,
        val insetsDp: FloatArray,
        val widthDp: Float,
        val heightDp: Float,
    )

    private fun windowGeometry(): Geometry {
        lateinit var geometry: Geometry
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val density = activity.resources.displayMetrics.density
            val insets = ViewCompat.getRootWindowInsets(decor)!!
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            assumeTrue("needs a status bar and a navigation bar", safe.top > 0 && safe.bottom > 0)
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            geometry = Geometry(
                top = origin[1],
                bottom = origin[1] + decor.height,
                statusTop = origin[1] + safe.top,
                navigationTop = origin[1] + decor.height - safe.bottom,
                navigationHeight = safe.bottom,
                cutoutTop = cutout.top,
                density = density,
                insetsDp = floatArrayOf(safe.left / density, safe.top / density, safe.right / density, safe.bottom / density),
                widthDp = decor.width / density,
                heightDp = decor.height / density,
            )
        }
        return geometry
    }

    private fun present(nodes: List<Node>, geometry: Geometry) {
        instrumentation.runOnMainSync {
            val batch = PamEngineLayoutProbe.nativeLayout(
                encodeTree(nodes),
                geometry.widthDp,
                geometry.heightDp,
                geometry.insetsDp,
                modalWindowSurfacePolicy(activity),
            )
            assertNotNull("engine layout probe failed", batch)
            val mutations = BatchDecoder.decode(ByteBuffer.wrap(batch!!).asReadOnlyBuffer())
            renderer = PamRenderer(activity, activity.host) { _, _, _ -> }.also {
                it.engineManagedSafeArea = true
                it.commit(listOf(mutations))
            }
        }
    }

    private fun shown(renderer: PamRenderer, id: Long): Boolean {
        var ready = false
        instrumentation.runOnMainSync {
            val view = renderer.viewForNode(id)
            ready = view != null && view.isAttachedToWindow && view.height > 0 && view.isShown
        }
        return ready
    }

    private fun dialogOf(renderer: PamRenderer): Dialog? {
        val host = renderer.viewForNode(SHEET) as? PamModalHost ?: return null
        val dialog = PamModalHost::class.java.getDeclaredField("dialog")
            .apply { isAccessible = true }
            .get(host) as? Dialog
        return dialog?.takeIf { it.isShowing }
    }

    /** Switches the system navigation mode and waits until the insets follow. */
    private fun selectNavigation(overlay: String) {
        val current = shell("cmd overlay list --user current android")
        val available = current.contains(overlay)
        assumeTrue("navigation overlay $overlay not available", available)
        shell("cmd overlay enable-exclusive --user current --category $overlay")
        SystemClock.sleep(2_500)
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().readText()
        }

    private fun waitUntil(timeoutMs: Long = 6_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(50)
        }
        return condition()
    }

    private fun screenTop(view: View): Int = IntArray(2).also(view::getLocationOnScreen)[1]

    private fun assertNear(message: String, expected: Int, actual: Int) {
        assertEquals(message, expected.toFloat(), actual.toFloat(), 3f)
    }

    private fun snapPoints(points: List<Double>): ByteArray {
        val buffer = ByteBuffer.allocate(2 + points.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(points.size.toShort())
        points.forEach(buffer::putDouble)
        return buffer.array()
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
        const val NAVIGATION_OVERLAY_PREFIX = "com.android.internal.systemui.navbar."
        const val THREE_BUTTON = "com.android.internal.systemui.navbar.threebutton"
        const val GESTURAL = "com.android.internal.systemui.navbar.gestural"
        const val CUTOUT_OVERLAY = "com.android.internal.display.cutout.emulation.tall"
        const val ROWS = 30
        const val SHEET = 10L
        const val SURFACE = 11L
        const val CONTENT = 12L
        const val SCROLL = 40L
        const val LAST_CARD = 33L
        const val TITLE = 50L
        const val FAB = 52L
        const val KIND_SCREEN = 1
        const val KIND_COLUMN = 2
        const val KIND_TEXT = 4
        const val KIND_SCROLL = 8
        const val KIND_VIEW = 11
        const val KIND_MODAL = 15
        const val KIND_SAFE_AREA_VIEW = 21
        const val KEY_SAFE_AREA_TOP = 150
        const val KEY_SAFE_AREA_RIGHT = 151
        const val KEY_SAFE_AREA_BOTTOM_EDGE = 152
        const val KEY_SAFE_AREA_LEFT = 153
        const val KEY_TEXT = 1
        const val KEY_WIDTH = 5
        const val KEY_HEIGHT = 6
        const val KEY_FLEX_GROW = 7
        const val KEY_BACKGROUND_COLOR = 10
        const val KEY_MIN_HEIGHT = 32
        const val KEY_MODAL_PRESENTATION = 58
        const val KEY_PADDING_TOP = 105
        const val KEY_POSITION_TYPE = 112
        const val KEY_RIGHT = 115
        const val KEY_BOTTOM = 116
        const val KEY_SNAP_POINTS = 315
    }
}
