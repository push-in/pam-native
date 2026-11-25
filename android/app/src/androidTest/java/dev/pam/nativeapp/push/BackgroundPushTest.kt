package dev.pam.nativeapp.push

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPushTest {
    @Test
    fun tokenRefreshIsPackageRestrictedAndPreservesOpaqueToken() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val token = "test:opaque-token_+/= "
        val intent = requireNotNull(BackgroundPush.tokenRefreshedIntent(context, token))

        assertEquals("dev.pam.nativeapp.action.PUSH_TOKEN_REFRESHED", intent.action)
        assertEquals(context.packageName, intent.`package`)
        assertEquals(setOf(BackgroundPush.EXTRA_TOKEN), intent.extras?.keySet())
        // Boolean assertions avoid including token contents in failure reports.
        assertTrue(intent.getStringExtra(BackgroundPush.EXTRA_TOKEN) == token)
        assertEquals(0, intent.flags)
        assertNull(intent.data)
        assertNull(intent.clipData)
    }

    @Test
    fun tokenRefreshRejectsBlankAndOversizedValuesInsteadOfTruncating() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (token in listOf("", " \t\n", "x".repeat(8_193))) {
            assertNull(BackgroundPush.tokenRefreshedIntent(context, token))
        }
        val token = "x".repeat(8_192)
        val intent = requireNotNull(BackgroundPush.tokenRefreshedIntent(context, token))
        assertTrue(intent.getStringExtra(BackgroundPush.EXTRA_TOKEN) == token)
    }

    @Test
    fun receivedIntentIsRestrictedAndBounded() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = BackgroundPush.receivedIntent(
            context = context,
            id = "call-42",
            title = "Incoming call",
            body = "Tap to answer",
            dataJson = """{"type":2,"call_id":"call-42"}""",
            deepLink = "pushin://call/call-42",
        )

        assertEquals(BackgroundPush.ACTION_RECEIVED, intent.action)
        assertEquals(context.packageName, intent.`package`)
        assertEquals("call-42", intent.getStringExtra(BackgroundPush.EXTRA_ID))
        assertEquals("Incoming call", intent.getStringExtra(BackgroundPush.EXTRA_TITLE))
        assertEquals("Tap to answer", intent.getStringExtra(BackgroundPush.EXTRA_BODY))
        assertEquals(
            """{"type":2,"call_id":"call-42"}""",
            intent.getStringExtra(BackgroundPush.EXTRA_DATA_JSON),
        )
        assertEquals(
            "pushin://call/call-42",
            intent.getStringExtra(BackgroundPush.EXTRA_DEEP_LINK),
        )
    }
}
