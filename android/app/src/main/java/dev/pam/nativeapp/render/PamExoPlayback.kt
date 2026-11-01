package dev.pam.nativeapp.render

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Buffer targets of a `MediaPlayer` with `preloadSeconds` (the forward
 * buffer, like iOS `AVPlayerItem.preferredForwardBufferDuration`): the
 * player keeps about [maxBufferMs] of media loaded ahead of the playhead.
 * Playback (re)starts with the ExoPlayer defaults, capped by the target.
 */
internal data class PamForwardBuffer(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
)

/**
 * `null` (platform MediaPlayer, no buffer control) when neither a forward
 * buffer ([seconds]) nor a start buffer ([startBufferMs], RN/ExoPlayer
 * `bufferForPlaybackMs`) is declared. A start buffer makes playback begin
 * once that much media is loaded (and resume after a stall once twice that
 * is, capped by the ExoPlayer default); without a forward buffer the
 * ExoPlayer default targets are kept.
 */
internal fun pamForwardBuffer(seconds: Int, startBufferMs: Int = 0): PamForwardBuffer? {
    val start = startBufferMs.coerceIn(0, MAX_START_BUFFER_MS)
    if (seconds <= 0 && start <= 0) return null
    val target = if (seconds > 0) {
        seconds.coerceAtMost(MAX_FORWARD_BUFFER_SECONDS) * 1_000
    } else {
        EXO_DEFAULT_BUFFER_MS
    }
    val playback = if (start > 0) start else EXO_BUFFER_FOR_PLAYBACK_MS
    val rebuffer = if (start > 0) {
        minOf(EXO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, start * 2)
    } else {
        EXO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
    }
    return PamForwardBuffer(
        minBufferMs = target,
        maxBufferMs = target,
        bufferForPlaybackMs = minOf(playback, target),
        bufferForPlaybackAfterRebufferMs = minOf(rebuffer, target),
    )
}

private const val MAX_START_BUFFER_MS = 30_000

/** `DefaultLoadControl.DEFAULT_MIN_BUFFER_MS` / `DEFAULT_MAX_BUFFER_MS`. */
private const val EXO_DEFAULT_BUFFER_MS = 50_000

private const val MAX_FORWARD_BUFFER_SECONDS = 300

/** `DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS`. */
private const val EXO_BUFFER_FOR_PLAYBACK_MS = 2_500

/** `DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS`. */
private const val EXO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

