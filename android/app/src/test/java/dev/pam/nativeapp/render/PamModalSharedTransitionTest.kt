package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PamModalSharedTransitionTest {
    @Test
    fun theTopmostFullWindowBackgroundCoversAnUnmountedModal() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        // A full-screen black viewer over a white page: black wins (top first).
        assertEquals(black, modalCoverColor(listOf(ModalCoverLayer(1f, black, 1f), ModalCoverLayer(1f, white, 1f))))
        // A transparent top layer and a small card are skipped.
        assertEquals(
            white,
            modalCoverColor(
                listOf(
                    ModalCoverLayer(1f, 0x00FF0000, 1f),
                    ModalCoverLayer(0.4f, black, 1f),
                    ModalCoverLayer(0.95f, white, 1f),
                ),
            ),
        )
        assertNull(modalCoverColor(listOf(ModalCoverLayer(0.5f, black, 1f))))
        assertNull(modalCoverColor(emptyList()))
    }

    @Test
    fun theCoverKeepsTheOpacityOfAViewFadedByADrag() {
        // A drag to dismiss faded the black viewer to 40%.
        val cover = modalCoverColor(listOf(ModalCoverLayer(1f, 0xFF000000.toInt(), 0.4f)))!!
        assertEquals(102, cover ushr 24)
        assertEquals(0, cover and 0x00FFFFFF)
        // A 50% scrim at 50% opacity is 25%.
        val scrim = modalCoverColor(listOf(ModalCoverLayer(1f, 0x80102030.toInt(), 0.5f)))!!
        assertEquals(64, scrim ushr 24)
        assertEquals(0x102030, scrim and 0x00FFFFFF)
    }

    @Test
    fun theModalFadesInFromALaunchBackdropAndOutFromWhereItIs() {
        assertEquals(0f, modalSharedEnterAlpha(0f, 0f), 0f)
        assertEquals(0.5f, modalSharedEnterAlpha(0f, 0.5f), 1e-6f)
        // A launch backdrop already covers 60%: the fade continues from there.
        assertEquals(0.6f, modalSharedEnterAlpha(0.6f, 0f), 1e-6f)
        assertEquals(0.8f, modalSharedEnterAlpha(0.6f, 0.5f), 1e-6f)
        assertEquals(1f, modalSharedEnterAlpha(0.6f, 1.4f), 0f)
        // A modal already faded by a drag closes from its current opacity.
        assertEquals(0.4f, modalSharedExitAlpha(0.4f, 0f), 1e-6f)
        assertEquals(0.2f, modalSharedExitAlpha(0.4f, 0.5f), 1e-6f)
        assertEquals(0f, modalSharedExitAlpha(1f, 1f), 0f)
        assertEquals(0f, modalSharedExitAlpha(1f, -0.2f + 1.4f), 0f)
    }

    @Test
    fun aPointMovesBetweenWindowsByTheirDistanceOnScreen() {
        // The screen below starts under a 63 px status bar; the modal window at the top.
        val (dx, dy) = PamSharedGeometry.delta(intArrayOf(0, 63), intArrayOf(0, 0))
        assertEquals(0f, dx, 0f)
        assertEquals(63f, dy, 0f)
        val (back, up) = PamSharedGeometry.delta(intArrayOf(10, 0), intArrayOf(30, 63))
        assertEquals(-20f, back, 0f)
        assertEquals(-63f, up, 0f)
    }
}
