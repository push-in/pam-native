package dev.pam.nativeapp

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater

/**
 * A store build whose process died during the first extraction (emulator
 * powered off, user swipe, battery, low-memory killer) left 1239 zero-byte
 * PHP sources beside a valid index.php; every later launch reused that tree
 * and failed with "Class Pam\Native\I18n\I18n not found" until the app was
 * reinstalled. A release is now reused only when its completion marker lists
 * every file at its size; anything else is discarded and extracted again.
 */
@RunWith(AndroidJUnit4::class)
class AssetInstallerInterruptedExtractionInstrumentedTest {
    private lateinit var root: File
    private val bundle = syntheticBundle(files = 120)
    private val packOpens = AtomicInteger()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.filesDir, "pam-release-test").apply { deleteRecursively() }
        assertTrue(root.mkdirs())
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun installer(bundle: Bundle = this.bundle) = AssetInstaller(root, { path ->
        if (path == "pam-bundle.pnb") packOpens.incrementAndGet()
        bundle.open(path)
    }, { emptyArray() })

    private fun release(bundle: Bundle = this.bundle) = File(root, "pam/releases/${bundle.version}")

    private fun assertInstalled(bundle: Bundle = this.bundle) {
        val release = release(bundle)
        bundle.php.forEach { (path, bytes) ->
            assertTrue("$path content", File(release, path).readBytes().contentEquals(bytes))
        }
        assertTrue(ReleaseIntegrity.isComplete(release, bundle.version))
    }

    @Test
    fun anExtractionKilledBeforeItsMarkerIsDiscardedAndExtractedAgain() {
        // What the killed process left behind: the release name and index.php
        // reached the disk, the other sources are empty or missing, no marker.
        val release = release()
        bundle.php.entries.forEachIndexed { index, (path, bytes) ->
            val file = File(release, path)
            file.parentFile!!.mkdirs()
            when {
                path == "index.php" -> file.writeBytes(bytes)
                index % 3 == 0 -> Unit
                else -> file.writeBytes(ByteArray(0))
            }
        }
        assertFalse(ReleaseIntegrity.isComplete(release, bundle.version))

        val entry = installer().install()

        assertEquals(File(release, "index.php"), entry)
        assertEquals(1, packOpens.get())
        assertInstalled()
        assertFalse("Staging is consumed by the rename", File(root, "pam/staging/pam-install-${bundle.version}").exists())
    }

    @Test
    fun aMarkedReleaseWithATruncatedOrMissingFileIsExtractedAgain() {
        installer().install()
        val release = release()
        val first = ReleaseIntegrity.installationId(release)
        assertNotNull(first)

        val victim = File(release, bundle.php.keys.first { it.startsWith("vendor/") })
        RandomAccessFile(victim, "rw").use { it.setLength(0) }
        installer().install()
        assertInstalled()
        val second = ReleaseIntegrity.installationId(release)
        assertNotEquals("Re-extraction gets a new installation id (OPcache key)", first, second)

        assertTrue(victim.delete())
        installer().install()
        assertInstalled()
        assertNotEquals(second, ReleaseIntegrity.installationId(release))
        assertEquals(3, packOpens.get())
    }

    @Test
    fun aCompleteReleaseIsReusedWithoutExtracting() {
        installer().install()
        val release = release()
        val id = ReleaseIntegrity.installationId(release)
        val stamp = File(release, "index.php").lastModified()
        repeat(3) { installer().install() }
        assertEquals("No re-extraction on a normal boot", id, ReleaseIntegrity.installationId(release))
        assertEquals(stamp, File(release, "index.php").lastModified())
        // Only the deferred-asset probe may peek at the pack index; nothing is written.
        assertInstalled()
    }

    @Test
    fun aMarkerForAnotherVersionDoesNotValidateTheRelease() {
        installer().install()
        val release = release()
        val marker = File(release, ReleaseIntegrity.MARKER)
        marker.writeText(marker.readText().replaceFirst(bundle.version, "f".repeat(64)))
        assertFalse(ReleaseIntegrity.isComplete(release, bundle.version))
        installer().install()
        assertInstalled()
    }

    @Test
    fun anInterruptedOtaSlotIsExtractedAgainInsteadOfReused() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val updates = File(context.filesDir, "pam/updates")
        val ota = File(context.filesDir, "pam/ota-releases")
        try {
            updates.deleteRecursively()
            ota.deleteRecursively()
            updates.mkdirs()
            val php = linkedMapOf(
                "index.php" to "<?php require __DIR__.'/src/App.php';".toByteArray(),
                "src/App.php" to "<?php final class App {}".toByteArray(),
                "src/Lang/I18n.php" to "<?php final class I18n {}".toByteArray(),
            )
            val archive = ByteArrayOutputStream().apply {
                write("PNA1".toByteArray())
                write(le32(php.size))
                php.forEach { (path, bytes) ->
                    write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(path.length.toShort()).array())
                    write(path.toByteArray())
                    write(le32(bytes.size))
                    write(bytes)
                }
            }.toByteArray()
            val digest = sha256Hex(archive)
            File(updates, "active.bundle").writeBytes(archive)
            File(updates, "active.json").writeText(JSONObject().put("version", 1).put("bundleSha256", digest).toString())
            val embedded = File(root, "pam/releases/${"e".repeat(64)}/index.php")
            File(updates, "active.base").writeText(embedded.parentFile!!.name)

            val slot = File(ota, digest)
            File(slot, "src/Lang").mkdirs()
            File(slot, "index.php").writeBytes(php.getValue("index.php"))
            File(slot, "src/App.php").writeBytes(ByteArray(0))

            val entry = ActiveUpdateInstaller(context).resolve(embedded)

            assertEquals(File(slot, "index.php"), entry)
            php.forEach { (path, bytes) -> assertTrue(path, File(slot, path).readBytes().contentEquals(bytes)) }
            assertTrue(ReleaseIntegrity.isComplete(slot, digest))
            val id = ReleaseIntegrity.installationId(slot)
            ActiveUpdateInstaller(context).resolve(embedded)
            assertEquals("A complete slot is reused", id, ReleaseIntegrity.installationId(slot))
        } finally {
            updates.deleteRecursively()
            ota.deleteRecursively()
        }
    }

    /**
     * First and warm boot timings. Uses the Zé Chat store bundle when it was
     * pushed to `/data/local/tmp/pam-release-bench` (`pam-bundle.pnb` plus
     * the `pam/` asset tree); otherwise a synthetic bundle of the same shape.
     */
    @Test
    fun reportsFirstAndWarmBootTimings() {
        val bench = File("/data/local/tmp/pam-release-bench")
        val source = if (File(bench, "pam-bundle.pnb").canRead()) {
            Bundle(
                version = File(bench, "pam/manifest.sha256").readText().trim(),
                php = emptyMap(),
                open = { path -> File(bench, path).inputStream() },
            )
        } else {
            syntheticBundle(files = 2_500)
        }
        val firsts = ArrayList<Long>()
        repeat(3) {
            root.deleteRecursively()
            root.mkdirs()
            val started = SystemClock.elapsedRealtimeNanos()
            installer(source).install()
            firsts += (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
        }
        val release = release(source)
        DeferredBundleAssets.resume()
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!File(release, ".assets-ready").isFile && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        DeferredBundleAssets.pause()
        val files = ReleaseIntegrity.inventory(release).size
        val checks = (1..20).map {
            val started = SystemClock.elapsedRealtimeNanos()
            assertTrue(ReleaseIntegrity.isComplete(release, source.version))
            (SystemClock.elapsedRealtimeNanos() - started) / 1_000
        }.sorted()
        val warm = (1..20).map {
            val started = SystemClock.elapsedRealtimeNanos()
            installer(source).install()
            (SystemClock.elapsedRealtimeNanos() - started) / 1_000
        }.sorted()
        val sequential = (1..20).map {
            val started = SystemClock.elapsedRealtimeNanos()
            assertTrue(ReleaseIntegrity.isComplete(release, source.version, threads = 1))
            (SystemClock.elapsedRealtimeNanos() - started) / 1_000
        }.sorted()
        val report = "bundle=${if (source.php.isEmpty()) "zechat" else "synthetic"} files=$files " +
            "firstInstallMs=$firsts sequentialCheckUsMedian=${sequential[sequential.size / 2]} markerCheckUsMedian=${checks[checks.size / 2]} markerCheckUsMax=${checks.last()} " +
            "warmInstallUsMedian=${warm[warm.size / 2]} warmInstallUsMax=${warm.last()}"
        Log.i("PamReleaseBench", report)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stream", report + "\n") })
    }

    class Bundle(val version: String, val php: Map<String, ByteArray>, val open: (String) -> InputStream)

    private companion object {
        fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

        fun sha256Hex(bytes: ByteArray): String = hexDigest(MessageDigest.getInstance("SHA-256").digest(bytes))

        /** A PNB1 pack of [files] PHP sources (vendor tree shape), no plain assets. */
        fun syntheticBundle(files: Int): Bundle {
            val php = LinkedHashMap<String, ByteArray>()
            php["index.php"] = "<?php require __DIR__.'/vendor/autoload.php';\n".toByteArray()
            php["manifest.sha256"] = ByteArray(0)
            for (index in 0 until files) {
                val path = "vendor/pkg${index % 40}/src/Dir${index % 7}/Class$index.php"
                php[path] = ("<?php\nnamespace Pkg;\nfinal class Class$index {\n" + "    // filler\n".repeat(index % 300) + "}\n").toByteArray()
            }
            val version = sha256Hex(php.keys.joinToString("\n").toByteArray())
            php["manifest.sha256"] = "$version\n".toByteArray()
            val entries = php.entries.toList()
            val chunks = entries.chunked(200).map { group ->
                val raw = ByteArrayOutputStream().apply { group.forEach { write(it.value) } }.toByteArray()
                val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
                val compressed = try {
                    deflater.setInput(raw)
                    deflater.finish()
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(64 * 1024)
                    while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
                    out.toByteArray()
                } finally {
                    deflater.end()
                }
                Triple(group, compressed, raw.size)
            }
            val index = buildString {
                chunks.forEach { (group, compressed, size) ->
                    append("c ${compressed.size} $size\n")
                    group.forEach { (path, bytes) -> append("${sha256Hex(bytes)} ${bytes.size} p $path\n") }
                }
            }.toByteArray()
            val pack = ByteArrayOutputStream().apply {
                write("PNB1".toByteArray())
                write(le32(index.size))
                write(index)
                chunks.forEach { write(it.second) }
            }.toByteArray()
            return Bundle(version, php) { path ->
                when (path) {
                    "pam/manifest.sha256" -> php.getValue("manifest.sha256").inputStream()
                    "pam-bundle.pnb" -> pack.inputStream()
                    else -> throw FileNotFoundException(path)
                }
            }
        }
    }
}
