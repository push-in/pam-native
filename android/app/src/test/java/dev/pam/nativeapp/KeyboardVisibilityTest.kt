package dev.pam.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardVisibilityTest {
    private class ManualScheduler : KeyboardVisibility.Scheduler {
        val tasks = mutableListOf<Runnable>()
        override fun post(delayMs: Long, task: Runnable) { tasks += task }
        override fun cancel(task: Runnable) { tasks -= task }
        fun run() { val pending = tasks.toList(); tasks.clear(); pending.forEach { it.run() } }
    }

    @Test fun framesAndModalHandoffEmitOnlyBinaryTransitions() {
        val events = mutableListOf<Boolean>()
        val visibility = KeyboardVisibility(ManualScheduler()) { visible, _ -> events.add(visible) }
        for (inset in 1..300) visibility.update(0, inset.toFloat())
        visibility.update(7, 300f)
        visibility.update(0, 0f)
        assertEquals(listOf(true), events)
        visibility.update(7, 0f)
        assertEquals(listOf(true, false), events)
    }

    @Test fun backgroundDropsSurfacesAndIgnoresLateFramesUntilResume() {
        val events = mutableListOf<Boolean>()
        val visibility = KeyboardVisibility(ManualScheduler()) { visible, _ -> events.add(visible) }
        visibility.update(9, 200f)
        visibility.setActive(false)
        visibility.update(9, 250f)
        visibility.setActive(true)
        visibility.update(0, 200f)
        visibility.update(0, 0f)
        assertEquals(listOf(true, false, true, false), events)
    }

    @Test fun showCarriesTheSettledHeightAndHeightChangesSettleOnce() {
        val scheduler = ManualScheduler()
        val events = mutableListOf<Pair<Boolean, Float>>()
        val visibility = KeyboardVisibility(scheduler) { visible, height -> events.add(visible to height) }
        visibility.updateHeight(0, 301.26f)
        visibility.update(0, 301.26f)
        assertEquals(listOf(true to 301.3f), events)
        // A suggestion strip grows the IME through several settled dispatches.
        visibility.updateHeight(0, 320f)
        visibility.updateHeight(0, 345f)
        assertEquals(1, scheduler.tasks.size)
        scheduler.run()
        assertEquals(listOf(true to 301.3f, true to 345f), events)
        visibility.updateHeight(0, 345.02f)
        assertEquals(0, scheduler.tasks.size)
        visibility.updateHeight(0, 0f)
        visibility.update(0, 0f)
        assertEquals(true, scheduler.tasks.isEmpty())
        assertEquals(false to 0f, events.last())
    }

    @Test fun dialogVisibilityBeforeItsHeightPublishesTheHeightAfterSettling() {
        val scheduler = ManualScheduler()
        val events = mutableListOf<Pair<Boolean, Float>>()
        val visibility = KeyboardVisibility(scheduler) { visible, height -> events.add(visible to height) }
        visibility.update(7, 1f)
        visibility.updateHeight(7, 280f)
        scheduler.run()
        assertEquals(listOf(true to 0f, true to 280f), events)
        visibility.setActive(false)
        assertEquals(false to 0f, events.last())
        visibility.updateHeight(7, 290f)
        assertEquals(true, scheduler.tasks.isEmpty())
    }

    @Test fun payloadKeepsTheLegacyVisibilityPrefix() {
        assertEquals("1;301.3;250", String(KeyboardVisibility.payload(true, 301.26f, 250)))
        assertEquals("0;0.0;0", String(KeyboardVisibility.payload(false, 300f)))
    }
}
