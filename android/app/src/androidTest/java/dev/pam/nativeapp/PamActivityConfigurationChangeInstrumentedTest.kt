package dev.pam.nativeapp

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Font scale and locale changes are declared `configChanges`: the same
 * PamActivity (and so the same module registry and plugin state) applies them
 * in place, and a running app rebuilds its views with re-measured text.
 */
@RunWith(AndroidJUnit4::class)
class PamActivityConfigurationChangeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as Application
    private val created = mutableListOf<PamActivity>()
    private var activity: PamActivity? = null
    private var originalFontScale: String? = null
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is PamActivity) synchronized(created) { created += activity }
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @Before
    fun launch() {
        app.registerActivityLifecycleCallbacks(callbacks)
        activity = instrumentation.startActivitySync(
            Intent(context, PamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamActivity
        // A bundled app draws its first frame; without one only the
        // Activity contract (no recreation) is checked.
        waitFor(10_000) { activity!!.firstFrameUptimeMillis > 0 }
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() {
        originalFontScale?.let { original ->
            shell(if (original == "null" || original.isBlank()) "settings delete system font_scale" else "settings put system font_scale $original")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
        activity?.let { launched -> instrumentation.runOnMainSync { launched.finish() } }
        app.unregisterActivityLifecycleCallbacks(callbacks)
    }

    @Test
    fun fontScaleChangeKeepsTheActivityAndRemeasuresText() {
        val first = activity!!
        val running = first.firstFrameUptimeMillis > 0
        val before = textSizes(first)
        val remountsBefore = onMain { first.configurationRemounts }
        originalFontScale = shell("settings get system font_scale").trim()
        val target = if (first.resources.configuration.fontScale >= 1.3f) 1f else 1.3f
        shell("settings put system font_scale $target")

        assertTrue(
            "font scale $target never reached the Activity",
            waitFor { first.resources.configuration.fontScale == target },
        )
        instrumentation.waitForIdleSync()
        assertNotRecreated(first)
        if (!running) return
        assertTrue("font scale change must remount the surface", waitFor { first.configurationRemounts > remountsBefore })
        val grew = target > 1f
        assertTrue(
            "text must be laid out again at font scale $target (before $before)",
            waitFor {
                val after = textSizes(first)
                before.any { (text, size) ->
                    after[text]?.let { if (grew) it > size else it < size } == true
                }
            },
        )
        assertEquals(ErrorOverlay.State.HIDDEN, onMain { first.errors.state })
    }

    @Test
    fun localeAndLayoutDirectionChangeKeepTheActivity() {
        assumeTrue("per-app locales need Android 13", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val first = activity!!
        val running = first.firstFrameUptimeMillis > 0
        val remountsBefore = onMain { first.configurationRemounts }
        context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("ar")

        assertTrue(
            "the ar locale never reached the Activity",
            waitFor { first.resources.configuration.locales[0].language == "ar" },
        )
        assertTrue(
            "the window must resolve RTL in place",
            waitFor { first.window.decorView.layoutDirection == View.LAYOUT_DIRECTION_RTL },
        )
        instrumentation.waitForIdleSync()
        assertNotRecreated(first)
        if (!running) return
        assertTrue("a locale change must remount the surface", waitFor { first.configurationRemounts > remountsBefore })
        assertEquals(ErrorOverlay.State.HIDDEN, onMain { first.errors.state })
    }

    private fun assertNotRecreated(first: PamActivity) {
        // Give a (wrong) relaunch time to show up before asserting it did not.
        SystemClock.sleep(500)
        instrumentation.waitForIdleSync()
        assertFalse("the Activity must not be destroyed", first.isDestroyed)
        assertEquals("the Activity must not be recreated", listOf(first), synchronized(created) { created.toList() })
    }

    private fun textSizes(activity: PamActivity): Map<String, Float> = onMain {
        val sizes = LinkedHashMap<String, Float>()
        fun visit(view: View) {
            when (view) {
                is TextView -> if (view.isShown && view.text.isNotBlank()) sizes.putIfAbsent(view.text.toString(), view.textSize)
                is ViewGroup -> for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(activity.rootHost)
        sizes
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < until) {
            if (onMain(condition)) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
}
