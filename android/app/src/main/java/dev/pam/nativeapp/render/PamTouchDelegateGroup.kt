package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.Region
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo

internal class PamTouchDelegateGroup(private val parent: View) : TouchDelegate(Rect(), parent) {
    private data class Entry(
        val bounds: Rect,
        val delegate: TouchDelegate,
    )

    private val entries = LinkedHashMap<View, Entry>()
    private var active: TouchDelegate? = null

    fun update(target: View, bounds: Rect) {
        entries[target] = Entry(
            bounds = Rect(bounds),
            delegate = LocalTouchDelegate(Rect(bounds), target, parent),
        )
    }

    fun updateTranslated(target: View, bounds: Rect) {
        entries[target] = Entry(
            bounds = Rect(bounds),
            delegate = TranslatedTouchDelegate(Rect(bounds), target, parent),
        )
    }

    fun remove(target: View) {
        val removed = entries.remove(target)?.delegate
        if (active === removed) {
            active = null
        }
    }

    fun isEmpty(): Boolean = entries.isEmpty()

    @SuppressLint("NewApi")
    override fun getTouchDelegateInfo(): AccessibilityNodeInfo.TouchDelegateInfo =
        AccessibilityNodeInfo.TouchDelegateInfo(
            entries.map { (target, entry) ->
                Region(entry.bounds) to target
            }.toMap(),
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            active = entries
                .filter { (target, entry) ->
                    target.isEnabled
                        && target.visibility == View.VISIBLE
                        && target.alpha > 0.01f
                        && entry.bounds.contains(event.x.toInt(), event.y.toInt())
                }
                .maxWithOrNull(
                    compareBy<Map.Entry<View, Entry>> { (target, _) ->
                        target.z
                    }.thenBy { (target, _) ->
                        (target.parent as? ViewGroup)?.indexOfChild(target) ?: -1
                    },
                )
                ?.value
                ?.delegate
        }
        val handled = active?.onTouchEvent(event) ?: false
        if (
            event.actionMasked == MotionEvent.ACTION_UP
            || event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            active = null
        }

        return handled
    }
}

private class TranslatedTouchDelegate(
    bounds: Rect,
    private val target: View,
    private val eventHost: View,
) : TouchDelegate(bounds, target) {
    private val touchBounds = Rect(bounds)
    private var active = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> active = touchBounds.contains(
                event.x.toInt(),
                event.y.toInt(),
            )
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> if (!active) return false
        }
        if (!active) return false

        val local = MotionEvent.obtain(event)
        val hostLocation = IntArray(2)
        val targetLocation = IntArray(2)
        eventHost.getLocationOnScreen(hostLocation)
        target.getLocationOnScreen(targetLocation)
        local.offsetLocation(
            (hostLocation[0] - targetLocation[0]).toFloat(),
            (hostLocation[1] - targetLocation[1]).toFloat(),
        )
        val handled = target.dispatchTouchEvent(local)
        local.recycle()
        if (
            event.actionMasked == MotionEvent.ACTION_UP
            || event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            active = false
        }
        return handled
    }
}

/**
 * Hit-slop delegate that keeps the touch position. The platform
 * [TouchDelegate] re-centres every delegated event in the target, so a large
 * delegated view (a Pressable panel inside a Pressable backdrop) routed every
 * tap to whatever child sat at its centre. Events are mapped into the
 * target's coordinates and only slop-region points are clamped onto its edge.
 */
internal class LocalTouchDelegate(
    bounds: Rect,
    private val target: View,
    private val host: View,
) : TouchDelegate(bounds, target) {
    private val touchBounds = Rect(bounds)
    private var active = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> active = touchBounds.contains(event.x.toInt(), event.y.toInt())
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> if (!active) return false
        }
        if (!active) return false
        val local = MotionEvent.obtain(event)
        val point = targetLocalPoint(
            parentX = event.x,
            parentY = event.y,
            hostScrollX = host.scrollX,
            hostScrollY = host.scrollY,
            targetLeft = target.left,
            targetTop = target.top,
            translationX = target.translationX,
            translationY = target.translationY,
            width = target.width,
            height = target.height,
        )
        local.offsetLocation(point.first - event.x, point.second - event.y)
        val handled = target.dispatchTouchEvent(local)
        local.recycle()
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            active = false
        }
        return handled
    }
}

/** Maps a host-local point into [target]-local coordinates, clamped inside it. */
internal fun targetLocalPoint(
    parentX: Float,
    parentY: Float,
    hostScrollX: Int,
    hostScrollY: Int,
    targetLeft: Int,
    targetTop: Int,
    translationX: Float,
    translationY: Float,
    width: Int,
    height: Int,
): Pair<Float, Float> {
    val x = parentX + hostScrollX - targetLeft - translationX
    val y = parentY + hostScrollY - targetTop - translationY
    val maxX = (width - 1).coerceAtLeast(0).toFloat()
    val maxY = (height - 1).coerceAtLeast(0).toFloat()
    return x.coerceIn(0f, maxX) to y.coerceIn(0f, maxY)
}
