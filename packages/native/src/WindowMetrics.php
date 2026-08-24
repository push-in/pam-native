<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class WindowMetrics
{
    public function __construct(
        public float $width,
        public float $height,
        public float $density,
        public UserInterfaceAppearance $appearance = UserInterfaceAppearance::Light,
        public float $fontScale = 1.0,
        public float $safeAreaTop = 0.0,
        public float $safeAreaRight = 0.0,
        public float $safeAreaBottom = 0.0,
        public float $safeAreaLeft = 0.0,
        public float $refreshRate = 60.0,
        public bool $reducedMotion = false,
        public string $deviceType = 'phone',
        public string $pointer = 'coarse',
        public string $inputMode = 'touch',
        public string $dynamicRange = 'standard',
        public string $displayMode = 'standalone',
        public string $foldPosture = 'flat',
        public float $memoryClass = 0.0,
        public float $performanceTier = 1.0,
    ) {
    }
}
