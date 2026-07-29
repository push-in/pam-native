import CoreLocation
import Foundation

final class LocationModule: NSObject, NativeModule, ClosableNativeModule, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var completion: ModuleCompletion?
    private var generation = 0

    override init() {
        super.init()
        manager.delegate = self
    }

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        guard method == "current" else {
            completion(.failure, Data("Unknown location method \(method)".utf8))
            return
        }
        do {
            let values = try WireMap.decode(payload)
            let highAccuracy = values.flag("highAccuracy", fallback: true)
            let timeout = values.integer("timeoutMs", fallback: 10_000)
                .clamped(to: 1_000...60_000)
            let maximumAge = values.integer("maximumAgeMs", fallback: 30_000)
                .clamped(to: 0...300_000)
            DispatchQueue.main.async {
                self.current(
                    highAccuracy: highAccuracy,
                    timeoutMs: timeout,
                    maximumAgeMs: maximumAge,
                    completion: completion
                )
            }
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }

    private func current(
        highAccuracy: Bool,
        timeoutMs: Int64,
        maximumAgeMs: Int64,
        completion: @escaping ModuleCompletion
    ) {
        guard self.completion == nil else {
            completion(.failure, Data("A location request is already active".utf8))
            return
        }
        guard manager.authorizationStatus == .authorizedWhenInUse ||
                manager.authorizationStatus == .authorizedAlways else {
            completion(.failure, Data("Location permission is required".utf8))
            return
        }

        manager.desiredAccuracy = highAccuracy
            ? kCLLocationAccuracyBest
            : kCLLocationAccuracyHundredMeters
        if let cached = manager.location,
           Date().timeIntervalSince(cached.timestamp) * 1_000 <= Double(maximumAgeMs) {
            finish(cached, completion)
            return
        }

        self.completion = completion
        generation += 1
        let requestGeneration = generation
        manager.requestLocation()
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(timeoutMs))) {
            guard self.generation == requestGeneration,
                  let pending = self.completion else { return }
            self.completion = nil
            self.generation += 1
            pending(.failure, Data("Timed out while obtaining location".utf8))
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last,
              let completion else { return }
        self.completion = nil
        generation += 1
        finish(location, completion)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard let completion else { return }
        self.completion = nil
        generation += 1
        completion(.failure, Data(error.localizedDescription.utf8))
    }

    func close() {
        generation += 1
        completion = nil
        manager.stopUpdatingLocation()
    }

    private func finish(_ location: CLLocation, _ completion: @escaping ModuleCompletion) {
        do {
            completion(
                .success,
                try WireMap.encode([
                    "latitude": .decimal(location.coordinate.latitude),
                    "longitude": .decimal(location.coordinate.longitude),
                    "accuracy": .decimal(max(0, location.horizontalAccuracy)),
                    "altitude": .decimal(location.altitude),
                    "speed": .decimal(max(0, location.speed)),
                    "bearing": .decimal(max(0, location.course)),
                    "timestamp": .integer(Int64(location.timestamp.timeIntervalSince1970 * 1_000)),
                ])
            )
        } catch {
            completion(.failure, Data(error.localizedDescription.utf8))
        }
    }
}

private extension Dictionary where Key == String, Value == WireValue {
    func flag(_ key: String, fallback: Bool) -> Bool {
        guard case let .flag(value)? = self[key] else { return fallback }
        return value
    }

    func integer(_ key: String, fallback: Int64) -> Int64 {
        guard case let .integer(value)? = self[key] else { return fallback }
        return value
    }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
