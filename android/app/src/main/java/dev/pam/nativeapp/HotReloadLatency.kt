package dev.pam.nativeapp

internal data class HotReloadTiming(
    val durationNanos: Long,
    val bundleBytes: Long,
    val failed: Boolean,
)

internal class HotReloadLatency {
    private var confirmedAtNanos = 0L
    private var bundleBytes = 0L

    @Synchronized
    fun begin(confirmedAtNanos: Long, bundleBytes: Long) {
        require(confirmedAtNanos > 0) { "Hot reload confirmation time must be monotonic" }
        // Transferred bytes: 0 when every edited file was already on the device.
        require(bundleBytes >= 0) { "Hot reload transferred bytes cannot be negative" }
        this.confirmedAtNanos = confirmedAtNanos
        this.bundleBytes = bundleBytes
    }

    @Synchronized
    fun complete(completedAtNanos: Long, failed: Boolean): HotReloadTiming? {
        if (confirmedAtNanos == 0L) return null
        val duration = (completedAtNanos - confirmedAtNanos).coerceAtLeast(0)
        val timing = HotReloadTiming(duration, bundleBytes, failed)
        confirmedAtNanos = 0
        bundleBytes = 0L
        return timing
    }
}
