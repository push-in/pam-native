import Foundation
import Network
import UIKit

final class DeviceModule: NativeModule, ClosableNativeModule {
    private let lock = NSLock()
    private var nextId = 1
    private var watches: [Int: DeviceWatch] = [:]

    func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        switch method {
        case "status":
            snapshot(completion)
        case "watch":
            do { try start(payload, completion) }
            catch { completion(.failure, Data(error.localizedDescription.utf8)) }
        case "next":
            do { try watch(payload).channel.next(completion) }
            catch { completion(.failure, Data(error.localizedDescription.utf8)) }
        case "stop":
            do {
                stop(try id(payload))
                completion(.success, Data())
            } catch {
                completion(.failure, Data(error.localizedDescription.utf8))
            }
        default:
            completion(.failure, Data("Unknown device method \(method)".utf8))
        }
    }

    private func snapshot(_ completion: @escaping ModuleCompletion) {
        let monitor = NWPathMonitor()
        let queue = DispatchQueue(label: "dev.pam.native.device-status")
        monitor.pathUpdateHandler = { path in
            monitor.cancel()
            DispatchQueue.main.async {
                UIDevice.current.isBatteryMonitoringEnabled = true
                let type: Int64
                if path.status != .satisfied {
                    type = 1
                } else if path.usesInterfaceType(.wifi) {
                    type = 2
                } else if path.usesInterfaceType(.cellular) {
                    type = 3
                } else if path.usesInterfaceType(.wiredEthernet) {
                    type = 4
                } else {
                    type = 5
                }
                let charging = UIDevice.current.batteryState == .charging ||
                    UIDevice.current.batteryState == .full
                let result = (try? WireMap.encode([
                    "batteryLevel": .decimal(Double(UIDevice.current.batteryLevel)),
                    "charging": .flag(charging),
                    "networkType": .integer(type),
                    "expensiveNetwork": .flag(path.isExpensive),
                    "lowPowerMode": .flag(ProcessInfo.processInfo.isLowPowerModeEnabled),
                ])) ?? Data()
                completion(.success, result)
            }
        }
        monitor.start(queue: queue)
    }

    private func start(_ payload: Data, _ completion: @escaping ModuleCompletion) throws {
        let values = try WireMap.decode(payload)
        let interval = min(max(values["intervalMs"]?.deviceInteger ?? 1_000, 250), 60_000)
        let channel = WatchChannel()
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        lock.lock()
        let id = nextId
        nextId += 1
        watches[id] = DeviceWatch(channel: channel, timer: timer)
        lock.unlock()
        timer.schedule(deadline: .now(), repeating: .milliseconds(Int(interval)))
        timer.setEventHandler { [weak self, weak channel] in
            self?.snapshot { status, data in
                if status == .success { channel?.offer(data) }
            }
        }
        timer.resume()
        completion(.success, try WireMap.encode(["subscription": .integer(Int64(id))]))
    }

    private func watch(_ payload: Data) throws -> DeviceWatch {
        let id = try id(payload)
        lock.lock()
        defer { lock.unlock() }
        guard let watch = watches[id] else { throw DeviceModuleError("Unknown device subscription") }
        return watch
    }

    private func id(_ payload: Data) throws -> Int {
        let values = try WireMap.decode(payload)
        guard case let .integer(value)? = values["subscription"] else {
            throw DeviceModuleError("Missing device subscription")
        }
        return Int(value)
    }

    private func stop(_ id: Int) {
        lock.lock()
        let watch = watches.removeValue(forKey: id)
        lock.unlock()
        watch?.timer.cancel()
        watch?.channel.close()
    }

    func close() {
        lock.lock()
        let ids = Array(watches.keys)
        lock.unlock()
        ids.forEach { stop($0) }
    }
}

private struct DeviceWatch {
    let channel: WatchChannel
    let timer: DispatchSourceTimer
}

private struct DeviceModuleError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

private extension WireValue {
    var deviceInteger: Int64? {
        if case let .integer(value) = self { return value }
        return nil
    }
}
