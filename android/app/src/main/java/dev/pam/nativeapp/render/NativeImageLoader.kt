package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Shader
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import dev.pam.nativeapp.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal data class NativeImageRequest(
    val source: String,
    val defaultSource: String? = null,
    val loadingIndicatorSource: String? = null,
    val sourceSet: String? = null,
    val requestHeaders: String? = null,
    val fadeDurationMs: Int = 300,
    val resizeMethod: Int = IMAGE_RESIZE_AUTO,
    val resizeMultiplier: Float = 1f,
    val progressiveRenderingEnabled: Boolean = false,
    val cachePolicy: Int = IMAGE_CACHE_DEFAULT,
    val mediaCachePolicy: Int = MEDIA_CACHE_MEMORY_AND_DISK,
    val mediaCacheKey: String? = null,
    val mediaCacheMaxAgeMs: Long = 0,
    val mediaCacheMaxBytes: Long = 0,
    val mediaCacheChecksum: String? = null,
    val repeat: Boolean = false,
) {
    fun signature(): String = listOf(
        source,
        defaultSource.orEmpty(),
        loadingIndicatorSource.orEmpty(),
        sourceSet.orEmpty(),
        requestHeaders.orEmpty(),
        fadeDurationMs,
        resizeMethod,
        resizeMultiplier,
        progressiveRenderingEnabled,
        cachePolicy,
        mediaCachePolicy,
        mediaCacheKey.orEmpty(),
        mediaCacheMaxAgeMs,
        mediaCacheMaxBytes,
        mediaCacheChecksum.orEmpty(),
        repeat,
    ).joinToString("\u0000")
}

internal data class NativeImageResult(
    val source: String,
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val animatedBytes: ByteArray? = null,
)

internal class NativeImageCallbacks(
    val onStart: () -> Unit = {},
    val onProgress: (Long, Long) -> Unit = { _, _ -> },
    val onSuccess: (NativeImageResult) -> Unit = {},
    val onError: (String) -> Unit = {},
    val onEnd: () -> Unit = {},
    val onCacheHit: (Boolean, String) -> Unit = { _, _ -> },
    val onCacheMiss: (String) -> Unit = {},
    val onCacheReady: (String, Long) -> Unit = { _, _ -> },
)

internal fun resolvePamImageFile(root: File, source: String): File {
    val uri = URI(source)
    require(uri.scheme.equals("pam-file", ignoreCase = true)) {
        "Invalid sandbox image URI."
    }
    require(uri.authority.isNullOrEmpty()) {
        "Sandbox image URI cannot contain an authority."
    }
    val sandbox = root.canonicalFile
    val relative = uri.path.orEmpty().removePrefix("/")
    require(relative.isNotEmpty()) { "Sandbox image path is empty." }
    val candidate = File(sandbox, relative).canonicalFile
    require(candidate.path.startsWith(sandbox.path + File.separator)) {
        "Sandbox image path escapes the application sandbox."
    }
    require(candidate.isFile) { "Sandbox image does not exist." }
    return candidate
}

internal fun isInlineImageSource(source: String): Boolean =
    source.regionMatches(0, "data:image/", 0, "data:image/".length, ignoreCase = true)

