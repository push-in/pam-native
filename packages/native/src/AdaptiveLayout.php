<?php

declare(strict_types=1);

namespace Pam\Native;

final class AdaptiveLayout
{
    private function __construct()
    {
    }

    public static function classify(
        ?WindowMetrics $window = null,
        bool $television = false,
    ): DeviceClass {
        if ($television) {
            return DeviceClass::Television;
        }
        $width = ($window ?? App::windowMetrics())->width;
        return match (true) {
            $width >= 840.0 => DeviceClass::Expanded,
            $width >= 600.0 => DeviceClass::Medium,
            default => DeviceClass::Compact,
        };
    }

    /**
     * Material window height class: compact < 480, medium < 900, expanded.
     * Short windows (landscape phones, split screen) usually need a compact
     * header and scrollable content more than a different width layout.
     */
    public static function classifyHeight(?WindowMetrics $window = null): DeviceClass
    {
        $height = ($window ?? App::windowMetrics())->height;

        return match (true) {
            $height >= 900.0 => DeviceClass::Expanded,
            $height >= 480.0 => DeviceClass::Medium,
            default => DeviceClass::Compact,
        };
    }

    /**
     * CSS `clamp()` for values computed in PHP: $fraction of $extent (a window
     * or parent dimension) kept within [$min, $max]. The usual way to size a
     * stage, sheet or card from the window instead of a measured device:
     * `AdaptiveLayout::clamp($window->height, 0.42, 220, 420)`.
     */
    public static function clamp(float $extent, float $fraction, float $min, float $max): float
    {
        return max($min, min($max, $extent * $fraction));
    }

    /** @template T @param array<int, T> $variants @return T */
    public static function select(array $variants, ?WindowMetrics $window = null): mixed
    {
        $class = self::classify($window);
        return $variants[$class->value]
            ?? $variants[DeviceClass::Compact->value]
            ?? throw new \InvalidArgumentException('Adaptive variants require a compact fallback.');
    }
}
