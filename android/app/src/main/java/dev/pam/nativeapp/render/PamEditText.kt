package dev.pam.nativeapp.render

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Editable
import android.text.method.KeyListener
import android.util.AttributeSet
import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

internal class PamEditText @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
) : EditText(context, attributes) {
    private var editableKeyListener: KeyListener? = keyListener
    private val originalBackground = background
    private val originalBackgroundTint = backgroundTintList
    private val originalCursorState: Drawable.ConstantState? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            textCursorDrawable?.constantState
        } else {
            null
        }
    private var contextMenuHidden = false
    private var contentSizeScheduled = false
    private var focusVisibilityScheduled = false
    private var lastContentWidth = -1
    private var lastContentHeight = -1
    private var selectionCallback: ((Int, Int) -> Unit)? = null
    private var contentSizeCallback: ((Int, Int) -> Unit)? = null
    private var keyCallback: ((String) -> Unit)? = null

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        isFocusable = enabled
        isFocusableInTouchMode = enabled
        if (!enabled) clearFocus()
    }

    fun setInputCallbacks(
        selection: ((Int, Int) -> Unit)?,
        contentSize: ((Int, Int) -> Unit)?,
        key: ((String) -> Unit)?,
    ) {
        selectionCallback = selection
        contentSizeCallback = contentSize
        keyCallback = key
        if (contentSize != null) scheduleContentSize()
    }

    fun setEditableValue(editable: Boolean) {
        if (editable) {
            if (keyListener == null) {
                keyListener = editableKeyListener
            }
        } else {
            if (keyListener != null) {
                editableKeyListener = keyListener
            }
            keyListener = null
        }
    }

    fun setContextMenuHidden(hidden: Boolean) {
        if (contextMenuHidden == hidden) return
        contextMenuHidden = hidden
        customSelectionActionModeCallback = if (hidden) {
            BLOCKED_ACTION_MODE
        } else {
            null
        }
        customInsertionActionModeCallback = if (hidden) {
            BLOCKED_ACTION_MODE
        } else {
            null
        }
        isLongClickable = !hidden
    }

    fun setCursorColor(color: Int?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        textCursorDrawable = if (color == null) {
            originalCursorState?.newDrawable(resources)?.mutate()
        } else {
            textCursorDrawable?.mutate()?.apply { setTint(color) }
        }
    }

    fun setUnderlineColor(color: Int?) {
        val transparent = color != null && Color.alpha(color) == 0
        if (transparent && background === originalBackground) {
            // A transparent tint still lets the framework EditText drawable
            // paint its focused accent on some Android versions. Remove only
            // that original platform drawable; never discard a custom PAM
            // background that may have been applied afterwards.
            background = null
        } else if (!transparent && background == null && originalBackground != null) {
            background = originalBackground
        }
        backgroundTintList = color?.let(ColorStateList::valueOf)
            ?: originalBackgroundTint
    }

    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        selectionCallback?.invoke(start, end)
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int,
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        scheduleContentSize()
    }

    override fun onLayout(
        changed: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        super.onLayout(changed, left, top, right, bottom)
        scheduleContentSize()
        scheduleFocusedVisibility()
    }

    private fun scheduleFocusedVisibility(attempt: Int = 0) {
        if (!hasFocus() || focusVisibilityScheduled) return
        focusVisibilityScheduled = true
        postDelayed({
            focusVisibilityScheduled = false
            if (!isAttachedToWindow || !hasFocus()) return@postDelayed
            val imeVisible = !(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
            )
            if (imeVisible) {
                var ancestor = parent as? View
                while (ancestor != null) {
                    if (ancestor is PamScrollContainer) {
                        ancestor.ensureViewportTargetVisible(this)
                        break
                    }
                    ancestor = ancestor.parent as? View
                }
            }
            // Orientation dispatches layout before the replacement IME inset
            // is necessarily visible. Reconcile for a bounded settling window;
            // losing focus cancels the chain on the next callback.
            if (attempt < FOCUS_VISIBILITY_RETRIES) {
                scheduleFocusedVisibility(attempt + 1)
            }
        }, if (attempt == 0) 0L else FOCUS_VISIBILITY_RETRY_MS)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        keyValue(keyCode, event)?.let { keyCallback?.invoke(it) }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val target = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(target, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.toString()
                    ?.take(MAX_KEY_BYTES)
                    ?.takeIf(String::isNotEmpty)
                    ?.let { keyCallback?.invoke(it) }
                return super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean {
                if (beforeLength > 0) keyCallback?.invoke("Backspace")
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean {
                if (beforeLength > 0) keyCallback?.invoke("Backspace")
                return super.deleteSurroundingTextInCodePoints(
                    beforeLength,
                    afterLength,
                )
            }
        }
    }

    private fun scheduleContentSize() {
        if (contentSizeCallback == null || contentSizeScheduled) return
        contentSizeScheduled = true
        postOnAnimation {
            contentSizeScheduled = false
            val textLayout = layout ?: return@postOnAnimation
            val contentWidth = textLayout.width + compoundPaddingLeft +
                compoundPaddingRight
            val contentHeight = textLayout.height + compoundPaddingTop +
                compoundPaddingBottom
            if (
                contentWidth != lastContentWidth ||
                contentHeight != lastContentHeight
            ) {
                lastContentWidth = contentWidth
                lastContentHeight = contentHeight
                contentSizeCallback?.invoke(contentWidth, contentHeight)
            }
        }
    }

    private fun keyValue(keyCode: Int, event: KeyEvent): String? =
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            else -> event.unicodeChar
                .takeIf { it > 0 }
                ?.let(Character::toString)
        }

    private companion object {
        const val FOCUS_VISIBILITY_RETRIES = 8
        const val FOCUS_VISIBILITY_RETRY_MS = 100L
        const val MAX_KEY_BYTES = 64
        val BLOCKED_ACTION_MODE = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean =
                false

            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean =
                false

            override fun onActionItemClicked(
                mode: ActionMode?,
                item: MenuItem?,
            ): Boolean = false

            override fun onDestroyActionMode(mode: ActionMode?) = Unit
        }
    }
}
