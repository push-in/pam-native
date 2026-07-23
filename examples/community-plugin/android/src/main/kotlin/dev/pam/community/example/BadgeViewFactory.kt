package dev.pam.community.example

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.TextView
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.views.NativeViewFactory

class BadgeViewFactory(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : NativeViewFactory {
    override fun create(
        context: Context,
        emit: (ByteArray) -> Unit,
    ): View = TextView(context).apply {
        setPadding(24, 12, 24, 12)
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(91, 72, 190))
        setTypeface(typeface, Typeface.BOLD)
        setOnClickListener {
            emit(WireMap.encode(mapOf("pressed" to WireValue.Flag(true))))
        }
    }

    override fun update(
        view: View,
        properties: Map<String, WireValue>,
    ) {
        require(view is TextView) { "community.badge requires a TextView" }
        val label = properties["label"]
        view.text = if (label is WireValue.Text) label.value else "Pam Native plugin"
        view.isEnabled = (properties["enabled"] as? WireValue.Flag)?.value ?: true
    }

    override fun release(view: View) {
        view.setOnClickListener(null)
    }
}
