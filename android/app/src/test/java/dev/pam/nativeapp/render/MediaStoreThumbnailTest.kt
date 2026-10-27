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

    @Test
    fun videoStillsUseThePlatformThumbnailAtAnySize() {
        // A preview-sized still of a gallery video never reads the container.
        assertTrue(isMediaStoreVideoPosterCandidate(video, 1080, 1350))
        assertTrue(isMediaStoreVideoPosterCandidate(file, 1080, 1350))
        assertFalse(isMediaStoreVideoPosterCandidate(photo, 1080, 1350))
        assertFalse(isMediaStoreVideoPosterCandidate(video, 0, 1350))
        assertFalse(isMediaStoreVideoPosterCandidate("content://com.example.files/v.mp4", 1080, 1350))
    }

    @Test
    fun videosNeverFallBackToReadingTheFile() {
        assertFalse(mediaStoreFallsBackToFileDecode(video = true))
        assertTrue(mediaStoreFallsBackToFileDecode(video = false))
    }

    @Test
    fun thumbnailsComeFromTheProviderNeverFromAFrameDecode() {
        assertEquals(MediaStoreThumbnailSource.PROVIDER_STREAM, mediaStoreThumbnailSource(29))
        assertEquals(MediaStoreThumbnailSource.PROVIDER_STREAM, mediaStoreThumbnailSource(35))
        assertEquals(MediaStoreThumbnailSource.LEGACY_MINI_KIND, mediaStoreThumbnailSource(28))
        assertEquals(MediaStoreThumbnailSource.LEGACY_MINI_KIND, mediaStoreThumbnailSource(26))
    }

    @Test
    fun galleryStillsDecodeAsRgb565() {
        assertEquals(NativeBitmapStorage.RGB_565, MEDIA_STORE_THUMBNAIL_STORAGE)
    }

    @Test
    fun thumbnailSampleKeepsTheBoxCovered() {
        assertEquals(1, thumbnailSampleSize(512, 384, 270, 270))
        assertEquals(2, thumbnailSampleSize(1024, 768, 270, 270))
        assertEquals(4, thumbnailSampleSize(1280, 1280, 270, 270))
        assertEquals(1, thumbnailSampleSize(0, 0, 270, 270))
        assertEquals(1, thumbnailSampleSize(512, 384, 0, 270))
    }
}
