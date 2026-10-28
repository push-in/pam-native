package dev.pam.nativeapp

import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Reduces root/dialog IME insets to settled keyboard changes; animation
 * frames never cross into PHP. Visibility transitions publish at once with
 * the best known height; a height-only change (suggestion strip, emoji
 * keyboard, a surface reporting its height after its visibility) publishes
 * once after [SETTLE_MS] without further changes.
 */
internal class KeyboardVisibility(
    private val scheduler: Scheduler = MainScheduler(),
    private val changed: (visible: Boolean, heightDp: Float) -> Unit,
) {
    /** Deferred work for height settling (tests drive it by hand). */
    interface Scheduler {
        fun post(delayMs: Long, task: Runnable)
        fun cancel(task: Runnable)
    }

    private class MainScheduler : Scheduler {
        private val main = Handler(Looper.getMainLooper())
        override fun post(delayMs: Long, task: Runnable) { main.postDelayed(task, delayMs) }
        override fun cancel(task: Runnable) { main.removeCallbacks(task) }
    }

    private val visibleSurfaces = mutableSetOf<Long>()
    private val heights = LinkedHashMap<Long, Float>()
    private var visible = false
    private var publishedHeight = 0f
    private var active = true
    private val settle = Runnable { publishHeight() }
    private var settlePending = false

    fun update(surface: Long, bottom: Float) {
        if (!active) return
        if (bottom > 0f) {
            visibleSurfaces.add(surface)
        } else {
            visibleSurfaces.remove(surface)
            // A closed dialog's keyboard must not size a later one.
            heights.remove(surface)
        }
        publish(visibleSurfaces.isNotEmpty())
    }

    /** Settled IME height in dp over [surface] (0 when it has none). */
    fun updateHeight(surface: Long, heightDp: Float) {
        if (!active) return
        heights.remove(surface)
        if (heightDp.isFinite() && heightDp > 0f) heights[surface] = heightDp
        if (visible && abs(currentHeight() - publishedHeight) >= 0.5f) scheduleHeight()
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) {
            visibleSurfaces.clear()
            heights.clear()
            publish(false)
        }
    }

    fun publishCurrent() {
        cancelSettle()
        publishedHeight = if (visible) currentHeight() else 0f
        changed(visible, publishedHeight)
    }

    /** The most recently reported height among surfaces with a visible keyboard. */
    private fun currentHeight(): Float {
        if (!visible) return 0f
        var height = 0f
        for ((surface, value) in heights) if (surface in visibleSurfaces) height = value
        if (height == 0f) height = heights.values.lastOrNull() ?: 0f
        return round1(height)
    }

    private fun publish(value: Boolean) {
        if (visible == value) return
        visible = value
        cancelSettle()
        publishedHeight = currentHeight()
        changed(value, publishedHeight)
    }

    private fun scheduleHeight() {
        cancelSettle()
        settlePending = true
        scheduler.post(SETTLE_MS, settle)
    }

    private fun cancelSettle() {
        if (!settlePending) return
        settlePending = false
        scheduler.cancel(settle)
    }

    private fun publishHeight() {
        settlePending = false
        if (!active || !visible) return
        val height = currentHeight()
        if (abs(height - publishedHeight) < 0.5f) return
        publishedHeight = height
        changed(true, height)
    }

    internal companion object {
        const val SETTLE_MS = 80L

        fun round1(value: Float): Float = (value * 10f).roundToInt() / 10f

        /** `EventKind.KEYBOARD_VISIBILITY` payload: `<visible>;<heightDp>;<durationMs>`. */
        fun payload(visible: Boolean, heightDp: Float, durationMs: Int = 0): ByteArray =
            "${if (visible) 1 else 0};${round1(if (visible) heightDp else 0f)};${durationMs.coerceAtLeast(0)}"
                .toByteArray()
    }
}
