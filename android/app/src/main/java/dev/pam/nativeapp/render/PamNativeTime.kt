package dev.pam.nativeapp.render

import android.graphics.Rect
import android.icu.text.BreakIterator
import android.os.SystemClock
import android.text.NoCopySpan
import android.text.SpannableString
import android.text.Spanned
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import dev.pam.nativeapp.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** `ClockFormat` (PHP authority): 1 `m:ss` .. 4 whole seconds. */
internal object PamClockFormat {
    const val MINUTES_SECONDS = 1
    const val PADDED_MINUTES_SECONDS = 2
    const val HOURS_MINUTES_SECONDS = 3
    const val SECONDS = 4

    fun format(format: Int, wholeSeconds: Long): String {
        val total = wholeSeconds.coerceAtLeast(0)
        val hours = total / 3_600
        val minutes = total % 3_600 / 60
        val seconds = total % 60
        return when (format) {
            PADDED_MINUTES_SECONDS -> two(total / 60) + ":" + two(seconds)
            HOURS_MINUTES_SECONDS -> "$hours:" + two(minutes) + ":" + two(seconds)
            SECONDS -> total.toString()
            else -> if (hours > 0) "$hours:" + two(minutes) + ":" + two(seconds) else "$minutes:" + two(seconds)
        }
    }

    private fun two(value: Long): String = if (value < 10) "0$value" else value.toString()
}

/**
 * A PHP-owned time source: [offsetMs] already counted plus, while
 * [running], the time since [startedAt] (epoch ms). The renderer only
 * reads it at each frame; PHP changes it to start, pause or move it.
 */
internal data class PamTimeSource(val startedAt: Long?, val offsetMs: Long, val running: Boolean) {
    val advancing: Boolean get() = running && startedAt != null

    fun elapsedAt(nowMs: Long): Long {
        val base = offsetMs.coerceAtLeast(0)
        val start = startedAt ?: return base
        return if (running) base + (nowMs - start).coerceAtLeast(0) else base
    }
}

/**
 * `clock*` on a Text: counts up from its source, or down from [durationMs]
 * (pausable) or to [endsAt] (a deadline, always running). Counting up shows
 * whole seconds elapsed; counting down shows the seconds left rounded up.
 */
internal data class PamClockSpec(
    val source: PamTimeSource,
    val durationMs: Long?,
    val endsAt: Long?,
    val format: Int,
) {
    val countsDown: Boolean get() = endsAt != null || durationMs != null

    /** Milliseconds shown at [nowMs]: elapsed, or left for a countdown. */
    fun valueAt(nowMs: Long): Long = when {
        endsAt != null -> (endsAt - nowMs).coerceAtLeast(0)
        durationMs != null -> (durationMs - source.elapsedAt(nowMs)).coerceAtLeast(0)
        else -> source.elapsedAt(nowMs)
    }

    fun label(nowMs: Long): String {
        val value = valueAt(nowMs)
        return PamClockFormat.format(format, if (countsDown) (value + 999) / 1_000 else value / 1_000)
    }

    /** Milliseconds until [label] changes, or -1 when it no longer changes. */
    fun nextChangeIn(nowMs: Long): Long {
        if (endsAt == null) {
            if (!source.advancing) return -1
            val start = source.startedAt ?: return -1
            if (start > nowMs) return start - nowMs
        }
        val value = valueAt(nowMs)
        if (!countsDown) return 1_000 - value % 1_000
        if (value <= 0) return -1
        val part = value % 1_000
        return if (part == 0L) 1_000 else part
    }
}

/** `progress*`: the fill fraction `elapsed / durationMs` of its source. */
internal data class PamProgressSpec(val source: PamTimeSource, val durationMs: Long) {
    fun fractionAt(nowMs: Long): Float {
        if (durationMs <= 0) return 0f
        return (source.elapsedAt(nowMs).toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
    }

    fun movingAt(nowMs: Long): Boolean =
        source.advancing && durationMs > 0 && source.elapsedAt(nowMs) < durationMs
}

/**
 * `reveal*`: one grapheme every [charMs] (or the whole text over
 * [durationMs]); a new [key] starts over.
 */
internal data class PamRevealSpec(val charMs: Long, val durationMs: Long, val key: String?) {
    private fun stepMs(count: Int): Double =
        if (charMs > 0) charMs.toDouble() else durationMs.toDouble() / max(1, count)

    /** Graphemes shown after [elapsedMs] of a [count]-grapheme text. */
    fun shown(elapsedMs: Long, count: Int): Int {
        if (count <= 0) return 0
        val step = stepMs(count)
        if (step <= 0.0) return count
        return min(count.toDouble(), floor(elapsedMs / step)).toInt()
    }

