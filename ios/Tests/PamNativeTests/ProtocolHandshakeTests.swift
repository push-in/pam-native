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
}
