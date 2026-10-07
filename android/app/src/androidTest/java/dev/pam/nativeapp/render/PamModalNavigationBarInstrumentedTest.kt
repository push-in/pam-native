package dev.pam.nativeapp.render

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamAppearance
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Modal's Dialog window draws the navigation bar the way React Native's
 * Modal does: icons for the app's light/dark appearance, a transparent
 * system-scrimmed bar when edge to edge, the app window background when
 * fitted - never the Dialog theme's black bar with light icons.
 */
@RunWith(AndroidJUnit4::class)
class PamModalNavigationBarInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: PamTestActivity
    private var modal: PamModalHost? = null

    @Before
    fun launch() {
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
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
    fun translucentModalFollowsTheAppAppearanceWithAScrimmedBar() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val window = present(translucent = true).window!!
        instrumentation.runOnMainSync {
            assertEquals(Color.TRANSPARENT, window.navigationBarColor)
            assertTrue(window.isNavigationBarContrastEnforced)
            assertEquals(
                expectedLightIcons(),
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars,
            )
        }
    }

    @Test
    fun fittedModalPaintsTheAppWindowBackground() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val window = present(translucent = false).window!!
        instrumentation.runOnMainSync {
            val dark = PamAppearance.isDark(activity)
            assertEquals(
                PamAppearance.color(activity, R.color.pam_window_background, dark),
                window.navigationBarColor,
            )
            assertEquals(
                expectedLightIcons(),
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars,
            )
        }
    }

    private fun expectedLightIcons(): Boolean =
        PamAppearance.bool(activity, R.bool.pam_light_navigation_bar, PamAppearance.isDark(activity))

    private fun present(translucent: Boolean): Dialog {
        instrumentation.runOnMainSync {
            val host = PamModalHost(activity)
            host.setNavigationBarTranslucent(translucent)
            host.setStatusBarTranslucent(translucent)
            activity.host.addView(
                host,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            host.setVisible(true)
            modal = host
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            var shown = false
            instrumentation.runOnMainSync { shown = modal?.isPresented() == true }
            if (shown) break
            SystemClock.sleep(50)
        }
        var dialog: Dialog? = null
        instrumentation.runOnMainSync {
            dialog = PamModalHost::class.java.getDeclaredField("dialog")
                .apply { isAccessible = true }
                .get(modal) as Dialog?
        }
        return checkNotNull(dialog) { "the modal never showed its Dialog" }
    }
}
