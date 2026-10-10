package dev.pam.nativeapp.modules

import android.app.Activity

/**
 * Why a location call failed, sent to PHP as "<code>: <detail>" (1.35.0).
 * Order and codes match `Pam\Native\LocationError` (1..4); iOS sends the same codes.
 */
internal enum class LocationFailure(val code: String) {
    PERMISSION("permission"),
    DISABLED("disabled"),
    UNAVAILABLE("unavailable"),
    TIMEOUT("timeout"),
    ;

    fun payload(detail: String): ByteArray = "$code: $detail".toByteArray()
}

internal class LocationException(val failure: LocationFailure, message: String) : Exception(message)

internal fun locationFailurePayload(error: Throwable): ByteArray {
    val failure = when (error) {
        is LocationException -> error.failure
        is SecurityException -> LocationFailure.PERMISSION
        else -> LocationFailure.UNAVAILABLE
    }
    return failure.payload(error.message ?: "Location operation failed")
}

/** `Location::requestServices()` result, `Pam\Native\LocationServicesResult` on the PHP side. */
internal enum class LocationServicesResult(val wire: Long) {
    ENABLED(1),
    DENIED(2),
    UNAVAILABLE(3),
    ;

    companion object {
        /** Result of Play Services' "turn on location" resolution activity. */
        fun fromResolution(resultCode: Int): LocationServicesResult =
            if (resultCode == Activity.RESULT_OK) ENABLED else DENIED
    }
}

internal fun isFreshLocation(timeMs: Long, nowMs: Long, maximumAgeMs: Long): Boolean =
    maximumAgeMs > 0 && nowMs - timeMs <= maximumAgeMs
