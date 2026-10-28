package dev.pam.nativeapp

import android.content.Context
import android.content.Intent

interface HostEventListener {
    fun initialIntent(context: Context, intent: Intent?) = Unit
    fun openedIntent(context: Context, intent: Intent?) = Unit
    fun foreground(active: Boolean) = Unit
    fun prepareReload() = Unit
}

/** Process-scoped hooks installed by optional plugins before the first Activity. */
object HostEvents {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<HostEventListener>()
    fun register(listener: HostEventListener) { listeners.addIfAbsent(listener) }
    fun initialIntent(context: Context, intent: Intent?) = listeners.forEach { it.initialIntent(context, intent) }
    fun openedIntent(context: Context, intent: Intent?) = listeners.forEach { it.openedIntent(context, intent) }
    fun foreground(active: Boolean) = listeners.forEach { it.foreground(active) }
    fun prepareReload() = listeners.forEach { it.prepareReload() }
}
