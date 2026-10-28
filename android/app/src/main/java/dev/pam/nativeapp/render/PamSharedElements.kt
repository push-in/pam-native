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

    fun ease(progress: Float, easing: Int, damping: Float, stiffness: Float, mass: Float): Float =
        when (easing) {
            1 -> progress
            3 -> {
                val frequency = sqrt(stiffness / mass) * 0.12f
                (1f - exp(-damping * 7f * progress) * cos(frequency * progress)).coerceIn(0f, 1.08f)
            }
            else -> progress * progress * (3f - 2f * progress)
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
    )

    private class Side(val clip: RectF, val content: RectF, val radius: Float)

    private class Flight(
        val source: View,
        val destination: View,
        val sourceAlpha: Float,
        val destinationAlpha: Float,
        val start: Side,
        val end: Side,
        val view: FlightView,
        val owned: List<Bitmap>,
        val destinationContent: PamSharedContentView?,
        val config: Config,
    )

    private val flights = ArrayList<Flight>(2)
    private val holding = ArrayList<Flight>(2)

    val isEmpty: Boolean get() = flights.isEmpty()
    val count: Int get() = flights.size
    val durationMs: Long get() = flights.maxOfOrNull { it.config.durationMs } ?: 0L

    fun prepare(incoming: View, outgoing: View, defaultDurationMs: Long) {
        clear()
        val sources = taggedViews(outgoing)
        if (sources.isEmpty()) return
        val destinations = taggedViews(incoming)
        for ((tag, candidates) in sources) {
            if (flights.size >= 16) break
            val source = candidates.firstOrNull { onScreen(it, outgoing, minimumShare = 0.5f) } ?: continue
            val destination = destinations[tag]?.firstOrNull { onScreen(it, incoming, minimumShare = 0f) }
                ?: continue
            val config = config(destination) ?: config(source)
                ?: Config(durationMs = defaultDurationMs.coerceIn(0L, 2_000L))
            createFlight(source, destination, config)?.let(flights::add)
        }
        flights.forEach {
            it.source.alpha = 0f
            it.destination.alpha = 0f
            host.overlay.add(it.view)
        }
    }

    fun apply(progress: Float) {
        flights.forEach { flight ->
            val config = flight.config
            val eased = PamSharedGeometry.ease(progress, config.easing, config.damping, config.stiffness, config.mass)
            flight.view.set(flight.start, flight.end, eased)
        }
    }

    /**
     * Ends the flights. A destination whose media is not drawn yet (an image
     * still decoding) keeps its flight on top, at the end frame, until it
     * draws or [HOLD_LIMIT_MS] pass: the screen never shows an empty box.
     */
    fun finish() {
        val ended = flights.toList()
        flights.clear()
        ended.forEach { flight ->
            flight.source.alpha = flight.sourceAlpha
            flight.destination.alpha = flight.destinationAlpha
            if (flight.destinationContent?.hasSharedContent == false) {
                flight.view.set(flight.start, flight.end, 1f)
                holding += flight
            } else {
                release(flight)
            }
        }
        if (holding.isNotEmpty()) pollHolding(System.currentTimeMillis())
    }

    fun clear() {
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
            val expired = System.currentTimeMillis() - startedAt >= HOLD_LIMIT_MS
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

    private fun createFlight(source: View, destination: View, config: Config): Flight? {
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
                source, destination, source.alpha, destination.alpha, start, end, view, owned,
                destinationMedia, config,
            )
        }
        owned.forEach { if (!it.isRecycled) it.recycle() }
        return snapshotFlight(source, destination, config)
    }

    /** Any view: its pixels scale from one frame to the other (resizeMode). */
    private fun snapshotFlight(source: View, destination: View, config: Config): Flight? {
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
            source, destination, source.alpha, destination.alpha, startSide, endSide, view,
            listOfNotNull(bitmap, target), null, config,
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
            if (ancestor === host) return 0f
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

    /** Maps a rect of [view] into host coordinates through every transform (drag, scale, zoom). */
    private fun rectInHost(view: View, local: RectF): RectF {
        val matrix = Matrix()
        var current: View = view
        while (current !== host) {
            matrix.postConcat(current.matrix)
            val parent = current.parent as? View ?: break
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
                easing = json.optInt("easing", 2).coerceIn(1, 3),
                resizeMode = json.optInt("resizeMode", 1).coerceIn(1, 3),
                crossFade = json.optBoolean("crossFade", true),
                damping = json.optDouble("damping", 0.82).toFloat().coerceIn(0.01f, 1f),
                stiffness = json.optDouble("stiffness", 220.0).toFloat().coerceIn(1f, 1_000f),
                mass = json.optDouble("mass", 1.0).toFloat().coerceIn(0.1f, 10f),
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

    private companion object {
        const val HOLD_LIMIT_MS = 700L
    }
}
