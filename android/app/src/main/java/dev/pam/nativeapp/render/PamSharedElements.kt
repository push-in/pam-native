package dev.pam.nativeapp.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.view.View
import android.view.ViewGroup
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How media content sits in its view: the image scale type or the video
 * resize mode. Values follow `resizeMode` (1 cover, 2 contain, 3 fill).
 */
internal object PamSharedFit {
    const val COVER = 1
    const val CONTAIN = 2
    const val FILL = 3
}

/**
 * A view that draws media (an image, a video frame). A shared-element flight
 * draws its whole, uncropped content and morphs the crop, so a cover
 * thumbnail grows into a contained full-screen view without stretching.
 */
internal interface PamSharedContentView {
    /** [PamSharedFit] of the content in this view, null when not supported. */
    val sharedFit: Int?

    /** True when content is drawn (decoded image, rendered frame or poster). */
    val hasSharedContent: Boolean

    /**
     * A drawable of the whole content the flight may resize freely (never the
     * view's own instance). [PamSharedContent.owned] is recycled afterwards.
     */
    fun sharedContent(): PamSharedContent?

    /**
     * Shows [content] (the picture that just flew in) until this view draws
     * its own media: the decoded image or poster replaces it, or the first
     * video frame covers it. False when the view already draws or cannot
     * take it; the flight then stays on top until the view draws.
     */
    fun adoptSharedPlaceholder(content: PamSharedContent): Boolean = false
}

internal class PamSharedContent(
    val drawable: Drawable,
    val width: Int,
    val height: Int,
    val owned: Bitmap? = null,
)

/** A content drawable the flight can resize, for an image view's drawable. */
internal fun sharedDrawableCopy(view: View, current: Drawable?): PamSharedContent? {
    val drawable = (current as? TransitionDrawable)
        ?.let { it.getDrawable(it.numberOfLayers - 1) }
        ?: current
        ?: return null
    val width = drawable.intrinsicWidth
    val height = drawable.intrinsicHeight
    if (width <= 0 || height <= 0) return null
    val copy = drawable.constantState?.newDrawable(view.resources)?.mutate() ?: return null
    return PamSharedContent(copy, width, height)
}

/** Pure geometry of a shared-element flight (unit tested on the JVM). */
internal object PamSharedGeometry {
    /** Rect of content [cw]x[ch] fitted in a [w]x[h] box: left, top, right, bottom. */
    fun fit(w: Float, h: Float, cw: Float, ch: Float, fit: Int): FloatArray {
        if (w <= 0f || h <= 0f || cw <= 0f || ch <= 0f || fit == PamSharedFit.FILL) {
            return floatArrayOf(0f, 0f, w, h)
        }
        val scale = if (fit == PamSharedFit.CONTAIN) min(w / cw, h / ch) else max(w / cw, h / ch)
        val dw = cw * scale
        val dh = ch * scale
        val left = (w - dw) / 2f
        val top = (h - dh) / 2f
        return floatArrayOf(left, top, left + dw, top + dh)
    }

    /** The visible part: the box itself, or the letterboxed content when contained. */
    fun clip(w: Float, h: Float, content: FloatArray, fit: Int): FloatArray =
        if (fit == PamSharedFit.CONTAIN) {
            floatArrayOf(
                max(0f, content[0]),
                max(0f, content[1]),
                min(w, content[2]),
                min(h, content[3]),
            )
        } else {
            floatArrayOf(0f, 0f, w, h)
        }

    fun lerp(from: Float, to: Float, progress: Float): Float = from + (to - from) * progress

    /** What moves a point at [from] (x, y on screen) to the same place relative to [to]. */
    fun delta(from: IntArray, to: IntArray): Pair<Float, Float> =
        (from[0] - to[0]).toFloat() to (from[1] - to[1]).toFloat()

    /**
     * Eased value at [progress] (linear time, 0..1) of a [durationMs] flight.
     * 3 is a damped spring (damping ratio, stiffness, mass) whose overshoot is
     * clamped and which lands exactly at the end; 4 is a cubic ease-in.
     */
    fun ease(progress: Float, easing: Int, damping: Float, stiffness: Float, mass: Float, durationMs: Long = 500L): Float {
        val t = progress.coerceIn(0f, 1f)
        return when (easing) {
            1 -> t
            3 -> if (t >= 1f) 1f else spring(t * durationMs / 1000f, damping, stiffness, mass).coerceIn(0f, 1f)
            4 -> t * t * t
            5 -> 1f - (1f - t) * (1f - t) * (1f - t)
            else -> t * t * (3f - 2f * t)
        }
    }

