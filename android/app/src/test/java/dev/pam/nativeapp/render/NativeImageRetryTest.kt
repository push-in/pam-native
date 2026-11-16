package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeImageRetryTest {
    @Test
    fun activeRequestWithTheSameSignatureIsReused() {
        assertTrue(
            shouldReuseImageRequest(
                sameSignature = true,
                finished = false,
                hasDrawable = false,
            ),
        )
    }

    @Test
    fun successfulRequestWithVisiblePixelsIsReused() {
        assertTrue(
            shouldReuseImageRequest(
                sameSignature = true,
                finished = true,
                hasDrawable = true,
            ),
        )
    }

    @Test
    fun failedRequestWithoutPixelsIsRetried() {
        assertFalse(
            shouldReuseImageRequest(
                sameSignature = true,
                finished = true,
                hasDrawable = false,
            ),
        )
    }

    @Test
    fun changedRequestIsNeverReused() {
        assertFalse(
            shouldReuseImageRequest(
                sameSignature = false,
                finished = false,
                hasDrawable = true,
            ),
        )
    }

    @Test
    fun failedRequestIsNeverReusedEvenWithAPlaceholder() {
        assertFalse(
            shouldReuseImageRequest(
                sameSignature = true,
                finished = true,
                hasDrawable = true,
                failed = true,
            ),
        )
    }

    @Test
    fun transientFailureRetriesWithBackoffUntilTheBudgetIsSpent() {
        assertTrue(shouldRetryImageRequest(attempts = 0, transient = true))
        assertTrue(shouldRetryImageRequest(attempts = IMAGE_MAX_TRANSIENT_RETRIES - 1, transient = true))
        assertFalse(shouldRetryImageRequest(attempts = IMAGE_MAX_TRANSIENT_RETRIES, transient = true))
    }

    @Test
    fun permanentFailureIsNeverRetried() {
        assertFalse(shouldRetryImageRequest(attempts = 0, transient = false))
    }

    @Test
    fun readTimeoutGrowsOnRetries() {
        assertTrue(imageReadTimeoutMs(0) < imageReadTimeoutMs(1))
        assertTrue(imageReadTimeoutMs(1) < imageReadTimeoutMs(2))
        assertTrue(imageReadTimeoutMs(5) <= 15_000)
    }
}
