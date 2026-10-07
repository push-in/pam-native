package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PamParseCacheTest {
    @Test
    fun parsesEachSourceOnceIncludingFailures() {
        val cache = PamParseCache<String>(capacity = 2)
        var calls = 0
        val parse = { source: String -> calls++; source.takeIf { it != "bad" }?.uppercase() }
        val first = cache.getOrParse("a", parse)
        assertSame(first, cache.getOrParse(String(charArrayOf('a')), parse))
        assertNull(cache.getOrParse("bad", parse))
        assertNull(cache.getOrParse("bad", parse))
        assertEquals(2, calls)
    }

    @Test
    fun evictsTheLeastRecentlyUsedSource() {
        val cache = PamParseCache<String>(capacity = 2)
        var calls = 0
        val parse = { source: String -> calls++; source }
        cache.getOrParse("a", parse)
        cache.getOrParse("b", parse)
        cache.getOrParse("a", parse)
        cache.getOrParse("c", parse)
        cache.getOrParse("a", parse)
        assertEquals(3, calls)
        cache.getOrParse("b", parse)
        assertEquals(4, calls)
    }

    @Test
    fun cachedDragConfigMatchesParse() {
        val source = "axis=x\nsnaps=0,64\nthreshold=24"
        assertEquals(PamDragConfig.parse(source), PamDragConfig.cached(source))
        assertSame(PamDragConfig.cached(source), PamDragConfig.cached(source))
    }
}
