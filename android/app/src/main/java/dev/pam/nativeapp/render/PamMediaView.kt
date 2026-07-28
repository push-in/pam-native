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
        addView(video, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        video.setOnPreparedListener { player ->
            preparedPlayer = player
            player.isLooping = looping
            applyAudio(player)
            player.playbackParams = player.playbackParams.setSpeed(rate)
            if (currentTime > 0) video.seekTo((currentTime * 1_000).toInt())
            if (autoPlay) video.start()
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
            val resolved = if (uri.scheme == null) {
                val root = File(context.filesDir, "pam-files").canonicalFile
                val file = File(root, value).canonicalFile
                if (!file.path.startsWith(root.path + File.separator)) {
                    onError?.invoke("Media path escapes the application sandbox")
                    return
                }
                Uri.fromFile(file)
            } else {
                uri
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
                    video.setVideoURI(if (cached == Uri.EMPTY) resolved else cached)
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

    override fun onDetachedFromWindow() {
        main.removeCallbacks(progress)
        preparedPlayer = null
        video.stopPlayback()
        super.onDetachedFromWindow()
    }
}
