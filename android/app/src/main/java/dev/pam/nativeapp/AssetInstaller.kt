package dev.pam.nativeapp

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class AssetInstaller(private val context: Context) {
    fun install(): File {
        val version = context.assets.open("$ASSET_ROOT/manifest.sha256").bufferedReader().use {
            it.readLine()?.trim().orEmpty()
        }
        require(version.matches(Regex("[a-f0-9]{64}"))) { "Invalid Pam Native asset manifest" }
        val release = File(context.filesDir, "pam/releases/$version")
        val entry = File(release, "index.php")
        if (entry.isFile) {
            scheduleReleaseCleanup(release)
            return entry
        }
        // Android may purge cacheDir while a large Composer tree is being
        // copied, producing a valid entry path whose nested templates vanish
        // before PHP starts. Installation is transactional application state,
        // not disposable cache, so stage beside the content-addressed releases.
        val staging = installationStagingDirectory(context.filesDir, version)
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create Pam Native staging directory" }
        val pack = runCatching {
            context.assets.open(BUNDLE_PACK, AssetManager.ACCESS_STREAMING)
        }.getOrNull()
        if (pack != null) {
            // One packed asset: a single sequential stream (no per-file
            // AssetManager open/lock), files written and verified in parallel.
            pack.use { input ->
                installPackedBundle(input, staging, installThreads()) { path ->
                    context.assets.open("$ASSET_ROOT/$path")
                }
            }
        } else {
            val listing = runCatching {
                context.assets.open(BUNDLE_LISTING).bufferedReader().use { it.readLines() }
            }.getOrNull()
            if (listing != null) {
                // Build-time listing (1.14 bundles): no AssetManager.list() walk,
                // files copied in parallel and each verified against its SHA-256.
                installListedBundle(parseBundleListing(listing), staging, installThreads()) { path ->
                    context.assets.open("$ASSET_ROOT/$path")
                }
            } else {
                copyDirectory(ASSET_ROOT, staging)
                verifyManifest(staging, version)
            }
        }
        release.parentFile?.mkdirs()
        if (release.exists()) {
            check(release.deleteRecursively()) {
                "Cannot remove an incomplete Pam Native application bundle"
            }
        }
        check(staging.renameTo(release)) { "Cannot activate Pam Native application bundle" }
        require(entry.isFile) { "Pam Native bundle does not contain index.php" }
        scheduleReleaseCleanup(release)
        return entry
    }

    /**
     * Release extraction is content-addressed, so upgrades never need to
     * overwrite a running bundle. Cleanup happens off the startup path and
     * retains one previous valid release for diagnostics or rollback.
     */
    private fun scheduleReleaseCleanup(activeRelease: File) {
        Thread(
            {
                runCatching { pruneReleases(activeRelease) }
            },
            "pam-release-cleanup",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun pruneReleases(activeRelease: File) {
        val releasesDirectory = activeRelease.parentFile ?: return
        staleReleaseDirectories(
            releasesDirectory = releasesDirectory,
            activeRelease = activeRelease,
            retainedInactiveReleases = RETAINED_INACTIVE_RELEASES,
        ).forEach { obsolete ->
            obsolete.deleteRecursively()
        }
    }

    private fun copyDirectory(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            context.assets.open(assetPath).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.renameTo(destination)) { "Cannot install ${destination.name}" }
            return
        }
        check(destination.mkdirs() || destination.isDirectory) {
            "Cannot create ${destination.path}"
        }
        children.forEach { name ->
            require(name.matches(Regex("[A-Za-z0-9._-]{1,255}")) && name != "..") {
                "Unsafe Pam Native asset path"
            }
            copyDirectory("$assetPath/$name", File(destination, name))
        }
    }

    private fun verifyManifest(directory: File, expected: String) {
        // The CLI (Rust `Path` ordering) hashes in path-component order, so
        // check that first; plain string order is kept for older bundles.
        // Matching the producer avoids a second full hash on every install.
        val cli = manifestDigest(directory, legacyComponentOrder = true)
        if (cli == expected) return
        val plain = manifestDigest(directory, legacyComponentOrder = false)
        require(plain == expected) { "Pam Native application bundle failed integrity verification" }
    }

    private fun manifestDigest(directory: File, legacyComponentOrder: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val files = files(directory)
            .filter { it.name != "manifest.sha256" }
            .let { candidates ->
                if (legacyComponentOrder) {
                    candidates.sortedWith { left, right ->
                        comparePathComponents(
                            left.relativeTo(directory).invariantSeparatorsPath,
                            right.relativeTo(directory).invariantSeparatorsPath,
                        )
                    }
                } else {
                    candidates.sortedBy { it.relativeTo(directory).invariantSeparatorsPath }
                }
            }
        files.forEach { file ->
            val relative = file.relativeTo(directory).invariantSeparatorsPath
            digest.update(relative.toByteArray())
            digest.update(0)
            file.inputStream().use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return hexDigest(digest.digest())
    }

    private fun comparePathComponents(left: String, right: String): Int {
        val leftParts = left.split('/')
        val rightParts = right.split('/')
        val common = minOf(leftParts.size, rightParts.size)
        for (index in 0 until common) {
            val comparison = leftParts[index].compareTo(rightParts[index])
            if (comparison != 0) return comparison
        }
        return leftParts.size.compareTo(rightParts.size)
    }

    private fun files(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile }
            .toList()

    private companion object {
        const val ASSET_ROOT = "pam"
        const val BUNDLE_LISTING = "pam-files.txt"
        const val BUNDLE_PACK = "pam-bundle.pnb"

        fun installThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        const val RETAINED_INACTIVE_RELEASES = 1
    }
}

/** One bundle file of the build-time listing: path, byte size, SHA-256. */
internal data class BundleFile(val path: String, val size: Long, val sha256: String)

/** Parses `<sha256> <size> <relative path>` lines written by the CLI. */
internal fun parseBundleListing(lines: List<String>): List<BundleFile> =
    lines.filter { it.isNotBlank() }.map { line ->
        val parts = line.split(' ', limit = 3)
        require(parts.size == 3) { "Invalid Pam Native bundle listing" }
        val (sha256, size, path) = parts
        require(sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid Pam Native bundle listing" }
        val bytes = size.toLongOrNull()
        require(bytes != null && bytes >= 0) { "Invalid Pam Native bundle listing" }
        require(
            path.split('/').all { segment ->
                segment.matches(Regex("[A-Za-z0-9._-]{1,255}")) && segment != "." && segment != ".."
            },
        ) { "Unsafe Pam Native asset path" }
        BundleFile(path, bytes, sha256)
    }

/**
 * Copies every listed file into [destination] with [threads] workers and
 * verifies each copy's size and SHA-256 while it streams.
 */
internal fun installListedBundle(
    files: List<BundleFile>,
    destination: File,
    threads: Int,
    open: (String) -> InputStream,
) {
    files.mapNotNull { File(destination, it.path).parentFile }.toSet().forEach { directory ->
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create ${directory.path}" }
    }
    val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1))
    try {
        val tasks: List<Future<*>> = files.map { file ->
            pool.submit {
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                open(file.path).use { input ->
                    File(destination, file.path).outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            copied += read
                        }
                    }
                }
                require(copied == file.size && MessageDigest.isEqual(digest.digest(), decodeSha256(file.sha256))) {
                    "Pam Native application bundle failed integrity verification"
                }
            }
        }
        tasks.forEach { task ->
            try {
                task.get()
            } catch (error: java.util.concurrent.ExecutionException) {
                throw error.cause ?: error
            }
        }
    } finally {
        pool.shutdownNow()
    }
}

