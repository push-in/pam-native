package dev.pam.nativeapp.modules

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import org.json.JSONArray
import kotlin.math.max
import kotlin.math.min

/** Private bitmap overlays, one sampled decode at a time, on the editor worker. */
internal object ImageLayerCompositor {
    fun compose(source: Bitmap, encoded: String, resolve: (String) -> File): Bitmap {
        if (encoded.isBlank()) return source
        val layers = JSONArray(encoded)
        require(layers.length() <= 80) { "Too many image layers" }
        if (layers.length() == 0) return source
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        try {
            repeat(layers.length()) { index ->
                val layer = layers.getJSONObject(index)
                val file = resolve(layer.getString("path"))
                val width = (layer.optDouble("width", 0.25).coerceIn(0.001, 1.0) * source.width).toFloat()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unable to decode image layer" }
                val heightFraction = layer.optDouble("height", 0.0).coerceIn(0.0, 1.0)
                val height = if (heightFraction > 0) (heightFraction * source.height).toFloat()
                    else width * bounds.outHeight / bounds.outWidth
                val fit = min(1f, min(source.width / width, source.height / height))
                val targetWidth = width * fit
                val targetHeight = height * fit
                val decodeLimit = max(targetWidth, targetHeight).toInt().coerceIn(1, 2048)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > decodeLimit) inSampleSize *= 2
                }
                val bitmap = BitmapFactory.decodeFile(file.path, options) ?: error("Unable to decode image layer")
                try {
                    val x = (layer.optDouble("x", 0.0).coerceIn(0.0, 1.0) * source.width).toFloat()
                        .coerceAtMost(source.width - targetWidth)
                    val y = (layer.optDouble("y", 0.0).coerceIn(0.0, 1.0) * source.height).toFloat()
                        .coerceAtMost(source.height - targetHeight)
                    val angle = layer.optDouble("rotation", 0.0).coerceIn(-Math.PI * 2, Math.PI * 2)
                    canvas.save()
                    canvas.rotate(Math.toDegrees(angle).toFloat(), x + targetWidth / 2, y + targetHeight / 2)
                    canvas.drawBitmap(bitmap, null, RectF(x, y, x + targetWidth, y + targetHeight), paint)
                    canvas.restore()
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (error: Throwable) {
            output.recycle()
            throw error
        }
        return output
    }
}
