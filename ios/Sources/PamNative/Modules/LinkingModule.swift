import Foundation

public enum PamLinking {
    private static let lock = NSLock()
    private static var initialURL: String?
    private static var values: [Data] = []
    private static var waiter: ModuleCompletion?

    public static func captureInitial(_ url: URL?) {
        guard let value = validated(url) else { return }
        lock.lock()
        if initialURL == nil { initialURL = value }
        lock.unlock()
    }

    public static func open(_ url: URL?) {
        guard let value = validated(url) else { return }
        let payload = encoded(value)
        lock.lock()
        let callback = waiter
        if callback == nil {
            if values.count >= 32 { values.removeFirst() }
            values.append(payload)
        } else {
            waiter = nil
        }
        lock.unlock()
        callback?(.success, payload)
    }

    /// The launch URL without consuming it (exported to PHP at boot).
    public static func launchURL() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return initialURL
    }

    fileprivate static func initial(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        let value = initialURL
        initialURL = nil
        lock.unlock()
        completion(.success, encoded(value ?? ""))
    }

    /// One listener waits at a time. A new wait supersedes a stale one (a PHP
    /// request a hot reload replaced while its wait was armed or in flight):
    /// the stale waiter ends with "superseded", which PHP ignores.
    fileprivate static func next(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        let superseded = waiter
        waiter = nil
        if !values.isEmpty {
            let value = values.removeFirst()
            lock.unlock()
            superseded?(.failure, Data("superseded".utf8))
            completion(.success, value)
        } else {
            waiter = completion
            lock.unlock()
            superseded?(.failure, Data("superseded".utf8))
        }
    }

    fileprivate static func close() {
        lock.lock()
        initialURL = nil
        values.removeAll()
        let callback = waiter
        waiter = nil
        lock.unlock()
        callback?(.failure, Data("Linking module closed".utf8))
    }

    private static func validated(_ url: URL?) -> String? {
        guard let value = url?.absoluteString.trimmingCharacters(in: .whitespacesAndNewlines),
              !value.isEmpty,
              value.utf8.count <= 8_192 else {
            return nil
        }
        return value
    }

    private static func encoded(_ url: String) -> Data {
        (try? WireMap.encode(["url": .text(url)])) ?? Data()
    }
}

final class LinkingModule: NativeModule, ClosableNativeModule {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "initialUrl":
            PamLinking.initial(completion)
        case "nextUrl":
            PamLinking.next(completion)
        default:
            completion(.failure, Data("Unknown linking method \(method)".utf8))
        }
    }

    func close() {
        PamLinking.close()
    }
}
