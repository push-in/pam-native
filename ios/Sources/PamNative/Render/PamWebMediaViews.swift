import AVFoundation
import AVKit
import Foundation
import Photos
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
    private var bufferingObservation: NSKeyValueObservation?
    private var buffering = false
    private var playbackRate: Float = 1
    private var resumeAfterPause = false
    private var sourceGeneration: UInt64 = 0
    private var photoVideoRequestId: PHImageRequestID = PHInvalidImageRequestID
    private var cachePolicy = 1
    private var cacheKey: String?
    private var cacheMaxAgeMs: Int64 = 0
    private var cacheMaxBytes: Int64 = 0
    private var cacheChecksum: String?
    private var cachePinned = false
    private var streamingCache = false
    private var downloadWhilePlaying = false
    /// `preloadSeconds`: `AVPlayerItem.preferredForwardBufferDuration`
    /// (0 = the system chooses), the same forward buffer as Android.
    private var forwardBufferSeconds: Double = 0
    /// Natural width/height (pixels) and duration (seconds) — `MediaReadyEvent`.
    var onReady: ((Int, Int, Double) -> Void)?
    var onLoadStart: (() -> Void)?
    var onBuffering: ((Bool) -> Void)?
    var onProgress: ((Double, Double) -> Void)?
    var onEnd: (() -> Void)?
    var onError: ((String) -> Void)?
    var onCacheHit: ((String) -> Void)?
    var onCacheMiss: ((String) -> Void)?
    var onCacheReady: ((String, Int64) -> Void)?

    /// Covered-route policy (Android parity): a covered route frees its
    /// AVPlayer/decoder and recreates it at the same position when visible.
    private var keepAliveWhenCovered = false
    private(set) var releasedForCover = false
    private var resumeSource = ""
    private var resumeAt: Double = 0
    private var resumePlaying = false
    private var coverageObserver: NSObjectProtocol?
    /// Views bound to this player's `nativeRef` (`mediaClock`/`mediaProgress`), resolved by the renderer.
    var boundViews: (() -> [UIView])?

    override init(frame: CGRect) {
        super.init(frame: frame)
        coverageObserver = NotificationCenter.default.addObserver(
            forName: PamRouteCoverage.didChange, object: nil, queue: .main
        ) { [weak self] _ in self?.syncCoverage() }
        backgroundColor = .black
        controller.view.frame = bounds
        controller.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(controller.view)
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(togglePlayback)))
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        if let coverageObserver { NotificationCenter.default.removeObserver(coverageObserver) }
        releasePlayer()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        // Next turn: a reparent (remove + add) must not recreate the player.
        DispatchQueue.main.async { [weak self] in self?.syncCoverage() }
    }

    func setKeepAliveWhenCovered(_ value: Bool) {
        guard keepAliveWhenCovered != value else { return }
        keepAliveWhenCovered = value
        syncCoverage()
    }

    private func syncCoverage() {
        let covered = !keepAliveWhenCovered && !source.isEmpty && PamRouteCoverage.isCovered(self)
        guard covered != releasedForCover else { return }
        releasedForCover = covered
        if covered {
            if let player, player.currentItem?.status == .readyToPlay {
                resumeSource = source
                resumeAt = player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0
                resumePlaying = player.timeControlStatus != .paused
            }
            sourceGeneration &+= 1
            releasePlayer()
        } else {
            let current = source
            source = ""
            setSource(current)
        }
    }

    private func bindingTargets() -> [UIView] {
        guard let ref = pamNativeRef, let all = boundViews?(), !all.isEmpty else { return [] }
        return all.filter { $0.pamMediaClockRef == ref || $0.pamMediaProgressRef == ref }
    }

    /// `mediaClock` labels and `mediaProgress` fills follow the player natively.
    private func updateBindings(position: Double, duration: Double, animated: Bool) {
        guard let ref = pamNativeRef else { return }
        for target in bindingTargets() {
            if target.pamMediaClockRef == ref, let label = target as? UILabel {
                let text = PamMediaClock.format(target.pamMediaClockFormat, position: position, duration: duration)
                if label.attributedText?.string != text {
                    // Keep the label's styled attributes (font, color, tabular digits).
                    if let current = label.attributedText, current.length > 0 {
                        label.attributedText = NSAttributedString(
                            string: text,
                            attributes: current.attributes(at: 0, effectiveRange: nil)
                        )
                    } else {
                        label.text = text
                    }
                }
            }
            if target.pamMediaProgressRef == ref {
                let fraction = max(PamMediaClock.fraction(position: position, duration: duration), 0.0001)
                // Scale from the leading edge without moving the anchor point
                // (the renderer positions transformed views through center).
                let rtl = target.effectiveUserInterfaceLayoutDirection == .rightToLeft
                let shift = (1 - fraction) * target.bounds.width / 2 * (rtl ? 1 : -1)
                let transform = CGAffineTransform(translationX: shift, y: 0).scaledBy(x: fraction, y: 1)
                if animated {
                    UIView.animate(withDuration: 0.25, delay: 0, options: [.curveLinear, .beginFromCurrentState]) {
                        target.transform = transform
                    }
                } else {
                    target.transform = transform
                }
            }
        }
    }

    func setSource(_ value: String) {
        guard value != source else { return }
        source = value
        if value != resumeSource {
            resumeSource = ""
            resumeAt = 0
            resumePlaying = false
        }
        sourceGeneration &+= 1
        releasePlayer()
        guard !value.isEmpty else { return }
        // A covered route prepares its player when it is visible again.
        if window != nil, !keepAliveWhenCovered, PamRouteCoverage.isCovered(self) {
            releasedForCover = true
            return
        }
        if PamPhotoAssetURI.identifier(value) != nil {
            guard let asset = PamPhotoAssetURI.asset(value), asset.mediaType == .video else {
                onError?("Photo asset is unavailable or is not a video")
                return
            }
            let generation = sourceGeneration
            let options = PHVideoRequestOptions()
            options.isNetworkAccessAllowed = true
            photoVideoRequestId = PHImageManager.default().requestAVAsset(
                forVideo: asset, options: options
            ) { [weak self] video, _, info in
                DispatchQueue.main.async {
                    guard let self, self.sourceGeneration == generation else { return }
                    self.photoVideoRequestId = PHInvalidImageRequestID
                    if let video {
                        self.installPlayer(AVPlayerItem(asset: video))
                    } else {
                        self.onError?((info?[PHImageErrorKey] as? Error)?.localizedDescription
                            ?? "Photo video could not be loaded")
                    }
                }
            }
            return
        }
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
        installPlayer(AVPlayerItem(url: url))
    }

    /// Applies to the current item at once; later items get it on install.
    func setForwardBufferSeconds(_ seconds: Double) {
        let value = min(max(seconds, 0), 300)
        guard value != forwardBufferSeconds else { return }
        forwardBufferSeconds = value
        player?.currentItem?.preferredForwardBufferDuration = value
    }

    private func installPlayer(_ item: AVPlayerItem) {
        releasePlayer()
        item.preferredForwardBufferDuration = forwardBufferSeconds
        let next = AVPlayer(playerItem: item)
        player = next
        controller.player = next
        next.isMuted = muted
        next.volume = volume
        onLoadStart?()
        buffering = false
        bufferingObservation = next.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            DispatchQueue.main.async {
                guard let self, self.player === player else { return }
                let waiting = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
                guard waiting != self.buffering else { return }
                self.buffering = waiting
                self.onBuffering?(waiting)
            }
        }
        statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self, self.player?.currentItem === item else { return }
                switch item.status {
                case .readyToPlay:
                    let resuming = !self.resumeSource.isEmpty && self.resumeSource == self.source
                    let startAt = resuming ? self.resumeAt : self.currentTime
                    if startAt > 0 {
                        self.player?.seek(to: CMTime(seconds: startAt, preferredTimescale: 600))
                    }
                    let size = item.presentationSize
                    let duration = item.duration.seconds
                    self.onReady?(
                        Int(size.width.rounded()),
                        Int(size.height.rounded()),
                        duration.isFinite ? max(0, duration) : 0
                    )
                    self.updateBindings(position: startAt, duration: duration, animated: false)
                    if self.autoPlay || (resuming && self.resumePlaying) { self.play() }
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
            let position = time.seconds.isFinite ? time.seconds : 0
            self.onProgress?(position, duration.isFinite ? duration : 0)
            self.updateBindings(position: position, duration: duration, animated: true)
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
        if photoVideoRequestId != PHInvalidImageRequestID {
            PHImageManager.default().cancelImageRequest(photoVideoRequestId)
            photoVideoRequestId = PHInvalidImageRequestID
        }
        if let observer, let player { player.removeTimeObserver(observer) }
        if let endToken { NotificationCenter.default.removeObserver(endToken) }
        observer = nil
        endToken = nil
        statusObservation?.invalidate()
        statusObservation = nil
        bufferingObservation?.invalidate()
        bufferingObservation = nil
        player?.pause()
        player = nil
        controller.player = nil
    }
}
