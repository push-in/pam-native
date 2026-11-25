import Foundation
import UIKit

/// Window privacy controls (`Screen::secure()`).
///
/// iOS has no public FLAG_SECURE: screenshots cannot be blocked. While secure
/// mode is on, PAM covers every app window with an opaque privacy shield
/// whenever the screen is being recorded, mirrored or AirPlayed
/// (`UIScreen.isCaptured`) and while the app is inactive, so the app switcher
/// snapshot never shows protected content (Android's recents thumbnail
/// behaviour). Reports `supported: true`, `screenshotsBlocked: false`.
final class WindowModule: NativeModule {
    /// Set by PamRuntime: the error overlay's reload (`App::reload()`).
    var onReload: (() -> Void)?

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "reload":
            guard let onReload else {
                completion(.failure, Data("This host cannot reload the app".utf8))
                return
            }
            // Answered first; the runtime is swapped on the next main-queue turn.
            completion(.success, Data())
            DispatchQueue.main.async { onReload() }
        case "secure":
            guard let values = try? WireMap.decode(payload), case let .flag(enabled)? = values["enabled"] else {
                completion(.failure, Data("Secure flag is required".utf8))
                return
            }
            DispatchQueue.main.async {
                PamScreenSecurity.shared.setEnabled(enabled)
                completion(.success, Self.status())
            }
        case "isSecure":
            DispatchQueue.main.async { completion(.success, Self.status()) }
        default:
            completion(.failure, Data("Unknown window method \(method)".utf8))
        }
    }

    private static func status() -> Data {
        let security = PamScreenSecurity.shared
        return (try? WireMap.encode([
            "enabled": .flag(security.enabled),
            "supported": .flag(true),
            "screenshotsBlocked": .flag(false),
            "captured": .flag(security.isCaptured),
        ])) ?? Data()
    }
}

/// Privacy shield driven by screen capture and app activity.
final class PamScreenSecurity {
    static let shared = PamScreenSecurity()

    private(set) var enabled = false
    private var observers: [NSObjectProtocol] = []
    private var inactive = false
    private var shields: [ObjectIdentifier: UIView] = [:]

    var isCaptured: Bool {
        windows().contains { $0.screen.isCaptured } || UIScreen.main.isCaptured
    }

    /// True when the shield must cover the windows.
    static func shouldShield(enabled: Bool, captured: Bool, inactive: Bool) -> Bool {
        enabled && (captured || inactive)
    }

    func setEnabled(_ value: Bool) {
        guard value != enabled else {
            refresh()
            return
        }
        enabled = value
        if value {
            let center = NotificationCenter.default
            observers = [
                center.addObserver(forName: UIScreen.capturedDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
                    self?.refresh()
                },
                center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
                    self?.inactive = true
                    self?.refresh()
                },
                center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
                    self?.inactive = false
                    self?.refresh()
                },
            ]
        } else {
            observers.forEach { NotificationCenter.default.removeObserver($0) }
            observers = []
            inactive = false
        }
        refresh()
    }

    private func windows() -> [UIWindow] {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
    }

    private func refresh() {
        let shield = Self.shouldShield(enabled: enabled, captured: isCaptured, inactive: inactive)
        let current = windows()
        let live = Set(current.map(ObjectIdentifier.init))
        for (key, view) in shields where !live.contains(key) {
            view.removeFromSuperview()
            shields[key] = nil
        }
        for window in current {
            let key = ObjectIdentifier(window)
            if shield {
                let cover = shields[key] ?? Self.makeShield()
                shields[key] = cover
                cover.frame = window.bounds
                window.addSubview(cover)
                window.bringSubviewToFront(cover)
            } else if let cover = shields.removeValue(forKey: key) {
                cover.removeFromSuperview()
            }
        }
    }

    private static func makeShield() -> UIView {
        let cover = UIView()
        cover.backgroundColor = .pamSystemBackground
        cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        cover.accessibilityViewIsModal = true
        cover.isAccessibilityElement = true
        cover.accessibilityLabel = "Protected content"
        let icon = UIImageView(image: UIImage(systemName: "lock.fill"))
        icon.tintColor = .secondaryLabel
        icon.translatesAutoresizingMaskIntoConstraints = false
        cover.addSubview(icon)
        NSLayoutConstraint.activate([
            icon.centerXAnchor.constraint(equalTo: cover.centerXAnchor),
            icon.centerYAnchor.constraint(equalTo: cover.centerYAnchor),
            icon.widthAnchor.constraint(equalToConstant: 36),
            icon.heightAnchor.constraint(equalToConstant: 36),
        ])
        icon.contentMode = .scaleAspectFit
        return cover
    }
}

