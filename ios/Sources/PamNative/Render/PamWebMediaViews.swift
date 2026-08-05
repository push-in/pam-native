import AVFoundation
import AVKit
import Foundation
import UIKit
import WebKit

final class PamWebView: WKWebView, WKNavigationDelegate, WKScriptMessageHandler {
    var onLoad: (() -> Void)?
    var onError: ((String) -> Void)?
    var onMessage: ((String) -> Void)?
    private var source = ""
    private var injectedJavaScript = ""
    private var javaScriptEnabled = true
    private var allowedHosts: Set<String> = []
    private var loadTimeout: DispatchWorkItem?

    init() {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.addUserScript(
            WKUserScript(
                source: """
                window.PamNative = window.PamNative || {};
                window.PamNative.postMessage = function(value) {
                    window.webkit.messageHandlers.pam.postMessage(String(value));
                };
                """,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false
            )
        )
        super.init(frame: .zero, configuration: configuration)
        navigationDelegate = self
        configuration.userContentController.add(WeakWebMessageHandler(self), name: "pam")
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setSource(_ value: String) {
        guard value != source else { return }
        source = value
        guard !value.isEmpty else {
            cancelLoadTimeout()
            stopLoading()
            return
        }
        scheduleLoadTimeout()
        if value.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("<") {
            loadHTMLString(value, baseURL: nil)
        } else if let url = URL(string: value) {
            load(URLRequest(url: url))
        }
    }

    func setJavaScriptEnabled(_ value: Bool) {
        javaScriptEnabled = value
        configuration.defaultWebpagePreferences.allowsContentJavaScript = value
    }

    func setDomStorageEnabled(_: Bool) {}
    func setUserAgent(_ value: String) { customUserAgent = value.isEmpty ? nil : value }
    func setInjectedJavaScript(_ value: String) { injectedJavaScript = value }
    func setAllowsInlineMedia(_ value: Bool) { configuration.allowsInlineMediaPlayback = value }
    func setAllowedHosts(_ value: String) {
        allowedHosts = Set(
            value.split(separator: "\n")
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }
                .filter { !$0.isEmpty }
        )
        if let current = url, !allows(current) {
            stopLoading()
            onError?("WebView navigation was blocked by the allowed-host policy")
        }
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard navigationAction.targetFrame?.isMainFrame != false,
              let target = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }
        if allows(target) {
            decisionHandler(.allow)
        } else {
            cancelLoadTimeout()
            onError?("WebView blocked navigation to \(target.host ?? "unknown host")")
            decisionHandler(.cancel)
        }
    }

    private func allows(_ url: URL) -> Bool {
        if allowedHosts.isEmpty || url.scheme == "about" || url.scheme == "data" {
            return true
        }
        guard let host = url.host?.lowercased() else { return false }
        return allowedHosts.contains(host)
    }

    private func scheduleLoadTimeout() {
        cancelLoadTimeout()
        let timeout = DispatchWorkItem { [weak self] in
            self?.stopLoading()
            self?.onError?("WebView navigation timed out after 30 seconds")
        }
        loadTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 30, execute: timeout)
    }

    private func cancelLoadTimeout() {
        loadTimeout?.cancel()
        loadTimeout = nil
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        cancelLoadTimeout()
        if javaScriptEnabled && !injectedJavaScript.isEmpty {
            evaluateJavaScript(injectedJavaScript)
        }
        onLoad?()
    }

    func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        cancelLoadTimeout()
        onError?(error.localizedDescription)
    }

    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        onMessage?(String(describing: message.body))
    }
}

private final class WeakWebMessageHandler: NSObject, WKScriptMessageHandler {
    private weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        target?.userContentController(userContentController, didReceive: message)
    }
}

