package dev.pam.nativeapp.modules

fun interface ModuleCompletion {
    fun complete(status: ModuleResultStatus, payload: ByteArray)
}

enum class ModuleResultStatus(val value: Int) {
    SUCCESS(1),
    FAILURE(2),
}

interface NativeModule {
    /**
     * Implementations may perform slow work away from the UI thread.
     *
     * The completion can be invoked from any thread. Pam Native serializes the
     * result before it re-enters the persistent PHP runtime.
     */
    fun invoke(
        method: String,
        payload: ByteArray,
        completion: ModuleCompletion,
    )
}
