package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImagePriorityTest {
    @Test
    fun `bundled image data uses the isolated inline lane`() {
        assertTrue(isInlineImageSource("data:image/png;base64,AAAA"))
        assertTrue(isInlineImageSource("DATA:IMAGE/WEBP;BASE64,AAAA"))
    }

    @Test
    fun `network and sandbox media stay on the shared media lane`() {
        assertFalse(isInlineImageSource("https://cdn.example.test/photo.webp"))
        assertFalse(isInlineImageSource("pam-file:/media/photo.jpg"))
        assertFalse(isInlineImageSource("data:text/plain;base64,AAAA"))
    }
}
