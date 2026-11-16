package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImageFadeTest {
    @Test
    fun cacheAndFastHitsAppearWithoutCrossFade() {
        assertFalse(imageFadesIn(0))
        assertFalse(imageFadesIn(16))
        assertFalse(imageFadesIn(IMAGE_INSTANT_LOAD_MS - 1))
    }

    @Test
    fun loadsTheUserWaitedForFadeIn() {
        assertTrue(imageFadesIn(IMAGE_INSTANT_LOAD_MS))
        assertTrue(imageFadesIn(800))
    }
}
