package dev.pam.nativeapp.modules

import dev.pam.nativeapp.modules.PermissionsModule.Companion.STATUS_BLOCKED
import dev.pam.nativeapp.modules.PermissionsModule.Companion.STATUS_DENIED
import dev.pam.nativeapp.modules.PermissionsModule.Companion.STATUS_GRANTED
import dev.pam.nativeapp.modules.PermissionsModule.Companion.notificationPermissionDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPermissionDecisionTest {
    @Test
    fun belowAndroid13TheSettingsSwitchDecides() {
        // No runtime permission below 13: runtimeGranted is always true.
        assertEquals(STATUS_GRANTED to false, notificationPermissionDecision(true, enabled = true, canAskAgain = false))
        assertEquals(
            "notifications turned off in Settings cannot be requested again",
            STATUS_BLOCKED to false,
            notificationPermissionDecision(true, enabled = false, canAskAgain = false),
        )
    }

    @Test
    fun android13CombinesTheRuntimePermissionWithTheSwitch() {
        assertEquals(STATUS_GRANTED to true, notificationPermissionDecision(true, enabled = true, canAskAgain = true))
        assertEquals(STATUS_BLOCKED to false, notificationPermissionDecision(true, enabled = false, canAskAgain = true))
        assertEquals(STATUS_DENIED to true, notificationPermissionDecision(false, enabled = false, canAskAgain = true))
        assertEquals(STATUS_BLOCKED to false, notificationPermissionDecision(false, enabled = false, canAskAgain = false))
    }
}
