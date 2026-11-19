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
import org.junit.AfterClass
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
    private var localeChanged = false

    @After
    fun restoreLocaleAndKeyboard() {
        if (localeChanged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
        instrumentation.waitForIdleSync()
        awaitKeyboardDown()
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
    fun aPagingScrollViewKeepsItsPageWhenTheFontScaleGrows() = withSurface(::pagerTree) { surface ->
        val pager = { surface.renderer.viewForNode(SCROLLER_ID) as PamScrollContainer }
        val pageWidth = main { pager().primaryViewportPixels() }
        main { pager().getChildAt(0).scrollBy(pageWidth * 3, 0) }
        waitFrames(6)
        assertEquals("on page 3", pageWidth * 3, main { pager().snapshotOffsetPixels().first })
        surface.remount(fontScale = 1.3f)
        waitFrames(8)
        assertEquals("still on page 3", pageWidth * 3, main { pager().snapshotOffsetPixels().first })
    }

    /*
     * An Activity that is still recreated (an undeclared configuration
     * change, the system reclaiming it) builds a new renderer: PamRuntime
     * hands it the old surface's anchors (detach -> attach), and its first
     * remount puts the reader back on the same row, re-measured.
     */

    @Test
    fun aVirtualizedListKeepsItsTopRowAcrossAnActivityRecreation() = withSurface(::listTree) { surface ->
        surface.scrollBy(rowsTop(40, 1f) + rowHeight(40, 1f) * 0.3f)
        val before = surface.topRow()
        surface.recreate(fontScale = 1.3f)
        surface.assertTopRow(before)
    }

    @Test
    fun anInvertedListScrolledUpKeepsItsTopRowAcrossAnActivityRecreation() =
        withSurface({ scale, locale -> anyListTree(scale, locale, inverted = true) }) { surface ->
            surface.scrollBy(-(rowsTop(30, 1f) + rowHeight(30, 1f) * 0.4f))
            val before = surface.topRow()
            assertTrue("scrolled into the history ($before)", before.id >= ROW_BASE + 20)
            surface.recreate(fontScale = 1.3f)
            surface.assertTopRow(before)
            main { assertTrue("not pulled back to the newest row", surface.scroller().canScrollVertically(1)) }
        }

    @Test
    fun aScrollViewKeepsItsTopRowAcrossAnActivityRecreation() = withSurface(::scrollTree) { surface ->
        surface.scrollBy(rowsTop(33, 1f) + rowHeight(33, 1f) * 0.6f)
        val before = surface.topRow()
        surface.recreate(fontScale = 1.3f)
        surface.assertTopRow(before)
    }

    @Test
    fun aRemountWithoutViewStateStartsAtTheTop() = withSurface(::listTree) { surface ->
        // What PamRuntime does after App::reload(): a cold start's mount.
        surface.scrollBy(rowsTop(40, 1f))
        main {
            surface.renderer.resetTree(preserveViewState = false)
            try {
                surface.renderer.commit(listOf(listTree(1f, false)))
            } finally {
                surface.renderer.finishRemount()
            }
        }
        waitFrames(6)
        main { assertFalse("back at the top", surface.scroller().canScrollVertically(-1)) }
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
        var activity: PamTestActivity,
        var renderer: PamRenderer,
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

        /**
         * As PamRuntime.detach/attach around a recreated Activity: the old
         * renderer's anchors go to a new renderer in a new Activity, built
         * for [fontScale], whose first remount replays the re-measured tree.
         */
        fun recreate(fontScale: Float) {
            val state = main { renderer.captureRetainedViewState() }
            assertTrue("the old surface had a place to keep", state != null)
            applyFontScale(fontScale)
            main {
                renderer.close()
                activity.finish()
            }
            activity = instrumentation.startActivitySync(
                Intent(context, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as PamTestActivity
            assertEquals(fontScale, activity.resources.configuration.fontScale)
            main {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.adoptRetainedViewState(state)
                renderer.resetTree(preserveViewState = true)
                try {
                    renderer.commit(listOf(tree(fontScale, false)))
                } finally {
                    renderer.finishRemount()
                }
            }
            instrumentation.waitForIdleSync()
            waitFrames(4)
        }

        private fun applyFontScale(fontScale: Float) {
            // Raised once for the whole class (each change restarts the
            // IME); a later test finds it applied and remounts by hand.
            if (originalFontScale == null) originalFontScale = shell("settings get system font_scale").trim()
            if (activity.resources.configuration.fontScale != fontScale) {
                shell("settings put system font_scale $fontScale")
            }
            assertTrue(
                "font scale $fontScale never reached the activity",
                waitFor { activity.resources.configuration.fontScale == fontScale },
            )
        }

        /** Applies a real configuration change, then replays the tree the engine emits for it. */
        fun remount(fontScale: Float? = null, locale: Boolean = false) {
            if (fontScale != null) applyFontScale(fontScale)
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
        var surface: Surface? = null
        try {
            main {
                renderer = PamRenderer(activity, activity.host) { id, kind, _ -> events?.add(id to kind) }
                // As PamActivity.onConfigurationChanged: before the restarted IME can blur the input.
                activity.onConfigurationApplied = { renderer.prepareConfigurationRemount() }
                renderer.commit(listOf(tree(1f, false)))
            }
            instrumentation.waitForIdleSync()
            waitFrames(6)
            surface = Surface(activity, renderer, tree)
            body(surface)
        } finally {
            main {
                activity.onConfigurationApplied = null
                renderer.close()
                activity.finish()
                surface?.takeIf { it.activity !== activity }?.let {
                    it.renderer.close()
                    it.activity.finish()
                }
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

    /** A horizontal paging ScrollView: page-wide pages, each a column of text rows. */
    private fun pagerTree(scale: Float, locale: Boolean): List<Mutation> {
        val mutations = ArrayList<Mutation>()
        mutations += Mutation.Create(spec(SCREEN_ID, 0, 0, NodeKind.SCREEN))
        mutations += Mutation.Create(
            spec(
                SCROLLER_ID,
                SCREEN_ID,
                0,
                NodeKind.SCROLL,
                mapOf(PropKey.SCROLL_HORIZONTAL to PropValue.Flag(true), PropKey.SCROLL_PAGING_ENABLED to PropValue.Flag(true)),
            ),
        )
        mutations += Mutation.Create(spec(CONTENT_ID, SCROLLER_ID, 0, NodeKind.ROW))
        repeat(PAGES) { page ->
            val id = ROW_BASE + page
            val x = page * VIEWPORT_WIDTH
            mutations += Mutation.Create(
                spec(id, CONTENT_ID, page, NodeKind.VIEW, mapOf(PropKey.BACKGROUND_COLOR to PropValue.Integer(if (page % 2 == 0) 0xFFEFEFEFL else 0xFFDADADAL))),
            )
            mutations += Mutation.Layout(id, Frame(x, 0f, VIEWPORT_WIDTH, VIEWPORT_HEIGHT))
            repeat(4) { line ->
                val text = TEXT_BASE + page * 10 + line
                val height = 40f * scale
                mutations += Mutation.Create(
                    spec(text, id, line, NodeKind.TEXT, mapOf(PropKey.TEXT to PropValue.Text(if (locale) "Seite $page" else "Page $page"))),
                )
                mutations += Mutation.Layout(text, Frame(x, line * height, VIEWPORT_WIDTH, height))
            }
        }
        mutations += Mutation.Layout(SCREEN_ID, Frame(0f, 0f, VIEWPORT_WIDTH, VIEWPORT_HEIGHT))
        mutations += Mutation.Layout(SCROLLER_ID, Frame(0f, 0f, VIEWPORT_WIDTH, VIEWPORT_HEIGHT))
        mutations += Mutation.Layout(CONTENT_ID, Frame(0f, 0f, VIEWPORT_WIDTH * PAGES, VIEWPORT_HEIGHT))
        mutations += Mutation.SetRoot(SCREEN_ID)
        return mutations
    }

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

    private fun shell(command: String): String = shell(instrumentation, command)

    private fun awaitKeyboardDown() = awaitKeyboardDown(instrumentation)

    companion object {
        /** The system font scale before the class raised it ("null" when unset). */
        private var originalFontScale: String? = null

        /**
         * Leaves the device as found before the next test class runs: the
         * font scale is restored and applied, the IME (restarted by that
         * change) has settled and the keyboard is down. Keyboard contracts
         * that run later met an IME still restarting and dropped their first
         * show request.
         */
        @JvmStatic
        @AfterClass
        fun restoreFontScale() {
            val original = originalFontScale ?: return
            originalFontScale = null
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val unset = original == "null" || original.isBlank()
            shell(instrumentation, if (unset) "settings delete system font_scale" else "settings put system font_scale $original")
            val expected = if (unset) 1f else original.toFloatOrNull() ?: 1f
            val until = SystemClock.uptimeMillis() + 10_000
            while (
                SystemClock.uptimeMillis() < until &&
                instrumentation.targetContext.applicationContext.resources.configuration.fontScale != expected
            ) {
                SystemClock.sleep(50)
            }
            // The IME process restarts for the change: give it time to bind
            // again before the next class asks for the keyboard.
            SystemClock.sleep(IME_RESTART_SETTLE_MS)
            awaitKeyboardDown(instrumentation)
        }

        private fun awaitKeyboardDown(instrumentation: android.app.Instrumentation) {
            val until = SystemClock.uptimeMillis() + 5_000
            while (
                SystemClock.uptimeMillis() < until &&
                shell(instrumentation, "dumpsys input_method").contains("mInputShown=true")
            ) {
                SystemClock.sleep(100)
            }
        }

        private fun shell(instrumentation: android.app.Instrumentation, command: String): String =
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader()
                .use { it.readText() }

        private const val IME_RESTART_SETTLE_MS = 1_500L
        private const val SCREEN_ID = 1L
        private const val SCROLLER_ID = 2L
        private const val CONTENT_ID = 3L
        private const val INPUT_ID = 4L
        private const val ROW_BASE = 100L
        private const val TEXT_BASE = 1_000L
        private const val ROWS = 80
        private const val PAGES = 6
        private const val VIEWPORT_WIDTH = 320f
        private const val VIEWPORT_HEIGHT = 420f
        private const val TOLERANCE_PX = 3f
    }
}
