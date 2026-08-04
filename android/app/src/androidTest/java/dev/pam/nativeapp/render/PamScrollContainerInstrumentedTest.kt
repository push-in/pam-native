package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamScrollContainerInstrumentedTest {
    @Test
    fun requestedOffsetIsReappliedAfterContentGrows() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var scroll: PamScrollContainer
            lateinit var content: View
            onMain(instrumentation) {
                scroll = PamScrollContainer(activity)
                content = View(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1_000,
                    )
                }
                scroll.insert(content)
                activity.host.addView(
                    scroll,
                    FrameLayout.LayoutParams(300, 400),
                )
                activity.host.measure(exactly(300), exactly(400))
                activity.host.layout(0, 0, 300, 400)
                scroll.setContentOffsetY(100_000f)
                assertEquals(600, scroll.snapshotOffsetPixels().second)

                content.layoutParams = content.layoutParams.apply {
                    height = 1_400
                }
                content.requestLayout()
                scroll.setContentOffsetY(100_000f)
            }

            instrumentation.waitForIdleSync()

            onMain(instrumentation) {
                assertEquals(1_000, scroll.snapshotOffsetPixels().second)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun endAnchorTracksGrowthOnlyWhileViewportIsNearEnd() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var scroll: PamScrollContainer
            lateinit var content: View
            onMain(instrumentation) {
                scroll = PamScrollContainer(activity)
                content = View(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1_000,
                    )
                }
                scroll.insert(content)
                scroll.setAutoScrollToEndThreshold(24f)
                scroll.setAnchorToEnd(true)
                activity.host.addView(scroll, FrameLayout.LayoutParams(300, 400))
                relayout(activity.host)
                assertEquals(600, scroll.snapshotOffsetPixels().second)

                content.layoutParams = content.layoutParams.apply { height = 1_400 }
                relayout(activity.host)
                assertEquals(1_000, scroll.snapshotOffsetPixels().second)

                // Renderer reconciliation may offer the offset captured before
                // the content mutation. It must not undo end-following.
                scroll.restoreOffsetPixels(0, 600)
                assertEquals(1_000, scroll.snapshotOffsetPixels().second)

                scroll.restoreOffsetPixels(0, 200)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(200, scroll.snapshotOffsetPixels().second)
                content.layoutParams = content.layoutParams.apply { height = 1_800 }
                relayout(activity.host)
                assertEquals(200, scroll.snapshotOffsetPixels().second)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun maintainedVisiblePositionCompensatesForContentGrowth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var scroll: PamScrollContainer
            lateinit var content: View
            onMain(instrumentation) {
                scroll = PamScrollContainer(activity)
                content = View(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1_000,
                    )
                }
                scroll.insert(content)
                scroll.setMaintainVisibleContentPosition(true)
                activity.host.addView(scroll, FrameLayout.LayoutParams(300, 400))
                relayout(activity.host)
                scroll.restoreOffsetPixels(0, 200)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                content.layoutParams = content.layoutParams.apply { height = 1_400 }
                relayout(activity.host)
                assertEquals(600, scroll.snapshotOffsetPixels().second)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun keyboardInsetKeepsEndAnchoredWithoutMovingAnOlderViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var scroll: PamScrollContainer
            onMain(instrumentation) {
                scroll = PamScrollContainer(activity)
                scroll.insert(View(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1_000,
                    )
                })
                scroll.setAutoScrollToEndThreshold(24f)
                scroll.setAnchorToEnd(true)
                activity.host.addView(scroll, FrameLayout.LayoutParams(300, 400))
                relayout(activity.host)
                assertEquals(600, scroll.snapshotOffsetPixels().second)

                scroll.setKeyboardAvoidanceInset(200)
                relayout(activity.host)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(200, scroll.keyboardAvoidanceInsetPixels())
                assertEquals(800, scroll.snapshotOffsetPixels().second)

                scroll.restoreOffsetPixels(0, 200)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                scroll.setKeyboardAvoidanceInset(300)
                relayout(activity.host)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(200, scroll.snapshotOffsetPixels().second)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun keyboardInsetScrollsFocusedFormTargetIntoVisibleViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var scroll: PamScrollContainer
            lateinit var input: View
            onMain(instrumentation) {
                scroll = PamScrollContainer(activity)
                val column = FrameLayout(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1_000,
                    )
                }
                input = View(activity)
                column.addView(
                    input,
                    FrameLayout.LayoutParams(300, 80).apply { topMargin = 720 },
                )
                scroll.insert(column)
                activity.host.addView(scroll, FrameLayout.LayoutParams(300, 400))
                relayout(activity.host)

                scroll.setKeyboardAvoidanceInset(200)
                relayout(activity.host)
                scroll.ensureKeyboardTargetVisible(input)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                val targetLocation = IntArray(2)
                val scrollLocation = IntArray(2)
                input.getLocationOnScreen(targetLocation)
                scroll.getLocationOnScreen(scrollLocation)
                val visibleBottom =
                    scrollLocation[1] + scroll.height - scroll.keyboardAvoidanceInsetPixels()

                assertTrue(scroll.snapshotOffsetPixels().second > 0)
                assertTrue(targetLocation[1] + input.height <= visibleBottom)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun requestScrollJumpsToEndOrIdentifiedDescendant() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var scroll: PamScrollContainer
            onMain(instrumentation) {
                scroll = PamScrollContainer(activity)
                val column = FrameLayout(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1_400,
                    )
                }
                column.addView(
                    View(activity).apply {
                        transitionName = "first-unread"
                    },
                    FrameLayout.LayoutParams(300, 80).apply {
                        topMargin = 720
                    },
                )
                scroll.insert(column)
                activity.host.addView(scroll, FrameLayout.LayoutParams(300, 400))
                relayout(activity.host)

                scroll.setScrollTargetTestId("first-unread")
                scroll.requestScroll()
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(720, scroll.snapshotOffsetPixels().second)
                scroll.setScrollTargetTestId("")
                scroll.requestScroll()
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(1_000, scroll.snapshotOffsetPixels().second)
            }
        } finally {
            activity.finish()
        }
    }

    private fun launchActivity(instrumentation: Instrumentation): PamTestActivity {
        val intent = Intent(
            instrumentation.targetContext,
            PamTestActivity::class.java,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return instrumentation.startActivitySync(intent) as PamTestActivity
    }

    private fun onMain(
        instrumentation: Instrumentation,
        block: () -> Unit,
    ) {
        instrumentation.runOnMainSync(block)
    }

    private fun exactly(size: Int): Int =
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun relayout(host: View) {
        host.measure(exactly(300), exactly(400))
        host.layout(0, 0, 300, 400)
    }
}
