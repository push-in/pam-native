package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import android.widget.FrameLayout
import android.widget.MediaController
import java.io.File
import java.net.URI

internal fun resolvePamMediaFile(root: File, source: String): File {
    val uri = URI(source)
    require(uri.scheme.equals("pam-file", ignoreCase = true)) {
        "Invalid sandbox media URI."
    }
    require(uri.authority.isNullOrEmpty()) {
        "Sandbox media URI cannot contain an authority."
    }
    val sandbox = root.canonicalFile
    val relative = uri.path.orEmpty().removePrefix("/")
    require(relative.isNotEmpty()) { "Sandbox media path is empty." }
    val candidate = File(sandbox, relative).canonicalFile
    require(candidate.path.startsWith(sandbox.path + File.separator)) {
        "Sandbox media path escapes the application sandbox."
    }
    require(candidate.isFile) { "Sandbox media does not exist." }
    return candidate
}

internal fun shouldUseResolvedMediaUri(cachedIsEmpty: Boolean, cachedScheme: String?): Boolean =
    cachedIsEmpty || cachedScheme.equals("pam-file", ignoreCase = true)

internal fun resolveVideoScale(
    resizeMode: Int,
    containerWidth: Int,
    containerHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
): Pair<Float, Float> {
    if (containerWidth <= 0 || containerHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
        return 1f to 1f
    }
    // The TextureView fills the container, so the decoder stretches every
    // frame to the container box. The view scale turns that stretched box
    // back into the requested fit: the displayed size divided by the box.
    val fitX = containerWidth.toFloat() / videoWidth
    val fitY = containerHeight.toFloat() / videoHeight
    val scale = when (resizeMode) {
        1 -> maxOf(fitX, fitY) // cover: fills the box, crops the overflow
        2 -> minOf(fitX, fitY) // contain: whole frame, letterboxed
        3 -> return 1f to 1f // fill: the stretched box itself
        else -> 1f // center / repeat: native pixel size
    }
    return (videoWidth * scale / containerWidth) to (videoHeight * scale / containerHeight)
}

