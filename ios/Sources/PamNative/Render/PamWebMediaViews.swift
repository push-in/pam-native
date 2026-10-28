import AVFoundation
import AVKit
import Foundation
import Photos
import UIKit
import WebKit

/// Page ↔ host channel rules shared with Android (`PamWebBridge.kt`).
///
/// Pages post with `window.PamNative.postMessage(text)` or, for pages written
/// for react-native-webview, `window.ReactNativeWebView.postMessage(text)`;
/// both exist from document start. The host answers by changing
/// `injectedJavaScript`: a loaded page runs each new script once. Only the
/// main frame may post, and each message carries the page URL.
enum PamWebBridge {
    static let handlerName = "pam"
    static let maxMessageCharacters = 1_048_576
    static let documentStartScript = """
    window.PamNative = window.PamNative || {};
    window.PamNative.postMessage = function(value) {
        window.webkit.messageHandlers.pam.postMessage(String(value));
    };
    window.ReactNativeWebView = window.ReactNativeWebView || {
        postMessage: function(value) { window.PamNative.postMessage(value); }
    };
    """

    static func accepts(_ message: String, isMainFrame: Bool) -> Bool {
        isMainFrame && message.count <= maxMessageCharacters
    }

    static func runsOnChange(previous: String, next: String, pageLoaded: Bool) -> Bool {
        pageLoaded && !next.isEmpty && next != previous
    }
}

final class PamWebView: WKWebView, WKNavigationDelegate, WKScriptMessageHandler {
    var onLoad: (() -> Void)?
    var onError: ((String) -> Void)?
    /// Message text and the URL of the page that posted it.
    var onMessage: ((String, String) -> Void)?
    private var source = ""
    private var injectedJavaScript = ""
    private var javaScriptEnabled = true
    private var allowedHosts: Set<String> = []
    private var loadTimeout: DispatchWorkItem?
    private var pageLoaded = false

