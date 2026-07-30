package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamLineHeightTest {
    @Test
    fun authoredLineHeightResolvesToExactRenderedPixels() {
        assertEquals(
            6f,
            resolvedLineSpacingExtra(
                logicalLineHeight = 18f,
                renderedTextSizePx = 28f,
                logicalFontSize = 14f,
                fontMetricsHeightPx = 30f,
            ),
            0.001f,
        )
    }

    @Test
    fun compactLineHeightMayReduceNativeFontMetrics() {
        assertEquals(
            -4f,
            resolvedLineSpacingExtra(
                logicalLineHeight = 13f,
                renderedTextSizePx = 14f,
                logicalFontSize = 14f,
                fontMetricsHeightPx = 17f,
            ),
            0.001f,
        )
    }
}
