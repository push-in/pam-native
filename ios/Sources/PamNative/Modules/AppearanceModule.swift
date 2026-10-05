import UIKit

/// Persists and applies the application's light/dark preference.
final class AppearanceModule: NativeModule {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "get":
            DispatchQueue.main.async { completion(.success, Self.snapshot()) }
        case "set":
            guard let values = try? WireMap.decode(payload),
                  case let .integer(raw)? = values["mode"],
                  PamAppearance.isValid(mode: Int(raw)) else {
                completion(.failure, Data("Appearance mode must be 1 (System), 2 (Light) or 3 (Dark)".utf8))
                return
            }
            let mode = Int(raw)
            DispatchQueue.main.async {
                PamAppearance.persist(mode)
                PamAppearance.exportEnvironment(mode: mode)
                // Trait changes propagate to view controllers, which report the
                // new metrics to PHP without recreating the hierarchy.
                PamAppearance.applyToConnectedWindows(mode: mode)
                completion(.success, Self.snapshot())
            }
        default:
            completion(.failure, Data("Unknown appearance method \(method)".utf8))
        }
    }

    private static func snapshot() -> Data {
        let mode = PamAppearance.storedMode()
        return (try? WireMap.encode([
            "mode": .integer(Int64(mode)),
            "appearance": .integer(PamAppearance.isDark(mode: mode) ? 2 : 1),
            "systemAppearance": .integer(PamAppearance.systemDark ? 2 : 1),
        ])) ?? Data()
    }
}
