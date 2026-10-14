<?php

declare(strict_types=1);

namespace Pam\Native\Sync;

use Closure;
use InvalidArgumentException;

/**
 * Retry delay for OfflineMutationQueue::retry(): $baseMs × $factor^(attempt-1),
 * capped at $maxMs, with optional ±$jitter (0.0–1.0) spread. After
 * $maxAttempts sends a retry marks the mutation Failed instead.
 *
 *     new BackoffPolicy(baseMs: 2_000, maxMs: 1_800_000, maxAttempts: 12)
 */
final readonly class BackoffPolicy
{
    public function __construct(
        public int $baseMs = 1_000,
        public int $maxMs = 3_600_000,
        public float $factor = 2.0,
        public float $jitter = 0.0,
        public ?int $maxAttempts = null,
    ) {
        if ($baseMs < 0 || $maxMs < $baseMs || $factor < 1.0 || $jitter < 0.0 || $jitter > 1.0 || ($maxAttempts !== null && $maxAttempts < 1)) {
            throw new InvalidArgumentException('Backoff needs 0 <= baseMs <= maxMs, factor >= 1, jitter within 0..1 and maxAttempts >= 1.');
        }
    }

    /**
     * Delay before the next attempt after $attempts sends.
     *
     * @param (Closure(): float)|null $random returns 0.0–1.0; defaults to mt_rand
     */
    public function delayMs(int $attempts, ?Closure $random = null): int
    {
        $exponent = min(30, max(0, $attempts - 1));
        $delay = min((float) $this->maxMs, $this->baseMs * ($this->factor ** $exponent));
        if ($this->jitter > 0.0) {
            $unit = $random !== null ? $random() : mt_rand() / mt_getrandmax();
            $delay *= 1 + $this->jitter * (2 * max(0.0, min(1.0, $unit)) - 1);
        }

        return (int) max(0, min($this->maxMs, round($delay)));
    }

    public function exhausted(int $attempts): bool
    {
        return $this->maxAttempts !== null && $attempts >= $this->maxAttempts;
    }
}
