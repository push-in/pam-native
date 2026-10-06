package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections

/**
 * Zé chat "Editar mensagem": a BottomSheet with `keyboardBehavior="interactive"`
 * over a screen whose composer is a panning KeyboardAvoidingView. The sheet's
 * dialog window owns the IME: its field sits above the keyboard (gorhom
 * interactive) while the covered base window and its composer stay put.
 */
@RunWith(AndroidJUnit4::class)
class PamSheetKeyboardInstrumentedTest {
    @Volatile
    private var lastGeometry = ""

    @Test
    fun focusedSheetInputSitsAboveTheImeAndTheBaseComposerStaysPut() {
        runSheetScenario(autoFocus = false)
    }

    @Test
    fun autoFocusedSheetInputOpensTheKeyboardOnceTheSheetIsPresented() {
        runSheetScenario(autoFocus = true)
    }

    /**
     * Zé's edit sheet: a header button precedes the field and the field is
     * remounted (a new key) with `autoFocus` on every open, both when the
     * sheet is re-presented and inside an already-presented sheet. The
     * keyboard must open each time without a tap.
     */
    @Test
    fun remountedAutoFocusInputOpensTheKeyboardOnEveryOpen() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var renderer: PamRenderer
        var width = 0f
        fun input(id: Long, name: String) = listOf(
            Mutation.Create(node(id, 7, NodeKind.INPUT, mapOf(
                PropKey.TEST_ID to PropValue.Text(name),
                PropKey.AUTO_FOCUS to PropValue.Flag(true),
            ), index = 1)),
            Mutation.Layout(id, Frame(16f, 64f, width - 32f, 48f)),
        )
        fun awaitKeyboard(name: String) {
            waitUntil(instrumentation, "keyboard open for $name", timeoutMs = 10_000) {
                val content = presentedModalContent(renderer) ?: return@waitUntil false
                val field = content.findByTransitionName(name) as? EditText ?: return@waitUntil false
                lastGeometry = "focus=${field.hasFocus()} windowFocus=${field.hasWindowFocus()} " +
                    "focused=${field.rootView.findFocus()} ime=${dialogImeTop(field)}"
                field.hasFocus() && dialogImeTop(field) != null &&
                    screenBottom(field) <= requireNotNull(dialogImeTop(field)) + 1
            }
        }
        try {
            onMain(instrumentation) {
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                val density = activity.host.resources.displayMetrics.density
                width = activity.host.width / density
                val height = activity.host.height / density
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(6, 1, NodeKind.MODAL, mapOf(
                        PropKey.VISIBLE to PropValue.Flag(true),
                        PropKey.MODAL_PRESENTATION to PropValue.Integer(3),
                        PropKey.BOTTOM_SHEET_KEYBOARD_BEHAVIOR to PropValue.Integer(1),
                        PropKey.ON_MODAL_REQUEST_CLOSE to PropValue.Flag(true),
                    ))),
                    Mutation.Create(node(7, 6, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFFL),
                    ))),
                    // "Cancelar" header button: focusable and first in order.
                    Mutation.Create(node(10, 7, NodeKind.BUTTON)),
                    Mutation.Layout(1, Frame(0f, 0f, width, height)),
                    Mutation.Layout(6, Frame(0f, 0f, width, height)),
                    Mutation.Layout(7, Frame(0f, 0f, width, 200f)),
                    Mutation.Layout(10, Frame(16f, 8f, 120f, 48f)),
                ) + input(20, "edit-1") + listOf(Mutation.SetRoot(1))))
            }
            awaitKeyboard("edit-1")

            // Close, then reopen with a remounted field in the same commit.
            onMain(instrumentation) {
                renderer.commit(listOf(listOf(Mutation.Update(6, PropKey.VISIBLE, PropValue.Flag(false)))))
            }
            waitUntil(instrumentation, "sheet dismissed") { presentedModalContent(renderer) == null }
            Thread.sleep(300)
            onMain(instrumentation) {
                renderer.commit(listOf(
                    listOf(Mutation.Remove(20)) + input(21, "edit-2") +
                        Mutation.Update(6, PropKey.VISIBLE, PropValue.Flag(true)),
                ))
            }
            awaitKeyboard("edit-2")

            // Remount inside the already-presented sheet (keyboard hidden first,
            // like a user would, a moment after it opened).
            Thread.sleep(600)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil(instrumentation, "IME hidden") {
                presentedModalContent(renderer)?.let { dialogImeTop(it) } == null
            }
            onMain(instrumentation) {
                renderer.commit(listOf(listOf(Mutation.Remove(21)) + input(22, "edit-3")))
            }
            awaitKeyboard("edit-3")
            onMain(instrumentation) { renderer.close() }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    /**
     * Zé's OptionDialog: `<Modal transparent>` whose content is a short
     * bottom-anchored Column. The window stays translucent (only the backdrop
     * colour over the screen below) and the Column keeps its authored frame
     * instead of being stretched into an opaque full-screen window.
     */
    @Test
    fun transparentModalKeepsItsBottomAnchoredContentOverATranslucentBackdrop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var renderer: PamRenderer
        try {
            onMain(instrumentation) {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                val density = activity.host.resources.displayMetrics.density
                val width = activity.host.width / density
                val height = activity.host.height / density
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFF0000L),
                    ))),
                    Mutation.Create(node(6, 1, NodeKind.MODAL, mapOf(
                        PropKey.VISIBLE to PropValue.Flag(true),
                        PropKey.MODAL_PRESENTATION to PropValue.Integer(1),
                        PropKey.MODAL_TRANSPARENT to PropValue.Flag(true),
                        PropKey.MODAL_BACKDROP_COLOR to PropValue.Integer(0x66000000L),
                        PropKey.MODAL_ANIMATION_TYPE to PropValue.Integer(1),
                    ))),
                    // <Column heightPercent="100" justifyContent="end"> (flattened)
                    Mutation.Create(node(7, 6, NodeKind.COLUMN)),
                    Mutation.Create(node(8, 7, NodeKind.PRESSABLE, mapOf(
                        PropKey.ON_PRESS to PropValue.Flag(true),
                    ))),
                    Mutation.Create(node(9, 7, NodeKind.COLUMN, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFFL),
                        PropKey.TEST_ID to PropValue.Text("option-dialog"),
                    ), index = 1)),
                    Mutation.Layout(1, Frame(0f, 0f, width, height)),
                    Mutation.Layout(6, Frame(0f, 0f, width, height)),
                    Mutation.Layout(7, Frame(0f, 0f, width, height)),
                    Mutation.Layout(8, Frame(0f, 0f, width, height * 0.56f)),
                    Mutation.Layout(9, Frame(0f, height * 0.56f, width, height * 0.44f)),
                    Mutation.SetRoot(1),
                )))
            }
            lateinit var sheet: View
            waitUntil(instrumentation, "transparent modal presented") {
                val content = presentedModalContent(renderer)
                lastGeometry = "content=$content"
                if (content == null) return@waitUntil false
                val found = content.findByTransitionName("option-dialog")
                lastGeometry = "found=$found shown=${found?.isShown} h=${found?.height}"
                sheet = found ?: return@waitUntil false
                sheet.isShown && sheet.height > 0
            }
            Thread.sleep(600)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            var sheetTop = 0
            var sheetBottom = 0
            var screenHeight = 0
            onMain(instrumentation) {
                val location = IntArray(2)
                sheet.getLocationOnScreen(location)
                sheetTop = location[1]
                sheetBottom = location[1] + sheet.height
                screenHeight = activity.host.height
            }
            val x = screenshot.width / 2
            val above = screenshot.getPixel(x, sheetTop / 2)
            val inside = screenshot.getPixel(x, (sheetTop + sheetBottom) / 2)
            val geometry = "sheet=$sheetTop..$sheetBottom host=$screenHeight " +
                "above=${Integer.toHexString(above)} inside=${Integer.toHexString(inside)}"
            assertTrue("the Column keeps its 44% height ($geometry)", sheetTop > screenHeight / 2)
            assertTrue("the Column stays on screen ($geometry)", sheetBottom <= screenshot.height)
            // Red screen under a 40% black backdrop: about (153, 0, 0).
            assertTrue(
                "the area above shows the screen through the backdrop ($geometry)",
                android.graphics.Color.red(above) in 120..185 &&
                    android.graphics.Color.green(above) < 30 &&
                    android.graphics.Color.blue(above) < 30,
            )
            assertEquals("the Column is drawn white ($geometry)", 0xFFFFFFFF.toInt(), inside)
            onMain(instrumentation) { renderer.close() }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    /**
     * Zé's real flow: the message-actions overlay (a transparent full-screen
     * Modal, recreated on every open, so its window is newer than the kept
     * edit sheet's) stays up 150 ms while "Editar" presents the kept sheet
     * and remounts its multiline, natively-synced input with `autoFocus`.
     * The sheet window only gains focus once the overlay is removed.
     */
    @Test
    fun autoFocusInAKeptSheetPresentedUnderAClosingOverlayOpensTheKeyboard() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var renderer: PamRenderer
        var width = 0f
        var height = 0f
        fun overlay(id: Long) = listOf(
            Mutation.Create(node(id, 1, NodeKind.MODAL, mapOf(
                PropKey.VISIBLE to PropValue.Flag(true),
                PropKey.MODAL_PRESENTATION to PropValue.Integer(1),
                PropKey.MODAL_ANIMATION_TYPE to PropValue.Integer(1),
                PropKey.MODAL_TRANSPARENT to PropValue.Flag(true),
                PropKey.ON_MODAL_REQUEST_CLOSE to PropValue.Flag(true),
            ), index = 2)),
            Mutation.Create(node(id + 1, id, NodeKind.PRESSABLE, mapOf(
                PropKey.ON_PRESS to PropValue.Flag(true),
                PropKey.BACKGROUND_COLOR to PropValue.Integer(0x6B0F1410L),
            ))),
            Mutation.Layout(id, Frame(0f, 0f, width, height)),
            Mutation.Layout(id + 1, Frame(0f, 0f, width, height)),
        )
        fun input(id: Long, name: String) = listOf(
            Mutation.Create(node(id, 7, NodeKind.INPUT, mapOf(
                PropKey.TEST_ID to PropValue.Text(name),
                PropKey.AUTO_FOCUS to PropValue.Flag(true),
                PropKey.MULTILINE to PropValue.Flag(true),
                PropKey.INPUT_SYNC_MODE to PropValue.Integer(1),
                PropKey.VALUE to PropValue.Text("mensagem $name"),
            ), index = 1)),
            Mutation.Layout(id, Frame(16f, 64f, width - 32f, 110f)),
        )
        fun awaitKeyboard(name: String) {
            waitUntil(instrumentation, "keyboard open for $name", timeoutMs = 10_000) {
                val content = presentedModalContent(renderer, sheetId = 6) ?: return@waitUntil false
                val field = content.findByTransitionName(name) as? EditText ?: return@waitUntil false
                lastGeometry = "focus=${field.hasFocus()} windowFocus=${field.hasWindowFocus()} " +
                    "ime=${dialogImeTop(field)}"
                field.hasFocus() && dialogImeTop(field) != null
            }
        }
        fun closeSheet() {
            Thread.sleep(600)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil(instrumentation, "IME hidden") {
                presentedModalContent(renderer, sheetId = 6)?.let { dialogImeTop(it) } == null
            }
            onMain(instrumentation) {
                renderer.commit(listOf(listOf(Mutation.Update(6, PropKey.VISIBLE, PropValue.Flag(false)))))
            }
            waitUntil(instrumentation, "sheet hidden") { presentedModalContent(renderer, sheetId = 6) == null }
            Thread.sleep(300)
        }
        fun editFromOverlay(overlayId: Long, oldInput: Long?, newInput: Long, name: String) {
            onMain(instrumentation) { renderer.commit(listOf(overlay(overlayId))) }
            waitUntil(instrumentation, "overlay presented") {
                presentedModalContent(renderer, sheetId = overlayId) != null
            }
            Thread.sleep(300)
            onMain(instrumentation) {
                renderer.commit(listOf(
                    listOfNotNull(oldInput?.let { Mutation.Remove(it) }) + input(newInput, name) +
                        Mutation.Update(6, PropKey.VISIBLE, PropValue.Flag(true)),
                ))
            }
            Thread.sleep(150)
            onMain(instrumentation) {
                renderer.commit(listOf(listOf(Mutation.Remove(overlayId))))
                // A long UI-thread frame (a heavy commit on a mid-range
                // phone) while the sheet window gains focus.
                activity.host.post { SystemClock.sleep(1_200) }
            }
            awaitKeyboard(name)
        }
        try {
            onMain(instrumentation) {
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                val density = activity.host.resources.displayMetrics.density
                width = activity.host.width / density
                height = activity.host.height / density
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(6, 1, NodeKind.MODAL, mapOf(
                        PropKey.VISIBLE to PropValue.Flag(false),
                        PropKey.MODAL_PRESENTATION to PropValue.Integer(3),
                        PropKey.BOTTOM_SHEET_KEYBOARD_BEHAVIOR to PropValue.Integer(1),
                        PropKey.ON_MODAL_REQUEST_CLOSE to PropValue.Flag(true),
                    ), index = 1)),
                    Mutation.Create(node(7, 6, NodeKind.COLUMN, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFF7F6F2L),
                    ))),
                    Mutation.Create(node(10, 7, NodeKind.PRESSABLE, mapOf(
                        PropKey.ON_PRESS to PropValue.Flag(true),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, width, height)),
                    Mutation.Layout(6, Frame(0f, 0f, width, height)),
                    Mutation.Layout(7, Frame(0f, 0f, width, 260f)),
                    Mutation.Layout(10, Frame(width - 60f, 8f, 44f, 44f)),
                    Mutation.SetRoot(1),
                )))
            }
            editFromOverlay(overlayId = 100, oldInput = null, newInput = 20, name = "edit-1")
            closeSheet()
            editFromOverlay(overlayId = 110, oldInput = 20, newInput = 21, name = "edit-2")
            closeSheet()
            editFromOverlay(overlayId = 120, oldInput = 21, newInput = 22, name = "edit-3")
            onMain(instrumentation) { renderer.close() }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    private fun runSheetScenario(autoFocus: Boolean) {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var renderer: PamRenderer
        val baseImeInsets = Collections.synchronizedList(ArrayList<Int>())
        try {
            onMain(instrumentation) {
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                activity.host.onImeInsetChanged = { baseImeInsets += it }
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                val density = activity.host.resources.displayMetrics.density
                val width = activity.host.width / density
                val height = activity.host.height / density
                val snapPoints = ByteBuffer.allocate(2 + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putShort(1)
                    putDouble(0.3)
                    flip()
                }
                val sheetInputProps = buildMap<PropKey, PropValue> {
                    put(PropKey.TEST_ID, PropValue.Text("sheet-input"))
                    if (autoFocus) put(PropKey.AUTO_FOCUS, PropValue.Flag(true))
                }
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.COLUMN)),
                    Mutation.Create(node(3, 2, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFF7F6F2),
                    ))),
                    Mutation.Create(node(4, 2, NodeKind.KEYBOARD_AVOIDING_VIEW, mapOf(
                        PropKey.KEYBOARD_BEHAVIOR to PropValue.Integer(2),
                        PropKey.TEST_ID to PropValue.Text("base-composer"),
                    ), index = 1)),
                    Mutation.Create(node(5, 4, NodeKind.INPUT, mapOf(
                        PropKey.TEST_ID to PropValue.Text("base-input"),
                    ))),
                    Mutation.Create(node(6, 1, NodeKind.MODAL, mapOf(
                        PropKey.VISIBLE to PropValue.Flag(true),
                        PropKey.MODAL_PRESENTATION to PropValue.Integer(3),
                        PropKey.BOTTOM_SHEET_SNAP_POINTS to PropValue.Bytes(snapPoints),
                        PropKey.BOTTOM_SHEET_KEYBOARD_BEHAVIOR to PropValue.Integer(1),
                    ), index = 1)),
                    Mutation.Create(node(7, 6, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFFL),
                    ))),
                    Mutation.Create(node(8, 7, NodeKind.INPUT, sheetInputProps)),
                    Mutation.Create(node(9, 7, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF1F6FEBL),
                        PropKey.TEST_ID to PropValue.Text("sheet-save"),
                    ), index = 1)),
                    Mutation.Layout(1, Frame(0f, 0f, width, height)),
                    Mutation.Layout(2, Frame(0f, 0f, width, height)),
                    Mutation.Layout(3, Frame(0f, 0f, width, height - 72f)),
                    Mutation.Layout(4, Frame(0f, height - 72f, width, 72f)),
                    Mutation.Layout(5, Frame(8f, 8f, width - 16f, 56f)),
                    Mutation.Layout(6, Frame(0f, 0f, width, height)),
                    Mutation.Layout(7, Frame(0f, 0f, width, 180f)),
                    Mutation.Layout(8, Frame(16f, 24f, width - 32f, 48f)),
                    Mutation.Layout(9, Frame(16f, 88f, width - 32f, 48f)),
                    Mutation.SetRoot(1),
                )))
            }
            lateinit var composer: View
            lateinit var sheetInput: EditText
            lateinit var save: View
            waitUntil(instrumentation, "sheet presented") {
                val content = presentedModalContent(renderer) ?: return@waitUntil false
                val input = content.findByTransitionName("sheet-input") as? EditText
                    ?: return@waitUntil false
                composer = requireNotNull(activity.host.findByTransitionName("base-composer"))
                sheetInput = input
                save = requireNotNull(content.findByTransitionName("sheet-save"))
                input.isShown && input.hasWindowFocus()
            }
            if (!autoFocus) {
                // Let the entrance settle, then tap the field like a user.
                Thread.sleep(400)
                onMain(instrumentation) {
                    sheetInput.requestFocus()
                    sheetInput.context.getSystemService(InputMethodManager::class.java)
                        .showSoftInput(sheetInput, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            waitUntil(instrumentation, "sheet field and save above the IME", timeoutMs = 10_000) {
                val imeTop = dialogImeTop(sheetInput) ?: return@waitUntil false
                val input = screenBottom(sheetInput)
                val button = screenBottom(save)
                lastGeometry = "imeTop=$imeTop input=$input save=$button " +
                    "sheetTy=${(sheetInput.parent as View).translationY} " +
                    "composerTy=${composer.translationY} baseIme=${activity.host.imeBottomInset}"
                sheetInput.hasFocus() && input <= imeTop + 1 && button <= imeTop + 1 &&
                    // Directly above the keyboard, not floating somewhere higher.
                    (sheetInput.parent as View).let { imeTop - screenBottom(it) in -1..1 }
            }
            Thread.sleep(500)
            onMain(instrumentation) {
                val imeTop = requireNotNull(dialogImeTop(sheetInput))
                assertTrue("settled: $lastGeometry", screenBottom(save) <= imeTop + 1)
                assertEquals("base composer must ignore the sheet's IME", 0f, composer.translationY)
                assertEquals("base host must ignore the sheet's IME", 0, activity.host.imeBottomInset)
                assertTrue("base ime reports $baseImeInsets", baseImeInsets.none { it > 0 })
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil(instrumentation, "sheet settles back once the IME hides") {
                lastGeometry = "sheetTy=${(sheetInput.parent as View).translationY}"
                dialogImeTop(sheetInput) == null && (sheetInput.parent as View).translationY == 0f
            }
            onMain(instrumentation) {
                assertEquals(0f, composer.translationY)
                renderer.close()
            }
        } finally {
            onMain(instrumentation) {
                activity.host.onImeInsetChanged = null
                activity.finish()
            }
        }
    }

    /** IME top in screen coordinates as seen by the view's (dialog) window, or null when hidden. */
    private fun dialogImeTop(view: View): Int? {
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

    private fun presentedModalContent(renderer: PamRenderer, sheetId: Long? = null): View? {
        val field = PamRenderer::class.java.getDeclaredField("views").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val views = field.get(renderer) as android.util.LongSparseArray<View>
        val modal = if (sheetId != null) {
            (views.get(sheetId) as? PamModalHost)?.takeIf { it.isPresented() }
        } else {
            (0 until views.size()).mapNotNull { views.valueAt(it) as? PamModalHost }
                .singleOrNull { it.isPresented() }
        } ?: return null
        val content = PamModalHost::class.java.getDeclaredField("content").apply { isAccessible = true }
        return content.get(modal) as? View
    }

    private fun waitUntil(
        instrumentation: Instrumentation,
        description: String,
        timeoutMs: Long = 8_000,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            Thread.sleep(50)
        }
        throw AssertionError("Timed out waiting for $description ($lastGeometry)")
    }

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
        index: Int = 0,
    ): NodeSpec = NodeSpec(id = id, parent = parent, index = index, kind = kind, properties = properties)

    private fun launchActivity(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as PamTestActivity

    private fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun View.findByTransitionName(name: String): View? {
        if (transitionName == name) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findByTransitionName(name)?.let { return it }
        }
        return null
    }
}
