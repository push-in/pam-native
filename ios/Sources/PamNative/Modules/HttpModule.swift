import Foundation

public final class HttpModule: NativeModule, ClosableNativeModule, @unchecked Sendable {
    private let session = URLSession(configuration: .default)
    private let queue = DispatchQueue(label: "pam.native.http", qos: .userInitiated)
    private var closed = false

    public init() {}

    public func invoke(method: String, payload: Data, completion: @escaping ModuleCompletion) {
        if closed {
            completion(.failure, "HTTP module is closed".data(using: .utf8) ?? Data())
            return
        }

        if method != "get" {
            completion(.failure, "Unknown HTTP method".data(using: .utf8) ?? Data())
            return
        }

        queue.async {
            do {
                let values = try WireMap.decode(payload)
                guard case let .text(urlText)? = values["url"] else {
                    throw RuntimeError("HTTP URL is required")
                }
                guard let url = URL(string: urlText), let scheme = url.scheme?.lowercased() else {
                    throw RuntimeError("Invalid URL")
                }
                #if !DEBUG
                if scheme != "https" {
                    throw RuntimeError("HTTP requests require HTTPS")
                }
                #else
                if scheme != "https" && scheme != "http" {
                    throw RuntimeError("Unsupported URL scheme")
                }
                #endif

                var request = URLRequest(url: url)
                request.httpMethod = "GET"
                request.addValue("application/json, text/plain, */*", forHTTPHeaderField: "Accept")
                request.timeoutInterval = 30

                let dataTask = self.session.dataTask(with: request) { data, response, error in
                    if let error {
                        completion(.failure, error.localizedDescription.data(using: .utf8) ?? Data())
                        return
                    }
                    let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
                    guard let bodyData = data, bodyData.count <= 900 * 1024 else {
                        completion(.failure, "HTTP response too large".data(using: .utf8) ?? Data())
                        return
                    }
                    let body = String(data: bodyData, encoding: .utf8) ?? ""
                    do {
                        let responsePayload = try WireMap.encode([
                            "statusCode": .integer(Int64(statusCode)),
                            "body": .text(body)
                        ])
                        completion(.success, responsePayload)
                    } catch {
                        completion(.failure, "Cannot encode response".data(using: .utf8) ?? Data())
                    }
                }
                dataTask.resume()
            } catch {
                completion(.failure, (error.localizedDescription).data(using: .utf8) ?? Data())
            }
        }
    }

    public func close() {
        closed = true
        session.invalidateAndCancel()
    }

    private struct RuntimeError: LocalizedError {
        let message: String
        init(_ value: String) { self.message = value }
        var errorDescription: String? { message }
    }
}
