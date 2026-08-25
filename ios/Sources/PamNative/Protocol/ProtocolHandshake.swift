import Foundation

public let PAM_ABI_VERSION = 1
public let PAM_MINIMUM_PROTOCOL_VERSION = 1
public let PAM_PROTOCOL_CAPABILITIES: Set<String> = [
    "compiler.freeze.v1", "plugins.composer.v1", "renderer.incremental.v1",
    "runtime.modules.v1", "wire.binary.v1",
]

public enum ProtocolCompatibilityStatus: Int {
    case compatible = 1
    case abiMismatch = 2
    case protocolMismatch = 3
    case missingCapability = 4
}

public struct ProtocolHandshake: Equatable {
    public let abiVersion: Int
    public let minimumProtocolVersion: Int
    public let maximumProtocolVersion: Int
    public let capabilities: Set<String>

    public init(abiVersion: Int, minimumProtocolVersion: Int, maximumProtocolVersion: Int, capabilities: Set<String>) throws {
        guard abiVersion > 0, minimumProtocolVersion > 0, maximumProtocolVersion >= minimumProtocolVersion,
              capabilities.count <= 256,
              capabilities.allSatisfy({ $0.range(of: "^[a-z][a-z0-9]*(?:[._-][a-z0-9]+){1,7}$", options: .regularExpression) != nil }) else {
            throw PamProtocolError.invalidProtocol("Invalid protocol handshake")
        }
        self.abiVersion = abiVersion
        self.minimumProtocolVersion = minimumProtocolVersion
        self.maximumProtocolVersion = maximumProtocolVersion
        self.capabilities = capabilities
    }

    public static func local() throws -> Self {
        try Self(abiVersion: PAM_ABI_VERSION, minimumProtocolVersion: PAM_MINIMUM_PROTOCOL_VERSION,
                 maximumProtocolVersion: PAM_PROTOCOL_VERSION, capabilities: PAM_PROTOCOL_CAPABILITIES)
    }
}

public struct ProtocolCompatibilityReport: Equatable {
    public let status: ProtocolCompatibilityStatus
    public let protocolVersion: Int?
    public let capabilities: Set<String>

    public func requiring(_ required: Set<String>) -> Self {
        guard status == .compatible, !capabilities.isSuperset(of: required) else { return self }
        return Self(status: .missingCapability, protocolVersion: nil, capabilities: [])
    }
}

public func negotiateProtocol(with peer: ProtocolHandshake) -> ProtocolCompatibilityReport {
    guard peer.abiVersion == PAM_ABI_VERSION else {
        return .init(status: .abiMismatch, protocolVersion: nil, capabilities: [])
    }
    let minimum = max(PAM_MINIMUM_PROTOCOL_VERSION, peer.minimumProtocolVersion)
    let maximum = min(PAM_PROTOCOL_VERSION, peer.maximumProtocolVersion)
    guard minimum <= maximum else {
        return .init(status: .protocolMismatch, protocolVersion: nil, capabilities: [])
    }
    return .init(status: .compatible, protocolVersion: maximum,
                 capabilities: PAM_PROTOCOL_CAPABILITIES.intersection(peer.capabilities))
}