internal fun staleReleaseDirectories(
    releasesDirectory: File,
    activeRelease: File,
    retainedInactiveReleases: Int,
): List<File> {
    require(retainedInactiveReleases >= 0)
    val activeCanonical = activeRelease.canonicalFile

    return releasesDirectory.listFiles()
        .orEmpty()
        .asSequence()
        .filter { candidate ->
            candidate.isDirectory &&
                candidate.name.matches(Regex("[a-f0-9]{64}")) &&
                candidate.canonicalFile != activeCanonical
        }
        .sortedByDescending(File::lastModified)
        .drop(retainedInactiveReleases)
        .toList()
}

internal fun installationStagingDirectory(filesDirectory: File, version: String): File {
    require(version.matches(Regex("[a-f0-9]{64}")))
    return File(filesDirectory, "pam/staging/pam-install-$version")
}

private const val PACK_MAGIC = "PNB1"
private const val MAX_PACK_INDEX_BYTES = 8 * 1024 * 1024
private const val MAX_PACK_FILES = 100_000
private const val MAX_PACK_FILE_BYTES = 256 * 1024 * 1024
private const val MAX_PACK_CHUNK_BYTES = 256 * 1024 * 1024
private const val MAX_PACK_BYTES_IN_FLIGHT = 16 * 1024 * 1024

