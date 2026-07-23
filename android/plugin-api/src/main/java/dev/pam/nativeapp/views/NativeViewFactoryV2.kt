package dev.pam.nativeapp.views

import android.content.Context
import android.view.View

/**
 * Opt-in typed event API for interactive native views.
 *
 * Legacy NativeViewFactory implementations remain source and binary compatible.
 */
interface NativeViewFactoryV2 : NativeViewFactory {
    fun create(
        context: Context,
        emitter: NativeViewEmitter,
    ): View

    override fun create(
        context: Context,
        emit: (ByteArray) -> Unit,
    ): View = create(
        context,
        NativeViewEmitter { _, payload -> emit(payload) },
    )
}
