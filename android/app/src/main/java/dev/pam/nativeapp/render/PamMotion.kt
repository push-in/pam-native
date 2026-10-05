package dev.pam.nativeapp.render

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * UI-thread motion primitives shared by declarative animations, CSS
 * transitions, drag release and tap effects. Everything here is pure Kotlin
 * except [PamMotionRunner], so curves and parsing are unit-testable on the JVM.
 */
internal data class PamSpringConfig(
    val stiffness: Double = 100.0,
    val damping: Double = 10.0,
    val mass: Double = 1.0,
) {
    init {
        require(stiffness.isFinite() && stiffness > 0.0) { "Spring stiffness must be positive." }
        require(damping.isFinite() && damping >= 0.0) { "Spring damping must not be negative." }
        require(mass.isFinite() && mass > 0.0) { "Spring mass must be positive." }
    }
}

/**
 * Closed-form damped harmonic oscillator, the same model Reanimated's
 * `withSpring` integrates. Velocity is expressed in value units per second.
 */
internal class PamSpring(
    val config: PamSpringConfig,
    val from: Double,
    val to: Double,
    val initialVelocity: Double = 0.0,
) {
    private val x0 = from - to
    private val omega0 = sqrt(config.stiffness / config.mass)
    private val zeta = config.damping / (2.0 * sqrt(config.stiffness * config.mass))
    private val scale = max(max(abs(to - from), abs(initialVelocity) * 0.05), 1e-3)
    private val restDisplacement = scale * 0.002
    private val restVelocity = scale * 0.02

    val durationMs: Long = computeDuration()

    /** Displacement relative to [to] at [seconds]. */
    private fun displacement(seconds: Double): Double {
        val v0 = initialVelocity
        return when {
            zeta < 1.0 -> {
                val omegaD = omega0 * sqrt(1.0 - zeta * zeta)
                exp(-zeta * omega0 * seconds) * (
                    x0 * cos(omegaD * seconds) +
                        (v0 + zeta * omega0 * x0) / omegaD * sin(omegaD * seconds)
                    )
            }
            zeta == 1.0 -> (x0 + (v0 + omega0 * x0) * seconds) * exp(-omega0 * seconds)
            else -> {
                val root = sqrt(zeta * zeta - 1.0)
                val r1 = -omega0 * (zeta - root)
                val r2 = -omega0 * (zeta + root)
                val a = (v0 - r2 * x0) / (r1 - r2)
                val b = x0 - a
                a * exp(r1 * seconds) + b * exp(r2 * seconds)
            }
        }
    }

    fun position(seconds: Double): Double =
        if (seconds * 1_000.0 >= durationMs) to else to + displacement(seconds)

    fun velocity(seconds: Double): Double {
        val h = 1e-4
        return (displacement(seconds + h) - displacement(max(0.0, seconds - h))) /
            (if (seconds > h) 2 * h else h + seconds)
    }

    private fun computeDuration(): Long {
        if (abs(x0) < restDisplacement && abs(initialVelocity) < restVelocity) return 0L
        var ms = 1L
        while (ms < MAX_SPRING_MS) {
            val seconds = ms / 1_000.0
            if (
                abs(displacement(seconds)) < restDisplacement &&
                abs(velocityAt(seconds)) < restVelocity
            ) {
                return ms
            }
            ms += 1
        }
        return MAX_SPRING_MS
    }

    private fun velocityAt(seconds: Double): Double {
        val h = 5e-4
        return (displacement(seconds + h) - displacement(seconds - h)) / (2 * h)
    }

    companion object {
        const val MAX_SPRING_MS = 10_000L
    }
}

internal fun interface PamEasing {
    fun transform(progress: Float): Float
}

internal class PamCubicBezier(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
) : PamEasing {
    override fun transform(progress: Float): Float {
        if (progress <= 0f) return 0f
        if (progress >= 1f) return 1f
        var t = progress
        repeat(8) {
            val x = sample(t, x1, x2) - progress
            if (abs(x) < 1e-5f) return sample(t, y1, y2)
            val derivative = sampleDerivative(t, x1, x2)
            if (abs(derivative) < 1e-6f) return@repeat
            t -= x / derivative
        }
        var low = 0f
        var high = 1f
        t = progress
        repeat(24) {
            val x = sample(t, x1, x2)
            if (abs(x - progress) < 1e-5f) return sample(t, y1, y2)
            if (x < progress) low = t else high = t
            t = (low + high) / 2f
        }
        return sample(t, y1, y2)
    }

    private fun sample(t: Float, a: Float, b: Float): Float =
        ((1f - 3f * b + 3f * a) * t + (3f * b - 6f * a)) * t * t + 3f * a * t

    private fun sampleDerivative(t: Float, a: Float, b: Float): Float =
        3f * (1f - 3f * b + 3f * a) * t * t + 2f * (3f * b - 6f * a) * t + 3f * a
}

