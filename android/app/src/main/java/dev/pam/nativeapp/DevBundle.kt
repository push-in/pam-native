package dev.pam.nativeapp

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object DevBundle {
    private const val MAX_BUNDLE_BYTES = 16 * 1024 * 1024
    private const val MAX_FILES = 10_000
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024

    fun extract(bytes: ByteArray, destination: File): File {
        require(bytes.size <= MAX_BUNDLE_BYTES) { "Hot reload bundle exceeds 16 MiB" }
        val reader = Reader(bytes)
        require(reader.text(4) == "PNA1") { "Invalid hot reload bundle" }
        val count = reader.u32()
        require(count in 1..MAX_FILES) { "Invalid hot reload file count" }
        destination.deleteRecursively()
        check(destination.mkdirs()) { "Cannot create hot reload directory" }
        repeat(count) {
            val path = reader.text(reader.u16())
            require(isSafePath(path)) { "Unsafe hot reload path" }
            val contents = reader.bytes(reader.u32().also {
                require(it <= MAX_FILE_BYTES) { "Hot reload file is too large" }
            })
            val target = File(destination, path)
            require(target.canonicalPath.startsWith(destination.canonicalPath + File.separator)) {
                "Hot reload path escapes destination"
            }
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "${target.name}.tmp")
            temporary.writeBytes(contents)
            check(temporary.renameTo(target)) { "Cannot activate hot reload file" }
        }
        reader.finish()
        return File(destination, "index.php").also {
            require(it.isFile) { "Hot reload bundle does not contain index.php" }
        }
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

