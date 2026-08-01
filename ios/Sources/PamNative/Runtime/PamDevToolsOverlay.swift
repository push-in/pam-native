import Foundation
import UIKit

public enum RuntimeDiagnosticKind: Int {
    case moduleCall = 1
    case event = 2
    case error = 3
    case lifecycle = 4
}

public struct RuntimeDiagnostic {
    public let kind: RuntimeDiagnosticKind
    public let label: String
    public let durationNanos: Int64
    public let failed: Bool

    public init(
        kind: RuntimeDiagnosticKind,
        label: String,
        durationNanos: Int64 = 0,
        failed: Bool = false
    ) {
        self.kind = kind
        self.label = label
        self.durationNanos = durationNanos
        self.failed = failed
    }
}

@MainActor
public final class PamDevToolsOverlay: UIView {
    private let label = UILabel()
    private var displayLink: CADisplayLink?
    private var latestMetrics: RuntimeFrameMetrics?
    private var frameWindowStarted: CFTimeInterval = 0
    private var frameCount = 0
    private var smoothedFps = 0.0
    private var diagnostics: [RuntimeDiagnostic] = []

    public override init(frame: CGRect) {
        super.init(frame: frame)
        configure()
    }

    public required init?(coder: NSCoder) {
        super.init(coder: coder)
        configure()
    }

    public func update(_ metrics: RuntimeFrameMetrics) {
        latestMetrics = metrics
        if !isHidden {
            renderMetrics()
        }
    }

    public func record(_ diagnostic: RuntimeDiagnostic) {
        if diagnostics.count >= 8 { diagnostics.removeFirst() }
        diagnostics.append(diagnostic)
        if !isHidden { renderMetrics() }
    }

    @discardableResult
    public func toggle() -> Bool {
        setVisible(isHidden)
        return !isHidden
    }

    public func setVisible(_ visible: Bool) {
        isHidden = !visible
        isAccessibilityElement = visible
        if visible {
            frameWindowStarted = 0
            frameCount = 0
            let link = CADisplayLink(target: self, selector: #selector(frameDidRender(_:)))
            link.add(to: .main, forMode: .common)
            displayLink = link
            renderMetrics()
        } else {
            displayLink?.invalidate()
            displayLink = nil
        }
    }

    private func configure() {
        isHidden = true
        isUserInteractionEnabled = false
        accessibilityLabel = "PAM Native DevTools"
        backgroundColor = UIColor(red: 15 / 255, green: 23 / 255, blue: 42 / 255, alpha: 0.94)
        layer.cornerRadius = 14
        layer.borderWidth = 1
        layer.borderColor = UIColor(red: 96 / 255, green: 165 / 255, blue: 250 / 255, alpha: 0.45).cgColor

        label.translatesAutoresizingMaskIntoConstraints = false
        label.numberOfLines = 0
        label.textColor = .white
        label.font = UIFontMetrics(forTextStyle: .caption1).scaledFont(
            for: .monospacedSystemFont(ofSize: 11, weight: .medium)
        )
        label.adjustsFontForContentSizeCategory = true
        addSubview(label)
        NSLayoutConstraint.activate([
            label.topAnchor.constraint(equalTo: topAnchor, constant: 10),
            label.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 12),
            label.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -12),
            label.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -10),
        ])
    }

    @objc private func frameDidRender(_ link: CADisplayLink) {
        if frameWindowStarted == 0 {
            frameWindowStarted = link.timestamp
        }
        frameCount += 1
        let elapsed = link.timestamp - frameWindowStarted
        guard elapsed >= 0.5 else {
            return
        }
        let measured = Double(frameCount) / elapsed
        smoothedFps = smoothedFps == 0 ? measured : smoothedFps * 0.7 + measured * 0.3
        frameWindowStarted = link.timestamp
        frameCount = 0
        renderMetrics()
    }

    private func renderMetrics() {
        guard let metrics = latestMetrics else {
            label.text = "PAM  waiting for first frame..."
            accessibilityValue = label.text
            return
        }
        let summary = String(
            format: "PAM  %.0f fps\nmount %.2f ms  host decode %.2f ms\nengine p95 d/r/l/e %lld/%lld/%lld/%lld µs\nframes %lld  deadline misses %lld\nnodes %lld  batches %d\npatch %lld  full %lld  coalesced %lld\nbuffer reuse %lld · %.2f MiB\nretained %.2f MiB",
            smoothedFps,
            Double(metrics.mountNanos) / 1_000_000,
            Double(metrics.decodeNanos) / 1_000_000,
            metrics.stats.decodeP95Micros,
            metrics.stats.reconcileP95Micros,
            metrics.stats.layoutP95Micros,
            metrics.stats.encodeP95Micros,
            metrics.stats.measuredFrames,
            metrics.stats.deadlineMisses,
            metrics.stats.nodes,
            metrics.batches,
            metrics.stats.patchCommits,
            metrics.stats.fullCommits,
            metrics.stats.coalescedCommands,
            metrics.stats.bufferReuses,
            Double(metrics.stats.reusedBufferBytes) / (1024 * 1024),
            Double(metrics.stats.retainedBytes) / (1024 * 1024),
        )
        let timeline = diagnostics.map { item -> String in
            let prefix: String
            if item.failed {
                prefix = "FAIL"
            } else {
                switch item.kind {
                case .moduleCall: prefix = "CALL"
                case .event: prefix = "EVNT"
                case .error: prefix = "ERR "
                case .lifecycle: prefix = "LIFE"
                }
            }
            if item.durationNanos > 0 {
                return String(
                    format: "%@ %6.1fms  %@",
                    prefix,
                    Double(item.durationNanos) / 1_000_000,
                    item.label
                )
            }
            return "\(prefix)          \(item.label)"
        }.joined(separator: "\n")
        label.text = timeline.isEmpty ? summary : "\(summary)\n\nCAPABILITIES\n\(timeline)"
        accessibilityValue = label.text
    }
}
