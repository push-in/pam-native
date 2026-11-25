package dev.pam.nativeapp

import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.protocol.EventKind
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import dev.pam.nativeapp.render.PamRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque

/** Uses the existing scheduling path, without starting PHP or a JNI runtime. */
@RunWith(AndroidJUnit4::class)
class PamRuntimeInteractionPriorityInstrumentedTest {
    @Test
    fun discreteActionsDoNotDeferTheirAnswerBehindAnotherFrame() {
        for (event in listOf(
            EventKind.PRESS, EventKind.CHANGE, EventKind.BACK, EventKind.LONG_PRESS,
            EventKind.SUBMIT, EventKind.REFRESH, EventKind.TOGGLE, EventKind.INPUT_KEY_PRESS,
            EventKind.MODAL_REQUEST_CLOSE, EventKind.DROP, EventKind.MENU_ACTION,
            EventKind.NAVIGATION_GESTURE_POP, EventKind.ACCESSIBILITY_ACTION,
            EventKind.SPAN_PRESS, EventKind.DOUBLE_TAP, EventKind.GESTURE_SETTLE,
        )) {
            assertFalse("$event answer must receive interactive priority", defersHeavyAnswer(event))
        }
    }

    @Test
    fun continuousUpdatesKeepTheHeavyCommitFrameBudget() {
        for (event in listOf(EventKind.NATIVE, EventKind.MEDIA_PROGRESS, EventKind.GESTURE_UPDATE)) {
            assertTrue("$event must not bypass the frame budget", defersHeavyAnswer(event))
        }
    }

    private fun defersHeavyAnswer(event: EventKind): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var deferred = false
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val renderer = PamRenderer(context, FrameLayout(context)) { _, _, _ -> }
            val runtime = PamRuntime(context, renderer, {}, installModules = false)
            try {
                PamRuntime::class.java.getDeclaredField("surfaceAwaitingFirstFrame")
                    .apply { isAccessible = true }.setBoolean(runtime, false)
                // Handle0 is never registered with the native batch owner, so
                // closing this synthetic queue cannot call JNI release.
                val pendingClass = Class.forName("dev.pam.nativeapp.PendingBatch")
                val constructor = pendingClass.declaredConstructors.single { it.parameterTypes.size == 5 }
                    .apply { isAccessible = true }
                val batch = constructor.newInstance(
                    List(40) { Mutation.Update(100L + it, PropKey.TEXT, PropValue.Text("changed")) },
                    0L, 0L, false, true,
                )
                @Suppress("UNCHECKED_CAST")
                val queue = PamRuntime::class.java.getDeclaredField("pendingBatches")
                    .apply { isAccessible = true }.get(runtime) as ArrayDeque<Any>
                queue.addLast(batch)
                runtime.dispatchEvent(100, event.value)
                deferred = PamRuntime::class.java.getDeclaredMethod("deferCommitPastFrame")
                    .apply { isAccessible = true }.invoke(runtime) as Boolean
            } finally {
                // Cancels any deferred commit before this UI-thread call ends.
                runtime.close()
            }
        }
        return deferred
    }
}
