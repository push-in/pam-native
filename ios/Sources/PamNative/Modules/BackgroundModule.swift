import Foundation
import UIKit

final class BackgroundModule: NativeModule, ClosableNativeModule {
    private var nextToken = 1
    private var tasks: [Int: UIBackgroundTaskIdentifier] = [:]

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        DispatchQueue.main.async {
            do {
                switch method {
                case "begin":
                    let token = self.nextToken
                    self.nextToken += 1
                    let identifier = UIApplication.shared.beginBackgroundTask(
                        withName: "PamNative-\(token)"
                    ) { [weak self] in
                        self?.end(token)
                    }
                    guard identifier != .invalid else {
                        throw BackgroundError("iOS denied the background task")
                    }
                    self.tasks[token] = identifier
                    completion(
                        .success,
                        (try? WireMap.encode(["token": .integer(Int64(token))])) ?? Data()
                    )
                case "end":
                    let values = try WireMap.decode(payload)
                    guard case let .integer(token)? = values["token"] else {
                        throw BackgroundError("Missing background token")
                    }
                    self.end(Int(token))
                    completion(.success, Data())
                default:
                    throw BackgroundError("Unknown background method \(method)")
                }
            } catch {
                completion(.failure, Data(error.localizedDescription.utf8))
            }
        }
    }

    private func end(_ token: Int) {
        guard let identifier = tasks.removeValue(forKey: token) else { return }
        UIApplication.shared.endBackgroundTask(identifier)
    }

    func close() {
        DispatchQueue.main.async {
            Array(self.tasks.keys).forEach(self.end)
        }
    }
}

private struct BackgroundError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
