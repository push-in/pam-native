package dev.pam.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Must match the CLI's `android_dev_port::device_port` (FNV-1a, 39100-39899). */
class HotReloadDevicePortTest {
    @Test
    fun eachPackageHasItsOwnDevicePortMatchingTheCli() {
        assertEquals(39_573, HotReloadClient.devicePort("dev.pam.biotest.debug"))
        assertEquals(39_183, HotReloadClient.devicePort("com.pushin.pushinpay.pam.debug"))
        assertEquals(39_868, HotReloadClient.devicePort("dev.pam.nativeapp"))
        assertNotEquals(
            HotReloadClient.devicePort("dev.pam.biotest.debug"),
            HotReloadClient.devicePort("com.pushin.pushinpay.pam.debug"),
        )
    }
}
