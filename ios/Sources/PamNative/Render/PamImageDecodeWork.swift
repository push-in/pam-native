import Foundation

/// A view's image demand can be revoked on main while its pixels wait off-main.
/// Completed operations release their encoded bytes instead of living with the node.
final class PamImageDecodeWork: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    private var operations: [BlockOperation] = []

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    func submit(on queue: OperationQueue, _ work: @escaping @Sendable () -> Void) {
        let operation = BlockOperation { [weak self] in
            guard let self, !self.isCancelled else { return }
            work()
        }
        operation.completionBlock = { [weak self, weak operation] in
            guard let self, let operation else { return }
            self.lock.lock()
            self.operations.removeAll { $0 === operation }
            self.lock.unlock()
        }
        lock.lock()
        guard !cancelled else {
            lock.unlock()
            return
        }
        operations.append(operation)
        lock.unlock()
        queue.addOperation(operation)
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let pending = operations
        operations.removeAll()
        lock.unlock()
        for operation in pending { operation.cancel() }
    }
}
