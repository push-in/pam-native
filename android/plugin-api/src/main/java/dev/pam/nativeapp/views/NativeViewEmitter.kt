package dev.pam.nativeapp.views

fun interface NativeViewEmitter {
    fun emit(
        kind: NativeViewEventKind,
        payload: ByteArray,
    )
}
