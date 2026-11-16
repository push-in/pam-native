package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.EventKind
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A presented BottomSheet unmounted by `p-if` (the engine removes its
 * subtree) plays the same exit as `visible = false`: its window stays while
 * pictures of its content slide down and the backdrop fades, takes no
 * touches meanwhile, and goes when the exit ends. The PHP side already
 * closed it, so nothing is notified.
 */
@RunWith(AndroidJUnit4::class)
class PamModalRemovalExitInstrumentedTest {
    private class Sample(val translationY: Float, val backdropAlpha: Int, val attached: Boolean)

    @Test
    fun removingAPresentedSheetKeepsItsWindowWhileTheExitPlays() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeAnimations(instrumentation)
        val activity = launchActivity(instrumentation)
        val events = Collections.synchronizedList(ArrayList<Int>())
        lateinit var renderer: PamRenderer
        PamMotionPolicy.reduceMotionOverride = false
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, kind, _ -> events += kind }
                renderer.commit(listOf(screen(activity) + sheet(activity)))
            }
            awaitEntrance(instrumentation, renderer)

            lateinit var host: PamModalHost
            lateinit var decor: View
            val samples = Collections.synchronizedList(ArrayList<Sample>())
            val done = CountDownLatch(1)
            instrumentation.runOnMainSync {
                host = modalHost(renderer)!!
                val content = content(host)
                decor = content.rootView
                renderer.commit(listOf(removeSheet()))

                assertFalse("the app no longer has the sheet", renderer.hasPresentedModal())
                assertTrue("the unmounted sheet plays its exit", host.isExitingAfterRemoval())
                assertTrue("its window stays for the exit", decor.isAttachedToWindow)
                val flags = (decor.layoutParams as WindowManager.LayoutParams).flags
                assertTrue(
                    "touches reach the screen below",
                    flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0,
                )
                val ghost = content.getChildAt(0)
                assertFalse("a picture took the removed sheet's place", ghost is ViewGroup)
                assertTrue("the picture has the sheet's size", ghost.width > 0 && ghost.height > 0)
                Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        val attached = decor.isAttachedToWindow
                        samples += Sample(
                            ghost.translationY,
                            backdropAlpha(host),
                            attached,
                        )
                        if (!attached || samples.size > 240) done.countDown() else {
                            Choreographer.getInstance().postFrameCallback(this)
                        }
                    }
                })
            }
            assertTrue("the window goes when the exit ends", done.await(5, TimeUnit.SECONDS))
            val playing = samples.filter { it.attached }
            assertTrue("the exit drew ${playing.size} frames", playing.size >= 3)
            assertTrue(
                "the sheet slides down (${playing.first().translationY} -> ${playing.last().translationY})",
                playing.last().translationY > playing.first().translationY,
            )
            assertTrue(
                "the backdrop fades (${playing.first().backdropAlpha} -> ${playing.last().backdropAlpha})",
                playing.last().backdropAlpha < playing.first().backdropAlpha,
            )
            instrumentation.runOnMainSync {
                assertFalse(decor.isAttachedToWindow)
                assertFalse(host.isExitingAfterRemoval())
            }
            Thread.sleep(100)
            assertNoDismissEvents(events)
            instrumentation.runOnMainSync { renderer.close() }
        } finally {
            PamMotionPolicy.reduceMotionOverride = null
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun reopeningDuringTheExitPresentsANewSheetAboveIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeAnimations(instrumentation)
        val activity = launchActivity(instrumentation)
        val events = Collections.synchronizedList(ArrayList<Int>())
        lateinit var renderer: PamRenderer
        PamMotionPolicy.reduceMotionOverride = false
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, kind, _ -> events += kind }
                renderer.commit(listOf(screen(activity) + sheet(activity)))
            }
            awaitEntrance(instrumentation, renderer)

            lateinit var exiting: PamModalHost
            lateinit var oldDecor: View
            instrumentation.runOnMainSync {
                exiting = modalHost(renderer)!!
                oldDecor = content(exiting).rootView
                renderer.commit(listOf(removeSheet()))
            }
            // The same sheet (same keys, same ids) opens again a frame later.
            Thread.sleep(32)
            instrumentation.runOnMainSync {
                assertTrue("still exiting when it reopens", exiting.isExitingAfterRemoval())
                renderer.commit(listOf(sheet(activity)))
            }
            awaitEntrance(instrumentation, renderer)
            waitUntil(instrumentation, "the old window goes") { !oldDecor.isAttachedToWindow }
            instrumentation.runOnMainSync {
                val reopened = modalHost(renderer)!!
                assertNotSame(exiting, reopened)
                val newDecor = content(reopened).rootView
                assertNotSame("the reopened sheet has its own window", oldDecor, newDecor)
                assertTrue(newDecor.isAttachedToWindow)
                assertTrue(reopened.isPresented())
                assertFalse(exiting.isExitingAfterRemoval())
                assertEquals(
                    "the reopened sheet is fully shown",
                    shownBackdropAlpha(reopened),
                    backdropAlpha(reopened),
                )
            }
            waitUntil(instrumentation, "the reopened sheet takes focus") {
                content(modalHost(renderer)!!).rootView.hasWindowFocus()
            }
            Thread.sleep(100)
            assertNoDismissEvents(events)
            instrumentation.runOnMainSync { renderer.close() }
        } finally {
            PamMotionPolicy.reduceMotionOverride = null
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun reducedMotionRemovesTheWindowAtOnce() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val events = Collections.synchronizedList(ArrayList<Int>())
        lateinit var renderer: PamRenderer
        PamMotionPolicy.reduceMotionOverride = true
        try {
            instrumentation.runOnMainSync {
                renderer = PamRenderer(activity, activity.host) { _, kind, _ -> events += kind }
                renderer.commit(listOf(screen(activity) + sheet(activity)))
            }
            waitUntil(instrumentation, "sheet presented") { renderer.hasPresentedModal() }
            instrumentation.runOnMainSync {
                val host = modalHost(renderer)!!
                val decor = content(host).rootView
                renderer.commit(listOf(removeSheet()))
                assertFalse(host.isExitingAfterRemoval())
                assertFalse("no exit: the window goes with the sheet", decor.isAttachedToWindow)
            }
            Thread.sleep(100)
            assertNoDismissEvents(events)
            instrumentation.runOnMainSync { renderer.close() }
        } finally {
            PamMotionPolicy.reduceMotionOverride = null
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun screen(activity: PamTestActivity): List<Mutation> {
        val density = activity.resources.displayMetrics.density
        return listOf(
            Mutation.Create(node(1, 0, NodeKind.SCREEN)),
            Mutation.Layout(1, Frame(0f, 0f, activity.host.width / density, activity.host.height / density)),
            Mutation.SetRoot(1),
        )
    }

    private fun sheet(activity: PamTestActivity): List<Mutation> {
        val density = activity.resources.displayMetrics.density
        val width = activity.host.width / density
        val height = activity.host.height / density
        return listOf(
            Mutation.Create(node(2, 1, NodeKind.MODAL, mapOf(
                PropKey.VISIBLE to PropValue.Flag(true),
                PropKey.MODAL_PRESENTATION to PropValue.Integer(3),
                PropKey.MODAL_ANIMATION_TYPE to PropValue.Integer(2),
                PropKey.ON_MODAL_REQUEST_CLOSE to PropValue.Flag(true),
                PropKey.ON_MODAL_DISMISS to PropValue.Flag(true),
                PropKey.ON_BOTTOM_SHEET_DISMISS to PropValue.Flag(true),
            ))),
            Mutation.Create(node(3, 2, NodeKind.VIEW, mapOf(
                PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFFFFFFFFL),
            ))),
            Mutation.Create(node(4, 3, NodeKind.VIEW, mapOf(
                PropKey.BACKGROUND_COLOR to PropValue.Integer(0xFF2E7D32L),
            ))),
            Mutation.Layout(2, Frame(0f, 0f, width, height)),
            Mutation.Layout(3, Frame(0f, 0f, width, height / 2f)),
            Mutation.Layout(4, Frame(16f, 32f, width - 32f, 48f)),
        )
    }

    /** The engine removes a subtree children first, one Remove per node. */
    private fun removeSheet(): List<Mutation> = listOf(Mutation.Remove(4), Mutation.Remove(3), Mutation.Remove(2))

    /** The entrance slid the sheet up and faded the backdrop in. */
    private fun awaitEntrance(instrumentation: Instrumentation, renderer: PamRenderer) {
        var state = "not presented"
        waitUntil(instrumentation, { "sheet entrance settled ($state)" }) {
            val host = modalHost(renderer)?.takeIf { it.isPresented() } ?: return@waitUntil false
            val content = content(host)
            val sheet = content.getChildAt(0) ?: return@waitUntil false
            state = "children=${content.childCount} sheet=${sheet.javaClass.simpleName} " +
                "y=${sheet.translationY} h=${sheet.height} backdrop=${backdropAlpha(host)}"
            sheet.translationY == 0f && backdropAlpha(host) == shownBackdropAlpha(host)
        }
    }

    private fun assertNoDismissEvents(events: List<Int>) {
        val dismissals = synchronized(events) {
            events.filter {
                it == EventKind.MODAL_DISMISS.value ||
                    it == EventKind.BOTTOM_SHEET_DISMISS.value ||
                    it == EventKind.MODAL_REQUEST_CLOSE.value
            }
        }
        assertEquals("the app closed it: nothing is notified", emptyList<Int>(), dismissals)
    }

    private fun modalHost(renderer: PamRenderer): PamModalHost? {
        val field = PamRenderer::class.java.getDeclaredField("views").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val views = field.get(renderer) as android.util.LongSparseArray<View>
        return views.get(2L) as? PamModalHost
    }

    private fun content(host: PamModalHost): ViewGroup =
        PamModalHost::class.java.getDeclaredField("content").apply { isAccessible = true }.get(host) as ViewGroup

    private fun backdropAlpha(host: PamModalHost): Int =
        (PamModalHost::class.java.getDeclaredField("backdropDrawable").apply { isAccessible = true }
            .get(host) as ColorDrawable).alpha

    /** A shown backdrop draws its colour's own alpha (ColorDrawable.getAlpha is modulated). */
    private fun shownBackdropAlpha(host: PamModalHost): Int =
        android.graphics.Color.alpha(
            PamModalHost::class.java.getDeclaredField("backdropColor").apply { isAccessible = true }.getInt(host),
        )

    private fun waitUntil(
        instrumentation: Instrumentation,
        description: String,
        condition: () -> Boolean,
    ) = waitUntil(instrumentation, { description }, condition)

    private fun waitUntil(
        instrumentation: Instrumentation,
        description: () -> String,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var satisfied = false
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(8)
        }
        throw AssertionError("Timed out waiting for ${description()}")
    }

    private fun assumeAnimations(instrumentation: Instrumentation) {
        assumeTrue(
            Settings.Global.getFloat(
                instrumentation.targetContext.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) > 0f,
        )
    }

    private fun launchActivity(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ): NodeSpec = NodeSpec(id = id, parent = parent, index = 0, kind = kind, properties = properties)
}
