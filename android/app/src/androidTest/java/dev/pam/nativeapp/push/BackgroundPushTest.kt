package dev.pam.nativeapp.push

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundPushTest {
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
