import Foundation

struct PamHotReloadReceipt {
    let entryPath: String
    let confirmedAtNanos: UInt64
    /// Bytes downloaded for this version (0 when every file was on the device).
    let bundleBytes: Int
    /// Runs on the PHP worker between the previous and the next request.
    let beforeNextRequest: (() -> Void)?
    let previousRequestReleased: () -> Void
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
        guard bundleBytes >= 0 else { throw HotReloadError.invalidBundleSize }
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

/// Debug-only client of the `pam dev` / `pam ios:dev` sync server (the
/// simulator shares the Mac's loopback). Same protocol as Android: long-poll
/// `/status`, one-round-trip deltas once `live/` runs, only missing files are
/// downloaded (streamed to disk, no bundle size limit), and transport errors
/// back off and resume on their own.
final class PamHotReloadClient {
    typealias ReloadCallback = (PamHotReloadReceipt) -> Void
    typealias ErrorCallback = (String) -> Void

    private let baseURL: URL
    private let store: PamHotReloadStore
    private let onReload: ReloadCallback
    private let onError: ErrorCallback
    private let queue = DispatchQueue(label: "dev.pam.native.hot-reload", qos: .utility)
    private let session: URLSession
    private let stateLock = NSLock()
    private var closed = false
    private var runningLive: Bool
    private var failures = 0
    private var pendingReport: (version: String, files: Int, bytes: Int64)?

    /// - Parameters:
    ///   - destination: the development root (`live/`, `live.manifest`, `objects/`).
    ///   - initialEntryPath: the entry the runtime runs now; its directory
    ///     provides files the device already holds for the first sync.
    init(
        baseURL: URL,
        destination: URL,
        initialEntryPath: String?,
        onReload: @escaping ReloadCallback,
        onError: @escaping ErrorCallback
    ) {
        self.baseURL = baseURL
        self.onReload = onReload
        self.onError = onError
        let liveEntry = destination.appendingPathComponent("live/index.php", isDirectory: false).standardizedFileURL.path
        let initialPath = initialEntryPath.map { URL(fileURLWithPath: $0).standardizedFileURL.path }
        let isLive = initialPath != nil && initialPath == liveEntry
        let bootDirectory = isLive ? nil : initialEntryPath.map { URL(fileURLWithPath: $0).deletingLastPathComponent() }
        store = PamHotReloadStore(root: destination, localSources: bootDirectory.map { [$0] } ?? [])
        runningLive = isLive
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 25
        configuration.timeoutIntervalForResource = 600
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.urlCache = nil
        session = URLSession(configuration: configuration)
    }

    func start() {
        queue.asyncAfter(deadline: .now() + 0.2) { [weak self] in self?.sync() }
    }

    func close() {
        stateLock.lock()
        closed = true
        stateLock.unlock()
        session.invalidateAndCancel()
    }

    private var isClosed: Bool {
        stateLock.lock(); defer { stateLock.unlock() }
        return closed
    }

    /// The runtime committed (or failed) the first frame of the last reload.
    func reportReloaded(durationNanos: Int64, failed: Bool) {
        queue.async { [weak self] in
            guard let self, let report = self.pendingReport, !self.isClosed else { return }
            self.pendingReport = nil
            let milliseconds = durationNanos / 1_000_000
            var request = URLRequest(url: self.url(
                "reloaded",
                ["version": report.version, "outcome": failed ? "2" : "1", "ms": "\(milliseconds)",
                 "bytes": "\(report.bytes)", "files": "\(report.files)"]
            ))
            request.httpMethod = "POST"
            request.timeoutInterval = 2
            self.session.dataTask(with: request).resume()
        }
    }

    private func url(_ path: String, _ query: [String: String]) -> URL {
        var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        components.queryItems = query.keys.sorted().map { URLQueryItem(name: $0, value: query[$0]) }
        return components.url!
    }

    private func schedule(after delay: TimeInterval) {
        guard !isClosed else { return }
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in self?.sync() }
    }

    private func sync() {
        guard !isClosed else { return }
        if store.hasPendingRequest {
            schedule(after: 0.05)
            return
        }
        do {
            try syncOnce()
            failures = 0
            schedule(after: 0)
        } catch PamHotReloadTransport.versionChanged {
            schedule(after: 0)
        } catch PamHotReloadTransport.unavailable {
            failures += 1
            schedule(after: backoff())
        } catch {
            guard !isClosed else { return }
            failures += 1
            onError("Hot reload failed: \(error)")
            schedule(after: backoff())
        }
    }

    private func backoff() -> TimeInterval {
        Self.backoffDelay(failures: failures)
    }

    /// Retry delay after `failures` consecutive failed syncs: a quick ramp to
    /// 2 s covers a dev server restart (about 10 s), then the delay doubles to
    /// a 30 s ceiling, so a debug build with no server running stops waking
    /// the radio and the CPU every 2 s for the life of the app.
    static func backoffDelay(failures: Int) -> TimeInterval {
        let count = max(failures, 1)
        if count <= quickRetries {
            return min(2.0, 0.3 * pow(2.0, Double(min(count - 1, 3))))
        }
        return min(maxBackoff, 2.0 * pow(2.0, Double(min(count - quickRetries, 5))))
    }

    static let quickRetries = 6
    static let maxBackoff: TimeInterval = 30

