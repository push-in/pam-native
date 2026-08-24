import Foundation
import UIKit

private let MAX_PAYLOAD_BYTES = 1024 * 1024
private let MAX_PENDING_EVENTS = 256
private let PERFORMANCE_LOG_TAG = "PamNativePerf"

private typealias PamNativeBatchCallback = @convention(c) (UInt64, UnsafePointer<UInt8>?, Int, UInt64) -> Bool
private typealias PamNativeCallCallback = @convention(c) (
    UInt64,
    Int64,
    UnsafePointer<CChar>?,
    UnsafePointer<CChar>?,
    UnsafePointer<UInt8>?,
    Int
) -> Void
private typealias PamNativeTypedCallCallback = @convention(c) (UInt64, Int64, Int32, UnsafePointer<UInt8>?, Int) -> Void
private typealias PamNativeErrorCallback = @convention(c) (UInt64, UnsafePointer<CChar>?) -> Void

private struct EventIdentity: Hashable {
    let nodeId: Int64
    let kind: Int
}

private struct PendingEvent {
    let nodeId: Int64
    let kind: Int
    let payload: Data
}

private struct PendingBatch {
    let mutations: [Mutation]
    let handle: UInt64
    let decodeNanos: Int64
}

@_silgen_name("pam_native_runtime_start")
private func pam_native_runtime_start(
    _ entry: UnsafePointer<CChar>,
    _ state_directory: UnsafePointer<CChar>,
    _ width_dp: Float,
    _ height_dp: Float,
    _ text_scale: Float,
    _ dark_appearance: Bool,
    _ on_batch: PamNativeBatchCallback,
    _ on_call: PamNativeCallCallback,
    _ on_typed_call: PamNativeTypedCallCallback,
    _ on_error: PamNativeErrorCallback,
) -> UInt64

@_silgen_name("pam_native_runtime_relayout")
private func pam_native_runtime_relayout(
    _ handle: UInt64,
    _ width_dp: Float,
    _ height_dp: Float,
    _ text_scale: Float,
    _ dark_appearance: Bool,
)

@_silgen_name("pam_native_runtime_set_refresh_rate")
private func pam_native_runtime_set_refresh_rate(
    _ handle: UInt64,
    _ refresh_rate_hz: Double,
)

@_silgen_name("pam_native_runtime_dispatch_event")
private func pam_native_runtime_dispatch_event(
    _ handle: UInt64,
    _ node_id: Int64,
    _ event_kind: Int,
    _ payload: UnsafePointer<UInt8>?,
    _ payload_size: Int,
)

@_silgen_name("pam_native_runtime_dispatch_module_result")
private func pam_native_runtime_dispatch_module_result(
    _ handle: UInt64,
    _ request_id: Int64,
    _ status: Int,
    _ payload: UnsafePointer<UInt8>?,
    _ payload_size: Int,
)

@_silgen_name("pam_native_runtime_reload")
private func pam_native_runtime_reload(_ handle: UInt64, _ entry: UnsafePointer<CChar>)

@_silgen_name("pam_native_runtime_stats")
private func pam_native_runtime_stats(_ handle: UInt64, _ values: UnsafeMutablePointer<UInt64>)

@_silgen_name("pam_native_runtime_release_batch")
private func pam_native_runtime_release_batch(_ batchHandle: UInt64)

@_silgen_name("pam_native_runtime_stop")
private func pam_native_runtime_stop(_ handle: UInt64)

private final class RuntimeRegistry {
    private static let lock = NSLock()
    private static var runtimes: [UInt64: PamRuntime] = [:]

    static func register(_ handle: UInt64, runtime: PamRuntime) {
        lock.lock()
        runtimes[handle] = runtime
        lock.unlock()
    }

    static func unregister(handle: UInt64) {
        lock.lock()
        runtimes.removeValue(forKey: handle)
        lock.unlock()
    }

    static func runtime(for handle: UInt64) -> PamRuntime? {
        lock.lock()
        defer { lock.unlock() }
        return runtimes[handle]
    }
}

private enum CInterop {
    static func data(from pointer: UnsafePointer<UInt8>?, _ count: Int) -> Data {
        guard let pointer, count > 0 else {
            return Data()
        }
        return Data(bytes: pointer, count: count)
    }