internal object PamEasings {
    val LINEAR = PamEasing { it }
    private val named: Map<String, PamEasing> = mapOf(
        "linear" to LINEAR,
        "ease" to PamCubicBezier(0.25f, 0.1f, 0.25f, 1f),
        "ease-in" to PamCubicBezier(0.42f, 0f, 1f, 1f),
        "ease-out" to PamCubicBezier(0f, 0f, 0.58f, 1f),
        "ease-in-out" to PamCubicBezier(0.42f, 0f, 0.58f, 1f),
        "ease-in-quad" to PamEasing { it * it },
        "ease-out-quad" to PamEasing { it * (2f - it) },
        "ease-in-out-quad" to PamEasing {
            if (it < 0.5f) 2f * it * it else -1f + (4f - 2f * it) * it
        },
        "ease-in-cubic" to PamEasing { it * it * it },
        "ease-out-cubic" to PamEasing { val p = it - 1f; p * p * p + 1f },
        "ease-in-out-cubic" to PamEasing {
            if (it < 0.5f) 4f * it * it * it else (it - 1f) * (2f * it - 2f) * (2f * it - 2f) + 1f
        },
        "ease-out-back" to PamCubicBezier(0.34f, 1.56f, 0.64f, 1f),
    )

    /** `linear`, CSS names, `*-quad`, `*-cubic`, or `bezier:x1:y1:x2:y2`. */
    fun parse(token: String): PamEasing {
        named[token]?.let { return it }
        if (token.startsWith("bezier:")) {
            val parts = token.removePrefix("bezier:").split(':').mapNotNull { it.toFloatOrNull() }
            if (parts.size == 4 && parts.all { it.isFinite() }) {
                return PamCubicBezier(parts[0].coerceIn(0f, 1f), parts[1], parts[2].coerceIn(0f, 1f), parts[3])
            }
        }
        return named.getValue("ease-in-out")
    }
}

internal enum class PamMotionProperty(val token: String) {
    OPACITY("opacity"),
    TRANSLATE_X("translateX"),
    TRANSLATE_Y("translateY"),
    SCALE("scale"),
    SCALE_X("scaleX"),
    SCALE_Y("scaleY"),
    ROTATE("rotate"),
    RADIUS("borderRadius"),
    ;

    companion object {
        fun parse(token: String): PamMotionProperty? = entries.firstOrNull { it.token == token }
    }
}

/** A number in the property's unit (dp for lengths), or a percentage of the view's own extent. */
internal data class PamMotionValue(val number: Double, val percent: Boolean = false) {
    companion object {
        fun parse(token: String): PamMotionValue? {
            val percent = token.endsWith("%")
            val number = token.removeSuffix("%").toDoubleOrNull() ?: return null
            if (!number.isFinite()) return null
            return PamMotionValue(number, percent)
        }
    }
}

internal sealed class PamMotionStep {
    data class Set(val value: PamMotionValue) : PamMotionStep()
    data class Wait(val durationMs: Long) : PamMotionStep()
    data class Timing(
        val to: PamMotionValue,
        val durationMs: Long,
        val easing: String,
        val delayMs: Long,
    ) : PamMotionStep()
    data class Spring(
        val to: PamMotionValue,
        val config: PamSpringConfig,
        val velocity: Double,
        val delayMs: Long,
    ) : PamMotionStep()
}