    /** Unit step response of a damped spring at [seconds] (Reanimated/RN spring physics). */
    fun spring(seconds: Float, damping: Float, stiffness: Float, mass: Float): Float {
        val omega = sqrt(stiffness / mass)
        val zeta = damping.coerceIn(0.01f, 1f)
        if (zeta >= 0.999f) return 1f - exp(-omega * seconds) * (1f + omega * seconds)
        val damped = omega * sqrt(1f - zeta * zeta)
        val envelope = exp(-zeta * omega * seconds)
        return 1f - envelope * (cos(damped * seconds) + zeta * omega / damped * kotlin.math.sin(damped * seconds))
    }
}

/**
 * Shared-element flights of one navigation transition. Each tag present on
 * both routes flies from its source frame to its destination frame in the
 * host overlay, on the UI thread animator: no PHP per frame, no layout.
 *
 * Media flights (image or video in the tagged view) draw the whole content
 * once and interpolate its rect, the visible crop and the corner radius, so
 * a rounded cover thumbnail becomes a contained full-screen view. Other
 * views fly as a snapshot of their pixels. Only views that are on screen
 * take part: a tag whose view scrolled away (or sits on an off-screen page)
 * has no flight and the route transition alone runs.
 */
internal class PamSharedElements(private val host: ViewGroup) {
    data class Config(
        val durationMs: Long = 500L,
        val easing: Int = 2,
        val resizeMode: Int = 1,
        val crossFade: Boolean = true,
        val damping: Float = 0.82f,
        val stiffness: Float = 220f,
        val mass: Float = 1f,
        val returnDurationMs: Long? = null,
        val returnEasing: Int? = null,
        /** Starts flying on the tap itself, toward a predicted full-screen frame. */
        val launch: Boolean = false,
        val launchBackdrop: Int = android.graphics.Color.BLACK,
    ) {
        /** The curve of a flight back home (the element's route is popped). */
        fun returning(): Config = copy(
            durationMs = returnDurationMs ?: durationMs,
            easing = returnEasing ?: easing,
        )
    }

    private class Side(val clip: RectF, val content: RectF, val radius: Float) {
        fun shifted(dx: Float, dy: Float): Side = Side(
            RectF(clip).apply { offset(dx, dy) },
            RectF(content).apply { offset(dx, dy) },
            radius,
        )
    }

    private class Flight(
        val tag: String,
        val source: View,
        val destination: View,
        val sourceAlpha: Float,
        val destinationAlpha: Float,
        val start: Side,
        val end: Side,
        val view: FlightView,
        val owned: MutableList<Bitmap>,
        val destinationContent: PamSharedContentView?,
        val config: Config,
        val content: PamSharedContent? = null,
    )

    private val flights = ArrayList<Flight>(2)
    private val holding = ArrayList<Flight>(2)

    val isEmpty: Boolean get() = flights.isEmpty()
    val count: Int get() = flights.size
    val durationMs: Long get() = flights.maxOfOrNull { it.config.durationMs } ?: 0L

    /** Eased progress of the longest flight, for a route fade that follows it. */
    fun eased(progress: Float): Float {
        val config = flights.maxByOrNull { it.config.durationMs }?.config ?: return progress
        return PamSharedGeometry.ease(progress, config.easing, config.damping, config.stiffness, config.mass, config.durationMs)
    }

    /**
     * The view each tag flew from on the last opening flight (a push, a modal
     * opening). The flight home prefers it while it is still on screen, so a
     * tag shown more than once returns to the instance it came from even when
     * the last touch was elsewhere (a close button).
     */
    private val homes = HashMap<String, java.lang.ref.WeakReference<View>>()

    private fun pick(candidates: List<View>?, route: View, minimumShare: Float, home: View? = null): View? {
        val visible = candidates?.filter { onScreen(it, route, minimumShare) }.orEmpty()
        if (home != null && home in visible) return home
        val x = touchX
        val y = touchY
        if (visible.size <= 1 || x.isNaN()) return visible.firstOrNull()
        return visible.filter { hits(it, x, y) }.minByOrNull(::visibleArea) ?: visible.first()
    }

    private fun remember(tag: String, source: View) {
        if (homes.size >= MAX_HOMES) homes.entries.removeAll { it.value.get()?.isAttachedToWindow != true }
        if (homes.size >= MAX_HOMES) homes.clear()
        homes[tag] = java.lang.ref.WeakReference(source)
    }

