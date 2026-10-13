package dev.pam.nativeapp.modules

internal class LinkingModule : NativeModule, SurfaceRetirableModule {
    override fun invoke(method: String, payload: ByteArray, completion: ModuleCompletion) {
        runCatching {
            when (method) {
                "initialUrl" -> PamDeepLinks.initial(completion)
                "nextUrl" -> PamDeepLinks.next(completion)
                else -> error("Unknown linking method $method")
            }
        }.onFailure { error ->
            completion.complete(
                ModuleResultStatus.FAILURE,
                (error.message ?: "Linking failed").toByteArray(),
            )
        }
    }

    // The queue and waiter belong to the retained PHP runtime, not this Activity.
    override fun retireSurface() = Unit

    override fun close() {
        PamDeepLinks.close("Linking module closed")
    }
}
