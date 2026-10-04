package dev.pam.nativeapp.render

import dev.pam.nativeapp.views.OverlayBounds
import dev.pam.nativeapp.views.OverlayCollisionResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayCollisionResolverTest {
    private val viewport = OverlayBounds(8f, 24f, 352f, 760f)
    private val anchor = OverlayBounds(16f, 300f, 192f, 348f)

    @Test fun lateralOverflowFallsBackWithoutCoveringAnchor() {
        assertEquals(2, OverlayCollisionResolver.selectCandidate(anchor, viewport, 248f, 160f,
            listOf(-240f to 244f, 200f to 244f, 16f to 356f, 16f to 132f)))
    }

    @Test fun fittingPreferredPositionRemainsPreferred() {
        assertEquals(0, OverlayCollisionResolver.selectCandidate(anchor, viewport, 120f, 80f,
            listOf(200f to 284f, 16f to 356f)))
    }

    @Test fun belowScreenUsesTopWhenItAvoidsAnchor() {
        val lowAnchor = OverlayBounds(16f, 690f, 192f, 738f)
        assertEquals(1, OverlayCollisionResolver.selectCandidate(lowAnchor, viewport, 248f, 160f,
            listOf(16f to 746f, 16f to 522f)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyCandidatesAreRejected() {
        OverlayCollisionResolver.selectCandidate(anchor, viewport, 248f, 160f, emptyList())
    }
}
