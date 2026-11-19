package dev.pam.nativeapp.text

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PamTextProcessorsTest {
    @After
    fun uninstall() = PamTextProcessors.install(null)

    @Test
    fun withoutAProcessorTheTextIsUntouched() {
        val text = "Oi 🫐"
        assertFalse(PamTextProcessors.active)
        assertSame(text, PamTextProcessors.process(text))
    }

    @Test
    fun anInstalledProcessorRewritesMeasuredAndDrawnText() {
        val marked = StringBuilder("Oi 🫐")
        PamTextProcessors.install { marked }
        assertTrue(PamTextProcessors.active)
        assertSame(marked, PamTextProcessors.process("Oi 🫐"))
        assertEquals("", PamTextProcessors.process("").toString())
    }

    @Test
    fun aFailingOrLengthChangingProcessorFallsBackToTheText() {
        val text = "Oi 🫐"
        PamTextProcessors.install { error("emoji font not loaded") }
        assertSame(text, PamTextProcessors.process(text))
        PamTextProcessors.install { "$it!" }
        assertSame("characters must not change, only spans", text, PamTextProcessors.process(text))
    }
}
