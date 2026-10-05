package dev.pam.nativeapp.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PamMotionTest {
    @Test
    fun springSettlingMatchesThePhpSolver() {
        // Values computed by Pam\Native\Animation\Spring::durationMs().
        assertEquals(1_272L, PamSpring(PamSpringConfig(), 0.0, 1.0).durationMs)
        assertEquals(916L, PamSpring(PamSpringConfig(300.0, 7.0, 0.5), 0.5, 1.1).durationMs)
        assertEquals(477L, PamSpring(PamSpringConfig(230.0, 22.0, 0.72), 640.0, 0.0, -2_000.0).durationMs)
        assertEquals(1_217L, PamSpring(PamSpringConfig(1_000.0, 200.0, 1.0), 0.0, 1.0).durationMs)
    }

    @Test
    fun underdampedSpringOvershootsAndSettlesOnTarget() {
        val spring = PamSpring(PamSpringConfig(300.0, 7.0, 0.5), 0.5, 1.1)
        val peak = (0..spring.durationMs).maxOf { spring.position(it / 1_000.0) }
        assertTrue("heart burst spring must overshoot", peak > 1.1)
        assertEquals(1.1, spring.position(spring.durationMs / 1_000.0), 1e-9)
        assertEquals(0.5, spring.position(0.0), 1e-9)
    }

    @Test
    fun cubicBezierMatchesCssKeywords() {
        val ease = PamEasings.parse("ease")
        assertEquals(0f, ease.transform(0f), 1e-6f)
        assertEquals(1f, ease.transform(1f), 1e-6f)
        // CSS `ease` at x=0.5 is ~0.8024.
        assertEquals(0.8024f, ease.transform(0.5f), 2e-3f)
        val linearBezier = PamEasings.parse("bezier:0.25:0.25:0.75:0.75")
        assertEquals(0.37f, linearBezier.transform(0.37f), 1e-3f)
        assertEquals(0.25f, PamEasings.parse("ease-in-quad").transform(0.5f), 1e-6f)
    }

    @Test
    fun programParsesPhasesAndRejectsUnknownProperties() {
        val program = PamMotionProgram.parse(
            """
            pam-motion 1 id=42 iterations=-1
            0 scale set(0.5) spring(1.1,300,7,0.5,0,0) timing(1,120,ease-in-out-quad,0)
            0 opacity set(0) timing(1,60,linear,0) wait(360) timing(0,260,ease-in-quad,0)
            1 translateX timing(-100%,200,linear,10)
            """.trimIndent(),
        )
        assertNotNull(program)
        program!!
        assertEquals(42L, program.id)
        assertEquals(-1, program.iterations)
        assertEquals(2, program.phases.size)
        assertEquals(3, program.phases[0][PamMotionProperty.SCALE]!!.size)
        assertEquals(
            PamMotionStep.Timing(PamMotionValue(-100.0, percent = true), 200L, "linear", 10L),
            program.phases[1][PamMotionProperty.TRANSLATE_X]!!.single(),
        )
        assertNull(PamMotionProgram.parse("pam-motion 1 id=1\n0 width timing(1,10,linear,0)"))
        assertNull(PamMotionProgram.parse("not a program"))
    }

    @Test
    fun timelineRunsTracksInParallelAndPhasesSequentially() {
        val program = PamMotionProgram.parse(
            """
            pam-motion 1 id=1 iterations=1
            0 opacity timing(1,100,linear,0)
            0 translateX timing(50,300,linear,0)
            1 opacity timing(0,100,linear,50)
            """.trimIndent(),
        )!!
        val timeline = PamMotionTimeline.build(
            program,
            current = { 0.0 },
            resolve = { _, value -> value.number },
        )
        assertEquals(450L, timeline.durationMs)
        assertEquals(0.5, timeline.valueAt(PamMotionProperty.OPACITY, 50L)!!, 1e-9)
        // Opacity holds at 1 until the second phase starts after the slower translateX track.
        assertEquals(1.0, timeline.valueAt(PamMotionProperty.OPACITY, 320L)!!, 1e-9)
        assertEquals(25.0, timeline.valueAt(PamMotionProperty.TRANSLATE_X, 150L)!!, 1e-9)
        assertEquals(0.5, timeline.valueAt(PamMotionProperty.OPACITY, 400L)!!, 1e-9)
        assertEquals(0.0, timeline.finalValues()[PamMotionProperty.OPACITY]!!, 1e-9)
    }

    @Test
    fun heartBurstTimelineMatchesReelPageDurations() {
        val program = PamMotionProgram.parse(
            """
            pam-motion 1 id=7 iterations=1
            0 scale set(0.5) spring(1.1,300,7,0.5,0,0) timing(1,120,ease-in-out-quad,0)
            0 opacity set(0) timing(1,60,linear,0) wait(360) timing(0,260,ease-in-quad,0)
            """.trimIndent(),
        )!!
        val timeline = PamMotionTimeline.build(program, { 1.0 }, { _, value -> value.number })
        assertEquals(916L + 120L, timeline.durationMs)
        assertEquals(1.0, timeline.valueAt(PamMotionProperty.OPACITY, 200L)!!, 1e-9)
        assertEquals(0.0, timeline.valueAt(PamMotionProperty.OPACITY, 700L)!!, 1e-9)
        assertEquals(0.5, timeline.valueAt(PamMotionProperty.SCALE, 0L)!!, 1e-9)
    }

    @Test
    fun transitionSpecUsesCssListSemantics() {
        val rules = PamTransitionSpec.parse(
            "@property transform,opacity\n@duration 300,120\n@timing spring:260:18:1,ease-out\n@delay 0,40",
        )
        val transform = PamTransitionSpec.ruleFor(rules, PamMotionProperty.TRANSLATE_X)!!
        assertEquals(PamSpringConfig(260.0, 18.0, 1.0), transform.spring)
        val opacity = PamTransitionSpec.ruleFor(rules, PamMotionProperty.OPACITY)!!
        assertEquals(120L, opacity.durationMs)
        assertEquals(40L, opacity.delayMs)
        assertEquals("ease-out", opacity.easing)
        assertNull(PamTransitionSpec.ruleFor(rules, PamMotionProperty.RADIUS))
        val cyclic = PamTransitionSpec.parse("@property opacity,scale,rotate\n@duration 100,200")
        assertEquals(100L, cyclic["rotate"]!!.durationMs)
        assertEquals(200L, PamTransitionSpec.ruleFor(cyclic, PamMotionProperty.SCALE_X)!!.durationMs)
    }

    @Test
    fun dragConfigParsesStoryDismissContract() {
        val config = PamDragConfig.parse(
            listOf(
                "axis=y", "min=0", "snaps=0,100%", "settle=spring:230:22:0.72",
                "settle.1=timing:190:ease-out", "threshold=120", "velocity=900",
                "drive=|scale|0,50%|1,0.955", "drive=replyIcon|opacity|0,46|0,1", "group=inbox",
            ).joinToString("\n"),
        )!!
        assertTrue(!config.horizontal)
        assertEquals(0.0, config.min!!, 0.0)
        assertNull(config.max)
        assertEquals(listOf(PamMotionValue(0.0), PamMotionValue(100.0, true)), config.snaps)
        assertEquals(190L, config.settleFor(1).durationMs)
        assertNull(config.settleFor(1).spring)
        assertEquals(PamSpringConfig(230.0, 22.0, 0.72), config.settleFor(0).spring)
        assertEquals(2, config.drivers.size)
        assertEquals("replyIcon", config.drivers[1].ref)
        assertEquals("inbox", config.group)
        assertNull(PamDragConfig.parse("axis=z"))
    }

    @Test
    fun dragReleaseChoosesAdjacentSnapByDistanceOrVelocity() {
        val story = listOf(0.0, 1_600.0)
        // Below both thresholds: spring back.
        assertEquals(0, PamDragMath.release(story, 0, 80.0, 200.0, 120.0, 900.0))
        // Past the distance threshold: dismiss.
        assertEquals(1, PamDragMath.release(story, 0, 130.0, 0.0, 120.0, 900.0))
        // Short fast fling: dismiss.
        assertEquals(1, PamDragMath.release(story, 0, 30.0, 1_200.0, 120.0, 900.0))
        // Fling against the drag direction does not dismiss.
        assertEquals(0, PamDragMath.release(story, 0, 30.0, -1_200.0, 120.0, 900.0))
        val swipeable = listOf(-160.0, 0.0, 80.0)
        assertEquals(0, PamDragMath.release(swipeable, 1, -70.0, 0.0, 32.0, 800.0))
        assertEquals(2, PamDragMath.release(swipeable, 1, 40.0, 0.0, 32.0, 800.0))
        // Closing an open row is the adjacent snap, not the far side.
        assertEquals(1, PamDragMath.release(swipeable, 0, -20.0, 1_500.0, 64.0, 800.0))
        // Swipe-to-reply has a single snap: always springs back.
        assertEquals(0, PamDragMath.release(listOf(0.0), 0, 60.0, 0.0, 46.0, 0.0))
    }

    @Test
    fun dragBoundsAndDriversClamp() {
        assertEquals(0.0, PamDragMath.bound(-40.0, 0.0, null, 0.0), 0.0)
        assertEquals(-6.0, PamDragMath.bound(-40.0, 0.0, null, 0.15), 1e-9)
        assertEquals(72.0, PamDragMath.bound(90.0, null, 72.0, 0.0), 0.0)
        assertEquals(0.5, PamDragMath.interpolate(listOf(0.0, 46.0), listOf(0.0, 1.0), 23.0), 1e-9)
        assertEquals(1.0, PamDragMath.interpolate(listOf(0.0, 46.0), listOf(0.0, 1.0), 72.0), 1e-9)
        assertEquals(1.0, PamDragMath.interpolate(listOf(-46.0, 0.0, 46.0), listOf(1.0, 0.0, 1.0), -60.0), 1e-9)
        assertEquals(0.5, PamDragMath.interpolate(listOf(46.0, 0.0), listOf(1.0, 0.0), 23.0), 1e-9)
        assertEquals(1, PamDragMath.nearest(listOf(-160.0, 0.0, 80.0), 3.0))
    }

    @Test
    fun tapEffectParsesAnchorAndProgram() {
        val effect = PamTapEffect.parse(
            "ref=heart;tilt=30\npam-motion 1 id=9 iterations=1\n0 scale set(0.5) spring(1.1,300,7,0.5,0,0)",
        )!!
        assertEquals("heart", effect.ref)
        assertEquals(30f, effect.tiltDegrees, 0f)
        assertEquals(9L, effect.program.id)
        assertNull(PamTapEffect.parse("ref=heart"))
        assertTrue(abs(effect.tiltDegrees) <= 180f)
    }
}
