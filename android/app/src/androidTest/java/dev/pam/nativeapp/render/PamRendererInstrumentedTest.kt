package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.TextureView
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.R
import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.EventKind
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
    fun semanticTextColorsReachLabelsButtonsAndInputsWithoutLoss() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val color = 0xFF4ADE80.toInt()
        try {
            onMain(instrumentation) {
                val controls = listOf(
                    TextView(activity),
                    Button(activity),
                    EditText(activity),
                )
                controls.forEach { control ->
                    applySemanticTextColor(control, color)
                    assertEquals(color, control.currentTextColor)
                }
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun largeAccessibilityScaleHonorsOptOutAndMaximumMultiplier() {
        assertEquals(1f, resolvedFontScale(false, 3f, 1.5f), 0.0001f)
        assertEquals(1.5f, resolvedFontScale(true, 3f, 1.5f), 0.0001f)
        assertEquals(3f, resolvedFontScale(true, 3f, 0f), 0.0001f)
        assertEquals(1f, resolvedFontScale(true, 3f, 0.5f), 0.0001f)
        assertEquals(0.85f, resolvedFontScale(true, 0.85f, 1.5f), 0.0001f)
    }

    @Test
    fun exposesSemanticTalkBackRoleStateRangeAndImportance() {
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
                                    NodeKind.TEXT,
                                    mapOf(
                                        PropKey.TEXT to PropValue.Text("Upload"),
                                        PropKey.ACCESSIBILITY_LABEL to PropValue.Text("Upload progress"),
                                        PropKey.ACCESSIBILITY_ROLE to PropValue.Integer(8),
                                        PropKey.ACCESSIBILITY_IMPORTANCE to PropValue.Integer(2),
                                        PropKey.ACCESSIBILITY_LIVE_REGION to PropValue.Integer(3),
                                        PropKey.ACCESSIBILITY_CHECKED_STATE to PropValue.Integer(3),
                                        PropKey.ACCESSIBILITY_EXPANDED to PropValue.Flag(false),
                                        PropKey.ACCESSIBILITY_BUSY to PropValue.Flag(true),
                                        PropKey.ACCESSIBILITY_VALUE_MIN to PropValue.Decimal(0.0),
                                        PropKey.ACCESSIBILITY_VALUE_MAX to PropValue.Decimal(100.0),
                                        PropKey.ACCESSIBILITY_VALUE_NOW to PropValue.Decimal(40.0),
                                        PropKey.ACCESSIBILITY_VALUE_TEXT to PropValue.Text("40 percent"),
                                        PropKey.SELECTED to PropValue.Flag(true),
                                        PropKey.ENABLED to PropValue.Flag(false),
                                        PropKey.TEST_ID to PropValue.Text("accessible-state"),
                                    ),
                                ),
                            ),
                            Mutation.Create(
                                node(
                                    3,
                                    1,
                                    NodeKind.TEXT,
                                    mapOf(
                                        PropKey.TEXT to PropValue.Text("Decorative"),
                                        PropKey.ACCESSIBILITY_IMPORTANCE to PropValue.Integer(4),
                                        PropKey.TEST_ID to PropValue.Text("hidden-state"),
                                    ),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(16f, 16f, 200f, 48f)),
                            Mutation.Layout(3, Frame(16f, 72f, 200f, 48f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )

                val view = requireNotNull(activity.host.findByTransitionName("accessible-state"))
                val info = view.createAccessibilityNodeInfo()
                assertEquals("Upload progress", view.contentDescription)
                assertEquals("android.widget.CheckBox", info.className.toString())
                assertTrue(info.isCheckable)
                assertTrue(info.isSelected)
                assertFalse(info.isEnabled)
                assertEquals(0f, info.rangeInfo?.min)
                assertEquals(100f, info.rangeInfo?.max)
                assertEquals(40f, info.rangeInfo?.current)
                assertEquals(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE, view.accessibilityLiveRegion)
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, view.importantForAccessibility)
                assertTrue(
                    info.actionList.any {
                        it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND.id
                    },
                )
                val stateDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    info.stateDescription
                } else {
                    info.extras.getCharSequence(
                        "androidx.view.accessibility.AccessibilityNodeInfoCompat.STATE_DESCRIPTION_KEY",
                    )
                }
                assertTrue(stateDescription?.contains(activity.getString(R.string.pam_accessibility_mixed)) == true)
                assertTrue(stateDescription?.contains(activity.getString(R.string.pam_accessibility_busy)) == true)
                assertTrue(stateDescription?.contains(activity.getString(R.string.pam_accessibility_collapsed)) == true)
                assertTrue(stateDescription?.contains("40 percent") == true)

                val hidden = requireNotNull(activity.host.findByTransitionName("hidden-state"))
                assertEquals(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                    hidden.importantForAccessibility,
                )
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun exposesAndDispatchesBoundedTalkBackCustomActions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val events = mutableListOf<Triple<Long, Int, ByteArray>>()
                val renderer = PamRenderer(activity, activity.host) { id, kind, payload ->
                    events += Triple(id, kind, payload)
                }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                            Mutation.Create(
                                node(
                                    2,
                                    1,
                                    NodeKind.TEXT,
                                    mapOf(
                                        PropKey.TEXT to PropValue.Text("Message"),
                                        PropKey.ACCESSIBILITY_ACTIONS to PropValue.Text(
                                            """[{"name":"archive","label":"Archive message"}]""",
                                        ),
                                        PropKey.ON_ACCESSIBILITY_ACTION to PropValue.Flag(true),
                                        PropKey.TEST_ID to PropValue.Text("message-actions"),
                                    ),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(16f, 16f, 200f, 48f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
                val view = requireNotNull(activity.host.findByTransitionName("message-actions"))
                val custom = requireNotNull(
                    view.createAccessibilityNodeInfo().actionList.firstOrNull {
                        it.label?.toString() == "Archive message"
                    },
                )
                assertTrue(view.performAccessibilityAction(custom.id, null))
                assertEquals(1, events.size)
                assertEquals(2L, events.single().first)
                assertEquals(EventKind.ACCESSIBILITY_ACTION.value, events.single().second)
                assertEquals("archive", events.single().third.toString(Charsets.UTF_8))
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun autoFocusInputWaitsUntilItsReactiveScreenIsAttached() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var renderer: PamRenderer
            onMain(instrumentation) {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                            Mutation.Create(node(3, 0, NodeKind.SCREEN)),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(3, Frame(0f, 0f, 360f, 720f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(
                                node(
                                    2,
                                    3,
                                    NodeKind.INPUT,
                                    mapOf(
                                        PropKey.AUTO_FOCUS to PropValue.Flag(true),
                                        PropKey.TEST_ID to PropValue.Text("dynamic-search"),
                                    ),
                                ),
                            ),
                            Mutation.Layout(2, Frame(16f, 24f, 328f, 48f)),
                        ),
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                renderer.commit(listOf(listOf(Mutation.SetRoot(3))))
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(150)
            onMain(instrumentation) {
                val input = activity.host.findByTransitionName("dynamic-search")
                assertTrue(input is PamEditText)
                assertTrue("The dynamically inserted input must retain autofocus.", input?.hasFocus() == true)
                assertTrue((input as PamEditText).showSoftInputOnFocus)
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun autoFocusInputRetriesWhenRetainedAncestorBecomesVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var renderer: PamRenderer
            onMain(instrumentation) {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                            Mutation.Create(
                                node(
                                    2,
                                    1,
                                    NodeKind.VIEW,
                                    mapOf(PropKey.VISIBLE to PropValue.Flag(false)),
                                ),
                            ),
                            Mutation.Create(
                                node(
                                    3,
                                    2,
                                    NodeKind.INPUT,
                                    mapOf(
                                        PropKey.AUTO_FOCUS to PropValue.Flag(true),
                                        PropKey.TEST_ID to PropValue.Text("retained-search"),
                                    ),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(0f, 0f, 360f, 96f)),
                            Mutation.Layout(3, Frame(16f, 24f, 328f, 48f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(1_150)
            onMain(instrumentation) {
                val input = activity.host.findByTransitionName("retained-search")
                assertTrue(input is PamEditText)
                input?.clearFocus()
                assertFalse("The stale hidden focus must be cleared for the regression.", input?.hasFocus() == true)
                renderer.commit(
                    listOf(
                        listOf(Mutation.Update(2, PropKey.VISIBLE, PropValue.Flag(true))),
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                val input = activity.host.findByTransitionName("retained-search")
                assertTrue(input is PamEditText)
                assertTrue("Visible retained input must recover autofocus.", input?.hasFocus() == true)
                assertTrue((input as PamEditText).showSoftInputOnFocus)
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun reactiveScrollRequestIsNotOverwrittenByRetainedViewport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            lateinit var renderer: PamRenderer
            lateinit var scroll: PamScrollContainer
            onMain(instrumentation) {
                renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(node(1, 0, NodeKind.SCREEN)),
                            Mutation.Create(
                                node(
                                    2,
                                    1,
                                    NodeKind.SCROLL,
                                    mapOf(PropKey.TEST_ID to PropValue.Text("timeline")),
                                ),
                            ),
                            Mutation.Create(node(3, 2, NodeKind.COLUMN)),
                            Mutation.Create(
                                node(
                                    4,
                                    3,
                                    NodeKind.VIEW,
                                    mapOf(PropKey.TEST_ID to PropValue.Text("timeline-end")),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(0f, 0f, 360f, 400f)),
                            Mutation.Layout(3, Frame(0f, 0f, 360f, 1_400f)),
                            Mutation.Layout(4, Frame(0f, 1_200f, 1f, 1f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
                scroll = activity.host.findByTransitionName("timeline") as PamScrollContainer
                scroll.restoreOffsetPixels(0, dp(activity.host, 200f))
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(dp(activity.host, 200f), scroll.snapshotOffsetPixels().second)
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Update(
                                2,
                                PropKey.SCROLL_TARGET_TEST_ID,
                                PropValue.Text("timeline-end"),
                            ),
                            Mutation.Update(2, PropKey.SCROLL_REQUEST, PropValue.Integer(1)),
                        ),
                    ),
                )
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertTrue(
                    "Expected reactive target scroll, offset=${scroll.snapshotOffsetPixels().second}",
                    scroll.snapshotOffsetPixels().second > dp(activity.host, 600f),
                )
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun statusBarConfigurationFollowsTheActiveRetainedRoute() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(
                                node(
                                    1,
                                    0,
                                    NodeKind.NAVIGATION_HOST,
                                    mapOf(
                                        PropKey.NAVIGATION_DURATION_MS to PropValue.Integer(0),
                                    ),
                                ),
                            ),
                            Mutation.Create(node(2, 1, NodeKind.SCREEN)),
                            Mutation.Create(
                                node(
                                    3,
                                    2,
                                    NodeKind.STATUS_BAR,
                                    mapOf(
                                        PropKey.STATUS_BAR_COLOR to
                                            PropValue.Integer(Color.RED.toLong()),
                                        PropKey.STATUS_BAR_STYLE to
                                            PropValue.Integer(1L),
                                    ),
                                ),
                            ),
                            Mutation.Create(node(4, 1, NodeKind.SCREEN)),
                            Mutation.Create(
                                node(
                                    5,
                                    4,
                                    NodeKind.STATUS_BAR,
                                    mapOf(
                                        PropKey.STATUS_BAR_COLOR to
                                            PropValue.Integer(Color.BLUE.toLong()),
                                        PropKey.STATUS_BAR_STYLE to
                                            PropValue.Integer(2L),
                                    ),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(3, Frame(0f, 0f, 0f, 0f)),
                            Mutation.Layout(4, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(5, Frame(0f, 0f, 0f, 0f)),
                            Mutation.Update(
                                1,
                                PropKey.NAVIGATION_OPERATION,
                                PropValue.Integer(2),
                            ),
                            Mutation.Update(
                                1,
                                PropKey.NAVIGATION_REVISION,
                                PropValue.Integer(1),
                            ),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
                activity.host.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(
                    Color.BLUE,
                    if (Build.VERSION.SDK_INT >= 35) {
                        (activity.window.decorView.background as ColorDrawable).color
                    } else {
                        @Suppress("DEPRECATION")
                        activity.window.statusBarColor
                    },
                )
                assertStatusBarUsesDarkIcons(activity, false)

                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Update(
                                1,
                                PropKey.NAVIGATION_OPERATION,
                                PropValue.Integer(3),
                            ),
                            Mutation.Update(
                                1,
                                PropKey.NAVIGATION_REVISION,
                                PropValue.Integer(2),
                            ),
                        ),
                    ),
                )
                activity.host.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(
                    Color.RED,
                    if (Build.VERSION.SDK_INT >= 35) {
                        (activity.window.decorView.background as ColorDrawable).color
                    } else {
                        @Suppress("DEPRECATION")
                        activity.window.statusBarColor
                    },
                )
                assertStatusBarUsesDarkIcons(activity, true)
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun assertStatusBarUsesDarkIcons(activity: PamTestActivity, expected: Boolean) {
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appearance = activity.window.insetsController?.systemBarsAppearance ?: 0
            appearance and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0
        } else {
            activity.window.decorView.systemUiVisibility and
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0
        }
        assertEquals(expected, enabled)
    }

    @Suppress("DEPRECATION")
    @Test
    fun statusBarColorWinsOverADifferentRootBackgroundAfterCommit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        try {
            onMain(instrumentation) {
                val renderer = PamRenderer(activity, activity.host) { _, _, _ -> }
                renderer.commit(
                    listOf(
                        listOf(
                            Mutation.Create(
                                node(
                                    1,
                                    0,
                                    NodeKind.VIEW,
                                    mapOf(
                                        PropKey.BACKGROUND_COLOR to
                                            PropValue.Integer(Color.WHITE.toLong()),
                                    ),
                                ),
                            ),
                            Mutation.Create(
                                node(
                                    2,
                                    1,
                                    NodeKind.STATUS_BAR,
                                    mapOf(
                                        PropKey.STATUS_BAR_COLOR to
                                            PropValue.Integer(Color.BLACK.toLong()),
                                        PropKey.STATUS_BAR_STYLE to
                                            PropValue.Integer(2L),
                                    ),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(0f, 0f, 0f, 0f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )
                activity.host.viewTreeObserver.dispatchOnPreDraw()
                assertEquals(
                    Color.BLACK,
                    if (Build.VERSION.SDK_INT >= 35) {
                        (activity.window.decorView.background as ColorDrawable).color
                    } else {
                        activity.window.statusBarColor
                    },
                )
                assertEquals(Color.BLACK, activity.host.statusBarSurfaceColor)
                if (activity.host.stableSafeAreaInsets.top > 0) {
                    val rendered = Bitmap.createBitmap(
                        activity.host.width,
                        activity.host.height,
                        Bitmap.Config.ARGB_8888,
                    )
                    activity.host.draw(Canvas(rendered))
                    assertEquals(Color.BLACK, rendered.getPixel(0, 0))
                }
                assertStatusBarUsesDarkIcons(activity, false)
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun intrinsicTextFollowsTheParentsRelevantCenteringAxis() {
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
                                    NodeKind.ROW,
                                    mapOf(PropKey.JUSTIFY_CONTENT to PropValue.Integer(2)),
                                ),
                            ),
                            Mutation.Create(
                                node(
                                    3,
                                    2,
                                    NodeKind.TEXT,
                                    mapOf(
                                        PropKey.TEXT to PropValue.Text("Centered"),
                                        PropKey.TEST_ID to PropValue.Text("intrinsic-centered"),
                                    ),
                                ),
                            ),
                            Mutation.Create(
                                node(
                                    4,
                                    2,
                                    NodeKind.TEXT,
                                    mapOf(
                                        PropKey.TEXT to PropValue.Text("Allocated"),
                                        PropKey.WIDTH to PropValue.Decimal(120.0),
                                        PropKey.TEST_ID to PropValue.Text("allocated-start"),
                                    ),
                                ),
                            ),
                            Mutation.Layout(1, Frame(0f, 0f, 360f, 720f)),
                            Mutation.Layout(2, Frame(0f, 0f, 360f, 80f)),
                            Mutation.Layout(3, Frame(70f, 0f, 100f, 40f)),
                            Mutation.Layout(4, Frame(170f, 0f, 120f, 40f)),
                            Mutation.SetRoot(1),
                        ),
                    ),
                )

                val intrinsic = requireNotNull(
                    activity.host.findByTransitionName("intrinsic-centered"),
                ) as TextView
                val allocated = requireNotNull(
                    activity.host.findByTransitionName("allocated-start"),
                ) as TextView
                assertEquals(
                    Gravity.CENTER_HORIZONTAL,
                    intrinsic.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK,
                )
                assertEquals(
                    Gravity.START,
                    allocated.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK,
                )
                renderer.close()
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun mediaPlayerUsesTextureBackedVideoThatParticipatesInAncestorClipping() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        val cache = NativeMediaFileCache(activity)
        val imageLoader = NativeImageLoader(activity)
        try {
            onMain(instrumentation) {
                val media = PamMediaView(activity, cache, imageLoader)
                activity.host.addView(media, FrameLayout.LayoutParams(200, 120))

                assertTrue(media.getChildAt(0) is TextureView)
                assertTrue(media.getChildAt(1) is PamImageView)
            }
        } finally {
            imageLoader.close()
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
    fun richVirtualListRemountsEmptyHoldersWhenRouteBecomesVisibleAgain() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = launchActivity(instrumentation)
        var mounts = 0
        lateinit var list: PamRecyclerList
        try {
            onMain(instrumentation) {
                list = PamRecyclerList(activity)
                activity.host.addView(
                    list,
                    FrameLayout.LayoutParams(
                        dp(activity.host, 300f),
                        dp(activity.host, 160f),
                    ),
                )
                list.setRichItems(
                    ids = listOf(101L),
                    extents = mapOf(101L to 72f),
                    mount = { id, holder ->
                        mounts += 1
                        holder.addView(View(activity).apply { tag = id })
                    },
                    unmount = { _, holder -> holder.removeAllViews() },
                )
                list.measure(
                    View.MeasureSpec.makeMeasureSpec(
                        dp(activity.host, 300f),
                        View.MeasureSpec.EXACTLY,
                    ),
                    View.MeasureSpec.makeMeasureSpec(
                        dp(activity.host, 160f),
                        View.MeasureSpec.EXACTLY,
                    ),
                )
                list.layout(
                    0,
                    0,
                    dp(activity.host, 300f),
                    dp(activity.host, 160f),
                )
                val holder = requireNotNull(list.findViewHolderForAdapterPosition(0))
                (holder.itemView as FrameLayout).removeAllViews()
                assertEquals(0, (holder.itemView as FrameLayout).childCount)
                list.onVisibilityAggregated(true)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                val holder = requireNotNull(list.findViewHolderForAdapterPosition(0))
                assertEquals(1, (holder.itemView as FrameLayout).childCount)
                assertTrue(mounts >= 2)
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
                assertFalse(target.isClickable)
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
