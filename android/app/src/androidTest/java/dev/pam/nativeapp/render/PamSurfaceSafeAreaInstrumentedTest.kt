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
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Debug-only JNI hook (PAM_ENGINE_LAYOUT_PROBE): real engine, Android surface policy. */
object PamEngineLayoutProbe {
    init {
        System.loadLibrary("pam_native_android")
    }

    @JvmStatic
    external fun nativeLayout(
        tree: ByteArray,
        width: Float,
        height: Float,
        insets: FloatArray,
        surfacePolicy: Int,
    ): ByteArray?

    /** Same, with the IME inset (dp from the window bottom) of the Modal node [surface]. */
    @JvmStatic
    external fun nativeLayoutWithSurfaceKeyboard(
        tree: ByteArray,
        width: Float,
        height: Float,
        insets: FloatArray,
        surfacePolicy: Int,
        surface: Long,
        keyboard: Float,
    ): ByteArray?
}

/**
 * Safe areas are per presentation surface. The engine lays out a Modal or
 * BottomSheet with the insets of its own Dialog window: a window that fits
 * the system bars already starts below the status bar and ends above the
 * navigation bar, so its SafeAreaView adds nothing; a translucent
 * (edge-to-edge) window extends under the bars and its SafeAreaView pads by
 * the real insets. Either way the status bar and the navigation bar are
 * cleared exactly once. Frames come from the real engine, views and windows
 * from the real renderer and PamModalHost.
 */
