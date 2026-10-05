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
import android.graphics.drawable.ColorDrawable
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
    internal lateinit var errors: ErrorOverlay
        private set
    private val permissionCallbacks = HashMap<Int, (Boolean) -> Unit>()
    private val activityResultCallbacks = HashMap<Int, (Int, Intent?) -> Unit>()
    private var nextPermissionRequest = 40_000
    private var nextActivityRequest = 50_000
    private var runtimeStarted = false
    private var fullyDrawnReported = false

    /** Uptime of this Activity's first committed native frame (0 = none yet). */
    @Volatile
    internal var firstFrameUptimeMillis = 0L
        private set
    private var runtimeEntryPath: String? = null
    private var recoveryAttempts = 0
    private var recoveryRunnable: Runnable? = null
    private lateinit var devTools: PamDevToolsOverlay
    private var devToolsReceiver: BroadcastReceiver? = null
    private val diagnosticsExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var viewportWidth = 0
    private var viewportHeight = 0
    private var viewportUpdateScheduled = false
    private var viewportUpdateReplayRequested = false
    private var forceScheduledViewportUpdate = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        // Below Android 12 the persisted appearance must reach the activity
        // configuration before resources and the DayNight theme are created.
        PamAppearance.overrideConfiguration(newBase)?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PamAppearance.applyPlatformNightMode(this)
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
        // Paint the window in the effective scheme before any content exists,
        // even when the system theme and the persisted override disagree, and
        // export the same scheme to PHP before its first frame.
        applyDefaultSystemBars()
        PamAppearance.exportEnvironment(this)
        val host = PamRootHost(this).also { rootHost = it }
        errors = ErrorOverlay(
            context = this,
            developerMode = ErrorOverlay.developerMode(this, BuildConfig.DEBUG),
            safeArea = ::currentSafeAreaInsets,
            dark = ::isDarkAppearance,
            onReload = ::reloadAfterError,
            onExit = ::finish,
        )
        devTools = PamDevToolsOverlay(this)
        val renderer = PamRenderer(this, host) { nodeId, kind, payload ->
            runtime.dispatchEvent(nodeId, kind, payload)
        }
        val onFrameCommitted: (RuntimeFrameMetrics) -> Unit = {
            devTools.update(it)
            errors.onFrameCommitted()
            recoveryAttempts = 0
            recoveryRunnable?.let(window.decorView::removeCallbacks)
            recoveryRunnable = null
            if (firstFrameUptimeMillis == 0L) {
                firstFrameUptimeMillis = SystemClock.uptimeMillis()
            }
            if (!fullyDrawnReported) {
                fullyDrawnReported = true
                reportFullyDrawn()
            }
        }
        // Embedded PHP lives as long as the process. When the previous
        // Activity finished (Back at the root, system recreation) while the
        // process survived, re-attach to that runtime and remount its tree
        // instead of booting PHP a second time in the same process.
        val retained = PamRuntimeHost.runtime?.takeIf(PamRuntime::isRunning)
        if (retained != null) {
            runtime = retained
            retained.attach(
                context = this,
                renderer = renderer,
                reportError = { message -> handleRuntimeError(message) },
                onFrameCommitted = onFrameCommitted,
                onDiagnostic = { diagnostic -> devTools.record(diagnostic) },
            )
        } else {
            runtime = PamRuntime(
                context = this,
                renderer = renderer,
                reportError = { message -> handleRuntimeError(message) },
                onFrameCommitted = onFrameCommitted,
                onDiagnostic = { diagnostic -> devTools.record(diagnostic) },
            )
        }
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
        if (retained != null) {
            // PHP already consumed its launch intent; this one is a new open.
            intent?.dataString?.let(PamDeepLinks::reportOpened)
            intent?.let { PamIncomingShares.reportOpened(this, it) }
            reportNotificationOpen(intent)
            runtimeEntryPath = PamRuntimeHost.entryPath
            runtimeStarted = true
            bindRuntimeSurface()
            // Window metrics, insets or font scale may differ from the
            // surface that rendered last; relayout once the window settles.
            scheduleViewportUpdate(force = true)
            return
        }
        PamDeepLinks.captureInitial(intent?.dataString)
        PamIncomingShares.captureInitial(this, intent)
        reportNotificationOpen(intent)

        // Bundle extraction/verification and OTA resolution touch the disk
        // (a full copy + hash on first launch); keep them off the main thread
        // so the first frame and input are never blocked (ANR on cold start).
        Thread(
            {
                val resolved = runCatching {
                    val embeddedEntry = AssetInstaller(this).install()
                    ActiveUpdateInstaller(this).resolve(embeddedEntry)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    resolved.mapCatching { entry -> startRuntime(entry) }.onFailure {
                        handleRuntimeError(it.message ?: "Pam Native failed to start")
                    }
                }
            },
            "pam-asset-install",
        ).start()
    }

    private fun startRuntime(entry: java.io.File) {
        val windowWidth = viewportWidth
        val windowHeight = viewportHeight
        run {
            runtimeEntryPath = entry.absolutePath
            val density = resources.displayMetrics.density
            val widthDp = windowWidth / density
            val heightDp = windowHeight / density
            exportBootMetrics(widthDp, heightDp)
            val safeArea = currentSafeAreaInsets()
            dispatchedSafeArea = safeArea
            runtime.start(
                entry,
                widthDp,
                heightDp,
                resources.configuration.fontScale,
                isDarkAppearance(),
                floatArrayOf(
                    safeArea.left / density,
                    safeArea.top / density,
                    safeArea.right / density,
                    safeArea.bottom / density,
                ),
            )
            PamRuntimeHost.runtime = runtime
            PamRuntimeHost.entryPath = entry.absolutePath
            runtimeStarted = true
            bindRuntimeSurface()
        }
    }

    private fun bindRuntimeSurface() {
        run {
            rootHost.onStableInsetsChanged = { scheduleViewportUpdate() }
            rootHost.onImeInsetChanged = { inset ->
                runtime.updateKeyboardInset(inset / resources.displayMetrics.density)
            }
            rootHost.windowFocused = ::hasWindowFocus
            // A re-attached runtime may still carry the previous host's IME.
            runtime.updateKeyboardInset(rootHost.imeBottomInset / resources.displayMetrics.density)
            // The runtime may start after the first window layout (assets are
            // installed off the UI thread); reconcile insets and metrics now.
            scheduleViewportUpdate(force = true)
            window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                scheduleViewportUpdate()
            }
            if (BuildConfig.DEBUG) {
                hotReload = HotReloadClient(
                    context = this,
                    onReload = { receipt -> runOnUiThread {
                        errors.clearError()
                        runtimeEntryPath = receipt.entryPath
                        runtime.reload(
                            receipt.entryPath,
                            receipt.confirmedAtNanos,
                            receipt.bundleBytes,
                        )
                    } },
                    onError = { message ->
                        runOnUiThread {
                            errors.report(
                                RuntimeErrorReport.native(message, fatal = false, phase = "hot-reload"),
                            )
                        }
                    },
                ).also { it.start() }
            }
        }
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            runtime.onHostResume()
        }
    }

    override fun onResume() {
        super.onResume()
        dev.pam.nativeapp.modules.PamActiveRoute.setForeground(true)
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
        dev.pam.nativeapp.modules.PamActiveRoute.setForeground(false)
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
        runtime.invalidateSystemBars()
        scheduleViewportUpdate(force = true)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
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

    // API 33+ is handled by registerBackCallback(), including predictive
    // progress on API 34+. These overrides are the legacy keyboard/button
    // path retained exclusively for older supported Android releases.
    @SuppressLint("GestureBackNavigation")
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (errors.consumeBack()) return true
            if (consumeSuppressedBack()) return true
            if (runtime.consumePresentedModalBack()) return true
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                runtime.dispatchBack()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (errors.consumeBack()) return
        if (!runtimeStarted) {
            super.onBackPressed()
            return
        }
        if (consumeSuppressedBack()) return
        if (runtime.consumePresentedModalBack()) return
        runtime.dispatchBack()
    }

    @Suppress("DEPRECATION")
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
        if (runtimeStarted && PamRuntimeHost.runtime === runtime) {
            // Keep PHP (and its state) alive for the next Activity; only this
            // surface goes away. Shutting embedded PHP down in a live process
            // is not restartable and crashed or hung the relaunch.
            runtime.detach(this)
        } else {
            runtime.close()
        }
        runtimeStarted = false
        permissionCallbacks.clear()
        super.onDestroy()
    }

    /**
     * Debug builds (or `devErrorOverlay: "always"`) queue every error in the
     * LogBox-style overlay. Production never shows stacks: non-fatal errors
     * are logged only (PHP App::onError() listeners already received them);
     * fatal ones retry with backoff and then show the friendly fallback.
     */
    private fun handleRuntimeError(message: String) {
        val report = RuntimeErrorReport.parse(message)
        devTools.record(RuntimeDiagnostic(RuntimeDiagnosticKind.ERROR, "${report.shortType}: ${report.message}".take(160)))
        Log.e(ERROR_TAG, report.copyText().take(4_000))
        if (errors.developerMode) {
            errors.report(report)
            return
        }
        if (!report.fatal) return
        val entry = runtimeEntryPath
        if (entry == null || recoveryAttempts >= MAX_RUNTIME_RECOVERY_ATTEMPTS) {
            errors.showFallback()
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

    /** Reload / "Try again" from the error overlay: a fresh PHP runtime on the active entry. */
    private fun reloadAfterError() {
        recoveryRunnable?.let(window.decorView::removeCallbacks)
        recoveryRunnable = null
        recoveryAttempts = 0
        val entry = runtimeEntryPath
        if (!runtimeStarted || entry == null) {
            recreate()
            return
        }
        runCatching { runtime.reload(entry) }
            .onFailure { handleRuntimeError(it.message ?: "Pam Native reload failed") }
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
                    interactive = if (errors.isBlocking || runtime.hasPresentedModal()) {
                        false
                    } else {
                        rootHost.startPredictiveBack()
                    }
                }

                override fun onBackProgressed(backEvent: BackEvent) {
                    if (interactive) rootHost.updatePredictiveBack(backEvent.progress)
                }

                override fun onBackCancelled() {
                    if (interactive) rootHost.cancelPredictiveBack()
                    interactive = false
                }

                override fun onBackInvoked() {
                    if (errors.consumeBack()) {
                        if (interactive) rootHost.cancelPredictiveBack()
                        interactive = false
                        return
                    }
                    if (consumeSuppressedBack()) {
                        if (interactive) rootHost.cancelPredictiveBack()
                        interactive = false
                        return
                    }
                    if (runtime.consumePresentedModalBack()) {
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
                if (
                    !errors.consumeBack()
                    && !consumeSuppressedBack()
                    && !runtime.consumePresentedModalBack()
                ) {
                    runtime.dispatchBack()
                }
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

    private fun isDarkAppearance(): Boolean = PamAppearance.isDark(this)

    /**
     * Applies a preference chosen at runtime without recreating the activity:
     * the window, system bars and PHP metrics restyle in place, and Android
     * 12+ delivers the per-app night mode as a handled `uiMode` change.
     */
    internal fun setAppearanceMode(mode: Int): Boolean {
        if (!PamAppearance.persist(this, mode)) return false
        PamAppearance.applyPlatformNightMode(this, mode)
        applyDefaultSystemBars()
        if (runtimeStarted) {
            runtime.invalidateSystemBars()
            scheduleViewportUpdate(force = true)
        }
        return true
    }

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
        val (windowWidth, windowHeight) = fullWindowSize()
        return resolvedViewportSize(
            laidOutWidth = window.decorView.width,
            laidOutHeight = window.decorView.height,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
        )
    }

    private fun scheduleViewportUpdate(force: Boolean = false) {
        forceScheduledViewportUpdate = forceScheduledViewportUpdate || force
        if (viewportUpdateScheduled) {
            // A configuration callback can enqueue a pre-layout read before
            // decorView receives its new orientation bounds. Do not discard a
            // later onLayout signal just because that stale read is pending.
            viewportUpdateReplayRequested = true
            return
        }
        viewportUpdateScheduled = true
        window.decorView.post {
            viewportUpdateScheduled = false
            val shouldForce = forceScheduledViewportUpdate
            forceScheduledViewportUpdate = false
            updateViewportFromWindow(force = shouldForce)
            if (viewportUpdateReplayRequested) {
                viewportUpdateReplayRequested = false
                scheduleViewportUpdate()
            }
        }
    }

    private fun updateViewportFromWindow(force: Boolean = false) {
        if (!runtimeStarted) return
        val (width, height) = resolvedViewportSize()
        val insets = currentSafeAreaInsets()
        if (!force && width == viewportWidth && height == viewportHeight && insets == dispatchedSafeArea) return
        viewportWidth = width
        viewportHeight = height
        dispatchedSafeArea = insets
        val density = resources.displayMetrics.density
        val widthDp = width / density
        val heightDp = height / density
        runtime.updateSafeArea(
            insets.left / density,
            insets.top / density,
            insets.right / density,
            insets.bottom / density,
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
        val appearanceMode = PamAppearance.storedMode(this)
        PamAppearance.exportEnvironment(this, appearanceMode)
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
                    "appearanceMode" to WireValue.Integer(appearanceMode.toLong()),
                    "systemAppearance" to WireValue.Integer(
                        PamAppearance.systemAppearance(this, appearanceMode).toLong(),
                    ),
                    "fontScale" to WireValue.Decimal(configuration.fontScale.toDouble()),
                    "safeAreaTop" to WireValue.Decimal((insets.top / density).toDouble()),
                    "safeAreaRight" to WireValue.Decimal((insets.right / density).toDouble()),
                    "safeAreaBottom" to WireValue.Decimal((insets.bottom / density).toDouble()),
                    "safeAreaLeft" to WireValue.Decimal((insets.left / density).toDouble()),
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

    private var dispatchedSafeArea: androidx.core.graphics.Insets? = null

    /**
     * Window safe area in pixels (system bars + cutout). When Android reports
     * no bottom inset (some gesture/OEM configurations) the configured
     * `safeArea.bottomFallback` dp is used, like React Native apps'
     * `getBottomSafeInset()`.
     */
    private fun currentSafeAreaInsets(): androidx.core.graphics.Insets {
        val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        val reported = ViewCompat.getRootWindowInsets(window.decorView)
            ?.getInsetsIgnoringVisibility(types)
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            androidx.core.graphics.Insets.toCompatInsets(
                windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout(),
                ),
            )
        } else {
            androidx.core.graphics.Insets.NONE
        }
        val fallbackDp = resources.getInteger(R.integer.pam_safe_area_bottom_fallback_dp)
        val bottom = if (reported.bottom == 0 && fallbackDp > 0) {
            (fallbackDp * resources.displayMetrics.density + 0.5f).toInt()
        } else {
            reported.bottom
        }
        return retainedWhileUnfocused(
            androidx.core.graphics.Insets.of(reported.left, reported.top, reported.right, bottom),
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && runtimeStarted) scheduleViewportUpdate()
    }

    private var safeAreaWindowSize: Pair<Int, Int>? = null
    private var lastFocusedSafeArea: androidx.core.graphics.Insets? = null

    /**
     * A dialog/sheet window (PAM Modal, BottomSheet, permission prompt) that
     * takes focus can make Android report this window's system-bar insets as
     * zero while the bars are still drawn over it. The base window's safe
     * area must not change because another window opened: keep the previous
     * edges for the same window size until focus returns.
     */
    private fun retainedWhileUnfocused(
        reported: androidx.core.graphics.Insets,
    ): androidx.core.graphics.Insets {
        val size = window.decorView.width to window.decorView.height
        val previous = lastFocusedSafeArea
        if (hasWindowFocus() || previous == null || safeAreaWindowSize != size) {
            safeAreaWindowSize = size
            lastFocusedSafeArea = reported
            return reported
        }
        return retainedSafeAreaInsets(previous, reported)
    }

    /** Metrics exported to PHP before its first render (PAM_BOOT_METRICS). */
    private fun exportBootMetrics(widthDp: Float, heightDp: Float) {
        val density = resources.displayMetrics.density
        val insets = currentSafeAreaInsets()
        val json = org.json.JSONObject()
            .put("width", widthDp.toDouble())
            .put("height", heightDp.toDouble())
            .put("density", density.toDouble())
            .put("fontScale", resources.configuration.fontScale.toDouble())
            .put("safeAreaTop", (insets.top / density).toDouble())
            .put("safeAreaRight", (insets.right / density).toDouble())
            .put("safeAreaBottom", (insets.bottom / density).toDouble())
            .put("safeAreaLeft", (insets.left / density).toDouble())
        runCatching { android.system.Os.setenv("PAM_BOOT_METRICS", json.toString(), true) }
    }

    private fun appearanceValue(): Long =
        if (isDarkAppearance()) APPEARANCE_DARK else APPEARANCE_LIGHT

    @Suppress("DEPRECATION")
    private fun applyDefaultSystemBars() {
        val dark = isDarkAppearance()
        window.setBackgroundDrawable(
            ColorDrawable(PamAppearance.color(this, R.color.pam_window_background, dark)),
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        val lightStatus = PamAppearance.bool(this, R.bool.pam_light_status_bar, dark)
        val lightNavigation = PamAppearance.bool(this, R.bool.pam_light_navigation_bar, dark)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lightStatusMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            val lightNavigationMask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                (if (lightStatus) lightStatusMask else 0) or
                    (if (lightNavigation) lightNavigationMask else 0),
                lightStatusMask or lightNavigationMask,
            )
        } else {
            var flags = window.decorView.systemUiVisibility
            flags = if (lightStatus) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            flags = if (lightNavigation) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            window.decorView.systemUiVisibility = flags
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
        const val ERROR_TAG = "PamNativeErrors"
        const val BACK_SUPPRESSION_WINDOW_MS = 1_000L
    }
}
