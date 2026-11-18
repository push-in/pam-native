package dev.pam.nativeapp.render

import android.app.LocaleManager
import android.content.Intent
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * A font scale, density or locale change rebuilds the mounted views from the
 * retained tree (same node ids, re-measured frames). What PHP does not own
 * must survive it: the row the reader was looking at stays under the
 * viewport's top edge (ScrollView, VirtualizedList, inverted list), a list
 * resting at its newest row stays there, and a focused input keeps its focus
 * and selection without reporting blur/focus to PHP.
 *
 * The configuration really changes (system font scale; per-app locale on
 * Android 13+); the replayed tree is what the engine emits for it: every row
 * re-measured taller (font) or with new text lengths (locale).
 */
@RunWith(AndroidJUnit4::class)
class PamConfigurationRemountStateInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private var originalFontScale: String? = null
    private var localeChanged = false

    /**
     * Leaves the device as found before the next test class runs: the font
     * scale and locale are restored and applied (the IME restarts for each
     * change), and the keyboard is down. A keyboard contract running next
     * must not meet an IME still restarting or left open.
     */
    @After
    fun restoreConfiguration() {
        originalFontScale?.let { original ->
            val unset = original == "null" || original.isBlank()
            shell(if (unset) "settings delete system font_scale" else "settings put system font_scale $original")
            val expected = if (unset) 1f else original.toFloatOrNull() ?: 1f
            waitFor { context.applicationContext.resources.configuration.fontScale == expected }
        }
        if (localeChanged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
        instrumentation.waitForIdleSync()
        val until = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < until && shell("dumpsys input_method").contains("mInputShown=true")) {
            SystemClock.sleep(100)
        }
    }

    @Test
    fun aScrollViewKeepsItsTopRowWhenTheFontScaleGrows() = withSurface(::scrollTree) { surface ->
        surface.scrollBy(rowsTop(40, 1f) + rowHeight(40, 1f) * 0.3f)
        val before = surface.topRow()
        assertTrue("scrolled to the middle ($before)", before.id >= ROW_BASE + 30)
        surface.remount(fontScale = 1.3f)
        surface.assertTopRow(before)
    }

    @Test
    fun aScrollViewKeepsItsTopRowWhenTheLocaleChanges() = withSurface(::scrollTree) { surface ->
        surface.scrollBy(rowsTop(33, 1f) + rowHeight(33, 1f) * 0.6f)
        val before = surface.topRow()
        surface.remount(locale = true)
        surface.assertTopRow(before)
    }

    @Test
    fun aScrollViewAtItsEndStaysThere() = withSurface(::scrollTree) { surface ->
        surface.scrollBy(1_000_000f)
        main { assertFalse(surface.scroller().canScrollVertically(1)) }
        surface.remount(fontScale = 1.3f)
        awaitTrue("still at the end") { !surface.scroller().canScrollVertically(1) }
    }

    @Test
    fun aVirtualizedListKeepsItsTopRowWhenTheFontScaleGrows() = withSurface(::listTree) { surface ->
        surface.scrollBy(rowsTop(40, 1f) + rowHeight(40, 1f) * 0.3f)
        val before = surface.topRow()
        assertTrue("scrolled to the middle ($before)", before.id >= ROW_BASE + 30)
        surface.remount(fontScale = 1.3f)
        surface.assertTopRow(before)
    }

    @Test
    fun aVirtualizedListKeepsItsTopRowWhenTheLocaleChanges() = withSurface(::listTree) { surface ->
        surface.scrollBy(rowsTop(25, 1f) + rowHeight(25, 1f) * 0.5f)
        val before = surface.topRow()
        surface.remount(locale = true)
        surface.assertTopRow(before)
    }

    @Test
    fun anInvertedListScrolledUpKeepsItsTopRowWhenTheFontScaleGrows() =
        withSurface({ scale, locale -> anyListTree(scale, locale, inverted = true) }) { surface ->
            main { assertFalse("an inverted list opens at its newest row", surface.scroller().canScrollVertically(1)) }
            // The reader scrolls up into the history.
            surface.scrollBy(-(rowsTop(30, 1f) + rowHeight(30, 1f) * 0.4f))
            val before = surface.topRow()
            assertTrue("scrolled into the history ($before)", before.id >= ROW_BASE + 20)
            surface.remount(fontScale = 1.3f)
            surface.assertTopRow(before)
            main { assertTrue("not pulled back to the newest row", surface.scroller().canScrollVertically(1)) }
        }

    @Test
    fun anInvertedListScrolledUpKeepsItsTopRowWhenTheLocaleChanges() =
        withSurface({ scale, locale -> anyListTree(scale, locale, inverted = true) }) { surface ->
            surface.scrollBy(-(rowsTop(30, 1f) + rowHeight(30, 1f) * 0.4f))
            val before = surface.topRow()
            surface.remount(locale = true)
            surface.assertTopRow(before)
        }

    @Test
    fun anInvertedListAtItsNewestRowStaysThere() =
        withSurface({ scale, locale -> anyListTree(scale, locale, inverted = true) }) { surface ->
            surface.remount(fontScale = 1.3f)
            awaitTrue("still at the newest row") { !surface.scroller().canScrollVertically(1) }
            main {
                val newest = surface.rowView(ROW_BASE)
                assertTrue("the newest row is on screen", newest != null && newest.isShown)
            }
        }

    @Test
    fun aFocusedInputKeepsFocusSelectionAndKeyboardWithoutBlurEvents() {
        val events = Collections.synchronizedList(ArrayList<Pair<Long, Int>>())
        withSurface(::inputTree, events) { surface ->
            lateinit var before: EditText
            main {
                before = surface.renderer.viewForNode(INPUT_ID) as EditText
                assertTrue(before.requestFocus())
                before.setSelection(2, 7)
                // Keyboard visibility is only observable (and asserted) on
                // Android 11+; older releases check focus and selection only.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    (context.getSystemService(InputMethodManager::class.java))
                        .showSoftInput(before, InputMethodManager.SHOW_IMPLICIT)
                }
            }
            awaitTrue("input focused") { before.isFocused }
            val imeShown = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                waitFor(3_000) { imeVisible(before) }
            events.clear()
            surface.remount(fontScale = 1.3f)
            lateinit var after: EditText
            awaitTrue("the rebuilt input takes the focus back") {
                after = surface.renderer.viewForNode(INPUT_ID) as EditText
                after.isFocused
            }
            main {
                assertNotSame("the input was rebuilt", before, after)
                assertEquals("selection start", 2, after.selectionStart)
                assertEquals("selection end", 7, after.selectionEnd)
            }
            if (imeShown) awaitTrue("the keyboard is open again") { imeVisible(after) }
            SystemClock.sleep(200)
            val focusEvents = synchronized(events) {
                events.filter { it.first == INPUT_ID && (it.second == EventKind.BLUR.value || it.second == EventKind.FOCUS.value) }
            }
            // The remount itself is invisible to PHP. Only when the IME,
            // restarted by the same change, hid (and blurred the input) before
            // the change reached the activity does PHP see that blur, and
            // then the refocus: it always ends knowing the input is focused.
            val kinds = focusEvents.map { it.second }
            assertTrue(
                "no blur/focus across the remount, or a blur answered by a focus: $kinds",
                kinds.isEmpty() || kinds == listOf(EventKind.BLUR.value, EventKind.FOCUS.value),
            )
            main { (context.getSystemService(InputMethodManager::class.java)).hideSoftInputFromWindow(after.windowToken, 0) }
            if (imeShown) awaitTrue("the keyboard is down again") { !imeVisible(after) }
        }
    }

    private class TopRow(val id: Long, val fraction: Float) {
        override fun toString(): String = "row ${id - ROW_BASE} at ${"%.2f".format(fraction)}"
    }

    private inner class Surface(
        val activity: PamTestActivity,
        val renderer: PamRenderer,
        val tree: (Float, Boolean) -> List<Mutation>,
    ) {
        /** The view that scrolls (main thread). */
        fun scroller(): View =
            when (val view = renderer.viewForNode(SCROLLER_ID)) {
                is PamScrollContainer -> view.getChildAt(0)
                else -> view!!
            }

        fun rowView(id: Long): View? = renderer.viewForNode(id)?.takeIf { it.isAttachedToWindow }

        fun scrollBy(dp: Float) {
            main {
                val px = (dp * activity.resources.displayMetrics.density).toInt()
                scroller().scrollBy(0, px)
            }
            waitFrames(6)
        }

        /** The row under the scroller's top edge and how far into it the edge is. */
        fun topRow(): TopRow = main { topRowNow() }

        private fun topRowNow(): TopRow {
            val scroller = scroller()
            val origin = IntArray(2).also(scroller::getLocationInWindow)[1] + scroller.paddingTop
            return (ROW_BASE until ROW_BASE + ROWS).firstNotNullOf { id ->
                val view = rowView(id) ?: return@firstNotNullOf null
                if (!view.isShown || view.height == 0) return@firstNotNullOf null
                val top = IntArray(2).also(view::getLocationInWindow)[1] - origin
                if (top <= 0 && top + view.height > 0) TopRow(id, -top.toFloat() / view.height) else null
            }
        }

        fun assertTopRow(expected: TopRow) {
            waitFrames(8)
            main {
                val view = rowView(expected.id)
                assertTrue("$expected is mounted after the remount", view != null && view.isShown)
                val scroller = scroller()
                val origin = IntArray(2).also(scroller::getLocationInWindow)[1] + scroller.paddingTop
                val top = IntArray(2).also(view!!::getLocationInWindow)[1] - origin
                val wanted = -(expected.fraction * view.height)
                assertTrue(
                    "$expected must stay under the top edge: top $top px, wanted ${wanted.toInt()} px (now ${runCatching { topRowNow() }.getOrNull()})",
                    abs(top - wanted) <= TOLERANCE_PX,
                )
            }
        }

        /** Applies a real configuration change, then replays the tree the engine emits for it. */
        fun remount(fontScale: Float? = null, locale: Boolean = false) {
            if (fontScale != null) {
                if (originalFontScale == null) originalFontScale = shell("settings get system font_scale").trim()
                shell("settings put system font_scale $fontScale")
                assertTrue(
                    "font scale $fontScale never reached the activity",
                    waitFor { activity.resources.configuration.fontScale == fontScale },
                )
            }
            if (locale && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                localeChanged = true
                context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("de")
                assertTrue(
                    "the de locale never reached the activity",
                    waitFor { activity.resources.configuration.locales[0].language == "de" },
                )
            }
            assertFalse("the activity is updated in place", activity.isDestroyed)
            instrumentation.waitForIdleSync()
            main {
                renderer.resetTree(preserveViewState = true)
                try {
                    renderer.commit(listOf(tree(fontScale ?: 1f, locale)))
                } finally {
                    renderer.finishRemount()
                }
            }
            waitFrames(4)
        }
    }

    private fun withSurface(
        tree: (Float, Boolean) -> List<Mutation>,
        events: MutableList<Pair<Long, Int>>? = null,
        body: (Surface) -> Unit,
    ) {
        val activity = instrumentation.startActivitySync(
            Intent(context, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        lateinit var renderer: PamRenderer
        try {
            main {
                renderer = PamRenderer(activity, activity.host) { id, kind, _ -> events?.add(id to kind) }
                // As PamActivity.onConfigurationChanged: before the restarted IME can blur the input.
                activity.onConfigurationApplied = { renderer.prepareConfigurationRemount() }
                renderer.commit(listOf(tree(1f, false)))
            }
            instrumentation.waitForIdleSync()
            waitFrames(6)
            body(Surface(activity, renderer, tree))
        } finally {
            main {
                activity.onConfigurationApplied = null
                renderer.close()
                activity.finish()
            }
        }
    }

    // Rows of varied heights (dp); a locale change rewrites their text, so
    // some grow and some shrink.
    private fun rowHeight(index: Int, scale: Float, locale: Boolean = false): Float {
        val base = 48f + (index % 5) * 14f
        val translated = if (locale) base * (if (index % 3 == 0) 1.35f else 0.9f) else base
        return translated * scale
    }

    private fun rowsTop(index: Int, scale: Float, locale: Boolean = false): Float =
        (0 until index).sumOf { rowHeight(it, scale, locale).toDouble() }.toFloat()

    private fun rows(parent: Long, scale: Float, locale: Boolean, text: Boolean = true): List<Mutation> {
        val mutations = ArrayList<Mutation>()
        var y = 0f
        repeat(ROWS) { index ->
            val id = ROW_BASE + index
            val height = rowHeight(index, scale, locale)
            mutations += Mutation.Create(
                spec(id, parent, index, NodeKind.VIEW, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(if (index % 2 == 0) 0xFFEFEFEFL else 0xFFDADADAL))),
            )
            mutations += Mutation.Layout(id, Frame(0f, y, VIEWPORT_WIDTH, height))
            if (text) {
                mutations += Mutation.Create(
                    spec(TEXT_BASE + index, id, 0, NodeKind.TEXT, mapOf(PropKey.TEXT to PropValue.Text(if (locale) "Zeile $index" else "Row $index"))),
                )
                mutations += Mutation.Layout(TEXT_BASE + index, Frame(8f, y + 8f, 200f, height - 16f))
            }
            y += height
        }
        return mutations
    }

    private fun scrollTree(scale: Float, locale: Boolean): List<Mutation> {
        val content = rows(CONTENT_ID, scale, locale)
        val total = rowsTop(ROWS, scale, locale)
        return listOf(
            Mutation.Create(spec(SCREEN_ID, 0, 0, NodeKind.SCREEN)),
            Mutation.Create(spec(SCROLLER_ID, SCREEN_ID, 0, NodeKind.SCROLL)),
            // A layout-only content column, like a ScrollView's content container.
            Mutation.Create(spec(CONTENT_ID, SCROLLER_ID, 0, NodeKind.COLUMN)),
        ) + content + listOf(
            Mutation.Layout(SCREEN_ID, Frame(0f, 0f, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)),
            Mutation.Layout(SCROLLER_ID, Frame(0f, 0f, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)),
            Mutation.Layout(CONTENT_ID, Frame(0f, 0f, VIEWPORT_WIDTH, total)),
            Mutation.SetRoot(SCREEN_ID),
        )
    }

    private fun listTree(scale: Float, locale: Boolean): List<Mutation> = anyListTree(scale, locale, inverted = false)

    private fun anyListTree(scale: Float, locale: Boolean, inverted: Boolean): List<Mutation> {
        val properties = if (inverted) mapOf(PropKey.LIST_INVERTED to PropValue.Flag(true)) else emptyMap()
        return listOf(
            Mutation.Create(spec(SCREEN_ID, 0, 0, NodeKind.SCREEN)),
            Mutation.Create(spec(SCROLLER_ID, SCREEN_ID, 0, NodeKind.VIRTUAL_LIST, properties)),
            // Cells are plain rows: their frames are what the list lays out.
        ) + rows(SCROLLER_ID, scale, locale, text = false) + listOf(
            Mutation.Layout(SCREEN_ID, Frame(0f, 0f, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)),
            Mutation.Layout(SCROLLER_ID, Frame(0f, 0f, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)),
            Mutation.SetRoot(SCREEN_ID),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun inputTree(scale: Float, locale: Boolean): List<Mutation> = listOf(
        Mutation.Create(spec(SCREEN_ID, 0, 0, NodeKind.SCREEN)),
        Mutation.Create(
            spec(
                INPUT_ID,
                SCREEN_ID,
                0,
                NodeKind.INPUT,
                mapOf(
                    PropKey.VALUE to PropValue.Text("draft message"),
                    PropKey.ON_FOCUS to PropValue.Flag(true),
                    PropKey.ON_BLUR to PropValue.Flag(true),
                ),
            ),
        ),
        Mutation.Layout(SCREEN_ID, Frame(0f, 0f, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)),
        Mutation.Layout(INPUT_ID, Frame(16f, 80f, 300f, 44f * scale)),
        Mutation.SetRoot(SCREEN_ID),
    )

    /** Main thread. */
    private fun imeVisible(view: View): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true

    private fun spec(
        id: Long,
        parent: Long,
        index: Int,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ): NodeSpec = NodeSpec(id = id, parent = parent, index = index, kind = kind, properties = properties)

    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) {
            if (main(condition)) return true
            SystemClock.sleep(32)
        }
        return false
    }

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        assertTrue("timed out: $description", waitFor(condition = condition))
    }

    private fun waitFrames(count: Int) {
        repeat(count) {
            val latch = CountDownLatch(1)
            instrumentation.runOnMainSync { Choreographer.getInstance().postFrameCallback { latch.countDown() } }
            latch.await(1, TimeUnit.SECONDS)
        }
        instrumentation.waitForIdleSync()
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }

    private companion object {
        const val SCREEN_ID = 1L
        const val SCROLLER_ID = 2L
        const val CONTENT_ID = 3L
        const val INPUT_ID = 4L
        const val ROW_BASE = 100L
        const val TEXT_BASE = 1_000L
        const val ROWS = 80
        const val VIEWPORT_WIDTH = 320f
        const val VIEWPORT_HEIGHT = 420f
        const val TOLERANCE_PX = 3f
    }
}
