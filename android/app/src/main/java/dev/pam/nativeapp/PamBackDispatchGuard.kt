package dev.pam.nativeapp

/** Identity shared by the DOWN/UP and OEM redispatches of one Back key. */
internal data class PamBackKey(val deviceId: Int, val downTime: Long)

/**
 * A modal owns its entire Back interaction, including duplicate host callbacks.
 * Identified new interactions release the fallback immediately. API 33's
 * OnBackInvokedCallback (and legacy OEM callbacks without a KeyEvent) supplies
 * no interaction identity: retain the existing conservative window for those.
 */
internal class PamBackDispatchGuard {
    private var consumedKey: PamBackKey? = null
    private var suppressUntil = Long.MIN_VALUE

    fun observeKey(key: PamBackKey, canBeginInteraction: Boolean): Boolean {
        if (key == consumedKey) return true
        if (canBeginInteraction) beginInteraction()
        return false
    }

    fun beginInteraction() {
        suppressUntil = Long.MIN_VALUE
    }

    fun modalConsumed(now: Long, key: PamBackKey? = null) {
        if (key != null) consumedKey = key
        suppressUntil = now + UNIDENTIFIED_CALLBACK_WINDOW_MS
    }

    fun suppressesUnidentifiedCallback(now: Long): Boolean = now <= suppressUntil

    private companion object {
        const val UNIDENTIFIED_CALLBACK_WINDOW_MS = 1_000L
    }
}
