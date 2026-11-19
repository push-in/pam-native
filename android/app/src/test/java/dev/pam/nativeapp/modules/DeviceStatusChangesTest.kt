package dev.pam.nativeapp.modules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusChangesTest {
    @Test
    fun deliversTheFirstStatusThenOnlyChanges() {
        val changes = DeviceStatusChanges()
        assertTrue(changes.accept(byteArrayOf(1, 2, 3)))
        assertFalse("an identical sample must not wake PHP", changes.accept(byteArrayOf(1, 2, 3)))
        assertTrue(changes.accept(byteArrayOf(1, 2, 4)))
        assertFalse(changes.accept(byteArrayOf(1, 2, 4)))
        assertTrue("going back to an earlier status is a change", changes.accept(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun aNetworkWatchIgnoresBatteryAndPowerSaveChanges() {
        assertEquals(DeviceStatusFields.ALL, DeviceStatusFields.of(null))
        assertEquals(DeviceStatusFields.ALL, DeviceStatusFields.of(0))
        assertEquals(DeviceStatusFields.NETWORK, DeviceStatusFields.of(2))
        val status = mapOf(
            "batteryLevel" to 0.81,
            "charging" to false,
            "networkType" to 2,
            "expensiveNetwork" to false,
            "lowPowerMode" to false,
        )
        assertEquals(
            mapOf("networkType" to 2, "expensiveNetwork" to false),
            DeviceStatusFields.select(status, DeviceStatusFields.NETWORK),
        )
        val changes = DeviceStatusChanges()
        val key = { values: Map<String, Any> ->
            DeviceStatusFields.select(values, DeviceStatusFields.NETWORK).toString().toByteArray()
        }
        assertTrue(changes.accept(key(status)))
        assertFalse(
            "a battery step or the charger must not wake a network-only watch",
            changes.accept(key(status + mapOf("batteryLevel" to 0.8, "charging" to true, "lowPowerMode" to true))),
        )
        assertTrue(changes.accept(key(status + ("networkType" to 3))))
    }
}
