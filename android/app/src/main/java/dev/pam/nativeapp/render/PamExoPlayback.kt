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
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Buffer targets of a `MediaPlayer` with `preloadSeconds` (the forward
 * buffer, like iOS `AVPlayerItem.preferredForwardBufferDuration`): the
 * player keeps about [maxBufferMs] of media loaded ahead of the playhead.
 * Playback starts after 1 s of media (2 s after a stall), capped by the target.
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
    // Without a declared start buffer, playback starts after 1 s of media
    // (2 s after a stall), not ExoPlayer's 2.5 s / 5 s: feeds and viewers
    // start like AVPlayer does instead of holding the poster for seconds.
    val playback = if (start > 0) start else DEFAULT_START_BUFFER_MS
    val rebuffer = if (start > 0) {
        minOf(EXO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, start * 2)
    } else {
        DEFAULT_START_BUFFER_MS * 2
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

/** Start buffer when only a forward buffer is declared (ExoPlayer: 2_500). */
private const val DEFAULT_START_BUFFER_MS = 1_000

/** `DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS`. */
private const val EXO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

/**
 * Media3 player owned entirely by a short-lived media looper. In particular,
 * ExoPlayer.release() waits for its playback thread/codec: doing that inside a
 * recycled cell's UI commit can block a first page change for hundreds of ms.
 * Commands and release stay ordered on the owner; callbacks return to the UI.
 * Immutable snapshots let the UI read clocks without waiting for that looper.
 */
/** One audio or text track of the source; [id] stays the same while the source does. */
internal data class PamMediaTrack(
    val id: String,
    val type: String,
    val language: String,
    val label: String,
    val selected: Boolean,
)

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

        /** [transient]: a network or rate-limit failure worth preparing again later; false for 4xx and bad media. */
        fun onError(message: String, transient: Boolean)

        /** The audio and text tracks, again whenever they or their selection change. */
        fun onTracks(tracks: List<PamMediaTrack>) = Unit

        /** The subtitle lines to show now; empty clears them. */
        fun onCues(text: String) = Unit
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
    // A video is reported prepared once its first frame is on the surface
    // (or after a short fallback): STATE_READY lands before that frame, and
    // a reveal (poster fade) at that moment showed a black surface.
    private var firstFrameRendered = false
    private var preparedAnnounced = false
    private val preparedFallback = Runnable { player?.let(::announcePrepared) }
    // The app's track choice, applied again when the tracks change ("" = default audio, no subtitles).
    private var audioChoice = ""
    private var textChoice = ""

    private fun announcePrepared(current: ExoPlayer) {
        if (preparedAnnounced || released.get()) return
        preparedAnnounced = true
        owner.removeCallbacks(preparedFallback)
        val size = current.videoSize
        val duration = snapshot.duration
        dispatch { listener.onPrepared(size.width, size.height, duration) }
    }
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
                // A paused host (429/503 seen by an image or another player)
                // fails the load before a request, so the retry policy waits.
                val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(appContext)) { spec ->
                    val paused = PamMediaHostGate.remainingMs(spec.uri.host)
                    if (paused > 0) throw MediaHostPausedException(spec.uri.host.orEmpty(), paused)
                    spec
                }
                val current = ExoPlayer.Builder(appContext)
                    .setLooper(owner.looper)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(dataSource)
                            .setLoadErrorHandlingPolicy(PamMediaLoadErrorPolicy()),
                    )
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
                // Subtitles stay off (and unparsed) until the app picks a text track.
                current.trackSelectionParameters = current.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
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
                                    val video = current.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                                    if (!video || firstFrameRendered) {
                                        announcePrepared(current)
                                    } else {
                                        owner.postDelayed(preparedFallback, FIRST_FRAME_FALLBACK_MS)
                                    }
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

                    override fun onRenderedFirstFrame() {
                        if (released.get()) return
                        firstFrameRendered = true
                        if (prepared) announcePrepared(current)
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            dispatch { listener.onVideoSize(videoSize.width, videoSize.height) }
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        if (released.get()) return
                        applyChoices(current)
                        val list = describe(tracks)
                        dispatch { listener.onTracks(list) }
                    }

                    override fun onCues(cueGroup: CueGroup) {
                        val text = cueGroup.cues
                            .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                            // Segmented WebVTT repeats a cue that spans two segments.
                            .distinct()
                            .joinToString("\n")
                        dispatch { listener.onCues(text) }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val transient = isTransientPlaybackError(error)
                        dispatch { listener.onError("Media playback failed (${error.errorCodeName})", transient) }
                    }
                })
                current.setMediaItem(MediaItem.fromUri(uri))
                current.prepare()
            }.onFailure { error ->
                runCatching { player?.release() }
                player = null
                snapshot = snapshot.copy(playing = false)
                dispatch { listener.onError(error.message ?: "Media source could not be prepared.", false) }
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
                dispatch { listener.onError(error.message ?: "Media command failed.", false) }
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

    private fun describe(tracks: Tracks): List<PamMediaTrack> = buildList {
        tracks.groups.forEachIndexed { index, group ->
            val type = when (group.type) {
                C.TRACK_TYPE_AUDIO -> "audio"
                C.TRACK_TYPE_TEXT -> "text"
                else -> return@forEachIndexed
            }
            for (track in 0 until group.length) {
                if (!group.isTrackSupported(track)) continue
                val format = group.getTrackFormat(track)
                add(PamMediaTrack("$index:$track", type, format.language.orEmpty(), format.label.orEmpty(), group.isTrackSelected(track)))
            }
        }
    }

    private fun applyChoices(current: ExoPlayer) {
        val groups = current.currentTracks.groups
        fun override(id: String, type: Int): TrackSelectionOverride? {
            val parts = id.split(':').mapNotNull(String::toIntOrNull)
            if (parts.size != 2) return null
            val group = groups.getOrNull(parts[0])?.takeIf { it.type == type && parts[1] in 0 until it.length } ?: return null
            return TrackSelectionOverride(group.mediaTrackGroup, parts[1])
        }
        val audio = override(audioChoice, C.TRACK_TYPE_AUDIO)
        val text = override(textChoice, C.TRACK_TYPE_TEXT)
        val next = current.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, text == null)
            .apply {
                audio?.let { setOverrideForType(it) }
                text?.let { setOverrideForType(it) }
            }
            .build()
        if (next != current.trackSelectionParameters) current.trackSelectionParameters = next
    }

    /** [audio]/[text]: a [PamMediaTrack.id], or "" for the default audio and no subtitles. */
    fun selectTracks(audio: String, text: String) = command {
        audioChoice = audio
        textChoice = text
        applyChoices(it)
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
private const val FIRST_FRAME_FALLBACK_MS = 400L

/**
 * Media loads retry like images: 429/503/502/504/408 and network errors back
 * off exponentially with jitter (honoring `Retry-After`, which also pauses
 * the host for every image and player), up to [PLAYER_LOAD_RETRIES] times
 * before the player fails; 4xx and bad media fail at once. ExoPlayer's
 * default gave up after three retries of at most a few seconds, shorter than
 * one rate-limit window.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class PamMediaLoadErrorPolicy(
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
) : DefaultLoadErrorHandlingPolicy(PLAYER_LOAD_RETRIES) {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val error = loadErrorInfo.exception
        val attempt = (loadErrorInfo.errorCount - 1).coerceAtLeast(0)
        mediaHttpFailure(error)?.let { failure ->
            return mediaRetryDelayMs(failure, attempt, random()) ?: C.TIME_UNSET
        }
        causeOf<MediaHostPausedException>(error)?.let { paused ->
            return mediaRetryDelayMs(paused, attempt, random()) ?: C.TIME_UNSET
        }
        if (causeOf<HttpDataSource.HttpDataSourceException>(error) != null) {
            return mediaBackoffMs(attempt, random())
        }
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    /** A rate-limited or overloaded server is retried, never excluded as a broken track or location. */
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? {
        val status = causeOf<HttpDataSource.InvalidResponseCodeException>(loadErrorInfo.exception)?.responseCode
        if (status != null && status in MEDIA_TRANSIENT_STATUS) return null
        return super.getFallbackSelectionFor(fallbackOptions, loadErrorInfo)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = PLAYER_LOAD_RETRIES
}

/** The HTTP status of a failed media load as a [MediaHttpException] (pausing the host on 429/503). */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun mediaHttpFailure(error: Throwable?): MediaHttpException? {
    val response = causeOf<HttpDataSource.InvalidResponseCodeException>(error) ?: return null
    val retryAfter = parseRetryAfterMs(
        // The status line is the entry with a null name.
        response.headerFields.entries
            .firstOrNull { entry -> (entry.key as String?)?.equals("Retry-After", ignoreCase = true) == true }
            ?.value?.firstOrNull(),
    )
    if (response.responseCode in MEDIA_HOST_PAUSE_STATUS) {
        PamMediaHostGate.pause(response.dataSpec.uri.host, retryAfter)
    }
    return MediaHttpException(response.responseCode, retryAfter)
}

/** Whether a failed player is worth preparing again later (network or rate limit, not 4xx or bad media). */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal fun isTransientPlaybackError(error: Throwable?): Boolean {
    causeOf<HttpDataSource.InvalidResponseCodeException>(error)?.let { return it.responseCode in MEDIA_TRANSIENT_STATUS }
    if (causeOf<MediaHostPausedException>(error) != null) return true
    if (causeOf<HttpDataSource.HttpDataSourceException>(error) != null) return true
    return (error as? PlaybackException)?.errorCode in setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
    )
}

internal inline fun <reified T : Throwable> causeOf(error: Throwable?): T? {
    var current = error
    var depth = 0
    while (current != null && depth++ < 8) {
        if (current is T) return current
        current = current.cause?.takeIf { it !== current }
    }
    return null
}

internal const val PLAYER_LOAD_RETRIES = 8
