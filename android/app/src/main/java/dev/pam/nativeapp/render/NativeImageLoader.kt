package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
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
    /**
     * Identity of a request. Every field is an immutable value, so the data
     * class equality is the signature; no string is joined per bind.
     */
    fun signature(): NativeImageRequest = this
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

/** Largest cell edge (px) served from the MediaStore thumbnail cache; bigger views decode the file. */
internal const val MEDIA_STORE_THUMBNAIL_MAX_EDGE = 640

internal fun isMediaStoreThumbnailCandidate(
    source: String,
    resizeMethod: Int,
    targetWidth: Int,
    targetHeight: Int,
): Boolean {
    if (resizeMethod == IMAGE_RESIZE_NONE || resizeMethod == IMAGE_RESIZE_SCALE) return false
    if (targetWidth <= 0 || targetHeight <= 0) return false
    if (max(targetWidth, targetHeight) > MEDIA_STORE_THUMBNAIL_MAX_EDGE) return false
    val uri = runCatching { URI(source) }.getOrNull() ?: return false
    return uri.scheme.equals("content", ignoreCase = true) &&
        uri.authority.equals("media", ignoreCase = true) &&
        mediaStoreKind(source) != MEDIA_STORE_OTHER
}

internal const val MEDIA_STORE_OTHER = 0
internal const val MEDIA_STORE_IMAGE = 1
internal const val MEDIA_STORE_VIDEO = 2
internal const val MEDIA_STORE_FILE = 3

/** Which MediaStore collection a `content://media/<volume>/...` URI names. */
internal fun mediaStoreKind(source: String): Int {
    val path = runCatching { URI(source).path }.getOrNull().orEmpty()
    return when {
        path.contains("/images/media/") -> MEDIA_STORE_IMAGE
        path.contains("/video/media/") -> MEDIA_STORE_VIDEO
        path.contains("/file/") -> MEDIA_STORE_FILE
        else -> MEDIA_STORE_OTHER
    }
}

/**
 * The platform thumbnail fits inside the requested box; a cell crops its image
 * (cover). A square box whose edge is the cell's longer side times the item's
 * aspect ratio yields a thumbnail whose shorter side still covers the cell,
 * whatever the stored rotation of the item. Unknown sizes request the cell.
 */
internal fun thumbnailRequestSize(
    itemWidth: Int,
    itemHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): android.util.Size {
    if (itemWidth <= 0 || itemHeight <= 0) return android.util.Size(targetWidth, targetHeight)
    val ratio = max(itemWidth, itemHeight).toFloat() / kotlin.math.min(itemWidth, itemHeight)
    val edge = kotlin.math.ceil(max(targetWidth, targetHeight) * ratio).toInt()
        .coerceAtMost(MEDIA_STORE_THUMBNAIL_MAX_EDGE * 2)
    return android.util.Size(edge, edge)
}

/**
 * Downscales a platform thumbnail to cover the cell exactly (the image view
 * crops it); keeps it when it is already at most the cell size.
 */