    static func string(from pointer: UnsafePointer<CChar>?) -> String {
        guard let pointer else { return "" }
        return String(cString: pointer)
    }
}

private final class PamRuntimeDisplayLinkTarget: NSObject {
    private weak var runtime: PamRuntime?

    init(runtime: PamRuntime) {
        self.runtime = runtime
    }

    @objc func didTick(_ sender: CADisplayLink) {
        runtime?.didTick()
    }
}

@_cdecl("pam_native_runtime_batch_callback")
private func pamNativeRuntimeBatchCallback(
    handle: UInt64,
    bytes: UnsafePointer<UInt8>?,
    size: Int,
    batchHandle: UInt64,
) -> Bool {
    guard let runtime = RuntimeRegistry.runtime(for: handle) else {
        return false
    }
    return runtime.onNativeBatch(
        bytes: bytes,
        size: size,
        batchHandle: batchHandle,
    )
}

@_cdecl("pam_native_runtime_call_callback")
private func pamNativeRuntimeCallCallback(
    handle: UInt64,
    requestId: Int64,
    module: UnsafePointer<CChar>?,
    method: UnsafePointer<CChar>?,
    payload: UnsafePointer<UInt8>?,
    payloadSize: Int,
) {
    RuntimeRegistry.runtime(for: handle)?.onNativeCall(
        requestId: requestId,
        module: CInterop.string(from: module),
        method: CInterop.string(from: method),
        payload: CInterop.data(from: payload, payloadSize),
    )
}

@_cdecl("pam_native_runtime_typed_call_callback")
private func pamNativeRuntimeTypedCallCallback(
    handle: UInt64,
    requestId: Int64,
    operation: Int32,
    payload: UnsafePointer<UInt8>?,
    payloadSize: Int,
) {
    RuntimeRegistry.runtime(for: handle)?.onNativeCallTyped(
        requestId: requestId,
        operation: Int(operation),
        payload: CInterop.data(from: payload, payloadSize),
    )
}

@_cdecl("pam_native_runtime_error_callback")
private func pamNativeRuntimeErrorCallback(
    handle: UInt64,
    message: UnsafePointer<CChar>?,
) {
    let text = message.flatMap { String(cString: $0).trimmingCharacters(in: .controlCharacters) } ?? "Unknown Pam Native error"
    RuntimeRegistry.runtime(for: handle)?.onNativeError(text)
}

public struct RuntimeStats {
    public let commits: Int64
    public let nodes: Int64
    public let created: Int64
    public let removed: Int64
    public let updated: Int64
    public let retainedBytes: Int64
    public let fullCommits: Int64
    public let patchCommits: Int64
    public let inputBytes: Int64
    public let outputBytes: Int64
    public let decodeP95Micros: Int64
    public let reconcileP95Micros: Int64
    public let layoutP95Micros: Int64
    public let encodeP95Micros: Int64
    public let coalescedCommands: Int64
    public let bufferReuses: Int64
    public let reusedBufferBytes: Int64
    public let measuredFrames: Int64
    public let deadlineMisses: Int64
}

public struct RuntimeFrameMetrics {
    public let batches: Int
    public let decodeNanos: Int64
    public let mountNanos: Int64
    public let stats: RuntimeStats
}

public final class PamRuntime {
    public typealias ReportError = (String) -> Void
    public typealias FrameCallback = (RuntimeFrameMetrics) -> Void
    public typealias DiagnosticCallback = (RuntimeDiagnostic) -> Void

    private var renderer: PamRenderer!
    private let reportError: ReportError
    private let onFrameCommitted: FrameCallback
    private let onDiagnostic: DiagnosticCallback
    private let modules: NativeModuleRegistry

    private let stateLock = NSLock()
    private var handle: UInt64 = 0
    private var closed = false
    private var frameScheduled = false
    private var readyForEvents = false
    private var ownedBatchHandles: Set<UInt64> = []
    private var pendingBatches: [PendingBatch] = []
    private var pendingImmediateEvents: [PendingEvent] = []
    private var pendingEvents: [EventIdentity: Data] = [:]