final class PamMediaView: UIView {
    private let controller = AVPlayerViewController()
    private var player: AVPlayer?
    private var source = ""
    private var autoPlay = false
    private var looping = false
    private var muted = false
    private var volume: Float = 1
    private var currentTime = 0.0
    private var observer: Any?
    private var endToken: NSObjectProtocol?
    private var statusObservation: NSKeyValueObservation?
    private var playbackRate: Float = 1
    private var resumeAfterPause = false
    private var sourceGeneration: UInt64 = 0
    private var cachePolicy = 1
    private var cacheKey: String?
    private var cacheMaxAgeMs: Int64 = 0
    private var cacheMaxBytes: Int64 = 0
    private var cacheChecksum: String?
    private var cachePinned = false
    private var streamingCache = false
    private var downloadWhilePlaying = false
    var onReady: (() -> Void)?
    var onProgress: ((Double, Double) -> Void)?
    var onEnd: (() -> Void)?
    var onError: ((String) -> Void)?
    var onCacheHit: ((String) -> Void)?
    var onCacheMiss: ((String) -> Void)?
    var onCacheReady: ((String, Int64) -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        controller.view.frame = bounds
        controller.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(controller.view)
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(togglePlayback)))
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit { releasePlayer() }

    func setSource(_ value: String) {
        guard value != source else { return }
        source = value
        sourceGeneration &+= 1
        releasePlayer()
        guard !value.isEmpty else { return }
        let url: URL
        if let candidate = URL(string: value), candidate.scheme != nil {
            url = candidate
        } else {
            let root = FileManager.default.urls(
                for: .applicationSupportDirectory,
                in: .userDomainMask
            )[0].appendingPathComponent("pam-files", isDirectory: true)
            let local = root.appendingPathComponent(value).standardizedFileURL
            guard local.path.hasPrefix(root.standardizedFileURL.path + "/") else {
                onError?("Media path escapes the application sandbox")
                return
            }
            url = local
        }
        let generation = sourceGeneration
        let remote = url.scheme == "https" || url.scheme == "http"
        if remote && cachePolicy >= 3 {
            let identity = PamMediaDiskCache.shared.identity(source: value, stableKey: cacheKey)
            if streamingCache || downloadWhilePlaying {
                installPlayer(url)
            }
            PamMediaDiskCache.shared.mediaURL(
                source: value,
                stableKey: cacheKey,
                maxAgeMs: cacheMaxAgeMs,
                maximumBytes: cacheMaxBytes,
                checksum: cacheChecksum,
                pinned: cachePinned,
                cacheOnly: cachePolicy == 7
            ) { [weak self] result in
                DispatchQueue.main.async {
                    guard let self, self.sourceGeneration == generation else { return }
                    switch result {
                    case let .success(resolution):
                        if resolution.hit {
                            self.onCacheHit?(identity)
                        } else {
                            self.onCacheMiss?(identity)
                        }
                        let size = (try? resolution.url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                        self.onCacheReady?(identity, Int64(size))
                        if !self.streamingCache && !self.downloadWhilePlaying {
                            self.installPlayer(resolution.url)
                        }
                    case let .failure(error):
                        if self.cachePolicy == 7 {
                            self.onError?(error.localizedDescription)
                        } else if !self.streamingCache && !self.downloadWhilePlaying {
                            self.installPlayer(url)
                        }
                    }
                }
            }
            return
        }
        installPlayer(url)
    }

    func setCache(
        policy: Int,
        key: String?,
        maxAgeMs: Int64,
        maxBytes: Int64,
        checksum: String?,
        pinned: Bool,
        streaming: Bool,
        downloadWhilePlaying: Bool
    ) {
        cachePolicy = policy
        cacheKey = key
        cacheMaxAgeMs = maxAgeMs
        cacheMaxBytes = maxBytes
        cacheChecksum = checksum
        cachePinned = pinned
        streamingCache = streaming
        self.downloadWhilePlaying = downloadWhilePlaying
    }

    private func installPlayer(_ url: URL) {
        releasePlayer()
        let item = AVPlayerItem(url: url)
        let next = AVPlayer(playerItem: item)
        player = next
        controller.player = next
        next.isMuted = muted
        next.volume = volume
        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self, self.player?.currentItem === item else { return }
                switch item.status {
                case .readyToPlay:
                    if self.currentTime > 0 {
                        self.seek(self.currentTime)
                    }
                    self.onReady?()
                    if self.autoPlay { self.play() }
                case .failed:
                    self.onError?(item.error?.localizedDescription ?? "Media could not be loaded")
                case .unknown:
                    break
                @unknown default:
                    self.onError?("Media entered an unsupported state")
                }
            }
        }
        observer = next.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            guard let self, let item = self.player?.currentItem else { return }
            let duration = item.duration.seconds
            self.onProgress?(time.seconds.isFinite ? time.seconds : 0, duration.isFinite ? duration : 0)
        }
        endToken = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: next.currentItem,
            queue: .main
        ) { [weak self] _ in
            guard let self else { return }
            self.onEnd?()
            if self.looping {
                self.player?.seek(to: .zero)
                self.player?.play()
            }
        }
    }

    func setAutoPlay(_ value: Bool) {
        autoPlay = value
        guard player?.currentItem?.status == .readyToPlay else { return }
        if value { play() } else { pause() }
    }
    func setControls(_ value: Bool) { controller.showsPlaybackControls = value }
    func setResizeMode(_ value: Int) {
        controller.videoGravity = switch value {
        case 1: .resizeAspectFill
        case 3: .resize
        default: .resizeAspect
        }
    }
    func setLoop(_ value: Bool) { looping = value }
    func setMuted(_ value: Bool) { muted = value; player?.isMuted = value }
    func setVolume(_ value: Float) {
        volume = min(max(value, 0), 1)
        player?.volume = volume
    }
    func seek(_ seconds: Double) {
        currentTime = max(seconds, 0)
        player?.seek(to: CMTime(seconds: currentTime, preferredTimescale: 600))
    }
    func setPlaybackRate(_ value: Float) {
        playbackRate = min(max(value, 0.25), 4)
        if player?.timeControlStatus == .playing { player?.rate = playbackRate }
    }

    @objc private func togglePlayback() {
        if player?.timeControlStatus == .playing { player?.pause() } else { play() }
    }

    private func play() {
        player?.playImmediately(atRate: playbackRate)
    }

    func onHostPause() {
        resumeAfterPause = player?.timeControlStatus == .playing
        player?.pause()
    }

    func onHostResume() {
        if resumeAfterPause {
            resumeAfterPause = false
            play()
        }
    }

    private func releasePlayer() {
        if let observer, let player { player.removeTimeObserver(observer) }
        if let endToken { NotificationCenter.default.removeObserver(endToken) }
        observer = nil
        endToken = nil
        statusObservation?.invalidate()
        statusObservation = nil
        player?.pause()
        player = nil
        controller.player = nil
    }
}
