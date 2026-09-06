package dev.pam.nativeapp.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardPrivacyPolicyTest {
    @Test
    fun expiryIsBoundedAndConvertedWithoutOverflow() {
        assertEquals(15_000L, ClipboardPrivacyPolicy.expiryMillis(15))
        assertEquals(60_000L, ClipboardPrivacyPolicy.expiryMillis(60))
        assertEquals(300_000L, ClipboardPrivacyPolicy.expiryMillis(300))
        listOf(14L, 301L).forEach { seconds ->
            runCatching { ClipboardPrivacyPolicy.expiryMillis(seconds) }
                .onSuccess { error("Unsafe expiry $seconds was accepted") }
        }
    }

    @Test
    fun scheduledCleanupNeverDeletesAClipboardReplacedByTheUser() {
        assertTrue(ClipboardPrivacyPolicy.shouldClear("pix-code", "pix-code"))
        assertFalse(ClipboardPrivacyPolicy.shouldClear("pix-code", "new clipboard value"))
        assertFalse(ClipboardPrivacyPolicy.shouldClear("pix-code", null))
    }
}
