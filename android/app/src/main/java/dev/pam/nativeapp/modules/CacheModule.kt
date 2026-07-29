package dev.pam.nativeapp.modules

import android.content.Context
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal class CacheModule(context: Context) : NativeModule, AutoCloseable {
    private val roots = CacheRoots(
        images = File(context.cacheDir, "pam-images-v1"),
        media = File(context.cacheDir, "pam-media-v1"),
        temporary = File(context.cacheDir, "pam-incoming-shares"),
    )
    private val closed = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pam-cache").apply { isDaemon = true }
    }

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        if (closed.get()) {
            completion.failure("Cache module is closed")
            return
        }
        try {
            executor.execute {
                runCatching {
                    when (method) {
                        "usage" -> usagePayload(roots.usage())
                        "clear" -> {
                            val values = WireMap.decode(payload)
                            val preserveOffline =
                                (values["preserveOffline"] as? WireValue.Flag)?.value ?: true
                            val before = roots.usage()
                            roots.clear(preserveOffline)
                            val after = roots.usage()
                            usagePayload(after, (before.totalBytes - after.totalBytes).coerceAtLeast(0))
                        }
                        else -> error("Unknown cache method $method")
                    }
                }.fold(
                    onSuccess = { completion.complete(ModuleResultStatus.SUCCESS, it) },
                    onFailure = { completion.failure(it.message ?: "Cache operation failed") },
                )
            }
        } catch (_: RejectedExecutionException) {
            completion.failure("Cache module is closed")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdownNow()
    }

    private fun usagePayload(usage: CacheUsage, freedBytes: Long = 0): ByteArray =
        WireMap.encode(
            mapOf(
                "fileCount" to WireValue.Integer(usage.fileCount),
                "freedBytes" to WireValue.Integer(freedBytes),
                "imageBytes" to WireValue.Integer(usage.imageBytes),
                "mediaBytes" to WireValue.Integer(usage.mediaBytes),
                "temporaryBytes" to WireValue.Integer(usage.temporaryBytes),
                "totalBytes" to WireValue.Integer(usage.totalBytes),
            ),
        )

    private fun ModuleCompletion.failure(message: String) {
        complete(ModuleResultStatus.FAILURE, message.toByteArray())
    }
}

internal data class CacheRoots(
    val images: File,
    val media: File,
    val temporary: File,
) {
    fun usage(): CacheUsage {
        val imageUsage = images.usage()
        val mediaUsage = media.usage()
        val temporaryUsage = temporary.usage()
        return CacheUsage(
            fileCount = imageUsage.second + mediaUsage.second + temporaryUsage.second,
            imageBytes = imageUsage.first,
            mediaBytes = mediaUsage.first,
            temporaryBytes = temporaryUsage.first,
        )
    }

    fun clear(preserveOffline: Boolean) {
        images.deleteContents()
        temporary.deleteContents()
        val pinnedNames = media.listFiles().orEmpty()
            .asSequence()
            .filter { it.extension == "media" }
            .filter { File(media, "${it.nameWithoutExtension}.pin").isFile }
            .map { it.nameWithoutExtension }
            .toSet()
        media.listFiles().orEmpty().forEach { file ->
            val pinned = file.nameWithoutExtension in pinnedNames &&
                file.extension in setOf("media", "pin")
            if (!preserveOffline || !pinned) {
                file.deleteRecursively()
            }
        }
    }
}

internal data class CacheUsage(
    val fileCount: Long,
    val imageBytes: Long,
    val mediaBytes: Long,
    val temporaryBytes: Long,
) {
    val totalBytes: Long
        get() = imageBytes + mediaBytes + temporaryBytes
}

private fun File.usage(): Pair<Long, Long> {
    if (!exists()) return 0L to 0L
    var bytes = 0L
    var files = 0L
    walkTopDown().forEach { entry ->
        if (entry.isFile) {
            bytes += entry.length().coerceAtLeast(0)
            files++
        }
    }
    return bytes to files
}

private fun File.deleteContents() {
    listFiles().orEmpty().forEach(File::deleteRecursively)
}
