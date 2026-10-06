package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.app.Dialog
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Insets
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.os.Build
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowManager
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.accessibility.AccessibilityWindowInfo
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
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PamKeyboardAwareScrollInstrumentedTest {
    @Test
    fun animatedInsetsRevealFocusWithoutShrinkingViewportOrResettingManualScroll() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        fixture { f ->
            f.onMain { f.first.showSoftInputOnFocus = false; f.first.requestFocus() }
            f.insets(240)
            f.waitFor("initial focused target") { f.visible(f.first, 240, 180) }
            f.onMain { assertEquals(f.initialHeight, f.scroll.height) }
            // The IME toolbar can change the inset during its native animation.
            f.insets(300, animated = true)
            f.waitFor("animated focused target") { f.visible(f.first, 300, 180) }
            f.onMain { f.second.showSoftInputOnFocus = false; f.second.requestFocus() }
            f.waitFor("new focus without any PHP event") { f.visible(f.second, 300, 180) }
            f.onMain {
                f.scroll.getChildAt(0).scrollTo(0, f.dp(120))
                f.dispatchInsets(300)
            }
            f.instrumentation.waitForIdleSync()
            f.onMain { assertEquals("unchanged insets retain manual scroll", f.dp(120), f.scroll.snapshotOffsetPixels().second) }
            f.insets(0)
            f.onMain {
                assertEquals("keyboard hide clears only the inset", 0, f.scroll.keyboardAvoidanceInsetPixels())
                assertEquals("hide does not restore an old authored offset", f.dp(120), f.scroll.snapshotOffsetPixels().second)
                f.renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.KEYBOARD_VERTICAL_OFFSET, null))))
            }
            f.instrumentation.waitForIdleSync()
            f.insets(300)
            f.waitFor("default 24dp clearance") { f.visible(f.second, 300, 24) }
            f.onMain { assertEquals(f.dp(300), f.scroll.keyboardAvoidanceInsetPixels()) }
            f.onMain { f.renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.SCROLL_KEYBOARD_INSET, null)))) }
            f.onMain { assertEquals(0, f.scroll.keyboardAvoidanceInsetPixels()) }
        }
    }

    @Test
    fun realImeFocusAndAuthoredResizeKeep180dpClearanceAndFullViewport() {
        fixture { f ->
            f.showFirstKeyboard()
            f.waitFor("real keyboard and first input") {
                val ime = f.imePixels()
                ime > 0 && f.visiblePixels(f.first, ime, f.dp(180))
            }
            f.onMain {
                assertEquals("IME never reduces the scroll viewport", f.initialHeight, f.scroll.height)
                f.second.requestFocus()
            }
            f.waitFor("switching focused input with keyboard open") { f.visiblePixels(f.second, f.imePixels(), f.dp(180)) }
            f.onMain {
                f.renderer.commit(listOf(listOf(Mutation.Layout(2, Frame(0f, 0f, f.width, f.height - 40f)))))
            }
            f.waitFor("authored resize updates the overlapping IME inset") {
                f.scroll.height == f.dp(f.height - 40f) && f.visiblePixels(f.second, f.imePixels(), f.dp(180))
            }
            f.onMain {
                f.second.context.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(f.second.windowToken, 0)
            }
            f.waitFor("keyboard hides") { f.imePixels() == 0 && f.scroll.keyboardAvoidanceInsetPixels() == 0 }
            f.onMain { assertEquals("only authored resize remains", f.dp(f.height - 40f), f.scroll.height) }
        }
    }

    @Test
    fun legacyScrollAndPanningOverlayShareImeDetectionAcrossDisableAndRemount() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
        fixture { f ->
            f.showFirstKeyboard()
            f.waitFor("legacy scroll sees real IME") { f.visiblePixels(f.first, f.imePixels(), f.dp(180)) }
            lateinit var overlay: View
            f.onMain {
                f.renderer.commit(listOf(listOf(
                    Mutation.Create(NodeSpec(6, 1, 1, NodeKind.KEYBOARD_AVOIDING_VIEW, mapOf(
                        PropKey.KEYBOARD_BEHAVIOR to PropValue.Integer(2),
                    ))),
                    Mutation.Layout(6, Frame(0f, f.height - 48f, f.width, 48f)),
                )))
                overlay = requireNotNull(f.renderer.viewForNode(6))
            }
            f.waitFor("late mounted feedback overlay follows existing keyboard") {
                val location = IntArray(2).also(overlay::getLocationInWindow)
                val expectedBottom = overlay.rootView.height - f.imePixels()
                overlay.translationY < 0 && kotlin.math.abs(location[1] + overlay.height - expectedBottom) <= 2
            }
            f.onMain {
                assertEquals(f.initialHeight, f.scroll.height)
                f.renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.SCROLL_KEYBOARD_INSET, PropValue.Flag(false)))))
                assertEquals(0, f.scroll.keyboardAvoidanceInsetPixels())
                assertTrue("overlay retains the shared measurement after scroll disables", overlay.translationY < 0)
                f.renderer.commit(listOf(listOf(Mutation.Update(6, PropKey.KEYBOARD_AVOIDING_ENABLED, PropValue.Flag(false)))))
                assertEquals(0f, overlay.translationY, 0f)
                assertTrue("disabling consumers must not take input focus", f.first.hasFocus())
                f.renderer.commit(listOf(listOf(Mutation.Update(2, PropKey.SCROLL_KEYBOARD_INSET, PropValue.Flag(true)))))
            }
            f.waitFor("new observer reads an already open keyboard") { f.visiblePixels(f.first, f.imePixels(), f.dp(180)) }
            lateinit var dialog: Dialog
            f.onMain {
                dialog = Dialog(f.activity).apply {
                    setContentView(View(f.activity))
                    show()
                }
            }
            try {
                f.waitFor("another window suspends the main-window observer") {
                    !f.activity.hasWindowFocus() && f.scroll.keyboardAvoidanceInsetPixels() == 0
                }
            } finally {
                f.onMain { dialog.dismiss() }
            }
            f.showFirstKeyboard()
            f.waitFor("observer resumes after its popup was detached") { f.visiblePixels(f.first, f.imePixels(), f.dp(180)) }
            f.onMain {
                val parent = f.scroll.parent as ViewGroup
                val index = parent.indexOfChild(f.scroll)
                parent.removeView(f.scroll)
                assertEquals("detach releases the keyboard content inset", 0, f.scroll.keyboardAvoidanceInsetPixels())
                parent.addView(f.scroll, index)
                f.first.requestFocus()
                f.first.context.getSystemService(InputMethodManager::class.java).showSoftInput(f.first, InputMethodManager.SHOW_IMPLICIT)
            }
            f.waitFor("reattached consumer measures the keyboard again") { f.visiblePixels(f.first, f.imePixels(), f.dp(180)) }
            f.onMain {
                f.first.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(f.first.windowToken, 0)
            }
            f.waitFor("legacy hide clears content inset") { f.imePixels() == 0 && f.scroll.keyboardAvoidanceInsetPixels() == 0 }
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as PamTestActivity
        instrumentation.waitForIdleSync()
        var current: Fixture? = null
        try {
            instrumentation.runOnMainSync { current = Fixture(instrumentation, activity) }
            val f = requireNotNull(current)
            instrumentation.waitForIdleSync()
            f.onMain { f.initialHeight = f.scroll.height }
            block(f)
        } finally {
            instrumentation.runOnMainSync {
                current?.renderer?.close()
                activity.finish()
            }
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
    }

    private class Fixture(val instrumentation: Instrumentation, val activity: PamTestActivity) {
        val renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
        val density = activity.host.resources.displayMetrics.density
        val width = activity.host.width / density
        val height = activity.host.height / density
        val scroll: PamScrollContainer
        val first: EditText
        val second: EditText
        var initialHeight = 0
        var imeShowAccepted = false

        init {
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            renderer.commit(listOf(listOf(
                Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                Mutation.Create(node(2, 1, NodeKind.SCROLL, mapOf(
                    PropKey.SCROLL_KEYBOARD_INSET to PropValue.Flag(true),
                    PropKey.KEYBOARD_VERTICAL_OFFSET to PropValue.Decimal(156.0),
                ))),
                // Retain the authored blank space after the inputs. A layout-only
                // column is flattened, so its 2x viewport frame cannot establish
                // the native scroll range by itself.
                Mutation.Create(node(3, 2, NodeKind.COLUMN, mapOf(PropKey.COLLAPSABLE to PropValue.Flag(false)))),
                Mutation.Create(node(4, 3, NodeKind.INPUT)),
                Mutation.Create(node(5, 3, NodeKind.INPUT, index = 1)),
                Mutation.Layout(1, Frame(0f, 0f, width, height)),
                Mutation.Layout(2, Frame(0f, 0f, width, height)),
                Mutation.Layout(3, Frame(0f, 0f, width, height * 2)),
                Mutation.Layout(4, Frame(16f, height * 0.72f, width - 32f, 54f)),
                Mutation.Layout(5, Frame(16f, height * 1.4f, width - 32f, 54f)),
                Mutation.SetRoot(1),
            )))
            scroll = requireNotNull(renderer.viewForNode(2)) as PamScrollContainer
            first = requireNotNull(renderer.viewForNode(4)) as EditText
            second = requireNotNull(renderer.viewForNode(5)) as EditText
        }

        fun dp(value: Number): Int = (value.toFloat() * density).roundToInt()
        fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
        fun showFirstKeyboard() {
            waitFor("activity window focus before user input") { activity.hasWindowFocus() && first.isAttachedToWindow }
            tap(first)
            onMain {
                first.requestFocus()
                imeShowAccepted = first.context.getSystemService(InputMethodManager::class.java)
                    .showSoftInput(first, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        fun tap(input: EditText) {
            val location = IntArray(2)
            var x = 0f
            var y = 0f
            onMain {
                input.showSoftInputOnFocus = true
                input.getLocationOnScreen(location)
                x = location[0] + input.width / 2f
                y = location[1] + input.height / 2f
            }
            val down = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                instrumentation.sendPointerSync(event)
                event.recycle()
            }
            instrumentation.waitForIdleSync()
        }
        fun imePixels(): Int {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return ViewCompat.getRootWindowInsets(activity.host)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
            }
            // Independent oracle: API26's adjustNothing insets report zero even
            // when the real IME window covers the input. Read the system-owned
            // accessibility window, not the observer under test.
            val ime = instrumentation.uiAutomation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?: return 0
            val bounds = Rect().also(ime::getBoundsInScreen)
            val root = activity.host.rootView
            val location = IntArray(2).also(root::getLocationOnScreen)
            return (location[1] + root.height - bounds.top).coerceAtLeast(0)
        }
        fun insets(heightDp: Int, animated: Boolean = false) {
            onMain { dispatchInsets(heightDp, animated) }
            instrumentation.waitForIdleSync()
        }
        fun dispatchInsets(heightDp: Int, animated: Boolean = false) {
            val insets = WindowInsets.Builder()
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, dp(heightDp)))
                .setVisible(WindowInsets.Type.ime(), heightDp > 0)
                .build()
            if (animated) scroll.dispatchWindowInsetsAnimationProgress(insets, emptyList())
            else scroll.dispatchApplyWindowInsets(insets)
        }
        fun visible(input: EditText, imeDp: Int, clearanceDp: Int): Boolean = visiblePixels(input, dp(imeDp), dp(clearanceDp))
        fun visiblePixels(input: EditText, ime: Int, clearance: Int): Boolean {
            if (ime <= 0) return false
            val inputLocation = IntArray(2)
            val scrollLocation = IntArray(2)
            input.getLocationInWindow(inputLocation)
            scroll.getLocationInWindow(scrollLocation)
            return inputLocation[1] >= scrollLocation[1] - 1 &&
                inputLocation[1] + input.height <= scroll.rootView.height - ime - clearance + 2
        }
        fun waitFor(label: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < deadline) {
                var ready = false
                onMain { ready = condition() }
                if (ready) return
                Thread.sleep(50)
            }
            val bounds = listOf(first, second).joinToString { input ->
                val location = IntArray(2)
                input.getLocationInWindow(location)
                "${location[1]}..${location[1] + input.height} scrollY=${input.scrollY} focused=${input.hasFocus()}"
            }
            val visibleFrame = Rect().also(activity.window.decorView::getWindowVisibleDisplayFrame)
            val screenshot = java.io.File(activity.getExternalFilesDir(null), "ime26-probe-before-finish.png")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                screenshot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            val imm = activity.getSystemService(InputMethodManager::class.java)
            throw AssertionError("$label: windowFocus=${activity.hasWindowFocus()} showAccepted=$imeShowAccepted activeInput=${imm.isActive(first)} acceptingText=${imm.isAcceptingText} screenshot=$screenshot visibleFrame=$visibleFrame root=${scroll.rootView.height} scroll=${scroll.height} inset=${scroll.keyboardAvoidanceInsetPixels()} ime=${imePixels()} offset=${scroll.snapshotOffsetPixels()} remaining=${scroll.remainingPrimaryPixels()} inputs=[$bounds]")
        }
        private fun node(id: Long, parent: Long, kind: NodeKind, properties: Map<PropKey, PropValue> = emptyMap(), index: Int = 0) =
            NodeSpec(id, parent, index, kind, properties)
    }
}
