package dev.pam.nativeapp.render

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

internal class PamImageBackground(context: Context) : FrameLayout(context) {
    val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    init {
        clipChildren = false
        clipToPadding = false
        addView(
            image,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    fun insert(view: View, index: Int) {
        addView(view, (index + 1).coerceIn(1, childCount))
    }
}
