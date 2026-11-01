package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.WeakHashMap

/** One view that ends inside a system-bar, cutout or home-indicator inset. */
data class PamSafeAreaViolation(
    val view: View,
    /** 0 left, 1 top, 2 right, 3 bottom. */
    val edge: Int,
    /** Pixels of the view inside the inset. */
    val intrusionPx: Int,
    val description: String,
) {
    val edgeName: String get() = EDGE_NAMES[edge.coerceIn(0, 3)]

    private companion object {
        val EDGE_NAMES = arrayOf("left", "top", "right", "bottom")
    }
}

/**
 * Debug safe-area lint on the real native views of every PAM window (the
 * activity and each Modal/BottomSheet dialog).
 *
 * After a layout settles it checks text, inputs, buttons and clickable views
 * against the window's system-bar and display-cutout insets and logs each
 * intrusion (`adb logcat -s PamSafeArea`). Content inside a scrolling
 * container counts only when the container cannot scroll further toward
 * that edge: a row may scroll under the bar, the last row may not end there.
 *
 * Enabled in debuggable builds (opt out: `adb shell setprop
 * debug.pam.safearea 0`). `adb shell setprop debug.pam.safearea.overlay 1`
 * also paints the inset areas and outlines offending views. Release builds
 * never attach it, and nothing runs while a window is hidden.
 */
object PamSafeAreaInspector {
    const val TAG = "PamSafeArea"
    private const val SETTLE_DELAY_MS = 300L

    private val attached = WeakHashMap<Window, Attachment>()

    fun enabled(context: Context): Boolean {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return debuggable && systemProperty("debug.pam.safearea") != "0"
    }

    private fun overlayEnabled(): Boolean = systemProperty("debug.pam.safearea.overlay") == "1"

    /** Starts inspecting [window] (no-op unless [enabled]). */
    fun attach(window: Window, label: String) {
        val decor = window.decorView
        if (!enabled(decor.context) || attached.containsKey(window)) return
        attached[window] = Attachment(window, label).also(Attachment::start)
    }

    fun detach(window: Window) {
        attached.remove(window)?.stop()
    }

    /** Current intrusions of [window] (used by instrumentation tests). */
    fun violations(window: Window): List<PamSafeAreaViolation> = scan(window.decorView)