/** One file of a packed bundle index (`<sha256> <size> <p|a> <path>`). */
internal class PackedBundleFile(
    val path: String,
    val size: Int,
    val sha256: ByteArray,
    /** In a deflated pack chunk; otherwise a plain APK asset `pam/<path>`. */
    val packed: Boolean,
)

/** An independently raw-deflated run of packed files (`c <compressed> <size>`). */
internal class PackedBundleChunk(
    val compressedSize: Int,
    val size: Int,
    val files: List<PackedBundleFile>,
)

internal class PackedBundleIndex(
    val plain: List<PackedBundleFile>,
    val chunks: List<PackedBundleChunk>,
)

/**
 * `PNB1` packed bundle: magic, little-endian u32 index length, UTF-8 index,
 * then the raw-deflate chunks in index order. Index lines:
 * `<sha256> <size> a <path>` (plain APK asset: images, fonts, CSS, files
 * host code may also read straight from the APK), `c <compressed> <size>`
 * (starts a chunk) and `<sha256> <size> p <path>` (next file of that chunk).
 */
internal fun parsePackedBundleIndex(index: String): PackedBundleIndex {
    // Hand-rolled (no Regex/split): this runs once per install on a cold,
    // still-interpreted process, where per-line regex matching cost ~100 ms.
    val plain = ArrayList<PackedBundleFile>()
    val chunks = ArrayList<PackedBundleChunk>()
    val seen = HashSet<String>()
    var chunkFiles: ArrayList<PackedBundleFile>? = null
    var chunkCompressed = 0
    var chunkSize = 0
    var chunkFilled = 0L
    var count = 0
    fun closeChunk() {
        val files = chunkFiles ?: return
        require(files.isNotEmpty() && chunkFilled == chunkSize.toLong()) { "Invalid Pam Native bundle index" }
        chunks += PackedBundleChunk(chunkCompressed, chunkSize, files)
        chunkFiles = null
    }
    fun number(text: String, from: Int, to: Int, maximum: Long): Long {
        require(to > from && to - from <= 12) { "Invalid Pam Native bundle index" }
        var value = 0L
        for (position in from until to) {
            val digit = text[position] - '0'
            require(digit in 0..9) { "Invalid Pam Native bundle index" }
            value = value * 10 + digit
        }
        require(value <= maximum) { "Invalid Pam Native bundle index" }
        return value
    }
    var start = 0
    while (start < index.length) {
        var end = index.indexOf('\n', start)
        if (end < 0) end = index.length
        if (end > start) {
            if (index.startsWith("c ", start)) {
                closeChunk()
                val split = index.indexOf(' ', start + 2)
                require(split in (start + 3) until end) { "Invalid Pam Native bundle index" }
                chunkCompressed = number(index, start + 2, split, MAX_PACK_CHUNK_BYTES.toLong()).toInt()
                chunkSize = number(index, split + 1, end, MAX_PACK_CHUNK_BYTES.toLong()).toInt()
                require(chunkCompressed > 0) { "Invalid Pam Native bundle index" }
                chunkFiles = ArrayList()
                chunkFilled = 0
            } else {
                val shaEnd = start + 64
                require(end > shaEnd + 4 && index[shaEnd] == ' ') { "Invalid Pam Native bundle index" }
                val sizeEnd = index.indexOf(' ', shaEnd + 1)
                require(sizeEnd in (shaEnd + 2) until end - 2) { "Invalid Pam Native bundle index" }
                val size = number(index, shaEnd + 1, sizeEnd, MAX_PACK_FILE_BYTES.toLong()).toInt()
                val kind = index[sizeEnd + 1]
                require((kind == 'p' || kind == 'a') && index[sizeEnd + 2] == ' ') { "Invalid Pam Native bundle index" }
                val path = index.substring(sizeEnd + 3, end)
                require(isSafeBundlePath(path)) { "Unsafe Pam Native asset path" }
                require(seen.add(path)) { "Duplicate Pam Native bundle path" }
                require(++count <= MAX_PACK_FILES) { "Invalid Pam Native bundle index" }
                val file = PackedBundleFile(path, size, decodeSha256(index.substring(start, shaEnd)), kind == 'p')
                if (file.packed) {
                    val files = chunkFiles
                    requireNotNull(files) { "Invalid Pam Native bundle index" }
                    files += file
                    chunkFilled += size
                } else {
                    plain += file
                }
            }
        }
        start = end + 1
    }
    closeChunk()
    require(count > 0) { "Invalid Pam Native bundle index" }
    return PackedBundleIndex(plain, chunks)
}

