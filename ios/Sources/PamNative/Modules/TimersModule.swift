import Foundation

final class TimersModule: NativeModule {
    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        guard method == "after" else {
            completion(.failure, Data("Unknown timers method \(method)".utf8))
            return
        }
        do {
            let values = try WireMap.decode(payload)
            let milliseconds: Int64
            if case let .integer(value)? = values["milliseconds"] {
                milliseconds = min(max(value, 0), 86_400_000)
            } else {
                milliseconds = 0
            }
            DispatchQueue.main.asyncAfter(
                deadline: .now() + .milliseconds(Int(milliseconds))
            ) {
                completion(.success, Data())
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }
}
