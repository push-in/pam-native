package dev.pam.nativeapp.views

import android.view.View

/** Optional capability for custom views owning collapsible declarative children.
 * Invoke on the UI thread, with a direct declarative child. PAM recalculates
 * layout after the current render transaction; hosts must not call during draw.
 */
interface NativeChildVisibilityHost {
    var onChildVisibilityChanged: ((View, Boolean) -> Unit)?
}
