package dev.pam.nativeapp.render

import java.util.Locale
import kotlin.math.floor

internal enum class PamDragTextFormat(val code: Int) {
    NUMBER(1), CLOCK(2), CLOCK_WITH_TOTAL(3);
}

internal data class PamDragTextDriver(
    val ref: String,
    val format: PamDragTextFormat,
    val decimals: Int,
    val input: List<PamMotionValue>,
    val output: List<Double>,
) {
    fun label(position: Double, extent: Double): String {
        val value = PamDragMath.interpolate(input.map { if (it.percent) extent * it.number / 100 else it.number }, output, position)
        return when (format) {
            PamDragTextFormat.NUMBER -> String.format(Locale.ROOT, "%.$decimals" + "f", value)
            PamDragTextFormat.CLOCK -> clock(value)
            PamDragTextFormat.CLOCK_WITH_TOTAL -> "${clock(value)} / ${clock(output.last())}"
        }
    }

    companion object {
        fun parse(source: String): PamDragTextDriver? {
            val parts = source.split('|')
            if (parts.size != 5 || !parts[0].matches(Regex("[A-Za-z0-9_.:-]{1,64}"))) return null
            val format = PamDragTextFormat.entries.firstOrNull { it.code == parts[1].toIntOrNull() } ?: return null
            val decimals = parts[2].toIntOrNull()?.takeIf { it in 0..3 } ?: return null
            val input = parts[3].split(',').map { PamMotionValue.parse(it) ?: return null }
            val output = parts[4].split(',').map { it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null }
            if (input.size !in 2..8 || input.size != output.size) return null
            return PamDragTextDriver(parts[0], format, decimals, input, output)
        }

        private fun clock(value: Double): String {
            val seconds = floor(value.coerceIn(0.0, 3_600_000.0)).toLong()
            val tail = "${(seconds / 60 % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
            return if (seconds >= 3600) "${seconds / 3600}:$tail" else "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }
    }
}
