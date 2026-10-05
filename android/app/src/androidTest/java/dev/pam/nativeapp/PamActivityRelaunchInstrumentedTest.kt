package dev.pam.nativeapp

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The embedded PHP runtime outlives its Activity (the process stays alive
 * after Back at the root). Relaunching must re-attach to that runtime and
 * render immediately instead of leaving the bare window background.
 */
@RunWith(AndroidJUnit4::class)
class PamActivityRelaunchInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val launched = mutableListOf<PamActivity>()

    @Before
    fun requireApplicationBundle() {
        val bundled = runCatching { context.assets.list("pam").orEmpty() }
            .getOrDefault(emptyArray())
        assumeTrue("needs an application bundle in assets", bundled.isNotEmpty())
    }

    @After
    fun tearDown() {
        launched.forEach { activity -> instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test
    fun relaunchAfterTheRootActivityFinishedRendersWithinOneSecond() {
        val first = launch()
        assertTrue("cold start never rendered", waitUntil(COLD_START_MS) { first.firstFrameUptimeMillis > 0 })

        // Back at the root on Android 11 and below (and any system finish):
        // the Activity is destroyed while the process and PHP stay alive.
        instrumentation.runOnMainSync { first.finish() }
        assertTrue("first activity was not destroyed", waitUntil(COLD_START_MS) { first.isDestroyed })

        val started = SystemClock.uptimeMillis()
        val second = launch()
        assertTrue(
            "relaunch did not render within ${RELAUNCH_BUDGET_MS}ms",
            waitUntil(RELAUNCH_BUDGET_MS) { second.firstFrameUptimeMillis > 0 && second.hasMountedContent() },
        )
        assertTrue(second.firstFrameUptimeMillis - started <= RELAUNCH_BUDGET_MS)
    }

    @Test
    fun relaunchAfterSystemBackAtTheRootRendersWithinOneSecond() {
        val first = launch()
        assertTrue("cold start never rendered", waitUntil(COLD_START_MS) { first.firstFrameUptimeMillis > 0 })
        instrumentation.waitForIdleSync()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        // Android 12+ moves the root task back (React Native semantics); older
        // releases finish the Activity. Either way the app must come back.
        waitUntil(COLD_START_MS) { first.isDestroyed || !first.hasWindowFocus() }

        val started = SystemClock.uptimeMillis()
        val rendered = if (first.isDestroyed) {
            val second = launch()
            waitUntil(RELAUNCH_BUDGET_MS) { second.hasMountedContent() }
        } else {
            // singleTask brings the backgrounded instance forward (onNewIntent).
            context.startActivity(
                Intent(context, PamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            waitUntil(RELAUNCH_BUDGET_MS) { first.hasWindowFocus() && first.hasMountedContent() }
        }
        assertTrue("relaunch did not render within ${RELAUNCH_BUDGET_MS}ms", rendered)
        assertTrue(SystemClock.uptimeMillis() - started <= RELAUNCH_BUDGET_MS + POLL_MS)
    }

    private fun launch(): PamActivity {
        val activity = instrumentation.startActivitySync(
            Intent(context, PamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamActivity
        launched += activity
        return activity
    }

    private fun PamActivity.hasMountedContent(): Boolean =
        !isDestroyed && rootHost.isAttachedToWindow && rootHost.childCount > 0

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return true
            SystemClock.sleep(POLL_MS)
        }
        return false
    }

    private companion object {
        const val COLD_START_MS = 15_000L
        const val RELAUNCH_BUDGET_MS = 1_000L
        const val POLL_MS = 16L
    }
}
