package dev.pam.nativeapp.views

import android.content.Context
import android.view.View
import dev.pam.nativeapp.protocol.WireValue

interface NativeViewFactory {
    fun create(
        context: Context,
        emit: (ByteArray) -> Unit,
    ): View

    fun update(
        view: View,
        properties: Map<String, WireValue>,
    )

    fun release(view: View) = Unit
}
