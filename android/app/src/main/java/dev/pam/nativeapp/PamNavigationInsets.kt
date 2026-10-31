package dev.pam.nativeapp

import android.content.Context
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat

/** No synthetic bottom edge for hardware keys, hidden bars or a side navigation bar. */
internal fun navigationBottomInset(
    reported: Int,
    navigation: Int,
    tappable: Int,
    bottomBarExists: Boolean,
    fallback: Int,
): Int = maxOf(reported, navigation, tappable).let {
    if (it == 0 && bottomBarExists) fallback.coerceAtLeast(0) else it
}

internal fun windowSafeInsets(context: Context, insets: WindowInsetsCompat): Insets {
    val safe = insets.getInsetsIgnoringVisibility(
        WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
    )
    val navigation = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
    val tappable = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.tappableElement())
    val resources = context.resources
    val mode = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
    val show = resources.getIdentifier("config_showNavigationBar", "bool", "android")
    val buttons = mode != 0 && resources.getInteger(mode) == 0
    val visible = insets.isVisible(WindowInsetsCompat.Type.navigationBars())
    val bottomBar = visible && buttons && show != 0 && resources.getBoolean(show) &&
        navigation.left == 0 && navigation.right == 0 && tappable.left == 0 && tappable.right == 0
    val configured = resources.getInteger(R.integer.pam_safe_area_bottom_fallback_dp)
    val fallback = (maxOf(48, configured) * resources.displayMetrics.density + 0.5f).toInt()
    return Insets.of(safe.left, safe.top, safe.right, navigationBottomInset(
        safe.bottom, navigation.bottom, tappable.bottom, bottomBar, fallback,
    ))
}
