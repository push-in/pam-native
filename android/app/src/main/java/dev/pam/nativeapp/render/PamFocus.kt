package dev.pam.nativeapp.render

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import dev.pam.nativeapp.R
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.lang.ref.WeakReference

/**
 * Remote control, game pad and keyboard focus for one node (the tag
 * `R.id.pam_focus`): who hears `onKeyDown`, which keys it takes before native
 * handling, where the D-pad goes next, and whether it remembers the last
 * focused child.
 */
internal class PamFocusInfo(val nodeId: Long) {
    var keyDown = false
    var captureKeys: Set<String> = emptySet()

    /** testIds for up, down, left and right ([PamFocus.DIRECTIONS] order). */
    val next = arrayOfNulls<String>(4)
    var group = false
    var remembered: WeakReference<View>? = null

    /** 1 draws the default ring while a remote or keyboard drives; 0 never. */
    var ring = 1

    /** A `:focus` style already shows the focus: no default ring. */
    var focusStyle = false
    var focusEvents = false

    fun isEmpty(): Boolean =
        !keyDown && captureKeys.isEmpty() && next.all { it == null } && !group &&
            ring == 1 && !focusStyle && !focusEvents
}

internal object PamFocus {
    private val DIRECTIONS = intArrayOf(
        View.FOCUS_UP,
        View.FOCUS_DOWN,
        View.FOCUS_LEFT,
        View.FOCUS_RIGHT,
    )

    /** Key codes whose down was captured: their up is swallowed too. */
    private val capturedDowns = HashSet<Int>()

    fun info(view: View): PamFocusInfo? = view.getTag(R.id.pam_focus) as? PamFocusInfo

    fun edit(view: View, nodeId: Long, change: (PamFocusInfo) -> Unit) {
        val info = info(view) ?: PamFocusInfo(nodeId)
        change(info)
        view.setTag(R.id.pam_focus, if (info.isEmpty()) null else info)
    }

    /**
     * Routes a key from the window's view tree: the nearest `onKeyDown` above
     * the focus hears it (every listener when nothing has the focus); a key
     * listed in `captureKeys` above the focus stops there, and the D-pad
     * follows `nextFocus*` and focus groups. Returns true when the key must
     * not reach native handling.
     */
    fun dispatch(root: View, event: KeyEvent, send: (Long, ByteArray) -> Boolean): Boolean {
        if (event.action == KeyEvent.ACTION_UP) return capturedDowns.remove(event.keyCode)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val key = keyName(event) ?: return false
        val focused = root.findFocus()
        val payload = payload(key, event)
        var listener: PamFocusInfo? = null
        var captured = false
        val modified = event.isCtrlPressed || event.isAltPressed || event.isMetaPressed
        var current: View? = focused
        while (current != null) {
            val info = info(current)
            if (info != null) {
                if (listener == null && info.keyDown) listener = info
                if (!captured && !modified && info.captures(key)) captured = true
            }
            current = current.parent as? View
        }
        if (focused == null) {
            forEachListener(root) { send(it.nodeId, payload) }
        } else if (listener != null) {
            send(listener.nodeId, payload)
        }
        if (captured) {
            capturedDowns += event.keyCode
            return true
        }
        val direction = direction(event.keyCode) ?: return false
        if (focused == null) return false
        if (moveFocus(root, focused, direction)) {
            capturedDowns += event.keyCode
            return true
        }
        return false
    }

    private fun PamFocusInfo.captures(key: String): Boolean =
        key in captureKeys || key == " " && "Space" in captureKeys

