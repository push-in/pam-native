package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamNavigationHostInstrumentedTest {
    @Test
    fun drawerAdaptsStatusBarIconsAndRestoresThemWhenClosed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val lightStatusBar = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.window.insetsController?.setSystemBarsAppearance(
                        lightStatusBar,
                        lightStatusBar,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                        activity.window.decorView.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                val drawer = PamDrawerLayout(activity).apply {
                    addView(View(activity))
                    addView(View(activity).apply { setBackgroundColor(Color.rgb(8, 24, 43)) })
                }
                activity.host.addView(drawer)

                drawer.setOpen(true, animated = false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    assertEquals(
                        0,
                        activity.window.insetsController?.systemBarsAppearance?.and(lightStatusBar),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    assertEquals(
                        0,
                        activity.window.decorView.systemUiVisibility and
                            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR,
                    )
                }

                drawer.setOpen(false, animated = false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    assertEquals(
                        lightStatusBar,
                        activity.window.insetsController?.systemBarsAppearance?.and(lightStatusBar),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    assertEquals(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR,
                        activity.window.decorView.systemUiVisibility and
                            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR,
                    )
                }
            }
        } finally {
            activity.finish()
        }
    }

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

    @Test
    fun transitionsReuseHardwareAcceleratedDisplayListsWithoutBitmapLayers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var first: View
            lateinit var second: View
            onMain(instrumentation) {
                val navigation = PamNavigationHost(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    operation = OPERATION_PUSH
                    transition = TRANSITION_SLIDE_FROM_RIGHT
                    durationMs = 120
                }
                activity.host.addView(navigation)
                first = View(activity)
                second = View(activity)
                navigation.insert(first, 0)
                navigation.insert(second, 1)
                navigation.navigate(1)
            }

            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(View.LAYER_TYPE_NONE, first.layerType)
                assertEquals(View.LAYER_TYPE_NONE, second.layerType)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun permanentDrawerStartsBelowTheStatusBar() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var drawer: View
            onMain(instrumentation) {
                val layout = PamDrawerLayout(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    addView(View(activity))
                    drawer = View(activity)
                    addView(drawer)
                    setDrawerType(TYPE_PERMANENT)
                }
                activity.host.addView(layout)
            }

            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                val expectedTop = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    drawer.rootWindowInsets
                        ?.getInsets(android.view.WindowInsets.Type.statusBars())
                        ?.top ?: 0
                } else {
                    @Suppress("DEPRECATION")
                    drawer.rootWindowInsets?.systemWindowInsetTop ?: 0
                }
                assertTrue(expectedTop > 0)
                assertEquals(
                    expectedTop,
                    (drawer.layoutParams as FrameLayout.LayoutParams).topMargin,
                )
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
        const val TRANSITION_SLIDE_FROM_RIGHT = 2
        const val TRANSITION_NONE = 8
        const val TYPE_PERMANENT = 4
    }
}
