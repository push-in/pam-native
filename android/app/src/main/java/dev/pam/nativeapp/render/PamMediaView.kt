package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
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
) : FrameLayout(context) {
    private val video = VideoView(context)
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
            if (video.isPlaying) {
                onProgress?.invoke(video.currentPosition / 1_000.0, video.duration.coerceAtLeast(0) / 1_000.0)
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
        video.setOnPreparedListener { player ->
            preparedPlayer = player
            player.isLooping = looping
            applyAudio(player)
            player.playbackParams = player.playbackParams.setSpeed(rate)
            if (currentTime > 0) video.seekTo((currentTime * 1_000).toInt())
            if (autoPlay) video.start()
            video.post(::applyVideoTransform)
            onReady?.invoke()
        }
        video.setOnCompletionListener {
            onEnd?.invoke()
            if (looping) video.start()
        }
        video.setOnErrorListener { _, what, extra ->
            onError?.invoke("Media playback failed ($what/$extra)")
            true
        }
        main.post(progress)
    }

    fun setSource(value: String) {
        if (source == value) return
        source = value
        sourceGeneration++
        preparedPlayer = null
        if (value.isEmpty()) {
            video.stopPlayback()
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
                    video.setVideoURI(
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

    fun setAutoPlay(value: Boolean) { autoPlay = value; if (value && video.canPause()) video.start() }
    fun setControls(value: Boolean) {
        controls = value
        video.setMediaController(if (value) MediaController(context).also { it.setAnchorView(video) } else null)
    }
    fun setLoop(value: Boolean) { looping = value; preparedPlayer?.isLooping = value }
    fun setMuted(value: Boolean) { muted = value; preparedPlayer?.let(::applyAudio) }
    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        preparedPlayer?.let(::applyAudio)
    }
    fun seek(seconds: Double) {
        currentTime = seconds.coerceAtLeast(0.0)
        video.seekTo((currentTime * 1_000).toInt())
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
        resumeAfterPause = video.isPlaying
        video.pause()
    }

    fun onHostResume() {
        if (resumeAfterPause) {
            resumeAfterPause = false
            video.start()
        }
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
            video.width,
            video.height,
        )
        video.pivotX = video.width / 2f
        video.pivotY = video.height / 2f
        video.scaleX = scaleX
        video.scaleY = scaleY
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(progress)
        preparedPlayer = null
        video.stopPlayback()
        super.onDetachedFromWindow()
    }
}
