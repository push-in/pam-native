package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Test

class PamMemoryTrimTest {
    @Test
    fun snapshotRemainsStableWhenCleanupMutatesTheRegistry() {
        val registry = mutableListOf<Any>("first", 42, "second", "third")
        val snapshot = snapshotValues<String>(registry.size, registry::get)

        val visited = mutableListOf<String>()
        for (value in snapshot) {
            visited += value
            registry.remove(value)
        }

        assertEquals(listOf("first", "second", "third"), visited)
        assertEquals(listOf(42), registry)
    }
}
