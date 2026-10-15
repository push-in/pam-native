package dev.pam.nativeapp.modules

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
}
