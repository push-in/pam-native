package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreThumbnailTest {
    private val photo = "content://media/external/images/media/42"
    private val video = "content://media/external/video/media/7"
    private val file = "content://media/external/file/9"

    @Test
    fun galleryCellsUseThePlatformThumbnail() {
        assertTrue(isMediaStoreThumbnailCandidate(photo, IMAGE_RESIZE_AUTO, 270, 270))
        assertTrue(isMediaStoreThumbnailCandidate(video, IMAGE_RESIZE_RESIZE, 270, 270))
        assertTrue(isMediaStoreThumbnailCandidate(file, IMAGE_RESIZE_AUTO, 270, 270))
    }

    @Test
    fun otherSourcesAndLargeViewsDecodeTheFile() {
        assertFalse(isMediaStoreThumbnailCandidate("content://com.example.files/a.jpg", IMAGE_RESIZE_AUTO, 270, 270))
        assertFalse(isMediaStoreThumbnailCandidate("pam-file://photo.jpg", IMAGE_RESIZE_AUTO, 270, 270))
        assertFalse(isMediaStoreThumbnailCandidate("https://cdn.example/a.jpg", IMAGE_RESIZE_AUTO, 270, 270))
        assertFalse(isMediaStoreThumbnailCandidate(photo, IMAGE_RESIZE_AUTO, 1080, 1350))
        assertFalse(isMediaStoreThumbnailCandidate(photo, IMAGE_RESIZE_NONE, 270, 270))
        assertFalse(isMediaStoreThumbnailCandidate(photo, IMAGE_RESIZE_SCALE, 270, 270))
        assertFalse(isMediaStoreThumbnailCandidate(photo, IMAGE_RESIZE_AUTO, 0, 270))
        assertFalse(isMediaStoreThumbnailCandidate("content://media/external/audio/albumart/3", IMAGE_RESIZE_AUTO, 270, 270))
    }

    @Test
    fun collectionsAreRecognized() {
        assertEquals(MEDIA_STORE_IMAGE, mediaStoreKind(photo))
        assertEquals(MEDIA_STORE_VIDEO, mediaStoreKind(video))
        assertEquals(MEDIA_STORE_FILE, mediaStoreKind(file))
        assertEquals(MEDIA_STORE_OTHER, mediaStoreKind("content://media/external/audio/media/1"))
    }
}
