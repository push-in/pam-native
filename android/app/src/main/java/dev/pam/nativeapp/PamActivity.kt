package dev.pam.nativeapp

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.FrameLayout
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.render.PamRenderer

class PamActivity : Activity() {
    private lateinit var runtime: PamRuntime
    private var hotReload: HotReloadClient? = null
    private var backCallback: OnBackInvokedCallback? = null
    private lateinit var errors: ErrorOverlay
    private val permissionCallbacks = HashMap<Int, (Boolean) -> Unit>()
    private var nextPermissionRequest = 40_000
    private var runtimeStarted = false
    private var fullyDrawnReported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = FrameLayout(this)
        errors = ErrorOverlay(this)
        val renderer = PamRenderer(this, host) { nodeId, kind, payload ->
            runtime.dispatchEvent(nodeId, kind, payload)
        }
        runtime = PamRuntime(
            context = this,
            renderer = renderer,
            reportError = { message -> errors.showError(message) },
            onFrameCommitted = {
                errors.clearError()
                if (!fullyDrawnReported) {
                    fullyDrawnReported = true
                    reportFullyDrawn()
                }
            },
        )
        val root = FrameLayout(this)
        root.addView(
            host,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(errors)
        setContentView(root)
        applyDefaultSystemBars()
        registerBackCallback()

        runCatching {
            val entry = AssetInstaller(this).install()
            val density = resources.displayMetrics.density
            val widthDp = resources.displayMetrics.widthPixels / density
            val heightDp = resources.displayMetrics.heightPixels / density
            runtime.start(
                entry,
                widthDp,
                heightDp,
                resources.configuration.fontScale,
                isDarkAppearance(),
            )
            runtimeStarted = true
            if (BuildConfig.DEBUG) {
                hotReload = HotReloadClient(
                    context = this,
                    onReload = { path -> runOnUiThread {
                        errors.clearError()
                        runtime.reload(path)
                    } },
                    onError = { message -> runOnUiThread { errors.showError(message) } },
                ).also { it.start() }
            }
        }.onFailure {
            errors.showError(it.message ?: "Pam Native failed to start")
        }
    }

    override fun onResume() {
        super.onResume()
        if (runtimeStarted) {
            runtime.dispatchLifecycle(EVENT_APP_STATE, APP_STATE_ACTIVE.toString().toByteArray())
        }
    }

    override fun onPause() {
        if (runtimeStarted) {
            runtime.dispatchLifecycle(EVENT_APP_STATE, APP_STATE_INACTIVE.toString().toByteArray())
        }
        super.onPause()
    }

    override fun onStop() {
        if (runtimeStarted) {
            runtime.dispatchLifecycle(EVENT_APP_STATE, APP_STATE_BACKGROUND.toString().toByteArray())
        }
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!runtimeStarted) return
        applyDefaultSystemBars()
        val density = resources.displayMetrics.density
        val widthDp = resources.displayMetrics.widthPixels / density
        val heightDp = resources.displayMetrics.heightPixels / density
        runtime.updateViewport(
            widthDp,
            heightDp,
            resources.configuration.fontScale,
            isDarkAppearance(),
        )
        runtime.dispatchLifecycle(
            EVENT_DIMENSIONS,
            WireMap.encode(
                mapOf(
                    "width" to WireValue.Decimal(widthDp.toDouble()),
                    "height" to WireValue.Decimal(heightDp.toDouble()),
                    "density" to WireValue.Decimal(density.toDouble()),
                    "appearance" to WireValue.Integer(appearanceValue()),
                ),
            ),
        )
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!runtimeStarted) return
        val pressure = if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            MEMORY_PRESSURE_CRITICAL
        } else {
            MEMORY_PRESSURE_MODERATE
        }
        runtime.trimMemory(pressure == MEMORY_PRESSURE_CRITICAL)
        runtime.dispatchLifecycle(EVENT_MEMORY_PRESSURE, pressure.toString().toByteArray())
    }

    @Deprecated("Deprecated in Android")
    override fun onLowMemory() {
        if (runtimeStarted) {
            runtime.trimMemory(critical = true)
            runtime.dispatchLifecycle(
                EVENT_MEMORY_PRESSURE,
                MEMORY_PRESSURE_CRITICAL.toString().toByteArray(),
            )
        }
        super.onLowMemory()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            keyCode == KeyEvent.KEYCODE_BACK
        ) {
            runtime.dispatchBack()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
        hotReload?.close()
        runtimeStarted = false
        runtime.close()
        permissionCallbacks.clear()
        super.onDestroy()
    }

    fun requestPamPermission(permission: String, callback: (Boolean) -> Unit) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            callback(true)
            return
        }
        val request = nextPermissionRequest++
        if (nextPermissionRequest > 60_000) nextPermissionRequest = 40_000
        permissionCallbacks[request] = callback
        requestPermissions(arrayOf(permission), request)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        val callback = permissionCallbacks.remove(requestCode)
        if (callback != null) {
            callback(grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        backCallback = OnBackInvokedCallback {
            runtime.dispatchBack()
        }.also { callback ->
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
        }
    }

    private fun isDarkAppearance(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun appearanceValue(): Long =
        if (isDarkAppearance()) APPEARANCE_DARK else APPEARANCE_LIGHT

    @Suppress("DEPRECATION")
    private fun applyDefaultSystemBars() {
        val dark = isDarkAppearance()
        val color = if (dark) Color.rgb(15, 23, 42) else Color.WHITE
        window.statusBarColor = color
        window.navigationBarColor = color

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else lightBars,
                lightBars,
            )
        } else {
            val lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            window.decorView.systemUiVisibility = if (dark) {
                window.decorView.systemUiVisibility and lightBars.inv()
            } else {
                window.decorView.systemUiVisibility or lightBars
            }
        }
    }

    private companion object {
        const val EVENT_APP_STATE = 16
        const val EVENT_DIMENSIONS = 17
        const val EVENT_MEMORY_PRESSURE = 18
        const val APP_STATE_ACTIVE = 1
        const val APP_STATE_INACTIVE = 2
        const val APP_STATE_BACKGROUND = 3
        const val MEMORY_PRESSURE_MODERATE = 1
        const val MEMORY_PRESSURE_CRITICAL = 2
        const val APPEARANCE_LIGHT = 1L
        const val APPEARANCE_DARK = 2L
    }
}
