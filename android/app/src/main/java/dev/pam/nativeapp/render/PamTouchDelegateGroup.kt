package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.graphics.Rect
import android.graphics.Region
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

internal class PamTouchDelegateGroup(parent: View) : TouchDelegate(Rect(), parent) {
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
            active = entries.values.firstOrNull { entry ->
                entry.bounds.contains(event.x.toInt(), event.y.toInt())
            }?.delegate
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