/** Relative `[A-Za-z0-9._-]` segments of 1-255 characters, never `.` or `..`. */
internal fun isSafeBundlePath(path: String): Boolean {
    var segmentStart = 0
    for (position in 0..path.length) {
        if (position == path.length || path[position] == '/') {
            val length = position - segmentStart
            if (length !in 1..255) return false
            if (length <= 2 && path.regionMatches(segmentStart, "..", 0, length)) return false
            segmentStart = position + 1
        } else {
            val character = path[position]
            if (!(character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' ||
                    character == '.' || character == '_' || character == '-')
            ) {
                return false
            }
        }
    }
    return true
}

/**
 * Installs a `PNB1` packed bundle into [destination]. The pack stream only
 * supplies compressed chunks (one sequential read, no per-file AssetManager
 * open); [threads] workers inflate them, verify every file's size and
 * SHA-256 against the index and write it. Plain APK assets are copied on
 * their own thread meanwhile. At most [MAX_PACK_BYTES_IN_FLIGHT] chunk bytes
 * are buffered ahead of the workers.
 */
internal fun installPackedBundle(
    pack: InputStream,
    destination: File,
    threads: Int,
    openAsset: (String) -> InputStream,
) {
    val input = java.io.DataInputStream(java.io.BufferedInputStream(pack, 64 * 1024))
    val magic = ByteArray(4).also(input::readFully)
    require(String(magic, Charsets.US_ASCII) == PACK_MAGIC) { "Invalid Pam Native bundle pack" }
    val indexLength = Integer.reverseBytes(input.readInt())
    require(indexLength in 1..MAX_PACK_INDEX_BYTES) { "Invalid Pam Native bundle index" }
    val index = parsePackedBundleIndex(String(ByteArray(indexLength).also(input::readFully), Charsets.UTF_8))
    val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1)) { runnable ->
        Thread({
            // On the first launch's critical path: ahead of default-priority work.
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
            runnable.run()
        }, "pam-asset-install-worker").apply { isDaemon = true }
    }
    val budget = java.util.concurrent.Semaphore(MAX_PACK_BYTES_IN_FLIGHT)
    val tasks = ArrayList<Future<*>>(index.chunks.size + 1)
    val plainTask = java.util.concurrent.FutureTask {
        index.plain.forEach { file ->
            check(!Thread.currentThread().isInterrupted) { "Pam Native bundle install was cancelled" }
            val bytes = openAsset(file.path).use { it.readBytes() }
            writeVerifiedBundleFile(destination, file, bytes, 0)
        }
    }
    try {
        Thread(plainTask, "pam-asset-install-plain").apply {
            isDaemon = true
            start()
        }
        tasks += plainTask
        index.chunks.forEach { chunk ->
            val permits = (chunk.compressedSize + chunk.size).coerceAtMost(MAX_PACK_BYTES_IN_FLIGHT)
            budget.acquire(permits)
            // One spare zero byte: raw (nowrap) inflate may need input past the stream end.
            val compressed = try {
                ByteArray(chunk.compressedSize + 1).also { input.readFully(it, 0, chunk.compressedSize) }
            } catch (error: Throwable) {
                budget.release(permits)
                throw error
            }
            tasks += pool.submit {
                try {
                    installChunk(destination, chunk, compressed)
                } finally {
                    budget.release(permits)
                }
            }
        }
        require(input.read() < 0) { "Pam Native bundle pack contains trailing bytes" }
        tasks.forEach { task ->
            try {
                task.get()
            } catch (error: java.util.concurrent.ExecutionException) {
                throw error.cause ?: error
            }
        }
    } finally {
        plainTask.cancel(true)
        pool.shutdownNow()
    }
}

