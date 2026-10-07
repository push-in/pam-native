package dev.pam.nativeapp

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.view.Choreographer
import dev.pam.nativeapp.modules.ModuleCompletion
import dev.pam.nativeapp.modules.NativeModuleRegistry
import dev.pam.nativeapp.protocol.BatchDecoder
import dev.pam.nativeapp.protocol.Mutation
import dev.pam.nativeapp.protocol.WireMap
import dev.pam.nativeapp.protocol.WireValue
import dev.pam.nativeapp.render.PamRenderer
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class PamRuntime(
    context: Context,
    renderer: PamRenderer,
    reportError: (String) -> Unit,
    onFrameCommitted: (RuntimeFrameMetrics) -> Unit = {},
    onDiagnostic: (RuntimeDiagnostic) -> Unit = {},
    installModules: Boolean = true,
) : AutoCloseable {
    // The PHP runtime is process scoped (embedded PHP cannot be restarted in
    // a live process), while the surface (Activity, renderer, modules bound
    // to that Activity) can be recreated. attach() rebinds every one of these.
    //
    // The runtime outlives its Activity (PamRuntimeHost keeps it for the
    // process), so it holds the process-wide application context strongly and
    // the current surface context only weakly: a destroyed Activity is never
    // retained through the runtime.
    private val applicationContext: Context = context.applicationContext ?: context
    private var surfaceContext = WeakReference(context)
    private val context: Context
        get() = surfaceContext.get() ?: applicationContext
    private var renderer: PamRenderer = renderer
    private var reportError: (String) -> Unit = reportError
    private var onFrameCommitted: (RuntimeFrameMetrics) -> Unit = onFrameCommitted
    private var onDiagnostic: (RuntimeDiagnostic) -> Unit = onDiagnostic
    private val main = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    @Volatile
    private var installedModules: NativeModuleRegistry? = null
    private val modulesInstalled = java.util.concurrent.CountDownLatch(1)

    /**
     * Native modules of the bound surface. A cold start boots PHP first and
     * builds them on the UI thread meanwhile ([installModules]); a module call
     * from the PHP thread that arrives earlier is queued (calls complete
     * asynchronously), so PHP keeps rendering instead of blocking on them.
     */
    private var modules: NativeModuleRegistry
        get() = installedModules ?: run {
            modulesInstalled.await()
            checkNotNull(installedModules)
        }
        set(value) {
            installedModules = value
        }

    /**
     * True between a surface change (detach/attach) and the arrival of the
     * engine's remount batch: every batch published before it describes the
     * previous surface and is dropped; the remount batch mounts the whole
     * retained tree on the new renderer.
     */
    private var awaitingRemount = false
    private var attachedSurface = true
    private val textTypefaces = dev.pam.nativeapp.render.NativeTypefaceLoader.shared(context)
    private val closed = AtomicBoolean()
    private val handleLock = Any()
    private val ownedBatchHandles = ConcurrentHashMap.newKeySet<Long>()
    private val pendingBatches = ArrayDeque<PendingBatch>()
    private val pendingImmediateEvents = ArrayDeque<PendingEvent>()
    private val pendingEvents = LinkedHashMap<EventIdentity, ByteArray>()
    private val hotReloadLatency = HotReloadLatency()
    private val reloadReleases = mutableMapOf<String, () -> Unit>()
    private var frameScheduled = false
    private var readyForEvents = false

    /**
     * Nothing is on screen for this surface yet (cold start or a re-attached
     * Activity): its first batch mounts as soon as it arrives instead of
     * waiting for the next vsync, so the window's next traversal draws it.
     */
    private var surfaceAwaitingFirstFrame = true
    private val frameCallback = Choreographer.FrameCallback {
        frameScheduled = false
        flushEvents()
        if (deferCommitPastFrame()) return@FrameCallback
        flushBatches()
    }

    /**
     * A batch that builds something (a page of older rows, a reel's chrome:
     * tens of mutations, 5-15 ms), or any batch while a list scrolls, is
     * committed right after this frame instead of inside it. The frame keeps
     * only what was already running (the scroll, a playing video), the commit
     * runs in the idle time before the next vsync while the render thread
     * draws, and dirty lists bind their new rows there too; the next
     * traversal shows the result. Small batches (a keystroke echo, a
     * toggle) still land in the frame that asked for them.
     */
    private var commitAfterFrameScheduled = false
    private val commitAfterFrame = Runnable {
        commitAfterFrameScheduled = false
        flushBatches()
        if (!closed.get()) renderer.layoutDirtyListsNow()
    }

    private fun deferCommitPastFrame(): Boolean {
        if (commitAfterFrameScheduled) return true
        if (pendingBatches.isEmpty() || surfaceAwaitingFirstFrame || closed.get()) return false
        if (pendingBatches.any(PendingBatch::remount)) return false
        if (pendingBatches.sumOf { it.mutations.size } < HEAVY_COMMIT_MUTATIONS && !renderer.isListScrolling()) {
            return false
        }
        commitAfterFrameScheduled = true
        val message = android.os.Message.obtain(main, commitAfterFrame)
        message.isAsynchronous = true
        main.sendMessageAtFrontOfQueue(message)
        return true
    }

    @Volatile
    private var handle = 0L

    init {
        bindRenderer(renderer)
        if (installModules) installModules()
    }

    /** Module calls made before [installModules], replayed in order once it ran. */
    private val callsAwaitingModules = ArrayList<() -> Unit>()

    private fun deferUntilModulesInstalled(call: () -> Unit): Boolean {
        if (installedModules != null) return false
        synchronized(callsAwaitingModules) {
            if (installedModules != null) return false
            callsAwaitingModules += call
        }
        return true
    }

    /** Builds the surface's native modules; idempotent, UI thread. */
    fun installModules() {
        if (installedModules != null) return
        val registry = NativeModuleRegistry(context)
        val waiting = synchronized(callsAwaitingModules) {
            installedModules = registry
            callsAwaitingModules.toList().also { callsAwaitingModules.clear() }
        }
        modulesInstalled.countDown()
        waiting.forEach { it() }
    }

    private fun bindRenderer(target: PamRenderer) {
        target.onNativeChildVisibility = { owner, child, visible ->
            synchronized(handleLock) {
                if (!closed.get() && handle != 0L) {
                    nativeSetChildVisibility(handle, owner, child, visible)
                }
            }
        }
        target.onSurfaceKeyboardInset = ::updateSurfaceKeyboardInset
    }

    val isRunning: Boolean
        get() = !closed.get() && handle != 0L

    /**
     * Rebinds a live runtime to a new host surface (a recreated Activity) and
     * asks the engine to mount the retained tree on [renderer] from scratch.
     * PHP keeps its state; nothing is re-executed.
     */
    fun attach(
        context: Context,
        renderer: PamRenderer,
        reportError: (String) -> Unit,
        onFrameCommitted: (RuntimeFrameMetrics) -> Unit = {},
        onDiagnostic: (RuntimeDiagnostic) -> Unit = {},
    ) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!closed.get()) { "Pam Runtime is closed" }
        val previousRenderer = this.renderer
        val previousModules = installedModules
        surfaceContext = WeakReference(context)
        this.reportError = reportError
        this.onFrameCommitted = onFrameCommitted
        this.onDiagnostic = onDiagnostic
        if (previousRenderer !== renderer) {
            previousRenderer.onNativeChildVisibility = null
            previousRenderer.onSurfaceKeyboardInset = null
            if (attachedSurface) runCatching { previousRenderer.close() }
            renderer.engineManagedSafeArea = previousRenderer.engineManagedSafeArea
            this.renderer = renderer
            bindRenderer(renderer)
        }
        if (previousModules == null) {
            installModules()
        } else if (previousModules.boundContext !== context) {
            modules = NativeModuleRegistry(context)
            previousModules.retire()
        }
        attachedSurface = true
        surfaceAwaitingFirstFrame = true
        requestRemount()
        onDiagnostic(RuntimeDiagnostic(RuntimeDiagnosticKind.LIFECYCLE, "surface attached"))
    }

    /**
     * The host surface is going away while the process (and PHP) stays alive.
     * Views are released; PHP keeps running and its later frames are folded
     * into the remount performed by the next [attach].
     */
    fun detach(context: Context) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (surfaceContext.get() !== context || !attachedSurface) return
        attachedSurface = false
        surfaceContext.clear()
        awaitingRemount = true
        choreographer.removeFrameCallback(frameCallback)
        main.removeCallbacks(commitAfterFrame)
        commitAfterFrameScheduled = false
        frameScheduled = false
        while (pendingBatches.isNotEmpty()) {
            releaseBatch(pendingBatches.removeFirst().handle)
        }
        pendingEvents.clear()
        renderer.onNativeChildVisibility = null
        runCatching { renderer.close() }
        reportError = {}
        onFrameCommitted = {}
        onDiagnostic = {}
    }

    /**
     * The mounted tree diverged from the engine's retained tree (a batch was
     * rejected or only partly mounted). Drops queued patches and replays the
     * retained tree onto a cleared renderer. A pending remount already does
     * that; a detached surface remounts on its next [attach].
     */
    private fun resynchronize() {
        if (closed.get() || !attachedSurface || awaitingRemount) return
        requestRemount()
    }

    private fun requestRemount() {
        awaitingRemount = true
        while (pendingBatches.isNotEmpty()) {
            releaseBatch(pendingBatches.removeFirst().handle)
        }
        synchronized(handleLock) {
            val active = handle
            if (active != 0L) {
                nativeRemount(active)
            } else {
                awaitingRemount = false
            }
        }
    }

    fun start(
        entry: File,
        widthDp: Float,
        heightDp: Float,
        textScale: Float,
        darkAppearance: Boolean,
        safeArea: FloatArray = FloatArray(4),
    ) {
        synchronized(handleLock) {
            check(!closed.get()) { "Pam Runtime is closed" }
            layoutViewport = floatArrayOf(widthDp, heightDp, textScale)
            renderer.engineManagedSafeArea = true
            check(handle == 0L) { "Pam Runtime is already running" }
            PamStartup.loadNativeLibrary()
            // Release bundles live in content-addressed directories: opcache
            // never stats their includes (debug builds hot reload in place).
            runCatching {
                android.system.Os.setenv(
                    "PAM_NATIVE_IMMUTABLE_SOURCES",
                    if (BuildConfig.DEBUG) "0" else "1",
                    true,
                )
            }
            val stateDirectory = File(context.filesDir, "pam/state").apply {
                check(mkdirs() || isDirectory) { "Cannot create Pam Native state directory" }
            }
            handle = nativeStart(
                entry.absolutePath,
                stateDirectory.absolutePath,
                widthDp,
                heightDp,
                textScale,
                darkAppearance,
                safeArea.getOrElse(0) { 0f },
                safeArea.getOrElse(1) { 0f },
                safeArea.getOrElse(2) { 0f },
                safeArea.getOrElse(3) { 0f },
                dev.pam.nativeapp.render.modalWindowSurfacePolicy(context),
            )
            check(handle != 0L) { "Pam Runtime failed to start" }
            val display = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            nativeSetRefreshRate(handle, display?.refreshRate?.toDouble() ?: 60.0)
        }
    }

    /**
     * Window safe-area insets (dp). The engine then lays out every
     * SafeAreaView from the window edges its frame touches, at any nesting.
     */
    fun updateSafeArea(left: Float, top: Float, right: Float, bottom: Float) {
        synchronized(handleLock) {
            val active = handle
            if (active != 0L) {
                renderer.engineManagedSafeArea = true
                nativeSetSafeAreaInsets(active, left, top, right, bottom)
            }
        }
        renderer.onEngineSafeAreaChanged()
    }

    fun updateViewport(
        widthDp: Float,
        heightDp: Float,
        textScale: Float,
        darkAppearance: Boolean,
    ) {
        synchronized(handleLock) {
            layoutViewport = floatArrayOf(widthDp, heightDp, textScale)
            val active = handle
            if (active != 0L) {
                nativeRelayout(active, widthDp, heightDp, textScale, darkAppearance)
            }
        }
    }

    private var layoutViewport = floatArrayOf(0f, 0f, 1f)

    /**
     * Visible IME height in dp (0 when hidden). The engine lays out a
     * trailing panning KeyboardAvoidingView directly above the keyboard and
     * shrinks its flexible siblings, then relayouts synchronously so native
     * views and engine frames move together.
     */
    fun updateKeyboardInset(bottomDp: Float) {
        synchronized(handleLock) {
            val active = handle
            val (width, height, textScale) = layoutViewport.let { Triple(it[0], it[1], it[2]) }
            if (active != 0L && width > 0f && height > 0f) {
                nativeSetKeyboardInset(active, bottomDp.coerceAtLeast(0f), width, height, textScale)
            }
        }
    }

    /**
     * Visible IME height in dp over the Modal/BottomSheet window of node
     * [surface] (0 when hidden). The engine lays that modal's resize/padding
     * KeyboardAvoidingViews out above it and relayouts synchronously, so
     * every IME animation frame moves the content with the keyboard.
     */
    fun updateSurfaceKeyboardInset(surface: Long, bottomDp: Float) {
        synchronized(handleLock) {
            val active = handle
            val (width, height, textScale) = layoutViewport.let { Triple(it[0], it[1], it[2]) }
            if (active != 0L && surface > 0L && width > 0f && height > 0f) {
                nativeSetSurfaceKeyboardInset(
                    active,
                    surface,
                    bottomDp.coerceAtLeast(0f),
                    width,
                    height,
                    textScale,
                )
            }
        }
    }

    fun dispatchLifecycle(kind: Int, payload: ByteArray) {
        onDiagnostic(RuntimeDiagnostic(RuntimeDiagnosticKind.LIFECYCLE, "event $kind"))
        dispatchEvent(0, kind, payload)
    }

    fun trimMemory(critical: Boolean) {
        renderer.trimMemory(critical)
    }

    fun invalidateSystemBars() {
        if (attachedSurface) renderer.invalidateSystemBars()
    }

    fun onHostPause() {
        renderer.onHostPause()
    }

    fun onHostResume() {
        renderer.onHostResume()
    }

    fun dispatchEvent(nodeId: Long, kind: Int, payload: ByteArray = ByteArray(0)) {
        if (payload.size > MAX_PAYLOAD_BYTES) return
        if (kind >= 42) {
            onDiagnostic(RuntimeDiagnostic(RuntimeDiagnosticKind.EVENT, "node $nodeId · event $kind"))
        }
        if (kind in COALESCED_EVENTS) {
            val enqueue = {
                if (!closed.get()) {
                    pendingEvents[EventIdentity(nodeId, kind)] = payload.copyOf()
                    scheduleFrame()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) enqueue() else main.post(enqueue)
            return
        }
        dispatchEventImmediately(nodeId, kind, payload)
    }

    private fun dispatchEventImmediately(nodeId: Long, kind: Int, payload: ByteArray) {
        synchronized(handleLock) {
            val active = handle
            if (active != 0L && readyForEvents) {
                nativeDispatchEvent(active, nodeId, kind, payload)
            } else if (active != 0L) {
                if (pendingImmediateEvents.size >= MAX_PENDING_EVENTS) {
                    pendingImmediateEvents.removeFirst()
                }
                pendingImmediateEvents.addLast(PendingEvent(nodeId, kind, payload.copyOf()))
            }
        }
    }

    fun dispatchBack() {
        dispatchEvent(0, EVENT_BACK)
    }

    fun hasPresentedModal(): Boolean = renderer.hasPresentedModal()

    fun consumePresentedModalBack(): Boolean = renderer.consumePresentedModalBack()

    fun reload(
        entryPath: String,
        confirmedAtNanos: Long? = null,
        bundleBytes: Int = 0,
        previousRequestReleased: (() -> Unit)? = null,
    ) {
        synchronized(handleLock) {
            val active = handle
            if (active != 0L) {
                if (confirmedAtNanos != null) {
                    hotReloadLatency.begin(confirmedAtNanos, bundleBytes)
                }
                if (previousRequestReleased != null) reloadReleases[entryPath] = previousRequestReleased
                readyForEvents = false
                modules.prepareReload()
                nativeReload(active, entryPath)
            }
        }
    }

    @Suppress("unused") // JNI: shutdown has finished, including lazy PHP autoloads.
    private fun onNativeRequestReleased(entryPath: String) {
        main.post { reloadReleases.remove(entryPath)?.invoke() }
    }

    fun stats(): RuntimeStats {
        val values = synchronized(handleLock) {
            val active = handle
            if (active == 0L) LongArray(19) else nativeStats(active)
        }
        return RuntimeStats(
            commits = values.getOrElse(0) { 0 },
            nodes = values.getOrElse(1) { 0 },
            created = values.getOrElse(2) { 0 },
            removed = values.getOrElse(3) { 0 },
            updated = values.getOrElse(4) { 0 },
            retainedBytes = values.getOrElse(5) { 0 },
            fullCommits = values.getOrElse(6) { 0 },
            patchCommits = values.getOrElse(7) { 0 },
            inputBytes = values.getOrElse(8) { 0 },
            outputBytes = values.getOrElse(9) { 0 },
            decodeP95Micros = values.getOrElse(10) { 0 },
            reconcileP95Micros = values.getOrElse(11) { 0 },
            layoutP95Micros = values.getOrElse(12) { 0 },
            encodeP95Micros = values.getOrElse(13) { 0 },
            coalescedCommands = values.getOrElse(14) { 0 },
            bufferReuses = values.getOrElse(15) { 0 },
            reusedBufferBytes = values.getOrElse(16) { 0 },
            measuredFrames = values.getOrElse(17) { 0 },
            deadlineMisses = values.getOrElse(18) { 0 },
        )
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        renderer.onNativeChildVisibility = null
        val active = synchronized(handleLock) {
            handle.also { handle = 0L }
        }
        if (active != 0L) {
            // Stopping joins the PHP worker (and shuts PHP down). Never make
            // the UI thread wait for it: a slow or stuck teardown would keep
            // the next Activity on the splash window.
            Thread({ nativeStop(active) }, "pam-runtime-stop").start()
        }
        main.removeCallbacksAndMessages(null)
        reloadReleases.clear()
        choreographer.removeFrameCallback(frameCallback)
        main.removeCallbacks(commitAfterFrame)
        commitAfterFrameScheduled = false
        frameScheduled = false
        while (pendingBatches.isNotEmpty()) {
            releaseBatch(pendingBatches.removeFirst().handle)
        }
        pendingEvents.clear()
        pendingImmediateEvents.clear()
        ownedBatchHandles.toList().forEach(::releaseBatch)
        installedModules?.close()
        renderer.close()
    }

    @Suppress("unused")
    private fun onNativeBatch(batch: ByteBuffer, batchHandle: Long): Boolean {
        if (batchHandle == 0L || closed.get() || !ownedBatchHandles.add(batchHandle)) return false
        val decodeStarted = System.nanoTime()
        Trace.beginSection("PamNative.decode")
        val mutations = try {
            runCatching {
                BatchDecoder.decode(batch.asReadOnlyBuffer())
            }.getOrElse { error ->
                ownedBatchHandles.remove(batchHandle)
                onNativeError(error.message ?: "Cannot decode native batch")
                // The engine already retained this batch: later patches would
                // target nodes this host never created ("Node N cannot
                // contain children"). Replay the retained tree instead.
                main.post(::resynchronize)
                return false
            }
        } finally {
            Trace.endSection()
        }
        val decodeNanos = System.nanoTime() - decodeStarted
        main.post {
            if (closed.get() || awaitingRemount || !attachedSurface) {
                releaseBatch(batchHandle)
                return@post
            }
            pendingBatches.addLast(
                PendingBatch(
                    mutations = mutations,
                    handle = batchHandle,
                    decodeNanos = decodeNanos,
                ),
            )
            markReadyForEvents()
            if (surfaceAwaitingFirstFrame) flushFirstFrame() else scheduleFrame()
        }
        return true
    }

    /**
     * JNI: the engine's full mount of the retained tree, published in order
     * with regular batches by the runtime worker. An empty buffer (no tree yet)
     * still ends the remount window.
     */
    @Suppress("unused")
    private fun onNativeRemount(batch: ByteBuffer?, batchHandle: Long): Boolean {
        if (closed.get()) return false
        val owned = batch != null && batchHandle != 0L && ownedBatchHandles.add(batchHandle)
        val decodeStarted = System.nanoTime()
        val mutations = if (owned) {
            runCatching { BatchDecoder.decode(batch!!.asReadOnlyBuffer()) }.getOrElse { error ->
                ownedBatchHandles.remove(batchHandle)
                onNativeError(error.message ?: "Cannot decode native remount batch")
                null
            }
        } else {
            null
        }
        val decodeNanos = System.nanoTime() - decodeStarted
        main.post {
            if (closed.get() || !attachedSurface) {
                if (mutations != null) releaseBatch(batchHandle)
                return@post
            }
            awaitingRemount = false
            while (pendingBatches.isNotEmpty()) {
                releaseBatch(pendingBatches.removeFirst().handle)
            }
            if (mutations != null) {
                pendingBatches.addLast(
                    PendingBatch(mutations, batchHandle, decodeNanos, remount = true),
                )
            }
            markReadyForEvents()
            if (surfaceAwaitingFirstFrame && mutations != null) flushFirstFrame() else scheduleFrame()
        }
        return mutations != null
    }

    @Suppress("unused")
    private fun onNativeCall(
        requestId: Long,
        module: String,
        method: String,
        payload: ByteArray,
    ) {
        if (deferUntilModulesInstalled { onNativeCall(requestId, module, method, payload) }) return
        val started = System.nanoTime()
        modules.invoke(
            module = module,
            method = method,
            payload = payload,
            completion = ModuleCompletion { status, result ->
                onDiagnostic(
                    moduleDiagnostic(
                        module,
                        method,
                        payload,
                        result,
                        status == dev.pam.nativeapp.modules.ModuleResultStatus.FAILURE,
                        System.nanoTime() - started,
                    ),
                )
                synchronized(handleLock) {
                    val active = handle
                    if (active != 0L) {
                        nativeDispatchModuleResult(active, requestId, status.value, result)
                    }
                }
            },
        )
    }

    private fun moduleDiagnostic(
        module: String,
        method: String,
        requestPayload: ByteArray,
        responsePayload: ByteArray,
        transportFailed: Boolean,
        durationNanos: Long,
    ): RuntimeDiagnostic {
        val fallback = RuntimeDiagnostic(
            RuntimeDiagnosticKind.MODULE_CALL,
            "$module.$method",
            durationNanos,
            transportFailed,
        )
        if (module != "http" || method != "request") return fallback

        return runCatching {
            val request = WireMap.decode(requestPayload)
            val methodName = (request["method"] as? WireValue.Text)?.value ?: return fallback
            val methodCode = RuntimeHttpMethod.valueOf(methodName).value
            val requestBytes = (request["body"] as? WireValue.Text)
                ?.value
                ?.toByteArray(Charsets.UTF_8)
                ?.size
                ?: 0
            val response = if (transportFailed) emptyMap() else WireMap.decode(responsePayload)
            val statusCode = (response["statusCode"] as? WireValue.Integer)?.value?.toInt()
            val responseBytes = (response["body"] as? WireValue.Text)
                ?.value
                ?.toByteArray(Charsets.UTF_8)
                ?.size
                ?: 0
            RuntimeDiagnostic(
                kind = RuntimeDiagnosticKind.NETWORK,
                label = "HTTP $methodName",
                durationNanos = durationNanos,
                failed = transportFailed || (statusCode != null && statusCode >= 400),
                methodCode = methodCode,
                statusCode = statusCode,
                requestBytes = requestBytes,
                responseBytes = responseBytes,
            )
        }.getOrElse { fallback }
    }

    @Suppress("unused")
    private fun onNativeCallTyped(
        requestId: Long,
        operation: Int,
        payload: ByteArray,
    ) {
        if (deferUntilModulesInstalled { onNativeCallTyped(requestId, operation, payload) }) return
        val started = System.nanoTime()
        modules.invoke(
            operationValue = operation,
            payload = payload,
            completion = ModuleCompletion { status, result ->
                onDiagnostic(
                    RuntimeDiagnostic(
                        RuntimeDiagnosticKind.MODULE_CALL,
                        "system.operation.$operation",
                        System.nanoTime() - started,
                        status == dev.pam.nativeapp.modules.ModuleResultStatus.FAILURE,
                    ),
                )
                synchronized(handleLock) {
                    val active = handle
                    if (active != 0L) {
                        nativeDispatchModuleResult(active, requestId, status.value, result)
                    }
                }
            },
        )
    }

    /** JNI: engine text measurement, see text_measure.rs and PamTextLayout. */
    @Suppress("unused", "LongParameterList")
    private fun onMeasureText(
        nodeId: Long,
        text: ByteArray,
        spans: ByteArray,
        family: String,
        features: String,
        fontSize: Float,
        fontScale: Float,
        letterSpacing: Float,
        lineHeight: Float,
        availableWidth: Float,
        fontWeight: Int,
        italic: Boolean,
        includeFontPadding: Boolean,
        textTransform: Int,
        breakStrategy: Int,
        hyphenation: Int,
        maxLines: Int,
        output: FloatArray,
    ): Boolean = try {
        dev.pam.nativeapp.render.PamTextLayout.measure(
            raw = String(text, Charsets.UTF_8),
            spansWire = String(spans, Charsets.UTF_8),
            style = dev.pam.nativeapp.render.PamTextStyle(
                fontFamily = family.ifEmpty { null },
                fontSize = fontSize,
                fontScale = fontScale,
                fontWeight = fontWeight,
                italic = italic,
                letterSpacing = letterSpacing,
                lineHeight = lineHeight,
                includeFontPadding = includeFontPadding,
                textTransform = textTransform,
                breakStrategy = breakStrategy,
                hyphenation = hyphenation,
                maxLines = maxLines,
                fontFeatures = features.ifEmpty { null },
            ),
            availableWidth = availableWidth,
            density = context.resources.displayMetrics.density,
            typefaces = textTypefaces,
            output = output,
        )
        true
    } catch (error: RuntimeException) {
        Log.w("PamNativeText", "Text measurement failed for node $nodeId", error)
        false
    }

    /**
     * JNI: PHP's synchronous `pam_native_crypto()` (Pam\Native\Crypto), on the
     * PHP worker thread. Null rejects (invalid signature, failed
     * authentication, malformed input).
     */
    @Suppress("unused")
    private fun onNativeCrypto(
        operation: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray,
    ): ByteArray? = try {
        PamCrypto.perform(operation, key, nonce, aad, input)
    } catch (error: RuntimeException) {
        Log.w("PamNativeCrypto", "Crypto operation $operation failed", error)
        null
    }

    @Suppress("unused")
    private fun onNativeError(message: String) {
        completeHotReload(failed = true)
        onDiagnostic(RuntimeDiagnostic(RuntimeDiagnosticKind.ERROR, message.take(120), failed = true))
        main.post {
            if (!closed.get()) {
                reportError(message)
            }
        }
    }

    private external fun nativeStart(
        entry: String,
        stateDirectory: String,
        widthDp: Float,
        heightDp: Float,
        textScale: Float,
        darkAppearance: Boolean,
        safeLeft: Float,
        safeTop: Float,
        safeRight: Float,
        safeBottom: Float,
        surfacePolicy: Int,
    ): Long
    private external fun nativeSetKeyboardInset(
        handle: Long,
        bottom: Float,
        width: Float,
        height: Float,
        textScale: Float,
    )

    private external fun nativeSetSurfaceKeyboardInset(
        handle: Long,
        surface: Long,
        bottom: Float,
        width: Float,
        height: Float,
        textScale: Float,
    )

    private external fun nativeRelayout(
        handle: Long,
        widthDp: Float,
        heightDp: Float,
        textScale: Float,
        darkAppearance: Boolean,
    )
    private external fun nativeSetChildVisibility(handle: Long, owner: Long, child: Long, visible: Boolean)

    private external fun nativeSetRefreshRate(handle: Long, refreshRateHz: Double)

    private external fun nativeSetSafeAreaInsets(handle: Long, left: Float, top: Float, right: Float, bottom: Float)

    private external fun nativeDispatchEvent(
        handle: Long,
        nodeId: Long,
        eventKind: Int,
        payload: ByteArray,
    )

    private external fun nativeDispatchModuleResult(
        handle: Long,
        requestId: Long,
        status: Int,
        payload: ByteArray,
    )

    private external fun nativeReload(handle: Long, entry: String)
    private external fun nativeRemount(handle: Long)
    private external fun nativeStats(handle: Long): LongArray
    private external fun nativeReleaseBatch(batchHandle: Long)
    private external fun nativeStop(handle: Long)

    private fun scheduleFrame() {
        if (
            frameScheduled ||
            pendingBatches.isEmpty() && (pendingEvents.isEmpty() || !readyForEvents)
        ) return
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun flushFirstFrame() {
        if (frameScheduled) {
            choreographer.removeFrameCallback(frameCallback)
            main.removeCallbacks(commitAfterFrame)
            commitAfterFrameScheduled = false
            frameScheduled = false
        }
        flushEvents()
        flushBatches()
    }

    private fun flushEvents() {
        if (pendingEvents.isEmpty()) return
        if (closed.get()) {
            pendingEvents.clear()
            return
        }
        if (!readyForEvents) return
        val current = pendingEvents.toMap()
        pendingEvents.clear()
        synchronized(handleLock) {
            val active = handle
            if (active == 0L) return
            current.forEach { (identity, payload) ->
                // Coalesced events wait for the next frame; drop those whose
                // node was removed meanwhile instead of waking PHP for them.
                if (identity.nodeId == 0L || renderer.hasNode(identity.nodeId)) {
                    nativeDispatchEvent(active, identity.nodeId, identity.kind, payload)
                }
            }
        }
    }

    private fun markReadyForEvents() {
        synchronized(handleLock) {
            if (readyForEvents) return
            readyForEvents = true
            val active = handle
            if (active == 0L) return
            while (pendingImmediateEvents.isNotEmpty()) {
                val event = pendingImmediateEvents.removeFirst()
                nativeDispatchEvent(active, event.nodeId, event.kind, event.payload)
            }
        }
    }

    private fun flushBatches() {
        if (closed.get()) {
            while (pendingBatches.isNotEmpty()) {
                releaseBatch(pendingBatches.removeFirst().handle)
            }
            return
        }

        if (pendingBatches.isEmpty()) {
            scheduleFrame()
            return
        }
        val current = ArrayList<PendingBatch>(pendingBatches.size)
        while (pendingBatches.isNotEmpty()) {
            current += pendingBatches.removeFirst()
        }
        if (renderer.isLayoutInProgress()) {
            current.asReversed().forEach(pendingBatches::addFirst)
            scheduleFrame()
            return
        }
        val started = System.nanoTime()
        var committed = false
        // A remount replays the whole retained tree: whatever is mounted
        // (nothing on a fresh surface, a diverged tree after a failure) goes.
        val remount = current.any(PendingBatch::remount)
        Trace.beginSection("PamNative.mount")
        if (Trace.isEnabled()) {
            Trace.beginSection("mutations=" + current.sumOf { it.mutations.size })
            Trace.endSection()
        }
        try {
            runCatching {
                if (remount) renderer.resetTree()
                renderer.commit(current.map(PendingBatch::mutations))
            }.onSuccess {
                committed = true
            }.onFailure {
                reportError(it.message ?: "Cannot render native batch")
            }
        } finally {
            Trace.endSection()
        }
        current.forEach { batch -> releaseBatch(batch.handle) }
        // A commit that threw part-way left a half-applied tree. Resynchronize
        // once from the retained tree; a failing remount is not retried.
        if (!committed && !remount) resynchronize()
        // Engine statistics cross JNI under the handle lock (milliseconds
        // while PHP renders): read only when the dev tools or a debug log ask.
        val metrics = RuntimeFrameMetrics(
            batches = current.size,
            decodeNanos = current.sumOf(PendingBatch::decodeNanos),
            mountNanos = System.nanoTime() - started,
            statsProvider = ::stats,
        )
        if (BuildConfig.DEBUG) {
            val runtimeStats = metrics.stats
            Log.d(
                PERFORMANCE_LOG_TAG,
                "batches=${metrics.batches} decodeNs=${metrics.decodeNanos} " +
                    "mountNs=${metrics.mountNanos} " +
                    "full=${runtimeStats.fullCommits} patch=${runtimeStats.patchCommits} " +
                    "inBytes=${runtimeStats.inputBytes} outBytes=${runtimeStats.outputBytes} " +
                    "buffers=${ownedBatchHandles.size}",
            )
        } else if (BuildConfig.BUILD_TYPE == "benchmark") {
            Log.d(
                PERFORMANCE_LOG_TAG,
                "batches=${metrics.batches} decodeNs=${metrics.decodeNanos} mountNs=${metrics.mountNanos}",
            )
        }
        if (committed) {
            surfaceAwaitingFirstFrame = false
            completeHotReload(failed = false)
            onFrameCommitted(metrics)
        }
        scheduleFrame()
    }

    private fun releaseBatch(batchHandle: Long) {
        if (ownedBatchHandles.remove(batchHandle)) {
            nativeReleaseBatch(batchHandle)
        }
    }

    private fun completeHotReload(failed: Boolean) {
        val timing = hotReloadLatency.complete(System.nanoTime(), failed) ?: return
        onDiagnostic(
            RuntimeDiagnostic(
                kind = RuntimeDiagnosticKind.HOT_RELOAD,
                label = if (failed) "confirmed version to failure" else "confirmed version to first frame",
                durationNanos = timing.durationNanos,
                failed = timing.failed,
                requestBytes = timing.bundleBytes,
            )
        )
    }

    companion object {
        private const val EVENT_BACK = 3
        private const val MAX_PAYLOAD_BYTES = 1024 * 1024
        private const val MAX_PENDING_EVENTS = 256
        private const val PERFORMANCE_LOG_TAG = "PamNativePerf"
        private const val HEAVY_COMMIT_MUTATIONS = 32
        private val COALESCED_EVENTS = setOf(
            9, // scroll
            17, // dimensions
            20, // image progress
            25, // input selection
            26, // input content size
            30, // pointer move
        )

        // The engine library is loaded off the UI thread by PamStartup
        // (start() waits for it); every JNI call below needs a live handle.
    }
}

private data class EventIdentity(
    val nodeId: Long,
    val kind: Int,
)

private data class PendingEvent(
    val nodeId: Long,
    val kind: Int,
    val payload: ByteArray,
)

data class RuntimeStats(
    val commits: Long,
    val nodes: Long,
    val created: Long,
    val removed: Long,
    val updated: Long,
    val retainedBytes: Long,
    val fullCommits: Long,
    val patchCommits: Long,
    val inputBytes: Long,
    val outputBytes: Long,
    val decodeP95Micros: Long = 0,
    val reconcileP95Micros: Long = 0,
    val layoutP95Micros: Long = 0,
    val encodeP95Micros: Long = 0,
    val coalescedCommands: Long = 0,
    val bufferReuses: Long = 0,
    val reusedBufferBytes: Long = 0,
    val measuredFrames: Long = 0,
    val deadlineMisses: Long = 0,
)

class RuntimeFrameMetrics(
    val batches: Int,
    val decodeNanos: Long,
    val mountNanos: Long,
    statsProvider: () -> RuntimeStats,
) {
    constructor(batches: Int, decodeNanos: Long, mountNanos: Long, stats: RuntimeStats) :
        this(batches, decodeNanos, mountNanos, { stats })

    /** Read from the engine on first access (the commit's frame stays free of it). */
    val stats: RuntimeStats by lazy(LazyThreadSafetyMode.NONE, statsProvider)
}

private data class PendingBatch(
    val mutations: List<Mutation>,
    val handle: Long,
    val decodeNanos: Long,
    /** The engine's full mount of its retained tree (see [PamRuntime.onNativeRemount]). */
    val remount: Boolean = false,
)

/** Process-wide owner of the embedded PHP runtime (see [PamRuntime.attach]). */
internal object PamRuntimeHost {
    @Volatile
    var runtime: PamRuntime? = null

    @Volatile
    var entryPath: String? = null
}
