package dev.pam.nativeapp.modules

import java.io.File
import java.io.IOException

/** Stable, bounded file body owned by one HTTP request. */
internal class HttpUploadSnapshot private constructor(val file: File) : AutoCloseable {
    override fun close() {
        if (file.exists() && !file.delete()) throw IOException("Cannot remove HTTP upload snapshot")
    }

    companion object {
        const val MAX_BYTES = 64L * 1024 * 1024

        fun create(root: File, cache: File, path: String, cancelled: () -> Boolean = { false }): HttpUploadSnapshot {
            require(path.isNotBlank() && !File(path).isAbsolute && '\\' !in path && '\u0000' !in path) {
                "Upload source must be a relative file path"
            }
            require(path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "Invalid upload source path"
            }
            val source = File(root, path).canonicalFile
            require(source.path.startsWith(root.canonicalPath + File.separator) && source.isFile) {
                "Upload source is outside the private files directory or missing"
            }
            val expected = source.length()
            require(expected <= MAX_BYTES) { "Upload file exceeds 64 MiB" }
            check(!cancelled()) { "HTTP upload cancelled" }
            check(cache.isDirectory || cache.mkdirs()) { "Cannot create HTTP upload cache" }
            val target = File.createTempFile("pam-upload-", ".tmp", cache)
            try {
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            check(!cancelled()) { "HTTP upload cancelled" }
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            require(copied <= expected && copied <= MAX_BYTES) { "Upload source changed during copy" }
                            output.write(buffer, 0, count)
                        }
                        require(copied == expected) { "Upload source changed during copy" }
                    }
                }
                return HttpUploadSnapshot(target)
            } catch (error: Exception) {
                target.delete()
                throw error
            }
        }
    }
}
