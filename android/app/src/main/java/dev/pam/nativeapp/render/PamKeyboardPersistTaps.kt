package dev.pam.nativeapp.render

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * React Native `keyboardShouldPersistTaps` (property 538). Wire values match
 * `Pam\Native\KeyboardPersistTaps`; [NONE] is the absent property, which keeps
 * PAM's historical behaviour (a tap never dismisses the keyboard).
 */
internal object KeyboardPersistTaps {
    const val NONE = 0
    const val NEVER = 1
    const val ALWAYS = 2
    const val HANDLED = 3

    const val PASS = 0
    const val DISMISS = 1
    const val DISMISS_AND_CONSUME = 2

    fun normalize(value: Int): Int = if (value in NEVER..HANDLED) value else NONE

    /** Only `never` and `handled` need a tap tracker. */
    fun tracks(mode: Int): Boolean = mode == NEVER || mode == HANDLED

    /**
     * What a completed tap does. Taps on a text input always pass (RN lets
     * them move the focus), and nothing happens without a visible keyboard.
     */
    fun decide(
        mode: Int,
        keyboardVisible: Boolean,
        tap: Boolean,
        onTextInput: Boolean,
        handledByChild: Boolean,
    ): Int = when {
        !keyboardVisible || !tap || onTextInput -> PASS
        mode == NEVER -> DISMISS_AND_CONSUME
        mode == HANDLED && !handledByChild -> DISMISS
        else -> PASS
    }
}

/**
 * Tap tracker installed on a scroll container or list only for `never` and
 * `handled`; it does no work unless the keyboard is visible at ACTION_DOWN.
 */
internal class KeyboardTapTracker(private val host: ViewGroup, val mode: Int) {
    private val touchSlop = ViewConfiguration.get(host.context).scaledTouchSlop
    private var armed = false
    private var downX = 0f
    private var downY = 0f
    private var onTextInput = false
    private var handledByChild = false
    private val location = IntArray(2)

    /**
     * Observes [event] before the host dispatches it. Returns true when the
     * final ACTION_UP must reach the children as ACTION_CANCEL (`never`: the
     * tap only dismisses the keyboard).
     */
    fun observe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                armed = keyboardVisible()
                if (armed) {
                    downX = event.rawX
                    downY = event.rawY
                    val target = findTapHandler(host, event.rawX, event.rawY)
                    onTextInput = target is EditText
                    handledByChild = target != null
                }
            }
            MotionEvent.ACTION_MOVE -> if (
                armed &&
                (abs(event.rawX - downX) > touchSlop || abs(event.rawY - downY) > touchSlop)
            ) {
                armed = false
            }
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_CANCEL,
            -> armed = false
            MotionEvent.ACTION_UP -> {
                if (!armed) return false
                armed = false
                return when (
                    KeyboardPersistTaps.decide(mode, true, true, onTextInput, handledByChild)
                ) {
                    KeyboardPersistTaps.DISMISS -> {
                        dismissKeyboard()
                        false
                    }
                    KeyboardPersistTaps.DISMISS_AND_CONSUME -> {
                        dismissKeyboard()
                        true
                    }
                    else -> false
                }
            }
        }
        return false
    }

    private fun keyboardVisible(): Boolean {
        if (host.rootView.findFocus() !is EditText) return false
        return ViewCompat.getRootWindowInsets(host)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun dismissKeyboard() {
        val focused = host.rootView.findFocus() ?: return
        (host.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()
    }

    /** Deepest visible descendant under the point that handles taps. */
    private fun findTapHandler(view: View, rawX: Float, rawY: Float): View? {
        if (view.visibility != View.VISIBLE) return null
        view.getLocationOnScreen(location)
        if (
            rawX < location[0] || rawY < location[1] ||
            rawX >= location[0] + view.width || rawY >= location[1] + view.height
        ) {
            return null
        }
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                findTapHandler(view.getChildAt(index), rawX, rawY)?.let { return it }
            }
        }
        if (view === host) return null
        if (view is EditText) return view
        if (view is ScrollView || view is HorizontalScrollView || view is RecyclerView) return null
        return if (view.isEnabled && (view.isClickable || view.isLongClickable)) view else null
    }
}
