import XCTest
@testable import PamNative

final class ProtocolHandshakeTests: XCTestCase {
    func testNegotiatesCommonContractAndFailsClosed() throws {
        let peer = try ProtocolHandshake(abiVersion: 1, minimumProtocolVersion: 1,
                                         maximumProtocolVersion: 2,
                                         capabilities: ["wire.binary.v1", "peer.future.v1"])
        let report = negotiateProtocol(with: peer)
        XCTAssertEqual(report.status, .compatible)
        XCTAssertEqual(report.protocolVersion, 1)
        XCTAssertEqual(report.capabilities, ["wire.binary.v1"])
        XCTAssertEqual(report.requiring(["renderer.gpu.v1"]).status, .missingCapability)
        XCTAssertEqual(negotiateProtocol(with: try ProtocolHandshake(
            abiVersion: 2, minimumProtocolVersion: 1, maximumProtocolVersion: 1, capabilities: [])).status, .abiMismatch)
        XCTAssertEqual(negotiateProtocol(with: try ProtocolHandshake(
            abiVersion: 1, minimumProtocolVersion: 2, maximumProtocolVersion: 3, capabilities: [])).status, .protocolMismatch)
        XCTAssertEqual([1, 2, 3, 4], [ProtocolCompatibilityStatus.compatible.rawValue,
            ProtocolCompatibilityStatus.abiMismatch.rawValue,
            ProtocolCompatibilityStatus.protocolMismatch.rawValue,
            ProtocolCompatibilityStatus.missingCapability.rawValue])
    }

    func testExportsTheCanonicalRendererContractPhpReads() {
        // Same golden bytes as packages/native/tests/protocol_extension.php.
        XCTAssertEqual(
            pamRendererContractJSON(propertyCeiling: 540, extensionPropertyCeiling: 1001,
                                    eventCeiling: 79, extensionEventCeiling: 1000),
            "{\"abi\":1,\"events\":[79,1000],\"properties\":[540,1001],\"protocol\":[1,1]}"
        )
        XCTAssertTrue(pamRendererContractJSON().contains("\"properties\":[\(PAM_PROPERTY_CEILING),\(PAM_EXTENSION_PROPERTY_CEILING)]"))
    }
}
