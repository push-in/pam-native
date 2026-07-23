package dev.pam.nativeapp.render

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout

internal class PamModalHost(context: Context) : FrameLayout(context) {
    private val content = FrameLayout(context)
    private var dialog: Dialog? = null
    private var presentation = PRESENTATION_DIALOG
    private var desiredVisible = true

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

    fun close() {
        desiredVisible = false
        dismiss()
        content.removeAllViews()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateDialog()
    }

    override fun onDetachedFromWindow() {
        dismiss()
        super.onDetachedFromWindow()
    }

    private fun updateDialog() {
        if (!isAttachedToWindow || !desiredVisible) {
            dismiss()
            return
        }
        if (dialog?.isShowing == true) return

        dialog = Dialog(context).also { modal ->
            modal.requestWindowFeature(Window.FEATURE_NO_TITLE)
            (content.parent as? ViewGroup)?.removeView(content)
            modal.setContentView(content)
            modal.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                val width = when (presentation) {
                    PRESENTATION_DIALOG -> (resources.displayMetrics.widthPixels * 0.9f).toInt()
                    else -> ViewGroup.LayoutParams.MATCH_PARENT
                }
                setLayout(width, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            modal.show()
            modal.window?.setLayout(
                when (presentation) {
                    PRESENTATION_DIALOG -> (resources.displayMetrics.widthPixels * 0.9f).toInt()
                    else -> ViewGroup.LayoutParams.MATCH_PARENT
                },
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    private fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    private companion object {
        const val PRESENTATION_DIALOG = 2
    }
}
