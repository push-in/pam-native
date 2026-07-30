package dev.pam.nativeapp.modules

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Disabled manifest placeholder used when an app does not provide
 * google-services.json. Firebase classes and their bytecode remain absent.
 */
internal class PamDisabledFirebaseMessagingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
