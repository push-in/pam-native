package dev.pam.nativeapp.render

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable

/** A drawable of PAM's own that paints the same whether its view is shown or hidden. */
internal interface PamStaticDrawable

/**
 * Whether [drawable] paints the same shown or hidden. A view's aggregated
 * visibility flip (its route covered or shown again, its tab left or
 * selected) calls `setVisible()` on its background, foreground and image,
 * and each call invalidates the view: every styled view of a screen
 * recorded its display list again on the frame that showed it. For these
 * drawables that invalidation changes nothing and is dropped; a ripple (its
 * hotspots are cleared when hidden) or an animated drawable keeps it.
 */
internal fun drawsSameWhenHidden(drawable: Drawable): Boolean = when (drawable) {
    is RippleDrawable -> false
    is GradientDrawable, is ColorDrawable, is BitmapDrawable, is PamStaticDrawable -> true
    is LayerDrawable -> (0 until drawable.numberOfLayers).all { index ->
        drawable.getDrawable(index)?.let(::drawsSameWhenHidden) ?: true
    }
    is InsetDrawable -> drawable.drawable?.let(::drawsSameWhenHidden) ?: true
    else -> false
}
