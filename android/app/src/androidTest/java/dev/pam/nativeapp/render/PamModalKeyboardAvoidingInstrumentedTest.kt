package dev.pam.nativeapp.render

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.BatchDecoder
import dev.pam.nativeapp.protocol.Mutation
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Zé Create text composer: a full-screen Modal (its own Dialog window) whose
 * `KeyboardAvoidingView` holds the stage and an absolute overlay with the
 * colour palette at its bottom. With the real IME of the device, the
 * palette must end up directly above the keyboard (React Native shrinks the
 * content the same way) and come back down when the keyboard hides. Frames
 * come from the real engine through the same surface keyboard feed the
 * runtime uses (PamRenderer.onSurfaceKeyboardInset), views and windows from
 * the real renderer and PamModalHost.
 */
@RunWith(AndroidJUnit4::class)
class PamModalKeyboardAvoidingInstrumentedTest {
    @get:Rule
    val animations = PamAnimationsEnabledRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private var renderer: PamRenderer? = null

    @Volatile
    private var lastGeometry = ""

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
    fun paddingKeyboardAvoidingViewInATranslucentModalKeepsThePaletteAboveTheIme() {
        runComposer(behavior = KEYBOARD_PADDING, translucent = true)
    }

    @Test
    fun paddingKeyboardAvoidingViewInAFittedModalKeepsThePaletteAboveTheIme() {
        runComposer(behavior = KEYBOARD_PADDING, translucent = false)
    }

    @Test
    fun resizeKeyboardAvoidingViewInAModalKeepsThePaletteAboveTheIme() {
        runComposer(behavior = KEYBOARD_RESIZE, translucent = true)
    }

    @Test
    fun panKeyboardAvoidingViewInAModalRidesAboveTheIme() {
        runComposer(behavior = KEYBOARD_PAN, translucent = true)
    }

    /**
     * An interactive BottomSheet lifts itself onto the IME (gorhom
     * `interactive`): its padding KeyboardAvoidingView must not avoid the
     * keyboard a second time, so the palette rests right on the keyboard.
     */
    @Test
    fun paddingKeyboardAvoidingViewInAnInteractiveBottomSheetRestsOnTheIme() {
        runComposer(behavior = KEYBOARD_PADDING, translucent = true, sheet = true)
    }

