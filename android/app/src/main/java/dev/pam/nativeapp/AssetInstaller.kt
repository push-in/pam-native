package dev.pam.nativeapp

import android.content.Context
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
        val listing = runCatching {
            context.assets.open(BUNDLE_LISTING).bufferedReader().use { it.readLines() }
        }.getOrNull()
        if (listing != null) {
            // Build-time listing: no AssetManager.list() walk, files copied in
            // parallel and each verified against its own SHA-256.
            installListedBundle(
                parseBundleListing(listing),
                staging,
                Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
            ) { path -> context.assets.open("$ASSET_ROOT/$path") }
        } else {
            copyDirectory(ASSET_ROOT, staging)
            verifyManifest(staging, version)
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
        return digest.digest().joinToString("") { "%02x".format(it) }
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
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(copied == file.size && actual == file.sha256) {
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