    fun nextChangeIn(elapsedMs: Long, count: Int): Long {
        val shown = shown(elapsedMs, count)
        if (shown >= count) return -1
        return max(1L, ceil((shown + 1) * stepMs(count) - elapsedMs).toLong())
    }
}

internal object PamNativeTime {
    /**
     * [text] styled like the renderer's [source]: the spans covering its first
     * character (line height, per-run style) cover the whole label.
     */
    fun restyle(text: String, source: CharSequence?): CharSequence {
        if (source !is Spanned || source.isEmpty() || text.isEmpty()) return text
        val spans = source.getSpans(0, 1, Any::class.java)
        if (spans.isEmpty()) return text
        val styled = SpannableString(text)
        for (span in spans) {
            if (span is NoCopySpan || source.getSpanStart(span) != 0) continue
            styled.setSpan(span, 0, text.length, source.getSpanFlags(span) and Spanned.SPAN_PRIORITY.inv())
        }
        return styled
    }

    /** UTF-16 end offset of each grapheme of [text]. */
    fun graphemeEnds(text: CharSequence): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text.toString())
        val ends = ArrayList<Int>(text.length)
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            ends += end
            end = iterator.next()
        }
        return ends.toIntArray()
    }
}

/**
 * Drives one view's native time primitives (`clock*`, `progress*`,
 * `reveal*`) from PHP-owned values, with no PHP render per second, frame or
 * character.
 *
 * It works only while the view is on screen: attached, shown (no hidden
 * ancestor, so not a covered route or inactive tab), in a visible window and
 * inside it. A clock wakes once per change of its label (1/s), a fill every
 * frame (every second with reduced motion, slower when a pixel takes longer
 * than two frames), a reveal once per character. A tick that finds the view
 * off screen stops; the next drawn frame of the window (a route coming back,
 * a scroll, the app returning) checks again through a pre-draw listener held
 * only while waiting. Clocks and fills read the wall clock, so they are
 * right again at once; a reveal only advances while it is seen.
 */
