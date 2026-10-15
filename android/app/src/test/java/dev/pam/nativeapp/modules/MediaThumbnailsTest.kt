package dev.pam.nativeapp.modules

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaThumbnailsTest {
    @Test
    fun edgeIsClampedToTheSupportedRange() {
        assertEquals(32, MediaThumbnails.clampEdge(1))
        assertEquals(256, MediaThumbnails.clampEdge(256))
        assertEquals(1024, MediaThumbnails.clampEdge(100_000))
    }

    @Test
    fun decodeSamplesDownWithoutGoingBelowTheRequestedEdge() {
        assertEquals(1, MediaThumbnails.sampleSize(300, 200, 256))
        assertEquals(8, MediaThumbnails.sampleSize(4032, 3024, 256))
        assertEquals(1, MediaThumbnails.sampleSize(0, 0, 256))
        assertEquals(256 to 192, MediaThumbnails.fittedSize(4032, 3024, 256))
        assertEquals(100 to 50, MediaThumbnails.fittedSize(100, 50, 256))
        assertEquals(1 to 256, MediaThumbnails.fittedSize(10, 4000, 256))
    }

    @Test
    fun trimDropsLeastRecentlyUsedUntilThreeQuartersOfTheBudget() {
        val entries = (0 until 10).map { MediaThumbnails.Entry("t$it.jpg", 100, lastUsed = it.toLong()) }
        assertTrue(MediaThumbnails.victims(entries, maxBytes = 1_000, maxFiles = 10).isEmpty())
        assertEquals((0..3).map { "t$it.jpg" }, MediaThumbnails.victims(entries, 900, 10))
        assertEquals((0..6).map { "t$it.jpg" }, MediaThumbnails.victims(entries, 10_000, 4))
    }

    @Test
    fun cacheKeysAreStableFileNamesInsideTheCacheDirectory() {
        val directory = Files.createTempDirectory("thumbs").toFile()
        val cache = MediaThumbnails(directory)
        val first = cache.file("42:1700000000:256")
        assertEquals(first, cache.file("42:1700000000:256"))
        assertNotEquals(first, cache.file("42:1700000001:256"))
        assertEquals(directory, first.parentFile)
        assertFalse(cache.hit(first))
        first.writeBytes(byteArrayOf(1))
        assertTrue(cache.hit(first))
        directory.deleteRecursively()
    }
}