private fun installChunk(destination: File, chunk: PackedBundleChunk, compressed: ByteArray) {
    val bytes = ByteArray(chunk.size)
    val inflater = java.util.zip.Inflater(true)
    try {
        inflater.setInput(compressed)
        var filled = 0
        while (filled < bytes.size) {
            val count = inflater.inflate(bytes, filled, bytes.size - filled)
            if (count == 0) {
                require(!inflater.finished() && !inflater.needsInput() && !inflater.needsDictionary()) {
                    "Pam Native bundle pack chunk is truncated"
                }
            }
            filled += count
        }
        require((inflater.finished() || inflater.inflate(ByteArray(1)) == 0 && inflater.finished()) && inflater.remaining <= 1) {
            "Pam Native bundle pack chunk is longer than its index"
        }
    } finally {
        inflater.end()
    }
    var offset = 0
    chunk.files.forEach { file ->
        writeVerifiedBundleFile(destination, file, bytes, offset)
        offset += file.size
    }
}

private fun writeVerifiedBundleFile(destination: File, file: PackedBundleFile, bytes: ByteArray, offset: Int) {
    require(offset >= 0 && bytes.size - offset >= file.size) {
        "Pam Native application bundle failed integrity verification"
    }
    val digest = SHA256.get()!!
    digest.update(bytes, offset, file.size)
    require((file.packed || bytes.size == file.size) && MessageDigest.isEqual(digest.digest(), file.sha256)) {
        "Pam Native application bundle failed integrity verification"
    }
    openBundleOutput(File(destination, file.path)).use { it.write(bytes, offset, file.size) }
}

/** Creates the file; its directory is made on first use (no upfront serial mkdir pass). */
private fun openBundleOutput(target: File): java.io.FileOutputStream =
    try {
        java.io.FileOutputStream(target)
    } catch (missing: java.io.FileNotFoundException) {
        val directory = target.parentFile
        check(directory != null && (directory.mkdirs() || directory.isDirectory)) {
            "Cannot create ${directory?.path}"
        }
        java.io.FileOutputStream(target)
    }

private val SHA256 = ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }


internal fun decodeSha256(hex: String): ByteArray {
    require(hex.length == 64) { "Invalid Pam Native bundle digest" }
    fun nibble(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        else -> throw IllegalArgumentException("Invalid Pam Native bundle digest")
    }
    return ByteArray(32) { index ->
        ((nibble(hex[index * 2]) shl 4) or nibble(hex[index * 2 + 1])).toByte()
    }
}

internal fun hexDigest(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    val text = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        text[index * 2] = digits[value ushr 4]
        text[index * 2 + 1] = digits[value and 0x0f]
    }
    return String(text)
}
