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
        guard.modalConsumed(now = 110, key = firstKey)
        assertTrue(guard.suppressesUnidentifiedCallback(now = 111))
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = true))
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = false))
        assertTrue(guard.suppressesUnidentifiedCallback(now = 140))

        assertFalse(guard.observeKey(nextKey, canBeginInteraction = true))
        assertFalse(guard.suppressesUnidentifiedCallback(now = 151))
        // A late DOWN/UP from the first gesture cannot pop the next screen.
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = true))
        assertFalse(guard.suppressesUnidentifiedCallback(now = 152))
    }

    @Test
    fun canceledOrRepeatedKeysDoNotReleaseTheUnidentifiedCallbackGuard() {
        val guard = PamBackDispatchGuard()
        guard.modalConsumed(now = 110, key = firstKey)
        assertFalse(guard.observeKey(nextKey, canBeginInteraction = false))
        assertTrue(guard.suppressesUnidentifiedCallback(now = 151))
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = false))
        assertTrue(guard.suppressesUnidentifiedCallback(now = 152))
    }

    @Test
    fun predictiveBackStartIdentifiesANewInteractionWithoutWaiting() {
        val guard = PamBackDispatchGuard()
        guard.modalConsumed(now = 110)
        assertTrue(guard.suppressesUnidentifiedCallback(now = 111))
        guard.beginInteraction()
        assertFalse(guard.suppressesUnidentifiedCallback(now = 112))
    }

    @Test
    fun api33AndLegacyCallbacksWithoutIdentityRetainTheConservativeFallback() {
        val guard = PamBackDispatchGuard()
        assertFalse(guard.suppressesUnidentifiedCallback(now = 0))
        guard.modalConsumed(now = 110)
        assertTrue(guard.suppressesUnidentifiedCallback(now = 111))
        assertTrue(guard.suppressesUnidentifiedCallback(now = 1_110))
        assertFalse(guard.suppressesUnidentifiedCallback(now = 1_111))
    }

    @Test
    fun duplicateUnidentifiedModalCallbackDoesNotForgetTheConsumedKey() {
        val guard = PamBackDispatchGuard()
        guard.modalConsumed(now = 110, key = firstKey)
        guard.modalConsumed(now = 111)
        assertTrue(guard.observeKey(firstKey, canBeginInteraction = true))
        assertFalse(guard.observeKey(nextKey, canBeginInteraction = true))
        assertFalse(guard.suppressesUnidentifiedCallback(now = 152))
    }

    @Test
    fun separateKeyDevicesCanStartDifferentGesturesAtTheSameTime() {
        val guard = PamBackDispatchGuard()
        guard.modalConsumed(now = 110, key = firstKey)
        assertFalse(guard.observeKey(PamBackKey(2, firstKey.downTime), canBeginInteraction = true))
        assertFalse(guard.suppressesUnidentifiedCallback(now = 111))
    }
}
