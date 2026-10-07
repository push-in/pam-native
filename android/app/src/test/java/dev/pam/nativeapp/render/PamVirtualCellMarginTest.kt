package dev.pam.nativeapp.render

import dev.pam.nativeapp.protocol.Frame
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.Assert.assertEquals
import org.junit.Test

class PamVirtualCellMarginTest {
    @Test
    fun cellRootMarginsResolveLikeTheEngine() {
        val margins = cellRootMargins(
            mapOf(
                PropKey.MARGIN to PropValue.Decimal(4.0),
                PropKey.MARGIN_VERTICAL to PropValue.Integer(6L),
                PropKey.MARGIN_BOTTOM to PropValue.Decimal(10.0),
                PropKey.MARGIN_LEFT to PropValue.Decimal(Double.NaN),
            ),
        )
        assertEquals(CellRootMargins(left = 0f, top = 6f, right = 4f, bottom = 10f), margins)
        assertEquals(CellRootMargins(), cellRootMargins(null))
    }

    @Test
    fun slotFrameIsTheRootMarginBox() {
        assertEquals(
            Frame(0f, 0f, 320f, 56f),
            cellSlotFrame(Frame(12f, 6f, 296f, 40f), CellRootMargins(12f, 6f, 12f, 10f)),
        )
    }

    @Test
    fun stickyChildPinsAndIsPushedByItsMarginBox() {
        assertEquals(0, stickyPinOffset(50, 92, 52, 392, horizontal = false))
        assertEquals(208, stickyPinOffset(300, 92, 52, 392, horizontal = false))
        assertEquals(248, stickyPinOffset(360, 92, 52, 392, horizontal = false))
        assertEquals(908, stickyPinOffset(1000, 92, 52, null, horizontal = false))
        assertEquals(0, stickyPinOffset(1000, 92, 52, null, horizontal = true))
    }
}
