package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PamAssetPathTest {
    @Test
    fun resolvesProjectAndExplicitPamAssetPaths() {
        assertEquals(
            "pam/assets/logos/brand.png",
            normalizedPamAssetPath("asset://assets/logos/brand.png"),
        )
        assertEquals(
            "pam/assets/logos/brand.png",
            normalizedPamAssetPath("asset://pam/assets/logos/brand.png"),
        )
        assertEquals(
            "pam/avatar.webp",
            normalizedPamAssetPath("ASSET://avatar.webp"),
        )
    }

    @Test
    fun leavesNonAssetSourcesUntouched() {
        assertNull(normalizedPamAssetPath("https://cdn.example.com/avatar.png"))
        assertNull(normalizedPamAssetPath("sans-serif"))
    }

    @Test
    fun rejectsEmptyTraversalAndUriSuffixes() {
        listOf(
            "asset://",
            "asset://assets/../secrets.png",
            "asset://assets//brand.png",
            "asset://assets/brand.png?size=2",
            "asset://assets/brand.png#icon",
        ).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                normalizedPamAssetPath(source)
            }
        }
    }
}