/// VoiceOver announcements and state.
final class AccessibilityModule: NativeModule {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "announce":
            guard let values = try? WireMap.decode(payload),
                  case let .text(text)? = values["text"],
                  !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  text.count <= 4_096 else {
                completion(.failure, Data("Announcement text is invalid".utf8))
                return
            }
            DispatchQueue.main.async {
                let running = UIAccessibility.isVoiceOverRunning
                if running {
                    UIAccessibility.post(notification: .announcement, argument: text)
                }
                completion(.success, (try? WireMap.encode(["delivered": .flag(running)])) ?? Data())
            }
        case "status":
            DispatchQueue.main.async {
                let running = UIAccessibility.isVoiceOverRunning
                completion(.success, (try? WireMap.encode([
                    "enabled": .flag(running),
                    "touchExploration": .flag(running),
                ])) ?? Data())
            }
        case "textScale":
            DispatchQueue.main.async {
                completion(.success, PamTextScale.snapshot(system: PamTextScale.systemMultiplier()))
            }
        case "setTextScale":
            guard let values = try? WireMap.decode(payload), case let .decimal(multiplier)? = values["multiplier"] else {
                completion(.failure, Data("Text scale multiplier is required".utf8))
                return
            }
            var cap: Double = 0
            if case let .decimal(value)? = values["maxSystemScale"] { cap = value }
            guard PamTextScale.persist(multiplier: Float(multiplier), maxSystemScale: Float(cap)) else {
                completion(.failure, Data("Text scale is out of range".utf8))
                return
            }
            DispatchQueue.main.async {
                completion(.success, PamTextScale.snapshot(system: PamTextScale.systemMultiplier()))
            }
        default:
            completion(.failure, Data("Unknown accessibility method \(method)".utf8))
        }
    }
}

