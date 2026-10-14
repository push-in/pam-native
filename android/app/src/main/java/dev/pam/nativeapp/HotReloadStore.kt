package dev.pam.nativeapp

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Bytes for a development file that already exist on the device: the bundle
 * the runtime booted from (installed release or OTA directory) and, for plain
 * files the installer leaves in the APK, the APK assets.
 */
internal fun interface HotReloadLocalSource {
    /** Opens [path] if this source has it, or returns null. */
    fun open(path: String): InputStream?
}

/** What one version needs: files to write, files to delete, digests to download. */
internal class HotReloadPlan(
    val manifest: HotReloadManifest,
    val changed: List<HotReloadFile>,
    val removed: List<String>,
    val missing: Set<String>,
    /** No live tree to update in place: the whole tree is written anew. */
    val fresh: Boolean,
) {
    val isEmpty: Boolean get() = changed.isEmpty() && removed.isEmpty()
    val changedBytes: Long get() = changed.sumOf(HotReloadFile::size)
}

/**
 * The device side of `pam dev` sync, owned by the hot reload executor.
 *
 * Layout under [root] (`files/pam/dev`):
 * - `live/` the stable development tree the runtime runs. Its path never
 *   changes, so OPcache and the component compile cache (both keyed by path)
 *   only recompile files that really changed.
 * - `live.manifest` the manifest `live/` matches.
 * - `objects/` verified incoming files named by SHA-256, deleted once applied.
 *
 * A PHP request may still autoload from `live/` while it shuts down, so
 * edits to a tree in use are applied by [apply] between two requests (the
 * runtime calls it after the previous request released and before the next
 * one starts). Every written file gets a fresh, strictly increasing mtime so
 * OPcache revalidation never keeps stale code for two saves in one second.
 */
