package dev.pam.nativeapp.render

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.LruCache
import org.json.JSONArray
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * CSS linear/radial gradient layer decoded from the compiler wire format
 * (see `CssEffects.php`). Decoding is cached per wire string so list rows
 * sharing a class share the parsed model; shaders are rebuilt only when the
 * painted box changes size.
 */
internal class PamGradientLayer(
    val type: Int,
    val repeating: Boolean,
    val mode: Int,
    val angle: Float,
    val cornerX: Int,
    val cornerY: Int,
    val points: FloatArray,
    val ellipse: Boolean,
    val size: Int,
    val sizeW: Float,
    val sizeWUnit: Int,
    val sizeH: Float,
    val sizeHUnit: Int,
    val centerX: Float,
    val centerXUnit: Int,
    val centerY: Float,
    val centerYUnit: Int,
    val colors: IntArray,
    val positions: FloatArray,
    val units: IntArray,
) {
    /** Builds the shader for a box of [width]×[height] px at [density] px/dp. */
    fun shader(width: Float, height: Float, density: Float): Shader? {
        if (width <= 0f || height <= 0f || colors.isEmpty()) return null
        return if (type == TYPE_RADIAL) radial(width, height, density) else linear(width, height, density)
    }

    private fun linear(width: Float, height: Float, density: Float): Shader {
        val startX: Float
        val startY: Float
        val endX: Float
        val endY: Float
        if (mode == MODE_POINTS) {
            startX = points[0] * width
            startY = points[1] * height
            endX = points[2] * width
            endY = points[3] * height
        } else {
            val radians = if (mode == MODE_CORNER) {
                // Perpendicular to the diagonal joining the two neighbouring corners.
                atan2(cornerX * height, -(cornerY * width))
            } else {
                Math.toRadians(angle.toDouble()).toFloat()
            }
            val dx = sin(radians)
            val dy = -cos(radians)
            val length = abs(width * dx) + abs(height * dy)
            startX = width / 2f - dx * length / 2f
            startY = height / 2f - dy * length / 2f
            endX = width / 2f + dx * length / 2f
            endY = height / 2f + dy * length / 2f
        }
        val length = hypot(endX - startX, endY - startY).coerceAtLeast(MIN_LENGTH)
        val stops = resolve(length, density)
        val first = stops.positions.first()
        val last = stops.positions.last()
        val span = (last - first).coerceAtLeast(MIN_SPAN)
        val unitX = (endX - startX)
        val unitY = (endY - startY)
        val normalized = normalize(stops, first, span)
        return LinearGradient(
            startX + unitX * first,
            startY + unitY * first,
            startX + unitX * (first + span),
            startY + unitY * (first + span),
            normalized.colors,
            normalized.positions,
            if (repeating) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP,
        )
    }

    private fun radial(width: Float, height: Float, density: Float): Shader {
        val cx = resolveLength(centerX, centerXUnit, width, density)
        val cy = resolveLength(centerY, centerYUnit, height, density)
        val left = abs(cx)
        val right = abs(width - cx)
        val top = abs(cy)
        val bottom = abs(height - cy)
        var rx: Float
        var ry: Float
        if (size == SIZE_EXPLICIT) {
            rx = resolveLength(sizeW, sizeWUnit, width, density)
            ry = if (ellipse) resolveLength(sizeH, sizeHUnit, height, density) else rx
        } else if (!ellipse) {
            val corners = floatArrayOf(
                hypot(left, top),
                hypot(right, top),
                hypot(left, bottom),
                hypot(right, bottom),
            )
            rx = when (size) {
                SIZE_CLOSEST_SIDE -> min(min(left, right), min(top, bottom))
                SIZE_FARTHEST_SIDE -> max(max(left, right), max(top, bottom))
                SIZE_CLOSEST_CORNER -> corners.min()
                else -> corners.max()
            }
            ry = rx
        } else {
            val closest = size == SIZE_CLOSEST_SIDE || size == SIZE_CLOSEST_CORNER
            val sideX = if (closest) min(left, right) else max(left, right)
            val sideY = if (closest) min(top, bottom) else max(top, bottom)
            if (size == SIZE_CLOSEST_SIDE || size == SIZE_FARTHEST_SIDE) {
                rx = sideX
                ry = sideY
            } else {
                // Passes through the chosen corner with the matching side ratio.
                rx = sideX * SQRT_2
                ry = sideY * SQRT_2
            }
        }
        rx = rx.coerceAtLeast(MIN_LENGTH)
        ry = ry.coerceAtLeast(MIN_LENGTH)
        val stops = resolve(rx, density)
        for (index in stops.positions.indices) {
            stops.positions[index] = stops.positions[index].coerceAtLeast(0f)
        }
        val first = if (repeating) stops.positions.first() else 0f
        val last = stops.positions.last()
        val span = (last - first).coerceAtLeast(MIN_SPAN)
        val normalized = if (repeating && first > 0f) {
            rotate(stops, first, span)
        } else {
            normalize(stops, 0f, span)
        }
        return RadialGradient(
            cx,
            cy,
            rx * span,
            normalized.colors,
            normalized.positions,
            if (repeating) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP,
        ).apply {
            if (abs(rx - ry) > 0.01f) {
                setLocalMatrix(Matrix().apply { setScale(1f, ry / rx, cx, cy) })
            }
        }
    }

    /** CSS color-stop fix-up: defaults, monotonic clamping, auto distribution. */
    private fun resolve(lineLength: Float, density: Float): Stops {
        val count = colors.size
        val resolved = FloatArray(count) { index ->
            when (units[index]) {
                UNIT_FRACTION -> positions[index]
                UNIT_DP -> positions[index] * density / lineLength
                else -> Float.NaN
            }
        }
        if (resolved[0].isNaN()) resolved[0] = 0f
        if (resolved[count - 1].isNaN()) resolved[count - 1] = 1f
        var maximum = resolved[0]
        for (index in 1 until count) {
            if (!resolved[index].isNaN()) {
                if (resolved[index] < maximum) resolved[index] = maximum
                maximum = resolved[index]
            }
        }
        var index = 1
        while (index < count) {
            if (resolved[index].isNaN()) {
                val start = index - 1
                var end = index
                while (resolved[end].isNaN()) end++
                val from = resolved[start]
                val to = resolved[end]
                for (fill in start + 1 until end) {
                    resolved[fill] = from + (to - from) * (fill - start) / (end - start)
                }
                index = end
            }
            index++
        }
        return Stops(colors.copyOf(), resolved)
    }

    private fun normalize(stops: Stops, origin: Float, span: Float): Stops =
        premultiplied(
            Stops(
                stops.colors,
                FloatArray(stops.positions.size) { index ->
                    ((stops.positions[index] - origin) / span).coerceIn(0f, 1f)
                },
            ),
        )

    /** Repeating radial gradients whose first stop is past the center. */
    private fun rotate(stops: Stops, first: Float, span: Float): Stops {
        val offset = ((first % span) + span) % span
        val wrapColor = colorAt(stops, first + span - offset)
        val shifted = ArrayList<Pair<Float, Int>>()
        shifted += 0f to wrapColor
        for (index in stops.positions.indices) {
            var position = stops.positions[index] - first + offset
            if (position > span) position -= span
            shifted += position to stops.colors[index]
        }
        shifted += span to wrapColor
        shifted.sortBy { it.first }
        return normalize(
            Stops(
                IntArray(shifted.size) { shifted[it].second },
                FloatArray(shifted.size) { shifted[it].first },
            ),
            0f,
            span,
        )
    }

    private fun colorAt(stops: Stops, position: Float): Int {
        val positions = stops.positions
        if (position <= positions.first()) return stops.colors.first()
        for (index in 1 until positions.size) {
            if (position <= positions[index]) {
                val range = positions[index] - positions[index - 1]
                val fraction = if (range <= 0f) 1f else (position - positions[index - 1]) / range
                return premultipliedLerp(stops.colors[index - 1], stops.colors[index], fraction)
            }
        }
        return stops.colors.last()
    }

    private class Stops(val colors: IntArray, val positions: FloatArray)

    companion object {
        const val TYPE_LINEAR = 1
        const val TYPE_RADIAL = 2
        const val MODE_ANGLE = 1
        const val MODE_CORNER = 2
        const val MODE_POINTS = 3
        const val UNIT_AUTO = 0
        const val UNIT_FRACTION = 1
        const val UNIT_DP = 2
        const val SIZE_CLOSEST_SIDE = 1
        const val SIZE_CLOSEST_CORNER = 2
        const val SIZE_FARTHEST_SIDE = 3
        const val SIZE_FARTHEST_CORNER = 4
        const val SIZE_EXPLICIT = 5
        private const val MIN_LENGTH = 0.001f
        private const val MIN_SPAN = 0.0001f
        private const val PREMULTIPLIED_STEPS = 8
        private val SQRT_2 = sqrt(2f)
        private val cache = LruCache<String, List<PamGradientLayer>>(128)

        fun parse(wire: String?): List<PamGradientLayer> {
            if (wire.isNullOrEmpty()) return emptyList()
            cache.get(wire)?.let { return it }
            val layers = runCatching { decode(JSONArray(wire)) }.getOrDefault(emptyList())
            cache.put(wire, layers)
            return layers
        }

        private fun decode(array: JSONArray): List<PamGradientLayer> =
            List(array.length()) { index ->
                val layer = array.getJSONObject(index)
                val stops = layer.getJSONArray("s")
                val center = layer.optJSONArray("c")
                val points = layer.optJSONArray("p")
                val width = layer.optJSONArray("w")
                val height = layer.optJSONArray("h")
                PamGradientLayer(
                    type = layer.optInt("t", TYPE_LINEAR),
                    repeating = layer.optInt("r", 0) == 1,
                    mode = layer.optInt("m", MODE_ANGLE),
                    angle = layer.optDouble("a", 180.0).toFloat(),
                    cornerX = layer.optInt("x", 0),
                    cornerY = layer.optInt("y", 0),
                    points = FloatArray(4) { points?.optDouble(it, 0.0)?.toFloat() ?: 0f },
                    ellipse = layer.optInt("e", 1) == 1,
                    size = layer.optInt("z", SIZE_FARTHEST_CORNER),
                    sizeW = width?.optDouble(0, 0.0)?.toFloat() ?: 0f,
                    sizeWUnit = width?.optInt(1, UNIT_DP) ?: UNIT_DP,
                    sizeH = height?.optDouble(0, 0.0)?.toFloat() ?: 0f,
                    sizeHUnit = height?.optInt(1, UNIT_DP) ?: UNIT_DP,
                    centerX = center?.optJSONArray(0)?.optDouble(0, 0.5)?.toFloat() ?: 0.5f,
                    centerXUnit = center?.optJSONArray(0)?.optInt(1, UNIT_FRACTION) ?: UNIT_FRACTION,
                    centerY = center?.optJSONArray(1)?.optDouble(0, 0.5)?.toFloat() ?: 0.5f,
                    centerYUnit = center?.optJSONArray(1)?.optInt(1, UNIT_FRACTION) ?: UNIT_FRACTION,
                    colors = IntArray(stops.length()) { stops.getJSONArray(it).getLong(0).toInt() },
                    positions = FloatArray(stops.length()) {
                        stops.getJSONArray(it).optDouble(1, 0.0).toFloat()
                    },
                    units = IntArray(stops.length()) { stops.getJSONArray(it).optInt(2, UNIT_AUTO) },
                )
            }

        private fun resolveLength(value: Float, unit: Int, reference: Float, density: Float): Float =
            if (unit == UNIT_FRACTION) value * reference else value * density

        /**
         * Browsers interpolate gradients in premultiplied space, so
         * `transparent` (transparent black) never darkens a fade. Android
         * interpolates unpremultiplied: re-express each segment with stops
         * whose straight-alpha interpolation matches the premultiplied one.
         */
        private fun premultiplied(stops: Stops): Stops {
            val colors = ArrayList<Int>(stops.colors.size * 2)
            val positions = ArrayList<Float>(stops.colors.size * 2)
            fun add(color: Int, position: Float) {
                if (colors.isNotEmpty() && colors.last() == color && positions.last() == position) return
                colors += color
                positions += position
            }
            if (stops.colors.size == 1) {
                return Stops(intArrayOf(stops.colors[0], stops.colors[0]), floatArrayOf(0f, 1f))
            }
            for (index in 1 until stops.colors.size) {
                val from = stops.colors[index - 1]
                val to = stops.colors[index]
                val start = stops.positions[index - 1]
                val end = stops.positions[index]
                val fromAlpha = Color.alpha(from)
                val toAlpha = Color.alpha(to)
                when {
                    fromAlpha == toAlpha || (from and 0xFFFFFF) == (to and 0xFFFFFF) -> {
                        add(from, start)
                        add(to, end)
                    }
                    fromAlpha == 0 -> {
                        add(to and 0x00FFFFFF, start)
                        add(to, end)
                    }
                    toAlpha == 0 -> {
                        add(from, start)
                        add(from and 0x00FFFFFF, end)
                    }
                    else -> for (step in 0..PREMULTIPLIED_STEPS) {
                        val fraction = step.toFloat() / PREMULTIPLIED_STEPS
                        add(premultipliedLerp(from, to, fraction), start + (end - start) * fraction)
                    }
                }
            }
            if (colors.size == 1) {
                colors += colors[0]
                positions += positions[0]
            }
            return Stops(colors.toIntArray(), positions.toFloatArray())
        }

        fun premultipliedLerp(from: Int, to: Int, fraction: Float): Int {
            val fromAlpha = Color.alpha(from) / 255f
            val toAlpha = Color.alpha(to) / 255f
            val alpha = fromAlpha + (toAlpha - fromAlpha) * fraction
            if (alpha <= 0f) return 0
            fun channel(shift: Int): Int {
                val a = (from shr shift and 0xFF) * fromAlpha
                val b = (to shr shift and 0xFF) * toAlpha
                return ((a + (b - a) * fraction) / alpha).toInt().coerceIn(0, 255)
            }
            return Color.argb(
                (alpha * 255f + 0.5f).toInt().coerceIn(0, 255),
                channel(16),
                channel(8),
                channel(0),
            )
        }
    }
}