    private let coalescedEvents: Set<Int> = [
        EventKind.scroll.rawValue,
        EventKind.dimensions.rawValue,
        EventKind.imageProgress.rawValue,
        EventKind.inputSelectionChange.rawValue,
        EventKind.inputContentSizeChange.rawValue,
        EventKind.pressMove.rawValue,
    ]

    private var displayLink: CADisplayLink?
    private var displayLinkTarget: PamRuntimeDisplayLinkTarget?
    private var lifecycleObservers: [NSObjectProtocol] = []
    private var runtimeEntry: String?
    private var recoveryAttempts = 0
    private var recoveryWorkItem: DispatchWorkItem?
#if DEBUG
    private var hotReloadClient: PamHotReloadClient?
    private let hotReloadLatency = PamHotReloadLatency()
#endif

    public init(
        hostView: UIView,
        nativeModules: [String: NativeModule] = [:],
        nativeViews: [String: NativeViewFactory] = [:],
        reportError: @escaping ReportError,
        onFrameCommitted: @escaping FrameCallback = { _ in },
        onDiagnostic: @escaping DiagnosticCallback = { _ in },
    ) {
        self.reportError = reportError
        self.onFrameCommitted = onFrameCommitted
        self.onDiagnostic = onDiagnostic
        self.modules = NativeModuleRegistry(additionalModules: nativeModules)
        self.renderer = PamRenderer(hostView: hostView, nativeViews: nativeViews) { [weak self] nodeId, kind, payload in
            self?.dispatchEvent(nodeId, kind: kind, payload: payload)
        }

        let target = PamRuntimeDisplayLinkTarget(runtime: self)
        displayLinkTarget = target
        lifecycleObservers = [
            NotificationCenter.default.addObserver(
                forName: UIApplication.didBecomeActiveNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.renderer.setApplicationActive(true)
                self?.dispatchLifecycle(kind: NativeViewEventKind.appState.rawValue, payload: Data("1".utf8))
            },
            NotificationCenter.default.addObserver(
                forName: UIApplication.willResignActiveNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.renderer.setApplicationActive(false)
                self?.dispatchLifecycle(kind: NativeViewEventKind.appState.rawValue, payload: Data("2".utf8))
            },
            NotificationCenter.default.addObserver(
                forName: UIApplication.didEnterBackgroundNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.dispatchLifecycle(kind: NativeViewEventKind.appState.rawValue, payload: Data("3".utf8))
            },
            NotificationCenter.default.addObserver(
                forName: UIApplication.didReceiveMemoryWarningNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.trimMemory(critical: true)
                self?.dispatchLifecycle(kind: NativeViewEventKind.memoryPressure.rawValue, payload: Data("2".utf8))
            },
        ]
    }

    deinit {
        close()
    }

    public func start(
        entry: String,
        widthDp: Float,
        heightDp: Float,
        textScale: Float,
        darkAppearance: Bool,
    ) {
        let normalizedEntry = entry.hasPrefix("file://")
            ? String(entry.dropFirst("file://".count))
            : entry
        guard !normalizedEntry.isEmpty else {
            reportError("Cannot start Pam Native: invalid entry path")
            return
        }
        runtimeEntry = normalizedEntry

        let stateDirectory = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first
            .map { $0.appendingPathComponent("pam/state").path } ?? NSTemporaryDirectory().appending("pam/state")
        do {
            try FileManager.default.createDirectory(
                atPath: stateDirectory,
                withIntermediateDirectories: true,
                attributes: nil,
            )
        } catch {
            reportError("Cannot create Pam Native state directory")
            return
        }

        var startedHandle: UInt64 = 0
        stateLock.lock()
        if closed {
            stateLock.unlock()
            reportError("Cannot start Pam Native after runtime close")
            return
        }
        if handle != 0 {
            stateLock.unlock()
            return
        }
        normalizedEntry.withCString { entryBuffer in
            stateDirectory.withCString { stateBuffer in
                startedHandle = pam_native_runtime_start(
                    entryBuffer,
                    stateBuffer,
                    widthDp,
                    heightDp,
                    textScale,
                    darkAppearance,
                    pamNativeRuntimeBatchCallback,
                    pamNativeRuntimeCallCallback,
                    pamNativeRuntimeTypedCallCallback,
                    pamNativeRuntimeErrorCallback,
                )
            }
        }

        if startedHandle == 0 {
            stateLock.unlock()
            reportError("Pam Native failed to start")
            return
        }

        handle = startedHandle
        pam_native_runtime_set_refresh_rate(
            startedHandle,
            Double(UIScreen.main.maximumFramesPerSecond)
        )
        readyForEvents = false
        stateLock.unlock()

        RuntimeRegistry.register(startedHandle, runtime: self)
        configureDisplayLink()
    }

