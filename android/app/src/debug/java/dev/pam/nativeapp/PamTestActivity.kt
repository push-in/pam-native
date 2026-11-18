package dev.pam.nativeapp

import android.content.res.Configuration
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import dev.pam.nativeapp.render.PamRootHost

class PamTestActivity : FragmentActivity() {
    internal val host: PamRootHost by lazy(LazyThreadSafetyMode.NONE) {
        PamRootHost(this).also(::setContentView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Match PamActivity's edge-to-edge contract so renderer integration
        // tests exercise PAM-owned system-bar insets instead of decor-fitted
        // coordinates that have already consumed them.
        WindowCompat.enableEdgeToEdge(window)
        host
    }

    /** Font scale, density and locale apply in place, like PamActivity (tests remount by hand). */
    @Volatile
    internal var appliedConfiguration: Configuration? = null

    /** Runs on each handled change, like PamActivity's remount preparation. */
    internal var onConfigurationApplied: ((Configuration) -> Unit)? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        appliedConfiguration = Configuration(newConfig)
        onConfigurationApplied?.invoke(newConfig)
    }
}
