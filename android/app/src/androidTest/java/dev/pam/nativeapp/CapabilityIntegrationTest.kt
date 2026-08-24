package dev.pam.nativeapp

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
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
        lateinit var overlay: PamDevToolsOverlay
        instrumentation.runOnMainSync {
            overlay = PamDevToolsOverlay(launched)
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

        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue(overlay.isShown)
            val text = descendantText(overlay).joinToString("\n")
            assertTrue(text.contains("FAIL"))
            assertTrue(text.contains("permissions.request"))
        }
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
            repeat(7) { index ->
                overlay.record(
                    RuntimeDiagnostic(
                        kind = RuntimeDiagnosticKind.ERROR,
                        label = "secret-$index",
                        failed = true,
                    ),
                )
            }
            overlay.record(
                RuntimeDiagnostic(
                    kind = RuntimeDiagnosticKind.NETWORK,
                    label = "PATCH https://secret.example/private?token=secret",
                    durationNanos = 12_345_000,
                    failed = false,
                    methodCode = RuntimeHttpMethod.PATCH.value,
                    statusCode = 202,
                    requestBytes = 17,
                    responseBytes = 8,
                ),
            )
            snapshot = JSONObject(overlay.snapshotJson(capturedAtUnixMs = 1234))
        }

        assert(snapshot.getInt("schemaVersion") == 1)
        assert(snapshot.getInt("surfaceCode") == 2)
        assert(snapshot.getInt("platformCode") == 1)
        assert(snapshot.getLong("capturedAtUnixMs") == 1234L)
        assert(snapshot.getJSONArray("timeline").length() == 8)
        assert(!snapshot.toString().contains("secret-"))
        assert(!snapshot.toString().contains("secret.example"))
        val network = snapshot.getJSONArray("timeline").getJSONObject(7)
        assert(network.getInt("kindCode") == RuntimeDiagnosticKind.NETWORK.value)
        assert(network.getInt("methodCode") == RuntimeHttpMethod.PATCH.value)
        assert(network.getInt("statusCode") == 202)
        assert(network.getInt("requestBytes") == 17)
        assert(network.getInt("responseBytes") == 8)
    }

    private fun descendantText(view: View?): List<String> {
        if (view == null) return emptyList()
        val own = (view as? TextView)?.text?.toString()?.let(::listOf).orEmpty()
        if (view !is ViewGroup) return own
        return own + (0 until view.childCount).flatMap { index ->
            descendantText(view.getChildAt(index))
        }
    }
}
