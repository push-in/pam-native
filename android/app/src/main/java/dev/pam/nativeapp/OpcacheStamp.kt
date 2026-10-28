package dev.pam.nativeapp

import java.io.File

/**
 * Keys the OPcache file cache (`<state>/opcache`) to the PHP bundle it was
 * compiled from. Entries are keyed by absolute script path and release
 * builds never stat a source (`validate_timestamps=0`), so a bundle whose
 * bytes change under the same path would run the old opcodes. Release
 * bundles live in content-addressed directories (`releases/<digest>`,
 * `ota-releases/<digest>`); the stamp records that directory and the CLI
 * content digest beside the entry, and any change drops the whole cache
 * (which also frees the opcodes of pruned releases).
 */
internal object OpcacheStamp {
    private const val STAMP_FILE = "opcache.stamp"
    private val DIGEST = Regex("[a-f0-9]{64}")

    fun stamp(entry: File): String {
        val release = entry.absoluteFile.parentFile ?: return entry.absolutePath
        val digest = runCatching {
            File(release, "manifest.sha256").bufferedReader().use { it.readLine() }?.trim()?.lowercase()
        }.getOrNull()?.takeIf { it.matches(DIGEST) }.orEmpty()
        return "${release.absolutePath}|$digest"
    }

    /** Returns true when the cache was dropped (first run, new bundle). */
    fun prepare(stateDirectory: File, stamp: String): Boolean {
        val cache = File(stateDirectory, "opcache")
        val stampFile = File(stateDirectory, STAMP_FILE)
        val previous = runCatching { stampFile.readText() }.getOrNull()
        if (previous == stamp) return false
        cache.deleteRecursively()
        cache.mkdirs()
        val temporary = File(stateDirectory, "$STAMP_FILE.tmp")
        runCatching {
            temporary.writeText(stamp)
            if (!temporary.renameTo(stampFile)) {
                stampFile.delete()
                temporary.renameTo(stampFile)
            }
        }
        return true
    }
}