    public func updateViewport(widthDp: Float, heightDp: Float, textScale: Float, darkAppearance: Bool) {
        let currentHandle = currentHandle()
        guard currentHandle != 0 else { return }
        pam_native_runtime_relayout(
            currentHandle,
            widthDp,
            heightDp,
            textScale,
            darkAppearance,
        )
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow)
        let insets = window?.safeAreaInsets ?? .zero
        let screen = window?.screen ?? UIScreen.main
        let idiom = UIDevice.current.userInterfaceIdiom
        let deviceType = switch idiom {
        case .pad: "tablet"
        case .tv: "tv"
        case .mac: "desktop"
        default: "phone"
        }
        let inputMode = idiom == .tv ? "remote" : (idiom == .mac ? "mouse" : "touch")
        let pointer = inputMode == "touch" || inputMode == "remote" ? "coarse" : "fine"
        let memoryClass = Double(ProcessInfo.processInfo.physicalMemory) / 1_048_576
        let refreshRate = Double(screen.maximumFramesPerSecond)
        let performanceTier: Double = memoryClass >= 4_096 && refreshRate >= 90
            ? 3
            : (memoryClass >= 2_048 ? 2 : 1)
        if let payload = try? WireMap.encode([
            "width": .decimal(Double(widthDp)),
            "height": .decimal(Double(heightDp)),
            "density": .decimal(Double(screen.scale)),
            "appearance": .integer(darkAppearance ? 2 : 1),
            "fontScale": .decimal(Double(textScale)),
            "safeAreaTop": .decimal(Double(insets.top)),
            "safeAreaRight": .decimal(Double(insets.right)),
            "safeAreaBottom": .decimal(Double(insets.bottom)),
            "safeAreaLeft": .decimal(Double(insets.left)),
            "refreshRate": .decimal(refreshRate),
            "reducedMotion": .flag(UIAccessibility.isReduceMotionEnabled),
            "deviceType": .text(deviceType),
            "pointer": .text(pointer),
            "inputMode": .text(inputMode),
            "dynamicRange": .text(screen.traitCollection.displayGamut == .P3 ? "high" : "standard"),
            "displayMode": .text("standalone"),
            "foldPosture": .text("flat"),
            "memoryClass": .decimal(memoryClass),
            "performanceTier": .decimal(performanceTier),
        ]) {
            dispatchLifecycle(kind: EventKind.dimensions.rawValue, payload: payload)
        }
    }

    public func dispatchLifecycle(kind: Int, payload: Data = Data()) {
        reportDiagnostic(RuntimeDiagnostic(kind: .lifecycle, label: "event \(kind)"))
        dispatchEvent(0, kind: kind, payload: payload)
    }

    public func dispatchBack() {
        dispatchEvent(0, kind: EventKind.back.rawValue, payload: Data())
    }

    public func trimMemory(critical: Bool) {
        renderer.trimMemory(critical)
    }

    public func reload(entry: String) {
        let currentHandle = currentHandle()
        guard !entry.isEmpty, currentHandle != 0 else {
            return
        }

        entry.withCString { buffer in
            pam_native_runtime_reload(currentHandle, buffer)
        }

        stateLock.lock()
        readyForEvents = false
        stateLock.unlock()
    }

