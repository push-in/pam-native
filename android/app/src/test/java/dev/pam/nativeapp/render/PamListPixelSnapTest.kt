package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamListPixelSnapTest {
    @Test fun aRootWithoutMarginsFillsItsSlot() {
        // The slot is the row's rounded extent; the root takes exactly it (no 1 px seam).
        assertTrue(cellRootFillsSlot(120.5f, 72.4f, 120.5f, 72.4f))
        assertFalse(cellRootFillsSlot(124.5f, 64.4f, 120.5f, 72.4f))
    }
}
