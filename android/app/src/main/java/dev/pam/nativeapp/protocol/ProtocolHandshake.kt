package dev.pam.nativeapp.protocol

internal const val PAM_ABI_VERSION = 1
internal const val PAM_MINIMUM_PROTOCOL_VERSION = 1
internal val PAM_PROTOCOL_CAPABILITIES = sortedSetOf(
    "compiler.freeze.v1", "plugins.composer.v1", "renderer.incremental.v1",
    "runtime.modules.v1", "wire.binary.v1",
)

enum class CompatibilityStatus(val value: Int) { COMPATIBLE(1), ABI_MISMATCH(2), PROTOCOL_MISMATCH(3), MISSING_CAPABILITY(4) }

data class ProtocolHandshake(
    val abiVersion: Int,
    val minimumProtocolVersion: Int,
    val maximumProtocolVersion: Int,
    val capabilities: Set<String>,
) {
    init {
        require(abiVersion > 0 && minimumProtocolVersion > 0)
        require(maximumProtocolVersion >= minimumProtocolVersion)
        require(capabilities.size <= 256 && capabilities.all(CAPABILITY_PATTERN::matches))
    }
    companion object {
        private val CAPABILITY_PATTERN = Regex("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+){1,7}$")
        fun local() = ProtocolHandshake(PAM_ABI_VERSION, PAM_MINIMUM_PROTOCOL_VERSION,
            PAM_PROTOCOL_VERSION, PAM_PROTOCOL_CAPABILITIES)
    }
}

data class CompatibilityReport(
    val status: CompatibilityStatus,
    val protocolVersion: Int? = null,
    val capabilities: Set<String> = emptySet(),
) {
    fun require(required: Set<String>): CompatibilityReport =
        if (status == CompatibilityStatus.COMPATIBLE && !capabilities.containsAll(required))
            CompatibilityReport(CompatibilityStatus.MISSING_CAPABILITY) else this
}

fun negotiateProtocol(peer: ProtocolHandshake): CompatibilityReport {
    if (peer.abiVersion != PAM_ABI_VERSION) return CompatibilityReport(CompatibilityStatus.ABI_MISMATCH)
    val minimum = maxOf(PAM_MINIMUM_PROTOCOL_VERSION, peer.minimumProtocolVersion)
    val maximum = minOf(PAM_PROTOCOL_VERSION, peer.maximumProtocolVersion)
    if (minimum > maximum) return CompatibilityReport(CompatibilityStatus.PROTOCOL_MISMATCH)
    return CompatibilityReport(CompatibilityStatus.COMPATIBLE, maximum,
        PAM_PROTOCOL_CAPABILITIES.intersect(peer.capabilities).toSortedSet())
}
