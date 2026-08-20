package dev.pam.nativeapp

internal data class HotReloadTiming(
    val durationNanos: Long,
    val bundleBytes: Int,
    val failed: Boolean,
)

internal class HotReloadLatency {
    private var confirmedAtNanos = 0L
    private var bundleBytes = 0

    @Synchronized
    fun begin(confirmedAtNanos: Long, bundleBytes: Int) {
        require(confirmedAtNanos > 0) { "Hot reload confirmation time must be monotonic" }
        require(bundleBytes in 1..MAX_BUNDLE_BYTES) { "Hot reload bundle bytes are out of bounds" }
        this.confirmedAtNanos = confirmedAtNanos
        this.bundleBytes = bundleBytes
    }

    @Synchronized
    fun complete(completedAtNanos: Long, failed: Boolean): HotReloadTiming? {
        if (confirmedAtNanos == 0L) return null
        val duration = (completedAtNanos - confirmedAtNanos).coerceAtLeast(0)
        val timing = HotReloadTiming(duration, bundleBytes, failed)
        confirmedAtNanos = 0
        bundleBytes = 0
        return timing
    }

    private companion object {
        const val MAX_BUNDLE_BYTES = 16 * 1024 * 1024
    }
}
