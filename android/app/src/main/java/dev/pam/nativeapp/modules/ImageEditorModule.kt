package dev.pam.nativeapp.modules

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

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
        val decoded = BitmapFactory.decodeFile(source.path) ?: error("Unable to decode the image.")
        var current = orient(decoded, values.integer("quarterTurns"), values.integer("flipHorizontal") == 1)
        if (current !== decoded) decoded.recycle()
        val cropped = crop(current, values.integer("cropRatio"))
        if (cropped !== current) current.recycle()
        val filtered = filter(cropped, values.integer("filter"))
        if (filtered !== cropped) cropped.recycle()
        val adjusted = adjust(filtered, values.integer("brightness"), values.integer("contrast"), values.integer("saturation"))
        if (adjusted !== filtered) filtered.recycle()
        val withText = compose(adjusted, values.text("overlayText").trim().take(120), false)
        if (withText !== adjusted) adjusted.recycle()
        val composed = compose(withText, values.text("sticker").trim().take(8), true)
        if (composed !== withText) withText.recycle()

        val directory = File(root, "editor").apply { mkdirs() }.canonicalFile
        require(directory.path.startsWith(root.path + File.separator))
        val output = File(directory, "image-edit-${System.nanoTime()}.jpg")
        FileOutputStream(output).buffered().use {
            check(composed.compress(Bitmap.CompressFormat.JPEG, 94, it))
        }
        composed.recycle()
        return output
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

    private fun resolve(path: String): File = File(root, path).canonicalFile.also {
        require(it.path.startsWith(root.path + File.separator) && it.isFile) { "Invalid editor source" }
    }

    private fun Map<String, WireValue>.requiredText(key: String) = (this[key] as? WireValue.Text)?.value ?: error("Missing field $key")
    private fun Map<String, WireValue>.text(key: String) = (this[key] as? WireValue.Text)?.value ?: ""
    private fun Map<String, WireValue>.integer(key: String) = ((this[key] as? WireValue.Integer)?.value ?: 0L).toInt()
    private fun ModuleCompletion.failure(message: String) = complete(ModuleResultStatus.FAILURE, message.toByteArray())
    override fun close() {
        executor.shutdownNow()
    }
}
