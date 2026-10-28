import Foundation
import os

/// Embedded PHP settings derived from the device, exported to the bridge as
/// environment variables before PHP starts (`pam_native_ios_bridge.cpp` reads
/// them in `apply_php_ini_defaults`). Mirrors the Android host:
/// - `memory_limit` from physical RAM (128/192/256M), the safety net of the
///   bounded PHP heap fix, capped by what jetsam still allows at boot;
/// - OPcache with a persistent file cache (no JIT: iOS forbids W^X), so a cold
///   start reuses the opcodes compiled by the previous launch.
public struct PamPhpRuntimeConfiguration: Equatable {
    public static let memoryLimitVariable = "PAM_NATIVE_MEMORY_LIMIT"
    public static let opcacheDirectoryVariable = "PAM_NATIVE_OPCACHE_DIR"
    public static let opcacheMemoryVariable = "PAM_NATIVE_OPCACHE_MEMORY"
    public static let immutableSourcesVariable = "PAM_NATIVE_IMMUTABLE_SOURCES"

    public let memoryLimitMegabytes: Int
    public let opcacheMemoryMegabytes: Int
    public let opcacheDirectory: String?
    public let immutableSources: Bool

    public init(
        memoryLimitMegabytes: Int,
        opcacheMemoryMegabytes: Int,
        opcacheDirectory: String?,
        immutableSources: Bool
    ) {
        self.memoryLimitMegabytes = memoryLimitMegabytes
        self.opcacheMemoryMegabytes = opcacheMemoryMegabytes
        self.opcacheDirectory = opcacheDirectory
        self.immutableSources = immutableSources
    }

    /// Android tiers: >= 6 GiB -> 256M, >= 3.5 GiB -> 192M, else 128M. When
    /// the process memory budget is known (`os_proc_available_memory`, 0 on
    /// the Simulator) the heap never takes more than a third of it, and never
    /// less than 128M (the floor the framework is validated with).
    public static func memoryLimitMegabytes(physicalMemoryBytes: UInt64, availableMemoryBytes: UInt64) -> Int {
        let ram = physicalMemoryBytes / 1_048_576
        let tier = ram >= 6 * 1_024 ? 256 : (ram >= 3 * 1_024 + 512 ? 192 : 128)
        guard availableMemoryBytes > 0 else { return tier }
        let budget = Int(availableMemoryBytes / 1_048_576 / 3)
        return max(128, min(tier, budget))
    }

    /// Shared memory reserved for compiled scripts. Only touched pages count
    /// toward the footprint; small devices still get a smaller reservation.
    public static func opcacheMemoryMegabytes(physicalMemoryBytes: UInt64) -> Int {
        physicalMemoryBytes / 1_048_576 >= 3 * 1_024 + 512 ? 128 : 64
    }

    public static func current(debugBuild: Bool, bundle: Bundle = .main) -> PamPhpRuntimeConfiguration {
        let physical = ProcessInfo.processInfo.physicalMemory
        return PamPhpRuntimeConfiguration(
            memoryLimitMegabytes: memoryLimitMegabytes(
                physicalMemoryBytes: physical,
                availableMemoryBytes: UInt64(os_proc_available_memory())
            ),
            opcacheMemoryMegabytes: opcacheMemoryMegabytes(physicalMemoryBytes: physical),
            opcacheDirectory: prepareOpcacheDirectory(stamp: buildStamp(bundle: bundle)),
            // Release sources never change under a path (read-only app bundle,
            // content-addressed ota-releases/<digest>); debug builds hot
            // reload in place and must revalidate every include.
            immutableSources: !debugBuild
        )
    }

