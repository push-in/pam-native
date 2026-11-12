import Darwin
import Foundation

/// A loopback TCP listener for HTTP fixtures, on BSD sockets.
///
/// NWListener on the iOS Simulator sometimes never reaches `.ready` within the
/// test timeout on CI ("Loopback HTTP server ready" expired). This listener
/// is bound and listening when `init` returns, so tests need no readiness
/// wait, and each accepted connection is served on the caller's queue.
final class LoopbackTCPListener: @unchecked Sendable {
    let port: UInt16
    private let descriptor: Int32
    private let acceptSource: DispatchSourceRead
    private let queue: DispatchQueue

    init(queue: DispatchQueue, accepted: @escaping (LoopbackTCPConnection) -> Void) throws {
        self.queue = queue
        let socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { throw POSIXError(.init(rawValue: errno) ?? .EIO) }
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        address.sin_port = 0
        let bound = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                Darwin.bind(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0, Darwin.listen(socket, 16) == 0 else {
            let code = errno
            Darwin.close(socket)
            throw POSIXError(.init(rawValue: code) ?? .EIO)
        }
        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(socket, $0, &length)
            }
        }
        port = UInt16(bigEndian: address.sin_port)
        descriptor = socket
        acceptSource = DispatchSource.makeReadSource(fileDescriptor: socket, queue: queue)
        acceptSource.setEventHandler { [queue] in
            let client = Darwin.accept(socket, nil, nil)
            guard client >= 0 else { return }
            var noSigPipe: Int32 = 1
            setsockopt(client, SOL_SOCKET, SO_NOSIGPIPE, &noSigPipe, socklen_t(MemoryLayout<Int32>.size))
            accepted(LoopbackTCPConnection(descriptor: client, queue: queue))
        }
        acceptSource.setCancelHandler { Darwin.close(socket) }
        acceptSource.resume()
    }

    func cancel() {
        acceptSource.cancel()
    }
}

/// One accepted connection; callbacks run on the listener's queue.
final class LoopbackTCPConnection: @unchecked Sendable {
    private let descriptor: Int32
    private let queue: DispatchQueue
    private let io: DispatchQueue = DispatchQueue(label: "pam.test.loopback.io")
    private var closed = false

    init(descriptor: Int32, queue: DispatchQueue) {
        self.descriptor = descriptor
        self.queue = queue
    }

    /// Reads up to `maximumLength` bytes; `finished` is true at end of stream.
    func receive(maximumLength: Int, completion: @escaping (Data?, Bool, Error?) -> Void) {
        io.async { [self] in
            var buffer = [UInt8](repeating: 0, count: maximumLength)
            let count = Darwin.recv(descriptor, &buffer, maximumLength, 0)
            let code = errno
            queue.async {
                if count < 0 {
                    completion(nil, true, POSIXError(.init(rawValue: code) ?? .EIO))
                } else {
                    completion(Data(buffer[0..<count]), count == 0, nil)
                }
            }
        }
    }

    func send(_ data: Data, completion: @escaping () -> Void) {
        io.async { [self] in
            data.withUnsafeBytes { raw in
                var offset = 0
                while offset < raw.count {
                    let sent = Darwin.send(descriptor, raw.baseAddress! + offset, raw.count - offset, 0)
                    if sent <= 0 { break }
                    offset += sent
                }
            }
            queue.async(execute: completion)
        }
    }

    /// Closes gracefully: half-close, then drain what the client still
    /// sends (an unread request body) before closing. Closing with unread
    /// bytes sends a TCP reset, which can discard the response before the
    /// client has read it.
    func cancel() {
        io.async { [self] in
            guard !closed else { return }
            closed = true
            Darwin.shutdown(descriptor, SHUT_WR)
            var timeout = timeval(tv_sec: 1, tv_usec: 0)
            setsockopt(descriptor, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
            var buffer = [UInt8](repeating: 0, count: 16_384)
            while Darwin.recv(descriptor, &buffer, buffer.count, 0) > 0 {}
            Darwin.close(descriptor)
        }
    }
}
