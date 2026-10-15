package dev.pam.nativeapp.modules

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaThumbnailInstrumentedTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun thumbnailIsDecodedAtTheRequestedEdgeIntoTheBoundedCache() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        grantRead()
        val source = File(context.cacheDir, "pam-thumb-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF3366CC.toInt())
            source.outputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it) }
            recycle()
        }
        val saved = MediaStoreSaver(context).save(source, "image/png", "PAM Tests", source.name)
        val uri = Uri.parse(saved.getString("uri"))
        val module = MediaLibraryModule(context)
        try {
            val id = saved.getString("id")
            val first = call(module, "thumbnail", mapOf(
                "id" to WireValue.Text(id),
                "maxPx" to WireValue.Integer(256),
            ))
            val thumbUri = (first.getValue("uri") as WireValue.Text).value
            val file = File(Uri.parse(thumbUri).path!!)
            assertTrue(thumbUri.startsWith("file://"))
            assertTrue(file.isFile && file.parentFile?.name == "pam-media-thumbnails")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            assertTrue("edge ${bounds.outWidth}x${bounds.outHeight}", maxOf(bounds.outWidth, bounds.outHeight) in 1..256)
            val second = call(module, "thumbnail", mapOf(
                "id" to WireValue.Text(id),
                "maxPx" to WireValue.Integer(256),
            ))
            assertEquals(thumbUri, (second.getValue("uri") as WireValue.Text).value)

            val page = call(module, "assets", mapOf(
                "type" to WireValue.Integer(5),
                "limit" to WireValue.Integer(20),
                "offset" to WireValue.Integer(0),
                "albumId" to WireValue.Text(""),
            ))
            val items = JSONArray((page.getValue("items") as WireValue.Text).value)
            val row = (0 until items.length()).map(items::getJSONObject).first { it.getString("id") == id }
            assertTrue(row.getString("thumbnailUri").contains("/images/media/$id"))

            val limited = call(module, "refreshLimitedSelection", emptyMap())
            assertEquals(WireValue.Flag(false), limited["limited"])
        } finally {
            module.close()
            context.contentResolver.delete(uri, null, null)
            source.delete()
        }
    }

    private fun grantRead() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        automation.grantRuntimePermission(context.packageName, permission)
    }

    private fun call(module: NativeModule, method: String, values: Map<String, WireValue>): Map<String, WireValue> {
        val latch = CountDownLatch(1)
        val status = AtomicReference<ModuleResultStatus>()
        val payload = AtomicReference<ByteArray>()
        module.invoke(method, WireMap.encode(values)) { resultStatus, bytes ->
            status.set(resultStatus)
            payload.set(bytes)
            latch.countDown()
        }
        assertTrue("$method timed out", latch.await(20, TimeUnit.SECONDS))
        check(status.get() == ModuleResultStatus.SUCCESS) { "$method failed: ${String(payload.get())}" }
        return if (payload.get().isEmpty()) emptyMap() else WireMap.decode(payload.get())
    }
}
