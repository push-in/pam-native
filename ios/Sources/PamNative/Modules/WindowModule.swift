import Foundation
import UIKit

/// Window privacy controls. iOS has no public FLAG_SECURE equivalent, so secure
/// mode reports `supported: false` and leaves the window unchanged.
final class WindowModule: NativeModule {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "secure", "isSecure":
            completion(.success, (try? WireMap.encode([
                "enabled": .flag(false),
                "supported": .flag(false),
            ])) ?? Data())
        default:
            completion(.failure, Data("Unknown window method \(method)".utf8))
        }
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

/// Image prefetching into the renderer cache is Android-only in this release.
final class ImagePrefetchModule: NativeModule {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        completion(.failure, Data("Image prefetch is not available on iOS yet".utf8))
    }
}
