package dev.pam.nativeapp

import android.content.Intent
import android.view.ViewGroup
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.containsString
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class CapabilityIntegrationTest {
    private var activity: CapabilityTestActivity? = null

    @After
    fun tearDown() {
        activity?.finish()
    }

    @Test
    fun devToolsRendersCapabilityTimelineAndFailureState() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launched = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, CapabilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as CapabilityTestActivity
        activity = launched
        instrumentation.runOnMainSync {
            val overlay = PamDevToolsOverlay(launched)
            launched.root.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            overlay.update(
                RuntimeFrameMetrics(
                    batches = 1,
                    decodeNanos = 1_000_000,
                    mountNanos = 2_000_000,
                    stats = RuntimeStats(
                        commits = 1,
                        nodes = 4,
                        created = 4,
                        removed = 0,
                        updated = 0,
                        retainedBytes = 1_024,
                        fullCommits = 1,
                        patchCommits = 0,
                        inputBytes = 128,
                        outputBytes = 256,
                    ),
                ),
            )
            overlay.record(
                RuntimeDiagnostic(
                    kind = RuntimeDiagnosticKind.MODULE_CALL,
                    label = "permissions.request",
                    durationNanos = 12_000_000,
                    failed = true,
                ),
            )
            overlay.toggle()
        }

        onView(withContentDescription("Pam Native DevTools")).check(matches(isDisplayed()))
        onView(withText(containsString("FAIL"))).check(matches(isDisplayed()))
        onView(withText(containsString("permissions.request"))).check(matches(isDisplayed()))
    }

    @Test
    fun devToolsExportsBoundedRedactedCrossHostSnapshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launched = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, CapabilityTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as CapabilityTestActivity
        activity = launched
        lateinit var snapshot: JSONObject
        instrumentation.runOnMainSync {
            val overlay = PamDevToolsOverlay(launched)
            repeat(12) { index ->
                overlay.record(
                    RuntimeDiagnostic(
                        kind = RuntimeDiagnosticKind.ERROR,
                        label = "secret-$index",
                        failed = true,
                    ),
                )
            }
            snapshot = JSONObject(overlay.snapshotJson(capturedAtUnixMs = 1234))
        }

        assert(snapshot.getInt("schemaVersion") == 1)
        assert(snapshot.getInt("surfaceCode") == 2)
        assert(snapshot.getInt("platformCode") == 1)
        assert(snapshot.getLong("capturedAtUnixMs") == 1234L)
        assert(snapshot.getJSONArray("timeline").length() == 8)
        assert(!snapshot.toString().contains("secret-"))
    }
}
