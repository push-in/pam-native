import Foundation

public final class StorageModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    private let defaults = UserDefaults.standard
    private let queue = DispatchQueue(label: "pam.native.storage", qos: .utility)
    private var closed = false

    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        if closed {
            completion(.failure, "Storage module is closed".data(using: .utf8) ?? Data())
            return
        }

        queue.async {
            do {
                let values = try WireMap.decode(payload)
                guard case let .text(key)? = values["key"],
                      self.isValidKey(key) else {
                    throw RuntimeError("Storage key is required")
                }

                switch method {
                case "get":
                    if let value = self.defaults.string(forKey: key) {
                        completion(.success, try WireMap.encode(["value": .text(value)]))
                    } else {
                        completion(.success, Data())
                    }
                case "set":
                    guard case let .text(value)? = values["value"] else {
                        throw RuntimeError("Storage value is required")
                    }
                    let bytes = value.data(using: .utf8) ?? Data()
                    guard bytes.count <= 256 * 1024 else {
                        throw RuntimeError("Storage value exceeds 256 KiB")
                    }
                    self.defaults.set(value, forKey: key)
                    completion(.success, Data())
                default:
                    completion(.failure, "Unknown storage method".data(using: .utf8) ?? Data())
                }
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        }
    }

    public func close() {
        closed = true
    }

    private func isValidKey(_ key: String) -> Bool {
        guard (1...128).contains(key.count) else { return false }
        return key.allSatisfy { char in
            char.isASCII && (char.isLetter || char.isNumber || char == "_" || char == "-" || char == ".")
        }
    }

    private struct RuntimeError: LocalizedError {
        let message: String
        init(_ value: String) { self.message = value }
        var errorDescription: String? { message }
    }
}
