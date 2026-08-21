import Foundation

struct PamHotReloadReceipt {
    let entryPath: String
    let confirmedAtNanos: UInt64
    let bundleBytes: Int
}

struct PamHotReloadTiming {
    let durationNanos: Int64
    let bundleBytes: Int
    let failed: Bool
}

struct PamHotReloadStatisticsSnapshot {
    let sampleCount: Int
    let successfulCount: Int
    let failureCount: Int
    let p95DurationNanos: Int64?
    let p95BudgetNanos: Int64

    var p95WithinBudget: Bool? { p95DurationNanos.map { $0 <= p95BudgetNanos } }
    var failureRateBasisPoints: Int { sampleCount == 0 ? 0 : failureCount * 10_000 / sampleCount }
}

final class PamHotReloadStatistics {
    private let lock = NSLock()
    private let capacity: Int
    private let p95BudgetNanos: Int64
    private var samples: [PamHotReloadTiming] = []

    init(capacity: Int = 64, p95BudgetNanos: Int64) throws {
        guard capacity > 0, p95BudgetNanos > 0 else { throw StatisticsError.invalidConfiguration }
        self.capacity = capacity
        self.p95BudgetNanos = p95BudgetNanos
    }

    func record(_ timing: PamHotReloadTiming) throws {
        guard timing.durationNanos >= 0 else { throw StatisticsError.invalidDuration }
        lock.lock()
        if samples.count == capacity { samples.removeFirst() }
        samples.append(timing)
        lock.unlock()
    }

    func snapshot() -> PamHotReloadStatisticsSnapshot {
        lock.lock()
        let captured = samples
        lock.unlock()
        let successful = captured.filter { !$0.failed }.map(\.durationNanos).sorted()
        let failures = captured.filter(\.failed).count
        let rank = successful.isEmpty ? nil : max(0, (successful.count * 95 + 99) / 100 - 1)
        return PamHotReloadStatisticsSnapshot(
            sampleCount: captured.count,
            successfulCount: successful.count,
            failureCount: failures,
            p95DurationNanos: rank.map { successful[$0] },
            p95BudgetNanos: p95BudgetNanos
        )
    }

    enum StatisticsError: Error {
        case invalidConfiguration
        case invalidDuration
    }
}

final class PamHotReloadLatency {
    private let lock = NSLock()
    private var confirmedAtNanos: UInt64 = 0
    private var bundleBytes = 0

    func begin(confirmedAtNanos: UInt64, bundleBytes: Int) throws {
        guard confirmedAtNanos > 0 else { throw HotReloadError.invalidMonotonicTime }
        guard (1...PamHotReloadClient.maximumBundleBytes).contains(bundleBytes) else {
            throw HotReloadError.invalidBundleSize
        }
        lock.lock()
        self.confirmedAtNanos = confirmedAtNanos
        self.bundleBytes = bundleBytes
        lock.unlock()
    }

    func complete(completedAtNanos: UInt64, failed: Bool) -> PamHotReloadTiming? {
        lock.lock()
        defer { lock.unlock() }
        guard confirmedAtNanos > 0 else { return nil }
        let elapsed = completedAtNanos >= confirmedAtNanos
            ? completedAtNanos - confirmedAtNanos
            : 0
        let timing = PamHotReloadTiming(
            durationNanos: Int64(clamping: elapsed),
            bundleBytes: bundleBytes,
            failed: failed
        )
        confirmedAtNanos = 0
        bundleBytes = 0
        return timing
    }
}

final class PamHotReloadClient: NSObject, URLSessionDataDelegate, URLSessionTaskDelegate {
    static let maximumBundleBytes = 16 * 1024 * 1024

    typealias ReloadCallback = (PamHotReloadReceipt) -> Void
    typealias ErrorCallback = (String) -> Void

    private enum RequestKind {
        case status
        case bundle(version: String, confirmedAtNanos: UInt64)

        var limit: Int {
            switch self {
            case .status: return 128
            case .bundle: return PamHotReloadClient.maximumBundleBytes
            }
        }
    }

    private struct PendingRequest {
        let kind: RequestKind
        var data = Data()
    }

    private let baseURL: URL
    private let destination: URL
    private let onReload: ReloadCallback
    private let onError: ErrorCallback
    private let queue = DispatchQueue(label: "dev.pam.native.hot-reload", qos: .utility)
    private var session: URLSession!
    private var pollWorkItem: DispatchWorkItem?
    private var requests: [Int: PendingRequest] = [:]
    private var version: String?
    private var closed = false

    init(baseURL: URL, destination: URL, onReload: @escaping ReloadCallback, onError: @escaping ErrorCallback) {
        self.baseURL = baseURL
        self.destination = destination
        self.onReload = onReload
        self.onError = onError
        super.init()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 1
        configuration.timeoutIntervalForResource = 2
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }

