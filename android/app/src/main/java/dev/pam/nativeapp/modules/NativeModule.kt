package dev.pam.nativeapp.modules

fun interface ModuleCompletion {
    fun complete(status: ModuleResultStatus, payload: ByteArray)
}

enum class ModuleResultStatus(val value: Int) {
    SUCCESS(1),
    FAILURE(2),
}

interface NativeModule {
    fun invoke(
        method: String,
        payload: ByteArray,
        completion: ModuleCompletion,
    )
}

