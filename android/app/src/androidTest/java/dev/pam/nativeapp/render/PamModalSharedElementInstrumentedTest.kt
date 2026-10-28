package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.provider.Settings
import android.view.Choreographer
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import dev.pam.nativeapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A `shared` tag pairs a screen with a Modal (a component, not a route): a
 * rounded cover avatar flies into the Modal's contained photo when it opens,
 * and back home when the Modal is unmounted or hidden.
 */
@RunWith(AndroidJUnit4::class)
class PamModalSharedElementInstrumentedTest {
    private class Scene(
        val host: ViewGroup,
        val avatar: PamImageView,
        val modal: PamModalHost,
        val photo: PamImageView,
    ) {
        fun show() = host.addView(modal, FrameLayout.LayoutParams(0, 0))
    }

    @Test
    fun avatarFliesIntoAModalPhotoAndHomeWhenTheModalIsUnmounted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeAnimations(instrumentation)
        val activity = launchActivity(instrumentation)
        try {
            val scene = openScene(instrumentation, activity)
            val opening = sample(instrumentation, scene.modal) { scene.show() }
            assertTrue("opening sampled ${opening.size} frames", opening.size >= 6)
            val avatarOnScreen = IntArray(2)
            val windowOnScreen = IntArray(2)
            val density = activity.resources.displayMetrics.density
            lateinit var windowSize: IntArray
            instrumentation.runOnMainSync {
                scene.avatar.getLocationOnScreen(avatarOnScreen)
                val decor = scene.photo.rootView
                decor.getLocationOnScreen(windowOnScreen)
                windowSize = intArrayOf(decor.width, decor.height)
                assertEquals(0, scene.modal.activeSharedElementCount())
                assertEquals(1f, scene.avatar.alpha, 0f)
                assertEquals(1f, scene.photo.alpha, 0f)
            }
            val first = opening.first()
            assertEquals((avatarOnScreen[0] - windowOnScreen[0]).toFloat(), first[0], 40f)
            assertEquals((avatarOnScreen[1] - windowOnScreen[1]).toFloat(), first[1], 40f)
            assertTrue("opens with the avatar's radius", first[4] > 30f * density)
            val last = opening.last()
            // A 4:3 photo contained in the window: full width, centred.
            assertEquals(0f, last[0], 60f)
            assertEquals(windowSize[0].toFloat(), last[2], 60f)
            assertEquals(windowSize[1] / 2f, (last[1] + last[3]) / 2f, 80f)
            assertTrue("lands square", last[4] < 4f * density)

            val closing = sample(instrumentation, scene.modal) {
                // What the renderer does when the Modal leaves the tree.
                scene.modal.prepareSharedRemoval()
                scene.modal.close()
                (scene.modal.parent as ViewGroup).removeView(scene.modal)
            }
            assertTrue("closing sampled ${closing.size} frames", closing.size >= 4)
            val home = closing.last()
            assertEquals((avatarOnScreen[0] - windowOnScreen[0]).toFloat(), home[0], 60f)
            assertTrue("returns into the round avatar", home[4] > 24f * density)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(1f, scene.avatar.alpha, 0f)
                assertEquals(0, scene.modal.activeSharedElementCount())
                assertFalse(scene.modal.isPresented())
            }
        } finally {
            activity.finish()
        }
    }

    @Test
    fun hidingTheModalFliesHomeBeforeTheWindowGoes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeAnimations(instrumentation)
        val activity = launchActivity(instrumentation)
        try {
            val scene = openScene(instrumentation, activity)
            sample(instrumentation, scene.modal) { scene.show() }
            val closing = sample(instrumentation, scene.modal) {
                scene.modal.setVisible(false)
            }
            assertTrue("closing sampled ${closing.size} frames", closing.size >= 4)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertFalse(scene.modal.isPresented())
                assertEquals(1f, scene.avatar.alpha, 0f)
                scene.modal.close()
            }
        } finally {
            activity.finish()
        }
    }

    private fun openScene(instrumentation: Instrumentation, activity: PamTestActivity): Scene {
        lateinit var scene: Scene
        instrumentation.runOnMainSync {
            val picture = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(Color.rgb(30, 120, 200)) }
            val ring = PamContainer(activity).apply {
                layoutParams = FrameLayout.LayoutParams(240, 240).apply { leftMargin = 80; topMargin = 260 }
                setOverflowClip(true, FloatArray(8) { 120f })
            }
            val avatar = PamImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(240, 240)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(BitmapDrawable(resources, picture))
                setTag(R.id.pam_shared_transition_tag, "avatar:7")
            }
            ring.addView(avatar)
            activity.host.addView(ring)
            val modal = PamModalHost(activity).apply {
                setPresentation(1)
                setAnimationType(3)
                setStatusBarTranslucent(true)
                setNavigationBarTranslucent(true)
            }
            val viewer = FrameLayout(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.BLACK)
            }
            val photo = PamImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(BitmapDrawable(resources, picture))
                setTag(R.id.pam_shared_transition_tag, "avatar:7")
                setTag(
                    R.id.pam_shared_transition_config,
                    """{"durationMs":360,"easing":3,"damping":0.816,"stiffness":240,"mass":0.9,"returnDurationMs":240,"returnEasing":4}""",
                )
            }
            viewer.addView(photo)
            modal.insert(viewer, 0)
            scene = Scene(activity.host, avatar, modal, photo)
        }
        return scene
    }

    /** Runs [action] on the main thread and samples the modal's flights until they land. */
    private fun sample(
        instrumentation: Instrumentation,
        modal: PamModalHost,
        action: () -> Unit,
    ): List<FloatArray> {
        val frames = Collections.synchronizedList(mutableListOf<FloatArray>())
        val landed = CountDownLatch(1)
        var seen = false
        var idleFrames = 0
        val sampler = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val current = modal.activeSharedElementFrames()
                if (current.isNotEmpty()) {
                    seen = true
                    frames += current.first().copyOf()
                } else if (seen || ++idleFrames > 120) {
                    landed.countDown()
                    return
                }
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        instrumentation.runOnMainSync {
            action()
            Choreographer.getInstance().postFrameCallback(sampler)
        }
        assertTrue("the flights land", landed.await(5, TimeUnit.SECONDS))
        return frames.toList()
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
            Intent(instrumentation.targetContext, PamTestActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        ) as PamTestActivity
}