    private fun forEachListener(view: View, action: (PamFocusInfo) -> Unit) {
        if (!view.isShown) return
        info(view)?.takeIf { it.keyDown }?.let(action)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) forEachListener(view.getChildAt(index), action)
        }
    }

    /** nextFocus* first, then the focus group's remembered child. */
    private fun moveFocus(root: View, focused: View, direction: Int): Boolean {
        val slot = DIRECTIONS.indexOf(direction)
        info(focused)?.next?.get(slot)?.let { testId ->
            val target = findByTestId(root, testId)
            if (target != null && target.isShown && target.requestFocus(direction)) return true
        }
        val group = root as? ViewGroup ?: return false
        val candidate = FocusFinder.getInstance().findNextFocus(group, focused, direction) ?: return false
        var ancestor: View? = candidate.parent as? View
        while (ancestor != null) {
            val info = info(ancestor)
            if (info != null && info.group) {
                if (isDescendant(focused, ancestor)) return false
                val remembered = info.remembered?.get()
                return remembered != null && remembered !== candidate &&
                    remembered.isAttachedToWindow && remembered.isShown &&
                    remembered.isFocusable && isDescendant(remembered, ancestor) &&
                    remembered.requestFocus(direction)
            }
            ancestor = ancestor.parent as? View
        }
        return false
    }

    /** Every focus group above [view] remembers it. */
    fun remember(view: View) {
        var ancestor: View? = view.parent as? View
        while (ancestor != null) {
            info(ancestor)?.takeIf { it.group }?.remembered = WeakReference(view)
            ancestor = ancestor.parent as? View
        }
    }

    private fun isDescendant(view: View, ancestor: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === ancestor) return true
            current = current.parent as? View
        }
        return false
    }

    fun findByTestId(view: View, testId: String): View? {
        if (view.transitionName == testId) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findByTestId(view.getChildAt(index), testId)?.let { return it }
            }
        }
        return null
    }

    private fun direction(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
        else -> null
    }

    private fun payload(key: String, event: KeyEvent): ByteArray = WireMap.encode(
        mapOf(
            "key" to WireValue.Text(key),
            "shift" to WireValue.Flag(event.isShiftPressed),
            "control" to WireValue.Flag(event.isCtrlPressed),
            "alt" to WireValue.Flag(event.isAltPressed),
            "meta" to WireValue.Flag(event.isMetaPressed),
            "repeat" to WireValue.Flag(event.repeatCount > 0),
        ),
    )

    /** The W3C UI Events `key` value of an Android key, or null to ignore it. */
    fun keyName(event: KeyEvent): String? = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
        KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
        KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_BUTTON_A,
        -> "Enter"
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> "GoBack"
        KeyEvent.KEYCODE_ESCAPE -> "Escape"
        KeyEvent.KEYCODE_MENU -> "ContextMenu"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_DEL -> "Backspace"
        KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
        KeyEvent.KEYCODE_SPACE -> " "
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        KeyEvent.KEYCODE_MOVE_HOME -> "Home"
        KeyEvent.KEYCODE_MOVE_END -> "End"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        -> "MediaPlayPause"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "MediaPlay"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "MediaPause"
        KeyEvent.KEYCODE_MEDIA_STOP -> "MediaStop"
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "MediaFastForward"
        KeyEvent.KEYCODE_MEDIA_REWIND -> "MediaRewind"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "MediaTrackNext"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "MediaTrackPrevious"
        KeyEvent.KEYCODE_MEDIA_RECORD -> "MediaRecord"
        KeyEvent.KEYCODE_CHANNEL_UP -> "ChannelUp"
        KeyEvent.KEYCODE_CHANNEL_DOWN -> "ChannelDown"
        KeyEvent.KEYCODE_INFO -> "Info"
        KeyEvent.KEYCODE_GUIDE -> "Guide"
        KeyEvent.KEYCODE_SEARCH -> "BrowserSearch"
        KeyEvent.KEYCODE_CAPTIONS -> "ClosedCaptionToggle"
        KeyEvent.KEYCODE_PROG_RED -> "ColorF0Red"
        KeyEvent.KEYCODE_PROG_GREEN -> "ColorF1Green"
        KeyEvent.KEYCODE_PROG_YELLOW -> "ColorF2Yellow"
        KeyEvent.KEYCODE_PROG_BLUE -> "ColorF3Blue"
        KeyEvent.KEYCODE_BUTTON_X -> "GamepadX"
        KeyEvent.KEYCODE_BUTTON_Y -> "GamepadY"
        KeyEvent.KEYCODE_BUTTON_L1 -> "GamepadL1"
        KeyEvent.KEYCODE_BUTTON_R1 -> "GamepadR1"
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${event.keyCode - KeyEvent.KEYCODE_F1 + 1}"
        else -> event.unicodeChar.takeIf { it > 0 && !Character.isISOControl(it) }
            ?.let { String(Character.toChars(it)) }
    }

    /**
     * The default focus ring: drawn as the view's foreground while a remote,
     * game pad or keyboard drives the app (not in touch mode), unless a
     * `:focus` style shows the focus or the node turned it off.
     */
    fun updateRing(view: View, focused: Boolean) {
        val info = info(view)
        val wants = focused && !view.isInTouchMode && (info == null || info.ring != 0 && !info.focusStyle)
        val ring = view.getTag(R.id.pam_focus_ring) as? FocusRing
        if (wants && ring == null) {
            val drawable = FocusRing(view, view.foreground)
            view.setTag(R.id.pam_focus_ring, drawable)
            view.defaultFocusHighlightEnabled = false
            view.foreground = drawable
        } else if (!wants && ring != null) {
            view.setTag(R.id.pam_focus_ring, null)
            if (view.foreground === ring) view.foreground = ring.previous
        }
    }

    /** True for views the D-pad stops on that PAM draws a ring around. */
    fun isFocusStop(view: View): Boolean = view.isFocusable && view !is android.widget.EditText &&
        view !is androidx.recyclerview.widget.RecyclerView && view !is android.widget.ScrollView &&
        view !is android.widget.HorizontalScrollView

    private class FocusRing(private val view: View, val previous: Drawable?) : Drawable() {
        private val density = view.resources.displayMetrics.density
        private val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            color = 0xFFFFFFFF.toInt()
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = 0x99000000.toInt()
        }
        private val bounds = RectF()
        private val outline = Outline()

        override fun draw(canvas: Canvas) {
            previous?.let {
                it.bounds = getBounds()
                it.draw(canvas)
            }
            outline.setEmpty()
            view.outlineProvider?.getOutline(view, outline)
            val radius = ((view.getTag(R.id.pam_focus_radius) as? Float) ?: outline.radius).coerceAtLeast(0f)
            val half = outer.strokeWidth / 2f
            bounds.set(getBounds())
            bounds.inset(half, half)
            canvas.drawRoundRect(bounds, radius, radius, outer)
            val gap = outer.strokeWidth / 2f + inner.strokeWidth / 2f
            bounds.inset(gap, gap)
            val innerRadius = (radius - outer.strokeWidth).coerceAtLeast(0f)
            canvas.drawRoundRect(bounds, innerRadius, innerRadius, inner)
        }

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
