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
            assertEquals(Color.TRANSPARENT, navigationBand(window))
            assertTrue(window.isNavigationBarContrastEnforced)
            assertEquals(
                expectedLightIcons(),
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars,
            )
        }
    }

    @Test
    fun nonTranslucentModalFollowsThePlatformWindowPolicy() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val window = present(translucent = false).window!!
        instrumentation.runOnMainSync {
            val dark = PamAppearance.isDark(activity)
            // Android 15+ with target 35+ enforces edge-to-edge even when
            // the app requests fitted bars. Earlier devices retain fitting.
            val forcedEdgeToEdge = Build.VERSION.SDK_INT >= 35 && activity.applicationInfo.targetSdkVersion >= 35
            assertEquals(
                if (forcedEdgeToEdge) Color.TRANSPARENT else PamAppearance.color(activity, R.color.pam_window_background, dark),
                navigationBand(window),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // The requested fitted policy still opts out of contrast;
                // the platform only forces its navigation color transparent.
                assertEquals(false, window.isNavigationBarContrastEnforced)
            }
            assertEquals(
                expectedLightIcons(),
                WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars,
            )
        }
    }

    @Test
    fun barColoursArePaintedWithoutTheDeprecatedWindowSetters() {
        val window = present(translucent = false).window!!
        instrumentation.runOnMainSync {
            @Suppress("DEPRECATION")
            assertEquals("the Dialog bar stays transparent", Color.TRANSPARENT, window.navigationBarColor)
            @Suppress("DEPRECATION")
            assertEquals(Color.TRANSPARENT, window.statusBarColor)
            modal!!.applyStatusBar(color = Color.BLUE, useDarkIcons = false, hidden = false, translucent = false)
            val bands = PamSystemBarBands.of(window)
            if (Build.VERSION.SDK_INT >= 35) {
                assertEquals("Android 15+ draws no bar colours", null, bands)
            } else {
                assertEquals(Color.BLUE, bands!!.statusColor)
                assertEquals(
                    PamAppearance.color(activity, R.color.pam_window_background, PamAppearance.isDark(activity)),
                    bands.navigationColor,
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 35) return
        // The bands sit in the decor overlay, above the content, where the bars are.
        val deadline = SystemClock.uptimeMillis() + 2_000
        var insets = listOf(0, 0, 0, 0)
        while (SystemClock.uptimeMillis() < deadline && insets[0] == 0) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { insets = PamSystemBarBands.of(window)!!.barInsets }
        }
        assertTrue("status bar band height $insets", insets[0] > 0)
        instrumentation.runOnMainSync {
            val decor = window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
            decor.draw(android.graphics.Canvas(bitmap))
            assertEquals(Color.BLUE, bitmap.getPixel(decor.width / 2, insets[0] / 2))
            bitmap.recycle()
        }
    }

    private fun navigationBand(window: android.view.Window): Int =
        PamSystemBarBands.of(window)?.navigationColor ?: Color.TRANSPARENT

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
