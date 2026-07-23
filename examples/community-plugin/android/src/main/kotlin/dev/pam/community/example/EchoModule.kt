package dev.pam.community.example

import android.content.Context
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.ModuleResultStatus
import dev.pam.nativeapp.modules.NativeModule
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue

class EchoModule(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : NativeModule {
    override fun invoke(
        method: String,
        payload: ByteArray,
        completion: ModuleCompletion,
    ) {
        when (method) {
            "echo" -> completion.complete(ModuleResultStatus.SUCCESS, payload)
            "timestamp" -> completion.complete(
                ModuleResultStatus.SUCCESS,
                WireMap.encode(
                    mapOf("milliseconds" to WireValue.Integer(System.currentTimeMillis())),
                ),
            )
            else -> completion.complete(
                ModuleResultStatus.FAILURE,
                "Unknown method $method".toByteArray(),
            )
        }
    }
}