    fun scan(decor: View): List<PamSafeAreaViolation> {
        if (!decor.isShown || decor.width == 0 || decor.height == 0) return emptyList()
        val insets = ViewCompat.getRootWindowInsets(decor) ?: return emptyList()
        val safe = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        if (safe.left == 0 && safe.top == 0 && safe.right == 0 && safe.bottom == 0) return emptyList()
        val origin = IntArray(2).also(decor::getLocationOnScreen)
        val window = Rect(origin[0], origin[1], origin[0] + decor.width, origin[1] + decor.height)
        val bands = intArrayOf(safe.left, safe.top, safe.right, safe.bottom)
        val result = ArrayList<PamSafeAreaViolation>()
        val location = IntArray(2)
        fun visit(view: View) {
            if (view.visibility != View.VISIBLE || view.alpha <= 0.01f || view is Overlay) return
            if (isContent(view, window)) {
                view.getLocationOnScreen(location)
                val left = location[0]
                val top = location[1]
                val distance = intArrayOf(
                    left - window.left,
                    top - window.top,
                    window.right - (left + view.width),
                    window.bottom - (top + view.height),
                )
                for (edge in 0..3) {
                    val intrusion = bands[edge] - distance[edge]
                    if (bands[edge] <= 0 || intrusion <= 1) continue
                    // Fully inside a scrolled container's hidden part: skip.
                    if (!onScreen(view, window)) continue
                    if (scrollsAway(view, edge)) continue
                    result += PamSafeAreaViolation(view, edge, intrusion, describe(view))
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(decor)
        return result
    }

    private fun isContent(view: View, window: Rect): Boolean {
        if (view.width <= 0 || view.height <= 0) return false
        return when (view) {
            is EditText, is CompoundButton -> true
            is TextView -> !view.text.isNullOrBlank()
            else -> view.isClickable &&
                view.width.toLong() * view.height < window.width().toLong() * window.height() / 2
        }
    }

    private fun onScreen(view: View, window: Rect): Boolean {
        val visible = Rect()
        return view.getGlobalVisibleRect(visible) && Rect.intersects(visible, window)
    }

    /** Whether a scrolling ancestor can still move [view] away from [edge]. */
    private fun scrollsAway(view: View, edge: Int): Boolean {
        var parent = view.parent
        while (parent is View) {
            val scrolling = parent as View
            val canScroll = when (edge) {
                3 -> scrolling.canScrollVertically(1)
                1 -> scrolling.canScrollVertically(-1)
                2 -> scrolling.canScrollHorizontally(1)
                else -> scrolling.canScrollHorizontally(-1)
            }
            if (canScroll) return true
            parent = scrolling.parent
        }
        return false
    }

    private fun describe(view: View): String = buildString {
        append(view.javaClass.simpleName)
        view.transitionName?.takeIf(String::isNotEmpty)?.let { append(" testId=").append(it) }
        when (view) {
            is TextView -> view.text?.takeIf { it.isNotBlank() }?.let {
                append(" \"").append(it.toString().take(48)).append('"')
            }
            else -> view.contentDescription?.takeIf { it.isNotBlank() }?.let {
                append(" label=\"").append(it.toString().take(48)).append('"')
            }
        }
    }

    @SuppressLint("PrivateApi")
    private fun systemProperty(name: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java, String::class.java)
            .invoke(null, name, "") as String
    }.getOrNull()?.takeIf(String::isNotEmpty)

    private class Attachment(private val window: Window, private val label: String) {
        private val decor = window.decorView
        private var overlay: Overlay? = null
        private var lastReport = ""
        private val inspect = Runnable { run() }
        private val schedule = ViewTreeObserver.OnGlobalLayoutListener {
            decor.removeCallbacks(inspect)
            decor.postDelayed(inspect, SETTLE_DELAY_MS)
        }
        private val scrolled = ViewTreeObserver.OnScrollChangedListener {
            decor.removeCallbacks(inspect)
            decor.postDelayed(inspect, SETTLE_DELAY_MS)
        }

        fun start() {
            decor.viewTreeObserver.addOnGlobalLayoutListener(schedule)
            decor.viewTreeObserver.addOnScrollChangedListener(scrolled)
        }

        fun stop() {
            decor.removeCallbacks(inspect)
            if (decor.viewTreeObserver.isAlive) {
                decor.viewTreeObserver.removeOnGlobalLayoutListener(schedule)
                decor.viewTreeObserver.removeOnScrollChangedListener(scrolled)
            }
            overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
            overlay = null
        }

        private fun run() {
            if (!decor.isShown || decor.windowVisibility != View.VISIBLE) return
            val violations = scan(decor)
            val report = violations.joinToString("\n") {
                "$label: ${it.description} ends ${pxToDp(it.intrusionPx)} dp inside the ${it.edgeName} inset"
            }
            if (report != lastReport) {
                lastReport = report
                if (report.isNotEmpty()) report.lines().forEach { Log.w(TAG, it) }
                else Log.i(TAG, "$label: no content inside the system-bar insets")
            }
            if (overlayEnabled()) showOverlay(violations) else hideOverlay()
        }

        private fun pxToDp(px: Int): String =
            "%.1f".format(px / decor.resources.displayMetrics.density)

        private fun showOverlay(violations: List<PamSafeAreaViolation>) {
            val group = decor as? ViewGroup ?: return
            val current = overlay ?: Overlay(decor.context).also {
                overlay = it
                group.addView(
                    it,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
            current.update(decor, violations)
        }

        private fun hideOverlay() {
            overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
            overlay = null
        }
    }

    /** Paints the inset bands and outlines offending views; never takes input. */
    private class Overlay(context: Context) : View(context) {
        private val band = Paint().apply { color = Color.argb(70, 255, 0, 64) }
        private val outline = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 3f * context.resources.displayMetrics.density
        }
        private var insets = intArrayOf(0, 0, 0, 0)
        private val rects = ArrayList<Rect>()

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        fun update(decor: View, violations: List<PamSafeAreaViolation>) {
            val safe = ViewCompat.getRootWindowInsets(decor)?.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            insets = intArrayOf(safe?.left ?: 0, safe?.top ?: 0, safe?.right ?: 0, safe?.bottom ?: 0)
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            val location = IntArray(2)
            rects.clear()
            violations.forEach {
                it.view.getLocationOnScreen(location)
                val left = location[0] - origin[0]
                val top = location[1] - origin[1]
                rects += Rect(left, top, left + it.view.width, top + it.view.height)
            }
            bringToFront()
            invalidate()
        }

        // A passive debug overlay: touches fall through to the app below, so
        // it never clicks and has no performClick to call.
        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (insets[0] > 0) canvas.drawRect(0f, 0f, insets[0].toFloat(), h, band)
            if (insets[1] > 0) canvas.drawRect(0f, 0f, w, insets[1].toFloat(), band)
            if (insets[2] > 0) canvas.drawRect(w - insets[2], 0f, w, h, band)
            if (insets[3] > 0) canvas.drawRect(0f, h - insets[3], w, h, band)
            rects.forEach { canvas.drawRect(it, outline) }
        }
    }
}
