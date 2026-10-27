package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which hidden images give their decoded pixels back (restored from cache on show). */
class NativeImageReleaseTest {
    private val remote = "https://cdn.example.test/poster.jpg"

    @Test fun memoryCachedImagesAreReleasedWhenHidden() {
        assertTrue(isReleasableImageRequest(NativeImageRequest(remote)))
    }

    @Test fun diskOnlyImagesKeepTheirPixelsUnlessAsked() {
        assertFalse(isReleasableImageRequest(NativeImageRequest(remote, mediaCachePolicy = MEDIA_CACHE_DISK)))
        assertTrue(
            "a media poster under its painted video decodes again from disk",
            isReleasableImageRequest(NativeImageRequest(remote, mediaCachePolicy = MEDIA_CACHE_DISK, releaseWhenHidden = true)),
        )
    }

    @Test fun uncachedImagesAreNeverReleased() {
        assertFalse(isReleasableImageRequest(NativeImageRequest(remote, mediaCachePolicy = MEDIA_CACHE_NONE, releaseWhenHidden = true)))
        assertFalse(isReleasableImageRequest(NativeImageRequest(remote, cachePolicy = IMAGE_CACHE_RELOAD, releaseWhenHidden = true)))
    }
}
