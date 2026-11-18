package dev.pam.nativeapp

import dev.pam.nativeapp.modules.OpenUrlSchemes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUrlSchemesTest {
    @Test fun defaultsAcceptWebMailPhoneAndMapsInAnyCase() {
        val schemes = OpenUrlSchemes()
        for (scheme in listOf("https", "Https", "HTTPS", "http", "HTTP", "mailto", "MailTo", "tel", "geo")) {
            assertTrue(scheme, schemes.accepts(scheme))
        }
        assertFalse(schemes.accepts(null))
        assertFalse(schemes.accepts(""))
        assertFalse(schemes.accepts("otpauth"))
        assertFalse(schemes.accepts("whatsapp"))
    }

    @Test fun declaredSchemesAreAcceptedButDangerousOnesNever() {
        val schemes = OpenUrlSchemes(listOf("otpauth", " WhatsApp ", "", "file", "intent", "JavaScript", "content"))
        assertTrue(schemes.accepts("otpauth"))
        assertTrue(schemes.accepts("OTPAUTH"))
        assertTrue(schemes.accepts("whatsapp"))
        for (scheme in listOf("file", "intent", "javascript", "content", "data", "android-app")) {
            assertFalse(scheme, schemes.accepts(scheme))
        }
    }
}
