package dev.pam.nativeapp.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolHandshakeTest {
    @Test fun negotiatesCommonContractAndFailsClosed() {
        val peer = ProtocolHandshake(1, 1, 2, setOf("wire.binary.v1", "peer.future.v1"))
        val report = negotiateProtocol(peer)
        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(1, report.protocolVersion)
        assertEquals(setOf("wire.binary.v1"), report.capabilities)
        assertEquals(CompatibilityStatus.MISSING_CAPABILITY, report.require(setOf("renderer.gpu.v1")).status)
        assertEquals(CompatibilityStatus.ABI_MISMATCH, negotiateProtocol(peer.copy(abiVersion = 2)).status)
        assertEquals(CompatibilityStatus.PROTOCOL_MISMATCH,
            negotiateProtocol(peer.copy(minimumProtocolVersion = 2, maximumProtocolVersion = 3)).status)
        assertEquals(listOf(1, 2, 3, 4), CompatibilityStatus.entries.map(CompatibilityStatus::value))
    }
}
