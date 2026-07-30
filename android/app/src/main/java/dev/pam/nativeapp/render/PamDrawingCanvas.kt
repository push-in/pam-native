package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.min

internal data class PamDrawingPoint(
    val x: Float,
    val y: Float,
)

internal data class PamDrawingStroke(
    val color: Int,
    val width: Float,
    val mode: Int,
    val points: List<PamDrawingPoint>,
)

internal object PamDrawingCodec {
    const val MAX_STROKES = 256
    const val MAX_POINTS_PER_STROKE = 2_048

    fun decode(value: String): List<PamDrawingStroke> {
        if (value.isBlank()) return emptyList()
        val source = JSONObject(value).optJSONArray("strokes") ?: return emptyList()
        val strokes = ArrayList<PamDrawingStroke>(min(source.length(), MAX_STROKES))
        for (index in 0 until min(source.length(), MAX_STROKES)) {
            val item = source.optJSONObject(index) ?: continue
            val rawPoints = item.optJSONArray("points") ?: continue
            val pointCount = min(rawPoints.length() / 2, MAX_POINTS_PER_STROKE)
            if (pointCount <= 0) continue
            val points = ArrayList<PamDrawingPoint>(pointCount)
            for (pointIndex in 0 until pointCount) {
                val offset = pointIndex * 2
                points += PamDrawingPoint(
                    rawPoints.optDouble(offset).toFloat().coerceIn(0f, 1f),
                    rawPoints.optDouble(offset + 1).toFloat().coerceIn(0f, 1f),
                )
            }
            strokes += PamDrawingStroke(
                color = item.optLong("color", 0xFFFFFFFFL).toInt(),
                width = item.optDouble("width", 6.0 / 360.0)
                    .toFloat()
                    .coerceIn(0.0005f, 0.25f),
                mode = item.optInt("mode", 1).coerceIn(1, 2),
                points = points,
            )
        }
        return strokes
    }

