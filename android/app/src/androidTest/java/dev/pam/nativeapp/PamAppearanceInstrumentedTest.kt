package dev.pam.nativeapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.system.Os
import android.view.ContextThemeWrapper
import android.view.WindowInsetsController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamAppearanceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private var activity: Activity? = null

    @Before
    fun resetPreference() {
        clearPreference()
    }

    @After
    fun tearDown() {
        activity?.let { launched -> instrumentation.runOnMainSync { launched.finish() } }
        activity = null
        clearPreference()
        PamAppearance.applyPlatformNightMode(context, PamAppearance.MODE_SYSTEM)
    }

    @Test
    fun preferenceIsWrittenDurablyForTheNextProcess() {
        assertEquals(PamAppearance.MODE_SYSTEM, PamAppearance.storedMode(context))
        assertTrue(PamAppearance.persist(context, PamAppearance.MODE_DARK))
        // commit() has completed, so a process killed now restarts dark.
        val file = File(context.applicationInfo.dataDir, "shared_prefs/${PamAppearance.PREFERENCES}.xml")
        assertTrue(file.readText().contains("name=\"${PamAppearance.KEY_MODE}\" value=\"3\""))
        assertEquals(PamAppearance.MODE_DARK, PamAppearance.storedMode(context))
        assertTrue(PamAppearance.isDark(context))
        assertTrue(PamAppearance.persist(context, PamAppearance.MODE_LIGHT))
        assertFalse(PamAppearance.isDark(context))
        assertEquals(PamAppearance.systemDark(context), PamAppearance.isDark(context, PamAppearance.MODE_SYSTEM))
    }

    @Test
    fun dayNightThemeResolvesConfiguredColoursPerScheme() {
        assertEquals(LIGHT_BACKGROUND, PamAppearance.color(context, R.color.pam_window_background, false))
        assertEquals(DARK_BACKGROUND, PamAppearance.color(context, R.color.pam_window_background, true))
        assertEquals(DARK_BACKGROUND, PamAppearance.color(context, R.color.pam_splash_background, true))
        assertTrue(PamAppearance.bool(context, R.bool.pam_light_status_bar, false))
        assertFalse(PamAppearance.bool(context, R.bool.pam_light_status_bar, true))

        for ((night, background, lightBars) in listOf(
            Triple(true, DARK_BACKGROUND, false),
            Triple(false, LIGHT_BACKGROUND, true),
        )) {
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val themed = ContextThemeWrapper(
                context.createConfigurationContext(configuration),
                R.style.Theme_PamNative,
            )
            val attributes = themed.obtainStyledAttributes(
                intArrayOf(android.R.attr.windowBackground, android.R.attr.windowLightStatusBar),
            )
            try {
                assertEquals(background, attributes.getColor(0, 0))
                assertEquals(lightBars, attributes.getBoolean(1, !lightBars))
            } finally {
                attributes.recycle()
            }
        }
    }

    @Test
    fun overrideConfigurationIsOnlyUsedBelowAndroid12() {
        val override = PamAppearance.overrideConfiguration(context, PamAppearance.MODE_DARK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertNull(override)
        } else {
            assertNotNull(override)
            assertEquals(
                Configuration.UI_MODE_NIGHT_YES,
                override!!.uiMode and Configuration.UI_MODE_NIGHT_MASK,
            )
        }
        assertNull(PamAppearance.overrideConfiguration(context, PamAppearance.MODE_SYSTEM))
    }

    @Test
    fun persistedDarkOverridePaintsTheWindowAndExportsBootMetricsBeforeContent() {
        assertTrue(PamAppearance.persist(context, PamAppearance.MODE_DARK))
        val launched = instrumentation.startActivitySync(
            Intent(context, PamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamActivity
        activity = launched
        instrumentation.runOnMainSync {
            assertEquals(DARK_BACKGROUND, (launched.window.decorView.background as ColorDrawable).color)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val appearance = launched.window.insetsController?.systemBarsAppearance ?: -1
                assertEquals(0, appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
            }
        }
        assertEquals("3", Os.getenv("PAM_APPEARANCE_MODE"))
        assertEquals("1", Os.getenv("PAM_SYSTEM_DARK"))
        // The activity configuration (theme, native widgets) is night as well:
        // via the override configuration below Android 12, or the persisted
        // per-app night mode delivered as a handled uiMode change on 12+.
        assertTrue(waitUntil { isNight(launched.resources.configuration) })
    }

    @Test
    fun runtimeChangeRestylesWithoutRecreatingTheActivity() {
        assertTrue(PamAppearance.persist(context, PamAppearance.MODE_LIGHT))
        val launched = instrumentation.startActivitySync(
            Intent(context, PamActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamActivity
        activity = launched
        instrumentation.runOnMainSync {
            assertEquals(LIGHT_BACKGROUND, (launched.window.decorView.background as ColorDrawable).color)
            assertTrue(launched.setAppearanceMode(PamAppearance.MODE_DARK))
            assertEquals(DARK_BACKGROUND, (launched.window.decorView.background as ColorDrawable).color)
        }
        assertTrue(waitUntil { isNight(launched.resources.configuration) || Build.VERSION.SDK_INT < Build.VERSION_CODES.S })
        instrumentation.waitForIdleSync()
        assertFalse(launched.isDestroyed)
        assertFalse(launched.isFinishing)
        assertEquals(PamAppearance.MODE_DARK, PamAppearance.storedMode(context))
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return true
            SystemClock.sleep(50)
        }
        return false
    }

    private fun isNight(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun clearPreference() {
        context.getSharedPreferences(PamAppearance.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private companion object {
        val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()
        val DARK_BACKGROUND = 0xFF121212.toInt()
    }
}
