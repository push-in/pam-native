package dev.pam.nativeapp

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object DevBundle {
    private const val MAX_BUNDLE_BYTES = 16 * 1024 * 1024
    private const val MAX_FILES = 10_000
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024

    fun extract(
        bytes: ByteArray,
        destination: File,
        maximumBundleBytes: Int = MAX_BUNDLE_BYTES,
    ): File {
        require(maximumBundleBytes in 1..MAX_PRODUCTION_BUNDLE_BYTES)
        require(bytes.size <= maximumBundleBytes) { "PAM Native bundle exceeds its size limit" }
        val parent = destination.parentFile ?: error("Hot reload destination has no parent")
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create hot reload parent directory" }
        val staging = File(parent, ".${destination.name}.incoming")
        val backup = File(parent, ".${destination.name}.previous")
        if (!destination.exists() && backup.exists()) {
            check(backup.renameTo(destination)) { "Cannot recover previous hot reload directory" }
        }
        staging.deleteRecursively()
        check(staging.mkdir()) { "Cannot create hot reload staging directory" }

        try {
            extractInto(bytes, staging)
            activate(staging, destination, backup)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
        return File(destination, "index.php")
    }

    private const val MAX_PRODUCTION_BUNDLE_BYTES = 256 * 1024 * 1024

    private fun extractInto(bytes: ByteArray, staging: File) {
        val reader = Reader(bytes)
        require(reader.text(4) == "PNA1") { "Invalid hot reload bundle" }
        val count = reader.u32()
        require(count in 1..MAX_FILES) { "Invalid hot reload file count" }
        val paths = HashSet<String>(count)
        repeat(count) {
            val path = reader.text(reader.u16())
            require(isSafePath(path)) { "Unsafe hot reload path" }
            require(paths.add(path)) { "Duplicate hot reload path" }
            val contents = reader.bytes(reader.u32().also {
                require(it <= MAX_FILE_BYTES) { "Hot reload file is too large" }
            })
            val target = File(staging, path)
            require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                "Hot reload path escapes destination"
            }
            check(target.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
                "Cannot create hot reload file directory"
            }
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeBytes(contents)
            check(temporary.renameTo(target)) { "Cannot activate hot reload file" }
        }
        reader.finish()
        require(File(staging, "index.php").isFile) {
            "Hot reload bundle does not contain index.php"
        }
    }

    private fun activate(staging: File, destination: File, backup: File) {
        backup.deleteRecursively()
        val hadActive = destination.exists()
        if (hadActive) {
            check(destination.renameTo(backup)) { "Cannot preserve active hot reload directory" }
        }
        if (!staging.renameTo(destination)) {
            if (hadActive) {
                check(backup.renameTo(destination)) { "Cannot restore active hot reload directory" }
            }
            error("Cannot activate hot reload directory")
        }
        backup.deleteRecursively()
    }

    private fun isSafePath(path: String): Boolean =
        path.isNotEmpty() &&
            !path.startsWith('/') &&
            !path.contains('\\') &&
            path.split('/').all {
                it.isNotEmpty() && it != "." && it != ".." &&
                    it.matches(Regex("[A-Za-z0-9._-]{1,255}"))
            }

    private class Reader(bytes: ByteArray) {
        private val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        fun u16(): Int = take(2).short.toInt() and 0xffff

        fun u32(): Int {
            val value = take(4).int.toLong() and 0xffff_ffffL
            require(value <= Int.MAX_VALUE) { "Hot reload field is too large" }
            return value.toInt()
        }

        fun text(length: Int): String = bytes(length).toString(Charsets.UTF_8)

        fun bytes(length: Int): ByteArray =
            ByteArray(length).also { take(length).get(it) }

        fun finish() {
            require(!buffer.hasRemaining()) { "Hot reload bundle contains trailing bytes" }
        }

        private fun take(length: Int): ByteBuffer {
            require(length >= 0 && buffer.remaining() >= length) { "Hot reload bundle is truncated" }
            return buffer.slice().order(ByteOrder.LITTLE_ENDIAN).also {
                it.limit(length)
                buffer.position(buffer.position() + length)
            }
        }
    }
}
