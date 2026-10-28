package dev.pam.nativeapp

import android.view.View

/** Lookup of mounted native views, on the UI thread. */
interface NativeRefHost {
    fun viewForNativeRef(ref: String): View?
}
