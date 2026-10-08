<?php

declare(strict_types=1);

namespace Pam\Native\System;

/**
 * Application text scale reported by Accessibility::textScale(). Text renders
 * at `appliedMultiplier * min(systemScale, appliedMaxSystemScale)` (an applied
 * cap of 0 leaves the system scale uncapped). A value stored with
 * Accessibility::setTextScale() takes effect on the next launch.
 */
final readonly class TextScale
{
    public function __construct(
        public float $multiplier = 1.0,
        public float $maxSystemScale = 0.0,
        public float $appliedMultiplier = 1.0,
        public float $appliedMaxSystemScale = 0.0,
        public float $systemScale = 1.0,
        public float $effectiveScale = 1.0,
    ) {
    }

    /** True when the stored value differs from the one this launch renders with. */
    public function pendingRestart(): bool
    {
        return abs($this->multiplier - $this->appliedMultiplier) > 1e-6
            || abs($this->maxSystemScale - $this->appliedMaxSystemScale) > 1e-6;
    }
}