#if DEBUG
    public func startHotReload(baseURL: URL = URL(string: "http://127.0.0.1:39100")!) {
        guard baseURL.scheme == "http", baseURL.host == "127.0.0.1" || baseURL.host == "localhost" else {
            reportError("Pam Native hot reload only accepts a loopback HTTP endpoint")
            return
        }
        let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let destination = cache.appendingPathComponent("pam/dev", isDirectory: true)
        let client = PamHotReloadClient(
            baseURL: baseURL,
            destination: destination,
            onReload: { [weak self] receipt in
                DispatchQueue.main.async {
                    guard let self else { return }
                    do {
                        try self.hotReloadLatency.begin(
                            confirmedAtNanos: receipt.confirmedAtNanos,
                            bundleBytes: receipt.bundleBytes
                        )
                        self.reload(entry: receipt.entryPath)
                    } catch {
                        self.reportHotReload(failed: true, message: "invalid hot reload measurement")
                    }
                }
            },
            onError: { [weak self] message in
                self?.reportDiagnostic(RuntimeDiagnostic(kind: .hotReload, label: String(message.prefix(120)), failed: true))
            }
        )
        stateLock.lock()
        guard !closed, handle != 0, hotReloadClient == nil else {
            stateLock.unlock()
            client.close()
            return
        }
        hotReloadClient = client
        stateLock.unlock()
        client.start()
    }
