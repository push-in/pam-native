import Foundation

/// Network resilience shared by images and videos, the same rules as
/// Android's `NativeMediaNetwork.kt`: transient failures (408, 429, 502,
/// 503, 504, timeouts and dropped connections) retry with exponential
/// backoff and jitter, honoring `Retry-After` (capped at 30 s); other 4xx
/// and bad bytes fail at once.
enum PamMediaRetry {
    static let maxDelay: TimeInterval = 30
    static let baseDelay: TimeInterval = 0.5
    /// About 90 s of backoff before a transient image failure is reported.
    static let maxImageRetries = 8
    /// Backed-off re-prepares of a failed remote video that stays on screen.
    static let maxPlaybackRecoveries = 4
    static let transientStatus: Set<Int> = [408, 429, 502, 503, 504]
    /// Statuses that pause every load of the host (rate limited or overloaded).
    static let pauseStatus: Set<Int> = [429, 503]

    /// `Retry-After` in delta-seconds or an HTTP date, capped at `maxDelay`; nil when absent or invalid.
    static func retryAfter(_ value: String?, now: Date = Date()) -> TimeInterval? {
        guard let text = value?.trimmingCharacters(in: .whitespaces), !text.isEmpty else { return nil }
        if text.allSatisfy({ $0.isASCII && $0.isNumber }) {
            return min(Double(text) ?? maxDelay, maxDelay)
        }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        guard let date = formatter.date(from: text) else { return nil }
        return min(max(0, date.timeIntervalSince(now)), maxDelay)
    }

    /// 0.5 s doubling to 30 s, with equal jitter (half the step plus a random half).
    static func backoff(attempt: Int, random: Double = Double.random(in: 0...1)) -> TimeInterval {
        let ceiling = min(baseDelay * pow(2, Double(min(max(attempt, 0), 16))), maxDelay)
        return ceiling / 2 + ceiling / 2 * min(max(random, 0), 1)
    }

    /// Delay before retrying an HTTP `status`, or nil when it is permanent.
    static func delay(
        status: Int,
        retryAfter: TimeInterval?,
        attempt: Int,
        random: Double = Double.random(in: 0...1)
    ) -> TimeInterval? {
        guard transientStatus.contains(status) else { return nil }
        if let retryAfter {
            return retryAfter + min(retryAfter / 5 + 0.1, 1) * random
        }
        return backoff(attempt: attempt, random: random)
    }

    /// Timeouts, lost connections, DNS and TLS hiccups of a mobile network.
    static func isTransient(_ error: Error) -> Bool {
        let error = error as NSError
        if error.domain == NSURLErrorDomain {
            return [
                NSURLErrorTimedOut,
                NSURLErrorNetworkConnectionLost,
                NSURLErrorNotConnectedToInternet,
                NSURLErrorCannotConnectToHost,
                NSURLErrorCannotFindHost,
                NSURLErrorDNSLookupFailed,
                NSURLErrorSecureConnectionFailed,
                NSURLErrorDataNotAllowed,
                NSURLErrorInternationalRoamingOff,
                NSURLErrorCallIsActive,
            ].contains(error.code)
        }
        if let underlying = error.userInfo[NSUnderlyingErrorKey] as? Error {
            return isTransient(underlying)
        }
        return false
    }

    /// Idle timeout per attempt: short first, longer on retries (URLRequest's default is 60 s).
    static func requestTimeout(attempt: Int) -> TimeInterval {
        attempt <= 0 ? 7 : attempt == 1 ? 11 : 15
    }
}

/// Process-wide pause per host, shared by images and videos: a 429/503 from
/// one request holds the other requests to that host for `Retry-After` (or a
/// growing default) instead of letting each of them spend the rate limit too.
final class PamMediaHostGate: @unchecked Sendable {
    static let shared = PamMediaHostGate()

    private struct State {
        var until: TimeInterval
        var strikes: Int
    }

    private var hosts: [String: State] = [:]
    private let lock = NSLock()

    private func now() -> TimeInterval { ProcessInfo.processInfo.systemUptime }

    func remaining(_ host: String?) -> TimeInterval {
        guard let host, !host.isEmpty else { return 0 }
        lock.lock()
        defer { lock.unlock() }
        guard let state = hosts[host.lowercased()] else { return 0 }
        return max(0, state.until - now())
    }

    /// `host` answered 429/503: pause it for `retryAfter`, or 1 s doubling per strike.
    @discardableResult
    func pause(_ host: String?, retryAfter: TimeInterval?) -> TimeInterval {
        guard let host, !host.isEmpty else { return 0 }
        let key = host.lowercased()
        lock.lock()
        defer { lock.unlock() }
        let previous = hosts[key]
        let strikes = (previous?.strikes ?? 0) + 1
        let pause = min(max(retryAfter ?? PamMediaRetry.backoff(attempt: strikes, random: 1), 0.25), PamMediaRetry.maxDelay)
        hosts[key] = State(until: max(previous?.until ?? 0, now() + pause), strikes: strikes)
        return pause
    }

    /// A request to `host` succeeded: once its pause is over, strikes start again from zero.
    func succeeded(_ host: String?) {
        guard let host, !host.isEmpty else { return }
        let key = host.lowercased()
        lock.lock()
        defer { lock.unlock() }
        if let state = hosts[key], state.until <= now() { hosts[key] = nil }
    }
}
