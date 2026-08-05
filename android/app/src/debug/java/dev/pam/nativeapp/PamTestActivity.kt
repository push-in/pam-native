package dev.pam.nativeapp

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import dev.pam.nativeapp.render.PamRootHost

class PamTestActivity : FragmentActivity() {
    internal val host: PamRootHost by lazy(LazyThreadSafetyMode.NONE) {
        PamRootHost(this).also(::setContentView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host
    }
}
