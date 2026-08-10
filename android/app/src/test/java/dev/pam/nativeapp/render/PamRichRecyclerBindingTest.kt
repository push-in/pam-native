package dev.pam.nativeapp.render

import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamRichRecyclerBindingTest {
    @Test
    fun layoutPayloadFullyBindsAnUninitializedHolder() {
        assertTrue(richHolderNeedsFullBind(RecyclerView.NO_ID, 41L, 0))
        assertTrue(richHolderNeedsFullBind(41L, 41L, 0))
    }

    @Test
    fun layoutPayloadOnlyResizesAMaterializedMatchingHolder() {
        assertFalse(richHolderNeedsFullBind(41L, 41L, 1))
        assertTrue(richHolderNeedsFullBind(40L, 41L, 1))
    }

    @Test
    fun resumeRebindsOnlyEmptyKnownRichHolders() {
        assertTrue(richHolderNeedsResumeRebind(41L, 0, 2))
        assertFalse(richHolderNeedsResumeRebind(41L, 1, 2))
        assertFalse(richHolderNeedsResumeRebind(RecyclerView.NO_ID, 0, 2))
        assertFalse(richHolderNeedsResumeRebind(41L, 0, -1))
        assertFalse(richHolderNeedsResumeRebind(41L, 0, 2, 41L))
        assertTrue(richHolderNeedsResumeRebind(41L, 0, 2, 40L))
    }
}