internal data class PamMotionProgram(
    val id: Long,
    val iterations: Int,
    val phases: List<Map<PamMotionProperty, List<PamMotionStep>>>,
) {
    companion object {
        private val STEP = Regex("^([a-z]+)\\(([^)]*)\\)$")

        /**
         * Text program:
         * `pam-motion 1 id=<n> iterations=<n>` followed by
         * `<phase> <property> <step> <step>...` lines, where steps are
         * `set(v)`, `wait(ms)`, `timing(to,ms,easing,delay)` and
         * `spring(to,stiffness,damping,mass,velocity,delay)`.
         */
        fun parse(source: String): PamMotionProgram? {
            val lines = source.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            if (lines.isEmpty()) return null
            val header = lines.first().split(' ')
            if (header.size < 2 || header[0] != "pam-motion" || header[1] != "1") return null
            val attributes = header.drop(2).mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }.toMap()
            val id = attributes["id"]?.toLongOrNull() ?: 0L
            val iterations = (attributes["iterations"]?.toIntOrNull() ?: 1).coerceIn(-1, 10_000)
            val phases = sortedMapOf<Int, MutableMap<PamMotionProperty, MutableList<PamMotionStep>>>()
            for (line in lines.drop(1)) {
                val tokens = line.split(' ').filter(String::isNotEmpty)
                if (tokens.size < 3) return null
                val phase = tokens[0].toIntOrNull()?.takeIf { it in 0..63 } ?: return null
                val property = PamMotionProperty.parse(tokens[1]) ?: return null
                val steps = phases.getOrPut(phase, ::linkedMapOf).getOrPut(property, ::mutableListOf)
                for (token in tokens.drop(2)) {
                    steps += parseStep(token) ?: return null
                    if (steps.size > 64) return null
                }
            }
            if (phases.isEmpty()) return null
            return PamMotionProgram(id, iterations, phases.values.toList())
        }

        internal fun parseStep(token: String): PamMotionStep? {
            val match = STEP.matchEntire(token) ?: return null
            val args = match.groupValues[2].split(',').map(String::trim)
            fun long(index: Int, fallback: Long = 0L): Long =
                (args.getOrNull(index)?.toDoubleOrNull()?.toLong() ?: fallback).coerceIn(0L, 60_000L)
            fun double(index: Int, fallback: Double): Double =
                args.getOrNull(index)?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: fallback
            return when (match.groupValues[1]) {
                "set" -> PamMotionStep.Set(PamMotionValue.parse(args[0]) ?: return null)
                "wait" -> PamMotionStep.Wait(long(0))
                "timing" -> PamMotionStep.Timing(
                    to = PamMotionValue.parse(args[0]) ?: return null,
                    durationMs = long(1, 300L),
                    easing = args.getOrNull(2)?.takeIf(String::isNotEmpty) ?: "ease-in-out",
                    delayMs = long(3),
                )
                "spring" -> PamMotionStep.Spring(
                    to = PamMotionValue.parse(args[0]) ?: return null,
                    config = runCatching {
                        PamSpringConfig(
                            stiffness = double(1, 100.0),
                            damping = double(2, 10.0),
                            mass = double(3, 1.0),
                        )
                    }.getOrNull() ?: return null,
                    velocity = double(4, 0.0),
                    delayMs = long(5),
                )
                else -> null
            }
        }
    }
}

/** One resolved piece of a property's timeline, in absolute milliseconds. */
internal class PamMotionSegment(
    val startMs: Long,
    val endMs: Long,
    private val sample: (Long) -> Double,
) {
    fun valueAt(ms: Long): Double = sample((ms - startMs).coerceIn(0L, endMs - startMs))
}

