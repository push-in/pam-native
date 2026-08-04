package dev.pam.nativeapp.modules

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.pam.nativeapp.render.PamDrawingCodec
import dev.pam.nativeapp.render.PamDrawingStroke
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import org.json.JSONArray
import kotlin.math.floor
import kotlin.math.min

internal class ImageEditorModule(context: Context) : NativeModule, AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val root = File(context.filesDir, "pam-files").canonicalFile

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        if (method != "render") {
            completion.failure("Unknown image editor method $method")
            return
        }
        val values = WireMap.decode(payload)
        executor.execute {
            runCatching { render(values) }
                .onSuccess { output ->
                    completion.complete(
                        ModuleResultStatus.SUCCESS,
                        WireMap.encode(
                            mapOf(
                                "name" to WireValue.Text(output.name),
                                "path" to WireValue.Text(output.relativeTo(root).path),
                                "size" to WireValue.Integer(output.length()),
                            ),
                        ),
                    )
                }
                .onFailure { completion.failure(it.message ?: "Unable to edit the image.") }
        }
    }

    private fun render(values: Map<String, WireValue>): File {
        val source = resolve(values.requiredText("path"))
        val maxWidth = values.integer("maxWidth").coerceAtLeast(0)
        val maxHeight = values.integer("maxHeight").coerceAtLeast(0)
        val decoded = decode(source, maxWidth, maxHeight)
        var current = composeDrawing(decoded, values.text("drawing"))
        if (current !== decoded) decoded.recycle()
        val oriented = orient(
            current,
            values.integer("quarterTurns"),
            values.integer("flipHorizontal") == 1,
        )
        if (oriented !== current) current.recycle()
        current = oriented
        val cropped = crop(current, values.integer("cropRatio"))
        if (cropped !== current) current.recycle()
        val filtered = filter(cropped, values.integer("filter"))
        if (filtered !== cropped) cropped.recycle()
        val adjusted = adjust(filtered, values.integer("brightness"), values.integer("contrast"), values.integer("saturation"))
        if (adjusted !== filtered) filtered.recycle()
        val layered = composeTextLayers(adjusted, values.text("textLayers"))
        if (layered !== adjusted) adjusted.recycle()
        val withText = compose(layered, values.text("overlayText").trim().take(120), false)
        if (withText !== layered) layered.recycle()
        val composed = compose(withText, values.text("sticker").trim().take(8), true)
        if (composed !== withText) withText.recycle()
        val resized = resize(composed, maxWidth, maxHeight)
        if (resized !== composed) composed.recycle()

        val directory = File(root, "editor").apply { mkdirs() }.canonicalFile
        require(directory.path.startsWith(root.path + File.separator))
        val output = File(directory, "image-edit-${System.nanoTime()}.jpg")
        FileOutputStream(output).buffered().use {
            check(
                resized.compress(
                    Bitmap.CompressFormat.JPEG,
                    values.integerOr("outputQuality", 94).coerceIn(1, 100),
                    it,
                ),
            )
        }
        resized.recycle()
        return output
    }

    private fun decode(source: File, maxWidth: Int, maxHeight: Int): Bitmap {
        if (maxWidth == 0 && maxHeight == 0) {
            return BitmapFactory.decodeFile(source.path) ?: error("Unable to decode the image.")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to decode the image." }
        val requestedWidth = if (maxWidth > 0) maxWidth else bounds.outWidth
        val requestedHeight = if (maxHeight > 0) maxHeight else bounds.outHeight
        var sampleSize = 1
        while (
            bounds.outWidth / (sampleSize * 2) >= requestedWidth
            && bounds.outHeight / (sampleSize * 2) >= requestedHeight
        ) {
            sampleSize *= 2
        }
        return BitmapFactory.decodeFile(
            source.path,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sampleSize
            },
        ) ?: error("Unable to decode the image.")
    }

    private fun resize(source: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (maxWidth == 0 && maxHeight == 0) return source
        val widthScale = if (maxWidth > 0) maxWidth.toFloat() / source.width else 1f
        val heightScale = if (maxHeight > 0) maxHeight.toFloat() / source.height else 1f
        val scale = min(1f, min(widthScale, heightScale))
        if (scale >= 1f) return source
        val width = floor(source.width * scale).toInt().coerceAtLeast(1)
        val height = floor(source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun orient(source: Bitmap, turns: Int, flip: Boolean): Bitmap {
        if (turns == 0 && !flip) return source
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply {
            if (flip) postScale(-1f, 1f)
            if (turns != 0) postRotate(turns * 90f)
        }, true)
    }

    private fun crop(source: Bitmap, ratio: Int): Bitmap {
        val target = when (ratio) {
            2 -> 1f
            3 -> 4f / 5f
            4 -> 9f / 16f
            5 -> 16f / 9f
            else -> return source
        }
        val current = source.width.toFloat() / source.height
        val width = if (current > target) (source.height * target).toInt().coerceAtLeast(1) else source.width
        val height = if (current > target) source.height else (source.width / target).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(source, (source.width - width) / 2, (source.height - height) / 2, width, height)
    }

    private fun filter(source: Bitmap, type: Int): Bitmap {
        val matrix = when (type) {
            2 -> ColorMatrix().apply { setSaturation(0f) }
            3 -> ColorMatrix().apply { setSaturation(1.35f) }
            4 -> ColorMatrix(floatArrayOf(1.10f,0f,0f,0f,5f, 0f,1.02f,0f,0f,2f, 0f,0f,0.90f,0f,0f, 0f,0f,0f,1f,0f))
            5 -> ColorMatrix(floatArrayOf(0.92f,0f,0f,0f,0f, 0f,1.01f,0f,0f,1f, 0f,0f,1.10f,0f,5f, 0f,0f,0f,1f,0f))
            else -> return source
        }
        return applyMatrix(source, matrix)
    }

    private fun adjust(source: Bitmap, brightness: Int, contrast: Int, saturation: Int): Bitmap {
        if (brightness == 0 && contrast == 0 && saturation == 0) return source
        val scale = 1f + contrast.coerceIn(-100, 100) / 100f
        val translate = 128f * (1f - scale) + brightness.coerceIn(-100, 100) * 1.6f
        val matrix = ColorMatrix(floatArrayOf(scale,0f,0f,0f,translate, 0f,scale,0f,0f,translate, 0f,0f,scale,0f,translate, 0f,0f,0f,1f,0f))
        matrix.postConcat(ColorMatrix().apply { setSaturation((1f + saturation.coerceIn(-100, 100) / 100f).coerceAtLeast(0f)) })
        return applyMatrix(source, matrix)
    }

    private fun applyMatrix(source: Bitmap, matrix: ColorMatrix) =
        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(source, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            })
        }

    private fun compose(source: Bitmap, value: String, sticker: Boolean): Bitmap {
        if (value.isEmpty()) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val size = (source.width * if (sticker) 0.20f else 0.072f).coerceIn(if (sticker) 72f else 34f, if (sticker) 320f else 110f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = size
            textAlign = Paint.Align.CENTER
            typeface = if (sticker) android.graphics.Typeface.DEFAULT else android.graphics.Typeface.DEFAULT_BOLD
            setShadowLayer(size * 0.1f, 0f, size * 0.07f, 0xCC000000.toInt())
        }
        val baseline = source.height * (if (sticker) 0.48f else 0.72f) - (paint.ascent() + paint.descent()) / 2f
        Canvas(output).drawText(value, source.width / 2f, baseline, paint)
        return output
    }

    private fun composeTextLayers(source: Bitmap, encoded: String): Bitmap {
        val layers = runCatching { JSONArray(encoded) }.getOrNull() ?: return source
        if (layers.length() == 0) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        repeat(layers.length().coerceAtMost(80)) { index ->
            val layer = layers.optJSONObject(index) ?: return@repeat
            val text = layer.optString("text").trim().take(500)
            if (text.isEmpty()) return@repeat
            val scale = layer.optDouble("scale", 1.0).toFloat().coerceIn(0.25f, 4f)
            val textSize = (source.width * 0.055f * scale).coerceIn(18f, source.width * 0.22f)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = runCatching { android.graphics.Color.parseColor(layer.optString("color", "#FFFFFF")) }
                    .getOrDefault(android.graphics.Color.WHITE)
                this.textSize = textSize
                textAlign = Paint.Align.LEFT
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                if (layer.optInt("styleType", 1) == 1) {
                    setShadowLayer(textSize * 0.09f, 0f, textSize * 0.06f, 0xCC000000.toInt())
                }
            }
            val width = (source.width * 0.78f).toInt().coerceAtLeast(1)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setIncludePad(false)
                .setMaxLines(8)
                .build()
            val padding = textSize * 0.36f
            val boxWidth = layout.width + padding * 2
            val boxHeight = layout.height + padding * 2
            val centerX = layer.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f) * source.width
            val centerY = layer.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f) * source.height
            canvas.save()
            canvas.translate(centerX, centerY)
            canvas.rotate(Math.toDegrees(layer.optDouble("rotation", 0.0)).toFloat())
            val style = layer.optInt("styleType", 1).coerceIn(1, 3)
            if (style != 1) {
                val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (style == 2) 0xEFFFFFFF.toInt() else 0xB3101318.toInt()
                    this.style = Paint.Style.FILL
                }
                canvas.drawRoundRect(
                    RectF(-boxWidth / 2, -boxHeight / 2, boxWidth / 2, boxHeight / 2),
                    textSize * 0.28f,
                    textSize * 0.28f,
                    box,
                )
            }
            canvas.translate(-layout.width / 2f, -layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
        return output
    }

    private fun composeDrawing(source: Bitmap, value: String): Bitmap {
        val strokes = runCatching { PamDrawingCodec.decode(value) }
            .getOrDefault(emptyList())
        if (strokes.isEmpty()) return source
        val overlay = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888,
        )
        val overlayCanvas = Canvas(overlay)
        strokes.forEach { stroke ->
            drawStroke(overlayCanvas, stroke, source.width, source.height)
        }
        return source.copy(Bitmap.Config.ARGB_8888, true).also { output ->
            Canvas(output).drawBitmap(overlay, 0f, 0f, null)
            overlay.recycle()
        }
    }

    private fun drawStroke(
        canvas: Canvas,
        stroke: PamDrawingStroke,
        width: Int,
        height: Int,
    ) {
        if (stroke.points.isEmpty()) return
        val path = android.graphics.Path()
        stroke.points.forEachIndexed { index, point ->
            val x = point.x * width
            val y = point.y * height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.color
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = (stroke.width * width).coerceAtLeast(1f)
            if (stroke.mode == 2) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        }
        if (stroke.points.size == 1) {
            canvas.drawPoint(
                stroke.points[0].x * width,
                stroke.points[0].y * height,
                paint,
            )
        } else {
            canvas.drawPath(path, paint)
        }
    }

    private fun resolve(path: String): File = File(root, path).canonicalFile.also {
        require(it.path.startsWith(root.path + File.separator) && it.isFile) { "Invalid editor source" }
    }

    private fun Map<String, WireValue>.requiredText(key: String) = (this[key] as? WireValue.Text)?.value ?: error("Missing field $key")
    private fun Map<String, WireValue>.text(key: String) = (this[key] as? WireValue.Text)?.value ?: ""
    private fun Map<String, WireValue>.integer(key: String) = ((this[key] as? WireValue.Integer)?.value ?: 0L).toInt()
    private fun Map<String, WireValue>.integerOr(key: String, fallback: Int) =
        ((this[key] as? WireValue.Integer)?.value ?: fallback.toLong()).toInt()
    private fun ModuleCompletion.failure(message: String) = complete(ModuleResultStatus.FAILURE, message.toByteArray())
    override fun close() {
        executor.shutdownNow()
    }
}
