package dev.pam.nativeapp

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

class CapabilityTestActivity : Activity() {
    lateinit var root: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        setContentView(root)
    }
}
