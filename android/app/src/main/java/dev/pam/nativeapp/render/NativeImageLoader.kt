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
import android.widget.ImageView
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
    /** `contain`: the whole image fits the view, so it decodes to the fitted size. */
    val contain: Boolean = false,
    /**
     * Drop the decoded bitmap while the view is hidden even when the memory
     * cache is not used (a media poster under its painted video); it is
     * decoded again from the disk cache when the view shows again.
     */
    val releaseWhenHidden: Boolean = false,
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
    /** Set when [bitmap] is already blurred (the view's `blurRadius`). */
    val blur: PamImageBlur? = null,
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
 * A `content://media` source that may be a video (a video row, or a Files row
 * whose media type is read from the provider). A video is never read as image
 * bytes: at any view size it draws the platform thumbnail (its poster frame),
 * so a preview-sized still of a gallery video works like a grid cell.
 */
internal fun isMediaStoreVideoPosterCandidate(source: String, targetWidth: Int, targetHeight: Int): Boolean {
    if (targetWidth <= 0 || targetHeight <= 0) return false
    val uri = runCatching { URI(source) }.getOrNull() ?: return false
    if (!uri.scheme.equals("content", ignoreCase = true) || !uri.authority.equals("media", ignoreCase = true)) return false
    val kind = mediaStoreKind(source)
    return kind == MEDIA_STORE_VIDEO || kind == MEDIA_STORE_FILE
}

/** Where a MediaStore thumbnail comes from on this API level. */
internal enum class MediaStoreThumbnailSource {
    /** Android 10+: the provider's typed image stream (any image MIME type) at the requested size (cancellable). */
    PROVIDER_STREAM,

    /** Android 8-9: the provider's cached MINI_KIND thumbnail; never a decoded video frame. */
    LEGACY_MINI_KIND,
}

internal fun mediaStoreThumbnailSource(sdk: Int): MediaStoreThumbnailSource =
    if (sdk >= 29) MediaStoreThumbnailSource.PROVIDER_STREAM else MediaStoreThumbnailSource.LEGACY_MINI_KIND

/**
 * A MediaStore row without a platform thumbnail: photos fall back to the
 * sampled file decode; videos fail the load instead of reading (up to 16 MiB
 * of) the container per cell and failing to decode it.
 */
internal fun mediaStoreFallsBackToFileDecode(video: Boolean): Boolean = !video

/**
 * Gallery stills are opaque: RGB_565 halves every thumbnail bitmap (heap on
 * API 26-27, the GPU buffer of the HARDWARE copy on API 28+).
 */
internal val MEDIA_STORE_THUMBNAIL_STORAGE = NativeBitmapStorage.RGB_565

internal fun NativeBitmapStorage.decodeConfig(): Bitmap.Config = when (this) {
    NativeBitmapStorage.HARDWARE -> Bitmap.Config.HARDWARE
    NativeBitmapStorage.RGB_565 -> Bitmap.Config.RGB_565
    NativeBitmapStorage.ARGB_8888 -> Bitmap.Config.ARGB_8888
}

/** inSampleSize (power of two) so the decoded thumbnail still covers the requested box. */
internal fun thumbnailSampleSize(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) {
        sample *= 2
    }
    return sample
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

/**
 * Whether a hidden view may give its decoded pixels back: it is restored
 * from the memory cache, or from the disk cache for `releaseWhenHidden`.
 */
internal fun isReleasableImageRequest(request: NativeImageRequest): Boolean =
    !isInlineImageSource(request.source) &&
        request.cachePolicy != IMAGE_CACHE_RELOAD &&
        request.cachePolicy != IMAGE_CACHE_NONE &&
        request.mediaCachePolicy != MEDIA_CACHE_NONE &&
        (
            request.releaseWhenHidden ||
                (request.mediaCachePolicy != MEDIA_CACHE_DISK && request.mediaCachePolicy != MEDIA_CACHE_NETWORK_FIRST)
        )

internal fun isInlineImageSource(source: String): Boolean =
    source.regionMatches(0, "data:image/", 0, "data:image/".length, ignoreCase = true)

/** Inline sources this small (icon masks) decode on the UI thread, in the frame. */
internal const val INLINE_SYNC_MAX_CHARS = 16 * 1024

internal fun isSynchronousInlineSource(source: String): Boolean =
    source.length <= INLINE_SYNC_MAX_CHARS && isInlineImageSource(source)

/**
 * Packaged `asset://` images up to this size (an app's `assets/icons`, icon
 * masks referenced by path instead of a `data:` URI) also decode in the frame
 * and live in the inline cache, like iOS `PamInlineImages.smallAsset`.
 */
internal const val ASSET_SYNC_MAX_BYTES = 16 * 1024

