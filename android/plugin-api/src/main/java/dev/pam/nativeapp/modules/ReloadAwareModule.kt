package dev.pam.nativeapp.modules

/**
 * A module that holds a single pending prompt or waiter (a system dialog, a
 * biometric prompt, a picker). `pam dev` restarts the PHP request on a hot
 * reload, and the callback waiting for that prompt goes with it: close the
 * prompt here so it does not stay on screen answering nobody. Completions
 * delivered after this call are dropped. Called on the PHP thread before the
 * restart; post UI work to the main thread.
 */
interface ReloadAwareModule {
    fun prepareReload()
}
