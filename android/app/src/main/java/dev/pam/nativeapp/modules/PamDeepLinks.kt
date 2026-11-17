package dev.pam.nativeapp.modules

import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import java.util.ArrayDeque

internal object PamDeepLinks {
    private val lock = Any()
    private val events = ArrayDeque<ByteArray>()
    private var initialUrl: String? = null
    private var waiter: ModuleCompletion? = null

    fun captureInitial(url: String?) {
        val validated = validated(url) ?: return
        synchronized(lock) {
            if (initialUrl == null) initialUrl = validated
        }
    }

    fun reportOpened(url: String?) {
        val validated = validated(url) ?: return
        val payload = payload(validated)
        val pending = synchronized(lock) {
            val value = waiter
            if (value == null) {
                if (events.size >= MAX_QUEUED_EVENTS) events.removeFirst()
                events.addLast(payload)
            } else {
                waiter = null
            }
            value
        }
        pending?.complete(ModuleResultStatus.SUCCESS, payload)
    }

    /** The launch URL without consuming it (exported to PHP at boot). */
    fun launchUrl(): String? = synchronized(lock) { initialUrl }

    fun initial(completion: ModuleCompletion) {
        val url = synchronized(lock) {
            val value = initialUrl
            initialUrl = null
            value
        }
        completion.complete(
            ModuleResultStatus.SUCCESS,
            payload(url.orEmpty()),
        )
    }

    /**
     * One listener waits at a time. A new wait supersedes a stale one (a PHP
     * request that a hot reload replaced while its wait was still armed or
     * in flight): the stale waiter ends with [SUPERSEDED], which PHP ignores.
     */
    fun next(completion: ModuleCompletion) {
        var superseded: ModuleCompletion? = null
        val event = synchronized(lock) {
            superseded = waiter
            waiter = null
            if (events.isEmpty()) {
                waiter = completion
                null
            } else {
                events.removeFirst()
            }
        }
        superseded?.complete(ModuleResultStatus.FAILURE, SUPERSEDED.toByteArray())
        if (event != null) completion.complete(ModuleResultStatus.SUCCESS, event)
    }

    fun close(message: String) {
        val pending = synchronized(lock) {
            val value = waiter
            waiter = null
            value
        }
        pending?.complete(ModuleResultStatus.FAILURE, message.toByteArray())
    }

    fun prepareReload() {
        synchronized(lock) {
            waiter = null
        }
    }

    private fun payload(url: String): ByteArray =
        WireMap.encode(mapOf("url" to WireValue.Text(url)))

    private fun validated(url: String?): String? =
        url?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_URL_BYTES }

    const val SUPERSEDED = "superseded"
    private const val MAX_QUEUED_EVENTS = 32
    private const val MAX_URL_BYTES = 8_192
}
