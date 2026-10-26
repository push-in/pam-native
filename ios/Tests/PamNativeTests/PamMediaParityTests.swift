import AVFoundation
import AVKit
import UIKit
import XCTest
@testable import PamNative

/// MediaPlayer parity with Android `PamMediaView`: poster, no black box, no
/// play/pause tap fighting the app's gestures, a bare `AVPlayerLayer` unless
/// controls are shown, hidden release, and the 256 MB media disk budget.
@MainActor
final class PamMediaParityTests: XCTestCase {
    /// 2×2 opaque black PNG.
    private let poster = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAEElEQVR4nGNgYGD4D8UQBgAd9AP9yOH2qAAAAABJRU5ErkJggg=="

    func testTapDoesNotToggleAndTouchesReachTheApp() {
        let media = PamMediaView(frame: CGRect(x: 0, y: 0, width: 200, height: 120))
        XCTAssertTrue(media.gestureRecognizers?.isEmpty ?? true,
                      "no play/pause recognizer: reels/feed gestures own the tap")
        media.setControls(false)
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 300, height: 300))
        window.isHidden = false
        defer { window.isHidden = true }
        window.addSubview(media)
        let hit = media.hitTest(CGPoint(x: 100, y: 60), with: nil)
        XCTAssertTrue(hit === media, "the video surface never swallows the touch")
    }

    func testNoBlackBoxBeforeTheFirstFrame() {
        let media = PamMediaView(frame: CGRect(x: 0, y: 0, width: 200, height: 120))
        XCTAssertEqual(media.backgroundColor, .clear)
    }

    func testControlsFalseUsesABarePlayerLayer() throws {
        let url = try Self.makeVideo()
        let media = PamMediaView(frame: CGRect(x: 0, y: 0, width: 160, height: 90))
        media.setControls(false)
        media.setSource(url.absoluteString)
        XCTAssertTrue(media.hasPlayer)
        XCTAssertTrue(media.isUsingPlayerLayer)
        XCTAssertNil(media.controller, "no AVPlayerViewController per cell")
        media.setControls(true)
        XCTAssertNotNil(media.controller)
        XCTAssertFalse(media.isUsingPlayerLayer)
        XCTAssertTrue(media.controller?.player != nil)
        media.setControls(false)
        XCTAssertNil(media.controller)
        XCTAssertTrue(media.isUsingPlayerLayer)
    }

    func testThumbnailPosterShowsUntilTheFirstFrame() throws {
        let (_, renderer, view) = PamRenderTestSupport.render([
            PamConstants.mediaThumbnailSource: .text(poster),
            PamConstants.mediaControls: .flag(false),
        ], kind: .media)
        defer { renderer.close() }
        let media = try XCTUnwrap(view as? PamMediaView)
        media.layoutIfNeeded()
        XCTAssertNotNil(media.posterView.image, "thumbnail() decodes into the poster")
        XCTAssertTrue(media.isPosterVisible)

        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 360, height: 720))
        window.isHidden = false
        defer { window.isHidden = true }
        window.addSubview(media)
        media.setSource(try Self.makeVideo().absoluteString)
        XCTAssertTrue(media.isPosterVisible, "covers the surface while the video loads")
        waitUntil(timeout: 10) { media.videoReadyForDisplay }
        XCTAssertTrue(media.videoReadyForDisplay, "a paused video draws its first frame")
        XCTAssertFalse(media.isPosterVisible)

        renderer.commit([[.update(id: 2, key: PamConstants.mediaThumbnailSource, value: nil)]])
        XCTAssertNil(media.posterView.image)
    }

    func testHiddenPlayerIsReleasedAfterTheDelayAndRestored() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 300, height: 300))
        window.isHidden = false
        defer { window.isHidden = true }
        let tab = UIView(frame: window.bounds)
        window.addSubview(tab)
        let media = PamMediaView(frame: CGRect(x: 0, y: 0, width: 160, height: 90))
        media.setControls(false)
        tab.addSubview(media)
        media.setSource(try Self.makeVideo().absoluteString)
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertTrue(media.hasPlayer)

        // Hidden without a coverage notification (Android: isShown false).
        tab.isHidden = true
        XCTAssertFalse(media.checkHiddenRelease(now: 100))
        XCTAssertFalse(media.checkHiddenRelease(now: 104))
        XCTAssertTrue(media.hasPlayer, "a short hide keeps the decoder")
        XCTAssertTrue(media.checkHiddenRelease(now: 105))
        XCTAssertFalse(media.hasPlayer, "released after 5 s hidden")

        tab.isHidden = false
        media.setNeedsLayout()
        media.layoutIfNeeded()
        XCTAssertTrue(media.hasPlayer, "shown again: recreated at the same position")
    }

    func testVisiblePlayerIsNeverReleased() throws {
        let window = pamTestWindow(frame: CGRect(x: 0, y: 0, width: 300, height: 300))
        window.isHidden = false
        defer { window.isHidden = true }
        let media = PamMediaView(frame: CGRect(x: 0, y: 0, width: 160, height: 90))
        window.addSubview(media)
        media.setSource(try Self.makeVideo().absoluteString)
        XCTAssertFalse(media.checkHiddenRelease(now: 0))
        XCTAssertFalse(media.checkHiddenRelease(now: 60))
        XCTAssertTrue(media.hasPlayer)
    }

    func testWebMStickerKeepsThePosterAndReportsAnError() {
        let media = PamMediaView(frame: CGRect(x: 0, y: 0, width: 120, height: 120))
        var errors: [String] = []
        media.onError = { errors.append($0) }
        media.setSource("https://cdn.example.test/stickers/party.webm")
        XCTAssertFalse(media.hasPlayer, "no AVPlayer (and no download) for WebM")
        RunLoop.main.run(until: Date().addingTimeInterval(0.05))
        XCTAssertEqual(errors, [PamMediaView.unsupportedContainerMessage])
        XCTAssertFalse(PamMediaView.isUnsupportedContainer(URL(string: "https://cdn.example.test/a.mp4")!))
    }

    func testMediaDiskBudgetIs256MBAndKeepsTheNewestFile() {
        let mb: Int64 = 1_024 * 1_024
        XCTAssertEqual(PamMediaDiskCache.diskBudget(limit: 0), 256 * mb)
        XCTAssertEqual(PamMediaDiskCache.diskBudget(limit: 4_096 * mb), 256 * mb, "never above 256 MB")
        XCTAssertEqual(PamMediaDiskCache.diskBudget(limit: 1 * mb), 16 * mb)
        XCTAssertEqual(PamMediaDiskCache.diskBudget(limit: 64 * mb), 64 * mb)
        // Newest first: a 300 MB file just downloaded is never deleted.
        XCTAssertEqual(PamMediaDiskCache.evictions(
            sizesNewestFirst: [300 * mb, 10 * mb, 10 * mb],
            pinned: [false, true, false],
            limit: 0
        ), [2], "pinned files stay; older files go")
        XCTAssertEqual(PamMediaDiskCache.evictions(
            sizesNewestFirst: [100 * mb, 100 * mb, 50 * mb, 20 * mb],
            pinned: [false, false, false, false],
            limit: 0
        ), [3])
    }

    func testSmallBundledAssetsDecodeOnceAndStayCached() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("pam-assets-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let icon = directory.appendingPathComponent("icon.png")
        try XCTUnwrap(PamInlineImages.decode(poster)).write(to: icon)
        let source = "asset://icons/\(UUID()).png"
        XCTAssertNil(PamInlineImages.cachedAsset(source))
        let image = try XCTUnwrap(PamInlineImages.smallAsset(source, url: icon))
        XCTAssertTrue(PamInlineImages.cachedAsset(source) === image, "the next cell gets it without I/O")

        let photo = directory.appendingPathComponent("photo.png")
        let large = Data(count: PamInlineImages.assetSyncMaxBytes + 1)
        try large.write(to: photo)
        XCTAssertGreaterThan(large.count, PamInlineImages.assetSyncMaxBytes)
        XCTAssertNil(PamInlineImages.smallAsset("asset://photos/\(UUID()).png", url: photo),
                     "larger assets keep the regular path")
    }

    // MARK: Helpers

    private func waitUntil(timeout: TimeInterval, _ condition: () -> Bool) {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline {
            RunLoop.main.run(until: Date().addingTimeInterval(0.02))
        }
    }

    private static var videoURL: URL?

    /// One-second 64×64 H.264 clip (orange frames), written once per run.
    static func makeVideo() throws -> URL {
        if let videoURL, FileManager.default.fileExists(atPath: videoURL.path) { return videoURL }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("pam-media-parity.mp4")
        try? FileManager.default.removeItem(at: url)
        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: 64,
            AVVideoHeightKey: 64,
        ])
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: 64,
            kCVPixelBufferHeightKey as String: 64,
        ])
        writer.add(input)
        writer.startWriting()
        writer.startSession(atSourceTime: .zero)
        for index in 0..<10 {
            while !input.isReadyForMoreMediaData { RunLoop.current.run(until: Date().addingTimeInterval(0.01)) }
            var buffer: CVPixelBuffer?
            CVPixelBufferCreate(nil, 64, 64, kCVPixelFormatType_32BGRA, nil, &buffer)
            let pixels = try XCTUnwrap(buffer)
            CVPixelBufferLockBaseAddress(pixels, [])
            let base = CVPixelBufferGetBaseAddress(pixels)!.assumingMemoryBound(to: UInt8.self)
            for offset in stride(from: 0, to: CVPixelBufferGetBytesPerRow(pixels) * 64, by: 4) {
                base[offset] = 0x20; base[offset + 1] = 0x80; base[offset + 2] = 0xFF; base[offset + 3] = 0xFF
            }
            CVPixelBufferUnlockBaseAddress(pixels, [])
            adaptor.append(pixels, withPresentationTime: CMTime(value: CMTimeValue(index), timescale: 10))
        }
        input.markAsFinished()
        let done = DispatchSemaphore(value: 0)
        writer.finishWriting { done.signal() }
        done.wait()
        XCTAssertEqual(writer.status, .completed)
        videoURL = url
        return url
    }
}