    /// Exports the settings; an existing variable (Xcode scheme, XCUITest
    /// launch environment, `SIMCTL_CHILD_*`) always wins.
    public func export(environment: (String) -> String? = { ProcessInfo.processInfo.environment[$0] }) {
        func set(_ name: String, _ value: String) {
            if environment(name) == nil { setenv(name, value, 1) }
        }
        set(Self.memoryLimitVariable, "\(memoryLimitMegabytes)M")
        set(Self.opcacheMemoryVariable, "\(opcacheMemoryMegabytes)")
        set(Self.immutableSourcesVariable, immutableSources ? "1" : "0")
        if let opcacheDirectory { set(Self.opcacheDirectoryVariable, opcacheDirectory) }
        // Renderer contract handshake (see ProtocolHandshake.swift).
        set(PAM_RENDERER_CONTRACT_VARIABLE, pamRendererContractJSON())
    }

    /// File cache entries are keyed by absolute script path and release
    /// builds never stat a source (`validate_timestamps=0`), so the stamp
    /// must change whenever any byte of the PHP bundle does, not only when
    /// the install path or version moves: an in-place install (Xcode delta
    /// install, a re-uploaded build number) keeps the path and would run the
    /// old opcodes against the new native host. The CLI's content digest
    /// (`PamBundle/manifest.sha256`) is part of the stamp; a bundle without
    /// one falls back to the executable identity, which every rebuild moves.
    static func buildStamp(bundle: Bundle) -> String {
        let info = bundle.infoDictionary ?? [:]
        let version = info["CFBundleVersion"] as? String ?? ""
        let short = info["CFBundleShortVersionString"] as? String ?? ""
        return "\(bundle.bundlePath)|\(short)|\(version)|\(contentIdentity(bundle: bundle))"
    }

    static func contentIdentity(bundle: Bundle, fileManager: FileManager = .default) -> String {
        let manifest = bundle.bundleURL.appendingPathComponent("PamBundle/manifest.sha256", isDirectory: false)
        if let digest = manifestDigest(at: manifest) { return digest }
        guard let executable = bundle.executableURL,
              let attributes = try? fileManager.attributesOfItem(atPath: executable.path) else {
            return ""
        }
        let size = (attributes[.size] as? NSNumber)?.int64Value ?? 0
        let modified = (attributes[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        return "exe:\(size):\(Int64(modified * 1_000))"
    }

    /// First line of a CLI manifest when it is a SHA-256 hex digest.
    static func manifestDigest(at url: URL) -> String? {
        guard let text = try? String(contentsOf: url, encoding: .utf8),
              let line = text.split(whereSeparator: \.isNewline).first else { return nil }
        let digest = line.trimmingCharacters(in: .whitespaces).lowercased()
        guard digest.count == 64, digest.allSatisfy({ $0.isHexDigit }) else { return nil }
        return digest
    }

    static func prepareOpcacheDirectory(
        stamp: String,
        root: URL? = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first,
        fileManager: FileManager = .default
    ) -> String? {
        guard let root else { return nil }
        let base = root.appendingPathComponent("pam", isDirectory: true)
        let directory = base.appendingPathComponent("opcache", isDirectory: true)
        let stampFile = base.appendingPathComponent("opcache.stamp")
        let previous = try? String(contentsOf: stampFile, encoding: .utf8)
        if previous != stamp {
            try? fileManager.removeItem(at: directory)
        }
        do {
            try fileManager.createDirectory(
                at: directory,
                withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700]
            )
            if previous != stamp {
                try stamp.write(to: stampFile, atomically: true, encoding: .utf8)
            }
        } catch {
            return nil
        }
        return directory.path
    }
}

/// Milliseconds since the kernel started this process (cold start clock for
/// the `[PamNativePerf]` first frame line).
enum PamProcessClock {
    static func millisecondsSinceProcessStart(now: Date = Date()) -> Int? {
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        guard sysctl(&mib, u_int(mib.count), &info, &size, nil, 0) == 0 else { return nil }
        let start = info.kp_proc.p_un.__p_starttime
        let started = Double(start.tv_sec) + Double(start.tv_usec) / 1_000_000
        return Int((now.timeIntervalSince1970 - started) * 1_000)
    }
}
