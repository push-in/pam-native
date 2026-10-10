import CoreLocation
import Foundation
import UIKit

/// Why a location call failed, sent to PHP as "<code>: <detail>" (1.35.0).
/// Same codes and order as Android's `LocationFailure` and PHP's `LocationError` (1...4).
enum LocationFailure: String, CaseIterable {
    case permission
    case disabled
    case unavailable
    case timeout

    func payload(_ detail: String) -> Data {
        Data("\(rawValue): \(detail)".utf8)
    }

    /// Core Location errors: denied → permission, everything else unavailable.
    static func from(_ error: Error) -> LocationFailure {
        if let clError = error as? CLError, clError.code == .denied {
            return .permission
        }
        return .unavailable
    }
}

/// `Location::requestServices()` result (`Pam\Native\LocationServicesResult`).
enum LocationServicesResult: Int64 {
    case enabled = 1
    case denied = 2
    case unavailable = 3
}

final class LocationModule: NSObject, NativeModule, ClosableNativeModule, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private var completion: ModuleCompletion?
    private var generation = 0
    private var nextWatch = 1
    private var watches: [Int: LocationWatch] = [:]

    override init() {
        super.init()
        manager.delegate = self
    }

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "watch":
            Self.withServicesState { enabled in self.watch(payload, servicesEnabled: enabled, completion) }
            return
        case "next":
            DispatchQueue.main.async {
                guard let id = Self.subscription(payload), let watch = self.watches[id] else {
                    completion(.failure, LocationFailure.unavailable.payload("Unknown location subscription"))
                    return
                }
                watch.channel.next(completion)
            }
            return
        case "stop":
            DispatchQueue.main.async {
                if let id = Self.subscription(payload) { self.stop(id) }
                completion(.success, Data())
            }
            return
        case "lastKnown":
            DispatchQueue.main.async { self.lastKnown(completion) }
            return
        case "servicesEnabled":
            // locationServicesEnabled() may block: never on the main thread.
            DispatchQueue.global(qos: .userInitiated).async {
                Self.complete(completion, ["enabled": .flag(CLLocationManager.locationServicesEnabled())])
            }
            return
        case "requestServices":
            // iOS has no "turn on location" dialog for apps: the user must use Settings.
            DispatchQueue.global(qos: .userInitiated).async {
                let result: LocationServicesResult = CLLocationManager.locationServicesEnabled()
                    ? .enabled
                    : .unavailable
                Self.complete(completion, ["result": .integer(result.rawValue)])
            }
            return
        case "openSettings":
            DispatchQueue.main.async {
                // Apps may only open their own Settings page (Location Services is under Privacy).
                guard let url = URL(string: UIApplication.openSettingsURLString) else {
                    Self.complete(completion, ["opened": .flag(false)])
                    return
                }
                UIApplication.shared.open(url) { opened in
                    Self.complete(completion, ["opened": .flag(opened)])
                }
            }
            return
        default:
            break
        }
        guard method == "current" else {
            completion(.failure, LocationFailure.unavailable.payload("Unknown location method \(method)"))
            return
        }
        do {
            let values = try WireMap.decode(payload)
            let highAccuracy = values.flag("highAccuracy", fallback: true)
            let timeout = values.integer("timeoutMs", fallback: 10_000)
                .clamped(to: 1_000...60_000)
            let maximumAge = values.integer("maximumAgeMs", fallback: 30_000)
                .clamped(to: 0...300_000)
            Self.withServicesState { enabled in
                self.current(
                    highAccuracy: highAccuracy,
                    timeoutMs: timeout,
                    maximumAgeMs: maximumAge,
                    servicesEnabled: enabled,
                    completion: completion
                )
            }
        } catch {
            completion(.failure, LocationFailure.unavailable.payload(error.localizedDescription))
        }
    }

    private var authorized: Bool {
        manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways
    }

    /// The switch first: with Location Services off iOS reports every app as `.denied`.
    private func readinessFailure(servicesEnabled: Bool) -> Data? {
        if !servicesEnabled {
            return LocationFailure.disabled.payload("No enabled location provider")
        }
        if !authorized {
            return LocationFailure.permission.payload("Location permission is required")
        }
        return nil
    }

    /// Reads `locationServicesEnabled()` off the main thread (it may block), then continues on main.
    private static func withServicesState(_ body: @escaping (Bool) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let enabled = CLLocationManager.locationServicesEnabled()
            DispatchQueue.main.async { body(enabled) }
        }
    }

    private func lastKnown(_ completion: @escaping ModuleCompletion) {
        guard authorized else {
            completion(.failure, LocationFailure.permission.payload("Location permission is required"))
            return
        }
        guard let location = manager.location else {
            completion(.failure, LocationFailure.unavailable.payload("No last known location"))
            return
        }
        finish(location, completion)
    }

    private static func complete(_ completion: ModuleCompletion, _ values: [String: WireValue]) {
        do {
            completion(.success, try WireMap.encode(values))
        } catch {
            completion(.failure, LocationFailure.unavailable.payload(error.localizedDescription))
        }
    }

    private func current(
        highAccuracy: Bool,
        timeoutMs: Int64,
        maximumAgeMs: Int64,
        servicesEnabled: Bool,
        completion: @escaping ModuleCompletion
    ) {
        guard self.completion == nil else {
            completion(.failure, LocationFailure.unavailable.payload("A location request is already active"))
            return
        }
        if let failure = readinessFailure(servicesEnabled: servicesEnabled) {
            completion(.failure, failure)
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
            pending(.failure, LocationFailure.timeout.payload("Timed out while obtaining location"))
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
        completion(.failure, LocationFailure.from(error).payload(error.localizedDescription))
    }

    func close() {
        generation += 1
        completion = nil
        manager.stopUpdatingLocation()
        DispatchQueue.main.async {
            self.watches.keys.forEach(self.stop)
        }
    }

    /// React Native watchPosition: one CLLocationManager per subscription,
    /// woken after `distanceFilterMeters`; fixes are buffered in a WatchChannel.
    private func watch(_ payload: Data, servicesEnabled: Bool, _ completion: @escaping ModuleCompletion) {
        do {
            let values = try WireMap.decode(payload)
            if let failure = readinessFailure(servicesEnabled: servicesEnabled) {
                completion(.failure, failure)
                return
            }
            let distance: Double
            switch values["distanceFilterMeters"] {
            case let .decimal(value)?: distance = value
            case let .integer(value)?: distance = Double(value)
            default: distance = 0
            }
            let watch = LocationWatch(
                highAccuracy: values.flag("highAccuracy", fallback: true),
                distanceFilter: min(max(distance, 0), 100_000)
            )
            let id = nextWatch
            nextWatch += 1
            watches[id] = watch
            watch.start()
            completion(.success, try WireMap.encode(["subscription": .integer(Int64(id))]))
        } catch {
            completion(.failure, LocationFailure.unavailable.payload(error.localizedDescription))
        }
    }

    private func stop(_ id: Int) {
        watches.removeValue(forKey: id)?.stop()
    }

    private static func subscription(_ payload: Data) -> Int? {
        guard let values = try? WireMap.decode(payload),
              case let .integer(value)? = values["subscription"] else { return nil }
        return Int(value)
    }

    fileprivate static func encode(_ location: CLLocation) throws -> Data {
        try WireMap.encode([
            "latitude": .decimal(location.coordinate.latitude),
            "longitude": .decimal(location.coordinate.longitude),
            "accuracy": .decimal(max(0, location.horizontalAccuracy)),
            "altitude": .decimal(location.altitude),
            "speed": .decimal(max(0, location.speed)),
            "bearing": .decimal(max(0, location.course)),
            "timestamp": .integer(Int64(location.timestamp.timeIntervalSince1970 * 1_000)),
        ])
    }

    private func finish(_ location: CLLocation, _ completion: @escaping ModuleCompletion) {
        do {
            completion(.success, try Self.encode(location))
        } catch {
            completion(.failure, LocationFailure.unavailable.payload(error.localizedDescription))
        }
    }
}

private final class LocationWatch: NSObject, CLLocationManagerDelegate {
    let channel = WatchChannel()
    private let manager = CLLocationManager()

    init(highAccuracy: Bool, distanceFilter: Double) {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = highAccuracy ? kCLLocationAccuracyBest : kCLLocationAccuracyHundredMeters
        manager.distanceFilter = distanceFilter > 0 ? distanceFilter : kCLDistanceFilterNone
    }

    func start() {
        manager.startUpdatingLocation()
    }

    func stop() {
        manager.stopUpdatingLocation()
        manager.delegate = nil
        channel.close()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last,
              let data = try? LocationModule.encode(location) else { return }
        channel.offer(data)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Transient (kCLErrorLocationUnknown): Core Location keeps trying.
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
