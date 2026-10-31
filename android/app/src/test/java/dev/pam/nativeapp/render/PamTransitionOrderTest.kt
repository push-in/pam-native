package dev.pam.nativeapp.render

import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.PropKey
import dev.pam.nativeapp.protocol.PropValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PamTransitionOrderTest {
    @Test
    fun aTransitionTurnedOnWithItsFirstChangeIsAppliedBeforeIt() {
        val scale = Mutation.Update(7, PropKey.SCALE_X, PropValue.Integer(2))
        val spec = Mutation.Update(7, PropKey.TRANSITION_SPEC, PropValue.Text("transform 300ms"))
        val animate = Mutation.Update(7, PropKey.ANIMATE_CHANGES, PropValue.Flag(true))
        assertEquals(listOf(spec, scale), transitionSpecsFirst(listOf(scale, spec)))
        assertEquals(listOf(animate, spec, scale), transitionSpecsFirst(listOf(scale, animate, spec)))
    }

    @Test
    fun aBatchWithoutTransitionSpecKeepsItsOrder() {
        val batch = listOf(Mutation.Update(7, PropKey.SCALE_X, PropValue.Integer(2)))
        assertSame(batch, transitionSpecsFirst(batch))
    }
}