internal fun coverScaledThumbnail(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
    val scale = max(
        targetWidth.toFloat() / bitmap.width.coerceAtLeast(1),
        targetHeight.toFloat() / bitmap.height.coerceAtLeast(1),
    )
    if (scale >= 1f) return bitmap
    val width = (bitmap.width * scale).toInt().coerceAtLeast(targetWidth)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(targetHeight)
    if (width >= bitmap.width && height >= bitmap.height) return bitmap
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

internal fun isInlineImageSource(source: String): Boolean =
    source.regionMatches(0, "data:image/", 0, "data:image/".length, ignoreCase = true)

/** Inline sources this small (icon masks) decode on the UI thread, in the frame. */
internal const val INLINE_SYNC_MAX_CHARS = 16 * 1024

internal fun isSynchronousInlineSource(source: String): Boolean =
    source.length <= INLINE_SYNC_MAX_CHARS && isInlineImageSource(source)

/**
 * One shared load per decoded key. The future is registered before its work
 * starts and leaves the map by identity when it completes, on whichever
 * thread completes it. A load that finished before its caller returned used
 * to remove itself inside ConcurrentHashMap.computeIfAbsent ("Recursive
 * update"): the failed future stayed registered and failed every later load
 * of that key once its bitmap left the memory cache.
 */
internal class InFlightImageLoads<T> {
    private val loads = ConcurrentHashMap<String, CompletableFuture<T>>()

    fun share(key: String, start: () -> CompletableFuture<T>): CompletableFuture<T> {
        loads[key]?.let { return it }
        val created = CompletableFuture<T>()
        loads.putIfAbsent(key, created)?.let { return it }
        created.whenComplete { _, _ -> loads.remove(key, created) }
        try {
            start().whenComplete { value, error ->
                if (error != null) created.completeExceptionally(error) else created.complete(value)
            }
        } catch (error: Throwable) {
            created.completeExceptionally(error)
        }
        return created
    }

    fun cancelAll() {
        loads.values.forEach { future -> future.cancel(true) }
        loads.clear()
    }

    val size: Int get() = loads.size
}

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
    private val cache = object : LruCache<String, DecodedBitmap>(memoryCacheBytes(context)) {
        override fun sizeOf(key: String, value: DecodedBitmap): Int =
            value.bitmap.allocationByteCount + (value.animatedBytes?.size ?: 0)
    }
    // Inline icon masks live apart from photos: a feed or an inbox of
    // avatars must never evict the glyphs of the next screen's first frame.
    private val inlineCache = object : LruCache<String, DecodedBitmap>(INLINE_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: DecodedBitmap): Int =
            value.bitmap.allocationByteCount + (value.animatedBytes?.size ?: 0)
    }
    private val inFlight = InFlightImageLoads<NativeImageResult>()
    private val active = WeakHashMap<PamImageView, ActiveRequest>()
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private val connections =
        ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val diskDirectory = File(context.cacheDir, DISK_DIRECTORY)
    private val diskLock = DISK_LOCK

    fun load(
        request: NativeImageRequest,
        view: PamImageView,
        callbacks: NativeImageCallbacks,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed.get()) return

        val signature = request.signature()
        val current = active[view]
        if (current?.released == true && current.signature == signature) {
            // Offscreen and released: the same request re-binds silently;
            // pixels come back when the view shows again.
            current.callbacks = callbacks
            return
        }
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
        view.onShownChanged = { shown -> onShownChanged(view, shown) }
        callbacks.onStart()
        // Keep already rendered pixels on screen while a changed request is
        // resolved. Reconciliation must never flash a blank/placeholder frame.
        if (view.drawable == null) {
            showPlaceholder(view, pending)
        }
        begin(view, token, view.width, view.height)
    }

    /**
     * Warms the shared disk cache for a remote image without decoding it.
     * Returns the cached byte count. Used by Image::prefetch().
     */
    fun prefetch(source: String, requestHeaders: String? = null, cacheKey: String? = null): Long {
        check(!closed.get()) { "Image loader is closed" }
        val request = NativeImageRequest(
            source = source,
            requestHeaders = requestHeaders,
            mediaCacheKey = cacheKey,
        )
        return loadRemote(source, request, { _, _ -> }, {}).size.toLong()
    }

    fun cancel(view: PamImageView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        active.remove(view)
        view.onImageSizeChanged = null
        view.onShownChanged = null
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
        // Inline glyphs are tiny and decode again synchronously; only a
        // critical trim drops them.
        if (critical) synchronized(inlineCache) { inlineCache.evictAll() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        active.keys.forEach { view -> view.onImageSizeChanged = null }
        active.clear()
        connections.toList().forEach(HttpURLConnection::disconnect)
        connections.clear()
        inFlight.cancelAll()
        executor.shutdownNow()
        inlineExecutor.shutdownNow()
        synchronized(cache) { cache.evictAll() }
        synchronized(inlineCache) { inlineCache.evictAll() }
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
        val memory = memoryCacheFor(source)

        if (
            pending.request.cachePolicy !in setOf(IMAGE_CACHE_RELOAD, IMAGE_CACHE_NONE) &&
            pending.request.mediaCachePolicy != MEDIA_CACHE_NONE &&
            pending.request.mediaCachePolicy != MEDIA_CACHE_DISK &&
            pending.request.mediaCachePolicy != MEDIA_CACHE_NETWORK_FIRST
        ) {
            synchronized(memory) { memory.get(key) }?.let { bitmap ->
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

        if (isSynchronousInlineSource(source)) {
            // A bundled glyph (tens of bytes to a few KiB) decodes in well
            // under a millisecond: paint it in the frame that lays the view
            // out, like a font glyph, instead of a frame later.
            val decoded = runCatching {
                decode(
                    loadDataUri(source),
                    measuredWidth,
                    measuredHeight,
                    pending.request.resizeMethod,
                    pending.request.resizeMultiplier,
                    repeat = pending.request.repeat,
                    inline = true,
                )
            }.getOrElse { error ->
                finishError(view, pending, safeError(error))
                return
            }
            if (
                pending.request.cachePolicy != IMAGE_CACHE_NONE &&
                pending.request.mediaCachePolicy != MEDIA_CACHE_NONE &&
                pending.request.mediaCachePolicy != MEDIA_CACHE_DISK
            ) {
                synchronized(memory) { memory.put(key, decoded) }
            }
            finishSuccess(
                view,
                pending,
                NativeImageResult(
                    source,
                    decoded.bitmap,
                    decoded.width,
                    decoded.height,
                    decoded.animatedBytes,
                ),
                animate = false,
            )
            return
        }

        val future = runCatching {
            inFlight.share(key) {
                CompletableFuture.supplyAsync(
                    supply@{
                        mediaStoreThumbnail(
                            source,
                            pending.request,
                            measuredWidth,
                            measuredHeight,
                        )?.let { thumbnail ->
                            if (
                                !closed.get() &&
                                pending.request.cachePolicy != IMAGE_CACHE_NONE &&
                                pending.request.mediaCachePolicy != MEDIA_CACHE_NONE &&
                                pending.request.mediaCachePolicy != MEDIA_CACHE_DISK
                            ) {
                                synchronized(memory) { memory.put(key, thumbnail) }
                            }
                            return@supply NativeImageResult(
                                source,
                                thumbnail.bitmap,
                                thumbnail.width,
                                thumbnail.height,
                            )
                        }
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
                                        repeat = pending.request.repeat,
                                        inline = isInlineImageSource(source),
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
                            repeat = pending.request.repeat,
                            inline = isInlineImageSource(source),
                        )
                        if (
                            !closed.get() &&
                            pending.request.cachePolicy != IMAGE_CACHE_NONE &&
                            pending.request.mediaCachePolicy != MEDIA_CACHE_NONE &&
                            pending.request.mediaCachePolicy != MEDIA_CACHE_DISK
                        ) {
                            synchronized(memory) { memory.put(key, decoded) }
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
                )
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
                        repeat = pending.request.repeat,
                        inline = isInlineImageSource(source),
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

    /**
     * Releases the pixels of an image hidden by an ancestor (a covered route,
     * an inactive tab or pager page) or detached (a recycled cell): the view
     * no longer pins its bitmap, so the memory cache's LRU bound really
     * bounds decoded images. Showing again restores it in the same frame from
     * the memory cache, or decodes it again silently (no repeated load
     * events) when it was evicted meanwhile. Animated, inline and
     * non-memory-cacheable requests are kept.
     */
    private fun onShownChanged(view: PamImageView, shown: Boolean) {
        if (closed.get()) return
        val pending = active[view] ?: return
        if (!shown) {
            if (
                pending.released ||
                !pending.finished ||
                pending.animated ||
                !isReleasable(pending.request) ||
                view.drawable == null
            ) {
                return
            }
            pending.released = true
            view.setImageDrawable(null)
            return
        }
        if (!pending.released) return
        pending.released = false
        val cached = pending.decodedKey?.let { key -> synchronized(cache) { cache.get(key) } }
        if (cached != null) {
            display(view, cached.bitmap, pending.request.repeat, 0)
            return
        }
        pending.restoringCallbacks = pending.callbacks
        pending.callbacks = NativeImageCallbacks()
        pending.finished = false
        pending.decodedKey = null
        begin(view, pending.token, view.width, view.height)
    }

    private fun isReleasable(request: NativeImageRequest): Boolean =
        !isInlineImageSource(request.source) &&
            request.cachePolicy != IMAGE_CACHE_RELOAD &&
            request.cachePolicy != IMAGE_CACHE_NONE &&
            request.mediaCachePolicy != MEDIA_CACHE_NONE &&
            request.mediaCachePolicy != MEDIA_CACHE_DISK &&
            request.mediaCachePolicy != MEDIA_CACHE_NETWORK_FIRST

    private fun endRestore(pending: ActiveRequest) {
        pending.restoringCallbacks?.let { original ->
            pending.callbacks = original
            pending.restoringCallbacks = null
        }
    }

    private fun imageExecutor(source: String) =
        if (isInlineImageSource(source)) inlineExecutor else executor

    private fun memoryCacheFor(source: String) =
        if (isInlineImageSource(source)) inlineCache else cache

    private fun finishSuccess(
        view: PamImageView,
        pending: ActiveRequest,
        result: NativeImageResult,
        animate: Boolean = true,
    ) {
        if (active[view] !== pending || pending.finished) return
        pending.finished = true
        pending.animated = result.animatedBytes != null
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
        endRestore(pending)
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
        endRestore(pending)
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
        val next = view.bitmapDrawable(bitmap, repeat)
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

    /**
     * Device gallery cells (MediaStore `content://media/...` photos and
     * videos, including the Files collection the media library pages) come
     * from the platform thumbnail cache at the cell size instead of reading
     * and decoding the whole file: a 4-column grid otherwise reads megabytes
     * per photo (and up to 16 MiB of every video, which then fails to decode)
     * into the heap while scrolling. Videos get a frame. Returns null for any
     * other source, a full-size or unresized request, or when the platform has
     * no thumbnail, so the regular byte decode runs.
     */
    private fun mediaStoreThumbnail(
        source: String,
        request: NativeImageRequest,
        targetWidth: Int,
        targetHeight: Int,
    ): DecodedBitmap? {
        if (!isMediaStoreThumbnailCandidate(source, request.resizeMethod, targetWidth, targetHeight)) {
            return null
        }
        val item = mediaStoreItem(android.net.Uri.parse(source)) ?: return null
        val bitmap = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.loadThumbnail(
                    item.uri,
                    thumbnailRequestSize(item.width, item.height, targetWidth, targetHeight),
                    null,
                )
            } else if (item.video) {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, item.uri)
                    retriever.getFrameAtTime(0)
                } finally {
                    retriever.release()
                }
            } else {
                null
            }
        }.getOrNull() ?: return null
        val fitted = coverScaledThumbnail(bitmap, targetWidth, targetHeight).let { scaled ->
            // Same storage as a regular decode: GPU-only on API 28+.
            if (
                nativeBitmapStorage(
                    sdk = Build.VERSION.SDK_INT,
                    inline = false,
                    allowHardware = true,
                    pixels = scaled.width.toLong() * scaled.height,
                    mimeType = null,
                ) == NativeBitmapStorage.HARDWARE
            ) {
                runCatching { scaled.copy(Bitmap.Config.HARDWARE, false) }.getOrNull()
                    ?.also { scaled.recycle() }
                    ?: scaled.also(Bitmap::prepareToDraw)
            } else {
                scaled.also(Bitmap::prepareToDraw)
            }
        }
        return DecodedBitmap(
            fitted,
            item.width.takeIf { it > 0 } ?: bitmap.width,
            item.height.takeIf { it > 0 } ?: bitmap.height,
        )
    }

    private class MediaStoreItem(
        val uri: android.net.Uri,
        val video: Boolean,
        val width: Int,
        val height: Int,
    )

    /**
     * The typed images/video URI of a MediaStore row (the thumbnail provider
     * only serves typed rows) and its natural pixel size, the raw size the
     * regular decode reports. Null for rows that are neither.
     */
    private fun mediaStoreItem(uri: android.net.Uri): MediaStoreItem? = runCatching {
        val kind = mediaStoreKind(uri.toString())
        val files = kind == MEDIA_STORE_FILE
        val projection = buildList {
            add(android.provider.MediaStore.MediaColumns.WIDTH)
            add(android.provider.MediaStore.MediaColumns.HEIGHT)
            if (files) add(android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE)
        }.toTypedArray()
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val mediaType = if (files) cursor.getInt(2) else 0
            val video = kind == MEDIA_STORE_VIDEO ||
                mediaType == android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val image = kind == MEDIA_STORE_IMAGE ||
                mediaType == android.provider.MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            if (!video && !image) return@use null
            val typed = if (files) {
                val volume = uri.pathSegments.firstOrNull() ?: "external"
                val id = android.content.ContentUris.parseId(uri)
                android.content.ContentUris.withAppendedId(
                    if (video) {
                        android.provider.MediaStore.Video.Media.getContentUri(volume)
                    } else {
                        android.provider.MediaStore.Images.Media.getContentUri(volume)
                    },
                    id,
                )
            } else {
                uri
            }
            MediaStoreItem(typed, video, cursor.getInt(0), cursor.getInt(1))
        }
    }.getOrNull()

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
        repeat: Boolean = false,
        inline: Boolean = false,
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
        // Decode at the display size (subsample, then an exact scale to the
        // size that covers the view) instead of keeping up to 2x the view's
        // pixels per edge (4x the memory) of every oversize photo.
        val plan = nativeImageDecodePlan(
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            resizeMethod = resizeMethod,
            resizeMultiplier = resizeMultiplier,
            exactScale = !repeat,
            maxEdge = MAX_DECODE_EDGE,
            maxPixels = MAX_DECODE_PIXELS,
        )
        val storage = nativeBitmapStorage(
            sdk = Build.VERSION.SDK_INT,
            inline = inline,
            allowHardware = true,
            pixels = plan.width.toLong() * plan.height,
            mimeType = bounds.outMimeType,
        )
        fun options(config: Bitmap.Config) = BitmapFactory.Options().apply {
            inSampleSize = plan.sample
            inPreferredConfig = config
            if (plan.scaled) {
                // BitmapFactory scales the subsampled decode by
                // inTargetDensity / inDensity in the same native pass.
                inScaled = true
                inDensity = (bounds.outWidth + plan.sample - 1) / plan.sample
                inTargetDensity = plan.width
            } else {
                inScaled = false
            }
        }
        val config = when (storage) {
            NativeBitmapStorage.HARDWARE -> Bitmap.Config.HARDWARE
            NativeBitmapStorage.RGB_565 -> Bitmap.Config.RGB_565
            NativeBitmapStorage.ARGB_8888 -> Bitmap.Config.ARGB_8888
        }
        // A GPU allocation can fail (no gralloc buffer for the format):
        // fall back to a regular heap bitmap.
        val bitmap = requireNotNull(
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options(config)) }
                .getOrNull()
                ?: if (config != Bitmap.Config.ARGB_8888) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options(Bitmap.Config.ARGB_8888))
                } else {
                    null
                },
        ) {
            "Unsupported image format."
        }
        // A scaled decode carries the target "density"; restore the display
        // density so BitmapDrawable's intrinsic size stays the pixel size.
        if (plan.scaled) bitmap.density = context.resources.displayMetrics.densityDpi
        if (bitmap.config != Bitmap.Config.HARDWARE) {
            // Starts the GPU texture upload now, on the RenderThread, instead
            // of inside the frame that first draws the image: a 1080x2042
            // feed photo spent 8.5 ms of a scroll frame in "Texture upload" on
            // the S10. Hardware bitmaps are already GPU textures.
            bitmap.prepareToDraw()
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
            .toHexString()
        return File(diskDirectory, "$digest.image")
    }

    private fun cacheIdentity(source: String, request: NativeImageRequest): String =
        request.mediaCacheKey ?: sourceIdentities[source] ?: sha256(source.toByteArray()).also { identity ->
            // Identities are compared for every active request on each
            // network callback; hash each source once.
            if (sourceIdentities.size >= MAX_SOURCE_IDENTITIES) sourceIdentities.clear()
            sourceIdentities[source] = identity
        }

    private val sourceIdentities = ConcurrentHashMap<String, String>()

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
            .toHexString()

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
            request.repeat,
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
        val signature: NativeImageRequest,
        val request: NativeImageRequest,
        var callbacks: NativeImageCallbacks,
        var decodedKey: String? = null,
        var finished: Boolean = false,
        var retryAttempts: Int = 0,
        var released: Boolean = false,
        var animated: Boolean = false,
        var restoringCallbacks: NativeImageCallbacks? = null,
    )

    private data class DecodedBitmap(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val animatedBytes: ByteArray? = null,
    )

    private companion object {
        /** Shared by every loader instance so prefetch and rendering never race on one file. */
        val DISK_LOCK = Any()
        const val INLINE_MEMORY_CACHE_BYTES = 4 * 1024 * 1024
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

/** Decoded-photo cache budget for this device; see [nativeImageMemoryCacheBytes]. */
private fun memoryCacheBytes(context: Context): Int {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        ?: return nativeImageMemoryCacheBytes(256, lowRam = false)
    return nativeImageMemoryCacheBytes(manager.memoryClass, manager.isLowRamDevice)
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

private const val MAX_SOURCE_IDENTITIES = 2_048
private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Lowercase hex without String.format (which takes ICU locale locks per byte). */
internal fun ByteArray.toHexString(): String {
    val output = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        output[index * 2] = HEX_DIGITS[value ushr 4]
        output[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
    }
    return String(output)
}