internal class PamTimeBinding private constructor(private val view: View) :
    Runnable,
    View.OnAttachStateChangeListener,
    View.OnLayoutChangeListener,
    ViewTreeObserver.OnPreDrawListener {
    var clock: PamClockSpec? = null
        private set
    var progress: PamProgressSpec? = null
        private set
    var reveal: PamRevealSpec? = null
        private set

    private var pending = false
    private var watching: ViewTreeObserver? = null
    private var reduced = false
    private var revealElapsedMs = 0L
    private var revealLastTick = -1L
    private var graphemeSource: CharSequence? = null
    private var graphemes = IntArray(0)
    private val visibleRect = Rect()

    /** Scheduled ticks that ran on screen (tests, diagnostics). */
    internal var ticks = 0
        private set

    /** A tick is posted (false while suspended off screen or at rest). */
    internal val isTicking: Boolean get() = pending

    /** Waiting for the view to come back on screen. */
    internal val isWatching: Boolean get() = watching != null

    private fun update(clock: PamClockSpec?, progress: PamProgressSpec?, reveal: PamRevealSpec?) {
        if (clock == this.clock && progress == this.progress && reveal == this.reveal) return
        val hadProgress = this.progress != null
        if (reveal == null || this.reveal == null || reveal.key != this.reveal?.key) {
            revealElapsedMs = 0
            revealLastTick = -1
        }
        this.clock = clock
        this.progress = progress
        this.reveal = reveal
        if (clock == null) (view as? PamTextView)?.nativeText = null
        if (reveal == null) (view as? PamTextView)?.revealEnd = -1
        if (hadProgress && progress == null) {
            view.removeOnLayoutChangeListener(this)
            view.scaleX = 1f
        } else if (!hadProgress && progress != null) {
            view.addOnLayoutChangeListener(this)
        }
        if (clock == null && progress == null && reveal == null) {
            cancel()
            unwatch()
            return
        }
        refresh()
    }

    /** Applies the current values now and (re)starts ticking if on screen. */
    fun refresh() {
        cancel()
        reduced = PamMotionPolicy.isReduced(view.context)
        if (showing()) {
            unwatch()
            ticks++
            schedule(apply(true))
        } else {
            revealLastTick = -1
            // Only something that still moves waits for the screen.
            if (apply(false) >= 0) watch()
        }
    }

    override fun run() {
        pending = false
        if (!showing()) {
            revealLastTick = -1
            watch()
            return
        }
        ticks++
        schedule(apply(true))
    }

    override fun onPreDraw(): Boolean {
        if (showing()) {
            unwatch()
            if (!pending) {
                ticks++
                schedule(apply(true))
            }
        }
        return true
    }

    override fun onViewAttachedToWindow(v: View) = refresh()

    override fun onViewDetachedFromWindow(v: View) {
        cancel()
        unwatch()
        revealLastTick = -1
    }

    override fun onLayoutChange(
        v: View, left: Int, top: Int, right: Int, bottom: Int,
        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
    ) {
        if (progress != null) view.pivotX = leadingPivot()
    }

    private fun close() {
        update(null, null, null)
        cancel()
        unwatch()
        view.removeOnAttachStateChangeListener(this)
        view.setTag(R.id.pam_time_binding, null)
    }

    /** Sets the values at [nowMs]; returns the delay to the next change, -1 for none. */
    private fun apply(onScreen: Boolean): Long {
        val now = System.currentTimeMillis()
        var next = -1L
        clock?.let { clock ->
            val label = clock.label(now)
            when (val text = view) {
                is PamTextView -> text.nativeText = label
                is TextView -> if (!text.text.contentEquals(label)) text.text = label
            }
            next = soonest(next, clock.nextChangeIn(now))
        }
        progress?.let { progress ->
            view.pivotX = leadingPivot()
            val fraction = progress.fractionAt(now)
            if (view.scaleX != fraction) view.scaleX = fraction
            if (progress.movingAt(now)) next = soonest(next, progressStep(progress))
        }
        reveal?.let { reveal ->
            val text = view as? PamTextView ?: return@let
            if (reduced) {
                text.revealEnd = -1
                return@let
            }
            if (onScreen) {
                val tick = SystemClock.uptimeMillis()
                if (revealLastTick >= 0) revealElapsedMs += tick - revealLastTick
                revealLastTick = tick
            }
            val content = text.text ?: ""
            if (content !== graphemeSource) {
                graphemeSource = content
                graphemes = PamNativeTime.graphemeEnds(content)
            }
            val shown = reveal.shown(revealElapsedMs, graphemes.size)
            text.revealEnd = when {
                shown >= graphemes.size -> -1
                shown == 0 -> 0
                else -> graphemes[shown - 1]
            }
            next = soonest(next, reveal.nextChangeIn(revealElapsedMs, graphemes.size))
        }
        return next
    }

    /** Frame by frame, unless a device pixel of the fill takes over two frames. */
    private fun progressStep(progress: PamProgressSpec): Long {
        if (reduced) return 1_000
        val msPerPixel = progress.durationMs / max(1, view.width)
        return if (msPerPixel >= 33) msPerPixel / 2 else 0
    }

    private fun leadingPivot(): Float =
        if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) view.width.toFloat() else 0f

    private fun showing(): Boolean {
        if (!view.isAttachedToWindow || view.windowVisibility != View.VISIBLE || !view.isShown) return false
        // A fill's own scale (0 at the start) must not hide it: test its track.
        val probe = if (progress != null) view.parent as? View ?: view else view
        return probe.getGlobalVisibleRect(visibleRect)
    }

    private fun schedule(delay: Long) {
        if (delay < 0) return
        pending = true
        if (delay == 0L) view.postOnAnimation(this) else view.postOnAnimationDelayed(this, delay)
    }

    private fun cancel() {
        if (pending) view.removeCallbacks(this)
        pending = false
    }

    private fun watch() {
        if (watching != null || !view.isAttachedToWindow) return
        val observer = view.viewTreeObserver
        if (!observer.isAlive) return
        observer.addOnPreDrawListener(this)
        watching = observer
    }

    private fun unwatch() {
        val observer = watching ?: return
        watching = null
        if (observer.isAlive) observer.removeOnPreDrawListener(this)
    }

    companion object {
        fun of(view: View): PamTimeBinding? = view.getTag(R.id.pam_time_binding) as? PamTimeBinding

        /** Binds (or with all null, releases) the primitives of [view]. */
        fun sync(view: View, clock: PamClockSpec?, progress: PamProgressSpec?, reveal: PamRevealSpec?) {
            val current = of(view)
            if (clock == null && progress == null && reveal == null) {
                current?.close()
                return
            }
            val binding = current ?: PamTimeBinding(view).also {
                view.setTag(R.id.pam_time_binding, it)
                view.addOnAttachStateChangeListener(it)
            }
            binding.update(clock, progress, reveal)
        }

        /** Releases [view]'s primitives (node removed or recycled). */
        fun clear(view: View) {
            of(view)?.close()
        }

        private fun soonest(a: Long, b: Long): Long = when {
            a < 0 -> b
            b < 0 -> a
            else -> min(a, b)
        }
    }
}
