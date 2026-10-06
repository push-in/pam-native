package dev.pam.nativeapp.render

import org.junit.Assert.*
import org.junit.Test

class PamDragTextTest {
    @Test fun clockInterpolatesBoundsAndTotalWithoutPhpEvents() {
        val driver = requireNotNull(PamDragTextDriver.parse("clock|3|0|0,360|0,125"))
        assertEquals("0:00 / 2:05", driver.label(-10.0, 14.0))
        assertEquals("1:02 / 2:05", driver.label(180.0, 14.0))
        assertEquals("2:05 / 2:05", driver.label(400.0, 14.0))
    }
    @Test fun numberAndClockFormatsResolvePercentInputsAndDescendingRanges() {
        val decimal = requireNotNull(PamDragTextDriver.parse("value|1|2|0,100%|0,2.5"))
        assertEquals("1.25", decimal.label(50.0, 100.0))
        val clock = requireNotNull(PamDragTextDriver.parse("value|2|0|100,0|60,0"))
        assertEquals("0:30", clock.label(50.0, 100.0))
        assertEquals("1:00:01", requireNotNull(PamDragTextDriver.parse("value|2|0|0,1|0,3601")).label(1.0, 1.0))
    }
    @Test fun malformedTextDriversAreRejected() {
        for (source in listOf("x|4|0|0,1|0,1", "x|1|4|0,1|0,1", "x|1|0|0,1|0,NaN", "x|1|0|0|1", "|1|0|0,1|0,1")) {
            assertNull(source, PamDragTextDriver.parse(source))
        }
    }
    @Test fun additiveContractKeepsExistingDragDefaults() {
        val original = requireNotNull(PamDragConfig.parse("axis=x"))
        assertTrue(original.snapOnRelease)
        assertNull(original.touchInset)
        assertTrue(original.textDrivers.isEmpty())
        val scrub = requireNotNull(PamDragConfig.parse("axis=x\ntouch=14\nsnapOnRelease=0\ntext=clock|3|0|0,360|0,125"))
        assertFalse(scrub.snapOnRelease)
        assertEquals(14.0, scrub.touchInset!!, 0.0)
        assertEquals(1, scrub.textDrivers.size)
    }
}
