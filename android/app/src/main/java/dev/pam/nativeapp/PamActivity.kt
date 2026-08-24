package dev.pam.nativeapp

import android.app.Activity
import android.app.ActivityManager
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.util.DisplayMetrics
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.widget.FrameLayout
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.render.PamRenderer
import dev.pam.nativeapp.render.PamRootHost
import dev.pam.nativeapp.modules.PamPushNotifications
import dev.pam.nativeapp.modules.PamIncomingShares
import dev.pam.nativeapp.modules.PamDeepLinks

class PamActivity : FragmentActivity() {
    internal lateinit var rootHost: PamRootHost
        private set
    private lateinit var runtime: PamRuntime
    private var hotReload: HotReloadClient? = null
    private var backCallback: OnBackInvokedCallback? = null
    private var suppressBackUntil = 0L
    private lateinit var errors: ErrorOverlay
    private val permissionCallbacks = HashMap<Int, (Boolean) -> Unit>()
    private val activityResultCallbacks = HashMap<Int, (Int, Intent?) -> Unit>()
    private var nextPermissionRequest = 40_000
    private var nextActivityRequest = 50_000
    private var runtimeStarted = false
    private var fullyDrawnReported = false
    private var runtimeEntryPath: String? = null
    private var recoveryAttempts = 0
    private var recoveryRunnable: Runnable? = null
    private lateinit var devTools: PamDevToolsOverlay
    private var devToolsReceiver: BroadcastReceiver? = null
    private val diagnosticsExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var viewportWidth = 0
    private var viewportHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep one deterministic edge-to-edge contract on every supported
        // Android version. Insets are consumed by PAM views, never implicitly
        // by the decor view or an OEM-specific compatibility path.
        WindowCompat.enableEdgeToEdge(window)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    } else {
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    }
            }
        }
        val host = PamRootHost(this).also { rootHost = it }
        errors = ErrorOverlay(this)
        devTools = PamDevToolsOverlay(this)
        val renderer = PamRenderer(this, host) { nodeId, kind, payload ->
            runtime.dispatchEvent(nodeId, kind, payload)
        }
        runtime = PamRuntime(
            context = this,
            renderer = renderer,
            reportError = { message -> handleRuntimeError(message) },
            onFrameCommitted = {
                devTools.update(it)
                errors.clearError()
                recoveryAttempts = 0
                recoveryRunnable?.let(window.decorView::removeCallbacks)
                recoveryRunnable = null
                if (!fullyDrawnReported) {
                    fullyDrawnReported = true
                    reportFullyDrawn()
                }
            },
            onDiagnostic = { diagnostic -> devTools.record(diagnostic) },
        )
        val root = FrameLayout(this)
        root.addView(
            host,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            devTools,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(errors)
        setContentView(root)
        val (windowWidth, windowHeight) = resolvedViewportSize()
        viewportWidth = windowWidth
        viewportHeight = windowHeight
        applyDefaultSystemBars()
        registerBackCallback()
        registerDevTools()
        PamDeepLinks.captureInitial(intent?.dataString)
        PamIncomingShares.captureInitial(this, intent)
        reportNotificationOpen(intent)

        runCatching {
            val entry = AssetInstaller(this).install()
            runtimeEntryPath = entry.absolutePath
            val density = resources.displayMetrics.density
            val widthDp = windowWidth / density
            val heightDp = windowHeight / density
            runtime.start(
                entry,
                widthDp,
                heightDp,
                resources.configuration.fontScale,
                isDarkAppearance(),
            )
            runtimeStarted = true
            rootHost.onStableInsetsChanged = { updateViewportFromWindow() }
            window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateViewportFromWindow()
            }
            if (BuildConfig.DEBUG) {
                hotReload = HotReloadClient(
                    context = this,
                    onReload = { receipt -> runOnUiThread {
                        errors.clearError()
                        runtime.reload(
                            receipt.entryPath,
                            receipt.confirmedAtNanos,
                            receipt.bundleBytes,
                        )
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
            runtime.onHostResume()
            runtime.dispatchLifecycle(EVENT_APP_STATE, APP_STATE_ACTIVE.toString().toByteArray())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PamDeepLinks.reportOpened(intent.dataString)
        PamIncomingShares.reportOpened(this, intent)
        reportNotificationOpen(intent)
    }

    override fun onPause() {
        if (runtimeStarted) {
            runtime.onHostPause()
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
        updateViewportFromWindow(force = true)
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
            if (consumeSuppressedBack()) return true
            runtime.dispatchBack()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    internal fun launchForResult(intent: Intent, callback: (Int, Intent?) -> Unit) {
        val request = nextActivityRequest++
        activityResultCallbacks[request] = callback
        startActivityForResult(intent, request)
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val callback = activityResultCallbacks.remove(requestCode)
        if (callback != null) {
            callback(resultCode, data)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        recoveryRunnable?.let(window.decorView::removeCallbacks)
        recoveryRunnable = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
        hotReload?.close()
        devToolsReceiver?.let(::unregisterReceiver)
        devToolsReceiver = null
        diagnosticsExecutor.shutdownNow()
        runtimeStarted = false
        runtime.close()
        permissionCallbacks.clear()
        super.onDestroy()
    }

    private fun handleRuntimeError(message: String) {
        devTools.record(RuntimeDiagnostic(RuntimeDiagnosticKind.ERROR, message.take(160)))
        if (BuildConfig.DEBUG) {
            errors.showError(message)
            return
        }
        val entry = runtimeEntryPath
        if (entry == null || recoveryAttempts >= MAX_RUNTIME_RECOVERY_ATTEMPTS) {
            errors.showError(message)
            return
        }
        if (recoveryRunnable != null) return
        recoveryAttempts++
        val delay = (250L shl (recoveryAttempts - 1)).coerceAtMost(2_000L)
        recoveryRunnable = Runnable {
            recoveryRunnable = null
            if (!isFinishing && !isDestroyed && runtimeStarted) {
                runCatching { runtime.reload(entry) }
                    .onFailure {
                        handleRuntimeError(it.message ?: "Pam Native recovery failed")
                    }
            }
        }.also { window.decorView.postDelayed(it, delay) }
    }

    @SuppressLint("InlinedApi")
    private fun registerDevTools() {
        if (!BuildConfig.DEBUG) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    DEVTOOLS_ACTION -> {
                        val shown = devTools.toggle()
                        Log.i("PamNativeDevTools", if (shown) "shown" else "hidden")
                    }
                    DIAGNOSTICS_ACTION -> publishDiagnostics(intent)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(DEVTOOLS_ACTION)
            addAction(DIAGNOSTICS_ACTION)
        }
        registerReceiver(
            receiver,
            filter,
            android.Manifest.permission.DUMP,
            null,
            Context.RECEIVER_EXPORTED,
        )
        devToolsReceiver = receiver
    }

    private fun publishDiagnostics(intent: Intent) {
        val requestId = intent.getStringExtra(DIAGNOSTICS_REQUEST_EXTRA).orEmpty()
        if (!requestId.matches(DIAGNOSTICS_REQUEST_PATTERN)) return
        val snapshot = devTools.snapshotJson()
        val directory = cacheDir
        diagnosticsExecutor.execute {
            directory.listFiles { file ->
                file.name.startsWith(DIAGNOSTICS_FILE_PREFIX)
            }?.forEach(File::delete)
            val temporary = File(directory, "$DIAGNOSTICS_FILE_PREFIX$requestId.tmp")
            val destination = File(directory, "$DIAGNOSTICS_FILE_PREFIX$requestId.json")
            runCatching {
                temporary.writeText(snapshot, Charsets.UTF_8)
                check(temporary.renameTo(destination)) { "cannot publish Native diagnostics" }
            }.onFailure {
                temporary.delete()
                Log.w("PamNativeDevTools", "Cannot publish diagnostics", it)
            }
        }
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

    fun requestPamPermissions(permissions: Array<String>, callback: (Boolean) -> Unit) {
        if (permissions.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            callback(true)
            return
        }
        val request = nextPermissionRequest++
        if (nextPermissionRequest > 60_000) nextPermissionRequest = 40_000
        permissionCallbacks[request] = callback
        requestPermissions(permissions, request)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        val callback = permissionCallbacks.remove(requestCode)
        if (callback != null) {
            callback(grantResults.any { it == PackageManager.PERMISSION_GRANTED })
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        backCallback = if (Build.VERSION.SDK_INT >= 34) {
            object : OnBackAnimationCallback {
                private var interactive = false

                override fun onBackStarted(backEvent: BackEvent) {
                    interactive = rootHost.startPredictiveBack()
                }

                override fun onBackProgressed(backEvent: BackEvent) {
                    if (interactive) rootHost.updatePredictiveBack(backEvent.progress)
                }

                override fun onBackCancelled() {
                    if (interactive) rootHost.cancelPredictiveBack()
                    interactive = false
                }

                override fun onBackInvoked() {
                    if (consumeSuppressedBack()) {
                        if (interactive) rootHost.cancelPredictiveBack()
                        interactive = false
                        return
                    }
                    if (interactive) rootHost.commitPredictiveBack()
                    interactive = false
                    runtime.dispatchBack()
                }
            }
        } else {
            OnBackInvokedCallback {
                if (!consumeSuppressedBack()) runtime.dispatchBack()
            }
        }.also { callback ->
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback,
            )
        }
    }

    internal fun suppressNextPamBack() {
        suppressBackUntil = SystemClock.uptimeMillis() + BACK_SUPPRESSION_WINDOW_MS
    }

    private fun consumeSuppressedBack(): Boolean {
        if (SystemClock.uptimeMillis() > suppressBackUntil) return false
        suppressBackUntil = 0L
        return true
    }

    private fun reportNotificationOpen(intent: Intent?) {
        if (intent?.getBooleanExtra("pam.notification.opened", false) != true) return
        PamPushNotifications.reportOpened(
            id = intent.getStringExtra("pam.notification.id").orEmpty(),
            title = intent.getStringExtra("pam.notification.title").orEmpty(),
            body = intent.getStringExtra("pam.notification.body").orEmpty(),
            dataJson = intent.getStringExtra("pam.notification.data").orEmpty().ifEmpty { "{}" },
            deepLink = intent.getStringExtra("pam.notification.deepLink").orEmpty(),
        )
        intent.removeExtra("pam.notification.opened")
    }

    private fun isDarkAppearance(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION")
    private fun fullWindowSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = window.windowManager.currentWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun resolvedViewportSize(): Pair<Int, Int> {
        val (width, height) = fullWindowSize()
        return width to height
    }

    private fun updateViewportFromWindow(force: Boolean = false) {
        if (!runtimeStarted) return
        val (width, height) = resolvedViewportSize()
        if (!force && width == viewportWidth && height == viewportHeight) return
        viewportWidth = width
        viewportHeight = height
        val density = resources.displayMetrics.density
        val widthDp = width / density
        val heightDp = height / density
        val insets = ViewCompat.getRootWindowInsets(window.decorView)?.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val configuration = resources.configuration
        val deviceType = when {
            configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
            configuration.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "phone"
        }
        val inputMode = when {
            deviceType == "tv" -> "remote"
            configuration.keyboard != Configuration.KEYBOARD_NOKEYS -> "keyboard"
            configuration.touchscreen == Configuration.TOUCHSCREEN_NOTOUCH -> "mouse"
            else -> "touch"
        }
        val pointer = if (inputMode == "touch" || inputMode == "remote") "coarse" else "fine"
        @Suppress("DEPRECATION")
        val refreshRate = windowManager.defaultDisplay.refreshRate.coerceAtLeast(1f)
        val reducedMotion = Settings.Global.getFloat(
            contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
        val memoryClass = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        val performanceTier = when {
            memoryClass >= 512 && refreshRate >= 90f -> 3L
            memoryClass >= 256 -> 2L
            else -> 1L
        }
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
                    "fontScale" to WireValue.Decimal(configuration.fontScale.toDouble()),
                    "safeAreaTop" to WireValue.Decimal(((insets?.top ?: 0) / density).toDouble()),
                    "safeAreaRight" to WireValue.Decimal(((insets?.right ?: 0) / density).toDouble()),
                    "safeAreaBottom" to WireValue.Decimal(((insets?.bottom ?: 0) / density).toDouble()),
                    "safeAreaLeft" to WireValue.Decimal(((insets?.left ?: 0) / density).toDouble()),
                    "refreshRate" to WireValue.Decimal(refreshRate.toDouble()),
                    "reducedMotion" to WireValue.Flag(reducedMotion),
                    "deviceType" to WireValue.Text(deviceType),
                    "pointer" to WireValue.Text(pointer),
                    "inputMode" to WireValue.Text(inputMode),
                    "dynamicRange" to WireValue.Text("standard"),
                    "displayMode" to WireValue.Text("standalone"),
                    "foldPosture" to WireValue.Text("flat"),
                    "memoryClass" to WireValue.Decimal(memoryClass.toDouble()),
                    "performanceTier" to WireValue.Decimal(performanceTier.toDouble()),
                ),
            ),
        )
    }

    private fun appearanceValue(): Long =
        if (isDarkAppearance()) APPEARANCE_DARK else APPEARANCE_LIGHT

    @Suppress("DEPRECATION")
    private fun applyDefaultSystemBars() {
        val dark = isDarkAppearance()
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

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
        const val DEVTOOLS_ACTION = "dev.pam.nativeapp.action.TOGGLE_DEVTOOLS"
        const val DIAGNOSTICS_ACTION = "dev.pam.nativeapp.action.CAPTURE_DIAGNOSTICS"
        const val DIAGNOSTICS_REQUEST_EXTRA = "requestId"
        const val DIAGNOSTICS_FILE_PREFIX = "pam-diagnostics-"
        val DIAGNOSTICS_REQUEST_PATTERN = Regex("[a-f0-9]{32}")
        const val MEMORY_PRESSURE_CRITICAL = 2
        const val APPEARANCE_LIGHT = 1L
        const val APPEARANCE_DARK = 2L
        const val MAX_RUNTIME_RECOVERY_ATTEMPTS = 3
        const val BACK_SUPPRESSION_WINDOW_MS = 250L
    }
}
