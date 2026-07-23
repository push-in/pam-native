<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class WindowMetrics
{
    public function __construct(
        public float $width,
        public float $height,
        public float $density,
    ) {
    }
}
