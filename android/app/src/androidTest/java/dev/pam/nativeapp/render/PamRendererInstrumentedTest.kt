package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.TextureView
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamRendererInstrumentedTest {
    @Test
    fun mediaPlayerUsesTextureBackedVideoThatParticipatesInAncestorClipping() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val cache = NativeMediaFileCache(activity)
        try {
            onMain(instrumentation) {
                val media = PamMediaView(activity, cache)
                activity.host.addView(media, FrameLayout.LayoutParams(200, 120))

                assertTrue(media.getChildAt(0) is TextureView)
            }
        } finally {
            cache.close()
            activity.finish()
        }
    }

    @Test
    fun richVirtualListUsesPerItemExtentsInsteadOfForcingItsEstimate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val list = PamRecyclerList(activity)
                activity.host.addView(
                    list,
                    FrameLayout.LayoutParams(
                        dp(activity.host, 300f),
                        dp(activity.host, 400f),
                    ),
                )
                list.setRowHeight(48f)
                list.setRichItems(
                    ids = listOf(101L, 102L),
                    extents = mapOf(101L to 100f, 102L to 180f),
                    mount = { id, holder ->
                        holder.addView(
                            View(activity).apply {
                                tag = id
                                background = ColorDrawable(Color.RED)
                            },
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                    },
                    unmount = { _, holder -> holder.removeAllViews() },
                )
                list.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        dp(activity.host, 300f),
                        View.MeasureSpec.EXACTLY,
                    ),
                    View.MeasureSpec.makeMeasureSpec(
                        dp(activity.host, 400f),
                        View.MeasureSpec.EXACTLY,
                    ),
                )
                list.layout(
                    0,
                    0,
                    dp(activity.host, 300f),
                    dp(activity.host, 400f),
                )

                assertEquals(
                    roundedDp(activity.host, 100f),
                    requireNotNull(list.findViewHolderForAdapterPosition(0))
                        .itemView.layoutParams.height,
                )
                assertEquals(
                    roundedDp(activity.host, 180f),
                    requireNotNull(list.findViewHolderForAdapterPosition(1))
                        .itemView.layoutParams.height,
                )
                assertTrue(list.clipChildren)
                assertTrue(
                    (
                        requireNotNull(list.findViewHolderForAdapterPosition(0))
                            .itemView as FrameLayout
                    ).clipChildren,
                )

                val retainedFirst = requireNotNull(
                    list.findViewHolderForAdapterPosition(0),
                ).itemView
                list.setRichItems(
                    ids = listOf(101L, 102L),
                    extents = mapOf(101L to 120f, 102L to 180f),
                    mount = { _, _ -> },
                    unmount = { _, _ -> },
                )
                list.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        dp(activity.host, 300f),
                        View.MeasureSpec.EXACTLY,
                    ),
                    View.MeasureSpec.makeMeasureSpec(
                        dp(activity.host, 400f),
                        View.MeasureSpec.EXACTLY,
                    ),
                )
                list.layout(
                    0,
                    0,
                    dp(activity.host, 300f),
                    dp(activity.host, 400f),
                )
                val updatedFirst = requireNotNull(
                    list.findViewHolderForAdapterPosition(0),
                ).itemView
                assertSame(retainedFirst, updatedFirst)
                assertEquals(
                    roundedDp(activity.host, 120f),
                    updatedFirst.layoutParams.height,
                )
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun richVirtualListClipsTranslatedDescendantsAtItsViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val parent = FrameLayout(activity).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                }
                val list = PamRecyclerList(activity)
                parent.addView(
                    list,
                    FrameLayout.LayoutParams(100, 100).apply {
                        topMargin = 50
                    },
                )
                list.setRowHeight(100f)
                list.setRichItems(
                    ids = listOf(101L),
                    extents = mapOf(101L to 100f),
                    mount = { _, holder ->
                        holder.addView(
                            View(activity).apply {
                                background = ColorDrawable(Color.RED)
                                translationY = -40f
                            },
                            FrameLayout.LayoutParams(100, 100),
                        )
                    },
                    unmount = { _, holder -> holder.removeAllViews() },
                )
                val overlayChild = View(activity).apply {
                    background = ColorDrawable(Color.RED)
                }
                list.overlay.add(overlayChild)
                overlayChild.layout(0, -40, 100, 60)
                parent.measure(
                    View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
                )
                parent.layout(0, 0, 100, 200)

                val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
                parent.draw(Canvas(bitmap))

                assertEquals(Color.TRANSPARENT, bitmap.getPixel(50, 30))
                assertEquals(Color.RED, bitmap.getPixel(50, 60))
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun overflowHiddenClipsChildrenToRoundedContainerAndCanBeDisabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val container = PamContainer(activity)
                val child = View(activity).apply {
                    background = ColorDrawable(Color.RED)
                }
                container.addView(
                    child,
                    FrameLayout.LayoutParams(100, 100),
                )
                container.measure(
                    View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                )
                container.layout(0, 0, 100, 100)
                container.setOverflowClip(true, FloatArray(8) { 30f })

                val clipped = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                container.draw(Canvas(clipped))
                assertEquals(Color.TRANSPARENT, clipped.getPixel(0, 0))
                assertEquals(Color.RED, clipped.getPixel(50, 50))

                container.setOverflowClip(false, FloatArray(8))
                val visible = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                container.draw(Canvas(visible))
                assertEquals(Color.RED, visible.getPixel(0, 0))
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun rendererKeepsRoundedOverflowClipInSyncWithPropertyUpdates() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                            Mutation.Create(
                                node(
                                    2,
                                    1,
                                    NodeKind.IMAGE_BACKGROUND,
                                    mapOf(
                                        PropKey.BORDER_RADIUS to PropValue.Decimal(30.0),
                                        PropKey.OVERFLOW to PropValue.Integer(2),
                                        PropKey.TEST_ID to PropValue.Text("rounded-overflow"),
                                    ),
                                ),
                            ),
                            Mutation.Create(
                                node(
                                    3,
                                    2,
                                    NodeKind.VIEW,
                                    mapOf(
                                        PropKey.BACKGROUND_COLOR to
                                            PropValue.Integer(Color.RED.toLong()),
                                    ),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(20f, 20f, 100f, 100f)),
                            Mutation.Layout(3, Frame(0f, 0f, 100f, 100f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
                val container = requireNotNull(
                    activity.host.findByTransitionName("rounded-overflow"),
                ) as PamContainer
                activity.host.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        dp(activity.host, 360f),
                        View.MeasureSpec.EXACTLY,
                    ),
                    View.MeasureSpec.makeMeasureSpec(
                        dp(activity.host, 720f),
                        View.MeasureSpec.EXACTLY,
                    ),
                )
                activity.host.layout(
                    0,
                    0,
                    dp(activity.host, 360f),
                    dp(activity.host, 720f),
                )
                val clipped = Bitmap.createBitmap(
                    container.width,
                    container.height,
                    Bitmap.Config.ARGB_8888,
                )
                container.draw(Canvas(clipped))
                assertEquals(Color.TRANSPARENT, clipped.getPixel(0, 0))

                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Update(2, PropKey.OVERFLOW, null),
                        ),
                    ),
                )
                val visible = Bitmap.createBitmap(
                    container.width,
                    container.height,
                    Bitmap.Config.ARGB_8888,
                )
                container.draw(Canvas(visible))
                assertEquals(Color.RED, visible.getPixel(0, 0))
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun imageUsesEngineFrameInsteadOfIntrinsicDrawableBounds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val image = PamImageView(activity)
                assertFalse(image.adjustViewBounds)
                assertEquals(android.widget.ImageView.ScaleType.CENTER_CROP, image.scaleType)
            }
        } finally {
            activity.finish()
        }
    }

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

    private fun roundedDp(view: View, value: Float): Int =
        (value * view.resources.displayMetrics.density + 0.5f).toInt()

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
