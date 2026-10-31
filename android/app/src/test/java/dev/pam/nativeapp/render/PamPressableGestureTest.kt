package dev.pam.nativeapp.render

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamPressableGestureTest {
    @Test
    fun aDoubleTapSurfaceNeverDimsOnPress() {
        assertFalse(showsPressFeedback(hasDoubleTap = true))
        assertTrue(showsPressFeedback(hasDoubleTap = false))
    }

    @Test
    fun recognizedGestureCancelsCompetingPressSemantics() {
        assertTrue(gestureRecognitionCancelsPress(recognized = true, pressActive = true))
        assertFalse(gestureRecognitionCancelsPress(recognized = false, pressActive = true))
        assertFalse(gestureRecognitionCancelsPress(recognized = true, pressActive = false))
    }

    @Test
    fun multiPointerGestureClaimsItsStreamBeforeAncestorScrollInterception() {
        assertTrue(gestureRequiresParentInterception(MotionEvent.ACTION_POINTER_DOWN, true))
        assertFalse(gestureRequiresParentInterception(MotionEvent.ACTION_DOWN, true))
        assertFalse(gestureRequiresParentInterception(MotionEvent.ACTION_POINTER_DOWN, false))
    }

    @Test
    fun focalZoomKeepsTheContentPointUnderTheFingers() {
        val pivot = 160f
        val start = 240f
        val base = 20f
        // Content point under the focus: (focal - pivot - t) / scale.
        val zoomed = focalZoomTranslation(start, start, pivot, base, 2f)
        assertEquals((start - pivot - base) / 1.5f, (start - pivot - zoomed) / 3f, 0.0001f)
        // Moving the fingers by 30 carries the content along.
        assertEquals(zoomed + 30f, focalZoomTranslation(start + 30f, start, pivot, base, 2f), 0.0001f)
        // No zoom, no movement: the translation stays.
        assertEquals(base, focalZoomTranslation(start, start, pivot, base, 1f), 0.0001f)
    }

    @Test
    fun containedContentFitsInsideTheView() {
        // Portrait 3:4 in a 360x700 page: full width, 480 tall.
        val portrait = containedContentSize(360f, 700f, 0.75f)
        assertEquals(360f, portrait.first, 0.0001f)
        assertEquals(480f, portrait.second, 0.0001f)
        // Landscape 16:9: full width, 202.5 tall.
        assertEquals(202.5f, containedContentSize(360f, 700f, 16f / 9f).second, 0.0001f)
        // Very tall content: full height, narrower.
        val tall = containedContentSize(360f, 700f, 0.25f)
        assertEquals(175f, tall.first, 0.0001f)
        assertEquals(700f, tall.second, 0.0001f)
        // No aspect: the content fills the view.
        assertEquals(360f to 700f, containedContentSize(360f, 700f, 0f))
    }

    @Test
    fun boundedTranslationStopsAtTheScaledContentEdges() {
        // Portrait 360x480 in a 360x700 page at 2x around the centre:
        // 720x960 may move 180 horizontally and 130 vertically.
        assertEquals(180f, boundedTranslation(500f, 360f, 360f, 180f, 2f), 0.0001f)
        assertEquals(-180f, boundedTranslation(-500f, 360f, 360f, 180f, 2f), 0.0001f)
        assertEquals(130f, boundedTranslation(400f, 700f, 480f, 350f, 2f), 0.0001f)
        assertEquals(40f, boundedTranslation(40f, 700f, 480f, 350f, 2f), 0.0001f)
        // Landscape 202.5 tall at 2.4x is still shorter than 700: stays centred.
        assertEquals(0f, boundedTranslation(300f, 700f, 202.5f, 350f, 2.4f), 0.0001f)
        // Below 1x the content is centred whatever the gesture left.
        assertEquals(0f, boundedTranslation(-90f, 360f, 360f, 180f, 0.7f), 0.0001f)
        // An off-centre pivot keeps the same edges.
        val left = boundedTranslation(1_000f, 360f, 360f, 0f, 2f)
        assertEquals(0f, left, 0.0001f)
        assertEquals(-360f, boundedTranslation(-1_000f, 360f, 360f, 0f, 2f), 0.0001f)
    }

    @Test
    fun nestedPressableWithoutLongPressLendsItToTheNearestHandler() {
        val plain = NestedLongPressNode(hasLongPress = false, enabled = true, boundary = false)
        val bubble = NestedLongPressNode(hasLongPress = true, enabled = true, boundary = false)
        val list = NestedLongPressNode(hasLongPress = false, enabled = false, boundary = true)
        // Play button inside the bubble: the bubble's long press opens the menu.
        assertEquals(0, nestedLongPressTarget(listOf(bubble, list)))
        // Through plain wrappers, nearest handler wins.
        assertEquals(1, nestedLongPressTarget(listOf(plain, bubble, bubble)))
        // A disabled handler swallows it (RN: a disabled responder does not forward).
        assertEquals(-1, nestedLongPressTarget(listOf(bubble.copy(enabled = false), bubble)))
        // Never across a list/scroll boundary, and nothing to lend to.
        assertEquals(-1, nestedLongPressTarget(listOf(plain, list, bubble)))
        assertEquals(-1, nestedLongPressTarget(emptyList()))
    }

    @Test
    fun lentLongPressUsesTheReceiversDelay() {
        assertEquals(300L, nestedLongPressDelay(own = 500L, hasOwnLongPress = false, ancestorDelay = 300L))
        assertEquals(500L, nestedLongPressDelay(own = 500L, hasOwnLongPress = true, ancestorDelay = 300L))
        assertEquals(500L, nestedLongPressDelay(own = 500L, hasOwnLongPress = false, ancestorDelay = null))
    }
}
