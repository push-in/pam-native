package dev.pam.nativeapp.render

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.pam.nativeapp.CapabilityTestActivity
import dev.pam.nativeapp.PamTestActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A closed Modal keeps its Dialog mounted and only hides it. After the
 * activity is stopped by another activity (Play Services' location settings
 * resolution, a share sheet) and comes back, Android 12 re-shows the hidden
 * dialog's surface as an input target: a GONE, fill-parent window that
 * swallowed every touch meant for the screen below. A hidden modal window
 * must never be touchable or focusable, and must be fully interactive again
 * when the same mounted modal is reopened.
 */
@RunWith(AndroidJUnit4::class)
class PamModalHiddenWindowInputInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private lateinit var below: View
    private lateinit var inside: View
    private var modal: PamModalHost? = null
    private var belowTaps = 0
    private var insideTaps = 0

    @Before
    fun launch() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        instrumentation.runOnMainSync {
            below = View(activity).apply { setOnClickListener { belowTaps++ } }
            activity.host.addView(
                below,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        instrumentation.waitForIdleSync()
    }

    @After
    fun finish() {
        instrumentation.runOnMainSync {
            modal?.close()
            activity.finish()
        }
    }

    @Test
    fun hiddenSheetNeverInterceptsTouchesAfterTheActivityIsStoppedAndReopens() {
        val dialog = present()
        instrumentation.runOnMainSync { modal!!.setVisible(false) }
        waitUntil("the sheet hides") { !modal!!.isPresented() }

        // Another activity covers and stops the PAM activity, then closes.
        val cover = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, CapabilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        waitUntil("the PAM activity stops") { stageOf(activity) == Stage.STOPPED }
        instrumentation.runOnMainSync { cover.finish() }
        waitUntil("the PAM activity resumes") {
            stageOf(activity) == Stage.RESUMED && activity.hasWindowFocus()
        }

        instrumentation.runOnMainSync {
            val flags = dialog.window!!.attributes.flags
            assertTrue(
                "a hidden modal window must not be touchable",
                flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0,
            )
            assertTrue(
                "a hidden modal window must not be focusable",
                flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0,
            )
        }
        tapUntil(below, "the tap reaches the screen below the hidden sheet") { belowTaps > 0 }

        // Reopening the same mounted sheet makes it interactive again.
        instrumentation.runOnMainSync { modal!!.setVisible(true) }
        waitUntil("the sheet reopens") { modal!!.isPresented() && inside.isShown && inside.height > 0 }
        instrumentation.runOnMainSync {
            val flags = dialog.window!!.attributes.flags
            assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            assertEquals(0, flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        val belowBefore = belowTaps
        tapUntil(inside, "the tap reaches the reopened sheet") { insideTaps > 0 }
        assertEquals(belowBefore, belowTaps)
    }

    private fun present(): Dialog {
        instrumentation.runOnMainSync {
            val host = PamModalHost(activity)
            host.setPresentation(3)
            host.setAnimationType(1)
            inside = View(activity).apply { setOnClickListener { insideTaps++ } }
            host.insert(inside, 0)
            inside.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600)
            activity.host.addView(
                host,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            host.setVisible(true)
            modal = host
        }
        waitUntil("the sheet shows") { modal!!.isPresented() && inside.isShown && inside.height > 0 }
        var dialog: Dialog? = null
        instrumentation.runOnMainSync {
            dialog = PamModalHost::class.java.getDeclaredField("dialog")
                .apply { isAccessible = true }
                .get(modal) as Dialog?
        }
        return checkNotNull(dialog) { "the modal never showed its Dialog" }
    }

    /**
     * Taps until [landed]: the window manager may drop a tap injected while
     * the activity's return transition is still running. A window that
     * swallows touches keeps swallowing every retry.
     */
    private fun tapUntil(view: View, what: String, landed: () -> Boolean) {
        repeat(TAP_ATTEMPTS) {
            tapCenterOf(view)
            val deadline = SystemClock.uptimeMillis() + 500
            while (SystemClock.uptimeMillis() < deadline) {
                var met = false
                instrumentation.runOnMainSync { met = landed() }
                if (met) return
                SystemClock.sleep(50)
            }
        }
        throw AssertionError("timed out waiting until $what\n${windowDump()}")
    }

    /** A real screen tap routed by the window manager, not a view dispatch. */
    private fun tapCenterOf(view: View) {
        val location = IntArray(2)
        instrumentation.runOnMainSync { view.getLocationOnScreen(location) }
        val x = location[0] + view.width / 2f
        val y = location[1] + view.height / 2f
        val down = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
    }

    /** Main thread only (called from [waitUntil]'s condition). */
    private fun stageOf(target: Activity): Stage? =
        ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(target)

    private fun windowDump(): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand("dumpsys window windows")
        val text = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        return text.lines().filter {
            it.contains("Window #") || it.contains("mCurrentFocus") || it.contains("mViewVisibility") ||
                it.contains(" fl=") || it.contains("mHasSurface")
        }.joinToString("\n")
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var met = false
            instrumentation.runOnMainSync { met = condition() }
            if (met) return
            SystemClock.sleep(50)
        }
        throw AssertionError("timed out waiting until $what")
    }

    private companion object {
        const val TAP_ATTEMPTS = 6
    }
}
