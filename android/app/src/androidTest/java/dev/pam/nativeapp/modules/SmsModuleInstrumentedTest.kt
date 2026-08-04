package dev.pam.nativeapp.modules

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsModuleInstrumentedTest {
    @Test
    fun composerIntentTargetsOnlySmsAppsAndCarriesDraft() {
        val intent = SmsModule.smsIntent(
            listOf("+5511999990000", "+5511888880000"),
            "Convite do Zé Chat",
        )

        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("smsto", intent.data?.scheme)
        assertEquals(
            "+5511999990000;+5511888880000",
            intent.data?.schemeSpecificPart,
        )
        assertEquals("Convite do Zé Chat", intent.getStringExtra("sms_body"))
    }

    @Test
    fun emptyBodyDoesNotAddAnSmsExtra() {
        val intent = SmsModule.smsIntent(listOf("+5511999990000"), "")

        assertEquals(false, intent.hasExtra("sms_body"))
    }
}