    /** The point (screen coordinates) is on the visible, clipped part of [view]. */
    private fun hits(view: View, x: Float, y: Float): Boolean {
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible)) return false
        val origin = IntArray(2)
        view.rootView.getLocationOnScreen(origin)
        visible.offset(origin[0], origin[1])
        return visible.contains(x.toInt(), y.toInt())
    }

    private fun visibleArea(view: View): Long {
        val visible = Rect()
        return if (view.getGlobalVisibleRect(visible)) visible.width().toLong() * visible.height() else Long.MAX_VALUE
    }

    fun prepare(incoming: View, outgoing: View, defaultDurationMs: Long, popping: Boolean = false) {
        clear()
        val launched = launch?.takeIf { !popping && it.source.isAttachedToWindow && isInside(it.source, outgoing) }
        launch?.takeIf { it !== launched }?.let(::returnLaunch)
        launch = null
        val destinations = taggedViews(incoming)
        if (launched != null) handOff(launched, destinations, incoming)
        val sources = taggedViews(outgoing)
        if (sources.isEmpty()) return
        for ((tag, candidates) in sources) {
            if (flights.size >= 16) break
            if (tag == launched?.tag) continue
            val source = pick(candidates, outgoing, minimumShare = 0.5f) ?: continue
            val home = if (popping) homes[tag]?.get() else null
            val destination = pick(destinations[tag], incoming, minimumShare = 0f, home) ?: continue
            if (!popping) remember(tag, source)
            val top = if (popping) source else destination
            val below = if (popping) destination else source
            val styled = config(top) ?: config(below)
            val config = when {
                styled == null -> Config(durationMs = defaultDurationMs.coerceIn(0L, 2_000L))
                popping -> styled.returning()
                else -> styled
            }
            createFlight(tag, source, destination, config)?.let(flights::add)
        }
        flights.forEach {
            it.source.alpha = 0f
            it.destination.alpha = 0f
            host.overlay.add(it.view)
        }
    }

    /**
     * Alpha the incoming route already shows through a launch backdrop: its
     * fade continues from there instead of starting at 0.
     */
    var fadeFloor = 0f
        private set

    private class Launch(
        val tag: String,
        val source: View,
        val sourceAlpha: Float,
        val start: Side,
        val predicted: Side,
        val view: FlightView,
        val backdrop: View,
        val content: PamSharedContent,
        val owned: MutableList<Bitmap>,
        val config: Config,
        val startedAt: Long,
    ) {
        var animator: android.animation.ValueAnimator? = null
        var eased = 0f
    }

    private var launch: Launch? = null
    private val launchTimeout = Runnable { launch?.let(::returnLaunch); launch = null }

    /**
     * A tap on an element whose style says `launch` starts its flight on
     * this very frame, toward the frame it will most likely land on (the
     * media contain-fit on the whole screen) over a fading backdrop, before
     * the app has even handled the tap. When the route it opens is pushed,
     * the flight is handed to the real destination ([prepare]); if no route
     * comes, it flies back home.
     */
    fun launchFromPress(route: View, pressed: View): Boolean {
        if (launch != null || flights.isNotEmpty() || !isInside(pressed, route)) return false
        // The launch element inside the pressed view (or the view itself):
        // the exact thumbnail that was tapped, no geometry guess.
        val source = taggedViews(pressed).entries.asSequence()
            .flatMap { (tag, views) -> views.asSequence().map { tag to it } }
            .firstOrNull { (_, view) -> config(view)?.launch == true && onScreen(view, route, 0.5f) } ?: return false
        val (tag, view) = source
        val config = config(view) ?: return false
        val media = contentView(view) ?: return false
        val fit = media.sharedFit ?: return false
        val content = media.sharedContent() ?: return false
        val start = side(view, media as View, fit, content) ?: run {
            content.owned?.takeIf { !it.isRecycled }?.recycle()
            return false
        }
        val fitted = PamSharedGeometry.fit(
            host.width.toFloat(), host.height.toFloat(), content.width.toFloat(), content.height.toFloat(), PamSharedFit.CONTAIN,
        )
        val box = RectF(fitted[0], fitted[1], fitted[2], fitted[3])
        val predicted = Side(RectF(box), RectF(box), 0f)
        val flightView = FlightView(host, content.drawable, content.width, content.height, null)
        val backdrop = View(host.context).apply {
            setBackgroundColor(config.launchBackdrop)
            alpha = 0f
            layout(0, 0, host.width, host.height)
        }
        val started = Launch(
            tag, view, view.alpha, start, predicted, flightView, backdrop, content,
            mutableListOf<Bitmap>().apply { content.owned?.let(::add) }, config, android.os.SystemClock.uptimeMillis(),
        )
        host.overlay.add(backdrop)
        host.overlay.add(flightView)
        flightView.set(start, predicted, 0f)
        view.alpha = 0f
        launch = started
        launchOwner = java.lang.ref.WeakReference(this)
        started.animator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = config.durationMs.coerceIn(1L, 2_000L)
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val eased = PamSharedGeometry.ease(
                    it.animatedValue as Float, config.easing, config.damping, config.stiffness, config.mass, config.durationMs,
                )
                started.eased = eased
                flightView.set(start, predicted, eased)
                backdrop.alpha = eased.coerceIn(0f, 1f)
            }
            start()
        }
        host.removeCallbacks(launchTimeout)
        host.postDelayed(launchTimeout, config.durationMs + LAUNCH_WAIT_MS)
        return true
    }

    /**
     * Takes over a flight launched on a tap in another host (the screen under
     * a Modal that the tap opened): it moves into this host's overlay, in this
     * host's coordinates, and [prepare] hands it to the destination or flies
     * it home. True when a launch is now pending here.
     */
    fun adoptLaunch(): Boolean {
        if (launch != null) return true
        val owner = launchOwner?.get()?.takeIf { it !== this } ?: return false
        val moved = owner.launch ?: return false
        owner.launch = null
        owner.host.removeCallbacks(owner.launchTimeout)
        launchOwner = java.lang.ref.WeakReference(this)
        moved.animator?.cancel()
        val (dx, dy) = screenDelta(owner.host, host)
        owner.host.overlay.remove(moved.view)
        owner.host.overlay.remove(moved.backdrop)
        moved.view.attach(host, dx, dy)
        moved.backdrop.layout(0, 0, host.width, host.height)
        host.overlay.add(moved.backdrop)
        host.overlay.add(moved.view)
        launch = Launch(
            moved.tag, moved.source, moved.sourceAlpha, moved.start.shifted(dx, dy), moved.predicted.shifted(dx, dy),
            moved.view, moved.backdrop, moved.content, moved.owned, moved.config, moved.startedAt,
        )
        return true
    }

    /** The pushed route holds the launched tag: continue from where it is to the real frame. */
    private fun handOff(launched: Launch, destinations: Map<String, List<View>>, incoming: View) {
        host.removeCallbacks(launchTimeout)
        launched.animator?.cancel()
        val destination = pick(destinations[launched.tag], incoming, 0f)
        if (destination == null) {
            returnLaunch(launched)
            return
        }
        remember(launched.tag, launched.source)
        val media = contentView(destination)
        val fit = media?.sharedFit
        val end = if (media != null && fit != null) side(destination, media as View, fit, launched.content) else null
        val target = end ?: rectInHost(destination, RectF(0f, 0f, destination.width.toFloat(), destination.height.toFloat()))
            .let { Side(RectF(it), RectF(it), cornerRadius(destination)) }
        val now = launched.view.current()
        val elapsed = android.os.SystemClock.uptimeMillis() - launched.startedAt
        val remaining = (launched.config.durationMs - elapsed).coerceIn(120L, 2_000L)
        fadeFloor = launched.backdrop.alpha
        host.overlay.remove(launched.backdrop)
        val flight = Flight(
            launched.tag, launched.source, destination, launched.sourceAlpha, destination.alpha, now, target, launched.view,
            launched.owned, media, Config(durationMs = remaining, easing = 5), launched.content,
        )
        destination.alpha = 0f
        flights += flight
    }

    /** No route took the launched element (the tap did something else): it flies back home. */
    private fun returnLaunch(launched: Launch) {
        host.removeCallbacks(launchTimeout)
        launched.animator?.cancel()
        val from = launched.view.current()
        val backdropFrom = launched.backdrop.alpha
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val value = it.animatedValue as Float
                launched.view.set(from, launched.start, value)
                launched.backdrop.alpha = backdropFrom * (1f - value)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    launched.source.alpha = launched.sourceAlpha
                    host.overlay.remove(launched.backdrop)
                    host.overlay.remove(launched.view)
                    launched.view.drop()
                    launched.owned.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                }
            })
            start()
        }
    }

    private fun isInside(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    /** Current crop and radius of every flight: left, top, right, bottom, radius (tests). */
    fun frames(): List<FloatArray> = flights.map { it.view.frame() }

    /** A finger-driven progress (back gesture): no curve, the finger is the curve. */
    fun applyTracking(progress: Float) {
        flights.forEach { it.view.set(it.start, it.end, progress.coerceIn(0f, 1f)) }
    }

    fun apply(progress: Float) {
        flights.forEach { flight ->
            val config = flight.config
            val eased = PamSharedGeometry.ease(progress, config.easing, config.damping, config.stiffness, config.mass, config.durationMs)
            flight.view.set(flight.start, flight.end, eased)
        }
    }

    /**
     * Ends the flights. A destination whose media is not drawn yet (an image
     * still decoding, a video before its poster or first frame) keeps the
     * picture that flew in as its placeholder until its own media draws, so
     * the screen never shows an empty or black box. A destination that cannot
     * take it keeps the flight on top until it draws, is removed, or the next
     * navigation starts ([HOLD_SAFETY_MS] only guards against a view that
     * never draws).
     */
    fun finish() {
        fadeFloor = 0f
        val ended = flights.toList()
        flights.clear()
        ended.forEach { flight ->
            flight.source.alpha = flight.sourceAlpha
            flight.destination.alpha = flight.destinationAlpha
            val target = flight.destinationContent
            val content = flight.content
            when {
                target == null || target.hasSharedContent -> release(flight)
                content != null && target.adoptSharedPlaceholder(content) -> {
                    // The destination owns the picture now: never recycle it.
                    content.owned?.let(flight.owned::remove)
                    release(flight)
                }
                else -> {
                    flight.view.set(flight.start, flight.end, 1f)
                    holding += flight
                }
            }
        }
        if (holding.isNotEmpty()) pollHolding(System.currentTimeMillis())
    }

    fun clear() {
        fadeFloor = 0f
        flights.forEach {
            it.source.alpha = it.sourceAlpha
            it.destination.alpha = it.destinationAlpha
            release(it)
        }
        flights.clear()
        holding.toList().forEach(::release)
        holding.clear()
    }

    private fun pollHolding(startedAt: Long) {
        host.postOnAnimation {
            val expired = System.currentTimeMillis() - startedAt >= HOLD_SAFETY_MS
            holding.toList().forEach { flight ->
                if (expired || flight.destinationContent?.hasSharedContent != false ||
                    !flight.destination.isAttachedToWindow
                ) {
                    holding.remove(flight)
                    release(flight)
                }
            }
            if (holding.isNotEmpty()) pollHolding(startedAt)
        }
    }

    private fun release(flight: Flight) {
        holding.remove(flight)
        host.overlay.remove(flight.view)
        flight.view.drop()
        flight.owned.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun createFlight(tag: String, source: View, destination: View, config: Config): Flight? {
        val sourceMedia = contentView(source)
        val destinationMedia = contentView(destination)
        val owned = ArrayList<Bitmap>(2)
        val sourceContent = sourceMedia?.sharedContent()?.also { content -> content.owned?.let(owned::add) }
        val destinationContent = if (destinationMedia?.hasSharedContent == true) {
            destinationMedia.sharedContent()?.also { content -> content.owned?.let(owned::add) }
        } else {
            null
        }
        // The sharper of the two pictures flies (a full-screen image over a
        // bubble thumbnail, a video frame over its poster).
        val content = listOfNotNull(sourceContent, destinationContent)
            .maxByOrNull { it.width.toLong() * it.height }
        if (content != null && sourceMedia != null && destinationMedia != null &&
            sourceMedia.sharedFit != null && destinationMedia.sharedFit != null
        ) {
            val start = side(source, sourceMedia as View, sourceMedia.sharedFit!!, content) ?: return null
            val end = side(destination, destinationMedia as View, destinationMedia.sharedFit!!, content) ?: return null
            val view = FlightView(host, content.drawable, content.width, content.height, null)
            return Flight(
                tag, source, destination, source.alpha, destination.alpha, start, end, view, owned,
                destinationMedia, config, content,
            )
        }
        owned.forEach { if (!it.isRecycled) it.recycle() }
        return snapshotFlight(tag, source, destination, config)
    }

    /** Any view: its pixels scale from one frame to the other (resizeMode). */
    private fun snapshotFlight(tag: String, source: View, destination: View, config: Config): Flight? {
        val bitmap = snapshot(source) ?: return null
        val target = if (config.crossFade) snapshot(destination) else null
        val start = rectInHost(source, RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()))
        val destinationRect = rectInHost(destination, RectF(0f, 0f, destination.width.toFloat(), destination.height.toFloat()))
        val endRect = when (config.resizeMode) {
            3 -> RectF(destinationRect.left, destinationRect.top, destinationRect.left + start.width(), destinationRect.top + start.height())
            2 -> {
                val fitted = PamSharedGeometry.fit(destinationRect.width(), destinationRect.height(), start.width(), start.height(), PamSharedFit.COVER)
                RectF(destinationRect.left + fitted[0], destinationRect.top + fitted[1], destinationRect.left + fitted[2], destinationRect.top + fitted[3])
            }
            else -> destinationRect
        }
        val startSide = Side(
            RectF(start),
            RectF(start),
            cornerRadius(source).takeIf { it > 0f }?.let { it * scaleInHost(source) } ?: frameRadius(source),
        )
        val endSide = Side(
            if (config.resizeMode == 2) RectF(destinationRect) else RectF(endRect),
            endRect,
            cornerRadius(destination).takeIf { it > 0f }?.let { it * scaleInHost(destination) }
                ?: frameRadius(destination),
        )
        val view = FlightView(
            host,
            BitmapDrawable(host.resources, bitmap),
            bitmap.width,
            bitmap.height,
            target?.let { BitmapDrawable(host.resources, it) },
        )
        return Flight(
            tag, source, destination, source.alpha, destination.alpha, startSide, endSide, view,
            mutableListOf<Bitmap>().apply { add(bitmap); target?.let(::add) }, null, config,
        )
    }

    private fun side(tagged: View, media: View, fit: Int, content: PamSharedContent): Side? {
        if (media.width <= 0 || media.height <= 0) return null
        val fitted = PamSharedGeometry.fit(
            media.width.toFloat(), media.height.toFloat(), content.width.toFloat(), content.height.toFloat(), fit,
        )
        val visible = PamSharedGeometry.clip(media.width.toFloat(), media.height.toFloat(), fitted, fit)
        val contentRect = rectInHost(media, RectF(fitted[0], fitted[1], fitted[2], fitted[3]))
        val clip = rectInHost(media, RectF(visible[0], visible[1], visible[2], visible[3]))
        if (tagged !== media) {
            // The tagged box (a rounded frame around the media) crops it too.
            val box = rectInHost(tagged, RectF(0f, 0f, tagged.width.toFloat(), tagged.height.toFloat()))
            if (!clip.intersect(box)) return null
        }
        val radius = cornerRadius(tagged).takeIf { it > 0f }?.let { it * scaleInHost(tagged) }
            ?: cornerRadius(media).takeIf { it > 0f }?.let { it * scaleInHost(media) }
            ?: frameRadius(tagged)
        return Side(clip, contentRect, radius)
    }

    /**
     * Radius of the rounded frame right around [view]: an ancestor with the
     * same frame that clips its children (a rounded bubble around a photo).
     */
    private fun frameRadius(view: View): Float {
        val box = rectInHost(view, RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        var current = view.parent as? View
        repeat(3) {
            val ancestor = current ?: return 0f
            if (ancestor === host || ancestor.parent !is View) return 0f
            val frame = rectInHost(ancestor, RectF(0f, 0f, ancestor.width.toFloat(), ancestor.height.toFloat()))
            if (kotlin.math.abs(frame.left - box.left) > 2f || kotlin.math.abs(frame.top - box.top) > 2f ||
                kotlin.math.abs(frame.right - box.right) > 2f || kotlin.math.abs(frame.bottom - box.bottom) > 2f
            ) {
                return 0f
            }
            val radius = cornerRadius(ancestor)
            if (radius > 0f) return radius * scaleInHost(ancestor)
            current = ancestor.parent as? View
        }
        return 0f
    }

    /** The tagged view itself when it draws media, else its top visible media child filling it. */
    private fun contentView(tagged: View): PamSharedContentView? {
        if (tagged is PamSharedContentView && tagged.sharedFit != null) return tagged
        if (tagged !is ViewGroup) return null
        val area = tagged.width.toFloat() * tagged.height
        if (area <= 0f) return null
        var found: PamSharedContentView? = null
        fun visit(group: ViewGroup) {
            for (index in group.childCount - 1 downTo 0) {
                if (found != null) return
                val child = group.getChildAt(index)
                if (child.visibility != View.VISIBLE || child.alpha <= 0f) continue
                if (child is PamSharedContentView && child.sharedFit != null) {
                    val box = rectInHost(child, RectF(0f, 0f, child.width.toFloat(), child.height.toFloat()))
                    val outer = rectInHost(tagged, RectF(0f, 0f, tagged.width.toFloat(), tagged.height.toFloat()))
                    if (box.width() * box.height() >= outer.width() * outer.height() * 0.8f &&
                        (child.hasSharedContent || found == null)
                    ) {
                        found = child
                        if (child.hasSharedContent) return
                    }
                    continue
                }
                if (child is ViewGroup) visit(child)
            }
        }
        visit(tagged)
        return found
    }

    private fun taggedViews(root: View): Map<String, List<View>> {
        val result = LinkedHashMap<String, MutableList<View>>()
        fun visit(view: View) {
            (view.getTag(dev.pam.nativeapp.R.id.pam_shared_transition_tag) as? String)
                ?.takeIf(String::isNotEmpty)
                ?.let { result.getOrPut(it) { ArrayList(1) } += view }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return result
    }

    /**
     * On screen inside its route: laid out, every ancestor below the route
     * visible, and at least [minimumShare] of its area inside the clips of
     * its ancestors (an off-screen pager page or a scrolled-away row is out).
     */
    private fun onScreen(view: View, route: View, minimumShare: Float): Boolean {
        if (view.width <= 0 || view.height <= 0 || !view.isAttachedToWindow) return false
        var current: View = view
        while (current !== route) {
            if (current.visibility != View.VISIBLE || current.alpha <= 0f) return false
            current = current.parent as? View ?: return false
        }
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible) || visible.isEmpty) return false
        if (minimumShare <= 0f) return true
        val box = rectInHost(view, RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()))
        val area = box.width() * box.height()
        return area > 0f && visible.width().toFloat() * visible.height() >= area * minimumShare * 0.98f
    }

    /**
     * Maps a rect of [view] into host coordinates through every transform
     * (drag, scale, zoom). A view in another window (the screen under a
     * Modal, the Modal over it) is mapped to its window's root and then
     * moved by the distance between the two windows on screen.
     */
    private fun rectInHost(view: View, local: RectF): RectF {
        val matrix = Matrix()
        var current: View = view
        while (current !== host) {
            matrix.postConcat(current.matrix)
            val parent = current.parent as? View
            if (parent == null) {
                val (dx, dy) = screenDelta(current, host)
                matrix.postTranslate(current.left + dx, current.top + dy)
                break
            }
            matrix.postTranslate(current.left.toFloat() - parent.scrollX, current.top.toFloat() - parent.scrollY)
            current = parent
        }
        return RectF(local).also(matrix::mapRect)
    }

    private fun scaleInHost(view: View): Float {
        val unit = rectInHost(view, RectF(0f, 0f, 100f, 100f))
        return min(unit.width(), unit.height()) / 100f
    }

    private fun cornerRadius(view: View): Float = when (view) {
        is PamContainer -> view.overflowCornerRadius()
        is PamImageView -> view.cornerRadius()
        else -> 0f
    }

    private fun snapshot(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return runCatching {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }.getOrNull()
    }

    private fun config(view: View): Config? {
        val encoded = view.getTag(dev.pam.nativeapp.R.id.pam_shared_transition_config) as? String ?: return null
        return runCatching {
            val json = JSONObject(encoded)
            Config(
                durationMs = json.optLong("durationMs", 500L).coerceIn(0L, 2_000L),
                easing = json.optInt("easing", 2).coerceIn(1, 4),
                resizeMode = json.optInt("resizeMode", 1).coerceIn(1, 3),
                crossFade = json.optBoolean("crossFade", true),
                damping = json.optDouble("damping", 0.82).toFloat().coerceIn(0.01f, 1f),
                stiffness = json.optDouble("stiffness", 220.0).toFloat().coerceIn(1f, 1_000f),
                mass = json.optDouble("mass", 1.0).toFloat().coerceIn(0.1f, 10f),
                returnDurationMs = json.optLong("returnDurationMs", -1L).takeIf { it >= 0 }?.coerceAtMost(2_000L),
                returnEasing = json.optInt("returnEasing", 0).takeIf { it in 1..4 },
                launch = json.optBoolean("launch", false),
                launchBackdrop = runCatching {
                    android.graphics.Color.parseColor(json.optString("launchBackdrop", "#000000"))
                }.getOrDefault(android.graphics.Color.BLACK),
            )
        }.getOrNull()
    }

    /**
     * Draws one flight: the content scaled into its current rect, clipped to
     * the current crop with the current corner radius. A snapshot flight
     * with a cross-fade also draws the destination's pixels on top.
     */
    private class FlightView(
        host: ViewGroup,
        private var drawable: Drawable?,
        private val contentWidth: Int,
        private val contentHeight: Int,
        private var target: Drawable?,
    ) : View(host.context) {
        private val clip = RectF()
        private val content = RectF()
        private var radius = 0f
        private var progress = 0f
        private val path = Path()

        init {
            layout(0, 0, host.width, host.height)
        }

        /** Moves into another host [dx], [dy] away on screen (a launch adopted by a Modal). */
        fun attach(host: ViewGroup, dx: Float, dy: Float) {
            layout(0, 0, host.width, host.height)
            clip.offset(dx, dy)
            content.offset(dx, dy)
            invalidate()
        }

        fun set(start: Side, end: Side, value: Float) {
            fun RectF.lerp(from: RectF, to: RectF) = set(
                PamSharedGeometry.lerp(from.left, to.left, value),
                PamSharedGeometry.lerp(from.top, to.top, value),
                PamSharedGeometry.lerp(from.right, to.right, value),
                PamSharedGeometry.lerp(from.bottom, to.bottom, value),
            )
            clip.lerp(start.clip, end.clip)
            content.lerp(start.content, end.content)
            radius = max(0f, PamSharedGeometry.lerp(start.radius, end.radius, value))
            progress = value.coerceIn(0f, 1f)
            invalidate()
        }

        fun frame(): FloatArray = floatArrayOf(clip.left, clip.top, clip.right, clip.bottom, radius)

        /** The side drawn right now (a handed-off flight continues from it). */
        fun current(): Side = Side(RectF(clip), RectF(content), radius)

        fun drop() {
            drawable = null
            target = null
        }

        override fun onDraw(canvas: Canvas) {
            val picture = drawable ?: return
            if (clip.isEmpty || content.isEmpty) return
            val checkpoint = canvas.save()
            if (radius > 0.5f) {
                path.reset()
                path.addRoundRect(clip, radius, radius, Path.Direction.CW)
                canvas.clipPath(path)
            } else {
                canvas.clipRect(clip)
            }
            canvas.translate(content.left, content.top)
            canvas.scale(content.width() / contentWidth, content.height() / contentHeight)
            picture.setBounds(0, 0, contentWidth, contentHeight)
            val overlay = target
            if (overlay == null) {
                picture.alpha = 255
                picture.draw(canvas)
            } else {
                picture.alpha = ((1f - progress) * 255).toInt()
                picture.draw(canvas)
                overlay.setBounds(0, 0, contentWidth, contentHeight)
                overlay.alpha = (progress * 255).toInt()
                overlay.draw(canvas)
            }
            canvas.restoreToCount(checkpoint)
        }
    }

    companion object {
        private const val HOLD_SAFETY_MS = 15_000L
        private const val MAX_HOMES = 32
        private var touchX = Float.NaN
        private var touchY = Float.NaN

        /** The host whose launch flight is still waiting for its destination. */
        private var launchOwner: java.lang.ref.WeakReference<PamSharedElements>? = null

        /**
         * The last touch on screen (raw coordinates). When one tag is on
         * screen more than once (the same author's avatar on several posts),
         * the instance under the finger that opened the route or Modal flies.
         */
        fun touched(rawX: Float, rawY: Float) {
            touchX = rawX
            touchY = rawY
        }

        /** How far [to] is from [from] on screen: what moves a point of one window into the other. */
        internal fun screenDelta(from: View, to: View): Pair<Float, Float> {
            if (from.rootView === to.rootView) {
                val a = IntArray(2)
                val b = IntArray(2)
                from.getLocationInWindow(a)
                to.getLocationInWindow(b)
                return PamSharedGeometry.delta(a, b)
            }
            val a = IntArray(2)
            val b = IntArray(2)
            from.getLocationOnScreen(a)
            to.getLocationOnScreen(b)
            return PamSharedGeometry.delta(a, b)
        }
        /** How long a launched flight waits for its route beyond its own duration. */
        private const val LAUNCH_WAIT_MS = 900L
    }
}
