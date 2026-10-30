package dev.pam.nativeapp.render

import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.postRuntimeBatch
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamRuntimeBatchOrderInstrumentedTest {
    @Test
    fun dependentPatchesWaitForTheirParentsWhileTheMainThreadIsBusy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        val main = Handler(Looper.getMainLooper())
        val delivered = CopyOnWriteArrayList<Int>()
        val failures = CopyOnWriteArrayList<Throwable>()
        lateinit var renderer: PamRenderer
        fun deliver(sequence: Int, mutations: List<Mutation>) = Runnable {
            try {
                renderer.commit(listOf(mutations))
                delivered.add(sequence)
            } catch (failure: Throwable) {
                failures.add(failure)
            }
        }
        fun create(id: Long, parent: Long, kind: NodeKind) =
            Mutation.Create(NodeSpec(id, parent, 0, kind, emptyMap()))
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                // A preceding normal batch (also how remounts are delivered).
                main.post(deliver(1, listOf(create(1, 0, NodeKind.SCREEN), Mutation.SetRoot(1))))
                // Both arrive before the main thread can drain either one.
                main.postRuntimeBatch(deliver(2, listOf(create(2, 1, NodeKind.ROW))))
                main.postRuntimeBatch(deliver(3, listOf(create(3, 2, NodeKind.TEXT))))
            }
            instrumentation.waitForIdleSync()
            assertNull(failures.firstOrNull()?.stackTraceToString(), failures.firstOrNull())
            assertEquals(listOf(1, 2, 3), delivered.toList())
        } finally {
            instrumentation.runOnMainSync { renderer.close(); activity.finish() }
        }
    }
}
