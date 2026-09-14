package dev.pam.nativeapp.render

import dev.pam.nativeapp.R

internal enum class ScrollIndicatorStyle(val wireValue: Int, val themeResource: Int) {
    AUTO(1, 0),
    DARK(2, R.style.PamScrollIndicator_Dark),
    LIGHT(3, R.style.PamScrollIndicator_Light);

    companion object {
        fun fromWire(value: Int): ScrollIndicatorStyle =
            entries.firstOrNull { it.wireValue == value } ?: AUTO
    }
}
