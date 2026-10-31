package dev.pam.nativeapp.render

import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation

internal data class PamKeyboardTrack(val property: PamMotionProperty, val hidden: Double, val open: Double) {
    fun value(progress: Double): Double = hidden + (open - hidden) * progress.coerceIn(0.0, 1.0)

    companion object {
        fun parse(source: String): List<PamKeyboardTrack>? {
            val lines = source.split('\n')
            if (lines.size !in 1..3) return null
            val tracks = ArrayList<PamKeyboardTrack>(lines.size)
            for (line in lines) {
                val parts = line.split('|')
                if (parts.size != 3) return null
                val property = PamMotionProperty.parse(parts[0]) ?: return null
                if (property !in listOf(PamMotionProperty.OPACITY, PamMotionProperty.TRANSLATE_X, PamMotionProperty.TRANSLATE_Y)) return null
                if (tracks.any { it.property == property }) return null
                val hidden = parts[1].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
                val open = parts[2].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
                if (property == PamMotionProperty.OPACITY && (hidden !in 0.0..1.0 || open !in 0.0..1.0)) return null
                tracks.add(PamKeyboardTrack(property, hidden, open))
            }
            return tracks
        }
    }
}

internal fun keyboardAnimationProgress(start: Double, end: Double, fraction: Double): Double =
    (start + (end - start) * fraction.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)

/** One source per window, installed only while an opt-in consumer is attached. */
internal class PamKeyboardProgressSource(private val host: PamRootHost) {
    private val listeners = LinkedHashMap<View, (Double) -> Unit>()
    var progress = 0.0
        private set
    private var start = 0.0
    private var end = 0.0
    private var animation: WindowInsetsAnimation? = null
    private var observer: View? = null

    fun subscribe(owner: View, listener: (Double) -> Unit): AutoCloseable {
        if (Build.VERSION.SDK_INT < 30) {
            return host.legacyImeInsets.subscribe(owner) {
                progress = if (host.legacyImeInsets.bottom > 0) 1.0 else 0.0
                listener(if (it > 0) 1.0 else 0.0)
            }
        }
        listeners[owner] = listener
        if (listeners.size == 1) {
            progress = settled()
            val insetObserver = View(host.context)
            observer = insetObserver
            insetObserver.setOnApplyWindowInsetsListener { _, insets ->
                onInsets(insets)
                insets
            }
            insetObserver.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(next: WindowInsetsAnimation) {
                    if (next.typeMask and WindowInsets.Type.ime() == 0) return
                    start = progress
                    animation = next
                }

                override fun onStart(next: WindowInsetsAnimation, bounds: WindowInsetsAnimation.Bounds): WindowInsetsAnimation.Bounds {
                    // Target visibility was captured from onApplyWindowInsets after onPrepare.
                    return bounds
                }

                override fun onProgress(insets: WindowInsets, runningAnimations: MutableList<WindowInsetsAnimation>): WindowInsets {
                    val current = animation ?: return insets
                    if (current in runningAnimations) {
                        publish(keyboardAnimationProgress(start, end, current.interpolatedFraction.toDouble()))
                    }
                    return insets
                }

                override fun onEnd(next: WindowInsetsAnimation) {
                    if (next !== animation) return
                    animation = null
                    publish(settled())
                }
            })
            host.addView(insetObserver, android.widget.FrameLayout.LayoutParams(0, 0))
            insetObserver.requestApplyInsets()
        }
        listener(progress)
        return AutoCloseable {
            listeners.remove(owner)
            if (listeners.isEmpty()) {
                observer?.let {
                    it.setWindowInsetsAnimationCallback(null)
                    it.setOnApplyWindowInsetsListener(null)
                    host.removeView(it)
                }
                observer = null
                animation = null
            }
        }
    }

    fun onInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30 && listeners.isNotEmpty()) {
            end = if (insets.isVisible(WindowInsets.Type.ime())) 1.0 else 0.0
            if (animation == null) publish(end)
        }
    }

    private fun settled(): Double = if (Build.VERSION.SDK_INT >= 30 &&
        host.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) 1.0 else 0.0

    private fun publish(value: Double) {
        progress = value
        for ((owner, listener) in listeners) {
            if (owner.isShown && owner.hasWindowFocus()) listener(value)
        }
    }
}

/** An attached view owns its subscription; no polling, timers or bridge events. */
internal class PamKeyboardProgressBinding(
    private val view: View,
    private val tracks: List<PamKeyboardTrack>,
) : View.OnAttachStateChangeListener, AutoCloseable {
    private var subscription: AutoCloseable? = null
    private var source: PamKeyboardProgressSource? = null
    private var treeObserver: android.view.ViewTreeObserver? = null
    private var wasShown = false
    private val visibilityListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        val shown = view.isShown && view.hasWindowFocus()
        if (shown && !wasShown) source?.let { apply(it.progress) }
        wasShown = shown
    }
    private val bases = tracks.associate { it.property to PamMotionTarget.read(view, it.property) }

    init {
        view.addOnAttachStateChangeListener(this)
        if (view.isAttachedToWindow) onViewAttachedToWindow(view)
    }

    override fun onViewAttachedToWindow(v: View) {
        var root: View? = v
        while (root != null && root !is PamRootHost) root = root.parent as? View
        subscription?.close()
        source = (root as? PamRootHost)?.keyboardProgressSource
        subscription = source?.subscribe(view, ::apply)
        wasShown = view.isShown && view.hasWindowFocus()
        treeObserver = view.viewTreeObserver.also { it.addOnGlobalLayoutListener(visibilityListener) }
    }

    private fun apply(progress: Double) {
        for (track in tracks) {
            val value = track.value(progress)
            PamMotionTarget.write(view, track.property,
                if (track.property == PamMotionProperty.OPACITY) value else value * view.resources.displayMetrics.density)
        }
    }

    override fun onViewDetachedFromWindow(v: View) {
        subscription?.close()
        subscription = null
        source = null
        treeObserver?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(visibilityListener)
        treeObserver = null
    }

    override fun close() {
        onViewDetachedFromWindow(view)
        view.removeOnAttachStateChangeListener(this)
        for ((property, value) in bases) PamMotionTarget.write(view, property, value)
    }
}