/// `Image::prefetch()`: downloads images into the renderer disk cache
/// (PamMediaDiskCache, same identity as `<Image cacheKey>`), two at a time,
/// higher priority first.
final class ImagePrefetchModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    static let maxUrls = 100
    static let maxPending = 128
    static let maxSubscribers = 512
    typealias CacheLookup = @Sendable (String, String?, @escaping @Sendable (Data?) -> Void) -> Void
    private let cacheLookup: CacheLookup
    private struct Identity: Hashable {
        let source: String
        let headers: String
        let cacheKey: String?
    }
    private final class Job: @unchecked Sendable {
        let identity: Identity
        let headers: [String: String]
        var priority: Int64
        let order: Int64
        var waiters: [PrefetchTally] = []
        var started = false
        var task: URLSessionTask?
        var operation: Operation?
        init(identity: Identity, headers: [String: String], priority: Int64, order: Int64) {
            self.identity = identity
            self.headers = headers
            self.priority = priority
            self.order = order
        }
    }
    private let session: URLSession
    private let assetQueue: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "dev.pam.image-prefetch-assets"
        queue.maxConcurrentOperationCount = 2
        queue.qualityOfService = .utility
        return queue
    }()
    private let lock = NSLock()
    private var closed = false
    private var active = false
    private var jobs: [Identity: Job] = [:]
    // Canceled transports keep their slot until their completion has drained.
    private var retiring: [ObjectIdentifier: Job] = [:]
    private var running = 0
    private var subscribers = 0
    private var sequence: Int64 = 0
    private var observers: [NSObjectProtocol] = []

    init(
        session: URLSession = URLSession(configuration: .default),
        initiallyActive: Bool? = nil,
        cacheLookup: @escaping CacheLookup = { source, key, completion in
            PamMediaDiskCache.shared.data(source: source, stableKey: key, maxAgeMs: 0, completion: completion)
        }
    ) {
        self.session = session
        self.cacheLookup = cacheLookup
        observers = [
            NotificationCenter.default.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.setActive(false) },
            NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in self?.setActive(false) },
            NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in self?.setActive(true) },
        ]
        if let initiallyActive {
            setActive(initiallyActive)
        } else if Thread.isMainThread {
            setActive(UIApplication.shared.applicationState == .active)
        } else {
            // Fail speculative work until the main-thread snapshot is available.
            DispatchQueue.main.async { [weak self] in
                self?.setActive(UIApplication.shared.applicationState == .active)
            }
        }
    }

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            guard method == "prefetch" else { throw PrefetchError("Unknown image method \(method)") }
            let values = try WireMap.decode(payload)
            guard case let .text(urlsJson)? = values["urls"],
                  let urls = try JSONSerialization.jsonObject(with: Data(urlsJson.utf8)) as? [String],
                  (1...Self.maxUrls).contains(urls.count) else {
                throw PrefetchError("Prefetch between 1 and \(Self.maxUrls) images")
            }
            var priority: Int64 = 2
            if case let .integer(value)? = values["priority"] { priority = min(max(value, 1), 5) }
            var headers: [String: String] = [:]
            if case let .text(text)? = values["headers"], !text.trimmingCharacters(in: .whitespaces).isEmpty {
                headers = (try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: String]) ?? [:]
            }
            let canonical = String(decoding: try JSONSerialization.data(withJSONObject: headers, options: .sortedKeys), as: UTF8.self)
            var cacheKeys: [String] = []
            if case let .text(text)? = values["cacheKeys"] {
                cacheKeys = (try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [Any])?.map { $0 as? String ?? "" } ?? []
            }
            let tally = PrefetchTally(count: urls.count, completion: completion)
            for (index, source) in urls.enumerated() {
                let key = index < cacheKeys.count && !cacheKeys[index].isEmpty ? cacheKeys[index] : nil
                let identity = Identity(source: source, headers: canonical, cacheKey: key)
                lock.lock()
                let existing = jobs[identity]
                let rejected = closed || !active || subscribers >= Self.maxSubscribers
                    || (existing == nil && jobs.count + retiring.count >= Self.maxPending)
                if !rejected {
                    sequence += 1
                    let job = existing ?? Job(identity: identity, headers: headers, priority: priority, order: sequence)
                    // A shared queued request inherits its most urgent consumer.
                    if !job.started { job.priority = max(job.priority, priority) }
                    job.waiters.append(tally)
                    subscribers += 1
                    jobs[identity] = job
                }
                lock.unlock()
                if rejected { tally.finish(bytes: nil) } else { pump() }
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    /// Cancels speculative requests only. The session remains reusable after foregrounding.
    func setActive(_ value: Bool) {
        if value {
            lock.lock()
            if !closed { active = true }
            lock.unlock()
        } else {
            cancelPending()
        }
    }

    func close() {
        lock.lock()
        guard !closed else { lock.unlock(); return }
        closed = true
        let registered = observers
        observers.removeAll()
        lock.unlock()
        cancelPending()
        for observer in registered { NotificationCenter.default.removeObserver(observer) }
        session.invalidateAndCancel()
    }

    private func cancelPending() {
        lock.lock()
        active = false
        let pending = Array(jobs.values)
        jobs.removeAll()
        subscribers = 0
        let callbacks = pending.flatMap { job -> [PrefetchTally] in
            if job.started { retiring[ObjectIdentifier(job)] = job }
            job.task?.cancel()
            job.operation?.cancel()
            let result = job.waiters
            job.waiters.removeAll()
            return result
        }
        lock.unlock()
        for callback in callbacks { callback.finish(bytes: nil) }
    }

    private func pump() {
        lock.lock()
        guard active, !closed, running < 2 else { lock.unlock(); return }
        let ready = jobs.values.filter { !$0.started }.sorted {
            $0.priority == $1.priority ? $0.order < $1.order : $0.priority > $1.priority
        }.prefix(2 - running)
        for job in ready { job.started = true; running += 1 }
        lock.unlock()
        for job in ready { start(job) }
    }

    private func accepts(_ job: Job) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return active && !closed && jobs[job.identity] === job
    }

    private func finish(_ job: Job, bytes: Int64?) {
        lock.lock()
        let retired = retiring.removeValue(forKey: ObjectIdentifier(job)) != nil
        guard retired || jobs[job.identity] === job else { lock.unlock(); return }
        if !retired { jobs[job.identity] = nil }
        if job.started { running -= 1; job.started = false }
        let callbacks = job.waiters
        subscribers -= callbacks.count
        job.waiters.removeAll()
        job.task = nil
        job.operation = nil
        lock.unlock()
        for callback in callbacks { callback.finish(bytes: bytes) }
        pump()
    }

    private func start(_ job: Job) {
        guard accepts(job) else { finish(job, bytes: nil); return }
        let source = job.identity.source
        if source.range(of: "asset://", options: [.anchored, .caseInsensitive]) != nil {
            let operation = BlockOperation { [weak self, weak job] in
                guard let self, let job, self.accepts(job) else { return }
                let bytes = PamInlineImages.preloadAsset(source)
                self.finish(job, bytes: bytes)
            }
            operation.completionBlock = { [weak self, weak job] in
                guard let self, let job else { return }
                self.finish(job, bytes: nil)
            }
            lock.lock()
            let accepted = active && !closed && jobs[job.identity] === job
            if accepted { job.operation = operation }
            lock.unlock()
            if accepted { assetQueue.addOperation(operation) } else { finish(job, bytes: nil) }
            return
        }
        guard let url = URL(string: source), let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http", url.host != nil else { finish(job, bytes: nil); return }
        cacheLookup(source, job.identity.cacheKey) { [weak self, weak job] data in
            guard let self, let job else { return }
            guard self.accepts(job) else { self.finish(job, bytes: nil); return }
            if let data, !data.isEmpty { self.finish(job, bytes: Int64(data.count)); return }
            self.download(job, url: url)
        }
    }

    private func download(_ job: Job, url: URL) {
        var request = URLRequest(url: url, timeoutInterval: 30)
        for (name, value) in job.headers where name.range(of: "^[A-Za-z0-9-]{1,64}$", options: .regularExpression) != nil {
            request.setValue(value, forHTTPHeaderField: name)
        }
        let task = session.dataTask(with: request) { [weak self, weak job] data, response, error in
            guard let self, let job else { return }
            guard self.accepts(job) else { self.finish(job, bytes: nil); return }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard error == nil, (200..<300).contains(status), let data, !data.isEmpty,
                  UIImage(data: data) != nil else { self.finish(job, bytes: nil); return }
            PamMediaDiskCache.shared.store(data, source: job.identity.source, stableKey: job.identity.cacheKey, checksum: nil, limit: 0, pinned: false) { [weak self, weak job] stored in
                guard let self, let job else { return }
                self.finish(job, bytes: stored ? Int64(data.count) : nil)
            }
        }
        lock.lock()
        let accepted = active && !closed && jobs[job.identity] === job
        if accepted { job.task = task }
        lock.unlock()
        if accepted { task.resume() } else { task.cancel(); finish(job, bytes: nil) }
    }

    private struct PrefetchError: LocalizedError {
        let message: String
        init(_ message: String) { self.message = message }
        var errorDescription: String? { message }
    }
}

private final class PrefetchTally: @unchecked Sendable {
    private let lock = NSLock()
    private var remaining: Int
    private var succeeded: Int64 = 0
    private var failed: Int64 = 0
    private var bytes: Int64 = 0
    private let completion: ModuleCompletion

    init(count: Int, completion: @escaping ModuleCompletion) {
        remaining = count
        self.completion = completion
    }

    func finish(bytes value: Int64?) {
        lock.lock()
        if let value {
            succeeded += 1
            bytes += value
        } else {
            failed += 1
        }
        remaining -= 1
        let done = remaining == 0
        let payload = done ? ((try? WireMap.encode([
            "succeeded": .integer(succeeded),
            "failed": .integer(failed),
            "bytes": .integer(bytes),
        ])) ?? Data()) : Data()
        lock.unlock()
        if done { completion(.success, payload) }
    }
}
