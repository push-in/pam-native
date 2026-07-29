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

    fileprivate static func initial(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        let value = initialURL
        initialURL = nil
        lock.unlock()
        completion(.success, encoded(value ?? ""))
    }

    fileprivate static func next(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        if !values.isEmpty {
            let value = values.removeFirst()
            lock.unlock()
            completion(.success, value)
        } else if waiter != nil {
            lock.unlock()
            completion(.failure, Data("Only one deep-link listener can wait at a time".utf8))
        } else {
            waiter = completion
            lock.unlock()
        }
    }

    fileprivate static func close() {
        lock.lock()
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
