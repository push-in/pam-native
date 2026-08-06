package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamMediaDataSourceTest {
    @Test
    fun remoteHttpMediaUsesMediaPlayerNetworkDataSource() {
        assertTrue(mediaDataSourceUsesNetworkString("https"))
        assertTrue(mediaDataSourceUsesNetworkString("HTTP"))
    }

    @Test
    fun localAndProviderMediaKeepContextAwareDataSource() {
        assertFalse(mediaDataSourceUsesNetworkString("content"))
        assertFalse(mediaDataSourceUsesNetworkString("file"))
        assertFalse(mediaDataSourceUsesNetworkString("android.resource"))
        assertFalse(mediaDataSourceUsesNetworkString(null))
    }
}
