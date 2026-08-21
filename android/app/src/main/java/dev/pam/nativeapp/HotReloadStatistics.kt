package dev.pam.nativeapp

internal data class HotReloadStatisticsSnapshot(
    val sampleCount: Int,
    val successfulCount: Int,
    val failureCount: Int,
    val p95DurationNanos: Long?,
    val p95BudgetNanos: Long,
) {
    val p95WithinBudget: Boolean?
        get() = p95DurationNanos?.let { it <= p95BudgetNanos }

    val failureRateBasisPoints: Int
        get() = if (sampleCount == 0) 0 else failureCount * 10_000 / sampleCount
}

internal class HotReloadStatistics(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val p95BudgetNanos: Long,
) {
    private val samples = ArrayDeque<HotReloadTiming>()

    init {
        require(capacity > 0) { "Hot reload statistics capacity must be positive" }
        require(p95BudgetNanos > 0) { "Hot reload p95 budget must be positive" }
    }

    @Synchronized
    fun record(timing: HotReloadTiming) {
        require(timing.durationNanos >= 0) { "Hot reload duration cannot be negative" }
        if (samples.size == capacity) samples.removeFirst()
        samples.addLast(timing)
    }

    @Synchronized
    fun snapshot(): HotReloadStatisticsSnapshot {
        val successfulDurations = samples
            .asSequence()
            .filterNot(HotReloadTiming::failed)
            .map(HotReloadTiming::durationNanos)
            .sorted()
            .toList()
        val failureCount = samples.count(HotReloadTiming::failed)
        val rank = if (successfulDurations.isEmpty()) {
            null
        } else {
            ((successfulDurations.size.toLong() * 95 + 99) / 100 - 1)
                .coerceAtLeast(0)
                .toInt()
        }
        return HotReloadStatisticsSnapshot(
            sampleCount = samples.size,
            successfulCount = successfulDurations.size,
            failureCount = failureCount,
            p95DurationNanos = rank?.let(successfulDurations::get),
            p95BudgetNanos = p95BudgetNanos,
        )
    }

    private companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
