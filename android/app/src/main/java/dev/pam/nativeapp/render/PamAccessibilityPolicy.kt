package dev.pam.nativeapp.render

import android.view.accessibility.AccessibilityNodeInfo

internal const val ACCESSIBILITY_ROLE_ADJUSTABLE = 6
internal const val ACCESSIBILITY_SCROLL_FORWARD = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
internal const val ACCESSIBILITY_SCROLL_BACKWARD = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD

/**
 * An `adjustable` node with an `on:accessibilityAction` handler takes
 * TalkBack's slider gestures (scroll forward/backward, as a SeekBar does) and
 * dispatches them as the `increment`/`decrement` actions VoiceOver's swipe
 * up/down sends, without the app declaring them in `accessibilityActions`.
 */
internal fun adjustsWithAccessibilityActions(role: Int, hasActionHandler: Boolean): Boolean =
    role == ACCESSIBILITY_ROLE_ADJUSTABLE && hasActionHandler

internal fun adjustableAccessibilityActionName(
    role: Int,
    hasActionHandler: Boolean,
    actionId: Int,
): String? {
    if (!adjustsWithAccessibilityActions(role, hasActionHandler)) return null
    return when (actionId) {
        ACCESSIBILITY_SCROLL_FORWARD -> "increment"
        ACCESSIBILITY_SCROLL_BACKWARD -> "decrement"
        else -> null
    }
}

/**
 * The checked state a checkable role announces. `AccessibilityCheckedState::None`
 * (0) means the same as leaving the property out: the `checked` prop decides.
 */
internal fun announcedCheckedState(explicit: Long?, checked: Boolean): Int =
    explicit?.takeIf { it in 1L..3L }?.toInt() ?: if (checked) 2 else 1
