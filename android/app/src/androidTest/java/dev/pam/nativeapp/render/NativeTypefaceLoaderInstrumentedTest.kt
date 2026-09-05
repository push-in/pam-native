package dev.pam.nativeapp.render

import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeTypefaceLoaderInstrumentedTest {
    @Test
    fun variableFontWeightsHaveDistinctWidthsAndReuseTheirOwnInstance() {
        val context = InstrumentationRegistry.getInstrumentation().context
        context.assets.open("pam/fonts/Inter.ttf").use { assertTrue(it.available() > 0) }
        val loader = NativeTypefaceLoader(context)
        val family = "asset://fonts/Inter.ttf"
        val widths = listOf(400, 500, 600, 700).map { weight ->
            val face = loader.resolve(family, weight, false)
            if (android.os.Build.VERSION.SDK_INT >= 28) assertEquals(weight, face.weight)
            assertSame(face, loader.resolve(family, weight, false))
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply { typeface = face; textSize = 64f }.measureText("linkinpay")
        }
        val engineWidths = listOf(268.34375f, 273.875f, 279.34375f, 284.875f)
        widths.zip(engineWidths).forEach { (drawn, measured) ->
            assertTrue("Engine width $measured is smaller than Android width $drawn", drawn <= kotlin.math.ceil(measured))
        }
        assertTrue(widths.last() > widths.first())
        widths.zipWithNext().forEach { (lighter, heavier) ->
            assertTrue("Expected nondecreasing widths, got $widths", heavier >= lighter)
        }
    }
}