@SuppressLint("ViewConstructor") // Programmatic renderer injects its shared cache coordinator.
internal class PamMediaView(
    context: Context,
    private val mediaCache: NativeMediaFileCache,
    private val imageLoader: NativeImageLoader,
) : FrameLayout(context),
    MediaController.MediaPlayerControl,
    TextureView.SurfaceTextureListener {
    private val video = TextureView(context)
    private val poster = PamImageView(context)
    private val main = Handler(Looper.getMainLooper())
    private var source = ""
    private val playback = MediaPlaybackLifecycle()
    private var controls = true
    private var looping = false
    private var muted = false
    private var volume = 1f
    @Volatile private var rate = 1f
    private var resizeMode = 1
    private var currentTime = 0.0
    @Volatile private var preparedPlayer: MediaPlayer? = null
    @Volatile private var prepared = false
    private var bufferedPercentage = 0
    private var videoSurface: Surface? = null
    private var mediaController: MediaController? = null
    private var creatingPlayer = false
    @Volatile private var playerGeneration = 0L
    var onReady: (() -> Unit)? = null

    /** Ready with natural video size (px) and duration (s). */
    var onReadyDetails: ((Int, Int, Double) -> Unit)? = null
    var onLoadStart: (() -> Unit)? = null
    var onBuffering: ((Boolean) -> Unit)? = null
    var onProgress: ((Double, Double) -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onCacheHit: ((String) -> Unit)? = null
    var onCacheMiss: ((String) -> Unit)? = null
    var onCacheProgress: ((String, Long, Long) -> Unit)? = null
    var onCacheReady: ((String, Long) -> Unit)? = null
    private var cacheRequest = MediaCacheRequest("", MEDIA_CACHE_NONE, null, 0, 0, null, false, false, false)
    private var sourceGeneration = 0L

    /** Natural video size, cached from the prepared/size callbacks (no player query per layout). */
    private var videoWidthPx = 0
    private var videoHeightPx = 0

    /**
     * Progress polling: the player is queried on [mediaExecutor] (each query
     * is a binder call), the result is delivered on the main thread.
     */
    private val progress = object : Runnable {
        override fun run() {
            val player = preparedPlayer
            if (prepared && player != null && onProgress != null) {
                val generation = playerGeneration
                mediaExecutor.execute {
                    val sample = runCatching {
                        if (player.isPlaying) player.currentPosition to player.duration else null
                    }.getOrNull() ?: return@execute
                    main.post {
                        if (generation == playerGeneration && prepared) {
                            onProgress?.invoke(sample.first / 1_000.0, sample.second.coerceAtLeast(0) / 1_000.0)
                        }
                    }
                }
            }
            main.postDelayed(this, 250)
        }
    }

    /** Runs a player command off the UI thread, in order with creation and release. */
    private fun command(block: (MediaPlayer) -> Unit) {
        val player = preparedPlayer ?: return
        val generation = playerGeneration
        mediaExecutor.execute {
            if (generation == playerGeneration && preparedPlayer === player) runCatching { block(player) }
        }
    }

    init {
        addView(
            video,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = android.view.Gravity.CENTER
            },
        )
        addView(
            poster,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        video.surfaceTextureListener = this
        clipChildren = true
        clipToPadding = true
        main.post(progress)
    }

    fun setSource(value: String) {
        if (source == value) return
        source = value
        showPoster()
        sourceGeneration++
        if (value.isEmpty()) {
            releasePlayer()
        } else {
            val uri = Uri.parse(value)
            val resolved = runCatching {
                when {
                    uri.scheme == null -> {
                        val root = File(context.filesDir, "pam-files").canonicalFile
                        val file = File(root, value).canonicalFile
                        require(file.path.startsWith(root.path + File.separator)) {
                            "Media path escapes the application sandbox"
                        }
                        require(file.isFile) { "Media does not exist." }
                        Uri.fromFile(file)
                    }
                    uri.scheme.equals("pam-file", ignoreCase = true) ->
                        Uri.fromFile(resolvePamMediaFile(File(context.filesDir, "pam-files"), value))
                    else -> uri
                }
            }.getOrElse {
                onError?.invoke(it.message ?: "Media source is invalid.")
                return
            }
            val generation = sourceGeneration
            mediaCache.resolve(
                cacheRequest.copy(source = value),
                MediaCacheCallbacks(
                    hit = { onCacheHit?.invoke(it) },
                    miss = { onCacheMiss?.invoke(it) },
                    progress = { key, loaded, total -> onCacheProgress?.invoke(key, loaded, total) },
                    ready = { key, bytes -> onCacheReady?.invoke(key, bytes) },
                    error = { onError?.invoke(it) },
                ),
            ) { cached ->
                if (generation == sourceGeneration) {
                    prepareMedia(
                        if (shouldUseResolvedMediaUri(cached == Uri.EMPTY, cached.scheme)) {
                            resolved
                        } else {
                            cached
                        },
                    )
                }
            }
        }
    }

    fun setThumbnail(request: NativeImageRequest?) {
        if (request == null || request.source.isBlank()) {
            imageLoader.cancel(poster)
            poster.visibility = GONE
            return
        }
        poster.visibility = VISIBLE
        imageLoader.load(request, poster, NativeImageCallbacks())
    }

    fun setCacheRequest(request: MediaCacheRequest) {
        if (cacheRequest == request) return
        cacheRequest = request
        if (source.isNotEmpty()) {
            val current = source
            source = ""
            setSource(current)
        }
    }

    fun setAutoPlay(value: Boolean) {
        playback.autoPlay(value)
        syncPlayback()
    }

    fun setControls(value: Boolean) {
        controls = value
        mediaController = if (value) {
            (mediaController ?: MediaController(context)).also {
                it.setAnchorView(this)
                it.setMediaPlayer(this)
                it.isEnabled = prepared
            }
        } else {
            mediaController?.hide()
            null
        }
    }

    fun setLoop(value: Boolean) {
        looping = value
        command { it.isLooping = value }
    }

    fun setMuted(value: Boolean) {
        muted = value
        val actual = audioLevel()
        command { it.setVolume(actual, actual) }
    }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        val actual = audioLevel()
        command { it.setVolume(actual, actual) }
    }
    fun seek(seconds: Double) {
        currentTime = seconds.coerceAtLeast(0.0)
        if (prepared) seekTo((currentTime * 1_000).toInt())
    }
    fun setPlaybackRate(value: Float) {
        rate = value.coerceIn(0.25f, 4f)
        syncPlayback()
    }

    fun setResizeMode(value: Int) {
        resizeMode = value.coerceIn(1, 5)
        poster.scaleType = when (resizeMode) {
            2 -> android.widget.ImageView.ScaleType.FIT_CENTER
            3 -> android.widget.ImageView.ScaleType.FIT_XY
            4, 5 -> android.widget.ImageView.ScaleType.CENTER
            else -> android.widget.ImageView.ScaleType.CENTER_CROP
        }
        video.post(::applyVideoTransform)
    }

    fun onHostPause() {
        playback.hostActive(false)
        syncPlayback()
    }

    fun onHostResume() {
        playback.hostActive(true)
        syncVisibility()
    }

    private fun syncVisibility() {
        playback.visibility(isAttachedToWindow, isShown && windowVisibility == VISIBLE)
        syncPlayback()
    }

    private fun syncPlayback() {
        if (prepared) command(::applyPlayback)
    }

    /** The only start path, evaluated on the worker immediately before playback. */
    private fun applyPlayback(player: MediaPlayer) {
        if (!prepared || preparedPlayer !== player) return
        if (playback.mayPlay) {
            // PlaybackParams itself can start Android MediaPlayer. Set it only
            // inside this same foreground/visibility gate, including warm players.
            applyRate(player, rate)
            if (!player.isPlaying) player.start()
        } else if (player.isPlaying) {
            player.pause()
        }
    }

    /**
     * MediaPlayer construction, data source, surface, prepare and release
     * are binder round-trips to mediaserver: on the S10 a feed settle that
     * swapped the playing video blocked the UI thread ~95 ms inside one
     * mount. They run on [mediaExecutor] (a thread without a Looper, so the
     * player's callbacks still arrive on the main thread); [playerGeneration]
     * drops a player whose source changed while it was being created.
     */
    private fun prepareMedia(uri: Uri) {
        releasePlayer()
        val generation = playerGeneration
        val surface = videoSurface
        val appContext = context.applicationContext
        creatingPlayer = true
        onLoadStart?.invoke()
        mediaExecutor.execute {
            val player = MediaPlayer()
            installListeners(player, generation)
            val failure = runCatching {
                player.setSurface(surface)
                if (mediaDataSourceUsesNetworkString(uri.scheme)) {
                    player.setDataSource(uri.toString())
                } else {
                    player.setDataSource(appContext, uri)
                }
                player.prepareAsync()
            }.exceptionOrNull()
            main.post {
                if (generation != playerGeneration) {
                    discard(player)
                    return@post
                }
                creatingPlayer = false
                if (failure != null) {
                    discard(player)
                    onError?.invoke(failure.message ?: "Media source could not be prepared.")
                    poster.visibility = VISIBLE
                    return@post
                }
                preparedPlayer = player
                if (videoSurface !== surface) runCatching { player.setSurface(videoSurface) }
            }
        }
    }

    private fun installListeners(player: MediaPlayer, generation: Long) {
        player.setOnPreparedListener {
            if (generation != playerGeneration) return@setOnPreparedListener
            // Prepared before the creation hand-off ran: adopt it now.
            if (preparedPlayer == null) preparedPlayer = it
            if (preparedPlayer !== it) return@setOnPreparedListener
            prepared = true
            videoWidthPx = it.videoWidth
            videoHeightPx = it.videoHeight
            val loop = looping
            val level = audioLevel()
            val startAt = currentTime
            mediaExecutor.execute {
                if (generation != playerGeneration || preparedPlayer !== it) return@execute
                runCatching {
                    it.isLooping = loop
                    it.setVolume(level, level)
                    if (startAt > 0) it.seekTo((startAt * 1_000).toInt())
                    applyPlayback(it)
                }
            }
            mediaController?.isEnabled = true
            applyVideoTransform()
            onReady?.invoke()
            onReadyDetails?.invoke(it.videoWidth, it.videoHeight, it.duration.coerceAtLeast(0) / 1_000.0)
        }
        player.setOnVideoSizeChangedListener { _, width, height ->
            if (generation == playerGeneration && width > 0 && height > 0) {
                videoWidthPx = width
                videoHeightPx = height
                applyVideoTransform()
            }
        }
        player.setOnInfoListener { _, what, _ ->
            if (generation == playerGeneration) {
                when (what) {
                    MediaPlayer.MEDIA_INFO_BUFFERING_START -> onBuffering?.invoke(true)
                    MediaPlayer.MEDIA_INFO_BUFFERING_END -> onBuffering?.invoke(false)
                }
            }
            false
        }
        player.setOnCompletionListener {
            if (generation != playerGeneration) return@setOnCompletionListener
            playback.completed(looping)
            onEnd?.invoke()
            syncPlayback()
        }
        player.setOnBufferingUpdateListener { _, percentage ->
            if (generation == playerGeneration) bufferedPercentage = percentage.coerceIn(0, 100)
        }
        player.setOnErrorListener { _, what, extra ->
            if (generation == playerGeneration) {
                prepared = false
                showPoster()
                mediaController?.isEnabled = false
                onError?.invoke("Media playback failed ($what/$extra)")
            }
            true
        }
    }

    private fun releasePlayer() {
        showPoster()
        prepared = false
        videoWidthPx = 0
        videoHeightPx = 0
        creatingPlayer = false
        playerGeneration++
        bufferedPercentage = 0
        mediaController?.isEnabled = false
        preparedPlayer?.let(::discard)
        preparedPlayer = null
    }

    /** Stops and frees a player off the UI thread (release() waits for mediaserver). */
    private fun discard(player: MediaPlayer) {
        mediaExecutor.execute {
            runCatching { player.setSurface(null) }
            runCatching { player.reset() }
            runCatching { player.release() }
        }
    }

    private fun audioLevel(): Float = if (muted) 0f else volume

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyVideoTransform()
    }

    private fun applyVideoTransform() {
        val (scaleX, scaleY) = resolveVideoScale(
            resizeMode,
            width,
            height,
            videoWidthPx,
            videoHeightPx,
        )
        video.pivotX = video.width / 2f
        video.pivotY = video.height / 2f
        video.scaleX = scaleX
        video.scaleY = scaleY
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        videoSurface?.release()
        val surface = Surface(texture)
        videoSurface = surface
        command { it.setSurface(surface) }
    }

    override fun onSurfaceTextureSizeChanged(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        applyVideoTransform()
    }

    /**
     * `setSurface(null)` waits for mediaserver to stop rendering into it (41
     * ms of a settle frame on the S10): it runs off the UI thread, and the
     * surface and its texture are released once the player let go of them.
     */
    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val surface = videoSurface
        videoSurface = null
        val player = preparedPlayer ?: run {
            surface?.release()
            return true
        }
        mediaExecutor.execute {
            runCatching { player.setSurface(null) }
            main.post {
                surface?.release()
                texture.release()
            }
        }
        return false
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (prepared && poster.visibility != GONE) {
            poster.visibility = GONE
        }
    }

    private fun showPoster() {
        if (poster.drawable != null || poster.onImageSizeChanged != null) {
            poster.visibility = VISIBLE
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (controls) mediaController?.show()
        return true
    }

    override fun start() {
        playback.request(true)
        syncPlayback()
    }

    override fun pause() {
        playback.request(false)
        syncPlayback()
    }

    override fun getDuration(): Int =
        if (prepared) preparedPlayer?.duration?.coerceAtLeast(0) ?: 0 else 0

    override fun getCurrentPosition(): Int =
        if (prepared) preparedPlayer?.currentPosition?.coerceAtLeast(0) ?: 0 else 0

    override fun seekTo(position: Int) {
        currentTime = position.coerceAtLeast(0) / 1_000.0
        if (prepared) {
            val target = position.coerceAtLeast(0)
            command { it.seekTo(target) }
        }
    }

    override fun isPlaying(): Boolean = prepared && preparedPlayer?.isPlaying == true

    override fun getBufferPercentage(): Int = bufferedPercentage

    override fun canPause(): Boolean = true

    override fun canSeekBackward(): Boolean = true

    override fun canSeekForward(): Boolean = true

    override fun getAudioSessionId(): Int = preparedPlayer?.audioSessionId ?: 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (findViewTreeLifecycleOwner() ?: context as? LifecycleOwner)?.lifecycle?.let {
            playback.hostActive(it.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        syncVisibility()
        main.removeCallbacks(progress)
        main.post(progress)
        if (source.isNotEmpty() && preparedPlayer == null && !creatingPlayer) {
            val current = source
            source = ""
            setSource(current)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncVisibility()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncVisibility()
    }

    override fun onDetachedFromWindow() {
        playback.visibility(false, false)
        main.removeCallbacks(progress)
        sourceGeneration++
        releasePlayer()
        videoSurface?.release()
        videoSurface = null
        super.onDetachedFromWindow()
    }
}

/**
 * `setPlaybackParams` with a non-zero speed starts a prepared or paused
 * MediaPlayer: a paused (warm, autoPlay=false) player must stay paused.
 */
private fun applyRate(player: MediaPlayer, rate: Float) {
    if (rate == 1f && player.playbackParams.speed == 1f) return
    val wasPlaying = player.isPlaying
    player.playbackParams = player.playbackParams.setSpeed(rate)
    if (!wasPlaying && player.isPlaying) player.pause()
}

/** Player setup/teardown thread (no Looper: callbacks stay on the main thread). */
private val mediaExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "PamMediaPlayer").apply { isDaemon = true }
}

internal fun mediaDataSourceUsesNetworkString(scheme: String?): Boolean =
    scheme.equals("http", ignoreCase = true) ||
        scheme.equals("https", ignoreCase = true)