/**
 * Media3 player owned entirely by a short-lived media looper. In particular,
 * ExoPlayer.release() waits for its playback thread/codec: doing that inside a
 * recycled cell's UI commit can block a first page change for hundreds of ms.
 * Commands and release stay ordered on the owner; callbacks return to the UI.
 * Immutable snapshots let the UI read clocks without waiting for that looper.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class PamExoPlayback(
    context: Context,
    uri: Uri,
    buffer: PamForwardBuffer,
    private val listener: Listener,
) {
    interface Listener {
        fun onPrepared(width: Int, height: Int, durationMs: Long)
        fun onVideoSize(width: Int, height: Int)
        fun onBuffering(buffering: Boolean)
        fun onCompletion()
        fun onError(message: String)
    }

    private data class Snapshot(
        val position: Long = 0,
        val duration: Long = 0,
        val buffered: Int = 0,
        val audioSession: Int = 0,
        val playing: Boolean = false,
        val speed: Float = 1f,
        val sampledAt: Long = 0,
    )

    private val main = Handler(Looper.getMainLooper())
    private val ownerThread = HandlerThread("PamMediaOwner").apply { start() }
    private val owner = Handler(ownerThread.looper)
    private val released = AtomicBoolean(false)
    @Volatile private var snapshot = Snapshot()
    // Accessed only on ownerThread.
    private var player: ExoPlayer? = null
    private var prepared = false
    private var buffering = false
    private val clock = object : Runnable {
        override fun run() {
            if (released.get()) return
            val current = player ?: return
            sample(current)
            if (current.isPlaying) owner.postDelayed(this, 250)
        }
    }

    init {
        val appContext = mediaContext(context)
        owner.post {
            if (released.get()) return@post
            runCatching {
                val current = ExoPlayer.Builder(appContext)
                    .setLooper(owner.looper)
                    .setLoadControl(
                        DefaultLoadControl.Builder()
                            .setBufferDurationsMs(
                                buffer.minBufferMs,
                                buffer.maxBufferMs,
                                buffer.bufferForPlaybackMs,
                                buffer.bufferForPlaybackAfterRebufferMs,
                            )
                            .setPrioritizeTimeOverSizeThresholds(true)
                            .build(),
                    )
                    .build()
                player = current
                current.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (released.get()) return
                        sample(current)
                        owner.removeCallbacks(clock)
                        if (current.isPlaying) owner.postDelayed(clock, 250)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (released.get()) return
                        sample(current)
                        when (playbackState) {
                            Player.STATE_READY -> {
                                setBuffering(false)
                                if (!prepared) {
                                    prepared = true
                                    val size = current.videoSize
                                    val duration = snapshot.duration
                                    dispatch { listener.onPrepared(size.width, size.height, duration) }
                                }
                            }
                            Player.STATE_BUFFERING -> if (prepared) setBuffering(true)
                            Player.STATE_ENDED -> {
                                setBuffering(false)
                                dispatch { listener.onCompletion() }
                            }
                            // Idle follows stop()/release() or an error, both reported elsewhere.
                            Player.STATE_IDLE -> Unit
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            dispatch { listener.onVideoSize(videoSize.width, videoSize.height) }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        dispatch { listener.onError("Media playback failed (${error.errorCodeName})") }
                    }
                })
                current.setMediaItem(MediaItem.fromUri(uri))
                current.prepare()
            }.onFailure { error ->
                runCatching { player?.release() }
                player = null
                snapshot = snapshot.copy(playing = false)
                dispatch { listener.onError(error.message ?: "Media source could not be prepared.") }
                owner.postDelayed({ ownerThread.quitSafely() }, OWNER_QUIT_DELAY_MS)
            }
        }
    }

    private fun dispatch(callback: () -> Unit) {
        main.post { if (!released.get()) callback() }
    }

    private fun command(operation: (ExoPlayer) -> Unit) {
        if (released.get()) return
        owner.post {
            if (released.get()) return@post
            val current = player ?: return@post
            runCatching {
                operation(current)
                sample(current)
            }.onFailure { error ->
                dispatch { listener.onError(error.message ?: "Media command failed.") }
            }
        }
    }

    private fun sample(current: ExoPlayer) {
        snapshot = Snapshot(
            position = current.currentPosition.coerceAtLeast(0),
            duration = current.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0,
            buffered = current.bufferedPercentage.coerceIn(0, 100),
            audioSession = current.audioSessionId,
            playing = current.isPlaying,
            speed = current.playbackParameters.speed,
            sampledAt = SystemClock.elapsedRealtime(),
        )
    }

    private fun setBuffering(value: Boolean) {
        if (buffering == value) return
        buffering = value
        dispatch { listener.onBuffering(value) }
    }

    val isPlaying: Boolean get() = !released.get() && snapshot.playing
    val durationMs: Long get() = snapshot.duration
    val positionMs: Long
        get() {
            val current = snapshot
            val elapsed = if (!released.get() && current.playing) {
                ((SystemClock.elapsedRealtime() - current.sampledAt).coerceAtLeast(0) * current.speed).toLong()
            } else 0L
            return (current.position + elapsed).coerceIn(0, current.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        }
    val bufferedPercentage: Int get() = snapshot.buffered
    val audioSessionId: Int get() = snapshot.audioSession

    fun setSurface(surface: Surface?) = command { current ->
        if (surface == null) current.clearVideoSurface() else current.setVideoSurface(surface)
    }

    fun setLooping(value: Boolean) = command {
        it.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setVolume(value: Float) = command { it.volume = value.coerceIn(0f, 1f) }
    fun seekTo(positionMs: Long) = command { it.seekTo(positionMs.coerceAtLeast(0)) }

    fun play(rate: Float) = command {
        if (it.playbackParameters.speed != rate) it.playbackParameters = PlaybackParameters(rate)
        if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
        it.playWhenReady = true
    }

    fun pause() = command { it.playWhenReady = false }

    /**
     * Lets go of the video surface on the player's thread, then runs [done]
     * on the main thread: the surface (and its texture) may be released only
     * after that, or a codec still starting writes into an abandoned queue
     * (CodecException / "flush() ... at Released state").
     */
    fun detachSurface(done: () -> Unit) {
        owner.post {
            runCatching { player?.clearVideoSurface() }
            main.post { done() }
        }
    }

    /** Runs [done] on the main thread after every command already posted. */
    fun afterPending(done: () -> Unit) {
        owner.post { main.post { done() } }
    }

    fun release(onReleased: (() -> Unit)? = null) {
        if (!released.compareAndSet(false, true)) {
            onReleased?.let { done -> owner.post { main.post { done() } } }
            return
        }
        owner.removeCallbacksAndMessages(null)
        owner.post {
            try {
                // Silence/pause precede codec teardown, which may wait for
                // mediaserver while the next visible player starts elsewhere.
                runCatching { player?.volume = 0f }
                runCatching { player?.playWhenReady = false }
                runCatching { player?.release() }
                player = null
                snapshot = snapshot.copy(playing = false)
                onReleased?.let { done -> main.post { done() } }
            } finally {
                // The codecs finish releasing asynchronously and still post to
                // this looper: quitting at once logged "sending message to a
                // Handler on a dead thread" for every released player.
                owner.postDelayed({ ownerThread.quitSafely() }, OWNER_QUIT_DELAY_MS)
            }
        }
    }
}

/**
 * The player's context, attributed to the manifest's "media" tag (API 30+):
 * an untagged audio track made AppOps log "attributionTag not declared".
 */
internal fun mediaContext(context: android.content.Context): android.content.Context {
    val application = context.applicationContext
    return if (android.os.Build.VERSION.SDK_INT >= 30) {
        application.createAttributionContext("media")
    } else {
        application
    }
}

/** How long a released player's owner thread outlives it (late codec events). */
private const val OWNER_QUIT_DELAY_MS = 3_000L
