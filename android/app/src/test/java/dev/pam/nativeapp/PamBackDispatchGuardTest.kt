package dev.pam.nativeapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamBackDispatchGuardTest {
    private val firstKey = PamBackKey(deviceId = 1, downTime = 100)
    private val nextKey = PamBackKey(deviceId = 1, downTime = 150)

    @Test
    fun newBackImmediatelyAfterModalCloseCanNavigateButItsDuplicateCannot() {
        val guard = PamBackDispatchGuard()
        assertFalse(guard.observeKey(firstKey, canBeginInteraction = true))
        guard.modalConsumed(key = firstKey)
        assertTrue(guard.suppressesUnidentifiedCallback())
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = true))
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = false))
        assertTrue(guard.suppressesUnidentifiedCallback())

        assertFalse(guard.observeKey(nextKey, canBeginInteraction = true))
        assertFalse(guard.suppressesUnidentifiedCallback())
        // A late DOWN/UP from the first gesture cannot pop the next screen.
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = true))
        assertFalse(guard.suppressesUnidentifiedCallback())
    }

    @Test
    fun canceledOrRepeatedKeysDoNotReleaseTheCurrentDispatchGuard() {
        val guard = PamBackDispatchGuard()
        guard.modalConsumed(key = firstKey)
        assertFalse(guard.observeKey(nextKey, canBeginInteraction = false))
        assertTrue(guard.suppressesUnidentifiedCallback())
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = false))
        assertTrue(guard.suppressesUnidentifiedCallback())
    }

    @Test
    fun predictiveBackStartIdentifiesANewInteractionWithoutWaiting() {
        val guard = PamBackDispatchGuard()
        guard.modalConsumed()
        assertTrue(guard.suppressesUnidentifiedCallback())
        guard.beginInteraction()
        assertFalse(guard.suppressesUnidentifiedCallback())
    }

    @Test
    fun consecutiveUnidentifiedBacksCloseTheModalThenNavigateWithoutAKeyOrGestureStart() {
        val guard = PamBackDispatchGuard()
        var modalClosing = false
        var closeRequests = 0
        var navigationBacks = 0
        // The same ownership order as the activity: dispatch duplicate,
        // presented/pending modal, then the application's navigation stack.
        fun backCallback() {
            if (guard.suppressesUnidentifiedCallback()) return
            if (!modalClosing) {
                modalClosing = true
                closeRequests++
                guard.modalConsumed()
                return
            }
            navigationBacks++
            guard.modalConsumed() // Closing-dialog handoff deduplicates its dispatch too.
        }

        backCallback()
        backCallback() // An OEM redispatch in the same main-loop turn.
        assertTrue(closeRequests == 1 && navigationBacks == 0)
        guard.endDispatchTurn()
        // Still exiting, but the next API 33+ button has no KeyEvent or
        // onBackStarted. Its new turn must reach navigation immediately.
        backCallback()
        assertTrue(closeRequests == 1 && navigationBacks == 1)
        backCallback() // Duplicate of the forwarded callback stays consumed.
        assertTrue(navigationBacks == 1)
        guard.endDispatchTurn()
        backCallback()
        assertTrue(navigationBacks == 2)
    }

    @Test
    fun endingTheDispatchTurnReleasesOnlyUnidentifiedCallbacks() {
        val guard = PamBackDispatchGuard()
        guard.modalConsumed(key = firstKey)
        assertTrue(guard.suppressesUnidentifiedCallback())
        guard.endDispatchTurn()
        assertFalse(guard.suppressesUnidentifiedCallback())
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = true))
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = false))
        assertFalse(guard.observeKey(nextKey, canBeginInteraction = true))
    }

    @Test
    fun duplicateUnidentifiedModalCallbackDoesNotForgetTheConsumedKey() {
        val guard = PamBackDispatchGuard()
        guard.modalConsumed(key = firstKey)
        guard.modalConsumed()
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = true))
        assertFalse(guard.observeKey(nextKey, canBeginInteraction = true))
        assertFalse(guard.suppressesUnidentifiedCallback())
    }

    @Test
    fun separateKeyDevicesCanStartDifferentGesturesAtTheSameTime() {
        val guard = PamBackDispatchGuard()
        guard.modalConsumed(key = firstKey)
        assertFalse(guard.observeKey(PamBackKey(2, firstKey.downTime), canBeginInteraction = true))
        assertFalse(guard.suppressesUnidentifiedCallback())
    }
}
