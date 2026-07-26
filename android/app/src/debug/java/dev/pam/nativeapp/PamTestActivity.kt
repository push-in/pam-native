package dev.pam.nativeapp

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

class PamTestActivity : Activity() {
    val host: FrameLayout by lazy(LazyThreadSafetyMode.NONE) {
        FrameLayout(this).also(::setContentView)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host
    }
}