    init() {
        let configuration = WKWebViewConfiguration()
        configuration.userContentController.addUserScript(
            WKUserScript(
                source: PamWebBridge.documentStartScript,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: true
            )
        )
        // Hardened defaults: no data detectors, no popups.
        configuration.dataDetectorTypes = []
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        super.init(frame: .zero, configuration: configuration)
        navigationDelegate = self
        // No link previews (long press) and no back/forward swipes inside the page.
        allowsLinkPreview = false
        allowsBackForwardNavigationGestures = false
        scrollView.contentInsetAdjustmentBehavior = .never
        configuration.userContentController.add(WeakWebMessageHandler(self), name: PamWebBridge.handlerName)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setSource(_ value: String) {
        guard value != source else { return }
        source = value
        pageLoaded = false
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

    func setInjectedJavaScript(_ value: String) {
        let runNow = javaScriptEnabled
            && PamWebBridge.runsOnChange(previous: injectedJavaScript, next: value, pageLoaded: pageLoaded)
        injectedJavaScript = value
        if runNow { evaluateJavaScript(value) }
    }

    func setAllowsInlineMedia(_ value: Bool) { configuration.allowsInlineMediaPlayback = value }

    /// false: the page never scrolls, bounces or zooms with the scroll view (a fixed page such as a game).
    func setScrollEnabled(_ value: Bool) {
        scrollView.isScrollEnabled = value
        scrollView.bounces = value
        scrollView.alwaysBounceVertical = false
        scrollView.alwaysBounceHorizontal = false
        scrollView.pinchGestureRecognizer?.isEnabled = value
    }

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

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        pageLoaded = false
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        cancelLoadTimeout()
        pageLoaded = true
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
        let text = String(describing: message.body)
        guard PamWebBridge.accepts(text, isMainFrame: message.frameInfo.isMainFrame) else { return }
        onMessage?(text, url?.absoluteString ?? "")
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

/// Video surface without player chrome: a bare `AVPlayerLayer`. The
/// heavier `AVPlayerViewController` (and its gesture recognizers) exists only
/// while `controls=true`, like Android's MediaController.
final class PamPlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

final class PamMediaView: UIView {
    /// A hidden (not covered) player keeps its decoder this long before it is
    /// released, Android `HIDDEN_RELEASE_MS`.
    static var hiddenReleaseDelay: TimeInterval = 5

    private let playerView = PamPlayerLayerView()
    private(set) var controller: AVPlayerViewController?
    /// Poster (`thumbnail()`), shown until the first video frame is drawn.
    let posterView = PamImageView()
    private var controls = true
    private var gravity: AVLayerVideoGravity = .resizeAspect
    private var readyForDisplayObservation: NSKeyValueObservation?
    private(set) var videoReadyForDisplay = false
    private var hiddenSince: CFTimeInterval?
    private var thumbnail: PamMediaThumbnail?
    private var thumbnailLoaded = false
    private var thumbnailGeneration: UInt64 = 0
    private var thumbnailRequestId: PHImageRequestID = PHInvalidImageRequestID
    /// Decodes local poster sources (asset://, pam-file://, data:), set by the renderer.
    var localImageResolver: ((String, CGSize) -> UIImage?)?
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
    /// `startBufferMs`: playback starts once this much media is loaded ahead
    /// of the playhead (ExoPlayer `bufferForPlaybackMs`); 0 = play at once.
    private var startBufferSeconds: Double = 0
    /// KVO on `loadedTimeRanges` while a play waits for the start buffer.
    private var startBufferObservation: NSKeyValueObservation?
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
    /// Background suspension (Android parity): the decoder is released while
    /// the app is not active and only a player with play intent comes back
    /// on resume; a paused one is restored on demand (play, seek).
    private(set) var releasedForBackground = false
    private(set) var pendingRestore = false
    /// Set by the renderer between `didEnterBackground` and `didBecomeActive`.
    static var appInBackground = false
    /// `idleSurface`: no AVPlayer or cache work while true; the view and its
    /// player layer stay mounted for the same cell to play again.
    private(set) var idleSurface = false
    private var coverageObserver: NSObjectProtocol?
    /// Views bound to this player's `nativeRef` (`mediaClock`/`mediaProgress`), resolved by the renderer.
    var boundViews: (() -> [UIView])?

    override init(frame: CGRect) {
        super.init(frame: frame)
        coverageObserver = NotificationCenter.default.addObserver(
            forName: PamRouteCoverage.didChange, object: nil, queue: .main
        ) { [weak self] _ in self?.syncCoverage() }
        // Transparent like Android's TextureView: no black box before the
        // first frame (the app's own background or the poster shows).
        backgroundColor = .clear
        clipsToBounds = true
        playerView.frame = bounds
        playerView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        playerView.isUserInteractionEnabled = false
        playerView.playerLayer.videoGravity = gravity
        addSubview(playerView)
        posterView.frame = bounds
        posterView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        posterView.isUserInteractionEnabled = false
        posterView.contentMode = .scaleAspectFit
        posterView.clipsToBounds = true
        posterView.isHidden = true
        addSubview(posterView)
        // No tap recognizer: like Android, a tap only shows the controls when
        // `controls=true` (AVPlayerViewController's own), and with
        // `controls=false` touches reach the app's Pressable/gestures
        // (reels mute, double-tap like, hold to pause) untouched.
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if thumbnail != nil, !thumbnailLoaded, bounds.width > 0, bounds.height > 0 {
            loadThumbnail()
        }
        if releasedForCover { syncCoverage() }
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        if let coverageObserver { NotificationCenter.default.removeObserver(coverageObserver) }
        if thumbnailRequestId != PHInvalidImageRequestID {
            PHImageManager.default().cancelImageRequest(thumbnailRequestId)
        }
        // No poster reload while deallocating: loadThumbnail() captures
        // `[weak self]`, and forming a weak reference to an object in deinit
        // aborts the process (a loops page recycled while its video played).
        releasePlayer(restoresPoster: false)
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
        hiddenSince = nil
        let covered = !keepAliveWhenCovered && !source.isEmpty && PamRouteCoverage.isCovered(self)
        guard covered != releasedForCover else { return }
        releasedForCover = covered
        if covered {
            rememberResumePoint()
            sourceGeneration &+= 1
            releasePlayer()
        } else if !releasedForBackground, !idleSurface {
            restorePlayer()
        }
    }

    func setIdleSurface(_ value: Bool) {
        guard idleSurface != value else { return }
        idleSurface = value
        if value {
            // An idle cell starts over when it plays again (no resume point).
            resumeSource = ""
            resumeAt = 0
            resumePlaying = false
            pendingRestore = false
            sourceGeneration &+= 1
            releasePlayer()
            return
        }
        if releasedForBackground {
            if !source.isEmpty { pendingRestore = true }
            return
        }
        guard !releasedForCover, !source.isEmpty else { return }
        restorePlayer()
    }

    /// True while the view keeps only its empty surface (tests, diagnostics).
    var holdsIdleSurface: Bool { idleSurface && player == nil && playerView.superview === self }

    /// Keeps the position and play intent of the player about to be released.
    private func rememberResumePoint() {
        guard let player, player.currentItem?.status == .readyToPlay else { return }
        resumeSource = source
        resumeAt = player.currentTime().seconds.isFinite ? player.currentTime().seconds : 0
        resumePlaying = player.timeControlStatus != .paused
    }

    /// Prepares the current source again (at the remembered position).
    private func restorePlayer() {
        pendingRestore = false
        let current = source
        source = ""
        setSource(current)
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
        pendingRestore = false
        guard !value.isEmpty else { return }
        // An idle surface prepares nothing until it is active again.
        if idleSurface { return }
        // A suspended app never allocates a decoder; resume prepares it.
        if Self.appInBackground, !keepAliveWhenCovered { releasedForBackground = true }
        if releasedForBackground {
            pendingRestore = true
            return
        }
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
        if Self.isUnsupportedContainer(url) {
            // AVFoundation has no WebM/Matroska demuxer (Android plays them
            // through ExoPlayer). No download, no player: the poster stays
            // and `on:error` lets the app show its fallback (WebM sticker).
            DispatchQueue.main.async { [weak self] in
                guard let self, self.sourceGeneration == generation else { return }
                self.onError?(Self.unsupportedContainerMessage)
            }
            return
        }
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

    static let unsupportedContainerMessage = "WebM video is not supported on iOS"

    /// WebM/Matroska sources (by extension): AVPlayer cannot open them.
    static func isUnsupportedContainer(_ url: URL) -> Bool {
        let ext = url.pathExtension.lowercased()
        return ext == "webm" || ext == "mkv"
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

    /// `startBufferMs`, applied to the next play.
    func setStartBufferMs(_ milliseconds: Int64) {
        startBufferSeconds = Double(min(max(milliseconds, 0), 30_000)) / 1_000
        if startBufferSeconds == 0, startBufferObservation != nil {
            cancelStartBuffer()
            play()
        }
    }

    private func installPlayer(_ item: AVPlayerItem) {
        releasePlayer()
        item.preferredForwardBufferDuration = forwardBufferSeconds
        let next = AVPlayer(playerItem: item)
        player = next
        attachSurface()
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
                    let willPlay = self.autoPlay || (resuming && self.resumePlaying)
                    if startAt > 0 || !willPlay {
                        // A paused player (autoPlay=false) draws the exact
                        // frame at currentTime (Android SEEK_CLOSEST).
                        self.player?.seek(
                            to: CMTime(seconds: startAt, preferredTimescale: 600),
                            toleranceBefore: willPlay ? .positiveInfinity : .zero,
                            toleranceAfter: willPlay ? .positiveInfinity : .zero
                        )
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
                    self.videoReadyForDisplay = false
                    self.updatePoster()
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
            if self.checkHiddenRelease() { return }
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
        if value, pendingRestore, !releasedForBackground, !releasedForCover, !idleSurface {
            restorePlayer()
            return
        }
        guard player?.currentItem?.status == .readyToPlay else { return }
        if value { play() } else { pause() }
    }
    func setControls(_ value: Bool) {
        guard controls != value else { return }
        controls = value
        attachSurface()
    }

    func setResizeMode(_ value: Int) {
        gravity = switch value {
        case 1: .resizeAspectFill
        case 3: .resize
        default: .resizeAspect
        }
        playerView.playerLayer.videoGravity = gravity
        controller?.videoGravity = gravity
        posterView.contentMode = switch value {
        case 1: .scaleAspectFill
        case 3: .scaleToFill
        default: .scaleAspectFit
        }
    }

    /// Puts the player on the bare layer (`controls=false`) or on an
    /// `AVPlayerViewController` created only for `controls=true`.
    private func attachSurface() {
        readyForDisplayObservation?.invalidate()
        readyForDisplayObservation = nil
        videoReadyForDisplay = false
        if controls, player != nil {
            let controller = controller ?? makeController()
            playerView.playerLayer.player = nil
            controller.player = player
            controller.videoGravity = gravity
            controller.view.isHidden = false
            readyForDisplayObservation = controller.observe(
                \.isReadyForDisplay, options: [.initial, .new]
            ) { [weak self] controller, _ in
                let ready = controller.isReadyForDisplay
                DispatchQueue.main.async { self?.setVideoReadyForDisplay(ready) }
            }
        } else {
            if let controller {
                controller.player = nil
                controller.view.removeFromSuperview()
                self.controller = nil
            }
            playerView.playerLayer.player = player
            if player != nil {
                readyForDisplayObservation = playerView.playerLayer.observe(
                    \.isReadyForDisplay, options: [.initial, .new]
                ) { [weak self] layer, _ in
                    let ready = layer.isReadyForDisplay
                    DispatchQueue.main.async { self?.setVideoReadyForDisplay(ready) }
                }
            }
        }
        updatePoster()
    }

    private func makeController() -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.showsPlaybackControls = true
        // No black box behind a letterboxed or loading video.
        controller.view.backgroundColor = .clear
        controller.view.frame = bounds
        controller.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        insertSubview(controller.view, belowSubview: posterView)
        self.controller = controller
        return controller
    }

    /// The player surface's `isReadyForDisplay` (KVO). Internal so tests
    /// drive the first frame signal without waiting on AVFoundation.
    func setVideoReadyForDisplay(_ ready: Bool) {
        guard player != nil else { return }
        videoReadyForDisplay = ready
        updatePoster()
    }

    /// The poster covers the video until its first frame (Android hides it in
    /// `onSurfaceTextureUpdated`) and comes back when the player is released.
    ///
    /// While the painted video covers it the poster bitmap is released (the
    /// decoded image is the largest allocation of a full-screen cell); it is
    /// decoded again from the media cache whenever it must cover the surface.
    private func updatePoster() {
        if player != nil && videoReadyForDisplay {
            posterView.isHidden = true
            if posterView.image != nil {
                posterView.image = nil
                thumbnailLoaded = false
            }
            return
        }
        posterView.isHidden = posterView.image == nil
        if posterView.image == nil, thumbnail != nil, !thumbnailLoaded, bounds.width > 0, bounds.height > 0 {
            loadThumbnail()
        }
    }

    /// True once the covered poster gave its bitmap back (tests, diagnostics).
    var isPosterReleased: Bool { thumbnail != nil && posterView.image == nil && !thumbnailLoaded }

    var isPosterVisible: Bool { !posterView.isHidden }

    // MARK: Poster (`thumbnail()`)

    func setThumbnail(_ source: String?, cacheKey: String?, policy: Int, maxAgeMs: Int64) {
        let next = source.flatMap { $0.isEmpty ? nil : PamMediaThumbnail(
            source: $0, cacheKey: cacheKey, policy: policy, maxAgeMs: maxAgeMs
        ) }
        guard next != thumbnail else { return }
        thumbnail = next
        thumbnailLoaded = false
        thumbnailGeneration &+= 1
        if thumbnailRequestId != PHInvalidImageRequestID {
            PHImageManager.default().cancelImageRequest(thumbnailRequestId)
            thumbnailRequestId = PHInvalidImageRequestID
        }
        posterView.image = nil
        updatePoster()
        if next != nil, !thumbnailLoaded, bounds.width > 0, bounds.height > 0 {
            loadThumbnail()
        }
    }

    private func loadThumbnail() {
        guard let thumbnail else { return }
        thumbnailLoaded = true
        let generation = thumbnailGeneration
        let size = bounds.size
        let deliver: (UIImage?) -> Void = { [weak self] image in
            guard let self, self.thumbnailGeneration == generation, let image else { return }
            self.posterView.image = image
            self.updatePoster()
        }
        let source = thumbnail.source
        if PamPhotoAssetURI.identifier(source) != nil {
            guard let asset = PamPhotoAssetURI.asset(source) else { return }
            let options = PHImageRequestOptions()
            options.isNetworkAccessAllowed = true
            options.deliveryMode = .highQualityFormat
            let scale = window?.screen.scale ?? UIScreen.main.scale
            thumbnailRequestId = PHImageManager.default().requestImage(
                for: asset,
                targetSize: CGSize(width: size.width * scale, height: size.height * scale),
                contentMode: .aspectFill,
                options: options
            ) { image, info in
                if info?[PHImageResultIsDegradedKey] as? Bool == true { return }
                DispatchQueue.main.async { deliver(image) }
            }
            return
        }
        guard let url = URL(string: source),
              let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http" else {
            deliver(localImageResolver?(source, size))
            return
        }
        let decode: (Data) -> UIImage? = { data in
            PamImageDownsampling.image(data: data, targetSize: size)
                ?? UIImage(data: data).map { $0.preparingForDisplay() ?? $0 }
        }
        let fetch: () -> Void = {
            URLSession.shared.dataTask(with: url) { data, response, _ in
                guard let data, !data.isEmpty,
                      (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? true else {
                    return
                }
                if thumbnail.policy >= 3 {
                    PamMediaDiskCache.shared.store(
                        data,
                        source: source,
                        stableKey: thumbnail.cacheKey,
                        checksum: nil,
                        limit: 0,
                        pinned: false
                    )
                }
                let image = decode(data)
                DispatchQueue.main.async { deliver(image) }
            }.resume()
        }
        guard thumbnail.policy >= 3 else {
            fetch()
            return
        }
        PamMediaDiskCache.shared.data(
            source: source,
            stableKey: thumbnail.cacheKey,
            maxAgeMs: thumbnail.maxAgeMs
        ) { data in
            guard let data else {
                if thumbnail.policy != 7 { fetch() }
                return
            }
            let image = decode(data)
            DispatchQueue.main.async { deliver(image) }
        }
    }

    // MARK: Hidden release

    /// A playing view that is hidden without a coverage notification (an
    /// ancestor hidden or made transparent, the view scrolled into a detached
    /// pager page) keeps decoding unseen: after `hiddenReleaseDelay` it is
    /// released like a covered route and comes back at the same position.
    /// Returns true when the player was released.
    @discardableResult
    func checkHiddenRelease(now: CFTimeInterval = CACurrentMediaTime()) -> Bool {
        guard !keepAliveWhenCovered, !source.isEmpty, PamRouteCoverage.isCovered(self) else {
            hiddenSince = nil
            return false
        }
        guard let since = hiddenSince else {
            hiddenSince = now
            return false
        }
        guard now - since >= Self.hiddenReleaseDelay else { return false }
        syncCoverage()
        return releasedForCover
    }
    func setLoop(_ value: Bool) { looping = value }
    func setMuted(_ value: Bool) { muted = value; player?.isMuted = value }
    func setVolume(_ value: Float) {
        volume = min(max(value, 0), 1)
        player?.volume = volume
    }
    func seek(_ seconds: Double) {
        currentTime = max(seconds, 0)
        if pendingRestore, !releasedForBackground, !releasedForCover, !idleSurface {
            // A seek on a suspended paused player draws that frame on demand.
            resumeSource = source
            resumeAt = currentTime
            restorePlayer()
            return
        }
        guard let player else { return }
        let time = CMTime(seconds: currentTime, preferredTimescale: 600)
        if player.timeControlStatus == .paused {
            // Paused: draw the exact frame (Android SEEK_CLOSEST).
            player.seek(to: time, toleranceBefore: .zero, toleranceAfter: .zero)
        } else {
            player.seek(to: time)
        }
    }
    func setPlaybackRate(_ value: Float) {
        playbackRate = min(max(value, 0.25), 4)
        if player?.timeControlStatus == .playing { player?.rate = playbackRate }
    }

    private func play() {
        guard let player else { return }
        guard startBufferSeconds > 0, let item = player.currentItem,
              pamBufferedAhead(item.loadedTimeRanges.map(\.timeRangeValue), at: item.currentTime().seconds)
                < startBufferSeconds,
              !item.isPlaybackBufferFull else {
            cancelStartBuffer()
            player.playImmediately(atRate: playbackRate)
            return
        }
        // Wait for the start buffer (event-driven, no polling); the stall is
        // reported like any other buffering.
        guard startBufferObservation == nil else { return }
        if !buffering {
            buffering = true
            onBuffering?(true)
        }
        startBufferObservation = item.observe(\.loadedTimeRanges, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard let self, self.player?.currentItem === item, self.startBufferObservation != nil else { return }
                let ahead = pamBufferedAhead(item.loadedTimeRanges.map(\.timeRangeValue), at: item.currentTime().seconds)
                guard ahead >= self.startBufferSeconds || item.isPlaybackBufferFull else { return }
                self.cancelStartBuffer()
                self.player?.playImmediately(atRate: self.playbackRate)
            }
        }
    }

    /// Pauses the player (and a play still waiting for its start buffer).
    private func pause() {
        if startBufferObservation != nil, buffering {
            buffering = false
            onBuffering?(false)
        }
        cancelStartBuffer()
        player?.pause()
    }

    private func cancelStartBuffer() {
        startBufferObservation?.invalidate()
        startBufferObservation = nil
    }

    /// App no longer active (system UI over it): the player only pauses.
    func onHostPause() {
        resumeAfterPause = resumeAfterPause || player?.timeControlStatus == .playing
            || startBufferObservation != nil
        pause()
    }

    /// App in background: unless `keepAliveWhenCovered`, the decoder is
    /// released, keeping position and play intent (Android parity).
    func onHostBackground() {
        guard !keepAliveWhenCovered, !releasedForBackground else { return }
        releasedForBackground = true
        guard !source.isEmpty else { return }
        if !releasedForCover {
            rememberResumePoint()
            if resumeSource != source {
                resumeSource = source
                resumeAt = currentTime
            }
            resumePlaying = resumeAfterPause
            sourceGeneration &+= 1
            releasePlayer()
        }
        resumeAfterPause = false
        pendingRestore = true
    }

    /// App active again: only a player that was playing (or asked to play)
    /// is prepared now; a paused one waits for play or seek.
    func onHostResume() {
        guard releasedForBackground else {
            if resumeAfterPause {
                resumeAfterPause = false
                play()
            }
            return
        }
        releasedForBackground = false
        guard pendingRestore, !releasedForCover, !idleSurface, !source.isEmpty else { return }
        if autoPlay || resumePlaying {
            restorePlayer()
        }
    }

    private func releasePlayer(restoresPoster: Bool = true) {
        cancelStartBuffer()
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
        readyForDisplayObservation?.invalidate()
        readyForDisplayObservation = nil
        videoReadyForDisplay = false
        playerView.playerLayer.player = nil
        controller?.player = nil
        if restoresPoster { updatePoster() }
    }

    /// Player state for tests.
    var hasPlayer: Bool { player != nil }
    var isUsingPlayerLayer: Bool { playerView.playerLayer.player != nil }
}

/// Poster request of a `PamMediaView` (`thumbnail()` and its cache options).
struct PamMediaThumbnail: Equatable, Sendable {
    let source: String
    let cacheKey: String?
    let policy: Int
    let maxAgeMs: Int64
}

/// Seconds of media loaded contiguously ahead of [position] in [ranges]
/// (`AVPlayerItem.loadedTimeRanges`); 0 when the playhead is outside them.
func pamBufferedAhead(_ ranges: [CMTimeRange], at position: Double) -> Double {
    let playhead = position.isFinite ? max(0, position) : 0
    for range in ranges {
        let start = range.start.seconds
        let end = (range.start + range.duration).seconds
        guard start.isFinite, end.isFinite else { continue }
        // A small tolerance: the playhead sits a frame before the range start after a seek.
        if playhead >= start - 0.05 && playhead < end {
            return end - playhead
        }
    }
    return 0
}
