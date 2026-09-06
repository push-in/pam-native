package dev.pam.nativeapp.modules

import java.io.File
import java.security.MessageDigest

internal object PrivateFileSha256 {
    const val MAX_BYTES = 64L * 1024 * 1024

    fun digest(root: File, path: String, cancelled: () -> Boolean = { Thread.currentThread().isInterrupted }): String {
        require(path.isNotBlank() && path.toByteArray(Charsets.UTF_8).size <= 4096 && !path.startsWith('/') && '\\' !in path
            && path.none { it.code < 32 || it.code == 127 }
            && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Invalid private file path" }
        val base = root.canonicalFile
        val file = File(base, path).canonicalFile
        require(file.path.startsWith(base.path + File.separator) && file.isFile) { "File does not exist in the private directory" }
        val expected = file.length()
        require(expected <= MAX_BYTES) { "File exceeds the 64 MiB hash limit" }
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(!cancelled()) { "File hashing cancelled" }
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                require(count <= expected && count <= MAX_BYTES) { "File changed while hashing" }
                hash.update(buffer, 0, read)
            }
        }
        require(count == expected) { "File changed while hashing" }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