    private func syncOnce() throws {
        let base = store.currentLiveManifest
        let known = runningLive ? base?.version : nil
        let deltaCapable = known != nil
        var query = ["version": known ?? "", "wait": "1"]
        if deltaCapable { query["delta"] = "1" }
        let (file, _) = try download(URLRequest(url: url("status", query)))
        defer { try? FileManager.default.removeItem(at: file) }
        let confirmedAtNanos = DispatchTime.now().uptimeNanoseconds
        let handle = try FileHandle(forReadingFrom: file)
        defer { try? handle.close() }
        let head = try readLine(handle)
        var downloaded: Int64 = 0
        let next: String
        var manifest: PamHotReloadManifest?
        if head.hasPrefix("PNS1 ") {
            let fields = head.dropFirst(5).split(separator: " ")
            guard fields.count == 2, deltaCapable, let base, let length = Int(fields[1]),
                  (0...(4 * 1024 * 1024)).contains(length) else {
                throw PamHotReloadSyncError.invalidManifest
            }
            next = String(fields[0])
            let delta = String(decoding: try handle.read(upToCount: length) ?? Data(), as: UTF8.self)
            guard delta.utf8.count == length else { throw PamHotReloadSyncError.invalidManifest }
            manifest = try PamHotReloadManifest.parse(delta, expectedVersion: next, base: base)
            let added = Set(delta.split(separator: "\n").filter { $0.hasPrefix("+ ") }.map { String($0.dropFirst(2).prefix(64)) })
            downloaded = try store.receive(from: handle, expected: added)
        } else {
            next = head.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard !next.isEmpty, next != known else { return }
        guard PamHotReloadManifest.isVersion(next) else { throw PamHotReloadSyncError.invalidManifest }
        if manifest == nil {
            var manifestQuery = ["version": next]
            if let base { manifestQuery["base"] = base.version }
            let (body, _) = try download(URLRequest(url: url("manifest", manifestQuery)))
            defer { try? FileManager.default.removeItem(at: body) }
            manifest = try PamHotReloadManifest.parse(
                String(contentsOf: body, encoding: .utf8), expectedVersion: next, base: base
            )
        }
        let plan = store.plan(manifest!)
        if !plan.missing.isEmpty {
            var request = URLRequest(url: url("objects", [:]))
            request.httpMethod = "POST"
            request.httpBody = Data(plan.missing.sorted().map { $0 + "\n" }.joined().utf8)
            let (objects, _) = try download(request)
            defer { try? FileManager.default.removeItem(at: objects) }
            let objectHandle = try FileHandle(forReadingFrom: objects)
            defer { try? objectHandle.close() }
            downloaded += try store.receive(from: objectHandle, expected: plan.missing)
        }
        if plan.isEmpty && runningLive { return }
        try store.begin(plan)
        let inPlace = runningLive
        if !inPlace {
            do { try store.apply(plan) } catch {
                store.abandon(next)
                throw error
            }
        }
        pendingReport = (next, plan.changed.count + plan.removed.count, plan.changedBytes)
        runningLive = true
        let store = self.store
        let prepare: (() -> Void)? = inPlace ? { _ = try? store.apply(plan) } : nil
        onReload(PamHotReloadReceipt(
            entryPath: store.liveEntry.path,
            confirmedAtNanos: confirmedAtNanos,
            bundleBytes: Int(clamping: downloaded),
            beforeNextRequest: prepare,
            previousRequestReleased: { [weak self] in
                self?.queue.async { store.previousRequestReleased(next) }
            }
        ))
    }

    /// Downloads to a temporary file (no response size limit, bounded memory).
    private func download(_ request: URLRequest) throws -> (URL, HTTPURLResponse) {
        var result: Result<(URL, HTTPURLResponse), Error> = .failure(PamHotReloadTransport.unavailable)
        let done = DispatchSemaphore(value: 0)
        let task = session.downloadTask(with: request) { location, response, error in
            defer { done.signal() }
            guard error == nil, let location, let response = response as? HTTPURLResponse else {
                result = .failure(PamHotReloadTransport.unavailable)
                return
            }
            if response.statusCode == 409 {
                result = .failure(PamHotReloadTransport.versionChanged)
                return
            }
            guard (200...299).contains(response.statusCode) else {
                result = .failure(PamHotReloadSyncError.invalidManifest)
                return
            }
            // The system deletes `location` when this handler returns.
            let kept = FileManager.default.temporaryDirectory
                .appendingPathComponent("pam-hot-reload-\(UUID().uuidString)", isDirectory: false)
            do {
                try FileManager.default.moveItem(at: location, to: kept)
                result = .success((kept, response))
            } catch {
                result = .failure(error)
            }
        }
        task.resume()
        done.wait()
        return try result.get()
    }

    private func readLine(_ handle: FileHandle) throws -> String {
        var bytes = Data()
        while bytes.count < 256, let byte = try handle.read(upToCount: 1), let value = byte.first {
            if value == 10 { break }
            bytes.append(value)
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    enum HotReloadError: Error {
        case invalidMonotonicTime
        case invalidBundleSize
    }
}

private enum PamHotReloadTransport: Error {
    case unavailable
    case versionChanged
}

private typealias HotReloadError = PamHotReloadClient.HotReloadError
