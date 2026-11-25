package dev.pam.nativeapp.push

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PamFirebaseTokenRefreshTest {
    @Test
    fun firebaseCallbackEmitsEachRotationWithoutStartingPhp() {
        val context = BroadcastCaptureContext(ApplicationProvider.getApplicationContext())
        val service = firebaseService(context)
        val onNewToken = service.javaClass.getMethod("onNewToken", String::class.java)
        val tokens = listOf("test-first-registration", "test-rotated-registration")

        tokens.forEach { onNewToken.invoke(service, it) }

        assertEquals(2, context.broadcasts.size)
        context.broadcasts.forEachIndexed { index, intent ->
            assertEquals(BackgroundPush.ACTION_TOKEN_REFRESHED, intent.action)
            assertEquals(context.packageName, intent.`package`)
            assertEquals(setOf(BackgroundPush.EXTRA_TOKEN), intent.extras?.keySet())
            // Do not include registration tokens in assertion failure output.
            assertTrue(intent.getStringExtra(BackgroundPush.EXTRA_TOKEN) == tokens[index])
        }
    }

    @Test
    fun firebaseCallbackDoesNotBroadcastInvalidTokens() {
        val context = BroadcastCaptureContext(ApplicationProvider.getApplicationContext())
        val service = firebaseService(context)
        val onNewToken = service.javaClass.getMethod("onNewToken", String::class.java)

        listOf("", " \t\n", "x".repeat(8_193)).forEach { onNewToken.invoke(service, it) }

        assertTrue(context.broadcasts.isEmpty())
    }

    private fun firebaseService(context: Context): Any {
        // Firebase is optional. Keep the common test APK compilable without it,
        // but exercise the actual production callback in Firebase-enabled builds.
        val serviceClass = try {
            Class.forName("dev.pam.nativeapp.modules.PamFirebaseMessagingService")
        } catch (_: ClassNotFoundException) {
            null
        }
        assumeTrue("Requires a Firebase-enabled app build", serviceClass != null)
        val service = requireNotNull(serviceClass).getDeclaredConstructor().newInstance()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(service, context)
        return service
    }

    private class BroadcastCaptureContext(base: Context) : ContextWrapper(base) {
        val broadcasts = mutableListOf<Intent>()

        override fun getApplicationContext(): Context = this

        override fun sendBroadcast(intent: Intent) {
            broadcasts += intent
        }
    }
}
