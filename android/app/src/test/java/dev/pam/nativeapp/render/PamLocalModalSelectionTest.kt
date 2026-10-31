package dev.pam.nativeapp.render

import dev.pam.nativeapp.protocol.PropValue
import dev.pam.nativeapp.protocol.WireValue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamLocalModalSelectionTest {
    @Test
    fun onlySheetItemNativeEventsMayUpdateTheLocalModalTrigger() {
        assertTrue(
            isLocalModalSelectionEvent(
                PropValue.Properties(
                    mapOf("behavior" to WireValue.Integer(24L)),
                ),
            ),
        )
        assertFalse(
            isLocalModalSelectionEvent(
                PropValue.Properties(
                    mapOf("behavior" to WireValue.Integer(3L)),
                ),
            ),
        )
        assertFalse(isLocalModalSelectionEvent(null))
    }

    @Test
    fun nativeEventsCloseOnlyEngineLocalModals() {
        assertTrue(isLocalModalMarker("pam:local-modal:language"))
        // An app Modal (camera, player) has no local marker and stays open.
        assertFalse(isLocalModalMarker(null))
        assertFalse(isLocalModalMarker("loop-camera"))
    }
}
