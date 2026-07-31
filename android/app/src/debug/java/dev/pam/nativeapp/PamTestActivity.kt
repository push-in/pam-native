package dev.pam.nativeapp

import android.os.Bundle
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import dev.pam.nativeapp.render.PamRootHost

class PamTestActivity : FragmentActivity() {
    val host: FrameLayout by lazy(LazyThreadSafetyMode.NONE) {
        PamRootHost(this).also(::setContentView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host
    }
}
