package dev.pam.nativeapp.render

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.ref.WeakReference

internal class PamModalHost(context: Context) : FrameLayout(context) {
    private val content = FrameLayout(context)
    private var dialog: Dialog? = null
    private var presentation = PRESENTATION_DIALOG
    private var desiredVisible = true
    private var onRequestClose: (() -> Unit)? = null
    private var previousFocus: WeakReference<View>? = null

    init {
        visibility = View.INVISIBLE
    }

    fun insert(view: View, index: Int) {
        content.addView(view, index.coerceIn(0, content.childCount))
    }

    fun setPresentation(value: Int) {
        if (presentation == value) return
        presentation = value
        dismiss()
        updateDialog()
    }

    fun setVisible(value: Boolean) {
        desiredVisible = value
        updateDialog()
    }

    fun setOnRequestClose(callback: (() -> Unit)?) {
        onRequestClose = callback
    }

    fun close() {
        desiredVisible = false
        dismiss()
        content.removeAllViews()
        onRequestClose = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateDialog()
    }

    override fun onDetachedFromWindow() {
        dismiss()
        super.onDetachedFromWindow()
    }

    @Suppress("DEPRECATION")
    private fun updateDialog() {
        if (!isAttachedToWindow || !desiredVisible) {
            dismiss()
            return
        }
        if (dialog?.isShowing == true) return

        previousFocus = WeakReference(rootView.findFocus())
        dialog = Dialog(context).also { modal ->
            modal.requestWindowFeature(Window.FEATURE_NO_TITLE)
            (content.parent as? ViewGroup)?.removeView(content)
            modal.setContentView(content)
            modal.setCanceledOnTouchOutside(false)
            modal.setOnKeyListener { _, keyCode, event ->
                if (
                    keyCode == KeyEvent.KEYCODE_BACK
                    && event.action == KeyEvent.ACTION_UP
                ) {
                    requestClose()
                    true
                } else {
                    false
                }
            }
            modal.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                setGravity(
                    if (presentation == PRESENTATION_SHEET) {
                        Gravity.BOTTOM
                    } else {
                        Gravity.CENTER
                    },
                )
            }
            modal.show()
            applyWindowLayout(modal)
            content.post {
                content.findFirstFocusable()?.requestFocus()
            }
        }
    }

    private fun requestClose() {
        val callback = onRequestClose
        if (callback != null) {
            callback()
            return
        }
        desiredVisible = false
        dismiss()
    }

    private fun applyWindowLayout(modal: Dialog) {
        modal.window?.setLayout(
            when (presentation) {
                PRESENTATION_DIALOG -> (resources.displayMetrics.widthPixels * 0.9f).toInt()
                else -> ViewGroup.LayoutParams.MATCH_PARENT
            },
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    private fun dismiss() {
        dialog?.dismiss()
        dialog = null
        previousFocus?.get()?.let { focus ->
            if (focus.isAttachedToWindow && focus.visibility == View.VISIBLE) {
                focus.post { focus.requestFocus() }
            }
        }
        previousFocus = null
    }

    private fun View.findFirstFocusable(): View? {
        if (this !== content && isFocusable && isEnabled && visibility == View.VISIBLE) {
            return this
        }
        if (this !is ViewGroup) return null
        repeat(childCount) { index ->
            getChildAt(index).findFirstFocusable()?.let { return it }
        }
        return null
    }

    private companion object {
        const val PRESENTATION_DIALOG = 2
        const val PRESENTATION_SHEET = 3
    }
}
