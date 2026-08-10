package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowInsetsController
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamNavigationHostInstrumentedTest {
    @Test
    fun replacingTheActiveRoutePromotesTheRemainingReplacement() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var active: View
            lateinit var replacement: View
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity)
                activity.host.addView(navigation)
                active = View(activity)
                replacement = View(activity)
                navigation.insert(active, 0)
                navigation.insert(replacement, 1)

                assertEquals(View.VISIBLE, active.visibility)
                assertEquals(View.INVISIBLE, replacement.visibility)

                navigation.removeRoute(active)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertSame(replacement, navigation.getChildAt(0))
                assertTrue(navigation.isActiveRoute(replacement))
                assertEquals(View.VISIBLE, replacement.visibility)
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
                    replacement.importantForAccessibility,
                )
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun nativeTabHostRetainsAllScenesWhenSelectionChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val tabs = PamTabHost(activity)
                val first = View(activity)
                val second = View(activity)
                tabs.insertScene(first, 0)
                tabs.insertScene(second, 1)
                tabs.configure(
                    """[{"name":"home","label":"Home","badge":null},{"name":"orders","label":"Orders","badge":"2"}]""",
                    1, 1, Color.BLACK, Color.GRAY, Color.WHITE, Color.BLACK, false,
                )
                tabs.selectTab(2)

                assertEquals(2, tabs.activeSceneIndex)
                assertEquals(View.GONE, first.visibility)
                assertEquals(View.VISIBLE, second.visibility)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun formSheetSizesNativeRouteControllerAtConfiguredDetent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var previous: View
            lateinit var sheet: View
            onMain(instrumentation) {
                val navigation = PamNavigationHost(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(390, 800)
                    operation = OPERATION_PUSH
                    transition = TRANSITION_NONE
                    screenPresentation = 7
                    sheetDetents = listOf(0.5f, 1f)
                    sheetInitialDetentIndex = 1
                    sheetCornerRadius = 24f
                }
                activity.host.addView(navigation)
                navigation.layout(0, 0, 390, 800)
                previous = View(activity)
                sheet = View(activity)
                navigation.insert(previous, 0)
                navigation.insert(sheet, 1)
                navigation.navigate(1)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(400, sheet.layoutParams.height)
                assertTrue(sheet.clipToOutline)
                assertEquals(View.VISIBLE, previous.visibility)
            }
        } finally {
            activity.finish()
        }
    }

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
                assertEquals(2, navigation.routeControllerCount())
                assertEquals(
                    androidx.lifecycle.Lifecycle.State.RESUMED,
                    navigation.activeControllerLifecycle(),
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
    fun pushActivatesInsertedRouteWhenReconciliationPlacesItBeforeTheRetainedRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var retainedChat: View
            lateinit var insertedMedia: View
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    transition = TRANSITION_NONE
                }
                activity.host.addView(navigation)
                val discardedInbox = View(activity)
                retainedChat = View(activity)
                navigation.insert(discardedInbox, 0)
                navigation.insert(retainedChat, 1)
                navigation.operation = OPERATION_PUSH
                navigation.navigate(1)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertTrue(navigation.isActiveRoute(retainedChat))

                navigation.removeRoute(navigation.getChildAt(0))
                insertedMedia = View(activity)
                navigation.insert(insertedMedia, 1)
                navigation.detachRouteForMove(retainedChat)
                navigation.insert(retainedChat, 0)
                assertSame(retainedChat, navigation.getChildAt(0))
                assertSame(insertedMedia, navigation.getChildAt(1))

                navigation.operation = OPERATION_PUSH
                navigation.navigate(2)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertTrue(navigation.isActiveRoute(insertedMedia))
                assertEquals(View.VISIBLE, insertedMedia.visibility)
                assertEquals(View.INVISIBLE, retainedChat.visibility)
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
                    insertedMedia.importantForAccessibility,
                )
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    retainedChat.importantForAccessibility,
                )
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun predictiveBackProgressStaysNativeAndCommitsWithoutSecondAnimation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var first: View
            lateinit var second: View
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity).apply {
                    layout(0, 0, 1_080, 1_920)
                    operation = OPERATION_PUSH
                    // This test exercises predictive-back state, not the preceding push.
                    // Keep setup deterministic on physical devices where animator scale and
                    // refresh cadence may leave the push animator active after waitForIdleSync.
                    transition = TRANSITION_NONE
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
                assertTrue(navigation.startPredictiveBack())
                navigation.updatePredictiveBack(0.5f)
                assertTrue(second.translationX > 0f)
                assertTrue(first.translationX < 0f)
                navigation.commitPredictiveBack()
                navigation.operation = OPERATION_POP
                navigation.navigate(2)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(View.VISIBLE, first.visibility)
                assertEquals(View.INVISIBLE, second.visibility)
                assertEquals(0f, first.translationX, 0.001f)
                assertEquals(0f, second.translationX, 0.001f)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun sharedElementsFollowPredictiveBackAndRestoreOriginalViews() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            Settings.Global.getFloat(
                instrumentation.targetContext.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f,
        )
        val activity = launchActivity(instrumentation)
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var source: View
            lateinit var destination: View
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity).apply {
                    layout(0, 0, 1_080, 1_920)
                    operation = OPERATION_PUSH
                    transition = TRANSITION_NONE
                }
                activity.host.addView(navigation)
                val first = FrameLayout(activity).apply { layout(0, 0, 1_080, 1_920) }
                val second = FrameLayout(activity).apply { layout(0, 0, 1_080, 1_920) }
                destination = View(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(312, 312).apply {
                        leftMargin = 48
                        topMargin = 96
                    }
                    setTag(R.id.pam_shared_transition_tag, "post:42")
                    setTag(
                        R.id.pam_shared_transition_config,
                        """{"durationMs":420,"easing":3,"resizeMode":2,"crossFade":true,"damping":0.76,"stiffness":240,"mass":1}""",
                    )
                }
                source = View(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(300, 300).apply {
                        leftMargin = 720
                        topMargin = 1_220
                    }
                    setTag(R.id.pam_shared_transition_tag, "post:42")
                }
                first.addView(destination)
                second.addView(source)
                navigation.insert(first, 0)
                navigation.insert(second, 1)
                navigation.navigate(1)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                navigation.transition = TRANSITION_SLIDE_FROM_RIGHT
                assertTrue(navigation.startPredictiveBack())
                assertEquals(View.INVISIBLE, source.visibility)
                assertEquals(View.INVISIBLE, destination.visibility)
                navigation.updatePredictiveBack(0.5f)
                navigation.commitPredictiveBack()
                navigation.operation = OPERATION_POP
                navigation.navigate(2)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(View.VISIBLE, source.visibility)
                assertEquals(View.VISIBLE, destination.visibility)
                assertTrue(navigation.isActiveRoute(navigation.getChildAt(0)))
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
