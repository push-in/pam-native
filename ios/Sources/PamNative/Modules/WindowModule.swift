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
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
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
        cover.backgroundColor = .systemBackground
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
    private let session: URLSession
    private let operations: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "dev.pam.image-prefetch"
        queue.maxConcurrentOperationCount = 2
        queue.qualityOfService = .utility
        return queue
    }()
    private let lock = NSLock()
    private var closed = false

    init(session: URLSession = URLSession(configuration: .default)) {
        self.session = session
    }

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        do {
            guard method == "prefetch" else { throw PrefetchError("Unknown image method \(method)") }
            lock.lock()
            let isClosed = closed
            lock.unlock()
            guard !isClosed else { throw PrefetchError("Image prefetch module is closed") }
            let values = try WireMap.decode(payload)
            guard case let .text(urlsJson)? = values["urls"],
                  let urls = try JSONSerialization.jsonObject(with: Data(urlsJson.utf8)) as? [String] else {
                throw PrefetchError("Image URLs are required")
            }
            guard (1...Self.maxUrls).contains(urls.count) else {
                throw PrefetchError("Prefetch between 1 and \(Self.maxUrls) images")
            }
            var priority: Int64 = 2
            if case let .integer(value)? = values["priority"] { priority = min(max(value, 1), 5) }
            var headers: [String: String] = [:]
            if case let .text(text)? = values["headers"], !text.trimmingCharacters(in: .whitespaces).isEmpty {
                headers = (try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: String]) ?? [:]
            }
            var cacheKeys: [String] = []
            if case let .text(text)? = values["cacheKeys"] {
                cacheKeys = (try JSONSerialization.jsonObject(with: Data(text.utf8)) as? [Any])?
                    .map { $0 as? String ?? "" } ?? []
            }
            let tally = PrefetchTally(count: urls.count, completion: completion)
            for (index, source) in urls.enumerated() {
                let key = index < cacheKeys.count && !cacheKeys[index].isEmpty ? cacheKeys[index] : nil
                let operation = BlockOperation { [weak self] in
                    guard let self else {
                        tally.finish(bytes: nil)
                        return
                    }
                    tally.finish(bytes: self.fetch(source: source, headers: headers, cacheKey: key))
                }
                operation.queuePriority = Self.queuePriority(priority)
                operations.addOperation(operation)
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    func close() {
        lock.lock()
        closed = true
        lock.unlock()
        operations.cancelAllOperations()
        session.invalidateAndCancel()
    }

    /// Synchronous on the prefetch queue; returns stored bytes or nil.
    private func fetch(source: String, headers: [String: String], cacheKey: String?) -> Int64? {
        guard let url = URL(string: source), let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http", url.host != nil else { return nil }
        let cache = PamMediaDiskCache.shared
        let cached = PrefetchWaiter<Data?>()
        cache.data(source: source, stableKey: cacheKey, maxAgeMs: 0) { cached.resolve($0) }
        if let data = cached.wait(), !data.isEmpty { return Int64(data.count) }
        var request = URLRequest(url: url, timeoutInterval: 30)
        for (name, value) in headers where name.range(of: "^[A-Za-z0-9-]{1,64}$", options: .regularExpression) != nil {
            request.setValue(value, forHTTPHeaderField: name)
        }
        let downloaded = PrefetchWaiter<Data?>()
        session.dataTask(with: request) { data, response, error in
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard error == nil, (200..<300).contains(status), let data, !data.isEmpty,
                  UIImage(data: data) != nil else {
                downloaded.resolve(nil)
                return
            }
            downloaded.resolve(data)
        }.resume()
        guard let data = downloaded.wait() else { return nil }
        let stored = PrefetchWaiter<Bool>()
        cache.store(data, source: source, stableKey: cacheKey, checksum: nil, limit: 0, pinned: false) {
            stored.resolve($0)
        }
        return stored.wait() == true ? Int64(data.count) : nil
    }

    private static func queuePriority(_ value: Int64) -> Operation.QueuePriority {
        switch value {
        case 5: return .veryHigh
        case 4: return .high
        case 3: return .normal
        case 2: return .low
        default: return .veryLow
        }
    }

    private struct PrefetchError: LocalizedError {
        let message: String
        init(_ message: String) { self.message = message }
        var errorDescription: String? { message }
    }
}

private final class PrefetchWaiter<Value>: @unchecked Sendable {
    private let semaphore = DispatchSemaphore(value: 0)
    private var value: Value?

    func resolve(_ value: Value) {
        self.value = value
        semaphore.signal()
    }

    func wait() -> Value? {
        semaphore.wait()
        return value
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
        let payload = (try? WireMap.encode([
            "succeeded": .integer(succeeded),
            "failed": .integer(failed),
            "bytes": .integer(bytes),
        ])) ?? Data()
        lock.unlock()
        if done { completion(.success, payload) }
    }
}
