package dev.pam.nativeapp

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Resolves an already verified PHP OTA slot without granting the host network authority. */
internal class ActiveUpdateInstaller(private val context: Context) {
    fun resolve(embeddedEntry: File): File {
        val updates = File(context.filesDir, "pam/updates")
        val bundle = File(updates, "active.bundle")
        val metadata = File(updates, "active.json")
        if (!bundle.isFile || !metadata.isFile) return embeddedEntry

        return runCatching {
            require(bundle.length() in 1..MAX_BUNDLE_BYTES.toLong()) { "OTA bundle size is invalid" }
            val manifest = JSONObject(metadata.readText(Charsets.UTF_8))
            require(manifest.optInt("version") == 1) { "OTA manifest version is invalid" }
            val expected = manifest.getString("bundleSha256")
            require(expected.matches(Regex("[a-f0-9]{64}"))) { "OTA bundle digest is invalid" }
            require(sha256(bundle) == expected) { "OTA bundle integrity check failed" }

            val destination = File(context.filesDir, "pam/ota-releases/$expected")
            val existing = File(destination, "index.php")
            if (existing.isFile) existing else DevBundle.extract(
                bundle.readBytes(),
                destination,
                MAX_BUNDLE_BYTES,
            )
        }.getOrElse {
            quarantine(updates)
            embeddedEntry
        }
    }

    private fun quarantine(updates: File) {
        val failed = File(updates, "failed.bundle")
        if (failed.exists()) failed.delete()
        File(updates, "active.bundle").renameTo(failed)
        val failedMetadata = File(updates, "failed.json")
        if (failedMetadata.exists()) failedMetadata.delete()
        File(updates, "active.json").renameTo(failedMetadata)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_BUNDLE_BYTES = 256 * 1024 * 1024
    }
}
