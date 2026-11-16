package dev.pam.nativeapp.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log

/**
 * The GL renderer the device draws with. CI emulators draw GL in software
 * (SwiftShader): every frame's draw costs ~20-25 ms whatever the view tree
 * did and takes the host CPU from the emulator, so whole-frame budgets only
 * hold on a hardware renderer. Structural checks hold on both.
 */
internal object PamGlRenderer {
    private const val TAG = "PamGlRenderer"

    /** GL_RENDERER names of software rasterizers. */
    private val SOFTWARE_RENDERERS = listOf("swiftshader", "llvmpipe", "softpipe", "software")

    /** GL_RENDERER of a throwaway EGL context, or null when the probe fails. */
    val name: String? by lazy(::probe)

    /** Unknown counts as software: the whole-frame budgets are only meaningful on a GPU. */
    val isHardware: Boolean
        get() = isHardware(name)

    fun isHardware(renderer: String?): Boolean {
        val name = renderer?.lowercase() ?: return false
        return SOFTWARE_RENDERERS.none { it in name }
    }

    private fun probe(): String? = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(
                display,
                intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, count, 0,
            ) && count[0] > 0,
        )
        val config = configs[0]
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT)
        val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        try {
            check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, surface, surface, context))
            GLES20.glGetString(GLES20.GL_RENDERER)
        } finally {
            // The display stays initialized: the process's renderer shares it.
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
        }
    }.onFailure { Log.w(TAG, "GL renderer probe failed", it) }.getOrNull()
}
