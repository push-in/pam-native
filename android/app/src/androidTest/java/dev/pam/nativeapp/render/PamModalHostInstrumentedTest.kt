package dev.pam.nativeapp.render

import android.view.Gravity
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PamModalHostInstrumentedTest {
    @Test
    fun dialogContentKeepsItsIntrinsicCardSizeAndIsCentered() {
        val params = modalChildLayoutParams(presentation = 2, sheetHeight = 640)

        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.height)
        assertEquals(Gravity.CENTER, params.gravity)
    }

    @Test
    fun fullScreenAndSheetPresentationsRetainTheirPlatformExtents() {
        val fullScreen = modalChildLayoutParams(presentation = 1, sheetHeight = 640)
        val sheet = modalChildLayoutParams(presentation = 3, sheetHeight = 640)

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, fullScreen.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, fullScreen.height)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, sheet.width)
        assertEquals(640, sheet.height)
        assertEquals(Gravity.BOTTOM, sheet.gravity)
    }
}
