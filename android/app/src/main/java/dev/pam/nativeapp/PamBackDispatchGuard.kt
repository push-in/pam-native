package dev.pam.nativeapp

/** Identity shared by the DOWN/UP and OEM redispatches of one Back key. */
internal data class PamBackKey(val deviceId: Int, val downTime: Long)

/**
 * A modal owns its entire Back interaction, including duplicate host callbacks.
 * Without a key identity (including button Back on modern Android), only
 * redispatches in the current main-loop turn can be identified as duplicates.
 * A closing modal forwards later callbacks even while its window animates out.
 */
internal class PamBackDispatchGuard {
    private var consumedKey: PamBackKey? = null
    private var consumedInDispatchTurn = false

    fun observeKey(key: PamBackKey, canBeginInteraction: Boolean): Boolean {
        if (key == consumedKey) return true
        if (canBeginInteraction) beginInteraction()
        return false
    }

    fun beginInteraction() {
        consumedInDispatchTurn = false
    }

    fun modalConsumed(key: PamBackKey? = null) {
        if (key != null) consumedKey = key
        consumedInDispatchTurn = true
    }

    fun endDispatchTurn() {
        consumedInDispatchTurn = false
    }

    fun suppressesUnidentifiedCallback(): Boolean = consumedInDispatchTurn
}