internal class PamMotionTimeline(
    val durationMs: Long,
    val tracks: Map<PamMotionProperty, List<PamMotionSegment>>,
    val initial: Map<PamMotionProperty, Double>,
) {
    fun valueAt(property: PamMotionProperty, ms: Long): Double? {
        val segments = tracks[property] ?: return null
        var value = initial[property] ?: return null
        for (segment in segments) {
            if (ms < segment.startMs) break
            value = segment.valueAt(ms)
        }
        return value
    }

    fun finalValues(): Map<PamMotionProperty, Double> =
        tracks.keys.associateWith { valueAt(it, durationMs) ?: 0.0 }

    companion object {
        /**
         * Resolves a program into absolute segments. [current] supplies each
         * property's starting value in output units and [resolve] converts
         * authored values (dp or percent) into output units.
         */
        fun build(
            program: PamMotionProgram,
            current: (PamMotionProperty) -> Double,
            resolve: (PamMotionProperty, PamMotionValue) -> Double,
        ): PamMotionTimeline {
            val tracks = linkedMapOf<PamMotionProperty, MutableList<PamMotionSegment>>()
            val initial = linkedMapOf<PamMotionProperty, Double>()
            val latest = linkedMapOf<PamMotionProperty, Double>()
            var phaseStart = 0L
            for (phase in program.phases) {
                var phaseEnd = phaseStart
                for ((property, steps) in phase) {
                    var cursor = phaseStart
                    var value = latest[property] ?: current(property).also {
                        initial[property] = it
                        latest[property] = it
                    }
                    val segments = tracks.getOrPut(property, ::mutableListOf)
                    for (step in steps) {
                        when (step) {
                            is PamMotionStep.Set -> {
                                val target = resolve(property, step.value)
                                segments += PamMotionSegment(cursor, cursor) { target }
                                value = target
                            }
                            is PamMotionStep.Wait -> cursor += step.durationMs
                            is PamMotionStep.Timing -> {
                                cursor += step.delayMs
                                val from = value
                                val to = resolve(property, step.to)
                                val duration = step.durationMs.coerceAtLeast(0L)
                                val easing = PamEasings.parse(step.easing)
                                segments += PamMotionSegment(cursor, cursor + duration) { elapsed ->
                                    if (duration == 0L) {
                                        to
                                    } else {
                                        from + (to - from) * easing.transform(elapsed.toFloat() / duration)
                                    }
                                }
                                cursor += duration
                                value = to
                            }
                            is PamMotionStep.Spring -> {
                                cursor += step.delayMs
                                val to = resolve(property, step.to)
                                val spring = PamSpring(step.config, value, to, step.velocity)
                                segments += PamMotionSegment(cursor, cursor + spring.durationMs) {
                                    spring.position(it / 1_000.0)
                                }
                                cursor += spring.durationMs
                                value = to
                            }
                        }
                    }
                    latest[property] = value
                    phaseEnd = max(phaseEnd, cursor)
                }
                phaseStart = phaseEnd
            }
            return PamMotionTimeline(phaseStart, tracks, initial)
        }
    }
}

/** Reads and writes animatable properties in view units (px for lengths). */
internal object PamMotionTarget {
    fun read(view: View, property: PamMotionProperty): Double =
        when (property) {
            PamMotionProperty.OPACITY -> view.alpha.toDouble()
            PamMotionProperty.TRANSLATE_X -> view.translationX.toDouble()
            PamMotionProperty.TRANSLATE_Y -> view.translationY.toDouble()
            PamMotionProperty.SCALE, PamMotionProperty.SCALE_X -> view.scaleX.toDouble()
            PamMotionProperty.SCALE_Y -> view.scaleY.toDouble()
            PamMotionProperty.ROTATE -> view.rotation.toDouble()
            PamMotionProperty.RADIUS ->
                ((view.getTag(dev.pam.nativeapp.R.id.pam_motion_radius) as? Float) ?: 0f).toDouble()
        }

    fun resolve(view: View, property: PamMotionProperty, value: PamMotionValue): Double {
        val density = view.resources.displayMetrics.density.toDouble()
        return when (property) {
            PamMotionProperty.TRANSLATE_X ->
                if (value.percent) view.width * value.number / 100.0 else value.number * density
            PamMotionProperty.TRANSLATE_Y ->
                if (value.percent) view.height * value.number / 100.0 else value.number * density
            PamMotionProperty.RADIUS ->
                if (value.percent) min(view.width, view.height) * value.number / 100.0 else value.number * density
            PamMotionProperty.OPACITY -> if (value.percent) value.number / 100.0 else value.number
            else -> if (value.percent) value.number / 100.0 else value.number
        }
    }

    fun write(view: View, property: PamMotionProperty, value: Double) {
        val number = value.toFloat()
        when (property) {
            PamMotionProperty.OPACITY -> view.alpha = number.coerceIn(0f, 1f)
            PamMotionProperty.TRANSLATE_X -> view.translationX = number
            PamMotionProperty.TRANSLATE_Y -> view.translationY = number
            PamMotionProperty.SCALE -> {
                view.scaleX = number
                view.scaleY = number
            }
            PamMotionProperty.SCALE_X -> view.scaleX = number
            PamMotionProperty.SCALE_Y -> view.scaleY = number
            PamMotionProperty.ROTATE -> view.rotation = number
            PamMotionProperty.RADIUS -> applyRadius(view, number.coerceAtLeast(0f))
        }
    }

    private fun applyRadius(view: View, radius: Float) {
        view.setTag(dev.pam.nativeapp.R.id.pam_motion_radius, radius)
        if (view.outlineProvider !is RadiusOutline) {
            view.outlineProvider = RadiusOutline
        }
        view.clipToOutline = radius > 0f
        view.invalidateOutline()
    }

