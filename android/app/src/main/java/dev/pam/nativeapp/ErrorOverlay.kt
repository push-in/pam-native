package dev.pam.nativeapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

internal class ErrorOverlay(context: Context) : FrameLayout(context) {
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
    }
    private val location = TextView(context).apply {
        setTextColor(0xFFFFCDD2.toInt())
        textSize = 13f
    }
    private val details = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        setTextIsSelectable(true)
    }
    private val dismiss = TextView(context).apply {
        text = context.getString(R.string.pam_error_dismiss)
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        contentDescription = context.getString(R.string.pam_error_dismiss_description)
        isClickable = true
        isFocusable = true
        setBackgroundColor(0xFF991B1B.toInt())
        setOnClickListener { clearError() }
    }

    init {
        setBackgroundColor(0xF07F1D1D.toInt())
        elevation = dp(24).toFloat()
        visibility = GONE
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        )
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(title, row())
            addView(location, row(top = 4))
            addView(details, row(top = 8))
            addView(dismiss, row(width = dp(112), height = dp(48), top = 12))
        }
        addView(content)
    }

    fun showError(message: String) {
        val diagnostic = ErrorDiagnostic.parse(message)
        title.text = diagnostic.title
        location.text = diagnostic.location
        details.text = diagnostic.details.take(MAX_ERROR_LENGTH)
        visibility = VISIBLE
        contentDescription = "${diagnostic.title}. ${diagnostic.location}"
    }

    fun clearError() {
        title.text = ""
        location.text = ""
        details.text = ""
        visibility = GONE
    }

    private fun row(
        width: Int = LayoutParams.MATCH_PARENT,
        height: Int = LayoutParams.WRAP_CONTENT,
        top: Int = 0,
    ): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val MAX_ERROR_LENGTH = 12_000
    }
}

private data class ErrorDiagnostic(
    val title: String,
    val location: String,
    val details: String,
) {
    companion object {
        fun parse(raw: String): ErrorDiagnostic {
            if (!raw.startsWith("PAMERR1\n")) {
                return ErrorDiagnostic(
                    title = "Pam Native runtime error",
                    location = "Native runtime",
                    details = raw,
                )
            }
            return runCatching {
                val objectValue = JSONObject(raw.substringAfter('\n'))
                val type = objectValue.optString("type", "PHP error").substringAfterLast('\\')
                val message = objectValue.optString("message", "Unknown PHP error")
                val file = objectValue.optString("file", "<unknown>")
                val line = objectValue.optInt("line", 1)
                val column = objectValue.optInt("column", 1)
                val trace = objectValue.optString("trace")
                ErrorDiagnostic(
                    title = type,
                    location = "$file:$line:$column",
                    details = if (trace.isBlank()) message else "$message\n\n$trace",
                )
            }.getOrElse {
                ErrorDiagnostic(
                    title = "Pam Native runtime error",
                    location = "Malformed diagnostic",
                    details = raw,
                )
            }
        }
    }
}
