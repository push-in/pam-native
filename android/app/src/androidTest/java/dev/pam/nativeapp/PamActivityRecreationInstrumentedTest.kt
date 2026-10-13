package dev.pam.nativeapp

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run against a bundled app; expectedText identifies its settled screen. */
@RunWith(AndroidJUnit4::class)
class PamActivityRecreationInstrumentedTest {
    @Test
    fun recreatedActivityRestoresVisibleContentAndKeepsEventSubscriptions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val expected = InstrumentationRegistry.getArguments().getString("expectedText") ?: ""
        org.junit.Assume.assumeTrue("requires a bundled app and expectedText", expected.isNotBlank())
        val app = context.applicationContext as Application
        var latest: PamActivity? = null
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) { if (activity is PamActivity) latest = activity }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        fun texts(view: View): List<String> = when (view) {
            is TextView -> if (view.isShown) listOf(view.text.toString()) else emptyList()
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }
        fun waitFor(condition: () -> Boolean): Boolean {
            val until = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < until) {
                var ok = false
                instrumentation.runOnMainSync { ok = condition() }
                if (ok) return true
                SystemClock.sleep(50)
            }
            return false
        }
        app.registerActivityLifecycleCallbacks(callback)
        try {
            val first = instrumentation.startActivitySync(Intent(context, PamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as PamActivity
            assertTrue("initial screen missing $expected", waitFor { texts(first.rootHost).any { it.contains(expected) } })
            instrumentation.runOnMainSync { first.recreate() }
            assertTrue("recreated screen missing $expected", waitFor {
                val next = latest
                next != null && next !== first && !next.isDestroyed && next.firstFrameUptimeMillis > 0 && texts(next.rootHost).any { it.contains(expected) }
            })
            // Give old-registry asynchronous completions a chance to reach PHP.
            SystemClock.sleep(500)
            instrumentation.runOnMainSync {
                assertEquals("Recreation must not terminate retained subscriptions", ErrorOverlay.State.HIDDEN, latest!!.errors.state)
                assertTrue(latest!!.rootHost.childCount > 0)
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(callback)
            // The instrumentation runner owns Activity teardown; persistent QA data is retained.
        }
    }
}
