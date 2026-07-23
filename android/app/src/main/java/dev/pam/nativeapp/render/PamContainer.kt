package dev.pam.nativeapp.render

import android.content.Context
import android.view.View
import android.widget.FrameLayout

internal class PamContainer(context: Context) : FrameLayout(context) {
    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun shouldDelayChildPressedState(): Boolean = false

    fun insert(view: View, index: Int) {
        val safeIndex = index.coerceIn(0, childCount)
        addView(view, safeIndex)
    }
}

