package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import org.json.JSONArray

internal class PamVectorCanvas(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val roundedRect = RectF()
    private var commands = JSONArray()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setCommands(value: String) {
        commands = runCatching { JSONArray(value) }.getOrDefault(JSONArray())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        repeat(commands.length().coerceAtMost(MAX_COMMANDS)) { index ->
            val command = commands.optJSONObject(index) ?: return@repeat
            paint.color = command.optLong("color", 0L).toInt()
            paint.style = Paint.Style.FILL
            when (command.optInt("kind")) {
                RECTANGLE -> canvas.drawRect(
                    command.number("x"),
                    command.number("y"),
                    command.number("x") + command.number("width"),
                    command.number("y") + command.number("height"),
                    paint,
                )
                ROUNDED_RECTANGLE -> {
                    val x = command.number("x")
                    val y = command.number("y")
                    val radius = command.number("radius").coerceAtLeast(0f)
                    roundedRect.set(
                        x,
                        y,
                        x + command.number("width"),
                        y + command.number("height"),
                    )
                    canvas.drawRoundRect(roundedRect, radius, radius, paint)
                }
                CIRCLE -> canvas.drawCircle(
                    command.number("centerX"),
                    command.number("centerY"),
                    command.number("radius").coerceAtLeast(0f),
                    paint,
                )
                LINE -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = command.number("width").coerceAtLeast(0f)
                    paint.strokeCap = Paint.Cap.ROUND
                    canvas.drawLine(
                        command.number("startX"),
                        command.number("startY"),
                        command.number("endX"),
                        command.number("endY"),
                        paint,
                    )
                }
            }
        }
    }

    private fun org.json.JSONObject.number(name: String): Float =
        optDouble(name, 0.0).toFloat().takeIf(Float::isFinite) ?: 0f

    private companion object {
        const val MAX_COMMANDS = 10_000
        const val RECTANGLE = 1
        const val ROUNDED_RECTANGLE = 2
        const val CIRCLE = 3
        const val LINE = 4
    }
}