internal class HotReloadStore(
    private val root: File,
    private val localSources: List<HotReloadLocalSource> = emptyList(),
) {
    val live = File(root, "live")
    val liveEntry: File get() = File(live, "index.php")
    private val objects = File(root, "objects")
    private val manifestFile = File(root, "live.manifest")
    private val nextLive = File(root, "live.next")
    private var lastStampSeconds = 0L

    @get:Synchronized
    var liveManifest: HotReloadManifest? = null
        private set

    @get:Synchronized
    var pendingVersion: String? = null
        private set
    val hasPendingRequest: Boolean get() = pendingVersion != null

    init {
        liveManifest = loadLiveManifest()
        lastStampSeconds = manifestFile.lastModified() / 1_000
        // Leftovers of an interrupted session and per-version bundle
        // directories of hosts before 1.39.3; live/ itself stays valid.
        root.listFiles()?.forEach { child ->
            if (child.name !in KEPT) child.deleteRecursively()
        }
    }

    private fun loadLiveManifest(): HotReloadManifest? = runCatching {
        if (!manifestFile.isFile || !liveEntry.isFile) return null
        val lines = manifestFile.readText(Charsets.UTF_8)
        val version = lines.substringBefore('\n')
        HotReloadManifest.parse(lines.substringAfter('\n'), version, null)
    }.getOrNull()

    @Synchronized
    fun plan(next: HotReloadManifest): HotReloadPlan {
        val base = liveManifest?.takeIf { live.isDirectory }
        val changed = next.files.values.filter { file -> base?.files?.get(file.path)?.sha256 != file.sha256 }
        val removed = base?.files?.keys?.filter { it !in next.files }.orEmpty()
        val missing = LinkedHashSet<String>()
        changed.forEach { file ->
            if (!objectFile(file.sha256).isFile && !importLocal(file)) missing += file.sha256
        }
        return HotReloadPlan(next, changed, removed, missing, fresh = base == null)
    }

    /** Copies a file the device already holds into `objects/` after verifying its digest. */
    private fun importLocal(file: HotReloadFile): Boolean {
        for (source in localSources) {
            val input = runCatching { source.open(file.path) }.getOrNull() ?: continue
            val accepted = runCatching { input.use { writeObject(file.sha256, file.size, it) } }.getOrDefault(false)
            if (accepted) return true
        }
        return false
    }

    /**
     * Reads a `PNO1` stream from `pam dev` holding every digest of [expected]
     * and stores each verified file under `objects/`. Returns the bytes read.
     */
    @Synchronized
    fun receive(stream: InputStream, expected: Set<String>): Long {
        val input = DataInputStream(stream.buffered(COPY_BUFFER_BYTES))
        val magic = ByteArray(4).also(input::readFully)
        require(magic.contentEquals(OBJECTS_MAGIC)) { "Invalid hot reload object stream" }
        val remaining = expected.toMutableSet()
        var bytes = 0L
        val digest = ByteArray(32)
        while (remaining.isNotEmpty()) {
            input.readFully(digest)
            val sha256 = hexDigest(digest)
            require(remaining.remove(sha256)) { "Unexpected hot reload object" }
            val size = java.lang.Long.reverseBytes(input.readLong())
            require(size in 0..MAX_OBJECT_BYTES) { "Hot reload object is too large" }
            require(writeObject(sha256, size, BoundedInputStream(input, size))) {
                "Hot reload object failed verification"
            }
            bytes += size
        }
        return bytes
    }

    private fun writeObject(sha256: String, size: Long, input: InputStream): Boolean {
        check(objects.isDirectory || objects.mkdirs()) { "Cannot create hot reload object directory" }
        val target = objectFile(sha256)
        val temporary = File(objects, "$sha256.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        temporary.outputStream().use { output ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                written += read
                if (written > size) break
                digest.update(buffer, 0, read)
                output.write(buffer, 0, read)
            }
        }
        if (written != size || hexDigest(digest.digest()) != sha256) {
            temporary.delete()
            return false
        }
        check(temporary.renameTo(target)) { "Cannot store hot reload object" }
        return true
    }

    private fun objectFile(sha256: String) = File(objects, sha256)

    /** Marks [plan] as handed to the runtime; no other version starts until it is released. */
    @Synchronized
    fun begin(plan: HotReloadPlan) {
        check(pendingVersion == null) { "A PHP request is still switching bundles" }
        pendingVersion = plan.manifest.version
    }

    /**
     * Writes [plan] into `live/`. Call it only while no PHP request runs
     * from `live/`: before the first switch to it, or between two requests.
     */
    @Synchronized
    fun apply(plan: HotReloadPlan) {
        val stamp = nextStampMillis()
        if (plan.fresh) {
            nextLive.deleteRecursively()
            check(nextLive.mkdirs()) { "Cannot create hot reload directory" }
            plan.manifest.files.values.forEach { file -> place(file, nextLive, stamp) }
            live.deleteRecursively()
            check(nextLive.renameTo(live)) { "Cannot activate hot reload directory" }
        } else {
            plan.changed.forEach { file -> place(file, live, stamp) }
            plan.removed.forEach { path -> File(live, path).delete() }
        }
        val temporary = File(root, "live.manifest.tmp")
        temporary.writeText(plan.manifest.version + "\n" + plan.manifest.text(), Charsets.UTF_8)
        check(temporary.renameTo(manifestFile)) { "Cannot record hot reload manifest" }
        liveManifest = plan.manifest
        objects.deleteRecursively()
    }

    private fun place(file: HotReloadFile, directory: File, stampMillis: Long) {
        val target = File(directory, file.path)
        require(target.canonicalPath.startsWith(directory.canonicalPath + File.separator)) {
            "Hot reload path escapes destination"
        }
        val parent = checkNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create hot reload file directory" }
        val temporary = File(parent, ".${target.name}.pamnext")
        objectFile(file.sha256).inputStream().use { input ->
            temporary.outputStream().use { output -> input.copyTo(output, COPY_BUFFER_BYTES) }
        }
        temporary.setLastModified(stampMillis)
        check(temporary.renameTo(target)) { "Cannot activate hot reload file ${file.path}" }
    }

    /** Whole seconds (OPcache compares seconds), strictly increasing per apply. */
    private fun nextStampMillis(): Long {
        val seconds = maxOf(System.currentTimeMillis() / 1_000, lastStampSeconds + 1)
        lastStampSeconds = seconds
        return seconds * 1_000
    }

    /** The PHP request that ran the previous files shut down; [version] is now current. */
    @Synchronized
    fun previousRequestReleased(version: String) {
        if (pendingVersion == version) pendingVersion = null
    }

    /** Drops a version that could not be handed to the runtime. */
    @Synchronized
    fun abandon(version: String) {
        if (pendingVersion == version) pendingVersion = null
    }

    private class BoundedInputStream(private val input: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = input.read()
            if (value < 0) throw EOFException("Hot reload object is truncated")
            remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val read = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Hot reload object is truncated")
            remaining -= read
            return read
        }
    }

    companion object {
        private val OBJECTS_MAGIC = "PNO1".toByteArray(Charsets.US_ASCII)
        private const val MAX_OBJECT_BYTES = 64L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private val KEPT = setOf("live", "live.manifest")

        /** Files of the bundle directory the runtime booted from. */
        fun directorySource(directory: File): HotReloadLocalSource = HotReloadLocalSource { path ->
            File(directory, path).takeIf(File::isFile)?.inputStream()
        }
    }
}
