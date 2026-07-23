package dev.pam.nativeapp.views

import android.content.Context
import android.view.View
import dev.pam.nativeapp.protocol.WireValue

interface NativeViewFactory {
    /**
     * create, update, and release are always called on Android's UI thread.
     * Expensive I/O or decoding must be dispatched to a background executor.
     *
     * A factory may return a ViewGroup to host declarative Pam children. Pam
     * inserts, moves, lays out, and removes those children on the UI thread.
     */
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
