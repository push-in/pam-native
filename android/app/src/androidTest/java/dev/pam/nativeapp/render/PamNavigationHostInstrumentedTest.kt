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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PamNavigationHostInstrumentedTest {
    @Test
    fun reducedMotionCommitsNavigationWithoutWaitingForAuthoredDuration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val completed = CountDownLatch(1)
        try {
            onMain(instrumentation) {
                PamMotionPolicy.reduceMotionOverride = true
                val navigation = PamNavigationHost(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(390, 800)
                    operation = OPERATION_PUSH
                    transition = TRANSITION_SLIDE_FROM_RIGHT
                    durationMs = 2_000
                    setGestureNavigation(
                        enabled = false,
                        edgeWidth = 24f,
                        threshold = 0.35f,
                        onPop = null,
                        onTransitionEnd = { completed.countDown() },
                        onGestureStart = null,
                        onGestureEnd = null,
                        onGestureCancel = null,
                    )
                }
                activity.host.addView(navigation)
                navigation.layout(0, 0, 390, 800)
                navigation.insert(View(activity), 0)
                navigation.insert(View(activity), 1)
                navigation.navigate(1)
            }

            assertTrue(
                "Reduced motion must not wait for the authored two-second transition",
                completed.await(1, TimeUnit.SECONDS),
            )
        } finally {
            onMain(instrumentation) { PamMotionPolicy.reduceMotionOverride = null }
            activity.finish()
        }
    }

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

    /**
     * Zé Chat cold-start deep link: Navigator::reset(Inbox) then, one tick
     * later, a push of the chat. When both PHP frames reach the engine before
     * it diffs, one commit removes the launch route and creates the new
     * routes in node-id order (the chat before the Inbox it sits on). The
     * chat must still be the destination, whatever the commit timing.
     */
    @Test
    fun movedKeepAliveRouteBecomesTheDestinationWithItsView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var inbox: View
            lateinit var feed: View
            onMain(instrumentation) {
                activity.host.removeAllViews()
                navigation = PamNavigationHost(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    transition = TRANSITION_NONE
                }
                activity.host.addView(navigation)
                inbox = View(activity)
                feed = View(activity)
                navigation.insert(inbox, 0)
                navigation.insert(feed, 1)
                navigation.operation = OPERATION_PUSH
                navigation.navigate(1)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                navigation.operation = OPERATION_POP
                navigation.navigate(2)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                // Navigator::keepAlive parks the popped Feed under the Inbox.
                navigation.detachRouteForMove(feed)
                navigation.insert(feed, 0)
                navigation.operation = OPERATION_IDLE
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertSame(feed, navigation.getChildAt(0))
                assertEquals(View.INVISIBLE, feed.visibility)
                assertTrue(navigation.isActiveRoute(inbox))
                // Back to the Feed tab: the very same view moves on top.
                navigation.detachRouteForMove(feed)
                navigation.insert(feed, 1)
                navigation.operation = OPERATION_PUSH
                navigation.navigate(3)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertSame(feed, navigation.getChildAt(1))
                assertTrue(navigation.isActiveRoute(feed))
                assertEquals(View.VISIBLE, feed.visibility)
                assertEquals(View.INVISIBLE, inbox.visibility)
                assertEquals(2, navigation.routeControllerCount())
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun resetThenPushLandsOnThePushedRouteWhateverTheCommitTiming() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            for (coalesced in listOf(true, false)) {
                lateinit var navigation: PamNavigationHost
                lateinit var inbox: View
                lateinit var chat: View
                onMain(instrumentation) {
                    activity.host.removeAllViews()
                    navigation = PamNavigationHost(activity).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        )
                        transition = TRANSITION_NONE
                    }
                    activity.host.addView(navigation)
                    navigation.insert(View(activity), 0)
                }
                instrumentation.waitForIdleSync()
                onMain(instrumentation) {
                    inbox = View(activity)
                    chat = View(activity)
                    // Engine order: removes, then creates by ascending node id.
                    navigation.removeRoute(navigation.getChildAt(0))
                    if (coalesced) {
                        navigation.insert(chat, 0)
                        navigation.insert(inbox, 0)
                        navigation.operation = OPERATION_PUSH
                        navigation.navigate(2)
                    } else {
                        navigation.insert(inbox, 0)
                        navigation.operation = OPERATION_RESET
                        navigation.navigate(1)
                    }
                }
                instrumentation.waitForIdleSync()
                if (!coalesced) {
                    onMain(instrumentation) {
                        navigation.insert(chat, 1)
                        navigation.operation = OPERATION_PUSH
                        navigation.navigate(2)
                    }
                    instrumentation.waitForIdleSync()
                }
                onMain(instrumentation) {
                    // The transition end lets PHP settle the stack (Idle).
                    navigation.operation = OPERATION_IDLE
                    navigation.navigate(3)
                }
                instrumentation.waitForIdleSync()
                onMain(instrumentation) {
                    val label = if (coalesced) "coalesced" else "separate commits"
                    assertSame("$label: engine order", inbox, navigation.getChildAt(0))
                    assertSame("$label: engine order", chat, navigation.getChildAt(1))
                    assertTrue("$label: chat is the active route", navigation.isActiveRoute(chat))
                    assertEquals("$label: chat shown", View.VISIBLE, chat.visibility)
                    assertEquals("$label: inbox hidden", View.INVISIBLE, inbox.visibility)
                    assertEquals(2, navigation.routeControllerCount())
                }
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
    fun predictiveBackTheAppConsumedSettlesBackWithoutPopping() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val cancelled = CountDownLatch(1)
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var first: View
            lateinit var second: View
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity).apply {
                    layout(0, 0, 1_080, 1_920)
                    operation = OPERATION_PUSH
                    transition = TRANSITION_NONE
                    setGestureNavigation(
                        enabled = true,
                        edgeWidth = 24f,
                        threshold = 0.35f,
                        onPop = null,
                        onTransitionEnd = null,
                        onGestureStart = null,
                        onGestureEnd = null,
                        onGestureCancel = { cancelled.countDown() },
                    )
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
                // A key Back: started and released at progress 0, then a
                // frame with no navigation (the app closed a panel). It
                // prepared nothing, so nothing settles or is cancelled.
                assertTrue(navigation.startPredictiveBack())
                navigation.commitPredictiveBack()
                assertEquals(0f, second.translationX, 0.001f)
                assertEquals(View.INVISIBLE, first.visibility)
                navigation.resolvePredictiveBack()
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(1L, cancelled.count)
                assertTrue(navigation.isActiveRoute(second))
                assertEquals(View.VISIBLE, second.visibility)
                assertEquals(View.INVISIBLE, first.visibility)
                assertEquals(0f, second.translationX, 0.001f)
                // A dragged one that the app consumed returns to rest.
                assertTrue(navigation.startPredictiveBack())
                navigation.updatePredictiveBack(0.5f)
                navigation.commitPredictiveBack()
                assertTrue(second.translationX > 0f)
                navigation.resolvePredictiveBack()
            }
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            Thread.sleep(400)
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertTrue(navigation.isActiveRoute(second))
                assertEquals(View.VISIBLE, second.visibility)
                assertEquals(0f, second.translationX, 0.001f)
                // A key Back that pops: the pop that follows completes it.
                assertTrue(navigation.startPredictiveBack())
                navigation.commitPredictiveBack()
                navigation.operation = OPERATION_POP
                navigation.navigate(2)
                navigation.resolvePredictiveBack()
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(400)
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertTrue(navigation.isActiveRoute(first))
                assertEquals(View.VISIBLE, first.visibility)
                assertEquals(0f, first.translationX, 0.001f)
            }
        } finally {
            activity.finish()
        }
    }

    /**
     * A Back key (Android 15+ also starts a predictive back) that an app
     * handler consumes, e.g. closing an emoji panel: no route moves on any
     * frame, before or after the release, until well past the verdict
     * timeout, like React Navigation with a consuming BackHandler.
     */
    @Test
    fun consumedKeyBackNeverMovesTheRouteOnAnyFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val gestureEvents = java.util.concurrent.atomic.AtomicInteger()
        val samples = java.util.Collections.synchronizedList(mutableListOf<String>())
        var sampling = true
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var first: View
            lateinit var second: View
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity).apply {
                    layout(0, 0, 1_080, 1_920)
                    operation = OPERATION_PUSH
                    transition = TRANSITION_NONE
                    setGestureNavigation(
                        enabled = true,
                        edgeWidth = 24f,
                        threshold = 0.35f,
                        onPop = { gestureEvents.incrementAndGet() },
                        onTransitionEnd = null,
                        onGestureStart = { gestureEvents.incrementAndGet() },
                        onGestureEnd = { gestureEvents.incrementAndGet() },
                        onGestureCancel = { gestureEvents.incrementAndGet() },
                    )
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
                fun sample() {
                    if (!sampling) return
                    samples += "${second.translationX},${second.translationY},${first.translationX}," +
                        "${first.visibility},${second.visibility},${navigation.isActiveRoute(second)}"
                    android.view.Choreographer.getInstance().postFrameCallback { sample() }
                }
                sample()
                // Key down: the system starts a predictive back at 0.
                assertTrue(navigation.startPredictiveBack())
                navigation.updatePredictiveBack(0f)
            }
            Thread.sleep(120)
            onMain(instrumentation) {
                // Key up: released, the runtime runs the handler, which
                // closes the panel and commits a frame with no navigation.
                navigation.commitPredictiveBack()
            }
            Thread.sleep(32)
            onMain(instrumentation) { navigation.resolvePredictiveBack() }
            Thread.sleep(1_300)
            instrumentation.waitForIdleSync()
            onMain(instrumentation) { sampling = false }
            val frames = synchronized(samples) { samples.toList() }
            assertTrue("too few frames sampled: ${frames.size}", frames.size > 20)
            val atRest = "0.0,0.0,0.0,${View.INVISIBLE},${View.VISIBLE},true"
            frames.forEachIndexed { index, frame -> assertEquals("frame $index", atRest, frame) }
            assertEquals(0, gestureEvents.get())
            onMain(instrumentation) {
                // The next Back that does pop still plays a pop.
                assertTrue(navigation.startPredictiveBack())
                navigation.commitPredictiveBack()
                navigation.operation = OPERATION_POP
                navigation.navigate(2)
                navigation.resolvePredictiveBack()
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(400)
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertTrue(navigation.isActiveRoute(first))
                assertEquals(View.VISIBLE, first.visibility)
                assertEquals(0f, first.translationX, 0.001f)
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
        val cancelled = CountDownLatch(1)
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var source: View
            lateinit var destination: View
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity).apply {
                    layout(0, 0, 1_080, 1_920)
                    operation = OPERATION_PUSH
                    transition = TRANSITION_NONE
                    setGestureNavigation(
                        enabled = true,
                        edgeWidth = 24f,
                        threshold = 0.35f,
                        onPop = null,
                        onTransitionEnd = null,
                        onGestureStart = null,
                        onGestureEnd = null,
                        onGestureCancel = { cancelled.countDown() },
                    )
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
                // Nothing is prepared until the gesture actually drags.
                assertEquals(View.VISIBLE, source.visibility)
                navigation.updatePredictiveBack(0.5f)
                // The flight draws in the overlay; both views are hidden by
                // alpha (visibility would stop their image loads).
                assertEquals(0f, source.alpha, 0f)
                assertEquals(0f, destination.alpha, 0f)
                assertEquals(1, navigation.activeSharedElementCount())
                navigation.cancelPredictiveBack()
            }
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            onMain(instrumentation) {
                assertEquals(1f, source.alpha, 0f)
                assertEquals(1f, destination.alpha, 0f)
                assertTrue(navigation.startPredictiveBack())
                navigation.updatePredictiveBack(0.65f)
                navigation.commitPredictiveBack()
                navigation.operation = OPERATION_POP
                navigation.navigate(2)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(1f, source.alpha, 0f)
                assertEquals(1f, destination.alpha, 0f)
                assertEquals(0, navigation.activeSharedElementCount())
                assertTrue(navigation.isActiveRoute(navigation.getChildAt(0)))
            }
        } finally {
            activity.finish()
        }
    }

    /**
     * A rounded cover thumbnail flies into a contained full-screen image and
     * back. Frames are sampled on every Choreographer frame: the crop grows
     * from the bubble to the letterboxed photo (and shrinks back), the radius
     * goes 14 dp -> 0 -> 14 dp, both views stay hidden only by alpha while
     * the flight draws, and nothing is left in the overlay afterwards.
     */
    @Test
    fun mediaSharedElementMorphsCropRadiusAndFitBothWays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            Settings.Global.getFloat(
                instrumentation.targetContext.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f,
        )
        val activity = launchActivity(instrumentation)
        val pushed = CountDownLatch(1)
        val popped = CountDownLatch(1)
        var phase = 0
        val pushFrames = java.util.Collections.synchronizedList(mutableListOf<FloatArray>())
        val popFrames = java.util.Collections.synchronizedList(mutableListOf<FloatArray>())
        var sampling: MutableList<FloatArray>? = null
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var thumbnail: PamImageView
            lateinit var viewer: PamImageView
            lateinit var viewerRoute: FrameLayout
            val density = activity.resources.displayMetrics.density
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity).apply {
                    operation = OPERATION_PUSH
                    transition = TRANSITION_NONE
                    setGestureNavigation(
                        enabled = false, edgeWidth = 24f, threshold = 0.35f, onPop = null,
                        onTransitionEnd = { if (phase == 1) pushed.countDown() else if (phase == 2) popped.countDown() },
                        onGestureStart = null, onGestureEnd = null, onGestureCancel = null,
                    )
                }
                activity.host.addView(navigation, FrameLayout.LayoutParams(1_080, 1_920))
                val photo = android.graphics.Bitmap.createBitmap(400, 300, android.graphics.Bitmap.Config.ARGB_8888)
                    .apply { eraseColor(Color.rgb(30, 120, 200)) }
                val chat = FrameLayout(activity)
                val bubble = PamContainer(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(200, 200).apply { leftMargin = 100; topMargin = 200 }
                    setOverflowClip(true, FloatArray(8) { 14f * density })
                }
                thumbnail = PamImageView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(200, 200)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, photo))
                    setTag(R.id.pam_shared_transition_tag, "media:1")
                }
                bubble.addView(thumbnail)
                chat.addView(bubble)
                viewerRoute = FrameLayout(activity).apply { setBackgroundColor(Color.BLACK) }
                viewer = PamImageView(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(1_080, 1_920)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, photo))
                    setTag(R.id.pam_shared_transition_tag, "media:1")
                    setTag(
                        R.id.pam_shared_transition_config,
                        """{"durationMs":360,"easing":3,"resizeMode":1,"crossFade":true,"damping":0.816,"stiffness":240,"mass":0.9,"returnDurationMs":200,"returnEasing":4}""",
                    )
                }
                viewerRoute.addView(viewer)
                navigation.insert(chat, 0)
                navigation.navigate(1)
            }
            instrumentation.waitForIdleSync()
            val sampler = object : android.view.Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    val target = sampling ?: return
                    navigation.activeSharedElementFrames().firstOrNull()?.let { target += it.copyOf() }
                    android.view.Choreographer.getInstance().postFrameCallback(this)
                }
            }
            onMain(instrumentation) {
                navigation.transition = TRANSITION_FADE
                navigation.durationMs = 300L
                phase = 1
                navigation.insert(viewerRoute, 1)
                navigation.navigate(2)
                sampling = pushFrames
                android.view.Choreographer.getInstance().postFrameCallback(sampler)
            }
            assertTrue(pushed.await(3, TimeUnit.SECONDS))
            onMain(instrumentation) {
                assertEquals(0, navigation.activeSharedElementCount())
                assertEquals(1f, thumbnail.alpha, 0f)
                assertEquals(1f, viewer.alpha, 0f)
                sampling = popFrames
                phase = 2
                navigation.operation = OPERATION_POP
                navigation.navigate(3)
            }
            assertTrue(popped.await(3, TimeUnit.SECONDS))
            onMain(instrumentation) { sampling = null }
            assertTrue("push sampled ${pushFrames.size} frames", pushFrames.size >= 8)
            assertTrue("pop sampled ${popFrames.size} frames", popFrames.size >= 4)
            val first = pushFrames.first()
            assertEquals(100f, first[0], 40f)
            assertEquals(200f, first[1], 40f)
            assertTrue("opens with the bubble radius", first[4] > 8f * density)
            val last = pushFrames.last()
            // Contained 4:3 photo in 1080x1920: 1080x810 centered vertically.
            assertEquals(0f, last[0], 60f)
            assertEquals(555f, last[1], 80f)
            assertEquals(1_365f, last[3], 80f)
            assertTrue("lands square", last[4] < 4f * density)
            for (index in 1 until pushFrames.size) {
                val widthBefore = pushFrames[index - 1][2] - pushFrames[index - 1][0]
                val width = pushFrames[index][2] - pushFrames[index][0]
                assertTrue("the crop grows without a jump back (frame $index)", width >= widthBefore - 2f)
            }
            val home = popFrames.last()
            assertEquals(100f, home[0], 60f)
            assertEquals(300f, home[2], 60f)
            assertTrue("returns into the rounded bubble", home[4] > 6f * density)
            onMain(instrumentation) {
                assertEquals(0, navigation.activeSharedElementCount())
                assertEquals(1f, thumbnail.alpha, 0f)
            }
        } finally {
            sampling = null
            activity.finish()
        }
    }

    @Test
    fun transitionsReuseHardwareAcceleratedDisplayListsWithoutBitmapLayers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val ended = CountDownLatch(1)
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var first: View
            lateinit var second: View
            val layerTypes = mutableSetOf<Int>()
            val sample = android.view.ViewTreeObserver.OnPreDrawListener {
                layerTypes += first.layerType
                layerTypes += second.layerType
                true
            }
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    operation = OPERATION_PUSH
                    transition = TRANSITION_SLIDE_FROM_RIGHT
                    durationMs = 120
                    setGestureNavigation(
                        enabled = false,
                        edgeWidth = 24f,
                        threshold = 0.35f,
                        onPop = null,
                        onTransitionEnd = { ended.countDown() },
                        onGestureStart = null,
                        onGestureEnd = null,
                        onGestureCancel = null,
                    )
                }
                activity.host.addView(navigation)
                first = View(activity)
                second = View(activity)
                navigation.insert(first, 0)
                navigation.insert(second, 1)
                navigation.viewTreeObserver.addOnPreDrawListener(sample)
                navigation.navigate(1)
            }

            assertTrue("push transition must end", ended.await(5, TimeUnit.SECONDS))
            awaitFrames(instrumentation)
            onMain(instrumentation) {
                navigation.viewTreeObserver.removeOnPreDrawListener(sample)
                // The outgoing screen may be composited from a hardware layer
                // while it moves; it is never drawn into a software bitmap.
                assertTrue(layerTypes.toString(), View.LAYER_TYPE_SOFTWARE !in layerTypes)
                assertEquals(View.LAYER_TYPE_NONE, first.layerType)
                assertEquals(View.LAYER_TYPE_NONE, second.layerType)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun reorderingAPrewarmedRouteKeepsItAttachedAndItsLayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var current: View
            lateinit var prewarmed: View
            var detaches = 0
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity)
                activity.host.addView(navigation, FrameLayout.LayoutParams(390, 800))
                current = View(activity)
                prewarmed = View(activity).apply {
                    addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) = Unit
                        override fun onViewDetachedFromWindow(view: View) {
                            detaches++
                        }
                    })
                }
                navigation.insert(current, 0)
                navigation.insert(prewarmed, 0)
            }
            // Mounted outside a frame: the next frame lays out the mount, the
            // one after it the hidden route.
            awaitFrames(instrumentation, 2)
            onMain(instrumentation) {
                // Mounted hidden: laid out at full size on the frame after its mount.
                assertEquals(View.INVISIBLE, prewarmed.visibility)
                assertEquals(390, prewarmed.width)
                assertEquals(800, prewarmed.height)
                navigation.reorderRoute(prewarmed, 1)
                assertSame(prewarmed, navigation.getChildAt(1))
                assertTrue(prewarmed.isAttachedToWindow)
            }
            awaitFrames(instrumentation)
            onMain(instrumentation) {
                assertEquals(0, detaches)
                assertEquals(800, prewarmed.height)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun hiddenRouteIsNotLaidOutInTheFrameThatMountsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var navigation: PamNavigationHost
            lateinit var hidden: View
            var sizeInMountFrame = -1
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity)
                activity.host.addView(navigation, FrameLayout.LayoutParams(390, 800))
                navigation.insert(View(activity), 0)
            }
            awaitFrames(instrumentation)
            onMain(instrumentation) {
                hidden = View(activity)
                navigation.insert(hidden, 0)
                navigation.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        navigation.viewTreeObserver.removeOnPreDrawListener(this)
                        sizeInMountFrame = hidden.height
                        return true
                    }
                })
            }
            awaitFrames(instrumentation, 2)
            onMain(instrumentation) {
                assertEquals(0, sizeInMountFrame)
                assertEquals(800, hidden.height)
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun retiringRouteDismissesServedImeEvenAfterAndroidAlreadyClearedFocus() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var source: FrameLayout
        lateinit var input: PamEditText
        try {
            onMain(instrumentation) {
                source = FrameLayout(activity)
                input = PamEditText(activity).apply { setText("retained search") }
                source.addView(input, FrameLayout.LayoutParams(600, 140))
                activity.host.addView(source)
            }
            awaitFrames(instrumentation, 2)
            onMain(instrumentation) {
                assertTrue(input.requestFocus())
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            awaitCondition(instrumentation, "IME must actually be visible before retiring the route") {
                input.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            }
            onMain(instrumentation) {
                // Android navigation can clear editor focus before dispatching
                // aggregated visibility, while IMM still serves that editor.
                input.clearFocus()
                source.visibility = View.INVISIBLE
            }
            awaitCondition(instrumentation, "A hidden unfocused served editor must release the IME") {
                input.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false
            }
            onMain(instrumentation) {
                assertTrue(!input.hasFocus())
                assertEquals("retained search", input.text.toString())
            }
        } finally { activity.finish() }
    }

    private fun awaitCondition(instrumentation: Instrumentation, message: String, condition: () -> Boolean) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000L
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            var matches = false
            onMain(instrumentation) { matches = condition() }
            if (matches) return
            Thread.sleep(40)
        }
        throw AssertionError(message)
    }

    @Test
    fun retainedRouteReleasesEditorFocusWithoutLosingDraft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var navigation: PamNavigationHost
        lateinit var source: FrameLayout
        lateinit var destination: FrameLayout
        lateinit var input: PamEditText
        try {
            onMain(instrumentation) {
                navigation = PamNavigationHost(activity).apply {
                    operation = OPERATION_PUSH
                    transition = TRANSITION_NONE
                }
                activity.host.addView(navigation)
                source = FrameLayout(activity)
                destination = FrameLayout(activity)
                input = PamEditText(activity).apply { setText("retained draft") }
                source.addView(input, FrameLayout.LayoutParams(300, 100))
                navigation.insert(source, 0)
                navigation.insert(destination, 1)
            }
            awaitFrames(instrumentation, 2)
            onMain(instrumentation) {
                assertTrue(input.requestFocus())
                input.onInputConnectionCreated = { /* A visible input connection is permitted before navigation commits. */ }
                navigation.navigate(1)
            }
            awaitFrames(instrumentation, 3)
            onMain(instrumentation) {
                assertEquals(View.INVISIBLE, source.visibility)
                assertTrue("Hidden route input cannot retain focus", !input.hasFocus())
                assertTrue("Hidden IME hook is released", input.onInputConnectionCreated == null)
                assertEquals("retained draft", input.text.toString())
                source.visibility = View.VISIBLE
                destination.visibility = View.INVISIBLE
                assertTrue("Returning visible editor remains focusable", input.requestFocus())
                assertEquals("retained draft", input.text.toString())
            }
        } finally { activity.finish() }
    }

    @Test
    fun retiringOldRouteDoesNotBlurDestinationEditor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var source: FrameLayout
        lateinit var first: PamEditText
        lateinit var next: PamEditText
        try {
            onMain(instrumentation) {
                source = FrameLayout(activity)
                first = PamEditText(activity)
                next = PamEditText(activity)
                source.addView(first, FrameLayout.LayoutParams(300, 100))
                activity.host.addView(source)
                activity.host.addView(next, FrameLayout.LayoutParams(300, 100))
            }
            awaitFrames(instrumentation, 2)
            onMain(instrumentation) {
                assertTrue(first.requestFocus())
                assertTrue(next.requestFocus())
                (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showSoftInput(next, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                awaitCondition(instrumentation, "Destination IME is shown") {
                    next.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
                }
            }
            onMain(instrumentation) {
                source.visibility = View.INVISIBLE
                assertTrue("Visible destination keeps focus", next.hasFocus())
                assertTrue(!first.hasFocus())
            }
            awaitFrames(instrumentation, 8)
            onMain(instrumentation) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    assertTrue("Retiring source preserves destination keyboard", next.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true)
                }
            }
        } finally { activity.finish() }
    }

    @Test
    fun lateAutoFocusCannotReviveHiddenInputButVisibleAutoFocusWorks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        lateinit var source: FrameLayout
        lateinit var input: PamEditText
        lateinit var renderer: PamRenderer
        val focus = PamRenderer::class.java.getDeclaredMethod(
            "attemptAutoFocus", View::class.java, java.lang.Long.TYPE, java.lang.Boolean.TYPE,
        ).apply { isAccessible = true }
        try {
            onMain(instrumentation) {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                source = FrameLayout(activity)
                input = PamEditText(activity)
                source.addView(input, FrameLayout.LayoutParams(300, 100))
                activity.host.addView(source)
            }
            awaitFrames(instrumentation, 2)
            onMain(instrumentation) {
                focus.invoke(renderer, input, android.os.SystemClock.uptimeMillis() + 2_000L, false)
                source.visibility = View.INVISIBLE
            }
            awaitFrames(instrumentation, 3)
            onMain(instrumentation) {
                assertTrue("Late focus callback cannot focus a hidden ancestor", !input.hasFocus())
                // Keep a real focus owner while revealing the route. Otherwise
                // Android may default-focus the only editor independently of
                // our cancelled callback, depending on the prior IME test.
                activity.host.isFocusableInTouchMode = true
                assertTrue(activity.host.requestFocus())
                source.visibility = View.VISIBLE
            }
            awaitFrames(instrumentation, 6)
            onMain(instrumentation) {
                assertTrue("Showing a retired route cannot restart its cancelled autofocus", !input.hasFocus())
                focus.invoke(renderer, input, android.os.SystemClock.uptimeMillis() + 2_000L, false)
            }
            awaitFrames(instrumentation, 3)
            onMain(instrumentation) {
                assertTrue("Visible autoFocus still focuses the input", input.hasFocus())
                source.visibility = View.INVISIBLE
            }
            awaitFrames(instrumentation, 3)
            onMain(instrumentation) {
                assertTrue("Pending IME callbacks cannot restore retired input focus", !input.hasFocus())
            }
        } finally { activity.finish() }
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
        const val OPERATION_IDLE = 1
        const val OPERATION_PUSH = 2
        const val OPERATION_POP = 3
        const val OPERATION_RESET = 5
        const val TRANSITION_SLIDE_FROM_RIGHT = 2
        const val TRANSITION_FADE = 5
        const val TRANSITION_NONE = 8
    }
}