    fun encode(strokes: List<PamDrawingStroke>): String =
        JSONObject().apply {
            put("version", 1)
            put(
                "strokes",
                JSONArray().apply {
                    strokes.takeLast(MAX_STROKES).forEach { stroke ->
                        put(
                            JSONObject().apply {
                                put("color", stroke.color.toLong() and 0xFFFFFFFFL)
                                put("width", stroke.width.toDouble())
                                put("mode", stroke.mode)
                                put(
                                    "points",
                                    JSONArray().apply {
                                        stroke.points
                                            .take(MAX_POINTS_PER_STROKE)
                                            .forEach { point ->
                                                put(point.x.toDouble())
                                                put(point.y.toDouble())
                                            }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }.toString()
}

internal class PamDrawingCanvas(context: Context) : FrameLayout(context) {
    val image = PamImageView(context).apply {
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
    }
    private val overlay = DrawingOverlay(context)
    private var encodedValue = ""
    private var brushColor = Color.WHITE
    private var brushWidth = 6f
    private var drawingMode = 1
    private var clearRequest = 0
    private var undoRequest = 0
    private var onChange: ((String) -> Unit)? = null

    init {
        setWillNotDraw(false)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setDrawing(value: String) {
        if (value == encodedValue) return
        val decoded = runCatching { PamDrawingCodec.decode(value) }.getOrDefault(emptyList())
        overlay.setStrokes(decoded)
        encodedValue = if (decoded.isEmpty()) "" else PamDrawingCodec.encode(decoded)
    }

    fun setBrushColor(value: Int) {
        brushColor = value
    }

    fun setBrushWidth(value: Float) {
        brushWidth = value.coerceIn(1f, 64f)
    }

    fun setDrawingMode(value: Int) {
        drawingMode = value.coerceIn(1, 2)
    }

    fun setClearRequest(value: Int) {
        if (value == clearRequest) return
        clearRequest = value
        if (value > 0 && overlay.clear()) emitChange()
    }

    fun setUndoRequest(value: Int) {
        if (value == undoRequest) return
        undoRequest = value
        if (value > 0 && overlay.undo()) emitChange()
    }

    fun setOnDrawingChange(callback: ((String) -> Unit)?) {
        onChange = callback
    }

    private fun emitChange() {
        encodedValue = PamDrawingCodec.encode(overlay.strokes())
        onChange?.invoke(encodedValue)
    }

    private inner class DrawingOverlay(context: Context) : View(context) {
        private val contentRect = RectF()
        private val strokes = ArrayList<PamDrawingStroke>()
        private var activePoints: MutableList<PamDrawingPoint>? = null

        fun setStrokes(value: List<PamDrawingStroke>) {
            strokes.clear()
            strokes.addAll(value)
            activePoints = null
            invalidate()
        }

        fun strokes(): List<PamDrawingStroke> = strokes

        fun clear(): Boolean {
            if (strokes.isEmpty() && activePoints == null) return false
            strokes.clear()
            activePoints = null
            invalidate()
            return true
        }

        fun undo(): Boolean {
            if (strokes.isEmpty()) return false
            strokes.removeAt(strokes.lastIndex)
            invalidate()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            updateContentRect()
            val checkpoint = canvas.saveLayer(contentRect, null)
            strokes.forEach { drawStroke(canvas, it) }
            activePoints?.let { points ->
                drawStroke(
                    canvas,
                    activeStroke(points),
                )
            }
            canvas.restoreToCount(checkpoint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!isEnabled || !this@PamDrawingCanvas.isEnabled) return false
            updateContentRect()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!contentRect.contains(event.x, event.y)) return false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    activePoints = mutableListOf(normalized(event.x, event.y))
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val points = activePoints ?: return false
                    for (index in 0 until event.historySize) {
                        appendPoint(points, event.getHistoricalX(index), event.getHistoricalY(index))
                    }
                    appendPoint(points, event.x, event.y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val points = activePoints ?: return false
                    appendPoint(points, event.x, event.y)
                    strokes += PamDrawingStroke(
                        brushColor,
                        normalizedBrushWidth(),
                        drawingMode,
                        points.toList(),
                    )
                    if (strokes.size > PamDrawingCodec.MAX_STROKES) {
                        strokes.removeAt(0)
                    }
                    activePoints = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    emitChange()
                    performClick()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    activePoints = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    invalidate()
                    return true
                }
            }
            return false
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        private fun appendPoint(
            points: MutableList<PamDrawingPoint>,
            x: Float,
            y: Float,
        ) {
            if (points.size >= PamDrawingCodec.MAX_POINTS_PER_STROKE) return
            val next = normalized(x, y)
            val previous = points.lastOrNull()
            val minimumX = 1.25f / contentRect.width().coerceAtLeast(1f)
            val minimumY = 1.25f / contentRect.height().coerceAtLeast(1f)
            if (
                previous == null ||
                hypot(
                    ((next.x - previous.x) / minimumX).toDouble(),
                    ((next.y - previous.y) / minimumY).toDouble(),
                ) >= 1.0
            ) {
                points += next
            }
        }

        private fun normalized(x: Float, y: Float) = PamDrawingPoint(
            ((x - contentRect.left) / contentRect.width().coerceAtLeast(1f))
                .coerceIn(0f, 1f),
            ((y - contentRect.top) / contentRect.height().coerceAtLeast(1f))
                .coerceIn(0f, 1f),
        )

        private fun activeStroke(points: List<PamDrawingPoint>) =
            PamDrawingStroke(
                brushColor,
                normalizedBrushWidth(),
                drawingMode,
                points,
            )

        private fun normalizedBrushWidth(): Float =
            (
                brushWidth *
                    resources.displayMetrics.density /
                    contentRect.width().coerceAtLeast(1f)
                ).coerceIn(0.0005f, 0.25f)

        private fun updateContentRect() {
            val drawable = image.drawable
            val sourceWidth = drawable?.intrinsicWidth?.takeIf { it > 0 } ?: width
            val sourceHeight = drawable?.intrinsicHeight?.takeIf { it > 0 } ?: height
            val scale = min(
                width.toFloat() / sourceWidth.coerceAtLeast(1),
                height.toFloat() / sourceHeight.coerceAtLeast(1),
            )
            val displayedWidth = sourceWidth * scale
            val displayedHeight = sourceHeight * scale
            contentRect.set(
                (width - displayedWidth) / 2f,
                (height - displayedHeight) / 2f,
                (width + displayedWidth) / 2f,
                (height + displayedHeight) / 2f,
            )
        }

        private fun drawStroke(canvas: Canvas, stroke: PamDrawingStroke) {
            if (stroke.points.isEmpty() || contentRect.isEmpty) return
            val path = Path()
            stroke.points.forEachIndexed { index, point ->
                val x = contentRect.left + point.x * contentRect.width()
                val y = contentRect.top + point.y * contentRect.height()
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = stroke.color
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
                strokeWidth = stroke.width * contentRect.width()
                if (stroke.mode == 2) {
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            }
            if (stroke.points.size == 1) {
                val point = stroke.points[0]
                canvas.drawPoint(
                    contentRect.left + point.x * contentRect.width(),
                    contentRect.top + point.y * contentRect.height(),
                    paint,
                )
            } else {
                canvas.drawPath(path, paint)
            }
        }
    }
}
