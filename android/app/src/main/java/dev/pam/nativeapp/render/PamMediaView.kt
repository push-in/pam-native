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
    val scaleX = containerWidth.toFloat() / videoWidth
    val scaleY = containerHeight.toFloat() / videoHeight
    return when (resizeMode) {
        1 -> maxOf(scaleX, scaleY).let { it to it }
        3 -> scaleX to scaleY
        else -> 1f to 1f
    }
}

@SuppressLint("ViewConstructor") // Programmatic renderer injects its shared cache coordinator.
internal class PamMediaView(
    context: Context,
    private val mediaCache: NativeMediaFileCache,
) : FrameLayout(context),
    MediaController.MediaPlayerControl,
    TextureView.SurfaceTextureListener {
    private val video = TextureView(context)
    private val main = Handler(Looper.getMainLooper())
    private var source = ""
    private var autoPlay = false
    private var controls = true
    private var looping = false
    private var muted = false
    private var volume = 1f
    private var rate = 1f
    private var resizeMode = 1
    private var currentTime = 0.0
    private var preparedPlayer: MediaPlayer? = null
    private var prepared = false
    private var bufferedPercentage = 0
    private var videoSurface: Surface? = null
    private var mediaController: MediaController? = null
    private var resumeAfterPause = false
    var onReady: (() -> Unit)? = null
    var onProgress: ((Double, Double) -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onCacheHit: ((String) -> Unit)? = null
    var onCacheMiss: ((String) -> Unit)? = null
    var onCacheProgress: ((String, Long, Long) -> Unit)? = null
    var onCacheReady: ((String, Long) -> Unit)? = null
    private var cacheRequest = MediaCacheRequest("", MEDIA_CACHE_NONE, null, 0, 0, null, false, false, false)
    private var sourceGeneration = 0L

    private val progress = object : Runnable {
        override fun run() {
            val player = preparedPlayer
            if (prepared && player?.isPlaying == true) {
                onProgress?.invoke(
                    player.currentPosition / 1_000.0,
                    player.duration.coerceAtLeast(0) / 1_000.0,
                )
            }
            main.postDelayed(this, 250)
        }
    }

    init {
        addView(
            video,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = android.view.Gravity.CENTER
            },
        )
        video.surfaceTextureListener = this
        clipChildren = true
        clipToPadding = true
        main.post(progress)
    }

    fun setSource(value: String) {
        if (source == value) return
        source = value
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
        autoPlay = value
        if (!prepared) return
        if (value) start() else pause()
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
        preparedPlayer?.isLooping = value
    }

    fun setMuted(value: Boolean) { muted = value; preparedPlayer?.let(::applyAudio) }
    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        preparedPlayer?.let(::applyAudio)
    }
    fun seek(seconds: Double) {
        currentTime = seconds.coerceAtLeast(0.0)
        if (prepared) seekTo((currentTime * 1_000).toInt())
    }
    fun setPlaybackRate(value: Float) {
        rate = value.coerceIn(0.25f, 4f)
        preparedPlayer?.let { it.playbackParams = it.playbackParams.setSpeed(rate) }
    }

    fun setResizeMode(value: Int) {
        resizeMode = value.coerceIn(1, 5)
        video.post(::applyVideoTransform)
    }

    fun onHostPause() {
        resumeAfterPause = isPlaying
        pause()
    }

    fun onHostResume() {
        if (resumeAfterPause) {
            resumeAfterPause = false
            start()
        }
    }

    private fun prepareMedia(uri: Uri) {
        releasePlayer()
        val player = MediaPlayer()
        preparedPlayer = player
        player.setSurface(videoSurface)
        player.setOnPreparedListener {
            if (preparedPlayer !== it) return@setOnPreparedListener
            prepared = true
            it.isLooping = looping
            applyAudio(it)
            it.playbackParams = it.playbackParams.setSpeed(rate)
            if (currentTime > 0) it.seekTo((currentTime * 1_000).toInt())
            mediaController?.isEnabled = true
            applyVideoTransform()
            if (autoPlay) it.start()
            onReady?.invoke()
        }
        player.setOnCompletionListener {
            onEnd?.invoke()
            if (looping) it.start()
        }
        player.setOnBufferingUpdateListener { _, percentage ->
            bufferedPercentage = percentage.coerceIn(0, 100)
        }
        player.setOnErrorListener { _, what, extra ->
            prepared = false
            mediaController?.isEnabled = false
            onError?.invoke("Media playback failed ($what/$extra)")
            true
        }
        runCatching {
            player.setDataSource(context, uri)
            player.prepareAsync()
        }.onFailure {
            releasePlayer()
            onError?.invoke(it.message ?: "Media source could not be prepared.")
        }
    }

    private fun releasePlayer() {
        prepared = false
        bufferedPercentage = 0
        mediaController?.isEnabled = false
        preparedPlayer?.let { player ->
            runCatching { player.setSurface(null) }
            runCatching { player.reset() }
            player.release()
        }
        preparedPlayer = null
    }

    private fun applyAudio(player: MediaPlayer) {
        val actual = if (muted) 0f else volume
        player.setVolume(actual, actual)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyVideoTransform()
    }

    private fun applyVideoTransform() {
        val (scaleX, scaleY) = resolveVideoScale(
            resizeMode,
            width,
            height,
            preparedPlayer?.videoWidth ?: 0,
            preparedPlayer?.videoHeight ?: 0,
        )
        video.pivotX = video.width / 2f
        video.pivotY = video.height / 2f
        video.scaleX = scaleX
        video.scaleY = scaleY
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        videoSurface?.release()
        videoSurface = Surface(texture)
        preparedPlayer?.setSurface(videoSurface)
    }

    override fun onSurfaceTextureSizeChanged(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
    ) {
        applyVideoTransform()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        preparedPlayer?.setSurface(null)
        videoSurface?.release()
        videoSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit

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
        if (prepared) preparedPlayer?.start()
    }

    override fun pause() {
        if (prepared) preparedPlayer?.pause()
    }

    override fun getDuration(): Int =
        if (prepared) preparedPlayer?.duration?.coerceAtLeast(0) ?: 0 else 0

    override fun getCurrentPosition(): Int =
        if (prepared) preparedPlayer?.currentPosition?.coerceAtLeast(0) ?: 0 else 0

    override fun seekTo(position: Int) {
        currentTime = position.coerceAtLeast(0) / 1_000.0
        if (prepared) preparedPlayer?.seekTo(position.coerceAtLeast(0))
    }

    override fun isPlaying(): Boolean = prepared && preparedPlayer?.isPlaying == true

    override fun getBufferPercentage(): Int = bufferedPercentage

    override fun canPause(): Boolean = true

    override fun canSeekBackward(): Boolean = true

    override fun canSeekForward(): Boolean = true

    override fun getAudioSessionId(): Int = preparedPlayer?.audioSessionId ?: 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        main.removeCallbacks(progress)
        main.post(progress)
        if (source.isNotEmpty() && preparedPlayer == null) {
            val current = source
            source = ""
            setSource(current)
        }
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(progress)
        sourceGeneration++
        releasePlayer()
        videoSurface?.release()
        videoSurface = null
        super.onDetachedFromWindow()
    }
}
