package dev.pam.nativeapp.render

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.ContextThemeWrapper
import android.view.View
import androidx.appcompat.widget.SwitchCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.PamTestActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * React Native's Android `Switch` is an AppCompat `SwitchCompat`
 * (`ReactSwitch`) colored with MULTIPLY color filters. Its thumb is the
 * AppCompat 9-patch: a 20 dp disc with a baked drop shadow and optical
 * insets that move the switch area in from the view's right edge. PAM must
 * draw the same pixels at the same size (1.34.1).
 */
@RunWith(AndroidJUnit4::class)
class PamSwitchReactNativeParityInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun offSwitchMatchesTheReactNativeSwitch() = compare(checked = false)

    @Test
    fun onSwitchMatchesTheReactNativeSwitch() = compare(checked = true)

    private fun compare(checked: Boolean) {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, PamTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as PamTestActivity
        try {
            var report = ""
            var thumbOffset = 0
            var shadowRn = 0f
            var shadowPam = 0f
            var different = 0
            var total = 0
            val samples = StringBuilder()
            instrumentation.runOnMainSync {
                // AppCompat's resources live in the test APK, not in the renderer's.
                val themed = ContextThemeWrapper(
                    instrumentation.context,
                    androidx.appcompat.R.style.Theme_AppCompat_Light_NoActionBar,
                )
                val rn = SwitchCompat(themed).apply {
                    showText = false
                    isChecked = checked
                    // ReactSwitch.setThumbColor / setTrackColor.
                    thumbDrawable.colorFilter = PorterDuffColorFilter(THUMB, PorterDuff.Mode.MULTIPLY)
                    trackDrawable.colorFilter = PorterDuffColorFilter(
                        if (checked) TRACK_ON else TRACK_OFF,
                        PorterDuff.Mode.MULTIPLY,
                    )
                }
                // ReactSwitchManager.measure: UNSPECIFIED in both axes.
                val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                rn.measure(unspecified, unspecified)
                val width = rn.measuredWidth
                val height = rn.measuredHeight
                val pam = PamSwitch(activity).apply {
                    isChecked = checked
                    setThumbColor(THUMB)
                    setTrackOffColor(TRACK_OFF)
                    setTrackOnColor(TRACK_ON)
                }
                val expected = render(rn, width, height)
                val actual = render(pam, width, height)
                total = width * height
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        if (delta(expected.getPixel(x, y), actual.getPixel(x, y)) > 24) {
                            different++
                            if (samples.length < 1500) {
                                samples.append(" $x,$y:%08X/%08X".format(expected.getPixel(x, y), actual.getPixel(x, y)))
                            }
                        }
                    }
                }
                thumbOffset = thumbCenterX(actual) - thumbCenterX(expected)
                shadowRn = shadowBelowThumb(expected)
                shadowPam = shadowBelowThumb(actual)
                report = "${width}x$height checked=$checked thumb RN ${thumbCenterX(expected)} PAM ${thumbCenterX(actual)} " +
                    "shadow RN $shadowRn PAM $shadowPam different $different/$total$samples"
            }
            assertEquals("thumb position (px) $report", 0, thumbOffset)
            assertTrue("thumb shadow $report", shadowRn > 0f && abs(shadowPam - shadowRn) <= shadowRn * 0.1f)
            assertEquals("pixels differing from RN $report", 0, different)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun render(view: View, width: Int, height: Int): Bitmap {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    /** Mean x of the thumb disc (red pixels, `THUMB`). */
    private fun thumbCenterX(bitmap: Bitmap): Int {
        var sum = 0L
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) > 250 && Color.red(pixel) > 250 && Color.green(pixel) < 5 && Color.blue(pixel) < 5) {
                    sum += x
                    count++
                }
            }
        }
        return if (count == 0) -1 else (sum / count).toInt()
    }

    /** Alpha of the dark (non-thumb, non-track) pixels: the thumb's drop shadow. */
    private fun shadowBelowThumb(bitmap: Bitmap): Float {
        var sum = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.red(pixel) < 40 && Color.green(pixel) < 40 && Color.blue(pixel) < 40) sum += Color.alpha(pixel)
            }
        }
        return sum / 255f
    }

    private fun delta(a: Int, b: Int): Int = maxOf(
        abs(Color.alpha(a) - Color.alpha(b)),
        abs(Color.red(a) - Color.red(b)),
        abs(Color.green(a) - Color.green(b)),
        abs(Color.blue(a) - Color.blue(b)),
    )

    private companion object {
        val THUMB = Color.RED
        val TRACK_OFF = Color.rgb(0x40, 0xC0, 0x40)
        val TRACK_ON = Color.rgb(0x40, 0x40, 0xC0)
    }
}
