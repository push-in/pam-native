package dev.pam.nativeapp.render

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.sign

internal data class PamDragDriver(
    val ref: String,
    val property: PamMotionProperty,
    val input: List<PamMotionValue>,
    val output: List<PamMotionValue>,
)

/** Settle animation used when a drag releases onto a snap point. */
internal data class PamDragSettle(
    val spring: PamSpringConfig?,
    val durationMs: Long = 0L,
    val easing: String = "ease-out",
) {
    companion object {
        val DEFAULT = PamDragSettle(PamSpringConfig(stiffness = 260.0, damping = 22.0, mass = 1.0))

        fun parse(token: String): PamDragSettle? {
            val parts = token.split(':')
            return when (parts[0]) {
                "spring" -> runCatching {
                    PamDragSettle(
                        PamSpringConfig(
                            stiffness = parts.getOrNull(1)?.toDoubleOrNull() ?: 260.0,
                            damping = parts.getOrNull(2)?.toDoubleOrNull() ?: 22.0,
                            mass = parts.getOrNull(3)?.toDoubleOrNull() ?: 1.0,
                        ),
                    )
                }.getOrNull()
                "timing" -> PamDragSettle(
                    spring = null,
                    durationMs = (parts.getOrNull(1)?.toLongOrNull() ?: 200L).coerceIn(0L, 10_000L),
                    easing = parts.getOrNull(2)?.takeIf(String::isNotEmpty) ?: "ease-out",
                )
                else -> null
            }
        }
    }
}

/**
 * Text drag contract (`key=value` lines):
 * `axis`, `min`, `max`, `rubber`, `target`, `snaps`, `settle`, `settle.<i>`,
 * `threshold`, `velocity`, `haptic`, `group` and repeated
 * `drive=<ref>|<property>|<inputs>|<outputs>`.
 */
