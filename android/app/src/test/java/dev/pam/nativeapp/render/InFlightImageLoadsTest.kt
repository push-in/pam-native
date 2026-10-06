package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class InFlightImageLoadsTest {
    @Test
    fun `a load that completes before share returns leaves nothing registered`() {
        val loads = InFlightImageLoads<String>()

        val future = loads.share("glyph") { CompletableFuture.completedFuture("bitmap") }

        assertEquals("bitmap", future.join())
        assertEquals(0, loads.size)
    }

    @Test
    fun `a failed load is never reused by the next request of the key`() {
        val loads = InFlightImageLoads<String>()
        val failed = loads.share("glyph") {
            CompletableFuture<String>().apply { completeExceptionally(IllegalStateException("decode")) }
        }
        assertTrue(failed.isCompletedExceptionally)
        assertEquals(0, loads.size)

        var started = 0
        val retried = loads.share("glyph") {
            started++
            CompletableFuture.completedFuture("bitmap")
        }

        assertEquals(1, started)
        assertEquals("bitmap", retried.join())
    }

    @Test
    fun `a start that throws fails its future and unregisters it`() {
        val loads = InFlightImageLoads<String>()

        val future = loads.share("glyph") { error("executor rejected") }

        assertTrue(future.isCompletedExceptionally)
        assertEquals(0, loads.size)
    }

    @Test
    fun `requests of a pending key share one load`() {
        val loads = InFlightImageLoads<String>()
        val work = CompletableFuture<String>()
        var started = 0

        val first = loads.share("photo") { started++; work }
        val second = loads.share("photo") { started++; CompletableFuture.completedFuture("other") }

        assertSame(first, second)
        assertEquals(1, started)
        assertEquals(1, loads.size)
        work.complete("bitmap")
        assertEquals("bitmap", second.join())
        assertEquals(0, loads.size)
    }

    @Test
    fun `only small inline images decode on the UI thread`() {
        assertTrue(isSynchronousInlineSource("data:image/png;base64,AAAA"))
        assertFalse(isSynchronousInlineSource("https://cdn.example.test/icon.png"))
        assertFalse(
            isSynchronousInlineSource("data:image/png;base64," + "A".repeat(INLINE_SYNC_MAX_CHARS)),
        )
    }
}
