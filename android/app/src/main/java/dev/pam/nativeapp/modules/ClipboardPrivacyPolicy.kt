package dev.pam.nativeapp.modules

internal object ClipboardPrivacyPolicy {
    const val sensitiveExtra = "android.content.extra.IS_SENSITIVE"
    const val defaultExpirySeconds = 60L

    fun expiryMillis(seconds: Long): Long {
        require(seconds in 15L..300L) {
            "Sensitive clipboard expiry must be between 15 and 300 seconds"
        }
        return seconds * 1_000L
    }

    fun shouldClear(expectedText: String, currentText: String?): Boolean =
        currentText == expectedText
}
