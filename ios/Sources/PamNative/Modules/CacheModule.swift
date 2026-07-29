import Foundation

final class CacheModule: NativeModule {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "usage":
            PamMediaDiskCache.shared.usage { usage in
                completion(.success, Self.payload(usage: usage, freedBytes: 0))
            }
        case "clear":
            do {
                let values = try WireMap.decode(payload)
                let preserveOffline: Bool
                if case let .flag(value)? = values["preserveOffline"] {
                    preserveOffline = value
                } else {
                    preserveOffline = true
                }
                PamMediaDiskCache.shared.clear(preserveOffline: preserveOffline) { usage, freedBytes in
                    completion(.success, Self.payload(usage: usage, freedBytes: freedBytes))
                }
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        default:
            completion(.failure, "Unknown cache method \(method)".data(using: .utf8) ?? Data())
        }
    }

    private static func payload(usage: PamCacheUsage, freedBytes: Int64) -> Data {
        (try? WireMap.encode([
            "fileCount": .integer(usage.fileCount),
            "freedBytes": .integer(freedBytes),
            "imageBytes": .integer(0),
            "mediaBytes": .integer(usage.totalBytes),
            "temporaryBytes": .integer(0),
            "totalBytes": .integer(usage.totalBytes),
        ])) ?? Data()
    }
}
