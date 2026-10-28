package dev.pam.nativeapp

import android.content.Intent
import android.content.IntentSender

/**
 * Generic activity-result hook for plugin modules. The PAM Native activity
 * implements it, so a plugin module created with that activity as its context
 * can start a system screen (contact form, settings resolution, picker) and
 * receive the result without depending on the runtime activity class.
 */
interface ActivityResultHost {
    /** False while the hosting activity is finishing or destroyed. */
    val isActivityResultHostActive: Boolean

    fun launchForResult(intent: Intent, callback: (Int, Intent?) -> Unit)

    fun launchIntentSenderForResult(sender: IntentSender, callback: (Int, Intent?) -> Unit)
}
