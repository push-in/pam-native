package dev.pam.nativeapp

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID

/**
 * Completion marker of an extracted release directory (`.pam-release`).
 *
 * A process killed mid-extraction (user swipe, battery, low-memory killer,
 * emulator power-off) can leave the release path populated with files whose
 * data never reached the disk: zero-byte or missing PHP sources behind a
 * present `index.php`. A release is only reused when this marker, written
 * last after every file and directory was fsynced, lists every file at its
 * recorded size. The boot check is one small read plus one stat per file,
 * never a content hash.
 *
 * Format: `pam-release 1 <version> <installation id>`, then one
 * `<size> <relative path>` line per file. The installation id changes on
 * every extraction so caches keyed by path (the OPcache file cache) can tell
 * a re-extracted release from the one they compiled.
 */
internal object ReleaseIntegrity {
    const val MARKER = ".pam-release"
    private const val HEADER = "pam-release 1 "
    private const val MIN_FILES_PER_THREAD = 256
    private const val SYNC_THREADS = 16

    private fun defaultThreads(): Int = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    /** Files of [root] (the marker excluded) with their sizes. */
    fun inventory(root: File): List<Pair<String, Long>> {
        val prefix = root.path.length + 1
        return root.walkTopDown()
            .filter { it.isFile && it.name != MARKER }
            .map { it.path.substring(prefix).replace(File.separatorChar, '/') to it.length() }
            .toList()
    }

    /** Fsyncs [root] and every directory that holds an [files] entry, deepest first. */
    fun syncDirectories(root: File, files: List<Pair<String, Long>>) {
        val directories = HashSet<String>()
        files.forEach { (path, _) ->
            var end = path.lastIndexOf('/')
            while (end > 0 && directories.add(path.substring(0, end))) end = path.lastIndexOf('/', end - 1)
        }
        directories.sortedByDescending { it.length }.forEach { syncDirectory(File(root, it)) }
        syncDirectory(root)
    }

    /**
     * Writes the marker durably (temporary, fsync, rename, directory fsync).
     * Callers fsync every listed file and directory first.
     */
    fun write(release: File, version: String, files: List<Pair<String, Long>>): String {
        require(version.isNotEmpty() && version.none { it == ' ' || it == '\n' })
        val id = UUID.randomUUID().toString().replace("-", "")
        val text = StringBuilder(64 + files.size * 64)
        text.append(HEADER).append(version).append(' ').append(id).append('\n')
        files.forEach { (path, size) ->
            require('\n' !in path) { "Unsafe Pam Native release path" }
            text.append(size).append(' ').append(path).append('\n')
        }
        val temporary = File(release, "$MARKER.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(text.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        check(temporary.renameTo(File(release, MARKER))) { "Cannot record the Pam Native release" }
        syncDirectory(release)
        return id
    }

    /**
     * True when [release] carries a marker for [version] and every listed file
     * exists at its recorded size (one stat each; no content is read). The
     * stats run on [threads] threads (the caller included): a few thousand
     * Composer sources otherwise cost tens of milliseconds on a slow device.
     */
    fun isComplete(release: File, version: String, threads: Int = defaultThreads()): Boolean {
        val text = try {
            File(release, MARKER).readText(Charsets.UTF_8)
        } catch (_: java.io.IOException) {
            return false
        }
        val headerEnd = text.indexOf('\n')
        if (headerEnd < 0 || !text.startsWith(HEADER)) return false
        val versionEnd = text.indexOf(' ', HEADER.length)
        if (versionEnd < 0 || versionEnd > headerEnd || text.substring(HEADER.length, versionEnd) != version) return false
        val base = release.path + File.separator
        val paths = ArrayList<String>(text.length / 64)
        val sizes = ArrayList<Long>(text.length / 64)
        var start = headerEnd + 1
        while (start < text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            val space = text.indexOf(' ', start)
            if (space <= start || space >= end - 1) return false
            sizes += text.substring(start, space).toLongOrNull() ?: return false
            paths += base + text.substring(space + 1, end)
            start = end + 1
        }
        if (paths.isEmpty()) return false
        val workers = threads.coerceIn(1, 8).coerceAtMost((paths.size + MIN_FILES_PER_THREAD - 1) / MIN_FILES_PER_THREAD)
        val intact = java.util.concurrent.atomic.AtomicBoolean(true)
        fun check(slice: Int) {
            var index = slice
            while (index < paths.size && intact.get()) {
                val file = File(paths[index])
                val size = sizes[index]
                val length = file.length()
                if (length != size || (size == 0L && !file.isFile)) intact.set(false)
                index += workers
            }
        }
        val helpers = (1 until workers).map { slice ->
            Thread({ check(slice) }, "pam-release-check").apply {
                isDaemon = true
                start()
            }
        }
        check(0)
        helpers.forEach(Thread::join)
        return intact.get()
    }

    /** Installation id of a complete release's marker, or null without one. */
    fun installationId(release: File): String? = try {
        File(release, MARKER).bufferedReader(Charsets.UTF_8).use { it.readLine() }
            ?.takeIf { it.startsWith(HEADER) }
            ?.substringAfterLast(' ')
            ?.takeIf { it.isNotEmpty() }
    } catch (_: java.io.IOException) {
        null
    }

    /** Fsyncs a regular file's data and metadata. */
    fun syncFile(file: File) {
        java.io.RandomAccessFile(file, "r").use { it.fd.sync() }
    }

    /**
     * Fsyncs every file in [files] under [root]. The calls are I/O bound and
     * concurrent fsyncs are merged into shared device flushes (f2fs
     * `flush_merge`, ext4 journal batching), so a wide pool costs a fraction
     * of a serial pass.
     */
    fun syncFiles(root: File, files: List<Pair<String, Long>>, threads: Int = SYNC_THREADS) {
        if (files.isEmpty()) return
        val next = java.util.concurrent.atomic.AtomicInteger()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        fun drain() {
            while (failure.get() == null) {
                val index = next.getAndIncrement()
                if (index >= files.size) return
                try {
                    syncFile(File(root, files[index].first))
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }
        }
        val helpers = (1 until threads.coerceIn(1, files.size)).map {
            Thread(::drain, "pam-release-sync").apply {
                isDaemon = true
                start()
            }
        }
        drain()
        helpers.forEach(Thread::join)
        failure.get()?.let { throw it }
    }

    /**
     * Makes the directory entries of [directory] durable. Best effort: a
     * filesystem that refuses a directory fsync still gets the marker check.
     */
    fun syncDirectory(directory: File) {
        try {
            val descriptor = android.system.Os.open(directory.path, android.system.OsConstants.O_RDONLY, 0)
            try {
                android.system.Os.fsync(descriptor)
            } finally {
                android.system.Os.close(descriptor)
            }
            return
        } catch (_: Throwable) {
            // JVM unit tests (no android.system.Os) or a filesystem without directory fsync.
        }
        runCatching { FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) } }
    }
}
