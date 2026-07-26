package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamNavigationHostInstrumentedTest {
    @Test
    fun pushAndPopExposeOnlyTheDestinationAfterTheStagedFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var first: View
            lateinit var second: View
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    operation = OPERATION_PUSH
                    transition = TRANSITION_NONE
                }
                activity.host.addView(navigation)
                first = View(activity)
                second = View(activity)
                navigation.insert(first, 0)
                navigation.insert(second, 1)
                assertEquals(View.VISIBLE, first.visibility)
                assertEquals(View.INVISIBLE, second.visibility)
                navigation.navigate(1)
            }

            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(View.INVISIBLE, first.visibility)
                assertEquals(View.VISIBLE, second.visibility)
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    first.importantForAccessibility,
                )
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
                    second.importantForAccessibility,
                )

                navigation.operation = OPERATION_POP
                navigation.navigate(2)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(View.VISIBLE, first.visibility)
                assertEquals(View.INVISIBLE, second.visibility)
                assertSame(first, navigation.getChildAt(0))
                assertSame(second, navigation.getChildAt(1))
            }
        } finally {
            activity.finish()
        }
    }

    private fun launchActivity(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as PamTestActivity

    private fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private companion object {
        const val OPERATION_PUSH = 2
        const val OPERATION_POP = 3
        const val TRANSITION_NONE = 8
    }
}