    private fun runComposer(behavior: Long, translucent: Boolean, sheet: Boolean = false) {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val engineInsets = Collections.synchronizedList(ArrayList<Float>())
        val lastFrames = HashMap<Long, dev.pam.nativeapp.protocol.Frame>()
        lateinit var tree: ByteArray
        var width = 0f
        var height = 0f
        lateinit var safe: FloatArray
        val policy = modalWindowSurfacePolicy(activity)
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val density = activity.resources.displayMetrics.density
            val insets = WindowInsetsCompat.toWindowInsetsCompat(decor.rootWindowInsets, decor)
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            safe = floatArrayOf(
                insets.left / density,
                insets.top / density,
                insets.right / density,
                insets.bottom / density,
            )
            width = activity.host.width / density
            height = activity.host.height / density
            // An edge-to-edge sheet's 50% snap point resolves below the status bar.
            tree = composerTree(behavior, translucent, sheet, sheetHeight = (height - safe[1]) * 0.5f)
            val batch = PamEngineLayoutProbe.nativeLayout(tree, width, height, safe, policy)
            assertNotNull("engine layout probe failed", batch)
            renderer = PamRenderer(activity, activity.host) { _, _, _ -> }.also { active ->
                active.engineManagedSafeArea = true
                // The runtime's path: surface IME -> engine relayout -> frames.
                active.onSurfaceKeyboardInset = { surface, inset ->
                    engineInsets += inset
                    val relayout = PamEngineLayoutProbe.nativeLayoutWithSurfaceKeyboard(
                        tree, width, height, safe, policy, surface, inset,
                    )
                    if (relayout != null) {
                        // Like the runtime's engine, publish only the frames that moved.
                        val frames = BatchDecoder.decode(ByteBuffer.wrap(relayout).asReadOnlyBuffer())
                            .filterIsInstance<Mutation.Layout>()
                            .filter { lastFrames[it.id] != it.frame }
                        frames.forEach { lastFrames[it.id] = it.frame }
                        if (frames.isNotEmpty()) active.commit(listOf(frames))
                    }
                }
                val initial = decode(batch!!)
                initial.filterIsInstance<Mutation.Layout>().forEach { lastFrames[it.id] = it.frame }
                active.commit(listOf(initial))
            }
        }
        val active = renderer!!
        lateinit var input: EditText
        lateinit var palette: View
        waitUntil("composer presented") {
            input = active.viewForNode(INPUT) as? EditText ?: return@waitUntil false
            palette = active.viewForNode(PALETTE) ?: return@waitUntil false
            input.isShown && input.hasWindowFocus() && palette.height > 0
        }
        // Let the entrance (sheet slide, modal fade) settle.
        Thread.sleep(700)
        val closedBottom = onMainValue { screenBottom(palette) }
        instrumentation.runOnMainSync {
            input.requestFocus()
            input.context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        waitUntil("palette directly above the IME", timeoutMs = 10_000) {
            val imeTop = imeTop(input) ?: return@waitUntil false
            val bottom = screenBottom(palette)
            lastGeometry = "imeTop=$imeTop palette=$bottom closed=$closedBottom " +
                "ty=${active.viewForNode(KAV)?.translationY} engine=${engineInsets.takeLast(3)}"
            input.hasFocus() && bottom <= imeTop + 2 && bottom >= imeTop - 2
        }
        Thread.sleep(500)
        instrumentation.runOnMainSync {
            val imeTop = requireNotNull(imeTop(input))
            assertTrue("settled above the IME: $lastGeometry", screenBottom(palette) <= imeTop + 2)
            assertTrue("input visible: $lastGeometry", screenBottom(input) <= imeTop + 2)
            assertEquals(
                "the covered activity window ignores the modal's IME",
                0,
                activity.host.imeBottomInset,
            )
        }
        if (behavior != KEYBOARD_PAN && !sheet) {
            // WindowInsetsAnimation frames reach the engine one by one: the
            // content moves with the keyboard instead of jumping at the end.
            val opening = engineInsets.filter { it > 0f }.distinct()
            assertTrue("animated surface insets: $engineInsets", opening.size >= 3)
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("palette back at the bottom once the IME hides") {
            lastGeometry = "palette=${screenBottom(palette)} closed=$closedBottom engine=${engineInsets.takeLast(3)}"
            imeTop(input) == null && kotlin.math.abs(screenBottom(palette) - closedBottom) <= 2
        }
    }

    /**
     * Screen > Modal(fullScreen or sheet) > View > KAV > View(flex 1) >
     * [stage(flex 1), overlay(absolute, space-between) > [top bar, input, palette]].
     */
    private fun composerTree(
        behavior: Long,
        translucent: Boolean,
        sheet: Boolean,
        sheetHeight: Float,
    ): ByteArray = encodeTree(
        listOf(
            TreeNode(1, 0, 0, KIND_SCREEN),
            TreeNode(
                MODAL, 1, 0, KIND_MODAL,
                buildMap {
                    put(KEY_MODAL_PRESENTATION, if (sheet) 3L else 1L)
                    if (translucent) {
                        put(KEY_MODAL_STATUS_BAR_TRANSLUCENT, true)
                        put(KEY_MODAL_NAVIGATION_BAR_TRANSLUCENT, true)
                    }
                },
            ),
            // A sheet's content is as tall as its (default 50%) snap point.
            TreeNode(
                11, MODAL, 0, KIND_VIEW,
                mapOf(
                    (if (sheet) KEY_HEIGHT else KEY_FLEX_GROW) to (if (sheet) sheetHeight.toDouble() else 1.0),
                    KEY_BACKGROUND_COLOR to 0xFF050608L,
                ),
            ),
            TreeNode(KAV, 11, 0, KIND_KAV, mapOf(KEY_FLEX_GROW to 1.0, KEY_KEYBOARD_BEHAVIOR to behavior)),
            TreeNode(13, KAV, 0, KIND_VIEW, mapOf(KEY_FLEX_GROW to 1.0)),
            TreeNode(14, 13, 0, KIND_VIEW, mapOf(KEY_FLEX_GROW to 1.0, KEY_BACKGROUND_COLOR to 0xFF111417L)),
            TreeNode(
                15, 13, 1, KIND_VIEW,
                mapOf(
                    KEY_POSITION_TYPE to 2L,
                    KEY_TOP to 0.0,
                    KEY_BOTTOM to 0.0,
                    KEY_LEFT to 0.0,
                    KEY_RIGHT to 0.0,
                    KEY_JUSTIFY_CONTENT to 4L,
                ),
            ),
            TreeNode(16, 15, 0, KIND_VIEW, mapOf(KEY_HEIGHT to 56.0, KEY_BACKGROUND_COLOR to 0xFF2244AAL)),
            TreeNode(INPUT, 15, 1, KIND_INPUT, mapOf(KEY_HEIGHT to 48.0, KEY_MULTILINE to true)),
            TreeNode(PALETTE, 15, 2, KIND_VIEW, mapOf(KEY_HEIGHT to 100.0, KEY_BACKGROUND_COLOR to 0xFFFF5F6DL)),
        ),
    )

    private fun decode(batch: ByteArray): List<Mutation> =
        BatchDecoder.decode(ByteBuffer.wrap(batch).asReadOnlyBuffer())

    /** IME top in screen coordinates as seen by the view's (dialog) window, or null when hidden. */
    private fun imeTop(view: View): Int? {
        val root = view.rootView
        val insets = root.rootWindowInsets ?: return null
        if (!insets.isVisible(WindowInsets.Type.ime())) return null
        val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
        if (ime <= 0) return null
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        return location[1] + root.height - ime
    }

    private fun screenBottom(view: View): Int {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return location[1] + view.height
    }

    private fun <T> onMainValue(block: () -> T): T {
        var value: T? = null
        instrumentation.runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitUntil(description: String, timeoutMs: Long = 8_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $description ($lastGeometry)")
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
        const val MODAL = 10L
        const val KAV = 12L
        const val INPUT = 17L
        const val PALETTE = 18L
        const val KEYBOARD_RESIZE = 1L
        const val KEYBOARD_PAN = 2L
        const val KEYBOARD_PADDING = 3L
        const val KIND_SCREEN = 1
        const val KIND_INPUT = 6
        const val KIND_VIEW = 11
        const val KIND_MODAL = 15
        const val KIND_KAV = 17
        const val KEY_HEIGHT = 6
        const val KEY_FLEX_GROW = 7
        const val KEY_BACKGROUND_COLOR = 10
        const val KEY_JUSTIFY_CONTENT = 41
        const val KEY_MULTILINE = 45
        const val KEY_MODAL_PRESENTATION = 58
        const val KEY_KEYBOARD_BEHAVIOR = 62
        const val KEY_POSITION_TYPE = 112
        const val KEY_LEFT = 113
        const val KEY_TOP = 114
        const val KEY_RIGHT = 115
        const val KEY_BOTTOM = 116
        const val KEY_MODAL_NAVIGATION_BAR_TRANSLUCENT = 256
        const val KEY_MODAL_STATUS_BAR_TRANSLUCENT = 257
    }
}
