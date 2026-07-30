package dev.pam.nativeapp

import android.content.Context
import java.io.File
import java.security.MessageDigest

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
        val staging = File(context.cacheDir, "pam-install-$version")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create Pam Native staging directory" }
        copyDirectory(ASSET_ROOT, staging)
        verifyManifest(staging, version)
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
        val canonical = manifestDigest(directory, legacyComponentOrder = false)
        if (canonical == expected) return
        val legacy = manifestDigest(directory, legacyComponentOrder = true)
        require(legacy == expected) { "Pam Native application bundle failed integrity verification" }
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
        const val RETAINED_INACTIVE_RELEASES = 1
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
