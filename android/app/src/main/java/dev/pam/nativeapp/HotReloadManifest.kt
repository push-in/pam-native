package dev.pam.nativeapp

import java.security.MessageDigest

/** One development bundle file: lowercase SHA-256, size and safe relative path. */
internal data class HotReloadFile(val path: String, val sha256: String, val size: Long)

/**
 * The `pam dev` file manifest (`PNM1`). Its version is the SHA-256 of the
 * canonical text, so a delta applied on the device is verified end to end.
 */
internal class HotReloadManifest private constructor(
    val version: String,
    val files: Map<String, HotReloadFile>,
) {
    val totalBytes: Long get() = files.values.sumOf(HotReloadFile::size)

    fun text(): String = canonicalText(files)

    companion object {
        const val MAGIC = "PNM1\n"
        private const val DELTA_MAGIC = "PNMD1 "
        private const val MAX_FILES = 10_000
        private const val MAX_FILE_BYTES = 64L * 1024 * 1024
        private val DIGEST = Regex("[a-f0-9]{64}")
        private val VERSION = Regex("[a-f0-9]{16,64}")

        fun isVersion(value: String): Boolean = value.matches(VERSION)

        /**
         * Parses a full manifest or a delta against [base] and verifies that
         * the result hashes to [expectedVersion].
         */
        fun parse(body: String, expectedVersion: String, base: HotReloadManifest?): HotReloadManifest {
            require(isVersion(expectedVersion)) { "Invalid hot reload version" }
            val files = if (body.startsWith(DELTA_MAGIC)) {
                val header = body.substringBefore('\n')
                val baseVersion = header.removePrefix(DELTA_MAGIC)
                require(base != null && base.version == baseVersion) { "Hot reload delta does not match the applied manifest" }
                val next = LinkedHashMap(base.files)
                body.substringAfter('\n', "").lineSequence().filter(String::isNotEmpty).forEach { line ->
                    when {
                        line.startsWith("+ ") -> parseLine(line.substring(2)).let { next[it.path] = it }
                        line.startsWith("- ") -> {
                            val path = line.substring(2)
                            require(isSafeBundlePath(path)) { "Unsafe hot reload path" }
                            next.remove(path)
                        }
                        else -> throw IllegalArgumentException("Invalid hot reload delta")
                    }
                }
                next
            } else {
                require(body.startsWith(MAGIC)) { "Invalid hot reload manifest" }
                val parsed = LinkedHashMap<String, HotReloadFile>()
                body.substring(MAGIC.length).lineSequence().filter(String::isNotEmpty).forEach { line ->
                    val file = parseLine(line)
                    require(parsed.put(file.path, file) == null) { "Duplicate hot reload path" }
                }
                parsed
            }
            require(files.size in 1..MAX_FILES) { "Invalid hot reload file count" }
            require("index.php" in files) { "Hot reload manifest does not contain index.php" }
            val digest = sha256Hex(canonicalText(files).toByteArray(Charsets.UTF_8))
            require(digest == expectedVersion) { "Hot reload manifest failed verification" }
            return HotReloadManifest(expectedVersion, files.toSortedMap())
        }

        private fun parseLine(line: String): HotReloadFile {
            val first = line.indexOf(' ')
            val second = line.indexOf(' ', first + 1)
            require(first == 64 && second > first + 1) { "Invalid hot reload manifest line" }
            val sha256 = line.substring(0, first)
            val size = line.substring(first + 1, second).toLongOrNull()
            val path = line.substring(second + 1)
            require(sha256.matches(DIGEST)) { "Invalid hot reload digest" }
            require(size != null && size in 0..MAX_FILE_BYTES) { "Invalid hot reload file size" }
            require(isSafeBundlePath(path)) { "Unsafe hot reload path" }
            return HotReloadFile(path, sha256, size)
        }

        /** Byte-wise path order, the order the CLI writes (paths are ASCII). */
        private fun canonicalText(files: Map<String, HotReloadFile>): String {
            val builder = StringBuilder(MAGIC.length + files.size * 112)
            builder.append(MAGIC)
            files.keys.sorted().forEach { path ->
                val file = files.getValue(path)
                builder.append(file.sha256).append(' ').append(file.size).append(' ').append(path).append('\n')
            }
            return builder.toString()
        }

        fun sha256Hex(bytes: ByteArray): String =
            hexDigest(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
