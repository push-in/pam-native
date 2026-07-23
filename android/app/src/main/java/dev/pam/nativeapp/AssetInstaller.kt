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
        return entry
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
        val digest = MessageDigest.getInstance("SHA-256")
        files(directory)
            .filter { it.name != "manifest.sha256" }
            .forEach { file ->
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
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        require(actual == expected) { "Pam Native application bundle failed integrity verification" }
    }

    private fun files(root: File): List<File> =
        root.walkTopDown()
            .filter { it.isFile }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
            .toList()

    private companion object {
        const val ASSET_ROOT = "pam"
    }
}
