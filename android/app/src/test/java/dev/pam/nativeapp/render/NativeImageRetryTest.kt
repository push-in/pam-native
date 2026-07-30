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
    fun transientFailureIsRetriedWhileAttached() {
        assertTrue(shouldRetryImageRequest(attempts = 0, attached = true))
        assertTrue(shouldRetryImageRequest(attempts = 1, attached = true))
        assertFalse(shouldRetryImageRequest(attempts = 2, attached = true))
    }

    @Test
    fun detachedImageDoesNotKeepBackgroundRetriesAlive() {
        assertFalse(shouldRetryImageRequest(attempts = 0, attached = false))
    }
}
