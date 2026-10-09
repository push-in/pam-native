package dev.pam.nativeapp.render

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

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

/** `null` (platform MediaPlayer, no forward-buffer control) for 0 seconds. */
internal fun pamForwardBuffer(seconds: Int): PamForwardBuffer? {
    if (seconds <= 0) return null
    val target = seconds.coerceAtMost(MAX_FORWARD_BUFFER_SECONDS) * 1_000
    return PamForwardBuffer(
        minBufferMs = target,
        maxBufferMs = target,
        bufferForPlaybackMs = minOf(EXO_BUFFER_FOR_PLAYBACK_MS, target),
        bufferForPlaybackAfterRebufferMs = minOf(EXO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, target),
    )
}

private const val MAX_FORWARD_BUFFER_SECONDS = 300

/** `DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS`. */
private const val EXO_BUFFER_FOR_PLAYBACK_MS = 2_500

/** `DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS`. */
private const val EXO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

/**
 * Media3 ExoPlayer behind a [PamMediaView] that declares a forward buffer
 * (`preloadSeconds` > 0). Android's MediaPlayer exposes no buffer control,
 * so only those players use ExoPlayer; every other one keeps MediaPlayer.
 * ExoPlayer is driven from the main thread (its application looper) and
 * reports back through [Listener] on that thread.
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

    private val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
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
    private var prepared = false
    private var buffering = false
    private var released = false

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (released) return
                    when (playbackState) {
                        Player.STATE_READY -> {
                            setBuffering(false)
                            if (!prepared) {
                                prepared = true
                                val size = player.videoSize
                                listener.onPrepared(size.width, size.height, durationMs)
                            }
                        }
                        Player.STATE_BUFFERING -> if (prepared) setBuffering(true)
                        Player.STATE_ENDED -> {
                            setBuffering(false)
                            listener.onCompletion()
                        }
                        else -> Unit
                    }
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (!released && videoSize.width > 0 && videoSize.height > 0) {
                        listener.onVideoSize(videoSize.width, videoSize.height)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!released) listener.onError("Media playback failed (${error.errorCodeName})")
                }
            },
        )
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
    }

    private fun setBuffering(value: Boolean) {
        if (buffering == value) return
        buffering = value
        listener.onBuffering(value)
    }

    val isPlaying: Boolean get() = !released && player.isPlaying

    val durationMs: Long
        get() = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0

    val positionMs: Long get() = player.currentPosition.coerceAtLeast(0)

    val bufferedPercentage: Int get() = player.bufferedPercentage.coerceIn(0, 100)

    val audioSessionId: Int get() = player.audioSessionId

    fun setSurface(surface: Surface?) {
        if (released) return
        if (surface == null) player.clearVideoSurface() else player.setVideoSurface(surface)
    }

    fun setLooping(value: Boolean) {
        player.repeatMode = if (value) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun setVolume(value: Float) {
        player.volume = value.coerceIn(0f, 1f)
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    fun play(rate: Float) {
        if (released) return
        if (player.playbackParameters.speed != rate) player.playbackParameters = PlaybackParameters(rate)
        // MediaPlayer.start() after completion plays again from the start.
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.playWhenReady = true
    }

    fun pause() {
        if (!released) player.playWhenReady = false
    }

    fun release() {
        if (released) return
        released = true
        player.release()
    }
}
