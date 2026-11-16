package dev.pam.nativeapp.render

import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native time primitives through the renderer: after one commit (one PHP
 * render) clocks, fills and reveals move on their own, and stop while the
 * view is off screen.
 */
@RunWith(AndroidJUnit4::class)
class PamNativeTimeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private lateinit var renderer: PamRenderer
    private var commits = 0

    @Before fun start() {
        PamMotionPolicy.reduceMotionOverride = false
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        main { renderer = PamRenderer(activity, activity.host) { _, _, _ -> } }
    }

    @After fun stop() {
        main {
            renderer.close()
            activity.finish()
        }
        PamMotionPolicy.reduceMotionOverride = null
    }

    @Test fun clockAdvancesWithoutRendersAndStopsWhileHidden() {
        val startedAt = System.currentTimeMillis() - 5_000
        commit(
            Mutation.Create(node(3, 2, NodeKind.TEXT, mapOf(
                PropKey.TEXT to PropValue.Text("00:00"),
                PropKey.CLOCK_STARTED_AT to PropValue.Integer(startedAt),
                PropKey.CLOCK_FORMAT to PropValue.Integer(PamClockFormat.PADDED_MINUTES_SECONDS.toLong()),
            ))),
            Mutation.Layout(3, Frame(0f, 0f, 200f, 40f)),
        )
        await("the clock shows the time since its start") { seconds(3) in 5..6 }
        val first = read { seconds(3) }
        await("the clock moves on its own", 3_500) { seconds(3) >= first + 2 }
        assertEquals("One render only: the PHP side never ticked", 1, commits)
        main { assertTrue(text(3).startsWith("00:")) }

        // Covered route / inactive tab: an ancestor is hidden.
        main { view(2).visibility = View.INVISIBLE }
        await("a pending tick finds it hidden and stops") { !binding(3).isTicking }
        val ticks = read { binding(3).ticks }
        val frozen = read { text(3) }
        SystemClock.sleep(2_200)
        main {
            assertEquals("No tick while hidden", ticks, binding(3).ticks)
            assertFalse(binding(3).isTicking)
            assertEquals("The hidden label does not change", frozen, text(3))
            assertTrue("It waits for the next frame showing it", binding(3).isWatching)
        }

        main { view(2).visibility = View.VISIBLE }
        await("visible again, it catches up with the wall clock at once") {
            seconds(3) >= ((System.currentTimeMillis() - startedAt) / 1_000).toInt() - 1 && binding(3).isTicking
        }
        assertEquals(1, commits)
    }

    @Test fun pausedClockStandsAtThePhpOffsetAndResumesOnUpdate() {
        val startedAt = System.currentTimeMillis()
        commit(
            Mutation.Create(node(3, 2, NodeKind.TEXT, mapOf(
                PropKey.TEXT to PropValue.Text("0:00"),
                PropKey.CLOCK_STARTED_AT to PropValue.Integer(startedAt - 60_000),
                PropKey.CLOCK_OFFSET_MS to PropValue.Integer(61_000),
                PropKey.CLOCK_RUNNING to PropValue.Flag(false),
            ))),
            Mutation.Layout(3, Frame(0f, 0f, 200f, 40f)),
        )
        await("a paused clock shows the offset") { text(3) == "1:01" }
        main { assertFalse("Nothing ticks for a paused clock", binding(3).isTicking) }
        // PHP resumes it: a new start, the offset kept, running again.
        commit(
            Mutation.Update(3, PropKey.CLOCK_STARTED_AT, PropValue.Integer(System.currentTimeMillis() - 2_000)),
            Mutation.Update(3, PropKey.CLOCK_RUNNING, null),
        )
        await("the resumed clock counts from the offset") { text(3) == "1:03" || text(3) == "1:04" }
        // PHP turns it off: the renderer's own text is back.
        commit(
            Mutation.Update(3, PropKey.CLOCK_STARTED_AT, null),
            Mutation.Update(3, PropKey.CLOCK_OFFSET_MS, null),
        )
        await("without a clock the text is the placeholder") { text(3) == "0:00" }
        main { assertTrue(PamTimeBinding.of(view(3)) == null) }
    }

    @Test fun progressFillsByTimeAndHoldsWhenPaused() {
        val startedAt = System.currentTimeMillis()
        commit(
            Mutation.Create(node(3, 2, NodeKind.VIEW, mapOf(
                PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFF0000),
                PropKey.PROGRESS_STARTED_AT to PropValue.Integer(startedAt),
                PropKey.PROGRESS_DURATION_MS to PropValue.Integer(2_000),
            ))),
            Mutation.Layout(3, Frame(0f, 0f, 300f, 8f)),
        )
        await("the fill grows frame by frame") { view(3).scaleX in 0.3f..0.8f }
        main { assertEquals(0f, view(3).pivotX, 0f) }
        await("and is full at the end", 3_000) { view(3).scaleX == 1f && !binding(3).isTicking }
        commit(
            Mutation.Update(3, PropKey.PROGRESS_OFFSET_MS, PropValue.Integer(500)),
            Mutation.Update(3, PropKey.PROGRESS_RUNNING, PropValue.Flag(false)),
        )
        await("paused, it shows the PHP offset") { view(3).scaleX == 0.25f }
        main { assertFalse(binding(3).isTicking) }
    }

    @Test fun revealTypesTheTextAndRestartsOnANewKey() {
        commit(
            Mutation.Create(node(3, 2, NodeKind.TEXT, mapOf(
                PropKey.TEXT to PropValue.Text("Hello world"),
                PropKey.REVEAL_CHAR_MS to PropValue.Integer(60),
                PropKey.REVEAL_KEY to PropValue.Text("a"),
            ))),
            Mutation.Layout(3, Frame(0f, 0f, 300f, 40f)),
        )
        val text = read { view(3) as PamTextView }
        await("typing has started") { text.revealEnd in 1..10 }
        val early = read { text.revealEnd }
        await("it advances one character at a time") { text.revealEnd > early || text.revealEnd == -1 }
        await("the whole text is shown at the end", 2_000) { text.revealEnd == -1 && !binding(3).isTicking }
        main { assertEquals("Hello world", text.text.toString()) }
        commit(Mutation.Update(3, PropKey.REVEAL_KEY, PropValue.Text("b")))
        await("a new key starts over") { text.revealEnd in 0..3 }
        assertEquals(2, commits)
    }

    private fun commit(vararg mutations: Mutation) {
        main {
            val first = commits == 0
            val all = mutableListOf<Mutation>()
            if (first) {
                all += Mutation.Create(node(1, 0, NodeKind.SCREEN))
                all += Mutation.Create(node(2, 1, NodeKind.VIEW, mapOf(PropKey.COLLAPSABLE to PropValue.Flag(false))))
            }
            all += mutations.filterIsInstance<Mutation.Create>()
            if (first) {
                all += Mutation.Layout(1, Frame(0f, 0f, 360f, 640f))
                all += Mutation.Layout(2, Frame(0f, 100f, 360f, 200f))
            }
            all += mutations.filterNot { it is Mutation.Create }
            if (first) all += Mutation.SetRoot(1)
            renderer.commit(listOf(all))
            commits++
        }
        instrumentation.waitForIdleSync()
    }

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ) = NodeSpec(id = id, parent = parent, index = 0, kind = kind, properties = properties)

    private fun view(id: Long): View = renderer.viewForNode(id) ?: error("No view for node $id")

    private fun binding(id: Long): PamTimeBinding = PamTimeBinding.of(view(id))!!

    private fun text(id: Long): String = (view(id) as PamTextView).text.toString()

    private fun seconds(id: Long): Int {
        val parts = text(id).split(':').map { it.toIntOrNull() ?: return -1 }
        return parts.fold(0) { total, part -> total * 60 + part }
    }

    private fun <T> read(block: () -> T): T {
        var value: T? = null
        main { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun await(message: String, timeoutMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (read(condition)) return
            SystemClock.sleep(20)
        }
        main { assertTrue("Timed out: $message", condition()) }
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
