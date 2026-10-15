package dev.pam.nativeapp.modules

import dev.pam.nativeapp.protocol.WireValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PamAlertButtonsTest {
    private fun payload(vararg buttons: Pair<String, Int>): Map<String, WireValue> = buildMap {
        put("title", WireValue.Text("t"))
        put("message", WireValue.Text("m"))
        put("buttonCount", WireValue.Integer(buttons.size.toLong()))
        buttons.forEachIndexed { index, (label, style) ->
            put("button${index}Label", WireValue.Text(label))
            put("button${index}Style", WireValue.Integer(style.toLong()))
        }
    }

    @Test
    fun legacyAlertHasNoButtons() {
        assertTrue(PamAlertButtons.decode(mapOf("title" to WireValue.Text("t"))).isEmpty())
    }

    @Test
    fun decodesButtonsInOrder() {
        val buttons = PamAlertButtons.decode(payload("Agora não" to 2, "Continuar" to 1))
        assertEquals(listOf(PamAlertButton("Agora não", 2), PamAlertButton("Continuar", 1)), buttons)
    }

    @Test
    fun cancelTakesTheNegativeSlotAndOthersFillPositiveThenNeutral() {
        val slots = PamAlertButtons.slots(PamAlertButtons.decode(payload("Cancelar" to 2, "Apagar" to 3, "Arquivar" to 1)))
        assertEquals(PamAlertSlots(positive = 1, negative = 0, neutral = 2), slots)
        val noCancel = PamAlertButtons.slots(PamAlertButtons.decode(payload("A" to 1, "B" to 1, "C" to 1)))
        assertEquals(PamAlertSlots(positive = 0, negative = 2, neutral = 1), noCancel)
    }

    @Test
    fun rejectsInvalidButtonLists() {
        listOf(
            payload(),
            payload("A" to 1, "B" to 1, "C" to 1, "D" to 1),
            payload("A" to 2, "B" to 2),
            payload(" " to 1),
            payload("A" to 4),
        ).forEach { values ->
            runCatching { PamAlertButtons.decode(values) }
                .onSuccess { error("Invalid alert buttons were accepted: $values") }
        }
    }

    @Test
    fun rationalePermissionsFollowTheKindAndSdk() {
        assertEquals(listOf("android.permission.CAMERA"), PermissionRationalePermissions.forKind(1, 34))
        assertEquals(emptyList<String>(), PermissionRationalePermissions.forKind(4, 32))
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), PermissionRationalePermissions.forKind(4, 33))
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), PermissionRationalePermissions.forKind(3, 32))
        assertEquals(3, PermissionRationalePermissions.forKind(3, 34).size)
        assertEquals(emptyList<String>(), PermissionRationalePermissions.forKind(8, 34))
        runCatching { PermissionRationalePermissions.forKind(99, 34) }
            .onSuccess { error("Unknown permission kind was accepted") }
    }
}
