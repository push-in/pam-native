package dev.pam.nativeapp.render

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamWebBridgeTest {
    @Test
    fun exposesTheReactNativeWebViewChannelAndItsAlias() {
        assertEquals("ReactNativeWebView", PamWebBridge.INTERFACE_NAME)
        assertTrue(PamWebBridge.ALIAS_SCRIPT.contains("window.PamNative.postMessage"))
        assertTrue(PamWebBridge.ALIAS_SCRIPT.contains("window.ReactNativeWebView.postMessage"))
    }

    @Test
    fun refusesMissingAndOversizedMessages() {
        assertFalse(PamWebBridge.accepts(null))
        assertTrue(PamWebBridge.accepts(""))
        assertTrue(PamWebBridge.accepts("x".repeat(PamWebBridge.MAX_MESSAGE_CHARS)))
        assertFalse(PamWebBridge.accepts("x".repeat(PamWebBridge.MAX_MESSAGE_CHARS + 1)))
    }

    @Test
    fun runsAChangedScriptOnlyOnALoadedPage() {
        assertTrue(PamWebBridge.runsOnChange("a", "b", pageLoaded = true))
        assertFalse(PamWebBridge.runsOnChange("a", "a", pageLoaded = true))
        assertFalse(PamWebBridge.runsOnChange("a", "", pageLoaded = true))
        assertFalse(PamWebBridge.runsOnChange("a", "b", pageLoaded = false))
    }

    @Test
    fun aFixedPageNeverOverScrolls() {
        assertEquals(View.OVER_SCROLL_NEVER, PamWebBridge.overScrollMode(1, scrollEnabled = false))
        assertEquals(View.OVER_SCROLL_NEVER, PamWebBridge.overScrollMode(3, scrollEnabled = true))
        assertEquals(View.OVER_SCROLL_ALWAYS, PamWebBridge.overScrollMode(2, scrollEnabled = true))
        assertEquals(View.OVER_SCROLL_IF_CONTENT_SCROLLS, PamWebBridge.overScrollMode(1, scrollEnabled = true))
    }

    @Test
    fun pausesInTheBackgroundAndWhenCovered() {
        assertTrue(PamWebBridge.runs(hostActive = true, shown = true))
        assertFalse(PamWebBridge.runs(hostActive = false, shown = true))
        assertFalse(PamWebBridge.runs(hostActive = true, shown = false))
    }
}