#endif

    public func stats() -> RuntimeStats {
        var values = [UInt64](repeating: 0, count: 19)
        let currentHandle = currentHandle()
        if currentHandle != 0 {
            values.withUnsafeMutableBufferPointer { pointer in
                pam_native_runtime_stats(currentHandle, pointer.baseAddress!)
            }
        }

        return RuntimeStats(
            commits: Int64(values[0]),
            nodes: Int64(values[1]),
            created: Int64(values[2]),
            removed: Int64(values[3]),
            updated: Int64(values[4]),
            retainedBytes: Int64(values[5]),
            fullCommits: Int64(values[6]),
            patchCommits: Int64(values[7]),
            inputBytes: Int64(values[8]),
            outputBytes: Int64(values[9]),
            decodeP95Micros: Int64(values[10]),
            reconcileP95Micros: Int64(values[11]),
            layoutP95Micros: Int64(values[12]),
            encodeP95Micros: Int64(values[13]),
            coalescedCommands: Int64(values[14]),
            bufferReuses: Int64(values[15]),
            reusedBufferBytes: Int64(values[16]),
            measuredFrames: Int64(values[17]),
            deadlineMisses: Int64(values[18]),
        )
    }

    public func close() {
#if DEBUG
        stateLock.lock()
        let client = hotReloadClient
        hotReloadClient = nil
        stateLock.unlock()
        client?.close()
#endif
        recoveryWorkItem?.cancel()
        recoveryWorkItem = nil
        stateLock.lock()
        if closed {
            stateLock.unlock()
            return
        }
        closed = true
        let currentHandle = handle
        handle = 0
        frameScheduled = false
        readyForEvents = false
        let pendingHandle = ownedBatchHandles
        ownedBatchHandles.removeAll()
        let pendingBatchesToRelease = pendingBatches.map { $0.handle }
        pendingBatches.removeAll()
        pendingEvents.removeAll()
        pendingImmediateEvents.removeAll()
        stateLock.unlock()

        if let link = displayLink {
            link.invalidate()
            displayLink = nil
        }

        modules.close()
        renderer.close()
        lifecycleObservers.forEach { NotificationCenter.default.removeObserver($0) }
        lifecycleObservers.removeAll()
        displayLinkTarget = nil

        if currentHandle != 0 {
            RuntimeRegistry.unregister(handle: currentHandle)
            pam_native_runtime_stop(currentHandle)
        }

        for handle in pendingHandle {
            releaseBatch(handle)
        }
        for handle in pendingBatchesToRelease {
            releaseBatch(handle)
        }
    }

    private func configureDisplayLink() {
        guard displayLink == nil else {
            return
        }
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard let target = self.displayLinkTarget else { return }
            let link = CADisplayLink(target: target, selector: #selector(PamRuntimeDisplayLinkTarget.didTick(_:)))
            if #available(iOS 15.0, *) {
                link.preferredFramesPerSecond = 120
            }
            link.isPaused = true
            link.add(to: .main, forMode: .common)
            self.displayLink = link
        }
    }

    private func currentHandle() -> UInt64 {
        stateLock.lock()
        let value = handle
        stateLock.unlock()
        return value
    }

    fileprivate func onNativeBatch(bytes: UnsafePointer<UInt8>?, size: Int, batchHandle: UInt64) -> Bool {
        if batchHandle == 0 || size > MAX_PAYLOAD_BYTES {
            releaseBatch(batchHandle)
            return false
        }

        stateLock.lock()
        if closed || handle == 0 || !ownedBatchHandles.insert(batchHandle).inserted {
            stateLock.unlock()
            releaseBatch(batchHandle)
            return false
        }
        stateLock.unlock()

        let start = DispatchTime.now().uptimeNanoseconds
        let payload = CInterop.data(from: bytes, size)
        let mutations: [Mutation]
        do {
            mutations = try BatchDecoder.decode(payload)
        } catch {
            onNativeError(error.localizedDescription)
            releaseBatch(batchHandle)
            return false
        }
        let decodeNanos = Int64(DispatchTime.now().uptimeNanoseconds - start)

        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            guard self.currentHandle() != 0 && !self.closed else {
                self.releaseBatch(batchHandle)
                self.stateLock.lock()
                _ = self.ownedBatchHandles.remove(batchHandle)
                self.stateLock.unlock()
                return
            }

            self.stateLock.lock()
            self.pendingBatches.append(
                PendingBatch(
                    mutations: mutations,
                    handle: batchHandle,
                    decodeNanos: decodeNanos,
                ),
            )
            self.stateLock.unlock()

            self.markReadyForEvents()
            self.scheduleFrame()
        }

        return true
    }

    fileprivate func onNativeCall(
        requestId: Int64,
        module: String,
        method: String,
        payload: Data,
    ) {
        let started = DispatchTime.now().uptimeNanoseconds
        modules.invoke(module: module, method: method, payload: payload) { [weak self] status, result in
            guard let self else { return }
            self.reportDiagnostic(self.moduleDiagnostic(
                module: module,
                method: method,
                requestPayload: payload,
                responsePayload: result,
                transportFailed: status == .failure,
                durationNanos: Int64(DispatchTime.now().uptimeNanoseconds - started)
            ))
            let active = self.currentHandle()
            guard active != 0 && !self.closed else {
                return
            }
            result.withUnsafeBytes { bytes in
                pam_native_runtime_dispatch_module_result(
                    active,
                    requestId,
                    status.rawValue,
                    bytes.bindMemory(to: UInt8.self).baseAddress,
                    bytes.count,
                )
            }
        }
    }

    private func moduleDiagnostic(
        module: String,
        method: String,
        requestPayload: Data,
        responsePayload: Data,
        transportFailed: Bool,
        durationNanos: Int64
    ) -> RuntimeDiagnostic {
        let fallback = RuntimeDiagnostic(
            kind: .moduleCall,
            label: "\(module).\(method)",
            durationNanos: durationNanos,
            failed: transportFailed
        )
        guard module == "http", method == "request" else { return fallback }

        do {
            let request = try WireMap.decode(requestPayload)
            guard case let .text(methodName)? = request["method"] else { return fallback }
            let methodCode: RuntimeHttpMethod
            switch methodName {
            case "GET": methodCode = .get
            case "POST": methodCode = .post
            case "PUT": methodCode = .put
            case "PATCH": methodCode = .patch
            case "DELETE": methodCode = .delete
            default: return fallback
            }
            let requestBytes: Int
            if case let .text(body)? = request["body"] {
                requestBytes = body.utf8.count
            } else {
                requestBytes = 0
            }
            let response: [String: WireValue] = transportFailed ? [:] : try WireMap.decode(responsePayload)
            let statusCode: Int?
            if case let .integer(value)? = response["statusCode"] {
                statusCode = Int(value)
            } else {
                statusCode = nil
            }
            let responseBytes: Int
            if case let .text(body)? = response["body"] {
                responseBytes = body.utf8.count
            } else {
                responseBytes = 0
            }
            return RuntimeDiagnostic(
                kind: .network,
                label: "HTTP \(methodName)",
                durationNanos: durationNanos,
                failed: transportFailed || (statusCode.map { $0 >= 400 } ?? false),
                methodCode: methodCode.rawValue,
                statusCode: statusCode,
                requestBytes: requestBytes,
                responseBytes: responseBytes
            )
        } catch {
            return fallback
        }
    }

    fileprivate func onNativeCallTyped(
        requestId: Int64,
        operation: Int,
        payload: Data,
    ) {
        let started = DispatchTime.now().uptimeNanoseconds
        modules.invoke(
            operationValue: operation,
            payload: payload,
        ) { [weak self] status, result in
            guard let self else { return }
            self.reportDiagnostic(RuntimeDiagnostic(
                kind: .moduleCall,
                label: "system.operation.\(operation)",
                durationNanos: Int64(DispatchTime.now().uptimeNanoseconds - started),
                failed: status == .failure
            ))
            let active = self.currentHandle()
            guard active != 0 && !self.closed else {
                return
            }
            result.withUnsafeBytes { bytes in
                pam_native_runtime_dispatch_module_result(
                    active,
                    requestId,
                    status.rawValue,
                    bytes.bindMemory(to: UInt8.self).baseAddress,
                    bytes.count,
                )
            }
        }
    }

    fileprivate func onNativeError(_ message: String) {
#if DEBUG
        reportHotReload(failed: true, message: "first frame failed")
#endif
        reportDiagnostic(RuntimeDiagnostic(
            kind: .error,
            label: String(message.prefix(120)),
            failed: true
        ))
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
#if DEBUG
            self.reportError(message)
#else
            guard
                let entry = self.runtimeEntry,
                self.recoveryAttempts < 3,
                self.recoveryWorkItem == nil
            else {
                self.reportError(message)
                return
            }
            self.recoveryAttempts += 1
            let delay = min(0.25 * pow(2.0, Double(self.recoveryAttempts - 1)), 2.0)
            let work = DispatchWorkItem { [weak self] in
                guard let self else { return }
                self.recoveryWorkItem = nil
                self.reload(entry: entry)
            }
            self.recoveryWorkItem = work
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
#endif
        }
    }

    fileprivate func dispatchEvent(_ nodeId: Int64, kind: Int, payload: Data = Data()) {
        guard payload.count <= MAX_PAYLOAD_BYTES else {
            return
        }
        if kind >= 42 {
            reportDiagnostic(RuntimeDiagnostic(kind: .event, label: "node \(nodeId) · event \(kind)"))
        }

        if coalescedEvents.contains(kind) {
            stateLock.lock()
            if closed {
                stateLock.unlock()
                return
            }
            pendingEvents[EventIdentity(nodeId: nodeId, kind: kind)] = payload
            stateLock.unlock()
            scheduleFrame()
            return
        }

        dispatchEventImmediately(nodeId: nodeId, kind: kind, payload: payload)
    }

    private func dispatchEventImmediately(nodeId: Int64, kind: Int, payload: Data) {
        stateLock.lock()
        guard !closed else {
            stateLock.unlock()
            return
        }
        let active = handle
        if active != 0 && readyForEvents {
            stateLock.unlock()
            payload.withUnsafeBytes { bytes in
                pam_native_runtime_dispatch_event(
                    active,
                    nodeId,
                    kind,
                    bytes.bindMemory(to: UInt8.self).baseAddress,
                    bytes.count,
                )
            }
            return
        }

        if pendingImmediateEvents.count >= MAX_PENDING_EVENTS {
            pendingImmediateEvents.removeFirst()
        }
        pendingImmediateEvents.append(PendingEvent(nodeId: nodeId, kind: kind, payload: payload))
        stateLock.unlock()
    }

    private func reportDiagnostic(_ diagnostic: RuntimeDiagnostic) {
        DispatchQueue.main.async { [onDiagnostic] in
            onDiagnostic(diagnostic)
        }
    }

    fileprivate func didTick() {
        stateLock.lock()
        guard !closed else {
            stateLock.unlock()
            return
        }
        frameScheduled = false
        stateLock.unlock()

        flushEvents()
        flushBatches()
        schedulePauseIfNeeded()
    }

    private func flushEvents() {
        var readyEvents: [EventIdentity: Data] = [:]
        let active: UInt64

        stateLock.lock()
        defer { stateLock.unlock() }
        guard !pendingEvents.isEmpty, readyForEvents, !closed else {
            return
        }
        active = handle
        if active == 0 { return }
        readyEvents = pendingEvents
        pendingEvents.removeAll()

        for (identity, payload) in readyEvents {
            payload.withUnsafeBytes { bytes in
                pam_native_runtime_dispatch_event(
                    active,
                    identity.nodeId,
                    identity.kind,
                    bytes.bindMemory(to: UInt8.self).baseAddress,
                    bytes.count,
                )
            }
        }
    }

    private func flushBatches() {
        var toProcess: [PendingBatch] = []
        stateLock.lock()
        if closed {
            let toRelease = pendingBatches.map { $0.handle }
            pendingBatches.removeAll()
            stateLock.unlock()
            toRelease.forEach(releaseBatch)
            return
        }

        if pendingBatches.isEmpty {
            stateLock.unlock()
            return
        }

        toProcess = pendingBatches
        pendingBatches.removeAll()
        let currentHandle = handle
        let active = currentHandle != 0
        stateLock.unlock()

        guard active else {
            return
        }

        let started = DispatchTime.now().uptimeNanoseconds
        let mutations = toProcess.map { $0.mutations }

        renderer.commit(mutations)
        let mountNanos = Int64(DispatchTime.now().uptimeNanoseconds - started)
        let metrics = RuntimeFrameMetrics(
            batches: mutations.count,
            decodeNanos: toProcess.reduce(0) { $0 + $1.decodeNanos },
            mountNanos: mountNanos,
            stats: stats(),
        )

        onFrameCommitted(metrics)
#if DEBUG
        reportHotReload(failed: false, message: "first frame committed")
#endif
        recoveryAttempts = 0
        recoveryWorkItem?.cancel()
        recoveryWorkItem = nil

        for batch in toProcess {
            releaseBatch(batch.handle)
        }
    }

    private func markReadyForEvents() {
        stateLock.lock()
        guard !readyForEvents else {
            stateLock.unlock()
            return
        }
        readyForEvents = true
        let active = handle
        let immediate = pendingImmediateEvents
        pendingImmediateEvents.removeAll()
        stateLock.unlock()

        guard active != 0, !immediate.isEmpty else {
            return
        }

        for event in immediate {
            dispatchEventImmediately(
                nodeId: event.nodeId,
                kind: event.kind,
                payload: event.payload,
            )
        }
    }

    private func scheduleFrame() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.stateLock.lock()
            defer { self.stateLock.unlock() }
            if self.frameScheduled {
                return
            }
            if self.closed {
                return
            }
            if self.pendingBatches.isEmpty && (self.pendingEvents.isEmpty || !self.readyForEvents) {
                return
            }
            self.frameScheduled = true
            self.displayLink?.isPaused = false
        }
    }

    private func schedulePauseIfNeeded() {
        stateLock.lock()
        let shouldPause = closed || ((pendingBatches.isEmpty && pendingEvents.isEmpty) || (!readyForEvents && !pendingImmediateEvents.isEmpty))
        stateLock.unlock()

        if shouldPause {
            displayLink?.isPaused = true
        } else {
            scheduleFrame()
        }
    }

    private func releaseBatch(_ handle: UInt64) {
        stateLock.lock()
        let shouldRelease = ownedBatchHandles.remove(handle) != nil
        stateLock.unlock()
        if shouldRelease {
            pam_native_runtime_release_batch(handle)
        }
    }

#if DEBUG
    private func reportHotReload(failed: Bool, message: String) {
        guard let timing = hotReloadLatency.complete(
            completedAtNanos: DispatchTime.now().uptimeNanoseconds,
            failed: failed
        ) else { return }
        reportDiagnostic(RuntimeDiagnostic(
            kind: .hotReload,
            label: message,
            durationNanos: timing.durationNanos,
            failed: timing.failed,
            responseBytes: timing.bundleBytes
        ))
    }
#endif
}
