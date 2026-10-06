package dev.pam.nativeapp

import java.io.File

/** Owned by the reload executor; files outlive the PHP request that uses them. */
internal class HotReloadBundles(private val root: File) {
    var currentVersion: String? = null
        private set
    private var pendingVersion: String? = null
    val hasPendingRequest: Boolean get() = pendingVersion != null

    fun prepare(version: String, bytes: ByteArray): File {
        check(pendingVersion == null) { "A PHP request is still switching bundles" }
        require(version.matches(Regex("[a-f0-9]{16,64}")))
        val destination = File(root, version)
        val entry = File(destination, "index.php")
        // A retained Activity can already be executing this immutable hash.
        // Do not atomically replace files underneath that live PHP request.
        if (!entry.isFile) DevBundle.extract(bytes, destination)
        pendingVersion = version
        return entry
    }

    fun previousRequestReleased(version: String) {
        if (pendingVersion != version) return
        currentVersion = version
        pendingVersion = null
        root.listFiles()?.forEach { file ->
            if (file.name != version) file.deleteRecursively()
        }
    }
}
