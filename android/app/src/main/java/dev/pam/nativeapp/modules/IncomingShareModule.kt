package dev.pam.nativeapp.modules

internal class IncomingShareModule : NativeModule, AutoCloseable {
    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "initial" -> PamIncomingShares.initial(completion)
                "next" -> PamIncomingShares.next(completion)
                else -> error("Unknown incoming-share method $method")
            }
        }.onFailure { error ->
            completion.complete(
                ModuleResultStatus.FAILURE,
                (error.message ?: "Incoming share failed").toByteArray(),
            )
        }
    }

    override fun close() {
        PamIncomingShares.close("Incoming-share module closed")
    }
}
