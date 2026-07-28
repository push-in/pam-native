import Foundation

final class WatchChannel {
    private let lock = NSLock()
    private var values: [Data] = []
    private var waiter: ModuleCompletion?
    private var closed = false

    func next(_ completion: @escaping ModuleCompletion) {
        lock.lock()
        if closed {
            lock.unlock()
            completion(.failure, Data("Observation is closed".utf8))
        } else if !values.isEmpty {
            let value = values.removeFirst()
            lock.unlock()
            completion(.success, value)
        } else if waiter != nil {
            lock.unlock()
            completion(.failure, Data("Observation already has a pending read".utf8))
        } else {
            waiter = completion
            lock.unlock()
        }
    }

    func offer(_ value: Data) {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        let callback = waiter
        if callback == nil {
            if values.count >= 4 { values.removeFirst() }
            values.append(value)
        } else {
            waiter = nil
        }
        lock.unlock()
        callback?(.success, value)
    }

    func close() {
        lock.lock()
        guard !closed else {
            lock.unlock()
            return
        }
        closed = true
        values.removeAll()
        let callback = waiter
        waiter = nil
        lock.unlock()
        callback?(.failure, Data("Observation stopped".utf8))
    }
}
