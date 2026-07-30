package dev.pam.nativeapp.render

import android.content.Context
import android.view.View

internal class PamImageBackground(context: Context) :
    PamContainer(context) {
    val image = PamImageView(context)

    init {
        addView(
            image,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    override fun insert(view: View, index: Int) {
        addView(view, (index + 1).coerceIn(1, childCount))
    }
}
