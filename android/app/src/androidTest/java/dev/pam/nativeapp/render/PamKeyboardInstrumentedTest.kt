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
import dev.pam.nativeapp.protocol.EventKind
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/**
 * Zé chat keyboard regressions: root SafeAreaView without the bottom edge, a
 * flexible timeline with `z-index: 1` and a panning composer
 * KeyboardAvoidingView. Uses the real IME of the device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class PamKeyboardInstrumentedTest {
    @Volatile
    private var lastGeometry = ""

    @Test
    fun panningComposerStaysAboveAHigherZIndexTimelineAndTheRootSeesTheIme() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var renderer: PamRenderer
        val imeInsets = Collections.synchronizedList(ArrayList<Int>())
        try {
            onMain(instrumentation) {
                // PamActivity's window contract: the IME never resizes the window.
                activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                activity.host.onImeInsetChanged = { imeInsets += it }
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                val density = activity.host.resources.displayMetrics.density
                val width = activity.host.width / density
                val height = activity.host.height / density
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.COLUMN)),
                    Mutation.Create(node(3, 2, NodeKind.VIEW, mapOf(
                        PropKey.Z_INDEX to PropValue.Integer(1),
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFF7F6F2),
                        PropKey.TEST_ID to PropValue.Text("kb-timeline"),
                    ))),
                    Mutation.Create(node(4, 2, NodeKind.KEYBOARD_AVOIDING_VIEW, mapOf(
                        PropKey.KEYBOARD_BEHAVIOR to PropValue.Integer(2),
                        PropKey.TEST_ID to PropValue.Text("kb-composer"),
                    ), index = 1)),
                    Mutation.Create(node(5, 4, NodeKind.INPUT, mapOf(
                        PropKey.TEST_ID to PropValue.Text("kb-input"),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, width, height)),
                    Mutation.Layout(2, Frame(0f, 0f, width, height)),
                    Mutation.Layout(3, Frame(0f, 0f, width, height - 72f)),
                    Mutation.Layout(4, Frame(0f, height - 72f, width, 72f)),
                    Mutation.Layout(5, Frame(8f, 8f, width - 16f, 56f)),
                    Mutation.SetRoot(1),
                )))
            }
            instrumentation.waitForIdleSync()
            lateinit var timeline: View
            lateinit var composer: View
            lateinit var input: EditText
            onMain(instrumentation) {
                timeline = requireNotNull(activity.host.findByTransitionName("kb-timeline"))
                composer = requireNotNull(activity.host.findByTransitionName("kb-composer"))
                input = requireNotNull(activity.host.findByTransitionName("kb-input")) as EditText
                input.requestFocus()
                showKeyboard(input)
            }
            // The IME may settle in several insets animations (suggestion
            // strip, toolbar); wait until the composer follows the final one.
            waitUntil(instrumentation, "composer directly above the IME") {
                val location = IntArray(2)
                composer.getLocationOnScreen(location)
                val settled = activity.host.rootWindowInsets
                    ?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
                val imeTop = activity.host.rootView.height - settled
                lastGeometry = "settled=$settled host=${activity.host.imeBottomInset} " +
                    "bottom=${location[1] + composer.height} ty=${composer.translationY} " +
                    "top=${composer.top} root=${activity.host.rootView.height}"
                activity.host.imeBottomInset == settled &&
                    settled > 0 &&
                    composer.translationY < 0f &&
                    kotlin.math.abs(location[1] + composer.height - imeTop) <= 1
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                // The KAV's own insets listener must not swallow the root
                // host's handling (stable safe areas + engine IME inset).
                assertTrue(imeInsets.any { it > 0 })
                assertTrue(
                    "translated composer must draw above the z-index 1 timeline",
                    composer.z > timeline.z,
                )
                val location = IntArray(2)
                composer.getLocationOnScreen(location)
                val imeTop = activity.host.rootView.height - activity.host.imeBottomInset
                assertTrue(
                    "composer bottom ${location[1] + composer.height} must sit above the IME top $imeTop " +
                        "(ty=${composer.translationY} top=${composer.top} host=${activity.host.height} " +
                        "root=${activity.host.rootView.height} ime=${activity.host.imeBottomInset})",
                    location[1] + composer.height <= imeTop + 1,
                )
            }
            // Back closes the IME (the IME consumes it) without moving focus.
            Thread.sleep(500)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil(instrumentation, "IME hidden and composer restored") {
                lastGeometry = "ime=${activity.host.imeBottomInset} ty=${composer.translationY} z=${composer.z} seq=$imeInsets"
                activity.host.imeBottomInset == 0 && composer.translationY == 0f
            }
            Thread.sleep(600)
            onMain(instrumentation) {
                assertEquals("after hide: $lastGeometry now ty=${composer.translationY} ime=${activity.host.imeBottomInset}", 0f, composer.z)
                assertEquals("ime sequence $imeInsets", 0, imeInsets.last())
                renderer.close()
            }
        } finally {
            onMain(instrumentation) {
                activity.host.onImeInsetChanged = null
                activity.finish()
            }
        }
    }

    @Test
    fun closingTheKeyboardWhileAnInputKeepsFocusBlursItSoTheComposerRestoresItsInset() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var renderer: PamRenderer
        val events = Collections.synchronizedList(ArrayList<Int>())
        try {
            onMain(instrumentation) {
                renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                    if (id == 3L) events += kind
                }
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.COLUMN)),
                    Mutation.Create(node(3, 2, NodeKind.INPUT, mapOf(
                        PropKey.ON_FOCUS to PropValue.Flag(true),
                        PropKey.ON_BLUR to PropValue.Flag(true),
                        PropKey.TEST_ID to PropValue.Text("kb-blur-input"),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(3, Frame(16f, 400f, 328f, 56f)),
                    Mutation.SetRoot(1),
                )))
            }
            instrumentation.waitForIdleSync()
            lateinit var input: EditText
            onMain(instrumentation) {
                input = requireNotNull(activity.host.findByTransitionName("kb-blur-input")) as EditText
                input.requestFocus()
                showKeyboard(input)
            }
            waitUntil(instrumentation, "IME shown") { activity.host.imeBottomInset > 0 }
            assertTrue(events.contains(EventKind.FOCUS.value))
            // Back closes the keyboard without moving focus.
            Thread.sleep(500)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitUntil(instrumentation, "input blurred after IME hide") {
                activity.host.imeBottomInset == 0 && !input.hasFocus()
            }
            assertTrue(events.contains(EventKind.BLUR.value))
            // Tapping the field again focuses it and reopens the IME.
            onMain(instrumentation) { assertTrue(input.requestFocus()) }
            onMain(instrumentation) { renderer.close() }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    @Test
    fun staleControlledValueEchoNeverOverwritesNewerImeText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var renderer: PamRenderer
        val changes = Collections.synchronizedList(ArrayList<String>())
        try {
            onMain(instrumentation) {
                renderer = PamRenderer(activity, activity.host) { id, kind, payload ->
                    if (id == 3L && kind == EventKind.CHANGE.value) {
                        changes += payload.toString(Charsets.UTF_8)
                    }
                }
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.COLUMN)),
                    Mutation.Create(node(3, 2, NodeKind.INPUT, mapOf(
                        PropKey.ON_CHANGE to PropValue.Flag(true),
                        PropKey.INPUT_SYNC_MODE to PropValue.Integer(3),
                        PropKey.VALUE to PropValue.Text(""),
                        PropKey.TEST_ID to PropValue.Text("kb-controlled-input"),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(3, Frame(16f, 80f, 328f, 56f)),
                    Mutation.SetRoot(1),
                )))
            }
            instrumentation.waitForIdleSync()
            lateinit var input: EditText
            onMain(instrumentation) {
                input = requireNotNull(
                    activity.host.findByTransitionName("kb-controlled-input"),
                ) as EditText
                input.requestFocus()
            }
            // Typed into the field itself, one key at a time: injected keys go
            // through whichever IME and window hold focus at that moment
            // (autocorrect, a late focus change), which made this test flaky.
            onMain(instrumentation) { typeInto(input, "qa teste") }
            waitUntil(instrumentation, "IME text dispatched") { changes.lastOrNull() == "qa teste" }
            assertTrue(changes.contains("qa test"))

            // PHP rendered the change event for "qa test" after the IME had
            // already appended the final "e": the echo is stale.
            onMain(instrumentation) {
                renderer.commit(listOf(listOf(
                    Mutation.Update(3, PropKey.VALUE, PropValue.Text("qa test")),
                )))
                assertEquals("qa teste", input.text.toString())
                renderer.commit(listOf(listOf(
                    Mutation.Update(3, PropKey.VALUE, PropValue.Text("qa teste")),
                )))
                assertEquals("qa teste", input.text.toString())
            }
            onMain(instrumentation) { typeInto(input, " 1") }
            waitUntil(instrumentation, "suffix dispatched") { changes.lastOrNull() == "qa teste 1" }
            onMain(instrumentation) {
                assertEquals("qa teste 1", input.text.toString())
                // An authored value (clearing after send) is always applied.
                renderer.commit(listOf(listOf(
                    Mutation.Update(3, PropKey.VALUE, PropValue.Text("")),
                )))
                assertEquals("", input.text.toString())
                assertFalse(input.text.isNotEmpty())
                renderer.close()
            }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    @Test
    fun deleteBurstOnAControlledInputNeverResurrectsStaleText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var renderer: PamRenderer
        val changes = Collections.synchronizedList(ArrayList<String>())
        try {
            onMain(instrumentation) {
                renderer = PamRenderer(activity, activity.host) { id, kind, payload ->
                    if (id == 3L && kind == EventKind.CHANGE.value) {
                        changes += payload.toString(Charsets.UTF_8)
                    }
                }
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.COLUMN)),
                    Mutation.Create(node(3, 2, NodeKind.INPUT, mapOf(
                        PropKey.ON_CHANGE to PropValue.Flag(true),
                        PropKey.INPUT_SYNC_MODE to PropValue.Integer(3),
                        PropKey.VALUE to PropValue.Text("J6pKKx9QwZ4rTyU2"),
                        PropKey.TEST_ID to PropValue.Text("kb-delete-input"),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(3, Frame(16f, 80f, 328f, 56f)),
                    Mutation.SetRoot(1),
                )))
            }
            instrumentation.waitForIdleSync()
            lateinit var input: EditText
            onMain(instrumentation) {
                input = requireNotNull(activity.host.findByTransitionName("kb-delete-input")) as EditText
                input.requestFocus()
                input.setSelection(input.text.length)
            }
            // PHP echoes the change events in order, late, interleaved with
            // the deletions (each event is rendered at most once).
            var echoedIndex = -1
            repeat(16) { index ->
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)
                if (index % 3 == 2) {
                    onMain(instrumentation) {
                        val target = changes.size - 3
                        if (target <= echoedIndex) return@onMain
                        echoedIndex = target
                        val echoed = changes[target]
                        val before = input.text.toString()
                        renderer.commit(listOf(listOf(
                            Mutation.Update(3, PropKey.VALUE, PropValue.Text(echoed)),
                        )))
                        assertEquals("stale echo '$echoed' must not resurrect text", before, input.text.toString())
                    }
                }
            }
            instrumentation.waitForIdleSync()
            waitUntil(instrumentation, "all deletions applied") {
                lastGeometry = "text=${input.text} changes=$changes"
                input.text.isEmpty() && changes.lastOrNull() == ""
            }
            // Each Backspace removes exactly the last character (no digit
            // removed from the middle: "J6pKK…" must never become "JpK").
            assertEquals(
                (1..16).map { "J6pKKx9QwZ4rTyU2".dropLast(it) },
                changes.toList(),
            )
            onMain(instrumentation) {
                for (index in echoedIndex + 1 until changes.size) {
                    renderer.commit(listOf(listOf(
                        Mutation.Update(3, PropKey.VALUE, PropValue.Text(changes[index])),
                    )))
                    assertEquals("echo #$index of $changes after #$echoedIndex", "", input.text.toString())
                }
                renderer.close()
            }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    @Test
    fun anUnfocusedWindowKeepsItsSafeAreaAndIgnoresAnotherWindowsIme() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val imeInsets = Collections.synchronizedList(ArrayList<Int>())
        try {
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                val host = activity.host
                val before = host.stableSafeAreaInsets
                assertTrue("portrait emulator exposes a bottom inset", before.bottom > 0)
                host.onImeInsetChanged = { imeInsets += it }
                host.windowFocused = { false }
                // A dialog on top: this window is reported without bars, but
                // with the IME of the dialog visible.
                val zero = android.graphics.Insets.of(0, 0, 0, 0)
                val insets = WindowInsets.Builder()
                    .setInsets(WindowInsets.Type.systemBars(), zero)
                    .setInsetsIgnoringVisibility(WindowInsets.Type.systemBars(), zero)
                    .setInsets(WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, 900))
                    .setVisible(WindowInsets.Type.ime(), true)
                    .build()
                host.dispatchApplyWindowInsets(insets)
                assertEquals(before, host.stableSafeAreaInsets)
                assertEquals(0, host.imeBottomInset)
                assertTrue(imeInsets.isEmpty())
                host.windowFocused = { host.hasWindowFocus() }
                host.onImeInsetChanged = null
                host.requestApplyInsets()
            }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    @Test
    fun removingAnOverlayModalAndPresentingASheetInOneCommitLeavesOneLiveWindowThatOwnsBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var renderer: PamRenderer
        val events = Collections.synchronizedList(ArrayList<Pair<Long, Int>>())
        try {
            onMain(instrumentation) {
                renderer = PamRenderer(activity, activity.host) { id, kind, _ -> events += id to kind }
                renderer.commit(listOf(listOf(
                    Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                    Mutation.Create(node(2, 1, NodeKind.MODAL, mapOf(
                        PropKey.VISIBLE to PropValue.Flag(true),
                        PropKey.MODAL_PRESENTATION to PropValue.Integer(1),
                        PropKey.MODAL_ANIMATION_TYPE to PropValue.Integer(1),
                        PropKey.MODAL_TRANSPARENT to PropValue.Flag(true),
                        PropKey.ON_MODAL_REQUEST_CLOSE to PropValue.Flag(true),
                    ))),
                    Mutation.Create(node(3, 2, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0x6B0F1410L),
                        PropKey.TEST_ID to PropValue.Text("kb-overlay"),
                    ))),
                    Mutation.Create(node(4, 1, NodeKind.MODAL, mapOf(
                        PropKey.VISIBLE to PropValue.Flag(false),
                        PropKey.MODAL_PRESENTATION to PropValue.Integer(3),
                        PropKey.ON_MODAL_REQUEST_CLOSE to PropValue.Flag(true),
                    ), index = 1)),
                    Mutation.Create(node(5, 4, NodeKind.VIEW, mapOf(
                        PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFFL),
                        PropKey.TEST_ID to PropValue.Text("kb-sheet-content"),
                    ))),
                    Mutation.Layout(1, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(2, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(3, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(4, Frame(0f, 0f, 360f, 640f)),
                    Mutation.Layout(5, Frame(0f, 0f, 360f, 240f)),
                    Mutation.SetRoot(1),
                )))
            }
            waitUntil(instrumentation, "overlay presented") { renderer.hasPresentedModal() }
            onMain(instrumentation) {
                renderer.commit(listOf(listOf(
                    Mutation.Remove(2),
                    Mutation.Update(4, PropKey.VISIBLE, PropValue.Flag(true)),
                )))
            }
            waitUntil(instrumentation, "sheet presented with its content") {
                val content = presentedModalContent(renderer)
                content != null && content.findByTransitionName("kb-sheet-content")?.isShown == true
            }
            Thread.sleep(400)
            onMain(instrumentation) {
                assertEquals("exactly one live PAM modal window", 1, presentedModals(renderer).size)
                assertTrue(presentedModalContent(renderer)?.findByTransitionName("kb-overlay") == null)
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            Thread.sleep(300)
            onMain(instrumentation) {
                assertEquals(
                    "Back requests closing the sheet once",
                    1,
                    events.count { it == 4L to EventKind.MODAL_REQUEST_CLOSE.value },
                )
                assertFalse("Back must not reach the activity below", activity.isFinishing)
                renderer.close()
            }
        } finally {
            onMain(instrumentation) { activity.finish() }
        }
    }

    private fun presentedModals(renderer: PamRenderer): List<PamModalHost> {
        val field = PamRenderer::class.java.getDeclaredField("views").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val views = field.get(renderer) as android.util.LongSparseArray<View>
        return (0 until views.size()).mapNotNull { views.valueAt(it) as? PamModalHost }
            .filter { it.isPresented() }
    }

    private fun presentedModalContent(renderer: PamRenderer): View? {
        val modal = presentedModals(renderer).singleOrNull() ?: return null
        val field = PamModalHost::class.java.getDeclaredField("content").apply { isAccessible = true }
        return field.get(modal) as? View
    }

    /** Like a tap on the field: the focused editor asks the IME to show. */
    private fun showKeyboard(input: EditText) {
        input.context.getSystemService(InputMethodManager::class.java)
            .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
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
            awaitFrames(instrumentation)
        }
        throw AssertionError("Timed out waiting for $description ($lastGeometry)")
    }

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
        index: Int = 0,
    ): NodeSpec = NodeSpec(
        id = id,
        parent = parent,
        index = index,
        kind = kind,
        properties = properties,
    )

    private fun typeInto(input: EditText, text: String) {
        val keys = android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD)
        requireNotNull(keys.getEvents(text.toCharArray())).forEach(input::dispatchKeyEvent)
    }

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