internal class NativeImageLoader(
    private val context: Context,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "pam-image").apply { isDaemon = true }
    }
    // Tiny bundled/data-URI assets (for example icon masks) must never queue
    // behind remote photos, animated WebP decoding or disk reads. They are
    // part of the first interactive frame, so keep an isolated serial lane.
    private val inlineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pam-image-inline").apply { isDaemon = true }
    }
    private val cache = object : LruCache<String, DecodedBitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: DecodedBitmap): Int =
            value.bitmap.allocationByteCount + (value.animatedBytes?.size ?: 0)
    }
    private val inFlight =
        ConcurrentHashMap<String, CompletableFuture<NativeImageResult>>()
    private val active = WeakHashMap<PamImageView, ActiveRequest>()
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private val connections =
        ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val diskDirectory = File(context.cacheDir, DISK_DIRECTORY)
    private val diskLock = Any()

    fun load(
        request: NativeImageRequest,
        view: PamImageView,
        callbacks: NativeImageCallbacks,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed.get()) return

        val signature = request.signature()
        val current = active[view]
        if (
            shouldReuseImageRequest(
                sameSignature = current?.signature == signature,
                finished = current?.finished == true,
                hasDrawable = view.drawable != null,
            )
        ) {
            checkNotNull(current)
            current.callbacks = callbacks
            return
        }

        val token = generation.incrementAndGet()
        val pending = ActiveRequest(
            token = token,
            signature = signature,
            request = request,
            callbacks = callbacks,
        )
        active[view] = pending
        view.onImageSizeChanged = { width, height ->
            begin(view, token, width, height)
        }
        callbacks.onStart()
        // Keep already rendered pixels on screen while a changed request is
        // resolved. Reconciliation must never flash a blank/placeholder frame.
        if (view.drawable == null) {
            showPlaceholder(view, pending)
        }
        begin(view, token, view.width, view.height)
    }

    fun cancel(view: PamImageView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        active.remove(view)
        view.onImageSizeChanged = null
        view.setImageDrawable(null)
    }

    fun trimMemory(critical: Boolean) {
        synchronized(cache) {
            if (critical) {
                cache.evictAll()
            } else {
                cache.trimToSize(cache.maxSize() / 2)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        active.keys.forEach { view -> view.onImageSizeChanged = null }
        active.clear()
        connections.toList().forEach(HttpURLConnection::disconnect)
        connections.clear()
        inFlight.values.forEach { future -> future.cancel(true) }
        executor.shutdownNow()
        inlineExecutor.shutdownNow()
        inFlight.clear()
        synchronized(cache) { cache.evictAll() }
    }

    private fun begin(
        view: PamImageView,
        token: Long,
        measuredWidth: Int,
        measuredHeight: Int,
    ) {
        val pending = active[view]
            ?.takeIf { it.token == token && !it.finished }
            ?: return
        if (measuredWidth <= 0 || measuredHeight <= 0) return

        val source = resolveSource(
            pending.request.source,
            pending.request.sourceSet,
            context.resources.displayMetrics.density,
            measuredWidth,
        )
        if (source.isBlank()) {
            finishError(view, pending, "Image source is empty.")
            return
        }
        val key = decodedKey(
            source,
            pending.request,
            measuredWidth,
            measuredHeight,
        )
        if (pending.decodedKey == key) return
        pending.decodedKey = key

        if (
            pending.request.cachePolicy !in setOf(IMAGE_CACHE_RELOAD, IMAGE_CACHE_NONE) &&
            pending.request.mediaCachePolicy != MEDIA_CACHE_NONE &&
            pending.request.mediaCachePolicy != MEDIA_CACHE_DISK &&
            pending.request.mediaCachePolicy != MEDIA_CACHE_NETWORK_FIRST
        ) {
            synchronized(cache) { cache.get(key) }?.let { bitmap ->
                pending.callbacks.onCacheHit(false, cacheIdentity(source, pending.request))
                finishSuccess(
                    view,
                    pending,
                    NativeImageResult(
                        source,
                        bitmap.bitmap,
                        bitmap.width,
                        bitmap.height,
                        bitmap.animatedBytes,
                    ),
                    animate = false,
                )
                return
            }
        }

        val future = runCatching {
            inFlight.computeIfAbsent(key) {
                CompletableFuture.supplyAsync(
                    {
                        val bytes = loadBytes(
                            source = source,
                            request = pending.request,
                            progress = { loaded, total ->
                                main.post {
                                    dispatchProgress(key, loaded, total)
                                }
                            },
                            partial = partial@{ partialBytes ->
                                if (!pending.request.progressiveRenderingEnabled) {
                                    return@partial
                                }
                                val preview = runCatching {
                                    decode(
                                        partialBytes,
                                        measuredWidth,
                                        measuredHeight,
                                        pending.request.resizeMethod,
                                        pending.request.resizeMultiplier,
                                    )
                                }.getOrNull()?.bitmap ?: return@partial
                                main.post {
                                    displayPartial(key, preview)
                                }
                            },
                        )
                        val decoded = decode(
                            bytes,
                            measuredWidth,
                            measuredHeight,
                            pending.request.resizeMethod,
                            pending.request.resizeMultiplier,
                        )
                        if (
                            !closed.get() &&
                            pending.request.cachePolicy != IMAGE_CACHE_NONE &&
                            pending.request.mediaCachePolicy != MEDIA_CACHE_NONE &&
                            pending.request.mediaCachePolicy != MEDIA_CACHE_DISK
                        ) {
                            synchronized(cache) { cache.put(key, decoded) }
                        }
                        NativeImageResult(
                            source,
                            decoded.bitmap,
                            decoded.width,
                            decoded.height,
                            decoded.animatedBytes,
                        )
                    },
                    imageExecutor(source),
                ).whenComplete { _, _ -> inFlight.remove(key) }
            }
        }.getOrElse { error ->
            finishError(view, pending, safeError(error))
            return
        }

        future.whenComplete { decoded, error ->
            main.post {
                val latest = active[view]
                    ?.takeIf {
                        it.token == token &&
                            it.decodedKey == key &&
                            !it.finished
                    }
                    ?: return@post
                if (error != null || decoded == null) {
                    finishError(view, latest, safeError(error))
                } else {
                    finishSuccess(
                        view,
                        latest,
                        decoded,
                    )
                }
            }
        }
    }

    private fun showPlaceholder(
        view: PamImageView,
        pending: ActiveRequest,
    ) {
        val source = pending.request.loadingIndicatorSource
            ?: pending.request.defaultSource
            ?: return
        val token = pending.token
        CompletableFuture.supplyAsync(
            {
                runCatching {
                    val request = pending.request.copy(
                        source = source,
                        sourceSet = null,
                        requestHeaders = null,
                        cachePolicy = IMAGE_CACHE_DEFAULT,
                        resizeMethod = IMAGE_RESIZE_AUTO,
                    )
                    val bytes = loadBytes(
                        source = source,
                        request = request,
                        progress = { _, _ -> },
                    )
                    decode(
                        bytes,
                        PLACEHOLDER_EDGE,
                        PLACEHOLDER_EDGE,
                        IMAGE_RESIZE_AUTO,
                        1f,
                    ).bitmap
                }.getOrNull()
            },
            imageExecutor(source),
        ).whenComplete { bitmap, _ ->
            if (bitmap == null) return@whenComplete
            main.post {
                val latest = active[view]
                    ?.takeIf { it.token == token && !it.finished }
                    ?: return@post
                display(view, bitmap, latest.request.repeat, 0)
            }
        }
    }

    private fun imageExecutor(source: String) =
        if (isInlineImageSource(source)) inlineExecutor else executor

    private fun finishSuccess(
        view: PamImageView,
        pending: ActiveRequest,
        result: NativeImageResult,
        animate: Boolean = true,
    ) {
        if (active[view] !== pending || pending.finished) return
        pending.finished = true
        val animated = result.animatedBytes?.let { bytes ->
            displayAnimated(view, bytes)
        } ?: false
        if (!animated) {
            display(
                view,
                result.bitmap,
                pending.request.repeat,
                if (animate) pending.request.fadeDurationMs else 0,
            )
        }
        pending.callbacks.onSuccess(result)
        pending.callbacks.onEnd()
    }

    private fun finishError(
        view: PamImageView,
        pending: ActiveRequest,
        message: String,
    ) {
        if (active[view] !== pending || pending.finished) return
        if (
            shouldRetryImageRequest(
                attempts = pending.retryAttempts,
                attached = view.isAttachedToWindow,
            )
        ) {
            pending.retryAttempts++
            pending.decodedKey = null
            main.postDelayed(
                {
                    if (active[view] === pending && !pending.finished) {
                        begin(view, pending.token, view.width, view.height)
                    }
                },
                RETRY_BASE_DELAY_MS * pending.retryAttempts,
            )
            return
        }
        pending.finished = true
        pending.callbacks.onError(message)
        pending.callbacks.onEnd()
    }

    private fun dispatchProgress(
        key: String,
        loaded: Long,
        total: Long,
    ) {
        active.values
            .filter { request ->
                request.decodedKey == key && !request.finished
            }
            .forEach { request ->
                request.callbacks.onProgress(loaded, total)
            }
    }

    private fun displayPartial(key: String, bitmap: Bitmap) {
        active.entries
            .filter { (_, request) ->
                request.decodedKey == key && !request.finished
            }
            .forEach { (view, request) ->
                display(view, bitmap, request.request.repeat, 0)
            }
    }

    private fun display(
        view: PamImageView,
        bitmap: Bitmap,
        repeat: Boolean,
        fadeDurationMs: Int,
    ) {
        val next = BitmapDrawable(context.resources, bitmap).apply {
            if (repeat) {
                setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            }
        }
        val previous = view.drawable
        if (fadeDurationMs > 0 && previous != null) {
            val transition = TransitionDrawable(arrayOf(previous, next)).apply {
                isCrossFadeEnabled = true
            }
            view.setImageDrawable(transition)
            transition.startTransition(fadeDurationMs.coerceIn(0, 10_000))
        } else {
            view.setImageDrawable(next)
        }
    }

    private fun displayAnimated(view: PamImageView, bytes: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val drawable = runCatching {
            ImageDecoder.decodeDrawable(
                ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
            )
        }.getOrNull() as? AnimatedImageDrawable ?: return false
        (view.drawable as? AnimatedImageDrawable)?.stop()
        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        view.setImageDrawable(drawable)
        drawable.start()
        return true
    }

    private fun loadBytes(
        source: String,
        request: NativeImageRequest,
        progress: (Long, Long) -> Unit,
        partial: (ByteArray) -> Unit = {},
    ): ByteArray {
        val uri = URI(source)
        return when (uri.scheme?.lowercase()) {
            "https", "http" ->
                loadRemote(source, request, progress, partial)
            "data" -> loadDataUri(source)
            "content", "android.resource", "file" ->
                context.contentResolver.openInputStream(
                    android.net.Uri.parse(source),
                )?.use(::readBounded)
                    ?: error("Image source cannot be opened.")
            "pam-file" ->
                resolvePamImageFile(
                    File(context.filesDir, "pam-files"),
                    source,
                ).inputStream().use(::readBounded)
            "asset" -> context.assets.open(
                requireNotNull(normalizedPamAssetPath(source)) {
                    "Image asset path is invalid."
                },
            ).use(::readBounded)
            null -> context.assets.open(source.removePrefix("/"))
                .use(::readBounded)
            else -> error("Unsupported image URI scheme.")
        }
    }

    private fun loadRemote(
        source: String,
        request: NativeImageRequest,
        progress: (Long, Long) -> Unit,
        partial: (ByteArray) -> Unit,
    ): ByteArray {
        validateRemote(URI(source), null)
        val headers = parseHeaders(request.requestHeaders)
        val origin = URI(source)
        val cacheFile = diskFile(source, headers, request.mediaCacheKey)
        val identity = cacheIdentity(source, request)
        val diskEnabled = request.cachePolicy != IMAGE_CACHE_NONE &&
            request.mediaCachePolicy in setOf(
            MEDIA_CACHE_DISK,
            MEDIA_CACHE_MEMORY_AND_DISK,
            MEDIA_CACHE_CACHE_FIRST,
            MEDIA_CACHE_NETWORK_FIRST,
            MEDIA_CACHE_CACHE_ONLY,
            MEDIA_CACHE_STALE_WHILE_REVALIDATE,
        )
        val readDiskFirst = diskEnabled &&
            request.cachePolicy !in setOf(IMAGE_CACHE_RELOAD, IMAGE_CACHE_NONE) &&
            request.mediaCachePolicy != MEDIA_CACHE_NETWORK_FIRST
        if (readDiskFirst) {
            readDisk(cacheFile, request.mediaCacheMaxAgeMs)?.let {
                requestCallbacks(identity) { callbacks -> callbacks.onCacheHit(true, identity) }
                return it
            }
            if (request.mediaCachePolicy == MEDIA_CACHE_STALE_WHILE_REVALIDATE) {
                readDisk(cacheFile)?.let { stale ->
                    requestCallbacks(identity) { callbacks -> callbacks.onCacheHit(true, identity) }
                    executor.execute {
                        runCatching {
                            loadRemote(
                                source,
                                request.copy(mediaCachePolicy = MEDIA_CACHE_NETWORK_FIRST),
                                progress,
                                partial,
                            )
                        }
                    }
                    return stale
                }
            }
        }
        requestCallbacks(identity) { callbacks -> callbacks.onCacheMiss(identity) }
        if (
            request.cachePolicy == IMAGE_CACHE_ONLY_IF_CACHED ||
            request.mediaCachePolicy == MEDIA_CACHE_CACHE_ONLY
        ) {
            error("Image is not available in the local cache.")
        }

        return try {
            var current = source
            var previous: URI? = null
            repeat(MAX_REDIRECTS + 1) { redirect ->
            val uri = URI(current)
            validateRemote(uri, previous)
            val connection = URL(current).openConnection() as HttpURLConnection
            connections += connection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = request.cachePolicy != IMAGE_CACHE_NONE
            if (request.cachePolicy == IMAGE_CACHE_NONE) {
                connection.setRequestProperty("Cache-Control", "no-cache, no-store")
                connection.setRequestProperty("Pragma", "no-cache")
            }
            connection.setRequestProperty("Accept", "image/*")
            if (sameOrigin(origin, uri)) {
                headers.forEach(connection::setRequestProperty)
            }
            try {
                val status = connection.responseCode
                if (status in REDIRECT_STATUS) {
                    require(redirect < MAX_REDIRECTS) {
                        "Image request has too many redirects."
                    }
                    val location = connection.getHeaderField("Location")
                        ?: error("Image redirect has no location.")
                    previous = uri
                    current = uri.resolve(location).toString()
                    return@repeat
                }
                require(status in 200..299) {
                    "Image request failed with HTTP $status."
                }
                val contentType = connection.contentType.orEmpty()
                    .substringBefore(';')
                    .trim()
                    .lowercase()
                require(
                    contentType.isEmpty() ||
                        contentType.startsWith("image/") ||
                        contentType == "application/octet-stream",
                ) {
                    "Image response has an unsupported content type."
                }
                val length = connection.contentLengthLong
                require(length in -1..MAX_IMAGE_BYTES.toLong()) {
                    "Image is too large."
                }
                val bytes = connection.inputStream.use { input ->
                    readBounded(
                        input,
                        length,
                        progress,
                        if (
                            request.progressiveRenderingEnabled &&
                            contentType in JPEG_CONTENT_TYPES
                        ) {
                            partial
                        } else {
                            {}
                        },
                    )
                }
                if (request.mediaCacheChecksum != null) {
                    require(sha256(bytes) == request.mediaCacheChecksum) {
                        "Image checksum verification failed."
                    }
                }
                if (diskEnabled) {
                    writeDisk(
                        cacheFile,
                        bytes,
                        request.mediaCacheMaxBytes.takeIf { it > 0 } ?: DISK_CACHE_BYTES,
                    )
                    requestCallbacks(identity) { callbacks ->
                        callbacks.onCacheReady(identity, bytes.size.toLong())
                    }
                }
                    return bytes
                } finally {
                    connections -= connection
                    connection.disconnect()
                }
            }
            error("Image request could not be completed.")
        } catch (error: Throwable) {
            if (request.mediaCachePolicy == MEDIA_CACHE_NETWORK_FIRST) {
                readDisk(cacheFile)?.let {
                    requestCallbacks(identity) { callbacks -> callbacks.onCacheHit(true, identity) }
                    return it
                }
            }
            throw error
        }
    }

    private fun decode(
        bytes: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
        resizeMethod: Int,
        resizeMultiplier: Float,
    ): DecodedBitmap {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Unsupported image format."
        }
        val sourcePixels = bounds.outWidth.toLong() * bounds.outHeight
        require(
            resizeMethod != IMAGE_RESIZE_NONE ||
                sourcePixels <= MAX_DECODE_PIXELS,
        ) {
            "Full-resolution image exceeds the safe decode limit."
        }

        val multiplier = resizeMultiplier.coerceIn(0.1f, 8f)
        val desiredWidth = max(
            1,
            (targetWidth.coerceAtMost(MAX_DECODE_EDGE) * multiplier).toInt(),
        )
        val desiredHeight = max(
            1,
            (targetHeight.coerceAtMost(MAX_DECODE_EDGE) * multiplier).toInt(),
        )
        val shouldResize = when (resizeMethod) {
            IMAGE_RESIZE_RESIZE -> true
            IMAGE_RESIZE_SCALE, IMAGE_RESIZE_NONE -> false
            else -> bounds.outWidth > desiredWidth * 2 ||
                bounds.outHeight > desiredHeight * 2
        }
        var sample = 1
        if (shouldResize) {
            while (
                bounds.outWidth / (sample * 2) >= desiredWidth &&
                bounds.outHeight / (sample * 2) >= desiredHeight
            ) {
                sample *= 2
            }
        }
        while (
            bounds.outWidth.toLong() * bounds.outHeight /
            sample /
            sample > MAX_DECODE_PIXELS
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = requireNotNull(
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options),
        ) {
            "Unsupported image format."
        }
        return DecodedBitmap(
            bitmap,
            bounds.outWidth,
            bounds.outHeight,
            bytes.takeIf(::isAnimatedImage),
        )
    }

    private fun isAnimatedImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (
            bytes[0] == 'G'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte()
        ) {
            return true
        }
        if (
            bytes[0] == 'R'.code.toByte() &&
            bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() &&
            bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() &&
            bytes[11] == 'P'.code.toByte()
        ) {
            for (index in 12..bytes.size - 4) {
                if (
                    bytes[index] == 'A'.code.toByte() &&
                    bytes[index + 1] == 'N'.code.toByte() &&
                    bytes[index + 2] == 'I'.code.toByte() &&
                    bytes[index + 3] == 'M'.code.toByte()
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun readBounded(
        input: java.io.InputStream,
        expected: Long = -1,
        progress: (Long, Long) -> Unit = { _, _ -> },
        partial: (ByteArray) -> Unit = {},
    ): ByteArray {
        require(expected in -1..MAX_IMAGE_BYTES.toLong()) {
            "Image is too large."
        }
        val initialCapacity = when {
            expected > 0 -> expected.toInt()
            else -> DEFAULT_IMAGE_CAPACITY
        }
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var lastProgress = 0L
        var nextPartial = PROGRESSIVE_STEP_BYTES
        var partialCount = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_IMAGE_BYTES) { "Image is too large." }
            output.write(buffer, 0, read)
            if (
                total >= nextPartial &&
                partialCount < MAX_PROGRESSIVE_PREVIEWS
            ) {
                partial(output.toByteArray())
                partialCount++
                nextPartial *= 2
            }
            if (
                total - lastProgress >= PROGRESS_STEP_BYTES ||
                (expected > 0 && total == expected)
            ) {
                lastProgress = total
                progress(total, expected.coerceAtLeast(0))
            }
        }
        if (total > lastProgress) {
            progress(total, expected.coerceAtLeast(total))
        }
        return output.toByteArray()
    }

    private fun loadDataUri(source: String): ByteArray {
        val separator = source.indexOf(',')
        require(separator > 5) { "Image data URI is malformed." }
        val metadata = source.substring(5, separator)
        require(metadata.substringBefore(';').startsWith("image/")) {
            "Data URI is not an image."
        }
        val encoded = source.substring(separator + 1)
        val bytes = if (metadata.endsWith(";base64")) {
            Base64.decode(encoded, Base64.DEFAULT)
        } else {
            URLDecoder.decode(encoded, Charsets.UTF_8.name())
                .toByteArray(Charsets.UTF_8)
        }
        require(bytes.size <= MAX_IMAGE_BYTES) { "Image is too large." }
        return bytes
    }

    private fun readDisk(file: File, maxAgeMs: Long = 0): ByteArray? = synchronized(diskLock) {
        if (!file.isFile || file.length() !in 1..MAX_IMAGE_BYTES.toLong()) {
            return@synchronized null
        }
        if (maxAgeMs > 0 && System.currentTimeMillis() - file.lastModified() > maxAgeMs) {
            return@synchronized null
        }
        runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.inputStream().use(::readBounded)
        }.getOrNull()
    }

    private fun writeDisk(file: File, bytes: ByteArray, limit: Long = DISK_CACHE_BYTES) {
        if (closed.get()) return
        synchronized(diskLock) {
            runCatching {
                diskDirectory.mkdirs()
                val temporary = File(diskDirectory, "${file.name}.tmp")
                temporary.outputStream().use { output -> output.write(bytes) }
                if (!temporary.renameTo(file)) {
                    file.outputStream().use { output -> output.write(bytes) }
                    temporary.delete()
                }
                trimDisk(limit.coerceIn(8L * 1024 * 1024, MAX_DISK_CACHE_BYTES))
            }
        }
    }

    private fun trimDisk(limit: Long = DISK_CACHE_BYTES) {
        val files = diskDirectory.listFiles()
            ?.filter(File::isFile)
            ?.sortedByDescending(File::lastModified)
            ?: return
        var size = 0L
        files.forEach { file ->
            size += file.length()
            if (size > limit) file.delete()
        }
    }

    private fun diskFile(
        source: String,
        headers: Map<String, String>,
        stableKey: String? = null,
    ): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(
                buildString {
                    append(stableKey ?: source)
                    headers.toSortedMap().forEach { (name, value) ->
                        append('\u0000').append(name).append(':').append(value)
                    }
                }.toByteArray(Charsets.UTF_8),
            )
            .joinToString("") { byte -> "%02x".format(byte) }
        return File(diskDirectory, "$digest.image")
    }

    private fun cacheIdentity(source: String, request: NativeImageRequest): String =
        request.mediaCacheKey ?: sha256(source.toByteArray())

    private fun requestCallbacks(
        identity: String,
        callback: (NativeImageCallbacks) -> Unit,
    ) {
        active.values
            .filter { request ->
                cacheIdentity(request.request.source, request.request) == identity &&
                    !request.finished
            }
            .forEach { request -> callback(request.callbacks) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun decodedKey(
        source: String,
        request: NativeImageRequest,
        width: Int,
        height: Int,
    ): String {
        val widthBucket = ((width + TARGET_BUCKET - 1) / TARGET_BUCKET)
            .coerceAtLeast(1)
        val heightBucket = ((height + TARGET_BUCKET - 1) / TARGET_BUCKET)
            .coerceAtLeast(1)
        return listOf(
            source,
            request.requestHeaders.orEmpty(),
            widthBucket,
            heightBucket,
            request.resizeMethod,
            request.resizeMultiplier,
        ).joinToString("\u0000")
    }

    private fun resolveSource(
        fallback: String,
        sourceSet: String?,
        density: Float,
        width: Int,
    ): String {
        val candidates = sourceSet
            ?.split(',')
            ?.mapNotNull { raw ->
                val value = raw.trim()
                val separator = value.lastIndexOf(' ')
                if (separator <= 0) return@mapNotNull null
                val source = value.substring(0, separator).trim()
                val descriptor = value.substring(separator + 1).trim()
                val score = when {
                    descriptor.endsWith('x') ->
                        descriptor.dropLast(1).toFloatOrNull()
                            ?.let { kotlin.math.abs(it - density) }
                    descriptor.endsWith('w') ->
                        descriptor.dropLast(1).toFloatOrNull()
                            ?.let { kotlin.math.abs(it - width) / max(1, width) }
                    else -> null
                } ?: return@mapNotNull null
                source to score
            }
            .orEmpty()
        return candidates.minByOrNull { it.second }?.first ?: fallback
    }

    private fun parseHeaders(packed: String?): Map<String, String> {
        if (packed.isNullOrBlank()) return emptyMap()
        return packed.lineSequence()
            .take(MAX_HEADERS)
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) return@mapNotNull null
                val name = line.substring(0, separator)
                val value = line.substring(separator + 1)
                if (!HEADER_NAME.matches(name) || value.length > MAX_HEADER_BYTES) {
                    return@mapNotNull null
                }
                name to value
            }
            .toMap()
    }

    private fun validateRemote(uri: URI, previous: URI?) {
        val scheme = uri.scheme?.lowercase()
        require(
            scheme == "https" || (BuildConfig.DEBUG && scheme == "http"),
        ) {
            "Remote images require HTTPS."
        }
        require(
            previous?.scheme?.lowercase() != "https" || scheme == "https",
        ) {
            "Image redirects cannot downgrade HTTPS."
        }
        require(!uri.host.isNullOrBlank()) { "Image URL has no host." }
    }

    private fun sameOrigin(first: URI, second: URI): Boolean =
        first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun safeError(error: Throwable?): String {
        var cause = error
        while (cause?.cause != null && cause.cause !== cause) {
            cause = cause.cause
        }
        return cause?.message
            ?.take(MAX_ERROR_BYTES)
            ?.takeIf(String::isNotBlank)
            ?: "Image request failed."
    }

    private data class ActiveRequest(
        val token: Long,
        val signature: String,
        val request: NativeImageRequest,
        var callbacks: NativeImageCallbacks,
        var decodedKey: String? = null,
        var finished: Boolean = false,
        var retryAttempts: Int = 0,
    )

    private data class DecodedBitmap(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val animatedBytes: ByteArray? = null,
    )

    private companion object {
        const val MEMORY_CACHE_BYTES = 32 * 1024 * 1024
        const val DISK_CACHE_BYTES = 96L * 1024 * 1024
        const val MAX_DISK_CACHE_BYTES = 2L * 1024 * 1024 * 1024
        const val DISK_DIRECTORY = "pam-images-v1"
        const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        const val DEFAULT_IMAGE_CAPACITY = 64 * 1024
        const val BUFFER_BYTES = 16 * 1024
        const val PROGRESS_STEP_BYTES = 64 * 1024L
        const val PROGRESSIVE_STEP_BYTES = 256 * 1024L
        const val MAX_PROGRESSIVE_PREVIEWS = 4
        const val MAX_DECODE_EDGE = 4096
        const val MAX_DECODE_PIXELS = 33_554_432L
        const val PLACEHOLDER_EDGE = 512
        const val TARGET_BUCKET = 64
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_REDIRECTS = 5
        const val MAX_HEADERS = 32
        const val MAX_HEADER_BYTES = 4_096
        const val MAX_ERROR_BYTES = 512
        const val RETRY_BASE_DELAY_MS = 32L
        val HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$")
        val REDIRECT_STATUS = setOf(301, 302, 303, 307, 308)
        val JPEG_CONTENT_TYPES = setOf("image/jpeg", "image/jpg")
    }
}