@RunWith(AndroidJUnit4::class)
class PamSurfaceSafeAreaInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private var renderer: PamRenderer? = null

    @Before
    fun launch() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        instrumentation.waitForIdleSync()
    }

    @After
    fun finish() {
        instrumentation.runOnMainSync {
            renderer?.close()
            activity.finish()
        }
    }

    @Test
    fun nonTranslucentModalClearsTheStatusAndNavigationBarsOnce() {
        val result = present(translucentStatus = false, translucentNavigation = false)
        // Whatever the window does, the bars are cleared exactly once.
        assertNear("header below the status bar once", result.window.statusTop, result.headerTop)
        assertNear("footer above the navigation bar once", result.window.navigationTop, result.footerBottom)
        if (result.policy == SURFACE_POLICY_SYSTEM_WINDOWS) {
            // Android 14 and older: the Dialog fits the system bars, so its
            // SafeAreaView adds no inset of its own.
            assertNear("fitted window top", result.window.statusTop, result.contentTop)
            assertNear("fitted window bottom", result.window.navigationTop, result.contentBottom)
            assertEquals(0f, result.safeAreaPaddingTop, 0.01f)
            assertEquals(0f, result.safeAreaPaddingBottom, 0.01f)
        } else {
            // Android 15+ enforced edge-to-edge: the Dialog extends under the
            // bars, so its SafeAreaView pads the real insets.
            assertNear("edge-to-edge window top", result.window.top, result.contentTop)
            assertNear("edge-to-edge window bottom", result.window.bottom, result.contentBottom)
            assertEquals(result.window.statusHeightDp, result.safeAreaPaddingTop, 0.6f)
            assertEquals(result.window.navigationHeightDp, result.safeAreaPaddingBottom, 0.6f)
        }
    }

    @Test
    fun statusBarTranslucentModalPadsTheRealInsets() {
        val result = present(translucentStatus = true, translucentNavigation = false)
        assertEdgeToEdgeModal(result)
    }

    @Test
    fun navigationBarTranslucentModalPadsTheRealInsets() {
        val result = present(translucentStatus = false, translucentNavigation = true)
        assertEdgeToEdgeModal(result)
    }

    /**
     * A BottomSheet window is always edge to edge (1.34), like the @gorhom
     * sheet in React Native's edge-to-edge root: its backdrop covers the
     * navigation bar, the sheet reaches the screen bottom and a SafeAreaView
     * inside it pads the navigation bar once, whatever the surface policy.
     */
    @Test
    fun nonTranslucentBottomSheetReachesTheScreenBottomAndPadsTheNavigationBar() {
        val result = present(translucentStatus = false, translucentNavigation = false, sheet = true)
        assertEquals(0f, result.safeAreaPaddingTop, 0.01f)
        assertNear("sheet header", result.sheetTop, result.headerTop)
        assertNear("edge-to-edge sheet window bottom", result.window.bottom, result.contentBottom)
        assertEquals(result.window.navigationHeightDp, result.safeAreaPaddingBottom, 0.6f)
    }

    @Test
    fun navigationBarTranslucentBottomSheetPadsOnlyTheNavigationBar() {
        val result = present(translucentStatus = false, translucentNavigation = true, sheet = true)
        assertEquals(0f, result.safeAreaPaddingTop, 0.01f)
        assertNear("sheet header", result.sheetTop, result.headerTop)
        assertNear("edge-to-edge sheet window bottom", result.window.bottom, result.contentBottom)
        assertEquals(result.window.navigationHeightDp, result.safeAreaPaddingBottom, 0.6f)
    }

    @Test
    fun hostSurfacePolicyMatchesTheRealDialogWindow() {
        val sdk = android.os.Build.VERSION.SDK_INT
        val target = instrumentation.targetContext.applicationInfo.targetSdkVersion
        val expected = if (sdk >= 35 && target >= 35) {
            SURFACE_POLICY_EDGE_TO_EDGE_WINDOWS
        } else {
            SURFACE_POLICY_SYSTEM_WINDOWS
        }
        assertEquals(expected, modalWindowSurfacePolicy(instrumentation.targetContext))
    }

    private fun assertEdgeToEdgeModal(result: Presented) {
        assertNear("edge-to-edge window top", result.window.top, result.contentTop)
        assertNear("edge-to-edge window bottom", result.window.bottom, result.contentBottom)
        assertNear("header below the status bar once", result.window.statusTop, result.headerTop)
        assertNear("footer above the navigation bar once", result.window.navigationTop, result.footerBottom)
        assertEquals(result.window.statusHeightDp, result.safeAreaPaddingTop, 0.6f)
        assertEquals(result.window.navigationHeightDp, result.safeAreaPaddingBottom, 0.6f)
    }

    private data class WindowGeometry(
        val top: Int,
        val bottom: Int,
        val statusTop: Int,
        val navigationTop: Int,
        val statusHeightDp: Float,
        val navigationHeightDp: Float,
    )

    private data class Presented(
        val policy: Int,
        val window: WindowGeometry,
        val contentTop: Int,
        val contentBottom: Int,
        val sheetTop: Int,
        val headerTop: Int,
        val footerBottom: Int,
        val safeAreaPaddingTop: Float,
        val safeAreaPaddingBottom: Float,
    )

    private fun present(
        translucentStatus: Boolean,
        translucentNavigation: Boolean,
        sheet: Boolean = false,
    ): Presented {
        lateinit var geometry: WindowGeometry
        lateinit var mutations: List<Mutation>
        val policy = modalWindowSurfacePolicy(activity)
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val density = activity.resources.displayMetrics.density
            val insets = WindowInsetsCompat.toWindowInsetsCompat(decor.rootWindowInsets, decor)
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            assumeTrue("needs a status bar and a navigation bar", safe.top > 0 && safe.bottom > 0)
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            geometry = WindowGeometry(
                top = origin[1],
                bottom = origin[1] + decor.height,
                statusTop = origin[1] + safe.top,
                navigationTop = origin[1] + decor.height - safe.bottom,
                statusHeightDp = safe.top / density,
                navigationHeightDp = safe.bottom / density,
            )
            val tree = encodeTree(
                listOf(
                    TreeNode(1, 0, 0, KIND_SCREEN),
                    TreeNode(
                        10, 1, 0, KIND_MODAL,
                        buildMap {
                            put(KEY_MODAL_PRESENTATION, if (sheet) 3L else 1L)
                            if (translucentStatus) put(KEY_MODAL_STATUS_BAR_TRANSLUCENT, true)
                            if (translucentNavigation) put(KEY_MODAL_NAVIGATION_BAR_TRANSLUCENT, true)
                        },
                    ),
                    TreeNode(11, 10, 0, KIND_SAFE_AREA_VIEW, mapOf(KEY_FLEX_GROW to 1.0, KEY_BACKGROUND_COLOR to 0xFFFFFFFFL)),
                    TreeNode(HEADER, 11, 0, KIND_VIEW, mapOf(KEY_HEIGHT to 56.0, KEY_BACKGROUND_COLOR to 0xFF2244AAL)),
                    TreeNode(13, 11, 1, KIND_VIEW, mapOf(KEY_FLEX_GROW to 1.0)),
                    TreeNode(FOOTER, 11, 2, KIND_VIEW, mapOf(KEY_HEIGHT to 50.0, KEY_BACKGROUND_COLOR to 0xFFAA4422L)),
                ),
            )
            val batch = PamEngineLayoutProbe.nativeLayout(
                tree,
                decor.width / density,
                decor.height / density,
                floatArrayOf(safe.left / density, safe.top / density, safe.right / density, safe.bottom / density),
                policy,
            )
            assertNotNull("engine layout probe failed", batch)
            mutations = BatchDecoder.decode(ByteBuffer.wrap(batch!!).asReadOnlyBuffer())
            renderer = PamRenderer(activity, activity.host) { _, _, _ -> }.also {
                it.engineManagedSafeArea = true
                it.commit(listOf(mutations))
            }
        }
        val frames = mutations.filterIsInstance<Mutation.Layout>().associate { it.id to it.frame }
        val safeArea = frames.getValue(11)
        val header = frames.getValue(HEADER)
        val footer = frames.getValue(FOOTER)
        val active = renderer!!
        assertTrue("modal window was not shown", waitUntil { presentedOnScreen(active) })
        instrumentation.waitForIdleSync()
        lateinit var presented: Presented
        instrumentation.runOnMainSync {
            val content = dialogOf(active)!!.window!!.decorView
                .findViewById<View>(android.R.id.content)
            val sheetView = active.viewForNode(11)!!
            val headerView = active.viewForNode(HEADER)!!
            val footerView = active.viewForNode(FOOTER)!!
            presented = Presented(
                policy = policy,
                window = geometry,
                contentTop = screenTop(content),
                contentBottom = screenTop(content) + content.height,
                sheetTop = screenTop(sheetView),
                                headerTop = screenTop(headerView),
                footerBottom = screenTop(footerView) + footerView.height,
                safeAreaPaddingTop = header.y - safeArea.y,
                safeAreaPaddingBottom = (safeArea.y + safeArea.height) - (footer.y + footer.height),
            )
        }
        return presented
    }

    private fun presentedOnScreen(renderer: PamRenderer): Boolean {
        var ready = false
        instrumentation.runOnMainSync {
            val header = renderer.viewForNode(HEADER)
            ready = dialogOf(renderer) != null &&
                header != null && header.isAttachedToWindow && header.height > 0
        }
        return ready
    }

    /** The showing Dialog window of the Modal node (main thread). */
    private fun dialogOf(renderer: PamRenderer): Dialog? {
        val host = renderer.viewForNode(10) as? PamModalHost ?: return null
        val dialog = PamModalHost::class.java.getDeclaredField("dialog")
            .apply { isAccessible = true }
            .get(host) as? Dialog
        return dialog?.takeIf { it.isShowing }
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            SystemClock.sleep(50)
        }
        return condition()
    }

    private fun screenTop(view: View): Int = IntArray(2).also(view::getLocationOnScreen)[1]

    private fun assertNear(message: String, expected: Int, actual: Int) {
        assertTrue("$message: expected $expected px, was $actual px", abs(expected - actual) <= 2)
    }

    private data class TreeNode(
        val id: Long,
        val parent: Long,
        val index: Int,
        val kind: Int,
        val properties: Map<Int, Any> = emptyMap(),
    )

    /** PNT1 tree frame (see pam-native-protocol `Tree::encode`). */
    private fun encodeTree(nodes: List<TreeNode>): ByteArray {
        val buffer = ByteBuffer.allocate(16 * 1024).order(ByteOrder.LITTLE_ENDIAN)
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
                    is Long -> buffer.put(2).putLong(value)
                    is Double -> buffer.put(3).putDouble(value)
                    is Boolean -> buffer.put(4).put(if (value) 1 else 0)
                    else -> error("unsupported property value $value")
                }
            }
        }
        return buffer.array().copyOf(buffer.position())
    }

    private companion object {
        const val HEADER = 12L
        const val FOOTER = 14L
        const val KIND_SCREEN = 1
        const val KIND_VIEW = 11
        const val KIND_MODAL = 15
        const val KIND_SAFE_AREA_VIEW = 21
        const val KEY_HEIGHT = 6
        const val KEY_FLEX_GROW = 7
        const val KEY_BACKGROUND_COLOR = 10
        const val KEY_MODAL_PRESENTATION = 58
        const val KEY_MODAL_NAVIGATION_BAR_TRANSLUCENT = 256
        const val KEY_MODAL_STATUS_BAR_TRANSLUCENT = 257
    }
}
