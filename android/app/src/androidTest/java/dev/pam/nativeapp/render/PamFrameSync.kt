package dev.pam.nativeapp.render

import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Waits until the main thread has rendered [count] more Choreographer frames,
 * including each frame's traversal (layout and draw).
 *
 * `waitForIdleSync` only drains the message queue: a frame whose vsync has not
 * arrived yet is not part of it, so frame-ordered assertions must count
 * frames explicitly instead of sleeping.
 */
internal fun awaitFrames(instrumentation: Instrumentation, count: Int = 1) {
    require(count > 0)
    val done = CountDownLatch(1)
    instrumentation.runOnMainSync {
        val main = Handler(Looper.getMainLooper())
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            private var remaining = count

            override fun doFrame(frameTimeNanos: Long) {
                if (--remaining > 0) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    // Runs after this frame's traversal has finished.
                    main.post { done.countDown() }
                }
            }
        })
    }
    // No waitForIdleSync here: a view animating forever (shimmer, spinner)
    // keeps a traversal barrier queued and the main thread never reports idle.
    check(done.await(5, TimeUnit.SECONDS)) { "$count frame(s) were not rendered within 5 s" }
}

/**
 * A software copy of the current window as composited by the system. The
 * capture can be refused transiently (null), so it is retried on later frames.
 */
internal fun windowCapture(instrumentation: Instrumentation): Bitmap {
    val deadline = SystemClock.uptimeMillis() + 5_000L
    while (true) {
        val capture = instrumentation.uiAutomation.takeScreenshot()
        if (capture != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && capture.config == Bitmap.Config.HARDWARE) {
                return capture.copy(Bitmap.Config.ARGB_8888, false).also { capture.recycle() }
            }
            return capture
        }
        check(SystemClock.uptimeMillis() < deadline) { "Window capture unavailable" }
        awaitFrames(instrumentation)
    }
}

/**
 * Captures the window once the main thread has drawn pending changes and the
 * compositor shows them: two consecutive captures, a frame apart, are equal.
 */
internal fun stableWindowCapture(instrumentation: Instrumentation): Bitmap {
    awaitFrames(instrumentation, 2)
    val deadline = SystemClock.uptimeMillis() + 5_000L
    var previous = windowCapture(instrumentation)
    while (true) {
        awaitFrames(instrumentation)
        val current = windowCapture(instrumentation)
        if (current.sameAs(previous) || SystemClock.uptimeMillis() >= deadline) {
            previous.recycle()
            return current
        }
        previous.recycle()
        previous = current
    }
}
