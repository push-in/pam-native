package dev.pam.nativeapp.modules

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * `ViewCapture::capture()`: renders a mounted view (found by its `nativeRef`)
 * and its subtree into a private PNG/JPEG under `pam-files`. The view is drawn
 * with its own content transform only, so a view kept invisible by an
 * ancestor (opacity 0, off screen, behind other content) captures as it
 * would look on screen. Drawing happens on the UI thread, encoding on a
 * worker; a 4096-pixel cap per side bounds the bitmap.
 */
internal class ViewCaptureModule(
    filesDir: File,
    private val density: () -> Float,
    private val findView: (String) -> View?,
) : NativeModule, AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val root = File(filesDir, "pam-files").canonicalFile

    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        if (method != "capture") {
            completion.failure("Unknown view capture method $method")
            return
        }
        val values = runCatching { WireMap.decode(payload) }.getOrElse {
            completion.failure("Invalid view capture request")
            return
        }
        val ref = (values["ref"] as? WireValue.Text)?.value.orEmpty()
        val pixelRatio = values.number("pixelRatio")
        val jpeg = (values["format"] as? WireValue.Integer)?.value == FORMAT_JPEG
        val quality = ((values["quality"] as? WireValue.Integer)?.value ?: 92L).toInt().coerceIn(1, 100)
        val directory = (values["directory"] as? WireValue.Text)?.value.orEmpty()
        main.post {
            val bitmap = runCatching { draw(ref, pixelRatio) }.getOrElse {
                completion.failure(it.message ?: "Unable to capture the view.")
                return@post
            }
            executor.execute {
                runCatching { write(bitmap, directory, jpeg, quality) }
                    .onSuccess { output ->
                        completion.complete(
                            ModuleResultStatus.SUCCESS,
                            WireMap.encode(
                                mapOf(
                                    "path" to WireValue.Text(output.relativeTo(root).path),
                                    "name" to WireValue.Text(output.name),
                                    "size" to WireValue.Integer(output.length()),
                                    "width" to WireValue.Integer(bitmap.width.toLong()),
                                    "height" to WireValue.Integer(bitmap.height.toLong()),
                                    "mimeType" to WireValue.Text(if (jpeg) "image/jpeg" else "image/png"),
                                ),
                            ),
                        )
                    }
                    .onFailure { completion.failure(it.message ?: "Unable to write the capture.") }
                bitmap.recycle()
            }
        }
    }

    private fun draw(ref: String, pixelRatio: Double): Bitmap {
        require(ref.isNotEmpty()) { "A nativeRef is required." }
        val view = findView(ref) ?: error("No mounted view has nativeRef $ref.")
        check(view.width > 0 && view.height > 0) { "The view $ref has not been laid out." }
        val scale = captureScale(view.width, view.height, pixelRatio, density())
        val width = max(1, (view.width * scale).roundToInt())
        val height = max(1, (view.height * scale).roundToInt())
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(width / view.width.toFloat(), height / view.height.toFloat())
        // Only the view's content: its own translation, scale, rotation and
        // alpha belong to its parent's drawing and are not applied here.
        canvas.translate(-view.scrollX.toFloat(), -view.scrollY.toFloat())
        view.draw(canvas)
        return bitmap
    }

    private fun write(bitmap: Bitmap, directory: String, jpeg: Boolean, quality: Int): File {
        val folder = File(root, safeDirectory(directory)).canonicalFile
        require(folder == root || folder.path.startsWith(root.path + File.separator)) { "Invalid capture directory" }
        folder.mkdirs()
        val output = File(folder, "capture-${System.nanoTime()}.${if (jpeg) "jpg" else "png"}")
        FileOutputStream(output).buffered().use { stream ->
            check(
                bitmap.compress(
                    if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG,
                    quality,
                    stream,
                ),
            ) { "Unable to encode the capture." }
        }
        return output
    }

    override fun close() {
        executor.shutdown()
    }

    private fun Map<String, WireValue>.number(key: String): Double = when (val value = this[key]) {
        is WireValue.Decimal -> value.value
        is WireValue.Integer -> value.value.toDouble()
        else -> 0.0
    }

    private fun ModuleCompletion.failure(message: String) =
        complete(ModuleResultStatus.FAILURE, message.toByteArray())

    internal companion object {
        const val FORMAT_PNG = 1L
        const val FORMAT_JPEG = 2L
        const val MAX_SIDE = 4096

        /**
         * Pixels per view pixel: `pixelRatio` output pixels per density-independent
         * point (the screen density when not positive), capped so neither side
         * exceeds [MAX_SIDE].
         */
        fun captureScale(width: Int, height: Int, pixelRatio: Double, density: Float): Float {
            val requested = if (pixelRatio > 0.0 && pixelRatio.isFinite()) (pixelRatio / density).toFloat() else 1f
            val largest = max(width, height).coerceAtLeast(1)
            return requested.coerceAtMost(MAX_SIDE / largest.toFloat()).coerceAtLeast(0.01f)
        }

        /** "a/b" folders of letters, digits, "_", "-" and "."; never "..". */
        fun safeDirectory(directory: String): String {
            val parts = directory.trim('/').split('/').filter { it.isNotEmpty() }
            require(parts.all { it != ".." && it != "." && it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }) {
                "Invalid capture directory"
            }
            return if (parts.isEmpty()) "view-captures" else parts.joinToString(File.separator)
        }
    }
}