    private object RadiusOutline : android.view.ViewOutlineProvider() {
        override fun getOutline(view: View, outline: android.graphics.Outline) {
            val radius = (view.getTag(dev.pam.nativeapp.R.id.pam_motion_radius) as? Float) ?: 0f
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }
}

/**
 * Plays a [PamMotionTimeline] on one view from a single frame-synchronised
 * animator. No PHP callback runs until [onComplete].
 */
internal class PamMotionRunner(
    private val view: View,
    private val timeline: PamMotionTimeline,
    private val iterations: Int,
    private val onFrame: (() -> Unit)? = null,
    private val onComplete: (() -> Unit)? = null,
) {
    private var animator: ValueAnimator? = null
    private var cancelled = false

    val isRunning: Boolean get() = animator?.isRunning == true

    fun start(reducedMotion: Boolean) {
        if (reducedMotion || timeline.durationMs <= 0L || iterations == 0) {
            applyAt(timeline.durationMs)
            onComplete?.invoke()
            return
        }
        val duration = timeline.durationMs
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = LinearInterpolator()
            repeatCount = if (iterations < 0) ValueAnimator.INFINITE else iterations - 1
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { applyAt((it.animatedFraction * duration).toLong()) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    applyAt(duration)
                    onComplete?.invoke()
                }
            })
            start()
        }
        applyAt(0L)
    }

    fun cancel() {
        cancelled = true
        animator?.cancel()
        animator = null
    }

    private fun applyAt(ms: Long) {
        for (property in timeline.tracks.keys) {
            timeline.valueAt(property, ms)?.let { PamMotionTarget.write(view, property, it) }
        }
        onFrame?.invoke()
    }
}

/** One resolved CSS transition entry. */
internal data class PamTransitionRule(
    val durationMs: Long,
    val delayMs: Long,
    val easing: String,
    val spring: PamSpringConfig?,
)

/**
 * `TransitionSpec` mirrors the CSS longhand lists:
 * `@property a,b`, `@duration ms,ms`, `@timing token,token`, `@delay ms,ms`.
 * Lists shorter than `@property` repeat cyclically, as in CSS.
 */
internal object PamTransitionSpec {
    fun parse(source: String): Map<String, PamTransitionRule> {
        val lists = mutableMapOf<String, List<String>>()
        for (line in source.lineSequence().map(String::trim).filter { it.startsWith("@") }) {
            val space = line.indexOf(' ')
            if (space < 0) continue
            lists[line.substring(1, space)] = line.substring(space + 1).split(',').map(String::trim)
        }
        val properties = lists["property"]?.filter(String::isNotEmpty) ?: listOf("all")
        val durations = lists["duration"] ?: listOf("0")
        val timings = lists["timing"] ?: listOf("ease")
        val delays = lists["delay"] ?: listOf("0")
        val rules = linkedMapOf<String, PamTransitionRule>()
        properties.forEachIndexed { index, property ->
            if (property == "none") return@forEachIndexed
            val duration = durations[index % durations.size].toLongOrNull()?.coerceIn(0L, 60_000L) ?: 0L
            val delay = delays[index % delays.size].toLongOrNull()?.coerceIn(0L, 60_000L) ?: 0L
            val timing = timings[index % timings.size]
            val spring = if (timing.startsWith("spring:")) {
                val values = timing.removePrefix("spring:").split(':').map { it.toDoubleOrNull() }
                runCatching {
                    PamSpringConfig(
                        stiffness = values.getOrNull(0) ?: 100.0,
                        damping = values.getOrNull(1) ?: 10.0,
                        mass = values.getOrNull(2) ?: 1.0,
                    )
                }.getOrNull()
            } else {
                null
            }
            rules[property] = PamTransitionRule(duration, delay, timing, spring)
        }
        return rules
    }

    /** CSS property names that govern a motion property, most specific first. */
    fun keysFor(property: PamMotionProperty): List<String> =
        when (property) {
            PamMotionProperty.OPACITY -> listOf("opacity", "all")
            PamMotionProperty.TRANSLATE_X,
            PamMotionProperty.TRANSLATE_Y,
            -> listOf("translate", "transform", "all")
            PamMotionProperty.SCALE,
            PamMotionProperty.SCALE_X,
            PamMotionProperty.SCALE_Y,
            -> listOf("scale", "transform", "all")
            PamMotionProperty.ROTATE -> listOf("rotate", "transform", "all")
            PamMotionProperty.RADIUS -> listOf("border-radius", "all")
        }

    fun ruleFor(rules: Map<String, PamTransitionRule>, property: PamMotionProperty): PamTransitionRule? =
        keysFor(property).firstNotNullOfOrNull(rules::get)
}
