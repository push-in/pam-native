package dev.pam.nativeapp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HotReloadStoreTest {
    @Test
    fun manifestVerifiesFullTextsAndDeltasAgainstTheVersionDigest() {
        val base = manifest("index.php" to "<?php", "src/A.php" to "a", "src/Old.php" to "o")
        assertEquals(3, base.files.size)
        val next = files("index.php" to "<?php", "src/A.php" to "a2", "src/New.php" to "n")
        val nextVersion = version(next)
        val delta = buildString {
            append("PNMD1 ").append(base.version).append('\n')
            append("+ ").append(line(next.getValue("src/A.php"))).append('\n')
            append("+ ").append(line(next.getValue("src/New.php"))).append('\n')
            append("- src/Old.php\n")
        }
        val applied = HotReloadManifest.parse(delta, nextVersion, base)
        assertEquals(listOf("index.php", "src/A.php", "src/New.php"), applied.files.keys.toList())
        assertEquals(nextVersion, applied.version)

        assertThrows(IllegalArgumentException::class.java) {
            HotReloadManifest.parse(delta, version(files("index.php" to "x")), base)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HotReloadManifest.parse(delta, nextVersion, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HotReloadManifest.parse(text(files("../escape.php" to "x", "index.php" to "y")), "a".repeat(64), null)
        }
    }

    @Test
    fun firstSyncReusesBootFilesAndDownloadsOnlyWhatTheDeviceLacks() = withRoot { root ->
        val boot = File(root, "release").apply { mkdirs() }
        File(boot, "index.php").writeText("<?php")
        File(boot, "src").mkdirs()
        File(boot, "src/A.php").writeText("a")
        File(boot, "src/Stale.php").writeText("stale")
        val store = HotReloadStore(File(root, "dev"), listOf(HotReloadStore.directorySource(boot)))
        val manifest = manifest("index.php" to "<?php", "src/A.php" to "a", "src/Stale.php" to "fresh")

        val plan = store.plan(manifest)

        assertTrue(plan.fresh)
        assertEquals(3, plan.changed.size)
        assertEquals(setOf(sha("fresh")), plan.missing)
        assertEquals(5L, store.receive(objects("fresh"), plan.missing))
        store.begin(plan)
        store.apply(plan)
        store.previousRequestReleased(manifest.version)
        assertEquals("fresh", File(store.live, "src/Stale.php").readText())
        assertEquals("a", File(store.live, "src/A.php").readText())
        assertEquals(manifest.version, store.liveManifest?.version)
        assertFalse(store.hasPendingRequest)

        // A new client (Activity recreated, process restarted) resumes from live/.
        val reopened = HotReloadStore(File(root, "dev"))
        assertEquals(manifest.version, reopened.liveManifest?.version)
        assertTrue(reopened.plan(manifest).isEmpty)
    }

    @Test
    fun editsApplyInPlaceWithFreshMtimesAndOnlyTheChangedFiles() = withRoot { root ->
        val store = HotReloadStore(File(root, "dev"))
        val first = manifest("index.php" to "<?php", "src/A.php" to "a", "src/B.php" to "b", "src/Gone.php" to "g")
        val initial = store.plan(first)
        store.receive(objects("<?php", "a", "b", "g"), initial.missing)
        store.apply(initial)
        val untouched = File(store.live, "src/B.php")
        val untouchedInode = Files.getAttribute(untouched.toPath(), "unix:ino")
        val edited = File(store.live, "src/A.php")
        val firstStamp = edited.lastModified()

        val second = manifest("index.php" to "<?php", "src/A.php" to "a2", "src/B.php" to "b")
        val plan = store.plan(second)
        assertFalse(plan.fresh)
        assertEquals(listOf("src/A.php"), plan.changed.map(HotReloadFile::path))
        assertEquals(listOf("src/Gone.php"), plan.removed)
        assertEquals(setOf(sha("a2")), plan.missing)
        store.receive(objects("a2"), plan.missing)
        store.begin(plan)
        // The runtime still runs the old files until the previous request is released.
        assertEquals("a", edited.readText())
        store.apply(plan)
        assertEquals("a2", edited.readText())
        assertFalse(File(store.live, "src/Gone.php").exists())
        assertEquals(untouchedInode, Files.getAttribute(untouched.toPath(), "unix:ino"))
        assertTrue("OPcache needs a newer second", edited.lastModified() >= firstStamp + 1_000)

        // A third save in the same second still gets a newer stamp.
        val third = manifest("index.php" to "<?php", "src/A.php" to "a3", "src/B.php" to "b")
        store.previousRequestReleased(second.version)
        val thirdPlan = store.plan(third)
        store.receive(objects("a3"), thirdPlan.missing)
        val before = edited.lastModified()
        store.apply(thirdPlan)
        assertNotEquals(before, edited.lastModified())
        assertTrue(edited.lastModified() > before)
        assertFalse(File(root, "dev/objects").exists())
    }

    @Test
    fun legacyPerVersionBundlesAndInterruptedWritesAreRemovedOnOpen() = withRoot { root ->
        val dev = File(root, "dev")
        File(dev, "a".repeat(64)).mkdirs()
        File(dev, "live.next/src").mkdirs()
        File(dev, "objects").mkdirs()
        File(dev, "objects/${"b".repeat(64)}.part").writeText("partial")
        val store = HotReloadStore(dev)
        assertNull(store.liveManifest)
        assertEquals(emptyList<String>(), dev.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun objectStreamsRejectTamperedTruncatedAndUnexpectedObjects() = withRoot { root ->
        val store = HotReloadStore(File(root, "dev"))
        val tampered = ByteArrayOutputStream().apply {
            write("PNO1".toByteArray())
            write(decodeSha256(sha("good")))
            write(littleEndian(4))
            write("evil".toByteArray())
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            store.receive(ByteArrayInputStream(tampered), setOf(sha("good")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.receive(objects("other"), setOf(sha("good")))
        }
        val truncated = objects("good").readBytes().copyOf(30)
        assertThrows(java.io.IOException::class.java) {
            store.receive(ByteArrayInputStream(truncated), setOf(sha("good")))
        }
        // A large first sync streams without any whole-bundle limit.
        val large = "x".repeat(20 * 1024 * 1024)
        assertEquals(large.length.toLong(), store.receive(objects(large), setOf(sha(large))))
    }

    @Test
    fun aPendingVersionBlocksTheNextUntilItsRequestIsReleased() = withRoot { root ->
        val store = HotReloadStore(File(root, "dev"))
        val first = manifest("index.php" to "1")
        val plan = store.plan(first)
        store.receive(objects("1"), plan.missing)
        store.begin(plan)
        assertTrue(store.hasPendingRequest)
        assertThrows(IllegalStateException::class.java) { store.begin(plan) }
        store.previousRequestReleased("f".repeat(64))
        assertTrue(store.hasPendingRequest)
        store.abandon(first.version)
        assertFalse(store.hasPendingRequest)
    }

    private fun objects(vararg contents: String): ByteArrayInputStream = ByteArrayInputStream(
        ByteArrayOutputStream().apply {
            write("PNO1".toByteArray())
            contents.forEach { content ->
                val bytes = content.toByteArray()
                write(decodeSha256(sha(content)))
                write(littleEndian(bytes.size.toLong()))
                write(bytes)
            }
        }.toByteArray(),
    )

    private fun littleEndian(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun sha(content: String) = HotReloadManifest.sha256Hex(content.toByteArray())

    private fun files(vararg entries: Pair<String, String>): Map<String, HotReloadFile> =
        entries.associate { (path, content) ->
            path to HotReloadFile(path, sha(content), content.toByteArray().size.toLong())
        }

    private fun line(file: HotReloadFile) = "${file.sha256} ${file.size} ${file.path}"

    private fun text(files: Map<String, HotReloadFile>) =
        HotReloadManifest.MAGIC + files.keys.sorted().joinToString("") { line(files.getValue(it)) + "\n" }

    private fun version(files: Map<String, HotReloadFile>) = HotReloadManifest.sha256Hex(text(files).toByteArray())

    private fun manifest(vararg entries: Pair<String, String>): HotReloadManifest {
        val files = files(*entries)
        return HotReloadManifest.parse(text(files), version(files), null)
    }

    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("pam-hot-reload-store").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
}
