package dev.pam.nativeapp.views

import dev.pam.nativeapp.protocol.WireValue

/** Compatibility bridge for reserved protocol nodes supplied by optional plugins. */
interface NativePropertyView {
    var onProtocolEvent: ((Int, ByteArray) -> Unit)?
    fun applyProtocolProperty(key: Int, value: WireValue?)
    fun pauseHost() = Unit
    fun resumeHost() = Unit
    fun releaseHost() = Unit
}
