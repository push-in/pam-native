package dev.pam.nativeapp

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpcacheStampTest {
    @Test
    fun newBundleContentUnderTheSamePathDropsTheFileCache() {
        val root = Files.createTempDirectory("pam-opcache-stamp").toFile()
        try {
            val release = File(root, "releases/current").apply { mkdirs() }
            val entry = File(release, "index.php").apply { writeText("<?php echo 1;") }
            val manifest = File(release, "manifest.sha256")
            manifest.writeText("a".repeat(64) + "\n")
            val state = File(root, "state").apply { mkdirs() }

            val first = OpcacheStamp.stamp(entry)
            assertTrue(first.endsWith("|" + "a".repeat(64)))
            assertTrue(OpcacheStamp.prepare(state, first))
            val cached = File(state, "opcache/index.php.bin").apply { writeText("opcodes") }

            assertFalse("Same bundle keeps the warm cache", OpcacheStamp.prepare(state, OpcacheStamp.stamp(entry)))
            assertTrue(cached.isFile)

            manifest.writeText("b".repeat(64) + "\n")
            val second = OpcacheStamp.stamp(entry)
            assertNotEquals(first, second)
            assertTrue(OpcacheStamp.prepare(state, second))
            assertFalse("Stale opcodes are dropped", cached.exists())
            assertTrue(File(state, "opcache").isDirectory)
            assertEquals(second, File(state, "opcache.stamp").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aNewContentAddressedReleaseDropsTheOpcodesOfThePreviousOne() {
        val root = Files.createTempDirectory("pam-opcache-release").toFile()
        try {
            val state = File(root, "state").apply { mkdirs() }
            val old = File(root, "releases/" + "1".repeat(64)).apply { mkdirs() }
            val new = File(root, "releases/" + "2".repeat(64)).apply { mkdirs() }
            OpcacheStamp.prepare(state, OpcacheStamp.stamp(File(old, "index.php")))
            val orphan = File(state, "opcache/old.bin").apply { writeText("x") }
            assertTrue(OpcacheStamp.prepare(state, OpcacheStamp.stamp(File(new, "index.php"))))
            assertFalse(orphan.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aMalformedManifestOnlyKeysTheReleasePath() {
        val root = Files.createTempDirectory("pam-opcache-manifest").toFile()
        try {
            File(root, "manifest.sha256").writeText("garbage\n")
            assertEquals(root.absolutePath + "|", OpcacheStamp.stamp(File(root, "index.php")))
        } finally {
            root.deleteRecursively()
        }
    }
}
