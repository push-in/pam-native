package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamDragFollowTest {
    @Test fun followDefaultsToOneAndScalesDisplacementFromTheRestingPosition() {
        assertEquals(1.0, PamDragConfig.parse("axis=y")!!.follow, 0.0)
        assertEquals(.92, PamDragConfig.parse("axis=y\nfollow=0.92")!!.follow, 0.0)
        assertEquals(102.0, PamDragMath.follow(10.0, 100.0, .92), .00001)
        assertEquals(-82.0, PamDragMath.follow(10.0, -100.0, .92), .00001)
        assertEquals(10.0, PamDragMath.follow(10.0, 100.0, 0.0), 0.0)
        for (invalid in listOf("NaN", "Infinity", "-1", "2")) {
            assertEquals(1.0, PamDragConfig.parse("axis=y\nfollow=$invalid")!!.follow, 0.0)
        }
    }

}