    func start() {
        queue.async { [weak self] in self?.schedulePoll(after: 0.2) }
    }

    func close() {
        queue.sync {
            guard !closed else { return }
            closed = true
            pollWorkItem?.cancel()
            pollWorkItem = nil
            requests.removeAll()
            session.invalidateAndCancel()
        }
    }

    private func schedulePoll(after delay: TimeInterval) {
        guard !closed else { return }
        pollWorkItem?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.requestStatus() }
        pollWorkItem = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func requestStatus() {
        guard !closed else { return }
        var components = URLComponents(url: baseURL.appendingPathComponent("status"), resolvingAgainstBaseURL: false)
        if let version { components?.queryItems = [URLQueryItem(name: "version", value: version)] }
        guard let url = components?.url else {
            fail("Invalid hot reload server URL")
            return
        }
        startRequest(url: url, kind: .status)
    }

    private func requestBundle(version: String, confirmedAtNanos: UInt64) {
        var components = URLComponents(url: baseURL.appendingPathComponent("bundle"), resolvingAgainstBaseURL: false)
        components?.queryItems = [URLQueryItem(name: "version", value: version)]
        guard let url = components?.url else {
            fail("Invalid hot reload bundle URL")
            return
        }
        startRequest(url: url, kind: .bundle(version: version, confirmedAtNanos: confirmedAtNanos))
    }

    private func startRequest(url: URL, kind: RequestKind) {
        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
        let task = session.dataTask(with: request)
        requests[task.taskIdentifier] = PendingRequest(kind: kind)
        task.resume()
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        queue.async { [weak self] in
            guard let self, var pending = self.requests[dataTask.taskIdentifier] else {
                completionHandler(.cancel)
                return
            }
            guard let response = response as? HTTPURLResponse, response.statusCode == 200 else {
                self.requests.removeValue(forKey: dataTask.taskIdentifier)
                completionHandler(.cancel)
                self.fail("Hot reload server returned an invalid response")
                return
            }
            let expected = response.expectedContentLength
            guard expected < 0 || expected <= pending.kind.limit else {
                self.requests.removeValue(forKey: dataTask.taskIdentifier)
                completionHandler(.cancel)
                self.fail("Hot reload response is too large")
                return
            }
            pending.data.reserveCapacity(expected > 0 ? Int(expected) : 0)
            self.requests[dataTask.taskIdentifier] = pending
            completionHandler(.allow)
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        queue.async { [weak self] in
            guard let self, var pending = self.requests[dataTask.taskIdentifier] else { return }
            guard data.count <= pending.kind.limit - pending.data.count else {
                self.requests.removeValue(forKey: dataTask.taskIdentifier)
                dataTask.cancel()
                self.fail("Hot reload response is too large")
                return
            }
            pending.data.append(data)
            self.requests[dataTask.taskIdentifier] = pending
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        queue.async { [weak self] in
            guard let self, let pending = self.requests.removeValue(forKey: task.taskIdentifier) else { return }
            guard !self.closed else { return }
            if error != nil {
                self.schedulePoll(after: 0.3)
                return
            }
            self.handle(pending)
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }

    private func handle(_ request: PendingRequest) {
        switch request.kind {
        case .status:
            guard let next = String(data: request.data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  isValid(version: next) else {
                fail("Invalid hot reload version")
                return
            }
            guard next != version else {
                schedulePoll(after: 0.3)
                return
            }
            requestBundle(version: next, confirmedAtNanos: DispatchTime.now().uptimeNanoseconds)
        case let .bundle(next, confirmedAtNanos):
            do {
                let entry = try PamDevBundle.extract(request.data, to: destination.appendingPathComponent(next))
                version = next
                onReload(PamHotReloadReceipt(
                    entryPath: entry.path,
                    confirmedAtNanos: confirmedAtNanos,
                    bundleBytes: request.data.count
                ))
                cleanup(except: next)
                schedulePoll(after: 0.3)
            } catch {
                fail("Hot reload bundle activation failed: \(error)")
            }
        }
    }

    private func isValid(version: String) -> Bool {
        (16...64).contains(version.utf8.count)
            && version.utf8.allSatisfy { ($0 >= 48 && $0 <= 57) || ($0 >= 97 && $0 <= 102) }
    }

    private func cleanup(except active: String) {
        guard let children = try? FileManager.default.contentsOfDirectory(
            at: destination,
            includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey]
        ) else { return }
        for child in children where child.lastPathComponent != active {
            let values = try? child.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            if values?.isDirectory == true, values?.isSymbolicLink != true {
                try? FileManager.default.removeItem(at: child)
            }
        }
    }

    private func fail(_ message: String) {
        onError(message)
        schedulePoll(after: 0.3)
    }

    enum HotReloadError: Error {
        case invalidMonotonicTime
        case invalidBundleSize
    }
}

private typealias HotReloadError = PamHotReloadClient.HotReloadError
