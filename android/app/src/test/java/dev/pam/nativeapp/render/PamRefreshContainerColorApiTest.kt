package dev.pam.nativeapp.render

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The progress background is an ARGB colour. SwipeRefreshLayout's deprecated
 * `setProgressBackgroundColor(int)` expects a colour resource id, so the
 * container must not expose a same-named ARGB overload that a non-null Int
 * argument silently binds to the resource variant.
 */
class PamRefreshContainerColorApiTest {
    @Test
    fun progressBackgroundTakesArgbThroughADedicatedSetter() {
        val methods = PamRefreshContainer::class.java.declaredMethods
        assertTrue(methods.any { it.name == "setProgressBackgroundArgb" })
        assertFalse(methods.any { it.name == "setProgressBackgroundColor" })
    }
}
