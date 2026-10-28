package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The disk cache is trimmed occasionally, never on every image write. */
class NativeImageDiskTrimTest {
    @Test
    fun smallWritesDoNotTrimEveryTime() {
        assertFalse(diskTrimDue(200L * 1024, 1))
        assertFalse(diskTrimDue(3L * 1024 * 1024, 47))
    }

    @Test
    fun enoughBytesOrFilesTriggerATrim() {
        assertTrue(diskTrimDue(4L * 1024 * 1024, 1))
        assertTrue(diskTrimDue(1024, 48))
    }
}
