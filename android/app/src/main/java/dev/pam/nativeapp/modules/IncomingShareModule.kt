package dev.pam.nativeapp.modules

internal class IncomingShareModule : NativeModule, SurfaceRetirableModule {
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

    // The queue and waiter belong to the retained PHP runtime, not this Activity.
    override fun retireSurface() = Unit

    override fun close() {
        PamIncomingShares.close("Incoming-share module closed")
    }
}
