package dev.pam.nativeapp.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PamSharedGeometryTest {
    @Test
    fun coverOverflowsTheBoxAndContainLetterboxes() {
        // A 4:3 photo in a 200x200 bubble (cover) and a 400x800 viewer (contain).
        assertArrayEquals(floatArrayOf(-33.333336f, 0f, 233.33334f, 200f), PamSharedGeometry.fit(200f, 200f, 400f, 300f, PamSharedFit.COVER), 0.01f)
        val contained = PamSharedGeometry.fit(400f, 800f, 400f, 300f, PamSharedFit.CONTAIN)
        assertArrayEquals(floatArrayOf(0f, 250f, 400f, 550f), contained, 0.01f)
        assertArrayEquals(floatArrayOf(0f, 0f, 50f, 60f), PamSharedGeometry.fit(50f, 60f, 400f, 300f, PamSharedFit.FILL), 0f)
    }

    @Test
    fun theVisibleCropIsTheBoxForCoverAndTheContentForContain() {
        val cover = PamSharedGeometry.fit(200f, 200f, 400f, 300f, PamSharedFit.COVER)
        assertArrayEquals(floatArrayOf(0f, 0f, 200f, 200f), PamSharedGeometry.clip(200f, 200f, cover, PamSharedFit.COVER), 0f)
        val contain = PamSharedGeometry.fit(400f, 800f, 400f, 300f, PamSharedFit.CONTAIN)
        assertArrayEquals(floatArrayOf(0f, 250f, 400f, 550f), PamSharedGeometry.clip(400f, 800f, contain, PamSharedFit.CONTAIN), 0.01f)
    }

    @Test
    fun interpolatedContentKeepsTheMediaAspect() {
        val from = PamSharedGeometry.fit(200f, 200f, 400f, 300f, PamSharedFit.COVER)
        val to = PamSharedGeometry.fit(400f, 800f, 400f, 300f, PamSharedFit.CONTAIN)
        for (step in 0..10) {
            val t = step / 10f
            val width = PamSharedGeometry.lerp(from[2] - from[0], to[2] - to[0], t)
            val height = PamSharedGeometry.lerp(from[3] - from[1], to[3] - to[1], t)
            assertEquals(4f / 3f, width / height, 0.001f)
        }
    }

    @Test
    fun springIsMonotonicWithoutOvershootAndLandsAtTheEnd() {
        // Reanimated { damping: 24, mass: 0.9, stiffness: 240, overshootClamping }.
        var previous = 0f
        for (step in 0..36) {
            val value = PamSharedGeometry.ease(step / 36f, 3, 0.816f, 240f, 0.9f, 360L)
            assertTrue("spring must stay in [0, 1] (step $step: $value)", value in 0f..1f)
            assertTrue("damped spring rises (step $step)", value >= previous - 0.002f)
            previous = value
        }
        assertEquals(1f, PamSharedGeometry.ease(1f, 3, 0.816f, 240f, 0.9f, 360L), 0f)
        assertTrue(PamSharedGeometry.ease(0.5f, 3, 0.816f, 240f, 0.9f, 360L) > 0.85f)
    }

    @Test
    fun easeInStartsSlowAndEndsAtOne() {
        assertEquals(0.125f, PamSharedGeometry.ease(0.5f, 4, 0.82f, 220f, 1f), 0.0001f)
        assertEquals(1f, PamSharedGeometry.ease(1f, 4, 0.82f, 220f, 1f), 0f)
        assertEquals(0.5f, PamSharedGeometry.ease(0.5f, 1, 0.82f, 220f, 1f), 0f)
    }
}
