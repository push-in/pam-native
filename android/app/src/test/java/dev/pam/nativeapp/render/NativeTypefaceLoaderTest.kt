package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeTypefaceLoaderTest {
    @Test
    fun resolvesProjectAndExplicitPamAssetPaths() {
        assertEquals(
            "pam/assets/fonts/Brand-Regular.ttf",
            normalizedFontAssetPath("asset://assets/fonts/Brand-Regular.ttf"),
        )
        assertEquals(
            "pam/assets/fonts/Brand-Bold.otf",
            normalizedFontAssetPath("asset://pam/assets/fonts/Brand-Bold.otf"),
        )
    }

    @Test
    fun leavesInstalledFontFamiliesToAndroid() {
        assertNull(normalizedFontAssetPath("sans-serif"))
    }

    @Test
    fun rejectsTraversalAndUnsupportedFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizedFontAssetPath("asset://assets/../secrets.ttf")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizedFontAssetPath("asset://assets/fonts/Brand.woff2")
        }
    }
}
