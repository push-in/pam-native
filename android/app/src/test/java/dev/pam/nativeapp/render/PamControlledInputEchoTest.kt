package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PamControlledInputEchoTest {
    private fun typed(vararg values: String): ArrayDeque<Pair<String, Long>> =
        ArrayDeque<Pair<String, Long>>().also { queue ->
            values.forEachIndexed { index, value -> recordInputInFlight(queue, value, index.toLong()) }
        }

    @Test
    fun staleEchoOfAnOlderKeystrokeIsIgnored() {
        val inFlight = typed("qa tes", "qa test", "qa teste")
        assertTrue(isStaleInputEcho(inFlight, "qa test", "qa teste", 10))
        assertEquals(listOf("qa teste"), inFlight.map { it.first })
        assertFalse(isStaleInputEcho(inFlight, "qa teste", "qa teste", 11))
        assertTrue(inFlight.isEmpty())
    }

    @Test
    fun authoredValuesAreApplied() {
        val inFlight = typed("o", "ok")
        assertFalse(isStaleInputEcho(inFlight, "", "ok", 10))
        assertTrue(inFlight.isEmpty())
        assertFalse(isStaleInputEcho(typed("abc"), "ABC", "abc", 10))
    }

    @Test
    fun repeatedValuesMatchInDispatchOrder() {
        val inFlight = typed("a", "", "a")
        assertFalse(isStaleInputEcho(inFlight, "a", "a", 10))
        val pending = typed("a", "", "ab")
        assertTrue(isStaleInputEcho(pending, "a", "ab", 10))
        assertTrue(isStaleInputEcho(pending, "", "ab", 10))
        assertEquals(listOf("ab"), pending.map { it.first })
    }

    @Test
    fun expiredEntriesNoLongerSuppressAuthoredValues() {
        val inFlight = typed("draft", "draft!")
        assertFalse(isStaleInputEcho(inFlight, "draft", "draft!", INPUT_ECHO_WINDOW_MS + 5))
    }
}