internal data class PamDragConfig(
    val horizontal: Boolean,
    val min: Double?,
    val max: Double?,
    val rubber: Double,
    val target: String,
    val snaps: List<PamMotionValue>,
    val settle: PamDragSettle,
    val snapSettles: Map<Int, PamDragSettle>,
    val threshold: Double,
    val velocity: Double,
    val haptic: Boolean,
    val group: String,
    val drivers: List<PamDragDriver>,
    val textDrivers: List<PamDragTextDriver>,
    val touchInset: Double?,
    val snapOnRelease: Boolean,
) {
    fun settleFor(index: Int): PamDragSettle = snapSettles[index] ?: settle

    companion object {
        fun parse(source: String): PamDragConfig? {
            val values = linkedMapOf<String, String>()
            val drivers = mutableListOf<PamDragDriver>()
            val textDrivers = mutableListOf<PamDragTextDriver>()
            for (line in source.split('\n', ';').map(String::trim).filter(String::isNotEmpty)) {
                val parts = line.split('=', limit = 2)
                if (parts.size != 2) continue
                if (parts[0] == "drive") {
                    parseDriver(parts[1])?.let(drivers::add)
                } else if (parts[0] == "text") {
                    PamDragTextDriver.parse(parts[1])?.let(textDrivers::add)
                } else {
                    values[parts[0]] = parts[1]
                }
            }
            val horizontal = when (values["axis"] ?: "x") {
                "x" -> true
                "y" -> false
                else -> return null
            }
            val snaps = (values["snaps"] ?: "0").split(',').mapNotNull { PamMotionValue.parse(it.trim()) }
            if (snaps.isEmpty() || snaps.size > 16) return null
            val snapSettles = values.keys
                .filter { it.startsWith("settle.") }
                .mapNotNull { key ->
                    val index = key.removePrefix("settle.").toIntOrNull() ?: return@mapNotNull null
                    PamDragSettle.parse(values.getValue(key))?.let { index to it }
                }
                .toMap()
            return PamDragConfig(
                horizontal = horizontal,
                min = values["min"]?.toDoubleOrNull()?.takeIf(Double::isFinite),
                max = values["max"]?.toDoubleOrNull()?.takeIf(Double::isFinite),
                rubber = (values["rubber"]?.toDoubleOrNull() ?: 0.0).coerceIn(0.0, 1.0),
                target = values["target"].orEmpty(),
                snaps = snaps,
                settle = values["settle"]?.let(PamDragSettle::parse) ?: PamDragSettle.DEFAULT,
                snapSettles = snapSettles,
                threshold = (values["threshold"]?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                velocity = (values["velocity"]?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0),
                haptic = values["haptic"] == "1",
                group = values["group"].orEmpty(),
                drivers = drivers.take(16),
                textDrivers = textDrivers.take(8),
                touchInset = values["touch"]?.toDoubleOrNull()?.takeIf(Double::isFinite),
                snapOnRelease = values["snapOnRelease"] != "0",
            )
        }

        private fun parseDriver(source: String): PamDragDriver? {
            val parts = source.split('|')
            if (parts.size != 4) return null
            val property = PamMotionProperty.parse(parts[1]) ?: return null
            val input = parts[2].split(',').map { PamMotionValue.parse(it.trim()) ?: return null }
            val output = parts[3].split(',').map { PamMotionValue.parse(it.trim()) ?: return null }
            if (input.size < 2 || input.size != output.size || input.size > 8) return null
            return PamDragDriver(parts[0], property, input, output)
        }
    }
}

/** Pure drag math so release decisions are unit-testable. */
internal object PamDragMath {
    fun bound(raw: Double, min: Double?, max: Double?, rubber: Double): Double =
        when {
            max != null && raw > max -> max + (raw - max) * rubber
            min != null && raw < min -> min + (raw - min) * rubber
            else -> raw
        }

    fun nearest(snaps: List<Double>, position: Double): Int =
        snaps.indices.minByOrNull { abs(snaps[it] - position) } ?: 0

    /**
     * Chooses the resting snap after a release that started on [origin].
     * Crossing [threshold] (distance) or [velocityThreshold] moves to the
     * adjacent snap in the travel direction; otherwise the drag springs back.
     */
    fun release(
        snaps: List<Double>,
        origin: Int,
        position: Double,
        velocity: Double,
        threshold: Double,
        velocityThreshold: Double,
    ): Int {
        val start = snaps.getOrElse(origin) { 0.0 }
        val displacement = position - start
        val fast = velocityThreshold > 0.0 && abs(velocity) >= velocityThreshold &&
            (displacement == 0.0 || sign(velocity) == sign(displacement))
        val far = threshold > 0.0 && abs(displacement) >= threshold
        if (!fast && !far) return origin
        val direction = if (fast) sign(velocity) else sign(displacement)
        if (direction == 0.0) return origin
        return snaps.indices
            .filter { (snaps[it] - start) * direction > 0.0 }
            .minByOrNull { abs(snaps[it] - start) }
            ?: origin
    }

    fun interpolate(input: List<Double>, output: List<Double>, value: Double): Double {
        if (input.size < 2) return output.firstOrNull() ?: value
        val ascending = input.first() <= input.last()
        val xs = if (ascending) input else input.reversed()
        val ys = if (ascending) output else output.reversed()
        if (value <= xs.first()) return ys.first()
        if (value >= xs.last()) return ys.last()
        for (index in 1 until xs.size) {
            if (value <= xs[index]) {
                val span = xs[index] - xs[index - 1]
                if (span == 0.0) return ys[index]
                val progress = (value - xs[index - 1]) / span
                return ys[index - 1] + (ys[index] - ys[index - 1]) * progress
            }
        }
        return ys.last()
    }
}

internal data class PamDragRelease(val snapIndex: Int, val thresholdReached: Boolean)

/**
 * Drives a pan gesture entirely on the UI thread: bounded translation,
 * interpolated driver properties, snap selection and spring/timing settle.
 */
internal class PamDragController(private val host: ViewGroup) {
    var config: PamDragConfig? = null
        private set
    private var onSettle: ((Int, Double) -> Unit)? = null
    private var origin = 0
    private var restingIndex = -1
    private var startPx = 0.0
    private var thresholdReached = false
    private var runner: PamMotionRunner? = null
    private var lastPosition = 0.0
    private var releasing = false
    private var releaseSettle: (() -> Unit)? = null

    val currentIndex: Int get() = restingIndex

    fun configure(next: PamDragConfig?, settle: ((Int, Double) -> Unit)?) {
        val previousGroup = config?.group.orEmpty()
        config = next
        onSettle = settle
        if (previousGroup.isNotEmpty()) unregister(previousGroup)
        next?.group?.takeIf(String::isNotEmpty)?.let(::register)
    }

    fun detach() {
        runner?.cancel()
        runner = null
        config?.group?.takeIf(String::isNotEmpty)?.let(::unregister)
    }

    fun begin(x: Float = 0f, y: Float = 0f, translationX: Float = 0f, translationY: Float = 0f) {
        val current = config ?: return
        runner?.cancel()
        runner = null
        val target = target() ?: return
        startPx = current.touchInset?.let { inset ->
            (if (current.horizontal) x - translationX else y - translationY).toDouble() - inset * density()
        } ?: read(target, current)
        val snaps = snapPixels(target, current)
        origin = PamDragMath.nearest(snaps, startPx)
        thresholdReached = false
        closeOthers(current.group)
        host.parent?.requestDisallowInterceptTouchEvent(true)
    }

    fun update(translationX: Float, translationY: Float) {
        val current = config ?: return
        val target = target() ?: return
        val density = density()
        val delta = if (current.horizontal) translationX else translationY
        val raw = startPx + delta
        val bounded = PamDragMath.bound(
            raw,
            current.min?.times(density),
            current.max?.times(density),
            current.rubber,
        )
        write(target, current, bounded)
        val snaps = snapPixels(target, current)
        val reached = current.threshold > 0.0 &&
            abs(bounded - snaps.getOrElse(origin) { 0.0 }) >= current.threshold * density
        if (reached != thresholdReached) {
            thresholdReached = reached
            if (reached && current.haptic) {
                host.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    /**
     * Ends the drag. A settle that completes within the release (reduced
     * motion, or already at its snap) is reported by [flushReleaseSettle],
     * after the gesture end has been delivered: settle never precedes end.
     */
    fun end(velocityX: Float, velocityY: Float, cancelled: Boolean): PamDragRelease? {
        releasing = true
        try {
            return release(velocityX, velocityY, cancelled)
        } finally {
            releasing = false
        }
    }

    /** Reports a settle deferred by [end]; call after delivering the end. */
    fun flushReleaseSettle() {
        val settle = releaseSettle ?: return
        releaseSettle = null
        settle()
    }

    private fun settled(index: Int, position: Double) {
        val callback = onSettle ?: return
        if (releasing) {
            releaseSettle = { callback(index, position) }
        } else {
            callback(index, position)
        }
    }

    private fun release(velocityX: Float, velocityY: Float, cancelled: Boolean): PamDragRelease? {
        val current = config ?: return null
        val target = target() ?: return null
        val density = density()
        val position = read(target, current)
        if (!current.snapOnRelease) {
            settled(-1, position / density())
            return PamDragRelease(-1, false)
        }
        val velocity = (if (current.horizontal) velocityX else velocityY).toDouble()
        val snaps = snapPixels(target, current)
        val index = if (cancelled) {
            origin
        } else {
            PamDragMath.release(
                snaps,
                origin,
                position,
                velocity,
                current.threshold * density,
                current.velocity * density,
            )
        }
        val reached = thresholdReached
        settleTo(index, velocity, animated = true)
        return PamDragRelease(index, reached)
    }

    /** Programmatic open/close; ignored while the requested snap is already resting. */
    fun snapTo(index: Int, animated: Boolean) {
        val current = config ?: return
        if (index !in current.snaps.indices || (index == restingIndex && runner == null)) return
        settleTo(index, 0.0, animated)
    }

    private fun settleTo(index: Int, velocity: Double, animated: Boolean) {
        val current = config ?: return
        val target = target() ?: return
        val snaps = snapPixels(target, current)
        val destination = snaps.getOrElse(index) { 0.0 }
        runner?.cancel()
        val property = axisProperty(current)
        val settle = current.settleFor(index)
        val step = if (settle.spring != null) {
            PamMotionStep.Spring(PamMotionValue(destination), settle.spring, velocity, 0L)
        } else {
            PamMotionStep.Timing(PamMotionValue(destination), settle.durationMs, settle.easing, 0L)
        }
        val timeline = PamMotionTimeline.build(
            PamMotionProgram(0L, 1, listOf(mapOf(property to listOf(step)))),
            current = { read(target, current) },
            resolve = { _, value -> value.number },
        )
        val finish = {
            runner = null
            restingIndex = index
            lastPosition = destination
            settled(index, destination / density())
            Unit
        }
        val next = PamMotionRunner(
            target,
            timeline,
            iterations = 1,
            onFrame = { applyDrivers(target, current) },
            onComplete = finish,
        )
        runner = next
        next.start(reducedMotion = !animated || PamMotionPolicy.isReduced(host.context))
    }

    private fun axisProperty(current: PamDragConfig): PamMotionProperty =
        if (current.horizontal) PamMotionProperty.TRANSLATE_X else PamMotionProperty.TRANSLATE_Y

    private fun read(target: View, current: PamDragConfig): Double =
        PamMotionTarget.read(target, axisProperty(current))

    private fun write(target: View, current: PamDragConfig, value: Double) {
        PamMotionTarget.write(target, axisProperty(current), value)
        lastPosition = value
        applyDrivers(target, current)
    }

    private fun snapPixels(target: View, current: PamDragConfig): List<Double> =
        current.snaps.map { PamMotionTarget.resolve(target, axisProperty(current), it) }

    private fun applyDrivers(target: View, current: PamDragConfig) {
        if (current.drivers.isEmpty() && current.textDrivers.isEmpty()) return
        val density = density()
        val positionDp = read(target, current) / density
        val extentDp = (if (current.horizontal) target.width else target.height) / density
        for (driver in current.drivers) {
            val view = if (driver.ref.isEmpty()) target else findRef(host, driver.ref) ?: continue
            val input = driver.input.map { if (it.percent) extentDp * it.number / 100.0 else it.number }
            val output = driver.output.map { PamMotionTarget.resolve(view, driver.property, it) }
            PamMotionTarget.write(view, driver.property, PamDragMath.interpolate(input, output, positionDp))
        }
        for (driver in current.textDrivers) {
            val view = findRef(host, driver.ref) as? TextView ?: continue
            val label = driver.label(positionDp, extentDp)
            if (view.text.toString() != label) view.text = label
        }
    }

    private fun target(): View? {
        val current = config ?: return null
        if (current.target.isNotEmpty()) {
            findRef(host, current.target)?.let { return it }
        }
        return host.getChildAt(0)
    }

    private fun density(): Double = host.resources.displayMetrics.density.toDouble().coerceAtLeast(0.01)

    private fun restIndex(current: PamDragConfig): Int =
        current.snaps.indexOfFirst { !it.percent && it.number == 0.0 }.takeIf { it >= 0 } ?: 0

    private fun closeOthers(group: String) {
        if (group.isEmpty()) return
        val members = groups[group] ?: return
        members.removeAll { it.get() == null }
        for (reference in members.toList()) {
            val other = reference.get() ?: continue
            if (other === this) continue
            val otherConfig = other.config ?: continue
            val rest = other.restIndex(otherConfig)
            if (other.restingIndex != rest && other.restingIndex >= 0 || other.runner != null) {
                other.snapTo(rest, animated = true)
            }
        }
    }

    private fun register(group: String) {
        val members = groups.getOrPut(group, ::mutableListOf)
        members.removeAll { it.get() == null || it.get() === this }
        members += WeakReference(this)
    }

    private fun unregister(group: String) {
        groups[group]?.removeAll { it.get() == null || it.get() === this }
    }

    companion object {
        private val groups = mutableMapOf<String, MutableList<WeakReference<PamDragController>>>()

        fun findRef(root: View, ref: String): View? {
            if (root.getTag(dev.pam.nativeapp.R.id.pam_native_ref) == ref) return root
            if (root !is ViewGroup) return null
            for (index in 0 until root.childCount) {
                findRef(root.getChildAt(index), ref)?.let { return it }
            }
            return null
        }
    }
}
