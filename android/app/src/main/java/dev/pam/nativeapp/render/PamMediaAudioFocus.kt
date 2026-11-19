package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Audio focus of a video player with sound (expo-video's `auto` mixing): a
 * playing player with sound holds focus, a muted or paused one does not.
 * Another app taking focus for good (music, a video) or headphones being
 * unplugged pause the player ([Listener.pause]); a transient loss (a call,
 * a navigation prompt) holds it until focus comes back ([Listener.hold]).
 * Ducking is left to the system (Android 8+ lowers media itself).
 *
 * The app's players share one focus request, so two PAM players with sound
 * never take the focus from each other. Main thread.
 */
internal class PamMediaAudioFocus(context: Context, private val listener: Listener) {
    interface Listener {
        /** Focus lost for good or audio becoming noisy: stop the play intent. */
        fun pause()

        /** Playback may (true) or may not (false: transient loss, delayed grant) run now. */
        fun hold(held: Boolean)
    }

    private val appContext = context.applicationContext

    /** This player asked for focus and has not abandoned it. */
    var requested = false
        private set

    /** Asks for focus once; true when playback may start now. */
    fun acquire(): Boolean {
        if (requested) return true
        requested = true
        return Shared.join(this)
    }

    fun abandon() {
        if (!requested) return
        requested = false
        Shared.leave(this)
    }

    // Holds only the application context (never an Activity); players leave
    // [holders] when they release, so no view outlives its player here.
    @SuppressLint("StaticFieldLeak")
    private object Shared {
        private val main = Handler(Looper.getMainLooper())
        private val holders = LinkedHashSet<PamMediaAudioFocus>()
        /** The application context (never an Activity), for the focus and noisy receiver. */
        private var context: Context? = null
        private var request: AudioFocusRequest? = null
        private var granted = false
        private var delayed = false
        private var transientLoss = false

        private val changes = AudioManager.OnAudioFocusChangeListener { change ->
            if (Looper.myLooper() == Looper.getMainLooper()) changed(change) else main.post { changed(change) }
        }

        private var noisyRegistered = false
        private val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) lost()
            }
        }

        fun join(holder: PamMediaAudioFocus): Boolean {
            holders += holder
            if (granted) return !transientLoss
            if (delayed) return false
            context = holder.appContext
            val audio = holder.appContext.getSystemService(AudioManager::class.java)
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focus = request ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(changes, main)
                    .build()
                    .also { request = it }
                audio.requestAudioFocus(focus)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(changes, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
            return when (result) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                    granted = true
                    transientLoss = false
                    registerNoisy()
                    true
                }
                // A call is ringing: players start when it ends (AUDIOFOCUS_GAIN).
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                    delayed = true
                    false
                }
                // Android 7 without a grant (a call in progress): play as before
                // rather than stay stuck; the next player asks again.
                else -> Build.VERSION.SDK_INT < Build.VERSION_CODES.O
            }
        }

        fun leave(holder: PamMediaAudioFocus) {
            holders -= holder
            if (holders.isEmpty()) release()
        }

        private fun release() {
            val audio = context?.getSystemService(AudioManager::class.java)
            if (granted || delayed) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    request?.let { audio?.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audio?.abandonAudioFocus(changes)
                }
            }
            granted = false
            delayed = false
            transientLoss = false
            unregisterNoisy()
        }

        private fun changed(change: Int) {
            if (!granted && !delayed) return
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    granted = true
                    delayed = false
                    transientLoss = false
                    registerNoisy()
                    holders.toList().forEach { it.listener.hold(true) }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    transientLoss = true
                    holders.toList().forEach { it.listener.hold(false) }
                }
                AudioManager.AUDIOFOCUS_LOSS -> lost()
                // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system ducks media on 8+; keep playing.
            }
        }

        /** Focus gone for good, or headphones unplugged: every player pauses. */
        private fun lost() {
            val paused = holders.toList()
            holders.clear()
            release()
            paused.forEach {
                it.requested = false
                it.listener.pause()
            }
        }

        private fun registerNoisy() {
            val context = context ?: return
            if (noisyRegistered) return
            noisyRegistered = true
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(noisy, filter)
            }
        }

        private fun unregisterNoisy() {
            if (!noisyRegistered) return
            noisyRegistered = false
            runCatching { context?.unregisterReceiver(noisy) }
        }
    }
}
