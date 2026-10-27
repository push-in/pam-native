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

class RendererContractTest {
    @Test fun exportsTheCanonicalContractPhpReads() {
        // Same golden bytes as packages/native/tests/protocol_extension.php.
        assertEquals(
            "{\"abi\":1,\"events\":[79,1000],\"properties\":[540,1001],\"protocol\":[1,1]}",
            rendererContractJson((1..540).toList() + 1001, (1..79).toList()),
        )
        assertEquals(
            "{\"abi\":1,\"events\":[80,1000],\"properties\":[543,1000],\"protocol\":[1,1]}",
            rendererContractJson((1..543).toList(), (1..80).toList()),
        )
        val local = rendererContractJson()
        assertEquals(true, local.contains("\"properties\":[${PropKey.entries.filter { it.value < PAM_EXTENSION_BASE }.maxOf(PropKey::value)},"))
    }
}
