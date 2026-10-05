package dev.pam.nativeapp

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.window.OnBackInvokedCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ErrorOverlayInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var launched: android.app.Activity? = null

    @After
    fun tearDown() {
        launched?.let { activity -> instrumentation.runOnMainSync { activity.finish() } }
        launched = null
    }

    @Test
    fun parsesStructuredReportsWithAppFramesFirstAndShortPaths() {
        val report = RuntimeErrorReport.parse(payload(fatal = false))
        assertEquals("RuntimeException", report.shortType)
        assertEquals("Native module value is too large", report.message)
        assertEquals("app/Screens/Chat.php", report.file)
        assertEquals("event", report.phase)
        assertFalse(report.fatal)
        assertEquals(1, report.appFrame)
        assertEquals("app/Screens/Chat.php:42", report.frames[1].location)
        assertEquals(RuntimeErrorReport.KIND_FRAMEWORK, report.frames[0].kind)
        assertTrue(report.copyText().contains("> ") && report.copyText().contains("#1 app/Screens/Chat.php:42"))

        val legacy = RuntimeErrorReport.parse(
            "PAMERR1\n" + JSONObject()
                .put("version", 1)
                .put("type", "Pam\\Native\\TemplateException")
                .put("message", "Boom")
                .put("file", "/data/user/0/dev.zechat/files/pam/releases/abc/app/View.php")
                .put("line", 3)
                .put(
                    "trace",
                    "#0 /data/user/0/dev.zechat/files/pam/releases/abc/vendor/pushinbr/pam-native/src/App.php(9): Pam\\Native\\App::run()\n" +
                        "#1 /data/user/0/dev.zechat/files/pam/releases/abc/index.php(4): boot()\n#2 {main}",
                ),
        )
        assertEquals("app/View.php", legacy.file)
        assertTrue("Version 1 payloads keep the full-screen treatment", legacy.fatal)
        assertEquals("vendor/pushinbr/pam-native/src/App.php:9", legacy.frames[0].location)
        assertEquals(RuntimeErrorReport.KIND_FRAMEWORK, legacy.frames[0].kind)
        assertEquals(1, legacy.appFrame)

        val native = RuntimeErrorReport.parse("Cannot render native batch")
        assertTrue(native.fatal)
        assertEquals("Cannot render native batch", native.message)
    }

    @Test
    fun nonFatalErrorShowsToastThatExpandsIntoReadableInspector() {
        val overlay = overlay(developerMode = true)
        onMain {
            overlay.showError(payload(fatal = false))
            assertEquals(ErrorOverlay.State.TOAST, overlay.state)
            assertEquals(View.VISIBLE, overlay.toast.visibility)
            assertEquals(View.GONE, overlay.panel.visibility)
            overlay.toast.performClick()
            assertEquals(ErrorOverlay.State.PANEL, overlay.state)
            assertEquals(View.VISIBLE, overlay.panel.visibility)
            val texts = visibleTexts(overlay.panel)
            assertTrue(texts.any { it == "Native module value is too large" })
            assertTrue(texts.any { it == "app/Screens/Chat.php:42" })
            assertTrue(texts.any { it.contains("framework frame") })
            assertTrue(texts.any { it.contains("throw new RuntimeException") })
        }
    }

    @Test
    fun dismissClosesTheOverlayAndDoesNotReshowTheSameError() {
        val overlay = overlay(developerMode = true)
        onMain {
            overlay.showError(payload(fatal = true))
            assertEquals(ErrorOverlay.State.PANEL, overlay.state)
            overlay.dismissButton.performClick()
            assertEquals(ErrorOverlay.State.HIDDEN, overlay.state)
            assertEquals(View.GONE, overlay.visibility)
            overlay.showError(payload(fatal = true))
            assertEquals("A dismissed error must not re-open the overlay", ErrorOverlay.State.HIDDEN, overlay.state)
            assertEquals(1, overlay.suppressedCount)
            overlay.clearError()
            overlay.showError(payload(fatal = true))
            assertEquals(ErrorOverlay.State.PANEL, overlay.state)
        }
    }

    @Test
    fun queuedErrorsNavigateAndDismissOneAtATime() {
        val overlay = overlay(developerMode = true)
        onMain {
            overlay.showError(payload(fatal = false, message = "first"))
            overlay.showError(payload(fatal = false, message = "second"))
            overlay.showError(payload(fatal = false, message = "second"))
            overlay.showError(payload(fatal = false, message = "third"))
            assertEquals(3, overlay.entryCount)
            overlay.expand()
            assertEquals("third", overlay.currentReport?.message)
            assertTrue(visibleTexts(overlay.panel).contains("3 / 3"))
            overlay.select(1)
            assertEquals("second", overlay.currentReport?.message)
            assertTrue(visibleTexts(overlay.panel).any { it.contains("×2") })
            overlay.dismissCurrent()
            assertEquals(2, overlay.entryCount)
            assertEquals("third", overlay.currentReport?.message)
            overlay.dismissCurrent()
            assertEquals("first", overlay.currentReport?.message)
            overlay.dismissCurrent()
            assertEquals(ErrorOverlay.State.HIDDEN, overlay.state)
        }
    }

    @Test
    fun toastNeverSwallowsTouchesOutsideItself() {
        val overlay = overlay(developerMode = true)
        onMain {
            overlay.showError(payload(fatal = false))
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertFalse("Touches above the toast must reach the app", overlay.dispatchTouchEvent(down(20f, 20f)))
            overlay.expand()
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertTrue("The inspector owns input while open", overlay.dispatchTouchEvent(down(20f, 20f)))
            overlay.dismissAll()
        }
        instrumentation.waitForIdleSync()
        onMain {
            assertFalse(overlay.dispatchTouchEvent(down(20f, 20f)))
        }
    }

    @Test
    fun releaseModeNeverShowsStacksAndOffersRetry() {
        var reloads = 0
        val overlay = overlay(developerMode = false) { reloads++ }
        onMain {
            overlay.showError(payload(fatal = false))
            assertEquals(ErrorOverlay.State.HIDDEN, overlay.state)
            overlay.showError(payload(fatal = true))
            assertEquals(ErrorOverlay.State.FALLBACK, overlay.state)
            assertEquals(View.GONE, overlay.panel.visibility)
            assertEquals(View.GONE, overlay.toast.visibility)
            val texts = visibleTexts(overlay)
            assertTrue(texts.none { it.contains("Chat.php") || it.contains("RuntimeException") || it.contains("too large") })
            assertTrue(texts.any { it == overlay.context.getString(R.string.pam_error_fallback_title) })
            overlay.fallbackRetry.performClick()
            assertEquals(1, reloads)
            assertEquals(ErrorOverlay.State.HIDDEN, overlay.state)
            assertFalse(ErrorOverlay.developerMode(overlay.context, debugBuild = false))
            assertTrue(ErrorOverlay.developerMode(overlay.context, debugBuild = true))
        }
    }

    @Test
    fun backDismissesTheInspectorInPamActivity() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamActivity
        launched = activity
        // The test APK carries no app bundle, so startup itself reports a
        // fatal error first; wait for it so it cannot race the assertions.
        val deadline = SystemClock.uptimeMillis() + 10_000
        var started = false
        while (!started && SystemClock.uptimeMillis() < deadline) {
            onMain { started = activity.errors.state != ErrorOverlay.State.HIDDEN }
            if (!started) SystemClock.sleep(50)
        }
        instrumentation.waitForIdleSync()
        onMain {
            activity.errors.clearError()
            activity.errors.showError(payload(fatal = true))
            assertEquals(ErrorOverlay.State.PANEL, activity.errors.state)
        }
        instrumentation.waitForIdleSync()
        // Invoke the activity's registered back path directly: an injected
        // KEYCODE_BACK goes to whichever window has focus on a shared device.
        onMain {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val field = PamActivity::class.java.getDeclaredField("backCallback").apply { isAccessible = true }
                (field.get(activity) as OnBackInvokedCallback).onBackInvoked()
            } else {
                @Suppress("DEPRECATION")
                activity.onBackPressed()
            }
        }
        instrumentation.waitForIdleSync()
        var state: ErrorOverlay.State? = null
        onMain { state = activity.errors.state }
        assertEquals(ErrorOverlay.State.HIDDEN, state)
        assertFalse("Back must close the overlay, not the activity", activity.isFinishing)
    }

    private fun overlay(developerMode: Boolean, onReload: () -> Unit = {}): ErrorOverlay {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        launched = activity
        lateinit var overlay: ErrorOverlay
        onMain {
            overlay = ErrorOverlay(activity, developerMode, onReload = onReload)
            activity.findViewById<ViewGroup>(android.R.id.content).addView(overlay)
        }
        instrumentation.waitForIdleSync()
        assertNotNull(overlay)
        return overlay
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun down(x: Float, y: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
    }

    private fun visibleTexts(root: View): List<String> {
        val result = ArrayList<String>()
        fun walk(view: View) {
            if (view.visibility != View.VISIBLE) return
            if (view is TextView) result += view.text.toString()
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(root)
        return result
    }

    private fun payload(fatal: Boolean, message: String = "Native module value is too large"): String =
        "PAMERR1\n" + JSONObject()
            .put("version", 2)
            .put("type", "RuntimeException")
            .put("message", message)
            .put("file", "app/Screens/Chat.php")
            .put("line", 42)
            .put("column", 1)
            .put("phase", if (fatal) "render" else "event")
            .put("fatal", fatal)
            .put("fingerprint", "fp-$message-$fatal")
            .put(
                "frames",
                JSONArray()
                    .put(frame("vendor/pushinbr/pam-native/src/Database/SQLite.php", 180, "Pam\\Native\\Database\\SQLite::{closure}()", "framework"))
                    .put(frame("app/Screens/Chat.php", 42, "App\\Screens\\Chat->load()", "app"))
                    .put(frame("vendor/pushinbr/pam-native/src/Internal/Runtime.php", 446, "Pam\\Native\\Internal\\Runtime::dispatchModuleResult()", "framework"))
                    .put(frame("index.php", 12, "{main}", "app")),
            )
            .put("appFrame", 1)
            .put(
                "snippet",
                JSONObject()
                    .put("file", "app/Screens/Chat.php")
                    .put("line", 42)
                    .put("start", 41)
                    .put("lines", JSONArray().put("    if (\$rows === null) {").put("        throw new RuntimeException('x');").put("    }")),
            )
            .put("trace", "#0 vendor/pushinbr/pam-native/src/Database/SQLite.php(180): closure")
            .toString()

    private fun frame(file: String, line: Int, call: String, kind: String) =
        JSONObject().put("file", file).put("line", line).put("call", call).put("kind", kind)
}