internal fun isPackagedAssetSource(source: String): Boolean =
    source.regionMatches(0, "asset://", 0, "asset://".length, ignoreCase = true)

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
    // Downloads and decodes run on separate lanes: a slow or rate-limited
    // response holds a network thread, never a decoder, and visible images
    // (then nearby, then hidden ones) take the next free thread of each.
    private val networkLane = MediaWorkLane("pam-image-net", IMAGE_NETWORK_THREADS)
    private val decodeLane = MediaWorkLane("pam-image", DECODE_THREADS)
    /** Highest priority of the views waiting for each decoded key (written on the main thread). */
    private val demand = ConcurrentHashMap<String, Int>()
    private val networkJobs = ConcurrentHashMap<String, NetworkJob>()
    private val pendingDecodeBytes = AtomicLong()
    private val visibleRect = android.graphics.Rect()
    private var demandRefreshPosted = false
    private val refreshDemandRunnable = Runnable { refreshDemand() }
    // Tiny bundled/data-URI assets (for example icon masks) must never queue
    // behind remote photos, animated WebP decoding or disk reads. They are
    // part of the first interactive frame, so keep an isolated serial lane.
    private val inlineExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pam-image-inline").apply { isDaemon = true }
    }
    private val cache = object : LruCache<String, DecodedBitmap>(memoryCacheBytes(context)) {
        override fun sizeOf(key: String, value: DecodedBitmap): Int =
            nativeImageCacheWeight(
                value.bitmap.allocationByteCount,
                value.bitmap.width.toLong() * value.bitmap.height,
            ) + (value.animatedBytes?.size ?: 0)
    }
    // Inline icon masks live apart from photos: a feed or an inbox of
    // avatars must never evict the glyphs of the next screen's first frame.
    private val inlineCache = object : LruCache<String, DecodedBitmap>(INLINE_MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: DecodedBitmap): Int =
            value.bitmap.allocationByteCount + (value.animatedBytes?.size ?: 0)
    }
    private val smallAssets = ConcurrentHashMap<String, Boolean>()
    private val inFlight = InFlightImageLoads<NativeImageResult>()
    private val active = WeakHashMap<PamImageView, ActiveRequest>()
    // Provider thumbnail work per decoded key: a recycled or rebound gallery
    // cell cancels it so MediaProvider stops generating for a gone tile.
    private val thumbnailSignals = ConcurrentHashMap<String, android.os.CancellationSignal>()
    private val generation = AtomicLong()
    private val closed = AtomicBoolean()
    private val connections =
        ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val diskDirectory = File(context.cacheDir, DISK_DIRECTORY)
    private val diskLock = DISK_LOCK
    private var diskBytesSinceTrim = 0L
    private var diskWritesSinceTrim = 0

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
                failed = current?.failed == true,
            )
        ) {
            checkNotNull(current)
            current.callbacks = callbacks
            return
        }

        // A rebound (recycled) cell no longer wants its previous image.
        val previousKey = current?.takeIf { !it.finished }?.decodedKey
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
            refreshStaleBlur(view, token)
        }
        view.onShownChanged = { shown -> onShownChanged(view, shown) }
        view.onBlurChanged = { blurChanged(view, token) }
        callbacks.onStart()
        // Keep already rendered pixels on screen while a changed request is
        // resolved. Reconciliation must never flash a blank/placeholder frame.
        if (view.drawable == null) {
            showPlaceholder(view, pending)
        }
        begin(view, token, view.width, view.height)
        previousKey?.let(::cancelOrphanThumbnail)
    }

    /**
     * Warms the shared disk cache for a remote image without decoding it.
     * Returns the cached byte count. Used by Image::prefetch().
     */
    fun prefetch(source: String, requestHeaders: String? = null, cacheKey: String? = null): Long {
        check(!closed.get()) { "Image loader is closed" }
        // A packaged image has nothing to download: decode it now and keep
        // it for the process, so its first Image paints in the bind frame.
        if (normalizedPamAssetPath(source) != null) return PamPreloadedImages.preload(context, source)
        val request = NativeImageRequest(
            source = source,
            requestHeaders = requestHeaders,
            mediaCacheKey = cacheKey,
        )
        return loadRemote(source, request, { _, _ -> }, {}, prefetch = true).size.toLong()
    }

    fun cancel(view: PamImageView) {
        check(Looper.myLooper() == Looper.getMainLooper())
        active.remove(view)?.takeIf { !it.finished }?.decodedKey?.let { key ->
            cancelOrphanThumbnail(key)
            // Its queued download or decode is dropped unless another view wants it.
            scheduleDemandRefresh()
        }
        view.onImageSizeChanged = null
        view.onShownChanged = null
        view.onBlurChanged = null
        view.setImageDrawable(null)
    }

    /** Main thread: no view waits for [key] any more, so its provider thumbnail work stops. */
    private fun cancelOrphanThumbnail(key: String) {
        if (!thumbnailSignals.containsKey(key)) return
        if (active.values.any { it.decodedKey == key && !it.finished }) return
        thumbnailSignals.remove(key)?.cancel()
    }

    fun trimMemory(critical: Boolean, hidden: Boolean = false) {
        synchronized(cache) {
            // Hidden app: entries no view holds are only a return-time
            // shortcut; a shown bitmap stays alive in its view (no recycle()
            // on eviction), so nothing visible decodes again on return.
            if (critical || hidden) {
                cache.evictAll()
            } else {
                cache.trimToSize(cache.maxSize() / 2)
            }
        }
        // Inline glyphs are tiny and decode again synchronously; only a
        // critical trim drops them.
        if (critical) {
            synchronized(inlineCache) { inlineCache.evictAll() }
            PamPreloadedImages.clear()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        active.keys.forEach { view ->
            view.onImageSizeChanged = null
            view.onBlurChanged = null
        }
        active.clear()
        connections.toList().forEach(HttpURLConnection::disconnect)
        connections.clear()
        inFlight.cancelAll()
        networkLane.close()
        decodeLane.close()
        networkJobs.values.forEach { job -> job.future.cancel(false) }
        networkJobs.clear()
        demand.clear()
        inlineExecutor.shutdownNow()
        synchronized(cache) { cache.evictAll() }
        synchronized(inlineCache) { inlineCache.evictAll() }
    }

    /**
     * Resolves the view's image at [measuredWidth] x [measuredHeight]. With
     * [syncOnly], only a memory-cache hit or an inline glyph is shown; it
     * returns false when a load would have to start instead.
     */
    private fun begin(
        view: PamImageView,
        token: Long,
        measuredWidth: Int,
        measuredHeight: Int,
        syncOnly: Boolean = false,
    ): Boolean {
        val pending = active[view]
            ?.takeIf { it.token == token && !it.finished }
            ?: return true
        // A blurred image is decoded to the heap and blurred on a worker,
        // then cached blurred: no GPU readback and no blur on this thread.
        val blur = blurTarget(view, measuredWidth, measuredHeight)
        if (pending.request.sourceSet == null && blur == null) {
            PamPreloadedImages.get(pending.request.source)?.let { bitmap ->
                // Preloaded by Image::prefetch(asset://): painted in this
                // frame, before layout, at full resolution (ImageView scales).
                finishSuccess(
                    view,
                    pending,
                    NativeImageResult(pending.request.source, bitmap, bitmap.width, bitmap.height, null),
                    animate = false,
                )
                return true
            }
        }
        if (measuredWidth <= 0 || measuredHeight <= 0) return true

        val source = resolveSource(
            pending.request.source,
            pending.request.sourceSet,
            context.resources.displayMetrics.density,
            measuredWidth,
        )
        if (source.isBlank()) {
            finishError(view, pending, "Image source is empty.")
            return true
        }
        val key = decodedKey(
            source,
            pending.request,
            measuredWidth,
            measuredHeight,
        ) + blur?.keySuffix().orEmpty()
        if (pending.decodedKey == key) return true
        pending.decodedKey = key
        // Classifies a packaged file before the cache lookup (inline cache or photos).
        val assetBytes = if (smallAssets[source] == null) smallAssetBytes(source) else null
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
                        bitmap.blur,
                    ),
                    animate = false,
                )
                return true
            }
        }

        if (isSynchronousInlineSource(source) || smallAssets[source] == true) {
            // A bundled glyph (tens of bytes to a few KiB) decodes in well
            // under a millisecond: paint it in the frame that lays the view
            // out, like a font glyph, instead of a frame later. Its blur (an
            // icon's shadow) is a heap bitmap downscaled to σ ≈ 4: as cheap.
            val decoded = runCatching {
                val glyph = decode(
                    if (isInlineImageSource(source)) {
                        loadDataUri(source)
                    } else {
                        assetBytes ?: requireNotNull(smallAssetBytes(source)) { "Image asset changed size." }
                    },
                    measuredWidth,
                    measuredHeight,
                    pending.request.resizeMethod,
                    pending.request.resizeMultiplier,
                    repeat = pending.request.repeat,
                    inline = true,
                    contain = pending.request.contain,
                    heap = blur != null,
                )
                blur?.let { target -> blurred(glyph, target) } ?: glyph
            }.getOrElse { error ->
                finishError(view, pending, safeError(error))
                return true
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
                    decoded.blur,
                ),
                animate = false,
            )
            return true
        }
        if (syncOnly) {
            pending.decodedKey = null
            return false
        }

        // Registered before the job is queued: a lane drops a job no view wants.
        demand.merge(key, viewPriority(view), ::maxOf)
        val attempt = pending.retryAttempts
        val future = runCatching {
            inFlight.share(key) {
                startLoad(source, key, pending.request, measuredWidth, measuredHeight, blur, memory, attempt)
            }
        }.getOrElse { error ->
            finishError(view, pending, safeError(error), error)
            return true
        }
        scheduleDemandRefresh()

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
                    finishError(view, latest, safeError(error), error)
                } else {
                    finishSuccess(
                        view,
                        latest,
                        decoded,
                    )
                }
            }
        }
        return true
    }

    /**
     * Loads and decodes [source] for [key]: MediaStore thumbnails, local
     * files and the disk cache on the decode lane; a disk miss downloads on
     * the network lane (shared by every size of the same image) and decodes
     * back on the decode lane. A queued step no view wants any more is
     * dropped with [MediaNotWantedException].
     */
    private fun startLoad(
        source: String,
        key: String,
        request: NativeImageRequest,
        width: Int,
        height: Int,
        blur: BlurTarget?,
        memory: LruCache<String, DecodedBitmap>,
        attempt: Int,
    ): CompletableFuture<NativeImageResult> {
        val result = CompletableFuture<NativeImageResult>()
        val cacheable = request.cachePolicy != IMAGE_CACHE_NONE &&
            request.mediaCachePolicy != MEDIA_CACHE_NONE &&
            request.mediaCachePolicy != MEDIA_CACHE_DISK
        fun decodeAndKeep(bytes: ByteArray): NativeImageResult {
            val decoded = decode(
                bytes,
                width,
                height,
                request.resizeMethod,
                request.resizeMultiplier,
                repeat = request.repeat,
                inline = isInline(source),
                contain = request.contain,
                heap = blur != null,
            ).let { sharp -> blur?.let { target -> blurred(sharp, target) } ?: sharp }
            if (!closed.get() && cacheable) synchronized(memory) { memory.put(key, decoded) }
            return NativeImageResult(
                source,
                decoded.bitmap,
                decoded.width,
                decoded.height,
                decoded.animatedBytes,
                decoded.blur,
            )
        }
        val first = Runnable {
            mediaStoreThumbnail(source, key, request, width, height, heap = blur != null)
                ?.let { blur?.let { target -> blurred(it, target) } ?: it }
                ?.let { thumbnail ->
                    if (!closed.get() && cacheable) synchronized(memory) { memory.put(key, thumbnail) }
                    result.complete(
                        NativeImageResult(source, thumbnail.bitmap, thumbnail.width, thumbnail.height, blur = thumbnail.blur),
                    )
                    return@Runnable
                }
            if (!isRemoteImageSource(source)) {
                result.complete(decodeAndKeep(loadBytes(source, request, progress = { _, _ -> })))
                return@Runnable
            }
            readCachedRemote(source, request)?.let { cached ->
                result.complete(decodeAndKeep(cached))
                return@Runnable
            }
            val progress = { loaded: Long, total: Long ->
                main.post { dispatchProgress(key, loaded, total) }
                Unit
            }
            val partial = partial@{ partialBytes: ByteArray ->
                // A blurred image shows only once blurred.
                if (!request.progressiveRenderingEnabled || blur != null) return@partial
                val preview = runCatching {
                    decode(
                        partialBytes,
                        width,
                        height,
                        request.resizeMethod,
                        request.resizeMultiplier,
                        repeat = request.repeat,
                        inline = isInline(source),
                        contain = request.contain,
                    )
                }.getOrNull()?.bitmap ?: return@partial
                main.post { displayPartial(key, preview) }
            }
            fetchRemote(source, request, key, MEDIA_PRIORITY_DROP, attempt, progress, partial)
                .whenComplete { bytes, error ->
                    if (error != null || bytes == null) {
                        result.completeExceptionally(mediaRootCause(error) ?: IllegalStateException("Image request failed."))
                        return@whenComplete
                    }
                    val size = bytes.size.toLong()
                    pendingDecodeBytes.addAndGet(size)
                    decodeLane.submit(
                        KeyJob(key, result, onDrop = { pendingDecodeBytes.addAndGet(-size) }) {
                            try {
                                result.complete(decodeAndKeep(bytes))
                            } finally {
                                pendingDecodeBytes.addAndGet(-size)
                            }
                        },
                    )
                }
        }
        if (isInline(source)) {
            inlineExecutor.execute {
                runCatching { first.run() }.onFailure(result::completeExceptionally)
            }
        } else {
            decodeLane.submit(KeyJob(key, result, onDrop = {}) { first.run() })
        }
        return result
    }

    /** A decode-lane step of [key]: as urgent as the views that want it, dropped when none does. */
    private inner class KeyJob(
        private val key: String,
        private val result: CompletableFuture<NativeImageResult>,
        private val onDrop: () -> Unit,
        private val work: () -> Unit,
    ) : MediaWorkLane.Job() {
        override fun priority(): Int = if (closed.get()) MEDIA_PRIORITY_DROP else demand[key] ?: MEDIA_PRIORITY_DROP

        override fun run() {
            if (result.isDone) return
            try {
                work()
            } catch (error: Throwable) {
                result.completeExceptionally(error)
            }
        }

        override fun drop() {
            onDrop()
            result.completeExceptionally(MediaNotWantedException())
        }
    }

    /**
     * One download of a remote image (all sizes and views of it share it).
     * It waits in the queue while its host is paused (429/503) or decoded
     * bytes pile up, and runs as urgently as the most visible view wanting it.
     */
    private inner class NetworkJob(
        private val id: String,
        private val source: String,
        private val request: NativeImageRequest,
        private val basePriority: Int,
        private val attempt: Int,
        private val progress: (Long, Long) -> Unit,
        private val partial: (ByteArray) -> Unit,
    ) : MediaWorkLane.Job() {
        val future = CompletableFuture<ByteArray>()
        val wanters: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val host = runCatching { URI(source).host }.getOrNull()
        private val counted = AtomicBoolean(basePriority < 0)

        init {
            if (counted.get()) PamMediaHostGate.backlog.incrementAndGet()
        }

        private fun uncount() {
            if (counted.compareAndSet(true, false)) PamMediaHostGate.backlog.decrementAndGet()
        }

        override fun priority(): Int {
            if (closed.get()) return MEDIA_PRIORITY_DROP
            var best = basePriority
            for (wanter in wanters) {
                val wanted = demand[wanter] ?: continue
                if (wanted > best) best = wanted
            }
            return best
        }

        override fun waitMs(): Long {
            val paused = PamMediaHostGate.remainingMs(host)
            if (paused > 0) return paused
            return if (pendingDecodeBytes.get() > MAX_PENDING_DECODE_BYTES) DECODE_BACKLOG_WAIT_MS else 0
        }

        override fun run() {
            uncount()
            try {
                future.complete(downloadRemote(source, request, attempt, progress, partial))
            } catch (error: Throwable) {
                future.completeExceptionally(error)
            } finally {
                networkJobs.remove(id, this)
            }
        }

        override fun drop() {
            uncount()
            networkJobs.remove(id, this)
            future.completeExceptionally(MediaNotWantedException())
        }
    }

    /** The download of [source], shared with a queued or running one of the same bytes. */
    private fun fetchRemote(
        source: String,
        request: NativeImageRequest,
        wanter: String?,
        basePriority: Int,
        attempt: Int,
        progress: (Long, Long) -> Unit,
        partial: (ByteArray) -> Unit,
    ): CompletableFuture<ByteArray> {
        val id = diskFile(source, parseHeaders(request.requestHeaders), request.mediaCacheKey).name +
            "\u0000" + request.cachePolicy + "\u0000" + request.mediaCachePolicy
        while (true) {
            networkJobs[id]?.let { existing ->
                wanter?.let(existing.wanters::add)
                if (!existing.future.isDone) return existing.future
                networkJobs.remove(id, existing)
            }
            val job = NetworkJob(id, source, request, basePriority, attempt, progress, partial)
            wanter?.let(job.wanters::add)
            if (networkJobs.putIfAbsent(id, job) == null) {
                networkLane.submit(job)
                return job.future
            }
            job.drop()
        }
    }

    /** Main thread: how urgently [view] needs its image now. */
    private fun viewPriority(view: PamImageView): Int = when {
        !view.isAttachedToWindow || !view.isShown -> MEDIA_PRIORITY_HIDDEN
        view.getGlobalVisibleRect(visibleRect) -> MEDIA_PRIORITY_VISIBLE
        else -> MEDIA_PRIORITY_NEAR
    }

    /**
     * Recomputes [demand] from the views still waiting (main thread). It
     * runs again while jobs are queued, so a cell scrolled on screen jumps
     * ahead and one scrolled away or recycled stops costing a request.
     */
    private fun scheduleDemandRefresh() {
        if (demandRefreshPosted || closed.get()) return
        demandRefreshPosted = true
        main.postDelayed(refreshDemandRunnable, DEMAND_REFRESH_MS)
    }

    private fun refreshDemand() {
        demandRefreshPosted = false
        if (closed.get()) return
        val fresh = HashMap<String, Int>()
        active.forEach { (view, pending) ->
            val key = pending.decodedKey ?: return@forEach
            if (!pending.finished) fresh.merge(key, viewPriority(view), ::maxOf)
        }
        // Add before removing: a worker never sees a wanted key missing.
        demand.putAll(fresh)
        demand.keys.removeAll { it !in fresh }
        if (networkLane.queued > 0 || decodeLane.queued > 0) scheduleDemandRefresh()
    }

    /** σ and geometry of the view's blur, read on the UI thread for a worker. */
    private class BlurTarget(
        val viewSigma: Float,
        val width: Int,
        val height: Int,
        val scaleType: ImageView.ScaleType?,
    ) {
        /** The blurred decode is cached apart from the sharp one, per σ and drawn size. */
        fun keySuffix(): String = "\u0000blur\u0000$viewSigma\u0000${width}x$height\u0000${scaleType?.ordinal}"
    }

    private fun blurTarget(view: PamImageView, width: Int, height: Int): BlurTarget? =
        view.blurSigma.takeIf { it > 0f }?.let { sigma -> BlurTarget(sigma, width, height, view.scaleType) }

    /**
     * Worker thread: blurs a heap decode at the σ it is drawn with (σ is
     * authored in view px; see [PamBitmapBlur.bitmapSigma]) and drops the
     * sharp pixels. An animated image shows its first frame, blurred.
     */
    private fun blurred(decoded: DecodedBitmap, target: BlurTarget): DecodedBitmap {
        val sharp = decoded.bitmap
        val sourceWidth = sharp.width
        val sourceHeight = sharp.height
        val sigma = PamBitmapBlur.bitmapSigma(target.viewSigma, target.width, target.height, sourceWidth, sourceHeight, target.scaleType)
        val output = PamBitmapBlur.blur(sharp, sigma)
        // The sharp decode is this load's own: nothing else holds it.
        if (output !== sharp) sharp.recycle()
        // Uploads the small texture on the RenderThread now, not in the first frame that draws it.
        output.prepareToDraw()
        return DecodedBitmap(
            output,
            decoded.width,
            decoded.height,
            null,
            PamImageBlur(sourceWidth, sourceHeight, target.viewSigma, sigma),
        )
    }

    /**
     * The view's blur changed. A load in flight for the previous blur is
     * dropped (its result never shows); a finished image is resolved again
     * silently unless it already shows this blur.
     */
    private fun blurChanged(view: PamImageView, token: Long) {
        val pending = active[view]?.takeIf { it.token == token } ?: return
        // Released: showing again resolves it with the current blur.
        if (pending.released) return
        if (pending.finished) {
            refreshStaleBlur(view, token)
            return
        }
        pending.decodedKey = null
        resolveAgain(view, pending)
    }

    /**
     * A finished image whose shown blur (or sharpness) no longer fits the
     * view (the blur changed, or the view resized past the σ tolerance) is
     * resolved again without load events and without a cross-fade. The shown
     * blurred pixels stay until the new ones arrive.
     */
    private fun refreshStaleBlur(view: PamImageView, token: Long) {
        val pending = active[view]
            ?.takeIf { it.token == token && it.finished && !it.released && !it.failed }
            ?: return
        if (view.showsWantedBlur()) return
        if (pending.restoringCallbacks == null) {
            pending.restoringCallbacks = pending.callbacks
            pending.callbacks = NativeImageCallbacks()
        }
        pending.finished = false
        pending.decodedKey = null
        resolveAgain(view, pending)
    }

    /**
     * A memory-cache hit shows in this frame. A load starts after the current
     * batch of property changes instead: a rebound cell may get its new
     * source in the same batch, and the old one must not be decoded for it.
     */
    private fun resolveAgain(view: PamImageView, pending: ActiveRequest) {
        if (begin(view, pending.token, view.width, view.height, syncOnly = true)) return
        main.post {
            if (active[view] === pending && !pending.finished && pending.decodedKey == null) {
                begin(view, pending.token, view.width, view.height)
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
        val blur = blurTarget(view, view.width, view.height)
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
                        inline = isInline(source),
                        heap = blur != null,
                    ).let { sharp -> blur?.let { target -> blurred(sharp, target) } ?: sharp }
                }.getOrNull()
            },
            placeholderExecutor(source),
        ).whenComplete { placeholder, _ ->
            if (placeholder == null) return@whenComplete
            main.post {
                val latest = active[view]
                    ?.takeIf { it.token == token && !it.finished }
                    ?: return@post
                display(view, placeholder.bitmap, latest.request.repeat, 0, placeholder.blur)
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
            if (!pending.finished) scheduleDemandRefresh()
            if (
                pending.failed ||
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
        if (pending.parked) {
            // A transient failure waited for the view to come back.
            pending.parked = false
            begin(view, pending.token, view.width, view.height)
            return
        }
        if (pending.failed) {
            // No failure sticks: shown or attached again, it loads again.
            pending.failed = false
            pending.finished = false
            pending.retryAttempts = 0
            pending.decodedKey = null
            pending.callbacks.onStart()
            begin(view, pending.token, view.width, view.height)
            return
        }
        if (!pending.finished) scheduleDemandRefresh()
        if (!pending.released) return
        pending.released = false
        val cached = pending.decodedKey?.let { key -> synchronized(cache) { cache.get(key) } }
        // The blur may have changed while hidden: only a matching entry shows.
        if (cached != null && view.blurMatches(cached.blur)) {
            display(view, cached.bitmap, pending.request.repeat, 0, cached.blur)
            return
        }
        pending.restoringCallbacks = pending.callbacks
        pending.callbacks = NativeImageCallbacks()
        pending.finished = false
        pending.decodedKey = null
        begin(view, pending.token, view.width, view.height)
    }

    private fun isReleasable(request: NativeImageRequest): Boolean =
        !isInline(request.source) && isReleasableImageRequest(request)

    private fun endRestore(pending: ActiveRequest) {
        pending.restoringCallbacks?.let { original ->
            pending.callbacks = original
            pending.restoringCallbacks = null
        }
    }

    /** Placeholders (usually packaged files) decode ahead of every queued photo. */
    private fun placeholderExecutor(source: String): java.util.concurrent.Executor =
        if (isInline(source)) {
            inlineExecutor
        } else {
            java.util.concurrent.Executor { work ->
                decodeLane.submit(
                    object : MediaWorkLane.Job() {
                        override fun priority(): Int = if (closed.get()) MEDIA_PRIORITY_DROP else MEDIA_PRIORITY_VISIBLE + 1

                        override fun run() = work.run()

                        // Only a closing loader drops it: nobody waits for that placeholder.
                        override fun drop() = Unit
                    },
                )
            }
        }

    private fun memoryCacheFor(source: String) =
        if (isInline(source)) inlineCache else cache

    /** data: glyphs and packaged files of at most [ASSET_SYNC_MAX_BYTES] bytes (icon masks). */
    private fun isInline(source: String): Boolean =
        isInlineImageSource(source) || smallAssets[source] == true

    /**
     * Bytes of a packaged `asset://` file small enough to decode in the frame
     * (icon masks shipped as files instead of data: URIs, which cost ~2 KB of
     * wire per node); null for larger files, which load like photos.
     */
    private fun smallAssetBytes(source: String): ByteArray? {
        if (!isPackagedAssetSource(source)) return null
        if (smallAssets[source] == false) return null
        val path = normalizedPamAssetPath(source) ?: return null
        val bytes = runCatching {
            context.assets.open(path).use { input ->
                val buffer = ByteArray(ASSET_SYNC_MAX_BYTES + 1)
                var length = 0
                while (length < buffer.size) {
                    val read = input.read(buffer, length, buffer.size - length)
                    if (read < 0) break
                    length += read
                }
                if (length > ASSET_SYNC_MAX_BYTES) null else buffer.copyOf(length)
            }
        }.getOrNull()
        if (smallAssets.size < MAX_SMALL_ASSETS) smallAssets[source] = bytes != null
        return bytes
    }

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
                // A silent re-resolve (restore, blur change) swaps in place.
                if (animate && pending.restoringCallbacks == null && fadesIn(pending)) pending.request.fadeDurationMs else 0,
                result.blur,
            )
        }
        pending.callbacks.onSuccess(result)
        pending.callbacks.onEnd()
        endRestore(pending)
        // The blur changed in a way no request saw (never expected): resolve
        // it again rather than leave a sharp image refused or a wrong σ.
        if (!animated && !view.showsWantedBlur()) {
            main.post { refreshStaleBlur(view, pending.token) }
        }
    }

    private fun finishError(
        view: PamImageView,
        pending: ActiveRequest,
        message: String,
        error: Throwable? = null,
    ) {
        if (active[view] !== pending || pending.finished || closed.get()) return
        val cause = mediaRootCause(error)
        // Dropped from a queue while this view still wanted it (it was hidden
        // or recycled a moment): ask again at once.
        val delay = if (cause is java.util.concurrent.CancellationException) {
            0L
        } else {
            mediaRetryDelayMs(cause, pending.retryAttempts, kotlin.random.Random.nextDouble())
        }
        if (shouldRetryImageRequest(pending.retryAttempts, transient = delay != null)) {
            pending.retryAttempts++
            pending.decodedKey = null
            if (!view.isAttachedToWindow) {
                // Detached (a recycled or not yet attached cell): nothing is
                // requested until it is attached and shown again.
                pending.parked = true
                return
            }
            main.postDelayed(
                {
                    if (active[view] === pending && !pending.finished && !closed.get()) {
                        if (view.isAttachedToWindow && view.isShown) {
                            begin(view, pending.token, view.width, view.height)
                        } else {
                            pending.parked = true
                        }
                    }
                },
                checkNotNull(delay),
            )
            return
        }
        pending.finished = true
        pending.failed = true
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

    /**
     * A disk or fast network hit lands within a couple of frames of the
     * request: it replaces the placeholder at once instead of cross-fading
     * over a frame already on screen (Coil/Glide skip the fade for cache hits).
     * Only a load the user actually waited for fades in.
     */
    private fun fadesIn(pending: ActiveRequest): Boolean =
        imageFadesIn(android.os.SystemClock.uptimeMillis() - pending.startedAt)

    private fun display(
        view: PamImageView,
        bitmap: Bitmap,
        repeat: Boolean,
        fadeDurationMs: Int,
        blur: PamImageBlur? = null,
    ) {
        // Null: a sharp bitmap while the view has a blur (never shown).
        val next = view.bitmapDrawable(bitmap, repeat, blur) ?: return
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
     * into the heap while scrolling. Videos get the provider's thumbnail at any
     * view size (a preview still too) and never a decoded frame per cell or a
     * file read. Thumbnails decode as RGB_565 (opaque stills). Returns null
     * for any other source, a full-size photo request, or a photo without a
     * platform thumbnail, so the regular byte decode runs; a video without one
     * fails the load.
     */
    private fun mediaStoreThumbnail(
        source: String,
        key: String,
        request: NativeImageRequest,
        targetWidth: Int,
        targetHeight: Int,
        heap: Boolean = false,
    ): DecodedBitmap? {
        val cell = isMediaStoreThumbnailCandidate(source, request.resizeMethod, targetWidth, targetHeight)
        if (!cell && !isMediaStoreVideoPosterCandidate(source, targetWidth, targetHeight)) {
            return null
        }
        val item = mediaStoreItem(android.net.Uri.parse(source)) ?: return null
        if (!cell && !item.video) return null
        val signal = android.os.CancellationSignal()
        thumbnailSignals[key] = signal
        val bitmap = try {
            runCatching {
                when (mediaStoreThumbnailSource(Build.VERSION.SDK_INT)) {
                    MediaStoreThumbnailSource.PROVIDER_STREAM -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        providerThumbnail(
                            item.uri,
                            thumbnailRequestSize(item.width, item.height, targetWidth, targetHeight),
                            targetWidth,
                            targetHeight,
                            signal,
                        )
                    } else {
                        null
                    }
                    MediaStoreThumbnailSource.LEGACY_MINI_KIND -> legacyThumbnail(item)
                }
            }.getOrNull()
        } finally {
            thumbnailSignals.remove(key, signal)
        }
        if (bitmap == null) {
            if (mediaStoreFallsBackToFileDecode(item.video)) return null
            error(if (signal.isCanceled) "Image load was cancelled." else "Video thumbnail is unavailable.")
        }
        val fitted = coverScaledThumbnail(bitmap, targetWidth, targetHeight).let { scaled ->
            // GPU-only on API 28+, keeping the 16-bit format of the decode;
            // a thumbnail to blur stays on the heap (blurred next).
            if (heap) {
                scaled
            } else if (
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

    /**
     * Android 10+: the thumbnail MediaProvider keeps for gallery apps
     * (`openTypedAssetFileDescriptor` with `EXTRA_SIZE`, what
     * `ContentResolver.loadThumbnail` calls), a small JPEG decoded as RGB_565
     * and sampled to the box. Cancelled through [signal] when the cell goes.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun providerThumbnail(
        uri: android.net.Uri,
        size: android.util.Size,
        targetWidth: Int,
        targetHeight: Int,
        signal: android.os.CancellationSignal,
    ): Bitmap? {
        val extras = android.os.Bundle().apply {
            putParcelable(android.content.ContentResolver.EXTRA_SIZE, android.graphics.Point(size.width, size.height))
        }
        val bytes = context.contentResolver.openTypedAssetFileDescriptor(uri, "image/*", extras, signal)
            ?.use { descriptor -> descriptor.createInputStream().use(::readBounded) }
            ?: return null
        signal.throwIfCanceled()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = MEDIA_STORE_THUMBNAIL_STORAGE.decodeConfig()
            inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * Android 8-9: the MINI_KIND thumbnail the provider caches (512 px), as
     * RGB_565. For videos this replaces `MediaMetadataRetriever.getFrameAtTime`,
     * a full-resolution frame decoded on every bind.
     */
    @Suppress("DEPRECATION")
    private fun legacyThumbnail(item: MediaStoreItem): Bitmap? {
        val id = android.content.ContentUris.parseId(item.uri)
        val options = BitmapFactory.Options().apply { inPreferredConfig = MEDIA_STORE_THUMBNAIL_STORAGE.decodeConfig() }
        return if (item.video) {
            android.provider.MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                id,
                android.provider.MediaStore.Video.Thumbnails.MINI_KIND,
                options,
            )
        } else {
            android.provider.MediaStore.Images.Thumbnails.getThumbnail(
                context.contentResolver,
                id,
                android.provider.MediaStore.Images.Thumbnails.MINI_KIND,
                options,
            )
        }
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

    /**
     * Synchronous remote load (Image::prefetch and the non-render callers):
     * the disk cache, else one download on the calling thread. A prefetch
     * waits for a paused host and lets visible downloads go first.
     */
    private fun loadRemote(
        source: String,
        request: NativeImageRequest,
        progress: (Long, Long) -> Unit,
        partial: (ByteArray) -> Unit,
        prefetch: Boolean = false,
    ): ByteArray {
        readCachedRemote(source, request)?.let { return it }
        if (prefetch) yieldToVisibleLoads(source)
        return downloadRemote(source, request, 0, progress, partial)
    }

    private fun yieldToVisibleLoads(source: String) {
        val host = runCatching { URI(source).host }.getOrNull()
        val started = System.nanoTime()
        while (!closed.get()) {
            val waited = (System.nanoTime() - started) / 1_000_000
            val paused = PamMediaHostGate.remainingMs(host)
            val busy = PamMediaHostGate.backlog.get() > 0 && waited < PREFETCH_YIELD_MAX_MS
            if (paused <= 0 && !busy) return
            if (waited >= MEDIA_RETRY_MAX_MS + PREFETCH_YIELD_MAX_MS) return
            Thread.sleep(if (paused > 0) paused.coerceAtMost(1_000) else 100)
        }
    }

    /**
     * The disk-cache part of a remote load: cached bytes (and, for
     * stale-while-revalidate, a background refresh), or null when it must be
     * downloaded. Throws when the request may only use the cache.
     */
    private fun readCachedRemote(
        source: String,
        request: NativeImageRequest,
    ): ByteArray? {
        validateRemote(URI(source), null)
        val headers = parseHeaders(request.requestHeaders)
        val cacheFile = diskFile(source, headers, request.mediaCacheKey)
        val identity = cacheIdentity(source, request)
        val readDiskFirst = remoteDiskEnabled(request) &&
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
                    if (!closed.get()) {
                        fetchRemote(
                            source,
                            request.copy(mediaCachePolicy = MEDIA_CACHE_NETWORK_FIRST),
                            wanter = null,
                            basePriority = MEDIA_PRIORITY_PREFETCH,
                            attempt = 0,
                            progress = { _, _ -> },
                            partial = {},
                        )
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
        return null
    }

    private fun remoteDiskEnabled(request: NativeImageRequest): Boolean =
        request.cachePolicy != IMAGE_CACHE_NONE &&
            request.mediaCachePolicy in setOf(
                MEDIA_CACHE_DISK,
                MEDIA_CACHE_MEMORY_AND_DISK,
                MEDIA_CACHE_CACHE_FIRST,
                MEDIA_CACHE_NETWORK_FIRST,
                MEDIA_CACHE_CACHE_ONLY,
                MEDIA_CACHE_STALE_WHILE_REVALIDATE,
            )

    /**
     * Downloads [source], following redirects by hand. The bytes are cached
     * on disk under the image's own key (its cacheKey, else the requested
     * URL), never under a redirect target, so a signed CDN URL behind an API
     * redirect is fetched once. A 429/503 pauses the host for every loader.
     */
    private fun downloadRemote(
        source: String,
        request: NativeImageRequest,
        attempt: Int,
        progress: (Long, Long) -> Unit,
        partial: (ByteArray) -> Unit,
    ): ByteArray {
        validateRemote(URI(source), null)
        val headers = parseHeaders(request.requestHeaders)
        val origin = URI(source)
        val cacheFile = diskFile(source, headers, request.mediaCacheKey)
        val identity = cacheIdentity(source, request)
        val diskEnabled = remoteDiskEnabled(request)
        return try {
            var current = source
            var previous: URI? = null
            repeat(MAX_REDIRECTS + 1) { redirect ->
            val uri = URI(current)
            validateRemote(uri, previous)
            PamMediaHostGate.remainingMs(uri.host).takeIf { it > 0 }?.let { paused ->
                throw MediaHostPausedException(uri.host, paused)
            }
            val connection = URL(current).openConnection() as HttpURLConnection
            connections += connection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = imageReadTimeoutMs(attempt)
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
                if (status !in 200..299) {
                    val retryAfter = parseRetryAfterMs(connection.getHeaderField("Retry-After"))
                    if (status in MEDIA_HOST_PAUSE_STATUS) PamMediaHostGate.pause(uri.host, retryAfter)
                    throw MediaHttpException(status, retryAfter, "Image")
                }
                PamMediaHostGate.succeeded(uri.host)
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
                // `Cache-Control: no-store` keeps the bytes off disk, unless
                // the app named the cache key itself (it owns that policy).
                val noStore = request.mediaCacheKey == null &&
                    connection.getHeaderField("Cache-Control")?.contains("no-store", ignoreCase = true) == true
                if (diskEnabled && !noStore) {
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
        contain: Boolean = false,
        /** A heap bitmap, never a GPU one, and no texture upload: it is blurred next. */
        heap: Boolean = false,
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
            contain = contain,
            maxEdge = MAX_DECODE_EDGE,
            maxPixels = MAX_DECODE_PIXELS,
        )
        val storage = nativeBitmapStorage(
            sdk = Build.VERSION.SDK_INT,
            inline = inline,
            allowHardware = !heap,
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
        if (bitmap.config != Bitmap.Config.HARDWARE && !heap) {
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
        // The bytes go to a per-thread temporary outside the lock; only the
        // rename and the (occasional) trim are serialized. Trimming lists and
        // sorts the whole cache directory, so doing it on every write made
        // image threads queue behind each other after a fast fling and the
        // rows that came to rest waited seconds for their pictures.
        val temporary = File(diskDirectory, "${file.name}.${Thread.currentThread().id}.tmp")
        val written = runCatching {
            diskDirectory.mkdirs()
            temporary.outputStream().use { output -> output.write(bytes) }
        }.isSuccess
        if (!written) {
            temporary.delete()
            return
        }
        synchronized(diskLock) {
            runCatching {
                if (!temporary.renameTo(file)) {
                    file.outputStream().use { output -> output.write(bytes) }
                    temporary.delete()
                }
                diskBytesSinceTrim += bytes.size
                diskWritesSinceTrim++
                if (diskTrimDue(diskBytesSinceTrim, diskWritesSinceTrim)) {
                    diskBytesSinceTrim = 0L
                    diskWritesSinceTrim = 0
                    trimDisk(limit.coerceIn(8L * 1024 * 1024, MAX_DISK_CACHE_BYTES))
                }
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
            request.contain,
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
        /** A transient failure waits for the view to be attached and shown before it retries. */
        var parked: Boolean = false,
        var released: Boolean = false,
        var animated: Boolean = false,
        var failed: Boolean = false,
        var restoringCallbacks: NativeImageCallbacks? = null,
        val startedAt: Long = android.os.SystemClock.uptimeMillis(),
    )

    private data class DecodedBitmap(
        val bitmap: Bitmap,
        val width: Int,
        val height: Int,
        val animatedBytes: ByteArray? = null,
        /** Set when [bitmap] is blurred (cached under the blurred key). */
        val blur: PamImageBlur? = null,
    )

    private companion object {
        /** Shared by every loader instance so prefetch and rendering never race on one file. */
        val DISK_LOCK = Any()
        const val INLINE_MEMORY_CACHE_BYTES = 4 * 1024 * 1024
        const val MAX_SMALL_ASSETS = 4_096
        const val DISK_CACHE_BYTES = 96L * 1024 * 1024
        // RN (expo-image/Glide) keeps 250 MB of images on disk; a per-image
        // `cacheMaxBytes` can no longer grow the shared directory past this.
        const val MAX_DISK_CACHE_BYTES = 256L * 1024 * 1024
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
        const val DECODE_THREADS = 3
        const val DEMAND_REFRESH_MS = 150L
        /** Downloaded bytes waiting for a decoder above this hold new downloads back. */
        const val MAX_PENDING_DECODE_BYTES = 24L * 1024 * 1024
        const val DECODE_BACKLOG_WAIT_MS = 50L
        /** Prefetches (Image::prefetch) wait at most this long for visible downloads to drain. */
        const val PREFETCH_YIELD_MAX_MS = 10_000L
        const val MAX_REDIRECTS = 5
        const val MAX_HEADERS = 32
        const val MAX_HEADER_BYTES = 4_096
        const val MAX_ERROR_BYTES = 512
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

/** A failed request is never reused: the same image bound again loads again. */
internal fun shouldReuseImageRequest(
    sameSignature: Boolean,
    finished: Boolean,
    hasDrawable: Boolean,
    failed: Boolean = false,
): Boolean = sameSignature && !failed && (!finished || hasDrawable)

/** Transient failures retry (with backoff) [IMAGE_MAX_TRANSIENT_RETRIES] times before onError; permanent ones never. */
internal fun shouldRetryImageRequest(
    attempts: Int,
    transient: Boolean,
): Boolean = transient && attempts < IMAGE_MAX_TRANSIENT_RETRIES

/**
 * Parallel downloads per loader: more than the 3 decoders, so a few slow or
 * held responses never stall the visible images queued behind them.
 */
internal const val IMAGE_NETWORK_THREADS = 5

/** About 90 s of backoff (0.5 s doubling to 30 s) before a transient failure is reported. */
internal const val IMAGE_MAX_TRANSIENT_RETRIES = 8

/** Read timeout (time to the first byte, then between reads) per attempt: short first, longer on retries. */
internal fun imageReadTimeoutMs(attempt: Int): Int = when {
    attempt <= 0 -> 7_000
    attempt == 1 -> 11_000
    else -> 15_000
}

internal fun isRemoteImageSource(source: String): Boolean =
    source.startsWith("https://", ignoreCase = true) || source.startsWith("http://", ignoreCase = true)

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

/** Trim the disk cache once a few megabytes or dozens of files were written since the last trim. */
internal fun diskTrimDue(bytesSinceTrim: Long, writesSinceTrim: Int): Boolean =
    bytesSinceTrim >= 4L * 1024 * 1024 || writesSinceTrim >= 48

/** Loads shorter than this (disk, memory-adjacent, fast network) skip the fade. */
internal const val IMAGE_INSTANT_LOAD_MS = 120L

internal fun imageFadesIn(elapsedMs: Long): Boolean = elapsedMs >= IMAGE_INSTANT_LOAD_MS
