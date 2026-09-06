package dev.pam.nativeapp

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
}
