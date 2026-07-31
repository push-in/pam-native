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
            delegate = TouchDelegate(Rect(bounds), target),
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
                .filter { (_, entry) ->
                    entry.bounds.contains(event.x.toInt(), event.y.toInt())
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
