package dev.pam.nativeapp.modules

internal class LinkingModule : NativeModule, AutoCloseable {
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

    override fun close() {
        PamDeepLinks.close("Linking module closed")
    }
}