internal fun shouldReuseImageRequest(
    sameSignature: Boolean,
    finished: Boolean,
    hasDrawable: Boolean,
): Boolean = sameSignature && (!finished || hasDrawable)

internal fun shouldRetryImageRequest(
    attempts: Int,
    attached: Boolean,
): Boolean = attached && attempts < 2

internal const val IMAGE_RESIZE_AUTO = 1
internal const val IMAGE_RESIZE_RESIZE = 2
internal const val IMAGE_RESIZE_SCALE = 3
internal const val IMAGE_RESIZE_NONE = 4
internal const val IMAGE_CACHE_DEFAULT = 1
internal const val IMAGE_CACHE_RELOAD = 2
internal const val IMAGE_CACHE_ONLY_IF_CACHED = 4
internal const val IMAGE_CACHE_NONE = 5
internal const val MEDIA_CACHE_NONE = 1
internal const val MEDIA_CACHE_MEMORY = 2
internal const val MEDIA_CACHE_DISK = 3
internal const val MEDIA_CACHE_MEMORY_AND_DISK = 4
internal const val MEDIA_CACHE_CACHE_FIRST = 5
internal const val MEDIA_CACHE_NETWORK_FIRST = 6
internal const val MEDIA_CACHE_CACHE_ONLY = 7
internal const val MEDIA_CACHE_STALE_WHILE_REVALIDATE = 8
