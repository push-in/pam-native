package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.graphics.drawable.RippleDrawable
import android.widget.Button
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.NodeKind
import dev.pam.nativeapp.protocol.NodeSpec
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamRendererInstrumentedTest {
    @Test
    fun rippleDirectiveUsesNativeDrawableWithoutPhpGestureRoundTrips() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val renderer = PamRenderer(activity, activity.host) { _, _, _ ->
                    throw AssertionError("Ripple must not dispatch an application event")
                }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                            Mutation.Create(
                                node(
                                    2,
                                    1,
                                    NodeKind.VIEW,
                                    mapOf(
                                        PropKey.RIPPLE_COLOR to PropValue.Integer(0x33000000),
                                        PropKey.RIPPLE_ALPHA to PropValue.Decimal(0.12),
                                        PropKey.TEST_ID to PropValue.Text("ripple-target"),
                                    ),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(20f, 20f, 120f, 48f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
                val target = requireNotNull(
                    activity.host.findByTransitionName("ripple-target"),
                )
                assertTrue(target.background is RippleDrawable)
                assertTrue(target.isClickable)
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun clickOutsideObservesRootWithoutConsumingChildTouchesAndDetaches() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val events = ArrayList<Pair<Long, Int>>()
                val renderer = PamRenderer(activity, activity.host) { id, kind, _ ->
                    events += id to kind
                }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                            Mutation.Create(
                                node(
                                    2,
                                    1,
                                    NodeKind.VIEW,
                                    mapOf(
                                        PropKey.ON_CLICK_OUTSIDE to PropValue.Flag(true),
                                        PropKey.TEST_ID to PropValue.Text("outside-target"),
                                    ),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(20f, 20f, 100f, 100f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
                fun tap(x: Float, y: Float) {
                    val now = android.os.SystemClock.uptimeMillis()
                    activity.host.dispatchTouchEvent(
                        MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0),
                    )
                }
                val target = requireNotNull(
                    activity.host.findByTransitionName("outside-target"),
                )
                val hostWidth = activity.host.width.coerceAtLeast(dp(activity.host, 360f))
                val hostHeight = activity.host.height.coerceAtLeast(dp(activity.host, 720f))
                activity.host.measure(
                    View.MeasureSpec.makeMeasureSpec(hostWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(hostHeight, View.MeasureSpec.EXACTLY),
                )
                activity.host.layout(0, 0, hostWidth, hostHeight)
                assertTrue(target.width > 0 && target.height > 0)
                val location = IntArray(2)
                val hostLocation = IntArray(2)
                target.getLocationOnScreen(location)
                activity.host.getLocationOnScreen(hostLocation)
                tap(
                    location[0] - hostLocation[0] + target.width / 2f,
                    location[1] - hostLocation[1] + target.height / 2f,
                )
                assertTrue(events.isEmpty())
                tap(
                    (location[0] - hostLocation[0] + target.width + dp(activity.host, 40f)).toFloat(),
                    (location[1] - hostLocation[1] + target.height + dp(activity.host, 40f)).toFloat(),
                )
                assertEquals(listOf(2L to 35), events)
                renderer.commit(listOf(listOf(Mutation.Remove(2))))
                tap(
                    (location[0] - hostLocation[0] + target.width + dp(activity.host, 40f)).toFloat(),
                    (location[1] - hostLocation[1] + target.height + dp(activity.host, 40f)).toFloat(),
                )
                assertEquals(1, events.size)
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun rendererRetainsNativeIdentityAcrossUpdatesAndRoutesPresses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val events = ArrayList<Triple<Long, Int, ByteArray>>()
                val renderer = PamRenderer(activity, activity.host) { id, kind, payload ->
                    events += Triple(id, kind, payload)
                }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                            Mutation.Create(
                                node(
                                    id = 2,
                                    parent = 1,
                                    kind = NodeKind.BUTTON,
                                    properties = mapOf(
                                        PropKey.TEXT to PropValue.Text("Pay"),
                                        PropKey.ON_PRESS to PropValue.Flag(true),
                                        PropKey.TEST_ID to PropValue.Text("pay-button"),
                                    ),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(24f, 32f, 160f, 48f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )

                val original = activity.host.findByTransitionName("pay-button")
                assertTrue(original is Button)
                assertEquals("Pay", (original as Button).text.toString())
                assertEquals(dp(activity.host, 160f), original.layoutParams.width)
                assertEquals(dp(activity.host, 48f), original.layoutParams.height)
                assertTrue(original.performClick())
                assertEquals(1, events.size)
                assertEquals(2L, events.single().first)
                assertEquals(1, events.single().second)
                assertTrue(events.single().third.isEmpty())

                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Update(2, PropKey.TEXT, PropValue.Text("Paid")),
                            Mutation.Layout(2, Frame(24f, 32f, 184f, 48f)),
                        ),
                    ),
                )
                val updated = activity.host.findByTransitionName("pay-button")
                assertSame(original, updated)
                assertEquals("Paid", (updated as Button).text.toString())
                assertEquals(dp(activity.host, 184f), updated.layoutParams.width)

                renderer.commit(listOf(listOf(Mutation.Remove(2))))
                assertNull(activity.host.findByTransitionName("pay-button"))
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    private fun node(
        id: Long,
        parent: Long,
        kind: NodeKind,
        properties: Map<PropKey, PropValue> = emptyMap(),
    ): NodeSpec = NodeSpec(
        id = id,
        parent = parent,
        index = 0,
        kind = kind,
        properties = properties,
    )

    private fun launchActivity(instrumentation: Instrumentation): PamTestActivity =
        instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as PamTestActivity

    private fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
        instrumentation.runOnMainSync(block)
    }

    private fun dp(view: View, value: Float): Int =
        (value * view.resources.displayMetrics.density).toInt()

    private fun View.findByTransitionName(name: String): View? {
        if (transitionName == name) {
            return this
        }
        if (this !is ViewGroup) {
            return null
        }
        for (index in 0 until childCount) {
            getChildAt(index).findByTransitionName(name)?.let { return it }
        }
        return null
    }
}
